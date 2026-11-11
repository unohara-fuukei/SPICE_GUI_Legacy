// WebView 75 (Chromium 75.0.3770.156) CSS compatibility pass.
// Runs AFTER `vite build` over the emitted CSS in build/_app/** and rewrites
// it in place. Everything handled here has a static, semantics-preserving
// fallback; features that only degrade cosmetically are reported, not touched.
//
// Transforms:
//   1. `inset: <1-4 values>`          → top/right/bottom/left longhands (inset shorthand is Chromium 87+)
//   2. `clamp()/min()/max()`          → fallback declaration BEFORE the original
//                                        (old browsers drop the unknown value and
//                                        keep the fallback; modern browsers
//                                        override it with the real one)
//   3. flex rules that also set `gap` → appended `[style]-boosted` child margin
//                                        rules (flexbox gap is Chromium 84+;
//                                        grid gap is native since 57 and is left alone)
//   4. `:focus-visible`               → duplicate rule with `:focus`
//                                        (focus-visible is Chromium 86+)
//   6. `:where(.svelte-xxx)`          → hoisted to plain `.svelte-xxx`
//                                        (Svelte 5 zero-specificity scoping uses
//                                        :where(), Chromium 84+; on 75 the WHOLE
//                                        rule containing it is dropped, which
//                                        silently kills component styles like
//                                        the gene-page menu dropdown)
//   5. `color-mix()`                  → static inputs are computed at build
//                                        time; `var()` inputs become
//                                        `var(--cm75-<id>)` fallback declarations
//                                        (original kept for modern browsers) and
//                                        a runtime shim (build/webview75-colormix.js,
//                                        loaded from app.html) computes the values
//                                        on the elements that define the source
//                                        variables. color-mix is Chromium 111+.
//
// Reported only (graceful degradation on Chromium 75):
//   backdrop-filter (76+), color-scheme (81+), aspect-ratio (88+ — patched at
//   source), content-visibility (85+), text-wrap (114+), @layer (99+), :has() (105+)
import fs from 'fs';
import path from 'path';
import postcss from 'postcss';

const BUILD_DIR = path.resolve(process.cwd(), 'build');
const SHIM_OUT = path.join(BUILD_DIR, 'webview75-colormix.js');

function listCssFiles(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) listCssFiles(p, out);
    else if (entry.name.endsWith('.css')) out.push(p);
  }
  return out;
}

const stats = { inset: 0, clampMinMax: 0, flexGapRules: 0, focusVisible: 0, colorMixStatic: 0, colorMixShim: 0, whereHoisted: 0, warn: {} };
const warn = (key, detail) => {
  if (!stats.warn[key]) stats.warn[key] = { count: 0, sample: detail };
  stats.warn[key].count++;
};

// ---------- generic value scanning helpers ----------

const isIdentChar = (ch) => ch && /[\w-]/.test(ch);

// Find every top-level `fn(` call (paren-matched) in `value`. Never matches
// `minmax(` or identifiers ending in the function name.
function findCalls(value, fns) {
  const calls = [];
  let i = 0;
  while (i < value.length) {
    const slice = value.slice(i);
    let matched = null;
    for (const fn of fns) {
      if (slice.startsWith(fn + '(') && !isIdentChar(value[i - 1])) { matched = fn; break; }
    }
    if (matched) {
      let depth = 0, j = i + matched.length;
      for (; j < value.length; j++) {
        if (value[j] === '(') depth++;
        else if (value[j] === ')') { depth--; if (depth === 0) break; }
      }
      if (depth !== 0) return calls; // unbalanced — give up on the rest
      calls.push({ fn: matched, start: i, end: j + 1, inner: value.slice(i + matched.length + 1, j) });
      i = j + 1;
    } else i++;
  }
  return calls;
}

