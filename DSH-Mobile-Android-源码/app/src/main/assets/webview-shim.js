/*
 * DSH Mobile —— WebView 兼容垫片
 *
 * 为什么需要它
 * ------------
 * DSH 的 Web 客户端及其插件用了不少较新的 JS 特性。老一些的手机
 * 系统 WebView 没有这些全局对象，插件在**加载阶段**就会抛
 * ReferenceError，整页显示 "Failed to load plugins"。
 *
 * 实测到的真实报错（某个用户的手机）：
 *   failed to import loader entry 0873777a
 *   (@deepseek-ai/dsh-client-ui-sidebar-documentpreview): Iterator is not defined
 *
 * 那个插件的源码是：
 *   if (typeof Iterator.prototype.join !== "function")
 *       Iterator.prototype.join = function (s) { return [...this].join(s); };
 * —— 它想"缺了就补"，却忘了 `Iterator` 这个全局本身可能不存在，
 * 于是 `Iterator.prototype` 直接抛 ReferenceError。
 *
 * 各特性的最低 Chrome 版本（对照 WebView 版本）：
 *   replaceAll 85 · at 92 · hasOwn 93 · findLast 97 · structuredClone 98
 *   toSorted 110 · isWellFormed 111 · groupBy 117 · withResolvers 119
 *   fromAsync 121 · **Iterator 122** · Set 方法 122 · **URL.parse 126**
 *
 * 注入方式：由 MainActivity.shouldInterceptRequest 在返回主文档时
 * 插到 <head> 之后、**任何页面脚本之前** —— 因为 DSH 的 HTML 开头就是
 * 内联 <script>，用 onPageStarted 注入来不及。
 *
 * 全部是幂等的能力检测：现代 WebView 上这个文件什么都不做。
 */
