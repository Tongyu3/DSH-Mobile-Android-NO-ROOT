// DSH Mobile —— 手机窄屏设置页改造补丁
//
// 背景：DSH 的设置弹窗是「左分类 + 右内容」两栏布局，固定 312px 宽，
// 其中左栏占 188px（60%），右栏只剩 124px，导致中文被挤成一字一行。
//
// 改造目标（用户要求）：
//   1. 弹窗改为全屏；
//   2. 分类与内容改为「列表 → 详情」两态切换：点分类进全屏详情，详情里有返回；
//   3. 内容区获得整屏宽度，文字正常横排。
//
// 实现方式：由 App 在页面加载后注入，完全不动容器里 DSH 的任何文件，
// 所以 DSH 升级后依然有效（选择器只依赖结构，不依赖 CSS Module 的哈希类名）。
(function () {
  var W = window;
  // 幂等守卫。迭代调试时可以先设 window.__dshMobileForce = true 再注入本文件，
  // 否则会调用上一次注入留下的旧闭包（旧 CSS / 旧逻辑），改了却看不到效果。
  if (W.__dshMobilePatch && !W.__dshMobileForce) { W.__dshMobilePatch(); return; }

  var NARROW_PX = 700;          // 窄屏阈值
  var root = document.documentElement;
  var wasOpen = false;          // 设置弹窗上一次是否处于打开状态

  var CSS = [
    /*
     * ⚠️ 必须用 :has(> nav) 把"设置弹窗"和其它弹窗区分开。
     *
     * 之前的写法是裸的 [role="dialog"]，结果把**所有**浮层都改成了
     * display:flex + flex-direction:row —— 典型受害者是「本轮用量」那个
     * token 统计小浮层（它也是 role=dialog，但没有 nav）：
     * 于是它的「标题 / 分隔线 / 明细」被排成横向三列，
     * 每列只有 97~116px 宽、却有 400 多像素高，
     * 中文被挤成竖条贴在右边 —— 就是用户说的"字体会挤在右边"。
     * 实测修前 标题[4,3,116,412] 分隔线[120,3,116,410] 明细[235,3,97,420]，
     * 修后 恢复成纵向堆叠、宽 275。
     */
    'html[data-dsh-mobile="1"] [role="dialog"]:has(>nav){',
    '  width:100vw!important;max-width:100vw!important;',
    '  height:100dvh!important;max-height:100dvh!important;',
    '  border-radius:0!important;margin:0!important;',
    '  display:flex!important;flex-direction:row!important;',
    '}',
    /* 左栏：列表态占满整宽 */
    'html[data-dsh-mobile="1"] [role="dialog"]:has(>nav)>nav{',
    '  width:100%!important;flex:1 1 auto!important;min-width:0!important;',
    '}',
    /* 右栏：详情态占满整宽 */
    'html[data-dsh-mobile="1"] [role="dialog"]:has(>nav)>div{',
    '  width:100%!important;flex:1 1 auto!important;min-width:0!important;',
    '}',
    /* 列表态：藏右栏 */
    'html[data-dsh-mobile="1"][data-dsh-view="list"] [role="dialog"]:has(>nav)>div{display:none!important;}',
    /* 详情态：藏左栏 */
    'html[data-dsh-mobile="1"][data-dsh-view="detail"] [role="dialog"]:has(>nav)>nav{display:none!important;}',
    /* 分类按钮拉满宽度，好点；但要排除我们自己注入的返回按钮，
       否则它会被这条规则撑到屏幕外 */
    'html[data-dsh-mobile="1"] [role="dialog"]:has(>nav)>nav button:not([data-dsh-close]){width:100%!important;}',
    'html[data-dsh-mobile="1"] [data-dsh-close]{width:auto!important;flex:0 0 auto!important;}',
    /*
     * 内容区内部：让它真正吃到整屏宽度。
     *
     * 关键坑：这些容器是 content-box，若只给 width:100% 而不改盒模型，
     * padding 会被加在宽度之外（实测 360 + 24×2 = 408px），右侧直接被切掉。
     * 所以必须同时改 box-sizing，并把 max-width 收到 100%（而不是 none）。
     */
    'html[data-dsh-mobile="1"] [role="dialog"]:has(>nav)>div,',
    'html[data-dsh-mobile="1"] [role="dialog"]:has(>nav)>div *{',
    '  box-sizing:border-box!important;',
    '  max-width:100%!important;',
    '}',
    'html[data-dsh-mobile="1"] [role="dialog"]:has(>nav)>div>div{width:100%!important;}',
    /* 分类列表的标题行：让"设置"和返回按钮分居两侧 */
    'html[data-dsh-mobile="1"] [role="dialog"]:has(>nav)>nav>div:first-child{',
    '  display:flex!important;align-items:center!important;',
    '  justify-content:space-between!important;width:100%!important;',
    '}',
    /* 标题与按钮都不许被压缩，否则「设置」会被挤成一字一行 */
    'html[data-dsh-mobile="1"] [role="dialog"]:has(>nav)>nav>div:first-child>*{',
    '  flex:0 0 auto!important;white-space:nowrap!important;',
    '}',
    /* 返回按钮 */
    'html[data-dsh-mobile="1"] [data-dsh-back],',
    'html[data-dsh-mobile="1"] [data-dsh-close]{',    '  display:inline-flex;align-items:center;gap:4px;',
    '  margin-right:8px;padding:4px 12px;border-radius:8px;',
    '  border:1px solid rgba(255,255,255,.28);background:transparent;',
    '  color:inherit;font-size:13px;line-height:1.4;cursor:pointer;',
    '}',
    /*
     * 提问卡片（ask_user_question）的底部按钮行：
     * 实测 footer 宽 230（内容 210），而 footerActions 宽 153，
     * 两者相加超出 → 「提交」按钮右边界跑到 366 > 视口 360，被切掉。
     * 解法：让 footer 允许换行，并让按钮组右对齐。
     */
    'html[data-dsh-mobile="1"] [data-dsh-qfooter]{',
    '  flex-wrap:wrap!important;row-gap:8px!important;',
    '}',
    'html[data-dsh-mobile="1"] [data-dsh-qactions]{',
    '  flex-wrap:wrap!important;margin-left:auto!important;',
    '  justify-content:flex-end!important;max-width:100%!important;',
    '}',
    'html[data-dsh-mobile="1"] [data-dsh-qactions]>*{flex:0 0 auto!important;}',
    /* 卡片两侧留白收窄，让内容更宽 */
    'html[data-dsh-mobile="1"] [data-dsh-qframe]{',
    '  padding-left:10px!important;padding-right:10px!important;',
    '}',
    /*
     * 左上角浮动鲸鱼按钮：侧边栏收起后用它唤出。
     * 只在收起状态显示（展开后 DSH 自己的侧边栏里就有切换按钮）。
     */
    '#dsh-rail-btn{',
    '  position:fixed;left:8px;top:8px;width:42px;height:42px;',
    '  border:none;border-radius:50%;z-index:950;',
    '  background:rgba(32,33,36,.62);color:#fff;cursor:pointer;',
    '  display:flex;align-items:center;justify-content:center;',
    '  -webkit-backdrop-filter:blur(8px);backdrop-filter:blur(8px);',
    '  box-shadow:0 2px 10px rgba(0,0,0,.35);',
    '  transition:opacity .2s ease,transform .22s cubic-bezier(.33,1,.68,1);',
    '  opacity:1;transform:scale(1);',
    '}',
    '#dsh-rail-btn svg{width:24px;height:24px;}',
    /* 用淡出+缩小代替 display 切换，才有过渡动画 */
    'body:not([data-dsh-rail-hidden="1"]) #dsh-rail-btn{',
    '  opacity:0;transform:scale(.8);pointer-events:none;',
    '}',
    /* 侧边栏隐藏时，DSH 自带的「打开侧边栏」按钮会飘到右上角（重复且碍眼），
       由浮动鲸鱼按钮替代它。用 :not(#dsh-rail-btn) 避免误伤自己。 */
    /*
     * DSH 自带的左侧边栏开关**一律**隐藏 —— 由浮动鲸鱼按钮替代。
     *
     * 原来只在"收起态"隐藏，于是展开/收起的中间态它会闪出来
     * （用户看到的就是"新对话页右上角莫名多出一个「打开侧边栏」"）。
     * 这个按钮任何时刻都不需要，就无条件隐藏。
     * 注意用**精确匹配**（=）而不是 *=，否则会把抽屉里的「收起侧边栏」也误杀。
     */
    'button[aria-label="打开侧边栏"],',
    'button[aria-label="显示侧边栏"]:not(#dsh-rail-btn){',
    '  display:none!important;',
    '}',
    /*
     * 右侧边栏的开关：**保留**（用户要求在任务页能打开工作区文件面板）。
     *
     * 早先我误判它"没什么用"就删掉了 —— 那是错的。实测 DSH 的右侧栏是一个
     * **自带过渡的文件面板**：`P3OORG_panel`，`transition: transform .3s
     * cubic-bezier(.4,0,.2,1)`，收起时 `translateX(360px)`、展开时 `none`，
     * 里面列的是当前工作区的文件（AGENTS.md / hello.txt / …），真的能用。
     * 当时看着"点了没反应"，其实是被侧边栏那个 2147482000 的 z-index 盖住了，
     * 跟这个按钮本身无关。
     *
     * 只在**非落地页**显示：还没选工作区时它打开也是空的。
     */
    'body[data-dsh-landing="1"] button[aria-label="打开右侧边栏"],',
    'body[data-dsh-landing="1"] button[aria-label="显示右侧边栏"]{',
    '  display:none!important;',
    '}',
    /*
     * 栅格必须**恒定**：侧边栏永不占栅格，主内容永远满宽满高。
     *
     * 为什么：DSH 展开态用的是单列栅格（grid-template-columns:360px），
     * 于是 sidebarCol 占第 1 行，centerCol / rightbarCol 被挤到第 2、3 行，
     * 而且高度被压成 **0**。我们收起时设的却是三列 0px 1fr 0px。
     * 两者**轨道数不同**，grid-template-columns 根本无法插值 ——
     * 这就是"切换时先蹦出别的页面、完全不平滑"的根因。
     * 现在两个状态共用同一套三列栅格，抽屉只靠 transform 平移。
     */
    'html[data-dsh-mobile="1"] [data-dsh-frame]{',
    '  grid-template-columns:0px 1fr 0px!important;',
    '}',
    /* 显式钉住各列与行：抽屉改成 fixed 之后，自动流也不会把主内容挤进 0 宽的列。
       行也钉在第 1 行 —— DSH 展开态会把 centerCol 排到第 2 行并压成 0 高，
       钉住之后它永远在主行、永远满高。（不覆盖 grid-template-rows，
       保留 DSH 自己的行定义，只靠 grid-row 定位，改动面更小。） */
    'html[data-dsh-mobile="1"] [data-dsh-frame]>[class*="sidebarCol"]{grid-column:1!important;grid-row:1!important;}',
    'html[data-dsh-mobile="1"] [data-dsh-frame]>[class*="centerCol"]{grid-column:2!important;grid-row:1!important;}',
    'html[data-dsh-mobile="1"] [data-dsh-frame]>[class*="rightbarCol"]{grid-column:3!important;grid-row:1!important;}',
    /*
     * 侧边栏改成**覆盖式抽屉**，用 transform 平移。
     *
     * 之前是对 width 做过渡：内容宽度不变、只是被 overflow 裁掉，
     * 视觉上就是"一个页面被横向压扁、同时露出下面的另一个页面"。
     * transform 是合成层动画，既平滑又完全不触碰布局。
     */
    'html[data-dsh-mobile="1"] [class*="sidebarCol"]{',
    '  position:fixed!important;left:0;top:0;bottom:0;',
    '  width:280px!important;max-width:82vw!important;min-width:0!important;',
    /*
     * ⚠️ z-index 必须**低于** DSH 自己那些 portal 到 body 的浮层，否则会把它们盖住。
     * 实测 DSH 的取值：气泡提示 100、工作区选择弹窗 1000、视图选项菜单 1100。
     * 之前这里写的是 2147482000，结果点「视图选项」菜单其实**已经打开了**
     * （DOM 里 position:fixed、z-index:1100、坐标也对），
     * 却被侧边栏整个盖在下面 —— 用户看到的就是"点了没反应"。
     * 取 900：高于 frame 内的 overlayLayer(20) / handle(11)，低于上面三个。
     */
    '  z-index:900!important;',
    '  overflow:hidden!important;',
    '  box-shadow:0 0 24px rgba(0,0,0,.5);',
    '  transition:transform .26s cubic-bezier(.33,1,.68,1)!important;',
    '}',
    /*
     * ⚠️ 展开态必须显式写 transform:none，并且**不能**用 will-change:transform。
     *
     * 原因：transform（以及 will-change:transform）会为 position:fixed 的后代
     * **创建包含块**。DSH 的设置弹窗就是渲染在侧边栏子树里的，
     * 于是它不再相对视口定位，而是被按抽屉的宽度裁切 ——
     * 实测弹窗只有 280px 宽（正好等于抽屉宽度），
     * 而它本该铺满整屏（my CSS 里写的是 width:100vw）。
     * 像素扫描证据：弹窗底色 44,44,46 只覆盖 x=0..839（1080 宽屏，
     * 即 280 CSS px），x≥882 是后面的聊天界面。
     * 副作用：从侧边栏进去的设置页、以及里面注入的「API Key / 手机控制」
     * 两个入口全都被挤成一条窄栏。
     */
    'body:not([data-dsh-rail-hidden="1"]) [class*="sidebarCol"]{',
    '  transform:none!important;',
    '}',
    'body[data-dsh-rail-hidden="1"] [class*="sidebarCol"]{',
    '  transform:translateX(-100%)!important;box-shadow:none;',
    '}',
    /*
     * 隐藏 DSH 的气泡提示（气泡组件的类名里都带 `_bubble_`）。
     *
     * 两个独立的毛病都出在它身上：
     *  1) **点一下就不消失。** 桌面端它靠 mouseleave 关掉，触屏没有 hover，
     *      实测点「复制」后它出现（SPAN._bubble_1nw3t_1，position:fixed、
     *      z-index:100），然后**永久停在屏幕上**，正好压在附件卡片上。
     *  2) **位置还会跑偏。** 它是 position:fixed，而侧边栏上有 transform，
     *      transform 会给 fixed 后代创建新的包含块 ——
     *      于是本该在左上角的气泡跑到右上角（用户截图里那个"打开侧边栏"）。
     *
     * 它只是按钮的**名称标签**（内容是「复制」而不是「已复制」），
     * 手机上既没有 hover 也不需要这层说明，纯噪音，直接全局隐藏。
     * 用类名子串而不是 role="tooltip"：实测有的实例带 role、有的不带。
     */
    '[class*="_bubble_"]{display:none!important;}',
    'html[data-dsh-mobile="1"] [role="tooltip"]{display:none!important;}',
    /*
     * 对话标题避让浮动鲸鱼：
     * 实测会话标题按钮在 [20,11]，鲸鱼占 [8..50]，两者重叠。
     * 侧边栏隐藏时把标题行整体右移，腾出鲸鱼的位置。
     */
    'body[data-dsh-rail-hidden="1"] [class*="titleRow"]{',
    '  padding-left:54px!important;',
    '}',
    /*
     * （原来的 grid-template-columns / width 过渡已删除 —— 见上面抽屉那段：
     *   轨道数变化无法插值，width 过渡又会把内容横向压扁，
     *   两者正是"切换不流畅、先弹出别的页面"的原因。）
     */
    /* 工作区选择器的快捷入口 */
    '[data-dsh-shortcuts]{',
    '  display:flex!important;flex-wrap:wrap;gap:8px;',
    '  padding:10px 12px 6px 12px;width:100%!important;',
    '  height:auto!important;min-height:0!important;',
    '}',
    '[data-dsh-shortcuts]>button{',
    '  flex:0 0 auto;padding:7px 12px;border-radius:999px;font-size:13px;',
    '  border:1px solid rgba(255,255,255,.22);background:rgba(255,255,255,.06);',
    '  color:inherit;cursor:pointer;white-space:nowrap;',
    '}',
    /* 可下钻的目录选择菜单 */
    '[data-dsh-dirmenu]{padding:8px 12px;width:100%!important;box-sizing:border-box!important;}',
    '.dsh-dm-path{font-size:12px;opacity:.7;margin-bottom:6px;word-break:break-all;}',
    '.dsh-dm-bar{display:flex;gap:8px;margin-bottom:8px;}',
    '.dsh-dm-bar>button{flex:1 1 auto;padding:8px 10px;border-radius:8px;font-size:13px;',
    '  border:1px solid rgba(255,255,255,.22);background:rgba(255,255,255,.06);color:inherit;cursor:pointer;}',
    '.dsh-dm-pick{background:rgba(80,140,255,.3)!important;border-color:rgba(130,175,255,.55)!important;}',
    '.dsh-dm-list{display:flex;flex-wrap:wrap;gap:8px;max-height:42vh;overflow:auto;}',
    '.dsh-dm-list>button{flex:0 0 auto;padding:7px 12px;border-radius:8px;font-size:13px;',
    '  border:1px solid rgba(255,255,255,.18);background:rgba(255,255,255,.04);color:inherit;cursor:pointer;}',
    '.dsh-dm-empty{font-size:12px;opacity:.55;}',
  ].join('\n');

  function ensureStyle() {
    var s = document.getElementById('dsh-mobile-style');
    if (!s) {
      s = document.createElement('style');
      s.id = 'dsh-mobile-style';
      (document.head || root).appendChild(s);
    }
    if (s.textContent !== CSS) s.textContent = CSS;   // 便于迭代时更新样式
  }

  function isSettingsDialog(el) {
    return el && el.getAttribute && el.getAttribute('role') === 'dialog'
        && el.querySelector(':scope > nav') !== null;
  }

  function settingsDialog() {
    var ds = document.querySelectorAll('[role="dialog"]');
    for (var i = 0; i < ds.length; i++) { if (isSettingsDialog(ds[i])) return ds[i]; }
    return null;
  }

  function injectBackButton(dlg) {
    var content = dlg.querySelector(':scope > div');
    if (!content) return;
    if (content.querySelector('[data-dsh-back]')) return;
    var header = content.firstElementChild;
    if (!header) return;
    var btn = document.createElement('button');
    btn.setAttribute('data-dsh-back', '1');
    btn.type = 'button';
    btn.textContent = '‹ 设置';
    btn.addEventListener('click', function (ev) {
      ev.preventDefault();
      ev.stopPropagation();
      root.setAttribute('data-dsh-view', 'list');
    }, true);
    header.insertBefore(btn, header.firstChild);
  }

  /**
   * 在分类列表的标题行放一个「返回」按钮，用于**关闭整个设置弹窗**回到主界面。
   *
   * 为什么必须补这个：原弹窗的关闭按钮（✕）位于右侧内容区的头部，
   * 而列表态下右侧内容被我隐藏了 —— 于是点开设置后没有任何出口。
   * 这里复用它：找到那个关闭按钮并替用户点一下。
   */
  function injectCloseButton(dlg) {
    var nav = dlg.querySelector(':scope > nav');
    if (!nav || nav.querySelector('[data-dsh-close]')) return;
    var titleRow = nav.firstElementChild;
    if (!titleRow) return;

    var btn = document.createElement('button');
    btn.setAttribute('data-dsh-close', '1');
    btn.type = 'button';
    btn.textContent = '返回';
    btn.addEventListener('click', function (ev) {
      ev.preventDefault();
      ev.stopPropagation();
      var all = dlg.querySelectorAll('button');
      for (var i = 0; i < all.length; i++) {
        // 原关闭按钮的文字藏在隐藏 label 里，textContent 仍包含「关闭」
        if ((all[i].textContent || '').indexOf('关闭') >= 0) { all[i].click(); return; }
      }
      // 兜底：多数弹窗支持 Esc 关闭
      document.dispatchEvent(new KeyboardEvent('keydown',
        { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true }));
    }, true);
    titleRow.appendChild(btn);
  }

  /**
   * 在 DSH 设置页的分类列表末尾追加「API Key（本机）」入口，
   * 通过 JS 桥（window.DshAndroid）打开本 App 的原生设置对话框。
   *
   * 存在的意义：这样 App 顶部那条状态栏就能**完全隐藏**，不再常年遮挡视野，
   * 同时又不会让人找不到设置入口。
   */
  function injectAppSettingsEntry(dlg) {
    var nav = dlg.querySelector(':scope > nav');
    if (!nav) return;
    var navList = nav.lastElementChild;           // 分类按钮容器
    if (!navList || navList.querySelector('[data-dsh-appsettings]')) return;
    var proto = navList.querySelector('button');

    function addEntry(attr, label, jsMethod) {
      var btn = document.createElement('button');
      btn.setAttribute(attr, '1');
      btn.type = 'button';
      if (proto) btn.className = proto.className;   // 复用 DSH 自己的按钮样式
      btn.textContent = label;
      btn.addEventListener('click', function (ev) {
        ev.preventDefault();
        ev.stopPropagation();
        if (window.DshAndroid && window.DshAndroid[jsMethod]) {
          window.DshAndroid[jsMethod]();
        }
      }, true);
      navList.appendChild(btn);
    }

    addEntry('data-dsh-appsettings', 'API Key（本机）', 'openAppSettings');
    addEntry('data-dsh-appcontrol', '📱 手机控制（本机）', 'openAppControl');
  }

  /**
   * 左侧边栏：DSH 的"折叠"态仍然占 56px 宽（图标轨道）。
   * 按用户要求参考 DeepSeek C 端：查看工作界面时隐藏侧边栏，点左上角鲸鱼再唤出。
   * 做法：把折叠态真正收到 0 宽，并浮出一个鲸鱼按钮调用 DSH 自己的切换逻辑。
   */
  function patchSidebar() {
    var side = document.querySelector('[class*="sidebarCol"]');
    if (!side || !document.body) return;
    var frame = side.parentElement;
    if (frame) frame.setAttribute('data-dsh-frame', '1');   // 供 CSS 定位/加动画
    var collapsed = !!side.querySelector('[class*="collapsed"]');

    /*
     * 清掉旧版补丁遗留在内联样式上的 !important —— 重新注入（不刷新页面）时，
     * 内联样式会盖过新 CSS，导致"改了却没效果"。
     */
    side.style.removeProperty('width');
    side.style.removeProperty('min-width');
    side.style.removeProperty('overflow');
    side.style.removeProperty('display');
    if (frame) frame.style.removeProperty('grid-template-columns');

    if (!document.getElementById('dsh-rail-btn')) {
      var btn = document.createElement('button');
      btn.id = 'dsh-rail-btn';
      btn.type = 'button';
      btn.setAttribute('aria-label', '显示侧边栏');
      var orig = side.querySelector('button[aria-label*="侧边栏"]');
      if (orig) btn.innerHTML = orig.innerHTML;   // 复用 DSH 自带的鲸鱼图标
      btn.addEventListener('click', function (ev) {
        ev.preventDefault();
        ev.stopPropagation();
        /*
         * 先乐观地把状态切到"展开"，再触发 DSH 自己的切换。
         *
         * 为什么：真正的状态同步是靠 MutationObserver + 150ms 去抖的，
         * 如果只等它，抽屉会在点击后 150ms 才开始动 —— 手感上就是"顿一下再弹出来"。
         * 这里提前置位，动画就能从**这一帧**开始。
         */
        requestRail(false);
        var o = document.querySelector('[class*="sidebarCol"] button[aria-label*="侧边栏"]');
        if (o) o.click();
      }, true);
      document.body.appendChild(btn);
    }

    if (collapsed) {
      /*
       * 只切换一个 body 属性 —— 栅格列宽、抽屉宽度与位移**全部交给 CSS**。
       *
       * 之前这里用内联 !important 去改 width / min-width / overflow /
       * grid-template-columns，既和 CSS 互相打架，也正是动画跳变的来源
       * （内联的 grid-template-columns 让轨道数在 3 与 1 之间跳）。
       * 另外注意：仍然不能用 display:none —— 侧边栏是栅格 item，
       * 移出栅格流会让后面的列整体前移一格，整屏会变空白。
       */
      document.body.setAttribute('data-dsh-rail-hidden', '1');
    } else {
      /*
       * ⚠️ 只有在**闩锁过期**后才允许按类名把状态改回"展开"。
       * 收起动画中途 DSH 会短暂没有 collapsed 类（见 requestRail 的说明），
       * 若这里照做，抽屉会弹回来 —— 就是那个"抽搐"。
       */
      if (Date.now() < railLatchUntil) return;
      document.body.removeAttribute('data-dsh-rail-hidden');
    }
  }

  /**
   * 抽屉状态的"闩锁"。
   *
   * 为什么必须有：DSH 收起的**过程**并不是直接加上 collapsed，而是
   *   1) 先摘掉 collapsed、换成 fading（淡出，约 160ms）
   *   2) 动画结束后才加上 collapsed + railIn
   * 如果期间我们照着类名去同步状态，就会在 (1) 阶段误判成"已经展开了"，
   * 把 data-dsh-rail-hidden 撤掉 —— 抽屉于是从半路弹回来，
   * 等 (2) 到了又缩回去。逐帧实测到的正是这个：
   *   t=227 translateX(-247) → t=277 (-207) → t=392 (-23) → 再滑到 -280
   * 用户看到的就是"已经关上的侧边栏又跳出来抽搐一下"。
   *
   * 所以：用户动作（点鲸鱼 / 点收起 / 自动收起）之后的一小段时间内，
   * **不跟随** DSH 的中间态类名，锁住我们自己的状态；
   * 等过渡结束（闩锁过期）再按最终类名校准。
   */
  var railLatchUntil = 0;

  /** 明确请求抽屉的开/合状态，并闩锁一段时间，避免被过渡中间态翻回去。 */
  function requestRail(hidden) {
    railLatchUntil = Date.now() + 520;      // 略长于 260ms 的过渡
    if (hidden) document.body.setAttribute('data-dsh-rail-hidden', '1');
    else document.body.removeAttribute('data-dsh-rail-hidden');
  }

  /** 收起抽屉：复用 DSH 自己的「收起侧边栏」按钮。 */
  function collapseRail() {
    var b = document.querySelector('[class*="sidebarCol"] button[aria-label*="收起侧边栏"]');
    if (b) { b.click(); return true; }
    return false;
  }

  /**
   * 手机上的抽屉必须"选完就收"。
   *
   * DSH 是按**桌面常驻栏**设计的：点了「新建会话」或某条会话后，
   * 侧边栏本来就该一直在那儿，所以它自己不负责收起。
   * 但在手机上抽屉是覆盖整屏的，不收起来用户就"回不到对话页"——
   * 这正是用户反馈的"点击创建新对话无法自动弹回对话页，切换对话后同理"。
   *
   * 这里用事件委托（capture）盯着抽屉内部的点击：
   * 命中「新建会话」按钮或会话列表区域里的可点元素时，等 DSH 自己处理完再收起。
   */
  function patchDrawerAutoClose() {
    if (W.__dshDrawerAutoClose) return;
    W.__dshDrawerAutoClose = true;
    document.addEventListener('click', function (ev) {
      var t = ev.target;
      if (!t || !t.closest) return;

      /*
       * 抽屉里的「收起侧边栏」：**立刻**切到收起态。
       * 同上，不能等 MutationObserver 的 150ms 去抖，否则点一下要顿一下才动。
       * 用精确匹配，避免误伤「收起右侧边栏」。
       */
      if (t.closest('[class*="sidebarCol"] button[aria-label="收起侧边栏"]')) {
        requestRail(true);
        return;
      }

      var side = t.closest('[class*="sidebarCol"]');
      if (!side) return;

      /*
       * 判定"点的是会话条目"，用 role 而不是哈希类名：
       *  · 工作区节点：role="treeitem" + aria-expanded（点它是展开/折叠，抽屉要留着）
       *  · 会话条目：  role="treeitem" 且**没有** aria-expanded
       *    （实测 YDXeBa_sessionRow 带 aria-selected，projectRow 带 aria-expanded）
       * 之前用「在 _regionArea 里 + 有可点祖先」来猜，结果会话行是普通 div，
       * 猜不中 —— 点会话根本不会收起抽屉。
       */
      var row = t.closest('[role="treeitem"]');
      var isSessionRow = !!row && !row.hasAttribute('aria-expanded');
      var isNewSession = !!t.closest('[class*="_newSession"], button[aria-label="新建会话"]');
      /*
       * 「添加工作区」会打开一个 portal 到 body 的选择框（实测 z-index 1000）。
       * 抽屉不收起来的话它有一大半压在抽屉底下，看着像"点了没反应"。
       * 用户明确要求这里要把抽屉收起来。
       */
      var isAddWorkspace = !!t.closest('button[aria-label="添加工作区"]');
      if (!isSessionRow && !isNewSession && !isAddWorkspace) return;

      // 先让 DSH 完成切换/开弹窗，再收起抽屉
      setTimeout(collapseRail, 80);
    }, true);
  }

  /**
   * 提问卡片（ask_user_question）：底部按钮行会溢出 ——
   * 实测 footer 内容宽 210，而按钮组宽 153，「提交」右边界到 366 > 视口 360 被切掉。
   * 这里给 frame / footer / 按钮组打标记，交给 CSS 换行与右对齐。
   */
  function patchQuestionCard() {
    var skip = null;
    var btns = document.querySelectorAll('button');
    for (var i = 0; i < btns.length; i++) {
      if ((btns[i].textContent || '').indexOf('跳过本题') >= 0) { skip = btns[i]; break; }
    }
    if (!skip) return;
    var actions = skip.parentElement;
    if (actions) actions.setAttribute('data-dsh-qactions', '1');
    var footer = actions ? actions.closest('footer') : null;
    if (footer) footer.setAttribute('data-dsh-qfooter', '1');
    var card = footer ? footer.parentElement : null;
    var frame = card ? card.parentElement : null;
    if (frame) frame.setAttribute('data-dsh-qframe', '1');
  }

  /**
   * 工作区选择器的"添加工作区"对话框：DSH 原生只列出容器里的「主目录」(/root)，
   * 而 /root 是空的，手机存储必须手输路径才能到 —— 用户根本找不到。
   * 这里在标题下方插入一排快捷入口，点一下直接跳到手机上的常用目录。
   */
  function patchWorkspacePicker() {
    var title = null;
    var all = document.querySelectorAll('div,span,h1,h2,h3,p');
    for (var i = 0; i < all.length; i++) {
      if (all[i].children.length === 0 && (all[i].textContent || '').trim() === '选择工作区目录') {
        title = all[i];
        break;
      }
    }
    if (!title) return;
    /*
     * 找准插入点：往上找第一个 display:flex + flex-direction:column 的祖先，
     * 那才是对话框的 header。
     *
     * ⚠️ 不能简单地用 title.parentElement —— 实测它常是 display:contents，
     * 在它后面插入会让快捷入口变成某个 flex 容器的子项，
     * 结果被丢到 x=336、高度撑满整屏（DOM 里有、屏幕上完全看不见）。
     */
    var header = null;
    var n = title;
    for (var j = 0; j < 8 && n && n !== document.body; j++) {
      var st = getComputedStyle(n);
      if (st.display === 'flex' && st.flexDirection === 'column') { header = n; break; }
      n = n.parentElement;
    }
    if (!header) return;
    if (header.querySelector('[data-dsh-shortcuts]')) return;

    var box = document.createElement('div');
    box.setAttribute('data-dsh-shortcuts', '1');
    var items = [
      ['📱 手机存储', '/sdcard'],
      ['📂 dsh 工作区', '/sdcard/dsh'],
      ['⬇️ 下载', '/sdcard/Download'],
      ['🖼 相册', '/sdcard/DCIM'],
      ['📷 图片', '/sdcard/Pictures'],
      ['📄 文档', '/sdcard/Documents'],
    ];
    for (var k = 0; k < items.length; k++) {
      (function (item) {
        var chip = document.createElement('button');
        chip.type = 'button';
        chip.textContent = item[0];
        chip.addEventListener('click', function (ev) {
          ev.preventDefault();
          ev.stopPropagation();
          renderDirMenu(item[1]);     // 进入可下钻的目录菜单，而不是直接选定
        }, true);
        box.appendChild(chip);
      })(items[k]);
    }
    // 插到标题之后（header 的第一个子元素通常就是标题行）
    var second = header.children.length > 0 ? header.children[0].nextSibling : null;
    header.insertBefore(box, second);
  }

  /** 找到选择器对话框的 header 容器（display:flex + column）。 */
  function pickerHeader() {
    var title = null;
    var all = document.querySelectorAll('div,span,h1,h2,h3,p');
    for (var i = 0; i < all.length; i++) {
      if (all[i].children.length === 0 && (all[i].textContent || '').trim() === '选择工作区目录') {
        title = all[i];
        break;
      }
    }
    if (!title) return null;
    var n = title;
    for (var j = 0; j < 8 && n && n !== document.body; j++) {
      var st = getComputedStyle(n);
      if (st.display === 'flex' && st.flexDirection === 'column') return n;
      n = n.parentElement;
    }
    return null;
  }

  /**
   * 渲染「可下钻的目录选择菜单」。
   *
   * 目录列表通过 JS 桥 DshAndroid.listDirs() 从 App 侧获取 ——
   * WebView 里的 JS 本身无法枚举设备文件系统。
   */
  function renderDirMenu(path) {
    var header = pickerHeader();
    if (!header) return;
    var old = document.querySelector('[data-dsh-dirmenu]');
    if (old) old.remove();

    var panel = document.createElement('div');
    panel.setAttribute('data-dsh-dirmenu', '1');

    var p = document.createElement('div');
    p.className = 'dsh-dm-path';
    p.textContent = path;
    panel.appendChild(p);

    var bar = document.createElement('div');
    bar.className = 'dsh-dm-bar';

    var up = document.createElement('button');
    up.type = 'button';
    up.textContent = '↑ 上级';
    up.addEventListener('click', function (ev) {
      ev.preventDefault();
      ev.stopPropagation();
      var parent = path.replace(/\/[^/]+\/?$/, '');
      renderDirMenu(parent === '' ? '/' : parent);
    }, true);
    bar.appendChild(up);

    var pick = document.createElement('button');
    pick.type = 'button';
    pick.className = 'dsh-dm-pick';
    pick.textContent = '✓ 选择此目录';
    pick.addEventListener('click', function (ev) {
      ev.preventDefault();
      ev.stopPropagation();
      choosePath(path);            // 走 DSH 原生流程提交
    }, true);
    bar.appendChild(pick);
    panel.appendChild(bar);

    var list = document.createElement('div');
    list.className = 'dsh-dm-list';
    var dirs = [];
    try {
      if (window.DshAndroid && window.DshAndroid.listDirs) {
        dirs = JSON.parse(window.DshAndroid.listDirs(path)) || [];
      }
    } catch (e) {
      dirs = [];
    }
    if (!dirs.length) {
      var empty = document.createElement('div');
      empty.className = 'dsh-dm-empty';
      empty.textContent = '(此目录下没有子文件夹)';
      list.appendChild(empty);
    } else {
      for (var i = 0; i < dirs.length; i++) {
        (function (d) {
          var b = document.createElement('button');
          b.type = 'button';
          b.textContent = '📁 ' + d.n;
          b.addEventListener('click', function (ev) {
            ev.preventDefault();
            ev.stopPropagation();
            renderDirMenu(d.p);
          }, true);
          list.appendChild(b);
        })(dirs[i]);
      }
    }
    panel.appendChild(list);
    header.insertBefore(panel, header.children[1] || null);
  }

  /** 驱动 DSH 原生的"编辑路径 → 回车 → 打开"流程，跳到指定目录。 */
  function choosePath(path) {
    var edit = document.querySelector('button[aria-label="编辑路径"]');
    if (edit) edit.click();
    var tries = 0;
    (function waitInput() {
      tries++;
      /*
       * ⚠️ 必须排除 type=file —— 页面里第一个 input 往往是附件选择器，
       * 而浏览器禁止脚本给文件输入框赋非空值，会抛
       *   InvalidStateError: This input element accepts a filename...
       */
      var inp = document.querySelector('input[type="text"], input:not([type="file"])');
      if (!inp) {
        if (tries < 15) setTimeout(waitInput, 200);
        return;
      }
      inp.focus();
      try {
        var setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
        setter.call(inp, path);
        inp.dispatchEvent(new Event('input', { bubbles: true }));
      } catch (e) {
        inp.value = path;
        inp.dispatchEvent(new Event('input', { bubbles: true }));
      }
      setTimeout(function () {
        ['keydown', 'keyup'].forEach(function (t) {
          inp.dispatchEvent(new KeyboardEvent(t, {
            key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true,
          }));
        });
        setTimeout(function () {
          var bs = document.querySelectorAll('button');
          for (var i = 0; i < bs.length; i++) {
            if ((bs[i].textContent || '').trim() === '打开') { bs[i].click(); return; }
          }
        }, 800);
      }, 300);
    })();
  }

  /**
   * 判断当前是不是"落地页"（还没选工作区的新对话页）。
   *
   * 用途：右侧边栏那个开关只在真正干活时才出现 ——
   * 落地页把它打开里面什么文件都没有，留着纯占地方（用户要求 UI 整洁）。
   * 判据是输入框上方那个工作区按钮的文字：落地页显示「选择工作区」，
   * 选了工作区之后会变成工作区名字（例如「dsh」）。
   */
  function markLanding() {
    if (!document.body) return;
    var bs = document.querySelectorAll('button');
    var landing = false;
    for (var i = 0; i < bs.length; i++) {
      if ((bs[i].textContent || '').trim() === '选择工作区') { landing = true; break; }
    }
    if (landing) document.body.setAttribute('data-dsh-landing', '1');
    else document.body.removeAttribute('data-dsh-landing');
  }

  var pending = false;
  function scheduleApply() {
    if (pending) return;
    pending = true;
    setTimeout(function () { pending = false; apply(); }, 150);
  }

  function apply() {
    ensureStyle();
    var narrow = window.innerWidth < NARROW_PX;
    root.setAttribute('data-dsh-mobile', narrow ? '1' : '0');
    if (!narrow) return;

    markLanding();
    patchSidebar();
    patchDrawerAutoClose();
    patchQuestionCard();
    patchWorkspacePicker();

    var dlg = settingsDialog();
    if (!dlg) { wasOpen = false; return; }

    // 每次"重新打开"设置都回到列表态
    if (!wasOpen) { root.setAttribute('data-dsh-view', 'list'); wasOpen = true; }
    if (!root.getAttribute('data-dsh-view')) root.setAttribute('data-dsh-view', 'list');
    injectBackButton(dlg);
    injectCloseButton(dlg);
    injectAppSettingsEntry(dlg);
  }

  var wasOpen = false;

  // 点分类 → 进详情态（捕获阶段，先于 React 处理）
  document.addEventListener('click', function (e) {
    if (window.innerWidth >= NARROW_PX) return;
    var t = e.target;
    if (!t || !t.closest) return;
    var cell = t.closest('[role="dialog"] > nav button');
    if (cell) {
      root.setAttribute('data-dsh-view', 'detail');
      setTimeout(apply, 30);
    }
  }, true);

  new MutationObserver(scheduleApply).observe(document.documentElement, {
    childList: true, subtree: true, attributes: true, attributeFilter: ['class'],
  });
  window.addEventListener('resize', scheduleApply);
  apply();

  W.__dshMobilePatch = apply;
})();
