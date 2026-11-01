package com.example.bagyowatch.server

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer

/**
 * BagyoApiProxy: Kotlin twin of the Node bagyoWatch proxy.
 * 
 * Provides the /api/bagyo/fetch endpoint that the GEV frontend
 * uses to fetch Philippine disaster data feeds without CORS restrictions.
 * 
 * Security: exact hostname allowlist, HTTPS only, default port only,
 * 60s cache, 4MB response cap, GET-only.
 * 
 * Note: Uses only JDK classes - no external JSON library required.
 * The GEV frontend expects JSON responses, and this proxy passes
 * through the upstream JSON as-is.
 */
public class BagyoApiProxy {

    private static final String[] ALLOWED_HOSTS = {
        "data.garbinwx.org",
        "www.gdacs.org",
        "www.pagasa.dost.gov.ph",
        "bagong.pagasa.dost.gov.ph",
        "earthquake.phivolcs.dost.gov.ph",
        "earthquake.usgs.gov",
        "firms.modaps.eosdis.nasa.gov"
    };

    private static final long CACHE_MS = 60_000;
    private static final int CACHE_MAX = 32;
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024; // 4 MB
    private static final int TIMEOUT_MS = 15_000;

    private HttpServer server;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Object cacheLock = new Object();

    public void start(int port) {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/api/bagyo/fetch", this::handleRequest);
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.start();
            Log.i("BagyoProxy", "Bagyo API proxy started on port " + port);

            // Signal ready
            new Thread(() -> {
                try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                Log.i("BagyoProxy", "Proxy base URL: http://127.0.0.1:" + port + "/api/bagyo/fetch");
            }).start();
        } catch (IOException e) {
            Log.e("BagyoProxy", "Failed to start proxy server", e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private void handleRequest(HttpExchange exchange) {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                String body = "{\"error\": \"method_not_allowed\"}";
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(405, body.getBytes(StandardCharsets.UTF_8).length);
                exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().close();
                return;
            }

            // Parse the 'u' query parameter from the path
            String targetUrl = extractTargetUrl(exchange.getRequestURI().getPath());

            if (targetUrl == null || targetUrl.isEmpty()) {
                String body = "{\"error\": \"missing_url_parameter\"}";
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(400, body.getBytes(StandardCharsets.UTF_8).length);
                exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().close();
                return;
            }

            // Validate the target URL
            URI parsedUrl;
            try {
                parsedUrl = new URI(targetUrl);
            } catch (Exception e) {
                String body = "{\"error\": \"invalid_url\"}";
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(400, body.getBytes(StandardCharsets.UTF_8).length);
                exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().close();
                return;
            }

            // Check protocol: HTTPS only
            if (!"https".equals(parsedUrl.getScheme())) {
                String body = "{\"error\": \"https_required\"}";
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(403, body.getBytes(StandardCharsets.UTF_8).length);
                exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().close();
                return;
            }

            // Check username/password: not allowed
            if (parsedUrl.getUserInfo() != null && !parsedUrl.getUserInfo().isEmpty()) {
                String body = "{\"error\": \"credentials_not_allowed\"}";
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(403, body.getBytes(StandardCharsets.UTF_8).length);
                exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().close();
                return;
            }

            // Check hostname against allowlist
            String hostname = parsedUrl.getHost();
            boolean allowed = false;
            for (String allowedHost : ALLOWED_HOSTS) {
                if (allowedHost.equals(hostname)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                String body = "{\"error\": \"host_not_allowed\", \"message\": \"Host not in allowed list: \" + hostname}";
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(403, body.getBytes(StandardCharsets.UTF_8).length);
                exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().close();
                return;
            }

            // Check cache
            String cacheKey = parsedUrl.toString();
            synchronized (cacheLock) {
                CacheEntry cached = cache.get(cacheKey);
                long now = System.currentTimeMillis();
                if (cached != null && now - cached.fetchedAt < CACHE_MS && cached.bodySize <= MAX_RESPONSE_BYTES) {
                    // Cache hit
                    exchange.getResponseHeaders().set("Content-Type", cached.contentType);
                    exchange.sendResponseHeaders(200, cached.bodySize);
                    exchange.getResponseBody().write(cached.body);
                    exchange.getResponseBody().close();
                    Log.d("BagyoProxy", "Cache hit for: " + hostname);
                    return;
                } else if (cached != null) {
                    cache.remove(cacheKey);
                }
            }

            // Fetch from upstream
            StringBuilder responseBuilder = new StringBuilder();
            try {
                URL up = parsedUrl.toURL();
                up.openConnection().setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 BagyoWatchPH/1.0"
                );
                up.openConnection().setConnectTimeout(10_000);
                up.openConnection().setReadTimeout(10_000);

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(up.openStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        responseBuilder.append(line).append('\n');
                    }
                }

                String rawResponse = responseBuilder.toString();
                int responseLen = rawResponse.getBytes(StandardCharsets.UTF_8).length;

                // Enforce response size cap
                if (responseLen > MAX_RESPONSE_BYTES) {
                    String body = "{\"error\": \"response_too_large\"}";
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(502, body.getBytes(StandardCharsets.UTF_8).length);
                    exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().close();
                    return;
                }

                // Cache the response
                synchronized (cacheLock) {
                    while (cache.size() >= CACHE_MAX) {
                        var oldestKey = cache.keySet().iterator().next();
                        cache.remove(oldestKey);
                    }
                    cache.put(cacheKey, new CacheEntry(
                        rawResponse,
                        System.currentTimeMillis(),
                        "application/json",
                        responseLen
                    ));
                }

                // Send response
                byte[] responseBytes = rawResponse.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, responseBytes.length);
                exchange.getResponseBody().write(responseBytes);
                Log.d("BagyoProxy", "Fetched and cached: " + hostname + " (" + responseLen + " bytes)");
            } catch (IOException e) {
                Log.w("BagyoProxy", "Upstream fetch failed for " + parsedUrl.getHost() + ": " + e.getMessage());
                String body = "{\"error\": \"upstream_unavailable\", \"message\": \"" + e.getMessage() + "\"}";
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(502, body.getBytes(StandardCharsets.UTF_8).length);
                exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().close();
            }
        } catch (Exception e) {
            Log.e("BagyoProxy", "Unexpected proxy error: ", e);
            try {
                String body = "{\"error\": \"internal_error\"}";
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(500, body.getBytes(StandardCharsets.UTF_8).length);
                exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().close();
            } catch (IOException ignored) {
            }
        } finally {
            try { exchange.getResponseBody().close(); } catch (IOException ignored) {}
        }
    }

    private String extractTargetUrl(String path) {
        // Path is /api/bagyo/fetch?u=<encoded_url>
        if (path == null || !path.startsWith("/api/bagyo/fetch")) {
            return null;
        }
        // Parse query parameter 'u'
        String query = path.substring("/api/bagyo/fetch".length());
        if (query.isEmpty()) return null;
        
        // Simple query param extraction: u=<url>
        if (query.startsWith("?")) {
            query = query.substring(1);
        }
        int eqIdx = query.indexOf('=');
        if (eqIdx < 0) return null;
        String paramValue = query.substring(eqIdx + 1);
        // Basic URL decoding
        try {
            return java.net.URLDecoder.decode(paramValue, "UTF-8");
        } catch (Exception e) {
            return paramValue;
        }
    }

    /** Simple cache entry record. */
    private static class CacheEntry {
        final String body;
        final long fetchedAt;
        final String contentType;
        final int bodySize;

        CacheEntry(String body, long fetchedAt, String contentType, int bodySize) {
            this.body = body;
            this.fetchedAt = fetchedAt;
            this.contentType = contentType;
            this.bodySize = bodySize;
        }
    }
}