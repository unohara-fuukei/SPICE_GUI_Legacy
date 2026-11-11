import { defineConfig } from "vite";
import { sveltekit } from "@sveltejs/kit/vite";
import { paraglideVitePlugin } from "@inlang/paraglide-js";

const host = process.env.TAURI_DEV_HOST;

// https://vite.dev/config/
export default defineConfig(async () => ({
  plugins: [
    sveltekit(),
    paraglideVitePlugin({
      project: './project.inlang',
      outdir: './src/lib/paraglide',
      // Tauri's webview rejects document.cookie, so localStorage must be first
      // for the language choice to survive a reload or app restart.
      strategy: ['localStorage', 'cookie', 'globalVariable', 'baseLocale'],
    }),
  ],

  // Batch workers use dynamic imports (UTIF), which requires ES module output.
  worker: {
    /** @type {'es'} */
    format: 'es',
  },

  // WebView 75 compat (Chromium 75.0.3770.156, Android 10-era devices):
  // esbuild lowers all syntax (optional chaining, nullish coalescing, class
  // static blocks, …) down to what Chromium 75 understands. Runtime APIs are
  // polyfilled by static/polyfills-core.js (core-js) loaded from app.html.
  // Module workers are NOT supported before Chromium 80 — the batch tooling
  // detects that at runtime and falls back to the main thread by design.
  build: {
    target: 'chrome75',
    cssTarget: 'chrome75',
  },

  // Vite options tailored for Tauri development and only applied in `tauri dev` or `tauri build`
  //
  // 1. prevent Vite from obscuring rust errors
  clearScreen: false,
  // 2. tauri expects a fixed port, fail if that port is not available
  server: {
    port: 1420,
    strictPort: true,
    host: host || false,
    hmr: host
      ? {
          protocol: "ws",
          host,
          port: 1421,
        }
      : undefined,
    watch: {
      // 3. tell Vite to ignore watching `src-tauri`
      ignored: ["**/src-tauri/**"],
    },
  },
}));
