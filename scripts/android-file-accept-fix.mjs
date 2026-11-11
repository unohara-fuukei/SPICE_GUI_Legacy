#!/usr/bin/env node
// Android WebView file-input crash fix (wry 0.55.1 RustWebChromeClient.kt):
// - accept containing extension entries (e.g. ".spiceproj") crashes the app:
//   MimeTypeMap returns null for unregistered extensions -> empty validTypes
//   -> validTypes[0] IndexOutOfBoundsException on the UI thread.
// - accept-less inputs can produce a null intent.type -> KotlinNullPointerException.
// Mitigation at the web layer: every file input gets a MIME-shaped accept.
// Pure-MIME accepts are kept; anything else (or missing) becomes */*.
import { readdirSync, readFileSync, writeFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = '/home/user/spice_gui/src';
const files = [];
(function walk(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p);
    else if (name.endsWith('.svelte')) files.push(p);
  }
})(ROOT);

let changedInputs = 0, changedFiles = 0;
for (const f of files) {
  let src = readFileSync(f, 'utf8');
  const before = src;

  // 1. rewrite accept="..." on file inputs
  src = src.replace(/(<input[^>]*type="file"[^>]*?)(\saccept="([^"]*)")/gs, (m, pre, attr, val) => {
    const items = val.split(',').map(s => s.trim()).filter(Boolean);
    const hasExt = items.some(i => !i.includes('/'));
    if (!hasExt && items.length) return m; // pure MIME, safe
    changedInputs++;
    return `${pre} accept="*/*"`;
  });
  // accept may appear before type="file" on some inputs; handle that order too
  src = src.replace(/(\saccept="([^"]*)")(?=[^>]*type="file")/gs, (m, attr, val) => {
    const items = val.split(',').map(s => s.trim()).filter(Boolean);
    const hasExt = items.some(i => !i.includes('/'));
    if (!hasExt && items.length) return m;
    if (attr.includes('*/*')) return m;
    changedInputs++;
    return ` accept="*/*"`;
  });

  // 2. add accept="*/*" to file inputs lacking accept entirely
  src = src.replace(/<input\b([^>]*?)type="file"([^>]*?)\/?>/gs, (m, pre, post) => {
    const whole = pre + post;
    if (/accept=/.test(whole)) return m;
    changedInputs++;
    return `<input${pre}type="file" accept="*/*"${post}/>`;
  });
  src = src.replace(/<input\b([^>]*?)type="file"([^>]*?)>/gs, (m, pre, post) => {
    const whole = pre + post;
    if (/accept=/.test(whole)) return m;
    if (m.includes('accept="*/*"')) return m;
    changedInputs++;
    return `<input${pre}type="file" accept="*/*"${post}>`;
  });

  if (src !== before) {
    writeFileSync(f, src);
    changedFiles++;
    console.log('patched', f);
  }
}
console.log(`done: ${changedInputs} inputs in ${changedFiles} files`);
