package com.example.bagyowatch.webview

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.scope.functions.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.bagyowatch.server.BagyoApiProxy

/**
 * MainActivity hosts the God's Eye View application via WebView,
 * with a native Compose overlay for layer controls and settings.
 * The WebView loads the GEV app from assets and communicates
 * with a local Kotlin HTTP server proxy for disaster data feeds.
 */
public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private BagyoApiProxy apiProxy;
    private boolean isProxyRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Set content view to WebView
        webView = new WebView(this);
        setContentView(webView);

        // Initialize the API proxy (Kotlin twin of the Node bagyoWatch proxy)
        apiProxy = new BagyoApiProxy(this);

        // WebView configuration
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setLoadsImagesAutomatically(true);
        webView.setScrollBarStyle(WebView.SCROLLBARS_OUTSIDE_OVERLAY);
        webView.setWebViewClient(new GeveWebViewClient());

        // Load the GEV app from assets
        webView.loadUrl("file:///android_asset/index.html");

        // Initialize API proxy (starts local server)
        if (!isProxyRunning) {
            apiProxy.start();
            isProxyRunning = true;
        }

        // Set up back navigation handling
        OnBackPressedCallback callback = new OnBackPressedCallback(true) {
            @Override
            public void handle() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    // Exit the application
                    finishAffinity();
                }
            }
        };
        getOnBackPressedDispatcher().addCallback(this, callback);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private class GeveWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            // Handle layer-state share links
            if (url.startsWith("gev:")) {
                // Parse the share link and restore camera/layer state
                String shareData = url.substring(4);
                // Could update layer state via JavaScript bridge
                Log.d("BagyoWatch", "GEV share link: " + shareData);
                return true;
            }

            // Handle content:// and other schemes
            if (url.startsWith("content://") || url.startsWith("file://")) {
                return super.shouldOverrideUrlLoading(view, url);
            }

            // Regular HTTP/HTTPS - let WebView handle it
            return super.shouldOverrideUrlLoading(view, url);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            Log.d("BagyoWatch", "GEV app loaded: " + url);

            // Inject layer state and initialize the app
            String initScript =
                "(() => {\n" +
                "  // Initialize BagyoWatch layer state\n" +
                "  const defaultState = {\n" +
                "    enabledLayerIds: ['pagasa-warnings', 'pagasa-rainfall', 'phivolcs-quakes', 'gdacs-alerts', 'earthquakes']\n" +
                "  };\n" +
                "  window.dispatchEvent(new CustomEvent('bagyowatch:init', { detail: defaultState }));\n" +
                "})();";

            webView.evaluateJavaScript(initScript, null);
        }

        @Override
        public void onReceivedJsError(WebView view, int errorCode, String description, String failingUrl) {
            super.onReceivedJsError(view, errorCode, description, failingUrl);
            Log.e("BagyoWatch", "WebView JS error: " + description + " (code: " + errorCode + ")");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (apiProxy != null && isProxyRunning) {
            apiProxy.stop();
            isProxyRunning = false;
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
    }

    // Compose overlay for layer controls - hosted as a separate window or embed
    @Composable
    fun LayerControlPanel(onLayerToggle: (String) -> Unit) {
        MaterialTheme.theme {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = "BagyoWatch PH Layers", style = MaterialTheme.typography.h6)
                    // Layer toggle buttons would go here
                    // Each layer button calls onLayerToggle with the layer id
                }
            }
        }
    }
}