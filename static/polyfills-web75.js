/* WebView 75 compat: Web APIs missing in Chromium 75 that core-js does not
 * cover. Loaded as a classic script before any ES module.
 *
 * - CanvasRenderingContext2D.prototype.roundRect / Path2D.prototype.roundRect
 *   (Chromium 99+) — spec-shaped implementation on top of arcTo (available
 *   since forever). Supports number / percentage / 1-4 item list radii.
 * - structuredClone (Chromium 98+) — recursive deep clone with Date / RegExp /
 *   Map / Set / TypedArray / ArrayBuffer support. Clone targets in this app
 *   are plain state objects (diagram configs, plasmid records), so the
 *   transferable / failure-semantics parts of the spec are not needed.
 */
(function () {
  'use strict';

  // ---------- roundRect ----------
  function installRoundRect(proto) {
    if (!proto || typeof proto.roundRect === 'function') return false;

    function toRadius(v, base) {
      if (typeof v === 'number' && v >= 0) return v;
      if (typeof v === 'string' && v.indexOf('%') > 0) {
        var p = parseFloat(v);
        if (p >= 0) return (p / 100) * base;
      }
      throw new TypeError('Failed to execute roundRect: provided radius is not a non-negative number');
    }

    // resolve radii into [tl, tr, br, bl], each an entry of
    // { x: horizontal px, y: vertical px }
    function resolveRadii(radii, w, h) {
      var list;
      if (radii === undefined || radii === null) {
        list = [0];
      } else if (typeof radii === 'number' || typeof radii === 'string') {
        list = [radii];
      } else if (typeof radii === 'object' && radii !== null && typeof radii.length === 'number') {
        if (radii.length === 0 || radii.length > 4) {
          throw new TypeError('Failed to execute roundRect: The radius provided (' + radii.length + ' items) is too long');
        }
        list = Array.prototype.slice.call(radii);
      } else {
        throw new TypeError('Failed to execute roundRect: The provided value is not of type (sequence<double> or DOMPointInit or unrestricted double)');
      }
      // 1 → [a]; 2 → [a, b, a, b]; 3 → [a, b, c, b]; 4 → [a, b, c, d]
      while (list.length < 4) {
        list = list.concat(list.slice(0, 4 - list.length));
      }
      var out = [];
      for (var i = 0; i < 4; i++) {
        var e = list[i];
        var r;
        if (typeof e === 'number' || typeof e === 'string') {
          r = { x: toRadius(e, Math.min(Math.abs(w), Math.abs(h))), y: toRadius(e, Math.min(Math.abs(w), Math.abs(h))) };
        } else if (e && typeof e === 'object') {
          r = {
            x: toRadius(e.x !== undefined ? e.x : 0, Math.abs(w)),
            y: toRadius(e.y !== undefined ? e.y : 0, Math.abs(h))
          };
        } else {
          throw new TypeError('Failed to execute roundRect: invalid radius entry');
        }
        out.push(r);
      }
      // spec: clamp radii so adjacent corners never overlap the edges
      var scale = 1;
      var pairs = [
        [out[0].x, out[1].x], [out[1].y, out[2].y],
        [out[2].x, out[3].x], [out[3].y, out[0].y]
      ];
      var limits = [Math.abs(w), Math.abs(h), Math.abs(w), Math.abs(h)];
      for (var k = 0; k < 4; k++) {
        var sum = pairs[k][0] + pairs[k][1];
        if (sum > limits[k]) scale = Math.min(scale, limits[k] / sum);
      }
      for (var j = 0; j < 4; j++) {
        out[j].x *= scale;
        out[j].y *= scale;
      }
      return out;
    }

    proto.roundRect = function (x, y, w, h, radii) {
      var r = resolveRadii(radii, w, h);
      var x0 = x, y0 = y, x1 = x + w, y1 = y + h;
      this.moveTo(x0 + r[0].x, y0);
      // top edge → top-right corner
      this.lineTo(x1 - r[1].x, y0);
      if (r[1].x || r[1].y) this.arcTo(x1, y0, x1, y0 + r[1].y, r[1].x);
      // right edge
      this.lineTo(x1, y1 - r[2].y);
      if (r[2].x || r[2].y) this.arcTo(x1, y1, x1 - r[2].x, y1, r[2].y);
      // bottom edge
      this.lineTo(x0 + r[3].x, y1);
      if (r[3].x || r[3].y) this.arcTo(x0, y1, x0, y1 - r[3].y, r[3].x);
      // left edge
      this.lineTo(x0, y0 + r[0].y);
      if (r[0].x || r[0].y) this.arcTo(x0, y0, x0 + r[0].x, y0, r[0].y);
      this.closePath();
      return undefined;
    };
    return true;
  }

  try {
    if (typeof CanvasRenderingContext2D !== 'undefined') installRoundRect(CanvasRenderingContext2D.prototype);
    if (typeof Path2D !== 'undefined') installRoundRect(Path2D.prototype);
  } catch (e) { /* never block module loading */ }

  // ---------- structuredClone ----------
  if (typeof window.structuredClone !== 'function') {
    function deepClone(value, seen) {
      if (value === null || typeof value !== 'object') return value;
      if (!seen) seen = new Map();
      if (seen.has(value)) return seen.get(value);
      var out;
      if (value instanceof Date) {
        out = new Date(value.getTime());
      } else if (value instanceof RegExp) {
        out = new RegExp(value.source, value.flags);
      } else if (typeof Map === 'function' && value instanceof Map) {
        out = new Map();
        seen.set(value, out);
        value.forEach(function (v, k) { out.set(deepClone(k, seen), deepClone(v, seen)); });
        return out;
      } else if (typeof Set === 'function' && value instanceof Set) {
        out = new Set();
        seen.set(value, out);
        value.forEach(function (v) { out.add(deepClone(v, seen)); });
        return out;
      } else if (typeof ArrayBuffer !== 'undefined' && value instanceof ArrayBuffer) {
        out = value.slice(0);
      } else if (ArrayBuffer.isView && ArrayBuffer.isView(value)) {
        out = new value.constructor(value);
      } else if (Array.isArray(value)) {
        out = [];
        seen.set(value, out);
        for (var i = 0; i < value.length; i++) out[i] = deepClone(value[i], seen);
        return out;
      } else {
        out = {};
        seen.set(value, out);
        for (var key in value) {
          if (Object.prototype.hasOwnProperty.call(value, key)) out[key] = deepClone(value[key], seen);
        }
      }
      seen.set(value, out);
      return out;
    }
    try {
      window.structuredClone = function (value) { return deepClone(value); };
    } catch (e) { /* ignore */ }
  }
})();
