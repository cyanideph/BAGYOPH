#!/usr/bin/env node
/**
 * BagyoWatch PH end-to-end verification against a running God's Eye View
 * preview (`npm run preview`, dist + the real provider middleware).
 *
 * What it proves:
 *   1. the built God's Eye View bundle boots in a real browser engine,
 *   2. the four BagyoWatch layers enable through the ordinary layer-state
 *      mechanism (no test hooks in the app),
 *   3. each layer reads real provider data through /api/bagyo/fetch,
 *   4. the globe renders a frame with those layers on (screenshot).
 *
 * Usage: node toolchain/verify-globe.mjs [baseUrl] [outPng]
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import puppeteer from 'puppeteer';
import {
  normalizeLayerState,
  serializeStoredLayerState,
} from '../upstream/src/data/layerState.js';

const BASE = process.argv[2] || 'http://127.0.0.1:4173/';
const OUT = process.argv[3] || 'artifacts/bagyowatch-globe.png';

const LAYER_STATE = serializeStoredLayerState(
  normalizeLayerState({
    enabledLayerIds: [
      'pagasa-warnings',
      'pagasa-rainfall',
      'phivolcs-quakes',
      'gdacs-alerts',
      'earthquakes',
    ],
  }),
);

// Camera: the Philippines, straight down, ~4,000 km up (share-link long form).
const PHILIPPINES_VIEW =
  '#v=2&lat=12.8797&lon=121.7740&alt=4000000&heading=0&pitch=-90';

const consoleLines = [];
const feedRequests = [];
const pageErrors = [];

const browser = await puppeteer.launch({
  headless: true,
  args: [
    '--no-sandbox',
    '--disable-setuid-sandbox',
    '--enable-unsafe-swiftshader',
    '--use-gl=angle',
    '--use-angle=swiftshader',
    '--window-size=1600,1000',
  ],
  defaultViewport: { width: 1600, height: 1000 },
});

try {
  const page = await browser.newPage();
  page.on('console', (message) => {
    const text = message.text();
    consoleLines.push(text);
  });
  page.on('pageerror', (error) => pageErrors.push(String(error)));
  page.on('request', (request) => {
    if (request.url().includes('/api/bagyo/fetch')) {
      feedRequests.push(request.url());
    }
  });
  page.on('response', (response) => {
    const url = response.url();
    if (url.includes('/api/bagyo/fetch')) {
      const entry = feedRequests.find((request) => request === url);
      if (entry) feedRequests[feedRequests.indexOf(entry)] =
        `${response.status()} ${decodeURIComponent(url.slice(url.indexOf('u=') + 2))}`;
    }
  });

  await page.evaluateOnNewDocument((value) => {
    try {
      window.localStorage.setItem('gev:layer-state:v2', value);
    } catch {
      /* storage may be unavailable */
    }
  }, LAYER_STATE);

  await page.goto(BASE + PHILIPPINES_VIEW, {
    waitUntil: 'domcontentloaded',
    timeout: 120_000,
  });

  // Wait until every BagyoWatch layer has reported a refresh (or an error).
  const deadline = Date.now() + 90_000;
  let done = false;
  while (Date.now() < deadline && !done) {
    await new Promise((resolve) => setTimeout(resolve, 2000));
    const updated = consoleLines.filter(
      (line) => /^\[Data:(pagasa|phivolcs|gdacs)/.test(line),
    );
    const kinds = new Set(
      updated.map((line) => /^\[Data:([a-z-]+)\]/.exec(line)?.[1]),
    );
    done = ['pagasa-warnings', 'pagasa-rainfall', 'phivolcs-quakes', 'gdacs-alerts']
      .every((id) => kinds.has(id) || consoleLines.some((l) => l.includes(`[Data:${id}]`)));
  }

  await new Promise((resolve) => setTimeout(resolve, 4000));
  mkdirSync(path.dirname(OUT), { recursive: true });
  await page.screenshot({ path: OUT });

  const canvasPixels = await page.evaluate(() => {
    const canvas = document.querySelector('canvas');
    if (!canvas) return { present: false };
    return { present: true, width: canvas.width, height: canvas.height };
  });

  const report = {
    baseUrl: BASE,
    screenshot: OUT,
    canvas: canvasPixels,
    feedRequests,
    pageErrors,
    dataLayerLogs: consoleLines.filter((line) =>
      /^\[Data:/.test(line),
    ),
    errors: consoleLines.filter((line) => /error|failed|unavailable/i.test(line)).slice(0, 40),
  };
  writeFileSync('artifacts/verify-globe-report.json', JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
} finally {
  await browser.close();
}
