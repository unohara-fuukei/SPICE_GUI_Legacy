// WebView 75 compatibility: ship core-js as a classic <script> loaded from
// app.html BEFORE any ES module executes, so every polyfill (Promise.allSettled
// [Chromium 76], String.replaceAll [85], Array.at [92], Object.hasOwn [93],
// structuredClone [98], …) is in place before the app bundle runs, no matter
// which chunk imports what.
//
// Runs as part of `prebuild`; copies core-js-bundle's minified UMD build into
// static/polyfills-core.js (adapter-static then ships it as a plain asset).
import fs from 'fs';
import path from 'path';

const src = path.resolve('node_modules/core-js-bundle/minified.js');
const dest = path.resolve('static/polyfills-core.js');

if (!fs.existsSync(src)) {
  console.error('❌ [webview75] core-js-bundle not installed — run pnpm install first');
  process.exit(1);
}
fs.mkdirSync(path.dirname(dest), { recursive: true });
fs.copyFileSync(src, dest);
console.log(`✅ [webview75] core-js polyfill bundle copied to static/polyfills-core.js (${(fs.statSync(dest).size / 1024).toFixed(0)} KiB)`);
