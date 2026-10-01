/**
 * dsh-phone-files —— 右侧栏的「手机文件」
 * ════════════════════════════════════════════════════════════════════════
 * 干什么：像文件管理器那样浏览手机存储（容器里的 /sdcard 就是手机的内部存储），
 *        多选文件/图片，一键把它们加进当前会话。
 *
 * 为什么长这样（照着官方 @deepseek-ai/dsh-client-ui-sidebar-files 抄的骨架）：
 *   · 浏览器侧的插件必须用 window.__ModuleLoader__.load({id, factory}) 包装；
 *   · 右侧栏的标签类型注册在 ctx.sidebarRightTabs，
 *     标签的**标题**与**内容**分别注册到两个插槽：
 *       sidebar.right.pane.tab        （内容）
 *       sidebar.right.pane.tab.title  （标题）
 *   · exports.inject 列的是**服务名**（slots / sidebarRightTabs），
 *     package.json 里的 dsh.client.inject 列的是**包名** —— 两者别混。
 *
 * 依赖的两个"桥"来自本 App 注入的脚本（tools/patch.js 与 MainActivity）：
 *   window.DshAndroid.listFiles(path)     → JSON 数组：[{name,dir,size,path}]
 *   window.__dshPhoneFilesInsert(paths)   → 把路径写进输入框（React 受控编辑器，
 *                                           只能靠 execCommand/粘贴事件，直接改
 *                                           textContent React 收不到）
 * 两个桥不在时（比如在桌面版 DSH 上跑），面板会显示一句提示而不是白屏。
 */