(function () {
  'use strict';
  var g = typeof globalThis !== 'undefined' ? globalThis : window;
  var applied = [];

  function def(target, name, fn) {
    try {
      Object.defineProperty(target, name, { value: fn, writable: true, configurable: true });
    } catch (e) {
      try { target[name] = fn; } catch (e2) { /* 放弃这一项 */ }
    }
  }
  function need(expr, name, fn) {
    try { if (expr()) return; } catch (e) { /* 探测本身抛错也当作"缺" */ }
    try { fn(); applied.push(name); } catch (e) { /* 单项失败不影响整体 */ }
  }

  // ── Iterator（Chrome 122+）────────────────────────────────
  need(function () { return typeof g.Iterator !== 'undefined'; }, 'Iterator', function () {
    // %IteratorPrototype%：所有内置迭代器的原型
    var ip = Object.getPrototypeOf(Object.getPrototypeOf([][Symbol.iterator]()));
    var It = function Iterator() { throw new TypeError('Illegal constructor'); };
    It.prototype = ip;
    def(g, 'Iterator', It);
  });

  need(function () { return typeof g.Iterator !== 'undefined' && typeof g.Iterator.from === 'function'; },
    'Iterator.from', function () {
      def(g.Iterator, 'from', function (obj) {
        if (obj === null || obj === undefined) throw new TypeError('Iterator.from called on null or undefined');
        if (typeof obj[Symbol.iterator] === 'function') return obj[Symbol.iterator]();
        if (typeof obj.next === 'function') return obj;
        throw new TypeError('Iterator.from: not iterable');
      });
    });

  need(function () { return typeof g.Iterator !== 'undefined' && typeof g.Iterator.prototype.map === 'function'; },
    'Iterator.prototype helpers', function () {
      var P = g.Iterator.prototype;
      var toArr = function (it) { var a = [], i = 0; for (var v of it) { a.push(v); if (++i > 1e7) break; } return a; };
      def(P, 'toArray', function () { return toArr(this); });
      def(P, 'map', function (f) { return toArr(this).map(f).values(); });
      def(P, 'filter', function (f) { return toArr(this).filter(f).values(); });
      def(P, 'take', function (n) { return toArr(this).slice(0, n).values(); });
      def(P, 'drop', function (n) { return toArr(this).slice(n).values(); });
      def(P, 'flatMap', function (f) {
        var out = [];
        toArr(this).forEach(function (v) {
          var r = f(v);
          if (r != null && typeof r[Symbol.iterator] === 'function') { for (var x of r) out.push(x); }
          else out.push(r);
        });
        return out.values();
      });
      def(P, 'forEach', function (f) { toArr(this).forEach(function (v, i) { f(v, i); }); });
      def(P, 'reduce', function (f, init) {
        var a = toArr(this);
        return arguments.length < 2 ? a.reduce(f) : a.reduce(f, init);
      });
      def(P, 'some', function (f) { return toArr(this).some(f); });
      def(P, 'every', function (f) { return toArr(this).every(f); });
      def(P, 'find', function (f) { return toArr(this).find(f); });
      def(P, 'join', function (sep) { return toArr(this).join(sep === undefined ? ',' : sep); });
    });

  // ── Promise.withResolvers（Chrome 119+）──────────────────
  need(function () { return typeof Promise.withResolvers === 'function'; }, 'Promise.withResolvers', function () {
    def(Promise, 'withResolvers', function () {
      var resolve, reject;
      var p = new Promise(function (res, rej) { resolve = res; reject = rej; });
      return { promise: p, resolve: resolve, reject: reject };
    });
  });

  // ── URL.parse（Chrome 126+）──────────────────────────────
  need(function () { return typeof URL.parse === 'function'; }, 'URL.parse', function () {
    def(URL, 'parse', function (url, base) {
      try { return new URL(url, base); } catch (e) { return null; }
    });
  });

  // ── Object.hasOwn（Chrome 93+，顺手）─────────────────────
  need(function () { return typeof Object.hasOwn === 'function'; }, 'Object.hasOwn', function () {
    def(Object, 'hasOwn', function (o, p) { return Object.prototype.hasOwnProperty.call(o, p); });
  });

  // ── String.prototype.replaceAll（Chrome 85+）─────────────
  need(function () { return typeof String.prototype.replaceAll === 'function'; }, 'String.replaceAll', function () {
    def(String.prototype, 'replaceAll', function (find, rep) {
      if (find instanceof RegExp) {
        if (!find.global) throw new TypeError('replaceAll must be called with a global RegExp');
        return this.replace(find, rep);
      }
      var esc = String(find).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      return this.replace(new RegExp(esc, 'g'), rep);
    });
  });

  // ── String.prototype.at / Array.prototype.at（Chrome 92+）─
  function atFn(n) {
    n = Math.trunc(n) || 0;
    if (n < 0) n += this.length;
    return (n < 0 || n >= this.length) ? undefined : this[n];
  }
  need(function () { return typeof String.prototype.at === 'function'; }, 'String.at', function () {
    def(String.prototype, 'at', atFn);
  });
  need(function () { return typeof Array.prototype.at === 'function'; }, 'Array.at', function () {
    def(Array.prototype, 'at', atFn);
  });

  // ── findLast / findLastIndex（Chrome 97+）────────────────
  need(function () { return typeof Array.prototype.findLast === 'function'; }, 'Array.findLast', function () {
    def(Array.prototype, 'findLast', function (f, thisArg) {
      for (var i = this.length - 1; i >= 0; i--) if (f.call(thisArg, this[i], i, this)) return this[i];
      return undefined;
    });
    def(Array.prototype, 'findLastIndex', function (f, thisArg) {
      for (var i = this.length - 1; i >= 0; i--) if (f.call(thisArg, this[i], i, this)) return i;
      return -1;
    });
  });

  // ── 非破坏性数组方法（Chrome 110+）───────────────────────
  need(function () { return typeof Array.prototype.toSorted === 'function'; }, 'Array.toSorted', function () {
    def(Array.prototype, 'toSorted', function (c) { return Array.prototype.slice.call(this).sort(c); });
    def(Array.prototype, 'toReversed', function () { return Array.prototype.slice.call(this).reverse(); });
    def(Array.prototype, 'toSpliced', function () {
      var a = Array.prototype.slice.call(this);
      return a.splice.apply(a, arguments);
    });
    def(Array.prototype, 'with', function (i, v) {
      var a = Array.prototype.slice.call(this);
      var idx = Math.trunc(i) || 0;
      if (idx < 0) idx += a.length;
      if (idx < 0 || idx >= a.length) throw new RangeError('Invalid index');
      a[idx] = v;
      return a;
    });
  });

  // ── String.prototype.isWellFormed / toWellFormed（Chrome 111+）
  need(function () { return typeof String.prototype.isWellFormed === 'function'; }, 'String.isWellFormed', function () {
    def(String.prototype, 'isWellFormed', function () {
      // 粗略实现：孤立代理项即视为不合法
      return !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(^|[^\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(this);
    });
    def(String.prototype, 'toWellFormed', function () {
      return this.replace(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(^|[^\uD800-\uDBFF])([\uDC00-\uDFFF])/g,
        function (m, pre, lone) { return lone ? (pre || '') + '\uFFFD' : '\uFFFD'; });
    });
  });

  // ── Object.groupBy / Map.groupBy（Chrome 117+）───────────
  need(function () { return typeof Object.groupBy === 'function'; }, 'Object.groupBy', function () {
    def(Object, 'groupBy', function (items, cb) {
      var out = Object.create(null), i = 0;
      for (var v of items) {
        var k = cb(v, i++);
        (out[k] || (out[k] = [])).push(v);
      }
      return out;
    });
    def(Map, 'groupBy', function (items, cb) {
      var out = new Map(), i = 0;
      for (var v of items) {
        var k = cb(v, i++);
        if (!out.has(k)) out.set(k, []);
        out.get(k).push(v);
      }
      return out;
    });
  });

  // ── Array.fromAsync（Chrome 121+）────────────────────────
  need(function () { return typeof Array.fromAsync === 'function'; }, 'Array.fromAsync', function () {
    def(Array, 'fromAsync', function (items, mapFn, thisArg) {
      return (async function () {
        var out = [], i = 0, v;
        if (items != null && typeof items[Symbol.asyncIterator] === 'function') {
          for await (v of items) out.push(mapFn ? await mapFn.call(thisArg, v, i++) : v);
        } else if (items != null && typeof items[Symbol.iterator] === 'function') {
          for (v of items) out.push(mapFn ? await mapFn.call(thisArg, v, i++) : v);
        } else if (items != null && typeof items.then === 'function') {
          var arr = await items;
          for (v of arr) out.push(mapFn ? await mapFn.call(thisArg, v, i++) : v);
        }
        return out;
      })();
    });
  });

  // ── Set 集合运算（Chrome 122+）───────────────────────────
  need(function () { return typeof Set.prototype.union === 'function'; }, 'Set methods', function () {
    var S = Set.prototype;
    def(S, 'union', function (o) { var r = new Set(this); for (var v of o) r.add(v); return r; });
    def(S, 'intersection', function (o) { var r = new Set(); for (var v of o) if (this.has(v)) r.add(v); return r; });
    def(S, 'difference', function (o) { var r = new Set(this); for (var v of o) r.delete(v); return r; });
    def(S, 'symmetricDifference', function (o) {
      var r = new Set(this);
      for (var v of o) { if (this.has(v)) r.delete(v); else r.add(v); }
      return r;
    });
    def(S, 'isSubsetOf', function (o) { for (var v of this) if (!o.has(v)) return false; return true; });
    def(S, 'isSupersetOf', function (o) { for (var v of o) if (!this.has(v)) return false; return true; });
    def(S, 'isDisjointFrom', function (o) { for (var v of this) if (o.has(v)) return false; return true; });
  });

  // 记录一下，方便从 DevTools 侧确认垫片生效、以及到底是哪个 WebView
  try {
    g.__dshWebCompat = { applied: applied, ua: navigator.userAgent };
  } catch (e) { /* 忽略 */ }
})();