const splitTopLevel = (str, sep) => {
  const parts = [];
  let cur = '', d = 0;
  for (const ch of str) {
    if (ch === '(') d++;
    if (ch === ')') d--;
    if (ch === sep && d === 0) { parts.push(cur); cur = ''; } else cur += ch;
  }
  parts.push(cur);
  return parts;
};

// ---------- inset ----------

const LENGTH_SPLIT = /\s+(?![^()]*\))/;

function expandInset(decl) {
  const parts = decl.value.trim().split(LENGTH_SPLIT);
  if (parts.length === 0 || parts.length > 4) return;
  const [a, b, c, d] = parts;
  const top = a, right = b ?? a, bottom = c ?? a, left = d ?? b ?? a;
  decl.cloneBefore({ prop: 'top', value: top });
  decl.cloneBefore({ prop: 'right', value: right });
  decl.cloneBefore({ prop: 'bottom', value: bottom });
  decl.cloneBefore({ prop: 'left', value: left });
  decl.remove();
  stats.inset++;
}

// ---------- clamp / min / max ----------

function pickFallbackArg(args, kind) {
  if (kind === 'clamp') return (args[1] ?? args[0]).trim();
  const isFluid = (a) => /%|v[w|h]|calc\(/.test(a);
  if (kind === 'min') return (args.find(isFluid) ?? args[0]).trim(); // small-viewport bias
  // max(): on small viewports the absolute length is usually the winning one
  return (args.find((a) => !isFluid(a)) ?? args[0]).trim();
}

function lowerClampMinMax(value) {
  let out = '', i = 0, changed = false;
  for (const call of findCalls(value, ['clamp', 'min', 'max'])) {
    out += value.slice(i, call.start);
    const args = splitTopLevel(call.inner, ',').map((a) => a.trim()).filter(Boolean);
    if (call.fn === 'clamp' ? args.length === 3 : args.length >= 2) {
      out += pickFallbackArg(args, call.fn);
      changed = true;
    } else {
      out += value.slice(call.start, call.end); // leave malformed untouched
    }
    i = call.end;
  }
  out += value.slice(i);
  return changed ? out : null;
}

// ---------- color-mix ----------

// [r, g, b, a] in 0..1 floats, or null
function parseColor(s) {
  s = s.trim().toLowerCase();
  if (s === 'transparent') return [0, 0, 0, 0];
  const NAMED = { white: [1, 1, 1, 1], black: [0, 0, 0, 1], red: [1, 0, 0, 1], green: [0, 0.5, 0, 1], blue: [0, 0, 1, 1], gray: [0.5, 0.5, 0.5, 1], grey: [0.5, 0.5, 0.5, 1], silver: [0.75, 0.75, 0.75, 1], orange: [1, 0.647, 0, 1], yellow: [1, 1, 0, 1], purple: [0.5, 0, 0.5, 1], pink: [1, 0.753, 0.797, 1], cyan: [0, 1, 1, 1], magenta: [1, 0, 1, 1] };
  if (NAMED[s]) return NAMED[s].slice();
  let m;
  if ((m = /^#([0-9a-f]{3,8})$/.exec(s))) {
    const h = m[1];
    if (h.length === 3 || h.length === 4) {
      const r = parseInt(h[0] + h[0], 16) / 255, g = parseInt(h[1] + h[1], 16) / 255, b = parseInt(h[2] + h[2], 16) / 255;
      const a = h.length === 4 ? parseInt(h[3] + h[3], 16) / 255 : 1;
      return [r, g, b, a];
    }
    if (h.length === 6 || h.length === 8) {
      const r = parseInt(h.slice(0, 2), 16) / 255, g = parseInt(h.slice(2, 4), 16) / 255, b = parseInt(h.slice(4, 6), 16) / 255;
      const a = h.length === 8 ? parseInt(h.slice(6, 8), 16) / 255 : 1;
      return [r, g, b, a];
    }
    return null;
  }
  const num = (x) => parseFloat(x);
  const isPct = (x) => typeof x === 'string' && x.trim().endsWith('%');
  if ((m = /^rgba?\(([^)]*)\)$/.exec(s))) {
    const parts = splitTopLevel(m[1].replace(/\//g, ','), ',').map((p) => p.trim()).filter(Boolean);
    if (parts.length < 3) return null;
    const isPct = (x) => x.endsWith('%');
    const chan = (x) => (isPct(x) ? num(x) / 100 : num(x) / 255);
    let a = 1;
    if (parts.length >= 4) a = isPct(parts[3]) ? num(parts[3]) / 100 : num(parts[3]);
    return [chan(parts[0]), chan(parts[1]), chan(parts[2]), Math.min(1, Math.max(0, a))];
  }
  if ((m = /^hsla?\(([^)]*)\)$/.exec(s))) {
    const parts = splitTopLevel(m[1].replace(/\//g, ','), ',').map((p) => p.trim()).filter(Boolean);
    if (parts.length < 3) return null;
    const h = (((num(parts[0]) % 360) + 360) % 360) / 360;
    const sat = (isPct(parts[1]) ? num(parts[1]) : num(parts[1])) / 100;
    const lig = (parts[2].endsWith('%') ? num(parts[2]) : num(parts[2])) / 100;
    let a = 1;
    if (parts.length >= 4) a = parts[3].endsWith('%') ? num(parts[3]) / 100 : num(parts[3]);
    const f = (n) => {
      const k = (n + h * 12) % 12;
      const c = sat * Math.min(lig, 1 - lig);
      return lig - c * Math.max(-1, Math.min(k - 3, 9 - k, 1));
    };
    return [f(0), f(8), f(4), Math.min(1, Math.max(0, a))];
  }
  return null;
}
const isPctStr = (x) => /^[-+]?[0-9.]+%$/.test(x.trim());

// premultiplied-alpha srgb mixing per CSS Color 5 `color-mix(in srgb, …)`
function mixSrgb(c1, p1, c2, p2) {
  let a = p1 + p2;
  if (Math.abs(a) < 1e-9) return null;
  const w1 = p1 / a, w2 = p2 / a;
  const pm = [0, 1, 2].map((i) => c1[i] * c1[3] * w1 + c2[i] * c2[3] * w2);
  const alpha = c1[3] * w1 + c2[3] * w2;
  if (alpha <= 1e-9) return 'rgba(0, 0, 0, 0)';
  const ch = pm.map((v) => Math.round(Math.min(255, Math.max(0, (v / alpha) * 255))));
  const al = Math.round(alpha * 10000) / 10000;
  return `rgba(${ch[0]}, ${ch[1]}, ${ch[2]}, ${al})`;
}

// Parse the inside of a color-mix call: "in srgb, <color1> [p1]?, <color2> [p2]?"
// Returns { space, c1: {color, pct}, c2: {color, pct} } or null.
function parseColorMixInner(inner) {
  const parts = splitTopLevel(inner, ',').map((p) => p.trim()).filter(Boolean);
  if (parts.length < 3 || parts.length > 5) return null;
  if (!/^in\s+srgb$/.test(parts[0])) return null;
  const rest = parts.slice(1);
  // rest: color1 [pct]? color2 [pct]? — join then re-split on whitespace at top level
  const joined = rest.join(' ');
  const tokens = splitTopLevel(joined, ' ').map((t) => t.trim()).filter(Boolean);
  // Walk tokens: each color is one or more tokens (e.g. "var(--x, #fff)" is one
  // token because it has no top-level spaces; "hsl(0, 0%, 10%)" also single).
  let i = 0;
  const readColor = () => {
    if (i >= tokens.length) return null;
    const c = { color: tokens[i++], pct: null };
    if (i < tokens.length && isPctStr(tokens[i])) c.pct = parseFloat(tokens[i++]) / 100;
    return c;
  };
  const c1 = readColor();
  const c2 = readColor();
  if (!c1 || !c2 || i !== tokens.length) return null;
  // percentages: default 50/50; if exactly one given, the other is 1 - p
  if (c1.pct == null && c2.pct == null) { c1.pct = 0.5; c2.pct = 0.5; }
  else if (c1.pct != null && c2.pct == null) c2.pct = 1 - c1.pct;
  else if (c1.pct == null && c2.pct != null) c1.pct = 1 - c2.pct;
  return { c1, c2 };
}

// registry shared across all css files
const cmExprs = new Map(); // normalized inner → id
const varDefSelectors = new Map(); // custom prop name → Set(rule selector)

function registerVarDefs(rule) {
  rule.walkDecls((d) => {
    if (!d.prop.startsWith('--') || d.prop.startsWith('--cm75-')) return;
    if (!varDefSelectors.has(d.prop)) varDefSelectors.set(d.prop, new Set());
    varDefSelectors.get(d.prop).add(rule.selectors ? rule.selectors.join(', ') : rule.selector);
  });
}

function processColorMix(decl) {
  const calls = findCalls(decl.value, ['color-mix']);
  if (calls.length === 0) return;
  let rewritten = '';
  let i = 0;
  let anyVar = false;
  for (const call of calls) {
    rewritten += decl.value.slice(i, call.start);
    const parsed = parseColorMixInner(call.inner);
    if (parsed && !/var\(/.test(call.inner)) {
      // static: compute now
      const c1 = parseColor(parsed.c1.color), c2 = parseColor(parsed.c2.color);
      if (c1 && c2) {
        const mixed = mixSrgb(c1, parsed.c1.pct, c2, parsed.c2.pct);
        if (mixed) { rewritten += mixed; stats.colorMixStatic++; i = call.end; continue; }
      }
      warn('color-mix-unparseable', call.inner.slice(0, 60));
      rewritten += decl.value.slice(call.start, call.end);
      i = call.end;
      continue;
    }
    if (parsed) {
      // var-based: hand over to the runtime shim
      const norm = call.inner.replace(/\s+/g, ' ').trim();
      let id = cmExprs.get(norm);
      if (id == null) { id = cmExprs.size; cmExprs.set(norm, id); }
      rewritten += `var(--cm75-${id})`;
      stats.colorMixShim++;
      anyVar = true;
    } else {
      warn('color-mix-unparseable', call.inner.slice(0, 60));
      rewritten += decl.value.slice(call.start, call.end);
    }
    i = call.end;
  }
  rewritten += decl.value.slice(i);
  if (rewritten !== decl.value) {
    if (anyVar) {
      // fallback BEFORE the original: old browsers keep it, modern override it
      decl.cloneBefore({ prop: decl.prop, value: rewritten });
    } else {
      decl.value = rewritten;
    }
  }
}

// ---------- runtime shim generation ----------

function generateShim() {
  if (cmExprs.size === 0) {
    // keep the referenced file present (app.html loads it unconditionally)
    fs.writeFileSync(SHIM_OUT, '/* no color-mix() fallback needed */\n', 'utf8');
    return;
  }
  // which custom properties do the shim expressions read?
  const usedProps = new Set();
  for (const inner of cmExprs.keys()) {
    for (const m of inner.matchAll(/var\(\s*(--[^\s,)]+)/g)) usedProps.add(m[1]);
  }
  const defs = {};
  for (const p of usedProps) {
    const sels = varDefSelectors.get(p);
    if (sels && sels.size) defs[p] = [...sels];
  }
  const exprs = [...cmExprs.entries()].map(([inner, id]) => ({ id, inner }));
  const data = JSON.stringify({ defs, exprs });

  const shim = `/* GENERATED by scripts/webview75-css-compat.mjs — color-mix() fallback
 * for Chromium 75 WebView (color-mix is Chromium 111+). Computes every
 * var()-dependent color-mix() on the elements that define the source custom
 * properties and exposes the result as --cm75-<id> inline custom properties,
 * which the rewritten fallback declarations consume. Modern browsers exit
 * immediately and use the native color-mix() declarations kept in the CSS. */
(function () {
  if (window.CSS && window.CSS.supports && window.CSS.supports('color', 'color-mix(in srgb, red 10%, transparent)')) return;
  var DATA = ${data};
  var NAME_RE = /^var\\(\\s*(--[\\w-]+)(?:\\s*,\\s*([\\s\\S]*))?\\)$/;
  function parseColor(s) {
    s = String(s).trim().toLowerCase();
    if (!s) return null;
    if (s === 'transparent') return [0, 0, 0, 0];
    var NAMED = { white: [1, 1, 1, 1], black: [0, 0, 0, 1], red: [1, 0, 0, 1], green: [0, 0.5, 0, 1], blue: [0, 0, 1, 1], gray: [0.5, 0.5, 0.5, 1], grey: [0.5, 0.5, 0.5, 1], silver: [0.75, 0.75, 0.75, 1], orange: [1, 0.647, 0, 1], yellow: [1, 1, 0, 1], purple: [0.5, 0, 0.5, 1], pink: [1, 0.753, 0.797, 1], cyan: [0, 1, 1, 1], magenta: [1, 0, 1, 1] };
    if (NAMED[s]) return NAMED[s].slice();
    var m;
    if ((m = /^#([0-9a-f]{3,8})$/.exec(s))) {
      var h = m[1];
      if (h.length === 3 || h.length === 4) {
        var r3 = parseInt(h[0] + h[0], 16) / 255, g3 = parseInt(h[1] + h[1], 16) / 255, b3 = parseInt(h[2] + h[2], 16) / 255;
        return [r3, g3, b3, h.length === 4 ? parseInt(h[3] + h[3], 16) / 255 : 1];
      }
      if (h.length === 6 || h.length === 8) {
        return [parseInt(h.slice(0, 2), 16) / 255, parseInt(h.slice(2, 4), 16) / 255, parseInt(h.slice(4, 6), 16) / 255, h.length === 8 ? parseInt(h.slice(6, 8), 16) / 255 : 1];
      }
      return null;
    }
    function splitTop(str, sep) {
      var parts = [], cur = '', d = 0;
      for (var i = 0; i < str.length; i++) {
        var ch = str[i];
        if (ch === '(') d++;
        if (ch === ')') d--;
        if (ch === sep && d === 0) { parts.push(cur); cur = ''; } else cur += ch;
      }
      parts.push(cur);
      return parts;
    }
    if ((m = /^rgba?\\(([^)]*)\\)$/.exec(s))) {
      var p = splitTop(m[1].replace(/\\//g, ','), ',').map(function (x) { return x.trim(); }).filter(Boolean);
      if (p.length < 3) return null;
      function chan(x) { return x.indexOf('%') >= 0 ? parseFloat(x) / 100 : parseFloat(x) / 255; }
      var a = p.length >= 4 ? (p[3].indexOf('%') >= 0 ? parseFloat(p[3]) / 100 : parseFloat(p[3])) : 1;
      return [chan(p[0]), chan(p[1]), chan(p[2]), Math.min(1, Math.max(0, a))];
    }
    if ((m = /^hsla?\\(([^)]*)\\)$/.exec(s))) {
      var hp = splitTop(m[1].replace(/\\//g, ','), ',').map(function (x) { return x.trim(); }).filter(Boolean);
      if (hp.length < 3) return null;
      var hue = (((parseFloat(hp[0]) % 360) + 360) % 360) / 360;
      var sat = parseFloat(hp[1]) / 100;
      var lig = parseFloat(hp[2]) / 100;
      var ha = hp.length >= 4 ? (hp[3].indexOf('%') >= 0 ? parseFloat(hp[3]) / 100 : parseFloat(hp[3])) : 1;
      function f(n) {
        var k = (n + hue * 12) % 12;
        var c = sat * Math.min(lig, 1 - lig);
        return lig - c * Math.max(-1, Math.min(k - 3, 9 - k, 1));
      }
      return [f(0), f(8), f(4), Math.min(1, Math.max(0, ha))];
    }
    return null;
  }
  function resolveColor(token, cache) {
    token = token.trim();
    var vm = NAME_RE.exec(token);
    if (vm) {
      var v = cache[vm[1]];
      if (v != null && v !== '') {
        var c = parseColor(v);
        if (c) return c;
      }
      if (vm[2] != null) return parseColor(vm[2].replace(/\\)$/, '').trim());
      return null;
    }
    return parseColor(token);
  }
  function computeExpr(inner, cache) {
    var parts = splitTop(inner, ',');
    if (parts.length < 3) return null;
    if (!/^\\s*in\\s+srgb\\s*$/.test(parts[0])) return null;
    var tokens = splitTop(parts.slice(1).join(' '), ' ').map(function (t) { return t.trim(); }).filter(Boolean);
    var i = 0;
    function readColor() {
      if (i >= tokens.length) return null;
      var c = { color: tokens[i++], pct: null };
      if (i < tokens.length && /^[-+]?[0-9.]+%$/.test(tokens[i])) c.pct = parseFloat(tokens[i++]) / 100;
      return c;
    }
    var c1 = readColor(), c2 = readColor();
    if (!c1 || !c2 || i !== tokens.length) return null;
    if (c1.pct == null && c2.pct == null) { c1.pct = 0.5; c2.pct = 0.5; }
    else if (c1.pct != null && c2.pct == null) c2.pct = 1 - c1.pct;
    else if (c1.pct == null && c2.pct != null) c1.pct = 1 - c2.pct;
    var a = resolveColor(c1.color, cache), b = resolveColor(c2.color, cache);
    if (!a || !b) return null;
    var sum = c1.pct + c2.pct;
    if (Math.abs(sum) < 1e-9) return null;
    var w1 = c1.pct / sum, w2 = c2.pct / sum;
    var alpha = a[3] * w1 + b[3] * w2;
    if (alpha <= 1e-9) return 'rgba(0, 0, 0, 0)';
    var out = [0, 1, 2].map(function (k) { return Math.round(Math.min(255, Math.max(0, ((a[k] * a[3] * w1 + b[k] * b[3] * w2) / alpha) * 255))); });
    return 'rgba(' + out[0] + ', ' + out[1] + ', ' + out[2] + ', ' + (Math.round(alpha * 10000) / 10000) + ')';
  }
  function apply() {
    var els = [document.documentElement];
    var seen = [document.documentElement];
    for (var prop in DATA.defs) {
      var sels = DATA.defs[prop];
      for (var si = 0; si < sels.length; si++) {
        var found;
        try { found = document.querySelectorAll(sels[si]); } catch (e) { continue; }
        for (var fi = 0; fi < found.length; fi++) {
          if (seen.indexOf(found[fi]) < 0) { seen.push(found[fi]); els.push(found[fi]); }
        }
      }
    }
    var exprs = DATA.exprs;
    for (var ei = 0; ei < els.length; ei++) {
      var el = els[ei];
      var cs = getComputedStyle(el);
      var cache = {};
      for (var pi in DATA.defs) {
        try { cache[pi] = cs.getPropertyValue(pi); } catch (e) {}
      }
      var style = el.style;
      for (var xi = 0; xi < exprs.length; xi++) {
        var val = computeExpr(exprs[xi].inner, cache);
        if (val) style.setProperty('--cm75-' + exprs[xi].id, val);
      }
    }
  }
  var timer = null;
  function schedule() {
    if (timer) return;
    timer = setTimeout(function () { timer = null; try { apply(); } catch (e) {} }, 60);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', schedule);
  else schedule();
  try {
    new MutationObserver(schedule).observe(document.documentElement, { subtree: true, childList: true, attributes: true, attributeFilter: ['class', 'style'] });
  } catch (e) {}
})();
`;
  fs.writeFileSync(SHIM_OUT, shim, 'utf8');
}

// ---------- plugin ----------
//
// NOTE: postcss's sync visitor re-visits a rule whose tree was mutated during
// the walk (cloneBefore marks ancestors dirty), which makes insert-style
// fallbacks loop forever. We therefore parse with postcss but iterate the tree
// ourselves: first a pure analysis pass that collects planned operations on
// snapshotted node lists, then an apply pass that performs every mutation
// AFTER the walk is done.

const inKeyframes = (rule) => {
  let p = rule.parent;
  while (p) {
    if (p.type === 'atrule' && /keyframes/.test(p.name || '')) return true;
    p = p.parent;
  }
  return false;
};

// Iterate rules depth-first over a SNAPSHOT of every container's children, so
// mutations during the apply pass can never affect the walk.
function walkRules(container, cb) {
  for (const node of [...container.nodes]) {
    if (node.type === 'rule') cb(node);
    if (node.nodes) walkRules(node, cb);
  }
}

function analyzeRule(rule, ops) {
  // 5. register custom property definitions (pure data for the shim)
  registerVarDefs(rule);

  const decls = {};
  rule.walkDecls((d) => {
    decls[d.prop] = decls[d.prop] || [];
    decls[d.prop].push(d);
  });
  const first = (prop) => (decls[prop] || [])[0];

  // plan per-declaration transforms (analysis only — values are read, not written)
  for (const d of rule.nodes) {
    if (d.type !== 'decl') continue;
    if (d.prop === 'inset') ops.push({ kind: 'inset', decl: d });
    if (/color-mix\(/.test(d.value)) ops.push({ kind: 'color-mix', decl: d });
    if (/clamp\(|min\(|max\(/.test(d.value)) ops.push({ kind: 'clamp', decl: d });
  }

  // 4. :focus-visible → :focus duplicate (applied after decl ops so the clone
  //    carries the inserted fallbacks too)
  if (rule.selector.includes(':focus-visible')) {
    ops.push({ kind: 'focus-visible', rule });
  }

  // 3. flex + gap in the same rule → child margin rules (skip keyframes)
  const display = (decls['display'] || []).map((d) => d.value.trim());
  const isFlex = display.some((v) => v === 'flex' || v === 'inline-flex');
  const gapDecl = first('gap');
  if (isFlex && gapDecl && !inKeyframes(rule)) {
    const dirDecl = first('flex-direction');
    const direction = dirDecl ? dirDecl.value.trim() : 'row';
    const value = gapDecl.value.trim();
    let marginValue = value;
    const two = value.split(LENGTH_SPLIT);
    if (two.length === 2) marginValue = direction.includes('column') ? two[0] : two[1];
    if (!/%/.test(marginValue)) {
      const axis = direction.includes('column') ? 'margin-top' : 'margin-left';
      ops.push({ kind: 'flex-gap', rule, axis, marginValue: marginValue.trim(), direction });
    }
  }

  // warnings — graceful degradation, report only
  rule.walkDecls((d) => {
    if (d.prop.includes('backdrop-filter')) warn('backdrop-filter', `${d.prop}: ${d.value}`);
    if (d.prop === 'color-scheme') warn('color-scheme', d.value);
    if (d.prop === 'aspect-ratio') warn('aspect-ratio', d.value);
    if (d.prop === 'content-visibility') warn('content-visibility', d.value);
    if (d.prop.startsWith('text-wrap')) warn('text-wrap', d.value);
  });
  if (rule.selector.includes(':has(')) warn(':has()', rule.selector.slice(0, 60));
  // @layer is atrule-level, checked below
}

function applyOps(ops) {
  for (const op of ops) {
    if (op.kind === 'inset') {
      expandInset(op.decl);
    } else if (op.kind === 'color-mix') {
      processColorMix(op.decl);
    } else if (op.kind === 'clamp') {
      // computed from the CURRENT value (a color-mix rewrite may have replaced it)
      if (/clamp\(|min\(|max\(/.test(op.decl.value)) {
        const lowered = lowerClampMinMax(op.decl.value);
        if (lowered && lowered !== op.decl.value) {
          op.decl.cloneBefore({ prop: op.decl.prop, value: lowered });
          stats.clampMinMax++;
        }
      }
    } else if (op.kind === 'focus-visible') {
      const clone = op.rule.clone();
      clone.selector = clone.selector.split(',').map((s) => s.replaceAll(':focus-visible', ':focus')).join(',');
      op.rule.after(clone);
      stats.focusVisible++;
    } else if (op.kind === 'flex-gap') {
      const sel = op.rule.selectors.map((s) => `${s} > * + *`).join(', ');
      const fallback = postcss.rule({ selector: sel });
      fallback.append({ prop: op.axis, value: op.marginValue });
      if (op.direction === 'row-reverse') fallback.append({ prop: 'margin-left', value: '0' });
      if (op.direction === 'column-reverse') fallback.append({ prop: 'margin-top', value: '0' });
      op.rule.after(fallback);
      stats.flexGapRules++;
    }
  }
}

// ---------- run ----------

const files = listCssFiles(BUILD_DIR);
if (files.length === 0) {
  console.warn('⚠️ [webview75] no CSS files found under build/ — did vite build run?');
  process.exit(0);
}

let totalBytes = 0;
for (const file of files) {
  let css = fs.readFileSync(file, 'utf8');

  // 6. `:where(.svelte-xxx)` → `.svelte-xxx`. Svelte 5 scopes selectors with a
  //    zero-specificity `:where(.svelte-hash)`; Chromium 75 (no :where, 84+)
  //    drops the entire rule, so e.g. `.menu-item-container.menu-open.svelte-x
  //    .menu-dropdown:where(.svelte-x){display:flex}` never applies and menus
  //    stay hidden. All :where() usages in this build are single-class (audited),
  //    so hoisting is a pure mechanical rewrite. Specificity rises by (0,1,0),
  //    uniformly for every scoped rule, and only in the WebView-75 build.
  css = css.replace(/:where\((\.[A-Za-z0-9_-]+)\)/g, (_m, cls) => {
    stats.whereHoisted++;
    return cls;
  });

  const root = postcss.parse(css, { from: file });
  const ops = [];
  walkRules(root, (rule) => analyzeRule(rule, ops));
  for (const node of root.nodes || []) {
    if (node.type === 'atrule' && node.name === 'layer') warn('@layer', node.params);
  }
  applyOps(ops);
  const out = root.toString();
  fs.writeFileSync(file, out, 'utf8');
  totalBytes += out.length;
}
generateShim();

console.log(`✅ [webview75] CSS compat pass done: ${files.length} files, ${(totalBytes / 1024).toFixed(0)} KiB`);
console.log(`   inset expanded: ${stats.inset}, clamp/min/max fallbacks: ${stats.clampMinMax}, flex-gap margin rules: ${stats.flexGapRules}, :focus duplicates: ${stats.focusVisible}, :where hoisted: ${stats.whereHoisted}`);
console.log(`   color-mix: ${stats.colorMixStatic} computed statically, ${stats.colorMixShim} via runtime shim (${cmExprs.size} distinct expressions)${fs.existsSync(SHIM_OUT) ? ` → ${SHIM_OUT}` : ''}`);
const warnKeys = Object.keys(stats.warn);
if (warnKeys.length) {
  console.log('ℹ️  [webview75] graceful-degradation features left as-is (newer-WebView only):');
  for (const k of warnKeys) console.log(`   ${k}: ${stats.warn[k].count}× (e.g. ${JSON.stringify(stats.warn[k].sample)})`);
}