window.__ModuleLoader__.load({
  id: "dsh-phone-files",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;
    Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" });

    const react = require("react");
    const h = react.createElement;

    /** 本实现的身份：标签类型 id 与内容注册用的 key 都用它。 */
    const PKG = "dsh-phone-files";
    /** 标签类型的名字（DSH 内部用来区分是哪一类标签）。 */
    const KIND = "phonefiles";

    // ── 小工具 ────────────────────────────────────────────────────────
    const fmtSize = (n) => {
      if (!n) return "";
      if (n < 1024) return n + " B";
      if (n < 1024 * 1024) return (n / 1024).toFixed(0) + " KB";
      if (n < 1024 * 1024 * 1024) return (n / 1048576).toFixed(1) + " MB";
      return (n / 1073741824).toFixed(2) + " GB";
    };
    const isImage = (name) => /\.(png|jpe?g|gif|webp|bmp|heic|avif)$/i.test(name || "");
    const parentOf = (p) => {
      if (!p || p === "/sdcard" || p === "/") return "/sdcard";
      const i = p.lastIndexOf("/");
      if (i <= 0) return "/sdcard";
      const up = p.slice(0, i);
      return up.length < 7 ? "/sdcard" : up;      // 不要越过 /sdcard
    };

    /** 列目录：优先走 App 的桥。 */
    function listDir(path) {
      try {
        if (!window.DshAndroid || !window.DshAndroid.listFiles) {
          return { error: "这个面板要在 DeepSeek Harness 安卓版里用（需要 App 的文件桥）" };
        }
        const raw = window.DshAndroid.listFiles(path);
        const arr = JSON.parse(raw || "[]");
        if (!arr.length && path !== "/sdcard") return { entries: [] };
        return { entries: arr };
      } catch (e) {
        return { error: "读取失败：" + (e && e.message ? e.message : e) };
      }
    }

    /** 把选中的路径交给 App 的插入函数（它负责和 React 编辑器打交道）。 */
    function insertPaths(paths) {
      try {
        if (window.__dshPhoneFilesInsert) return !!window.__dshPhoneFilesInsert(paths);
        // 退路：至少不让用户白点 —— 复制到剪贴板
        if (navigator.clipboard) {
          navigator.clipboard.writeText(paths.join("\n"));
          return "copied";
        }
        return false;
      } catch (e) {
        return false;
      }
    }

    // ── 面板本体 ──────────────────────────────────────────────────────
    const S = {
      wrap: { display: "flex", flexDirection: "column", height: "100%", fontSize: 13, minHeight: 0 },
      bar: { display: "flex", gap: 6, alignItems: "center", padding: "6px 8px", flexWrap: "wrap" },
      btn: {
        border: "1px solid var(--dsw-alias-border-l3, rgba(128,128,128,.35))",
        background: "transparent", color: "inherit", borderRadius: 6,
        padding: "3px 8px", fontSize: 12, cursor: "pointer",
      },
      btnPrimary: {
        border: "1px solid transparent", borderRadius: 6, padding: "3px 10px",
        fontSize: 12, cursor: "pointer", background: "#2563EB", color: "#fff",
      },
      crumb: { fontSize: 12, opacity: .75, padding: "0 8px 6px", wordBreak: "break-all" },
      list: { flex: 1, overflowY: "auto", minHeight: 0, padding: "0 4px 8px" },
      row: {
        display: "flex", alignItems: "center", gap: 8, padding: "6px 6px",
        borderRadius: 6, cursor: "pointer", userSelect: "none",
      },
      name: { flex: 1, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" },
      meta: { fontSize: 11, opacity: .55, flexShrink: 0 },
      hint: { padding: 12, fontSize: 12, opacity: .7, lineHeight: 1.6 },
    };

    function PhoneFilesBody() {
      const [path, setPath] = react.useState("/sdcard");
      const [state, setState] = react.useState({ entries: [], error: null });
      const [picked, setPicked] = react.useState(() => new Set());
      const [note, setNote] = react.useState("");

      const load = react.useCallback((p) => {
        const r = listDir(p);
        setState(r.error ? { entries: [], error: r.error } : { entries: r.entries || [], error: null });
      }, []);

      react.useEffect(() => { load(path); }, [path, load]);

      const toggle = (p) => {
        setPicked((prev) => {
          const next = new Set(prev);
          if (next.has(p)) next.delete(p); else next.add(p);
          return next;
        });
      };

      const openDir = (p) => {
        setPicked(new Set());
        setNote("");
        setPath(p);
      };

      const addToChat = () => {
        const paths = [...picked];
        if (!paths.length) return;
        const r = insertPaths(paths);
        if (r === true) setNote("已加入输入框：" + paths.length + " 个文件（按回车发送）");
        else if (r === "copied") setNote("已复制 " + paths.length + " 个路径到剪贴板");
        else setNote("加不进去：输入框没找到，或者桥不可用");
        if (r === true) setPicked(new Set());
      };

      const rows = [];
      if (state.error) {
        rows.push(h("div", { key: "e", style: S.hint }, state.error));
      } else if (!state.entries.length) {
        rows.push(h("div", { key: "z", style: S.hint }, "这个文件夹是空的"));
      } else {
        for (const it of state.entries) {
          const on = picked.has(it.path);
          rows.push(h("div", {
            key: it.path,
            style: Object.assign({}, S.row, on ? { background: "rgba(37,99,235,.16)" } : null),
            onClick: () => (it.dir ? openDir(it.path) : toggle(it.path)),
            title: it.path,
          },
            h("span", { style: { width: 16, textAlign: "center", opacity: it.dir ? .9 : .5 } }, it.dir ? "📁" : (isImage(it.name) ? "🖼" : "📄")),
            h("span", { style: S.name }, it.name),
            h("span", { style: S.meta }, it.dir ? "文件夹" : fmtSize(it.size)),
            it.dir
              ? h("span", { style: S.meta }, "›")
              : h("span", {
                  style: Object.assign({}, S.meta, { color: on ? "#2563EB" : "inherit", fontWeight: on ? 700 : 400 }),
                }, on ? "已选" : "")
          ));
        }
      }

      return h("div", { style: S.wrap },
        h("div", { style: S.bar },
          h("button", { style: S.btn, onClick: () => openDir(parentOf(path)), title: "上一级" }, "↑ 上一级"),
          h("button", { style: S.btn, onClick: () => openDir("/sdcard") }, "根目录"),
          h("button", { style: S.btn, onClick: () => load(path) }, "刷新"),
          h("span", { style: { flex: 1 } }),
          h("button", { style: S.btnPrimary, onClick: addToChat, disabled: !picked.size },
            picked.size ? ("加入会话 (" + picked.size + ")") : "加入会话")
        ),
        h("div", { style: S.crumb }, path),
        note ? h("div", { style: Object.assign({}, S.hint, { padding: "0 8px 6px" }) }, note) : null,
        h("div", { style: S.list }, rows)
      );
    }

    function PhoneFilesTitle() {
      return "手机文件";
    }

    // ── 注册 ──────────────────────────────────────────────────────────
    function apply(ctx) {
      // ① 标签类型：右侧栏靠它知道"还有一类标签叫手机文件"
      ctx.effect(() => ctx.sidebarRightTabs.register({
        id: PKG,
        kind: KIND,
        priority: "builtin",
        title: () => "手机文件",
        guide: [{
          order: 60,
          title: () => "手机文件",
          description: () => "浏览手机存储，多选后一键加进会话",
        }],
      }), "phone-files: 标签类型");

      // ② 标签内容
      ctx.effect(() => ctx.slots.inject("sidebar.right.pane.tab", () => ctx.slots.register({
        name: "sidebar.right.pane.tab",
        key: PKG,
      }, PhoneFilesBody)), "phone-files: 面板内容");

      // ③ 标签标题
      ctx.effect(() => ctx.slots.inject("sidebar.right.pane.tab.title", () => ctx.slots.register({
        name: "sidebar.right.pane.tab.title",
        key: PKG,
      }, PhoneFilesTitle)), "phone-files: 面板标题");
    }

    exports.apply = apply;
    /** 需要的客户端服务（不是包名 —— 包名写在 package.json 的 dsh.client.inject 里）。 */
    exports.inject = ["slots", "sidebarRightTabs"];
    return module.exports;
  },
});
