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
    /*
     * 列表态：藏右栏。
     *
     * ⚠️ 这里必须写成 **"不是详情态就藏"**，而不是 "是列表态才藏"。
     *
     * 原因：弹窗刚渲染出来的那一瞬间，JS 还没来得及打上 data-dsh-view
     * （apply() 走的是 150ms 去抖）。如果条件是 `[data-dsh-view="list"]`，
     * 那一刻两个条件都不成立 → **左右两栏同时可见**，
     * 用户就会看到"打开设置时先闪一下「通用设置」详情页"。
     * 逐帧实测：t=675ms 弹窗出现、view=null、右栏可见；
     * 直到 t=831ms JS 补上 view=list 才藏起来 —— 中间闪了 156ms。
     *
     * 反过来写之后，弹窗一出现（此时 <html data-dsh-mobile="1"> 早就有了）
     * 右栏就已经是隐藏的，闪不出来。
     */
    'html[data-dsh-mobile="1"]:not([data-dsh-view="detail"]) [role="dialog"]:has(>nav)>div{display:none!important;}',
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
    /*
     * 右侧栏的「图片预览」：把原图缩到能看全。
     *
     * 实测（聊天里文件卡片 →「在侧边栏预览」）：DSH 的预览组件是给**桌面**设计的，
     * 图片按原始尺寸渲染、不做任何缩放：
     *   .JFRUHG_frame { width:max-content; min-width:100%; padding:8px }
     *   .JFRUHG_image { width:auto; max-width:none; max-height:none; margin:auto }
     * 于是 1080×2376 的截图在 360px 宽的面板里仍然是 1080px 宽，
     * 只露出一个角、还能横向拖 —— 用户看到的就是
     * **"点预览弹出来了，但什么都看不见"**。
     * 实测数据：img 盒 1080×2376 且 y=-1177；body scrollWidth=1096 vs clientWidth=352。
     *
     * 手机上正确的行为是"缩到能看全"：宽度撑满面板、但**不放大**小图，
     * 高度按比例，纵向仍然可以滚（长截图要能往下看）。
     * 修后实测：img 336×739、scrollWidth 352 = clientWidth 352，横向不再溢出。
     *
     * 定位用 data-image-preview / data-sidebar-right-panel 两个**稳定属性**，
     * 不依赖 CSS Module 的哈希类名；也**不加** data-dsh-mobile 限定 ——
     * 平板的右侧栏一样窄，同样需要缩。
     */
    '[data-sidebar-right-panel] [data-image-preview]{',
    '  width:100%!important;min-width:0!important;',
    '  height:auto!important;min-height:0!important;',
    '}',
    '[data-sidebar-right-panel] [data-image-preview]>img{',
    '  width:auto!important;max-width:100%!important;',
    '  height:auto!important;max-height:none!important;margin:0 auto!important;',
    '}',
    /*
     * 会话行右侧的「…」**常显**。
     *
     * 桌面端靠 hover 才显示它；手机上点一下行就直接切会话了，
     * 于是"点「…」开菜单"这条路根本走不到（这正是用户反馈的
     * "为了归档得先被迫切走一次"）。
     *
     * 试过"长按出菜单"，不可行：DSH 的 Menu 带 closeOnPointerLeave，
     * 手指一抬菜单就关（详见下面那段说明）。所以最稳的做法是让「…」
     * 一直看得见、点得到 —— 点它开菜单、点行切会话，互不干扰。
     *
     * 0.2.0 的 rowActions 里还多了「归档会话」「置顶会话」两个内联按钮，
     * 三个图标并排太挤，这里只留「…」（归档/置顶在菜单里也有）。
     */
    'html[data-dsh-mobile="1"] [class*="sidebarCol"] [role="treeitem"]:not([aria-expanded]) [class*="rowActions"]{',
    '  display:inline-flex!important;',
    '}',
    'html[data-dsh-mobile="1"] [class*="sidebarCol"] [role="treeitem"]:not([aria-expanded]) [class*="rowActions"] button:not([aria-label*="的操作"]){',
    '  display:none!important;',
    '}',
    /*
     * 右侧栏 / 插件页打开时**藏掉浮动鲸鱼**。
     *
     * 这两个界面都是整屏的，而鲸鱼固定在左上角 (12,12) 34×34 ——
     * 实测正好压住右侧栏的「关闭」按钮和插件页的标题（用户反馈"挡视线"）。
     * 用户明确说了宁可隐藏也不要拖动，那就隐藏。
     */
    'body:has([data-sidebar-right-panel][data-sidebar-right-open]) #dsh-rail-btn,',
    'body:has([data-plugin-panel]) #dsh-rail-btn{',
    '  display:none!important;',
    '}',  ].join('\n');

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
   * 在 DSH 设置页的分类列表末尾追加本机的两项入口
   * （「API Key」和「手机权限」，都带线条小图标）
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

    /*
     * ⚠️ 必须拿**未选中**的原生按钮当模板。
     *
     * 原来用的是 navList.querySelector('button') —— 那是**第一个**按钮，
     * 而它通常正是当前选中的那一项，className 里带着 VOzbGW_active。
     * 于是我们追加的每一行都继承了"选中"样式：
     * 四个入口**永远全部高亮**（实测 bg=rgb(67,69,74)，而未选中的原生项是透明的），
     * 用户看到的就是"没点它们也亮着，颜色还和原生不一样"。
     *
     * 修法：优先找一个不带 active 的原生按钮；实在找不到，就把 active 那一段剥掉。
     */
    var protoCls = '';
    var protoBtn = null;
    var btns = navList.querySelectorAll('button');
    for (var i = 0; i < btns.length; i++) {
      if (!/active|select|current|checked/i.test(String(btns[i].className))) {
        protoCls = String(btns[i].className);
        protoBtn = btns[i];
        break;
      }
    }
    if (!protoCls && btns.length) {
      protoCls = String(btns[0].className).split(/\s+/).filter(function (c) {
        return c && !/active|select|current|checked/i.test(c);
      }).join(' ');
      protoBtn = btns[0];
    }

    /*
     * 简易白色线条图标（24×24 网格，跟着文字颜色走）。
     *
     * 为什么要自己画：原生分类前面都带一个线条小图标，我们之前注入的入口是**纯文字**，
     * 混在原生列表里一眼就能看出"这不是原生的"。
     * 这里用最省的办法对齐 —— **克隆一个原生按钮**（连它的 svg 一起），
     * 再把 svg 里的路径换成我们的，于是尺寸/描边/颜色自动跟原生一致，
     * 也不用去猜它的 CSS Module 类名。
     */
    /*
     * ⚠️ 坐标系必须和原生一致：原生这些图标是 **16×16 网格**、描边 1.3，
     * 而我们最初画在 24×24 上 —— 塞进 16px 的 svg 里，描边会被缩成 ~1.07px、
     * 图形也偏小，于是"大小和粗细跟别的不统一"（用户一眼就看出来了）。
     * 现在统一画在 16×16 上，描边沿用原生的（CSS 给，不用自己设）。
     */
    var ICON_KEY = '<circle cx="8" cy="4.6" r="2.5"/>'      // 竖着的钥匙：上面是空心钥匙柄
                 + '<path d="M8 7.1v7.3"/>'                  // 杆
                 + '<path d="M8 10.4h2.9"/>'                 // 齿
                 + '<path d="M8 12.6h2.2"/>';
    var ICON_PHONE = '<rect x="4.7" y="1.9" width="6.6" height="12.2" rx="1.7"/>'
                   + '<path d="M6.9 3.7h2.2"/>';

    function addEntry(attr, label, jsMethod, iconPath) {
      var btn;
      if (protoBtn) {
        // 克隆原生项：拿到它的 svg 小图标与完全同款的排版
        btn = protoBtn.cloneNode(true);
        btn.removeAttribute('id');
        var texts = btn.querySelectorAll('*');
        var labelEl = null;
        for (var j = 0; j < texts.length; j++) {
          if (texts[j].children.length === 0 && (texts[j].textContent || '').trim()) {
            labelEl = texts[j];
          }
        }
        if (labelEl) labelEl.textContent = label;
        else btn.textContent = label;

        var svg = btn.querySelector('svg');
        if (svg && iconPath) {
          /*
           * ⚠️ **不要**去改 viewBox / stroke-* —— 克隆来的 svg 本来就是
           * 16×16 网格、描边 1.3、颜色由 CSS（class）给。
           * 我们只要把路径换成同样画在 16×16 网格上的，尺寸/粗细/颜色
           * 就自动和原生分类完全一致。
           */
          /*
           * ⚠️ 描边必须写在**我们自己的 <g> 上**。
           * 原生的 svg 虽然写着 stroke-width=1.3，但它的路径是**填充型**的
           * （computed stroke 是 none）—— 我们塞进去的是线条路径，光靠继承会
           * 什么都画不出来（实测：图标整个消失）。
           * 包一层 <g> 显式给 stroke/stroke-width，颜色用 currentColor 跟文字一致。
           */
          svg.innerHTML = '<g fill="none" stroke="currentColor" stroke-width="1.3"'
                        + ' stroke-linecap="round" stroke-linejoin="round">'
                        + iconPath + '</g>';
        }
      } else {
        // 拿不到原生模板就退回纯文字（至少不会崩）
        btn = document.createElement('button');
        btn.type = 'button';
        if (protoCls) btn.className = protoCls;
        btn.textContent = label;
      }
      btn.setAttribute(attr, '1');
      btn.addEventListener('click', function (ev) {
        ev.preventDefault();
        ev.stopPropagation();
        /*
         * 刻意**不关闭**设置弹窗。
         *
         * 我一开始想"先关掉再打开本机页面，免得退出后掉回原生分类"，
         * 但那是基于猜测做的，用户立刻指出不对：关掉之后从本机页面返回，
         * 人落在"侧边栏还开着的对话页"，而不是刚才那个设置页 —— 更迷失。
         *
         * 正确做法是让弹窗留着，返回时回到**分类列表**这一层
         * （见下面的 restoreListOnReturn）。
         */
        markPendingReturn();
        if (window.DshAndroid && window.DshAndroid[jsMethod]) {
          window.DshAndroid[jsMethod]();
        }
      }, true);
      navList.appendChild(btn);
    }

    /*
     * 点了本机入口之后，从那个页面返回时要把设置弹窗恢复到**分类列表**这一层。
     *
     * 为什么需要：这些入口不是 DSH 自己的分类，点它们不会改变弹窗的"当前分类"。
     * 而本机页面抢走前台再交还时，DSH 的弹窗可能已经落到某个原生分类的内容页上，
     * 于是用户看到的是"进去转一圈、退出却停在一个原生设置页"。
     * 回到 list 态就对了。
     */
    function markPendingReturn() {
      try {
        root.setAttribute('data-dsh-view', 'list');
        var t = setInterval(function () {
          if (document.visibilityState !== 'visible') return;
          root.setAttribute('data-dsh-view', 'list');
          scheduleApply();
          clearInterval(t);
        }, 200);
        setTimeout(function () { clearInterval(t); }, 60000);
      } catch (e) { /* 忽略 */ }
    }

    /*
     * 本机入口收成两个（用户要求）：
     *   · API Key —— 单独留一个，钥匙图标
     *   · 手机权限 —— 其余全部（手机控制/白名单/插件市场/常驻服务/内核升级/桌面图标）
     *     收进这个大类，图标是一部线条手机
     * 并且**去掉所有「（本机）」后缀** —— 混在原生分类里那一截括号最显杂乱。
     */
    addEntry('data-dsh-appsettings', 'API Key', 'openAppSettings', ICON_KEY);
    addEntry('data-dsh-phoneperm', '手机权限', 'openPhonePerm', ICON_PHONE);
  }

  /**
   * 宽屏（平板）：把"只剩图标轨道"的侧边栏展开成**完整会话列表**。
   *
   * <h3>为什么需要它</h3>
   * 真机实测（wm size 1600x2560 + density 320 → 逻辑宽 800dp，也就是绝大多数
   * 平板竖屏的那一档）：
   *   · DSH 把侧边栏渲染成 **56px 的图标轨道**，里面只有
   *     新建会话 / 插件 / 添加工作区 / 搜索会话 / 设置 五个图标；
   *   · **会话行根本没进 DOM**（`[role="treeitem"]` 数量为 0）——
   *     不是"被藏起来了"，纯 CSS 救不回来；
   *   · 于是用户连"怎么换会话"都找不到（用户反馈的原话就是这个）。
   *
   * 而在 **1707dp** 下同一份代码会渲染 280px 的侧边栏 + 5 条会话行 ——
   * 所以这是 DSH 自己的**宽度断点**行为，不是"状态被收起了"
   * （localStorage 里也没有任何 sidebar 展开/收起 的键，我们扒过）。
   *
   * <h3>怎么展开的</h3>
   * 侧边栏里有一个 `aria-label="打开侧边栏"` 的按钮，大小是 **0×0**（看不见），
   * 但**点它有效**：实测点一下侧边栏立刻 56 → 280，会话行 0 → 5。
   * 这是 DSH 自己的开关，跟着它走最稳 —— 不自己去改它的布局和状态机。
   *
   * <h3>为什么最多只点 3 次、而且成功一次就收手</h3>
   * 用户手动收起侧边栏是**有意的**，不能每次 DOM 变动都把它抢回来
   * （那会变成"我一收它就弹回来"）。所以：成功展开一次就永久收手；
   * 万一 DSH 渲染时序把它弹回去，最多再补 2 次。
   */
  var sidebarExpandTries = 0;

  function patchSidebarExpanded() {
    if (W.__dshExpandDone) return;
    // 手机上抽屉本来就该是收起的（那是另一方 patchSidebar 的活），这里退出
    if (window.innerWidth < NARROW_PX) { W.__dshExpandDone = true; return; }

    var side = document.querySelector('[class*="sidebarCol"]');
    if (!side) return;                      // 还没渲染出来，下次 apply 再看

    var wide = side.getBoundingClientRect().width >= 200;
    var rows = document.querySelectorAll('[role="treeitem"]').length;
    if (wide || rows > 0) {                 // 已经是完整侧边栏了
      W.__dshExpandDone = true;
      return;
    }
    if (sidebarExpandTries >= 3) { W.__dshExpandDone = true; return; }

    var b = null;
    var bs = side.querySelectorAll('button');
    for (var i = 0; i < bs.length; i++) {
      if ((bs[i].getAttribute('aria-label') || '') === '打开侧边栏') { b = bs[i]; break; }
    }
    if (!b) return;                         // 这一档没有这个按钮，下次再说

    sidebarExpandTries++;
    b.click();
    if (sidebarExpandTries >= 3) W.__dshExpandDone = true;
  }

  /**
   * 手机（窄屏）：DSH 的"折叠"态仍然占 56px 宽（图标轨道）。
   * 按用户要求参考 DeepSeek C 端：查看工作界面时隐藏侧边栏，点左上角鲸鱼再唤出。
   * 做法：把折叠态真正收到 0 宽，并浮出一个鲸鱼按钮调用 DSH 自己的切换逻辑。
   *
   * （平板走的是上面 patchSidebarExpanded —— 方向正好相反：大屏要常驻。）
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
       * 我们自己正在编排抽屉（比如从插件页返回：要先切走再切回来），
       * 中间那几次会话切换**不能让抽屉自己收起** —— 否则用户看到的就是
       * "抽屉先回去、又弹回来、再跟对话一起收"，很乱。见 __dshHandleBack。
       */
      if (W.__dshHoldDrawer) return;

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
       * 点「…」（rowActions 那一小块）只是开菜单，不等于"选完了会话"，
       * 抽屉要留着。DSH 自己的 onClick 里有 e.stopPropagation()，
       * 本来就不会顺带切换会话；但抽屉收不收是在**这里**判定的，
       * 不排除掉就会出现"点一下会话菜单，整个侧边栏没了"。
       */
      if (t.closest('[class*="sidebarCol"] [class*="rowActions"]')) return;

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
      /*
       * 会话行现在是"两次点击"（见 patchSessionRowTwoTap）：
       * 第一次点只亮出「…」，**并没有切换会话**，抽屉自然不该收。
       * 那次点击被打上了 __dshConsumed 标记。两个监听器都挂在 document 的
       * 捕获阶段，而 stopPropagation 拦不住**同一个节点**上的其它监听器，
       * 所以这里一定看得到它（patchSessionRowTwoTap 注册得更早，
       * 由 apply() 里的调用顺序保证，别调换）。
       */
      /*
       * 侧边栏的「插件」入口：点它会把中间栏切成插件页。抽屉不收的话会**盖住**
       * 插件页右上角的「添加插件」按钮（实测那个按钮在 x=236..331，
       * 正好压在 280px 宽的抽屉底下，看起来就像"按钮坏了"）。
       */
      var isPanelRow = !!t.closest('button[aria-label="插件"]');
      var isNewSession = !!t.closest('[class*="_newSession"], button[aria-label="新建会话"]');
      /*
       * 「添加工作区」会打开一个 portal 到 body 的选择框（实测 z-index 1000）。
       * 抽屉不收起来的话它有一大半压在抽屉底下，看着像"点了没反应"。
       * 用户明确要求这里要把抽屉收起来。
       */
      var isAddWorkspace = !!t.closest('button[aria-label="添加工作区"]');
      if (!isSessionRow && !isNewSession && !isAddWorkspace && !isPanelRow) return;

      // 先让 DSH 完成切换/开弹窗，再收起抽屉
      setTimeout(collapseRail, 80);
    }, true);
  }

  /*
   * 【为什么不给会话行做"长按出菜单"】
   *
   * 试过两种写法（touchstart+定时器、pointerdown/up 计时），都不行，
   * 根因不在我们这边：**DSH 的 Menu 组件带 `closeOnPointerLeave: true`**。
   * 手机上一抬手就会派发 pointerleave，于是菜单刚被点开就立刻关掉 ——
   * 长按这条路在触屏上根本立不住。
   *
   * 所以改成"**「…」常显**"：CSS 里让会话行右侧的「…」一直可见（见上面那段），
   * 点它开菜单、点行本身切会话，两者互不干扰。
   * 这也顺手修掉了用户反馈的"连点两下弹出重命名"—— 那正是之前"两次点击"方案的副作用。
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

  /**
   * 回车 = 换行，不再直接发送（用户明确要求）。
   *
   * DSH 沿用桌面语义："Enter 发送、Shift+Enter 换行"。可**手机上根本没有 Shift 键**，
   * 于是想多写两行的人一定会在第一个回车处被提前发出去。
   * 改成：**回车换行，发送只靠右下角那个发送按钮**。
   *
   * 做法：在 document 的**捕获阶段**拦下 Enter —— React 17+ 把事件委托挂在根容器上，
   * 而根容器是 document 的后代，所以捕获阶段一定比它早。
   * preventDefault + stopImmediatePropagation 让 DSH 完全收不到这次回车，
   * 再自己插入换行，行为不依赖 DSH 内部实现。
   *
   * 带修饰键的（Shift/Ctrl/Cmd/Alt + Enter）一律放行，不做干扰。
   */
  function patchComposerEnter() {
    if (W.__dshEnterPatch) return;
    W.__dshEnterPatch = true;
    document.addEventListener('keydown', function (ev) {
      if (ev.key !== 'Enter' && ev.keyCode !== 13) return;
      if (ev.shiftKey || ev.ctrlKey || ev.metaKey || ev.altKey) return;
      var t = ev.target;
      if (!t || !t.closest) return;
      if (!t.closest('[contenteditable="true"]')) return;

      ev.preventDefault();
      ev.stopImmediatePropagation();

      /*
       * ⚠️ 千万不要自己插换行。
       *
       * 这个输入框是 **Lexical** 编辑器（DOM 里有 `data-lexical-text`）。
       * 实测 `document.execCommand('insertLineBreak')` 会绕过 Lexical 的内部模型：
       * 它把已有内容整个替换成一个文本节点 "\n"（"AAA" 直接没了），
       * 而且 HTML 会把纯空白折叠掉 —— 输入框高度纹丝不动，**视觉上根本没换行**。
       *
       * 正确做法：把这次回车**改写成带 shiftKey 的 keydown 再派发一次**，
       * 让 Lexical 走它自己那条「Shift+Enter = 软换行」的命令路径。
       * 实测产物与 DSH 原生 Shift+Enter **完全一致**：
       *   <p><span>AAA</span><br><br data-lexical-managed-linebreak="true"></p>
       *   innerText "AAA\n\n"、输入框高度 36 → 52（真的多了一行）
       *
       * 合成事件带 shiftKey=true，会被上面那行修饰键判断直接放行，不会递归。
       */
      var target = ev.target || document.activeElement;
      if (target && target.dispatchEvent) {
        target.dispatchEvent(new KeyboardEvent('keydown', {
          key: 'Enter', code: 'Enter', keyCode: 13, which: 13,
          shiftKey: true, bubbles: true, cancelable: true, composed: true,
        }));
      }
    }, true);
  }

  /**
   * 把 DSH 的「在默认程序中打开 / 打开方式」改成走**安卓**。
   *
   * 【问题】这两个动作是**服务端**行为：网页 POST `/open-in-app/open`，
   * 由运行 dsh 的那台主机去拉起桌面程序。手机上主机就是 proot 容器，
   * 里面没有桌面环境，于是必然失败 —— 用户看到的是
   * 「此主机没有可用的桌面，无法打开文件或文件夹」。
   * （实测现场：聊天里文件卡片右下角的「打开方式」，以及正文里的
   *   `/sdcard/...` 文件引用，aria-label 都是"在默认程序中打开 …"。）
   *
   * 【做法】拦 `window.fetch`，命中那次 POST 就改调 App 的 JS 桥
   * `DshAndroid.openPath(path)`（App 那边换成 content:// 再弹系统的
   * 「选择打开方式」），并**伪造一个 200 响应**，免得 UI 弹出 open failed。
   *
   * 【为什么拦 fetch 而不是拦按钮】按钮有三处（文件卡片、正文引用、
   * 预览面板工具栏），类名还是 CSS Module 的哈希；而服务端调用只有这一个端点。
   * 拦端点既少又稳，DSH 以后加新入口也自动覆盖。
   */
  function patchOpenInApp() {
    if (W.__dshOpenInApp) return;
    if (typeof W.fetch !== 'function') return;
    W.__dshOpenInApp = true;
    var orig = W.fetch;
    W.fetch = function (input, init) {
      try {
        var url = typeof input === 'string' ? input
                : (input && input.url) ? input.url : '';
        if (url.indexOf('/open-in-app/open') >= 0
            && init && init.method === 'POST' && init.body
            && W.DshAndroid && W.DshAndroid.openPath) {
          var body = JSON.parse(init.body);
          var path = body && body.path;
          if (path) {
            W.DshAndroid.openPath(String(path));
            // 同一个 URL 的调用方只检查 response.ok，给个空 JSON 就够
            return Promise.resolve(new Response('{}', {
              status: 200,
              headers: { 'content-type': 'application/json' },
            }));
          }
        }
      } catch (e) {
        // 解析失败就走原路，绝不因为我们的拦截把页面搞坏
      }
      return orig.apply(this, arguments);
    };

    /*
     * 光拦 fetch 还不够 —— 实测点了没反应。
     *
     * 因为 DSH 的交互是两步：先 GET /open-in-app/apps 拿"可用的桌面程序"列表，
     * 用户从菜单里选一个，才会 POST /open-in-app/open。
     * 而手机上那个列表**是空的**（没有 VS Code 之类的桌面程序），
     * 于是菜单里一个选项都没有，点击根本不走到 launch()，fetch 也就没机会被拦。
     *
     * 所以在**点击**这一层就接管：识别那两类按钮，直接取路径交给 App。
     *   ① 正文里的文件引用：aria-label = "在默认程序中打开 <路径>"
     *   ② 预览面板工具栏的「打开方式」：路径在面板头部（[class*="_path"][title]）
     */
    document.addEventListener('click', function (ev) {
      var t = ev.target;
      if (!t || !t.closest) return;
      var path = null;

      var chip = t.closest('button[aria-label^="在默认程序中打开 "]');
      if (chip) {
        path = chip.getAttribute('title')
            || (chip.getAttribute('aria-label') || '').replace('在默认程序中打开 ', '');
      }
      if (!path) {
        var tool = t.closest('[data-sidebar-right-panel] button[aria-label="打开方式"]');
        if (tool) {
          var p = document.querySelector('[data-sidebar-right-panel] [class*="_path"][title]');
          if (p) path = p.getAttribute('title');
        }
      }
      if (!path) return;
      if (!(W.DshAndroid && W.DshAndroid.openPath)) return;
      // 拦在 React 之前：既别弹出那个空菜单，也别再让服务端去拉桌面程序
      ev.stopPropagation();
      ev.preventDefault();
      W.DshAndroid.openPath(String(path));
    }, true);
  }

  /**
   * 记住"插件页打开之前，我在哪个会话"。
   *
   * 【为什么非记不可】插件页占的是**中间栏**，而 DSH 在插件页打开时
   * **不给任何会话行打 `aria-selected`**（真机实测：进插件页后抽屉里那几行
   * 全都不高亮，返回键处理里再问"当前会话是谁"已经问不出来了）。
   * 0.2.0 的第一版就是栽在这里 —— 找不到目标，于是走了"只把抽屉拉开"的安全退化，
   * 用户看到的就是"按返回了，背景还是插件页"。
   *
   * 所以趁还能看到高亮的时候（每次 apply() 都会跑）把它记下来。
   * 三样都存，按可靠性从高到低用：
   *   row     —— DOM 节点本身（React 按 key 复用元素，切走再回来还是它）
   *   key     —— data-row-key，形如 `session:<uuid>`（不一定有这个属性）
   *   title   —— 标题前 8 个字（节点被重建时的最后一道保险，
   *              只取标题、不碰相对时间，因为时间会从"刚刚"变成"1天"）
   */
  function rememberCurrentSession() {
    var sel = document.querySelector(
      '[class*="sidebarCol"] [role="treeitem"][aria-selected="true"]:not([aria-expanded])');
    if (!sel) return;
    W.__dshLastSession = {
      row: sel,
      key: sel.getAttribute('data-row-key') || '',
      title: (sel.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 8),
    };
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

    /*
     * 与宽度无关的修补 —— 平板（>= 700px）同样要生效。
     *
     * 以前这里是 `if (!narrow) return;`，等于把平板用户直接放过去了：
     * 他们拿到的是一个"完全没打过补丁"的 DSH —— 回车会直接发送、
     * 「添加工作区」对话框里没有手机目录快捷入口、落地页右上角还挂着一个
     * 多余的侧边栏按钮。这三件事跟窄屏布局毫无关系，所以无条件执行。
     */
    markLanding();
    patchComposerEnter();
    patchWorkspacePicker();
    // 「在默认程序中打开 / 打开方式」→ 交给安卓（与屏幕宽度无关，平板同样需要）
    patchOpenInApp();
    // 记下当前会话（插件页一开，这个信息就再也问不出来了，见函数注释）
    rememberCurrentSession();
    /*
     * 平板：把"只剩图标轨道"的侧边栏展开成完整会话列表 —— 大屏上会话列表
     * 本来就该常驻可见，否则用户连"怎么换会话"都找不到。
     * 函数内部自己按宽度判断，窄屏会直接退出（手机上抽屉该是收起的）。
     */
    patchSidebarExpanded();

    /*
     * 本机入口（「API Key」/「手机权限」）必须**与屏幕宽度无关**。
     *
     * 用户反馈：**平板用户看不到白名单这些设置**。
     * 原因就在这里 —— 它原先放在下面 `if (!narrow) return;` 之后，
     * 于是 >= 700px 的设备整个补丁在这行就返回了，设置里根本没有这两项，
     * 而 DSH 原生界面里又没有别的入口能进本 App 的设置，等于全丢了。
     *
     * 这两项注入的是"点了打开安卓原生界面"的按钮，跟窄屏布局毫无关系，
     * 所以无条件执行（幂等：已经注入过就直接返回）。
     */
    var dlgAny = settingsDialog();
    if (dlgAny) injectAppSettingsEntry(dlgAny);

    /*
     * 下面这些都是手机窄屏专属的布局修补，平板不能套用：
     *   - patchSidebar / patchDrawerAutoClose：平板本来就是常驻侧边栏，没有抽屉
     *   - patchQuestionCard：配套的 CSS 全部限定在 html[data-dsh-mobile="1"] 下
     *   - 设置页的「返回 / 关闭」和 data-dsh-view 单栏切换：平板是双栏，不需要
     */
    if (!narrow) return;

    patchSidebar();
    /*
     * ⚠️ 顺序不能调换：patchSessionRowTwoTap 必须比 patchDrawerAutoClose
     * 更早注册到 document 的捕获阶段 —— 它给"第一次点击"打的
     * __dshConsumed 标记，是给后者读的。
     */
    patchDrawerAutoClose();
    patchQuestionCard();

    var dlg = settingsDialog();
    if (!dlg) {
      /*
       * 设置已关闭：把视图状态复位回列表态。
       *
       * 否则"在某分类详情里关掉设置、再打开"会直接落在详情页上。
       * 复位放在关闭时做（而不是打开时做），就是为了配合上面那条 CSS ——
       * 弹窗出现的那一刻状态已经是 list，右栏一开始就是隐藏的，不会闪。
       */
      wasOpen = false;
      if (root.getAttribute('data-dsh-view') !== 'list') {
        root.setAttribute('data-dsh-view', 'list');
      }
      return;
    }

    // 兜底：万一状态丢了，也保证是列表态
    if (!root.getAttribute('data-dsh-view')) root.setAttribute('data-dsh-view', 'list');
    wasOpen = true;
    injectBackButton(dlg);
    injectCloseButton(dlg);
    // 本机入口不在这里注入 —— 见上面：它对平板同样必要，所以已经无条件注入过了。
  }

  var wasOpen = false;

  // 点分类 → 进详情态（捕获阶段，先于 React 处理）
  document.addEventListener('click', function (e) {
    if (window.innerWidth >= NARROW_PX) return;
    var t = e.target;
    if (!t || !t.closest) return;
    // 我们自己注入的「返回 / 关闭」也在 nav 里，但它们各自有处理器，
    // 别在这里先把状态切成 detail（虽然随后会被改回 list，但没必要来回跳）
    if (t.closest('[data-dsh-back],[data-dsh-close]')) return;
    var cell = t.closest('[role="dialog"] > nav button');
    if (cell) {
      root.setAttribute('data-dsh-view', 'detail');
      setTimeout(apply, 30);
    }
  }, true);

  /**
   * 手机系统「返回键」的分层返回。
   *
   * <p>由 App 侧调用：MainActivity.onBackPressed 会先执行这里，
   * 只有在返回 'false' 时才真的退出界面。
   *
   * <p>【为什么需要它】原来 App 没有接管返回键，于是**按一下就直接退出整个界面** ——
   * 用户看到的就"按返回键直接把 App 关了"，而不是"关掉当前这一层"。
   * 手机上正确的行为是由内到外一层层退：设置详情 → 设置弹窗 → 其它浮层 → 右侧栏 → 左侧抽屉。
   *
   * <p>返回字符串而不是布尔：evaluateJavascript 拿到的是 JSON，
   * 用 'true'/'false' 最省事，也不用担心 undefined。
   */
  W.__dshHandleBack = function () {
    try {
      // 1) 设置弹窗里的「分类详情」→ 先退回分类列表
      var dlg = settingsDialog();
      if (dlg && root.getAttribute('data-dsh-view') === 'detail') {
        root.setAttribute('data-dsh-view', 'list');
        apply();
        return 'true';
      }
      // 2) 设置弹窗本身 → 关掉
      if (dlg) {
        var close = dlg.querySelector('[data-dsh-close]');
        if (close) { close.click(); return 'true'; }
      }
      // 3) 其它模态浮层（图片大图、DSH 自己的对话框）—— 它们普遍认 Escape
      var modal = document.querySelector('[role="dialog"][aria-modal="true"]');
      if (modal) {
        window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
        return 'true';
      }
      /*
       * 4) 右侧栏：**分层退**。
       * 面板里如果开着标签页（终端 / 文件预览），第一次返回应该先关掉那个标签、
       * 回到面板首页 —— 用户反馈现在"按返回直接回到对话界面"，跳了一层，不合理。
       */
      var panel = document.querySelector('[data-sidebar-right-panel][data-sidebar-right-open]');
      if (panel) {
        var tabClose = panel.querySelector('[class*="_tabClose"]');
        if (tabClose) { tabClose.click(); return 'true'; }
        var collapse = document.querySelector('button[aria-label="收起右侧边栏"]');
        if (collapse) { collapse.click(); return 'true'; }
      }
      /*
       * 5) 插件页：它占的是**中间栏**，DSH 自己没给返回入口（没按钮、返回键也没用），
       *    用户点进去就出不来。这里替它退：切回当前会话即可。
       */
      if (document.querySelector('[data-plugin-panel]')) {
        /*
         * 插件页占的是**中间栏**，DSH 自己没给返回入口（没按钮、返回键也没用），
         * 用户点进去就出不来，得由我们替它退。
         *
         * 【为什么这么绕】DSH 对"点已经选中的那条会话"是 **no-op**，点了插件页照样在。
         * 所以只能**先切到别的会话把插件页顶掉，再切回来**。
         *
         * 【踩过的两个坑，都是真机实测出来的】
         *  1. "我在哪个会话"在插件页打开时**问不出来** —— 实测进插件页后抽屉里
         *     所有会话行都没有 `aria-selected`（截图里那几行全不高亮）。
         *     0.2.0 第一版就栽在这儿：目标找不到 → 走安全退化 → 只把抽屉拉开、
         *     插件页留着不动，用户看到的正是"按返回了背景还是插件页"。
         *     解法：在**还能看到高亮的时候**把当前会话记下来，
         *     见 rememberCurrentSession()（节点 / row-key / 标题前缀三样都存）。
         *  2. 回切**不能比文本**：相对时间会变（"刚刚" → "1天"），
         *     拿 innerText 前若干字去比就再也比不中。现在优先用**节点引用**
         *     （React 按 key 复用元素，切走再回来还是同一个），再依次兜底。
         *
         * 编排期间用 __dshHoldDrawer 压住"点会话就收抽屉"（见 patchDrawerAutoClose），
         * 否则用户看到的是"抽屉先回去、又弹回来、再跟对话一起收"，一开一合很乱：
         * 全程不开合，只在**最后**收一次。
         *
         * 任何一步没把握（没有别的会话可中转、行还没渲染出来）就**安全退化**成
         * "只拉开抽屉让用户自己点"，绝不把返回键弄坏。
         */
        var rowsOf = function () {
          var all = document.querySelectorAll('[class*="sidebarCol"] [role="treeitem"]');
          var out = [];
          for (var n = 0; n < all.length; n++) {
            // 工作区节点带 aria-expanded，不是会话行
            if (!all[n].hasAttribute('aria-expanded')) out.push(all[n]);
          }
          return out;
        };
        var keyOf = function (r) { return r.getAttribute('data-row-key') || ''; };
        var titleOf = function (r) {
          return (r.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 8);
        };
        var last = W.__dshLastSession || null;
        /* "这一行就是我要回去的会话"：节点 → row-key → 标题前缀 → 最后才看 aria-selected */
        var isTarget = function (r) {
          if (last) {
            if (r === last.row) return true;
            if (last.key && keyOf(r) === last.key) return true;
            if (last.title && titleOf(r) === last.title) return true;
          }
          return r.getAttribute('aria-selected') === 'true';
        };
        var openRail = function () {
          if (document.body.getAttribute('data-dsh-rail-hidden') === '1') {
            var b = document.getElementById('dsh-rail-btn');
            if (b) b.click();
          }
        };
        var start = function () {
          var rows = rowsOf();
          if (!rows.length) { openRail(); return; }
          var cur = null, other = null;
          for (var n = 0; n < rows.length; n++) { if (isTarget(rows[n])) cur = rows[n]; }
          // 中转会话：随便另一条真会话都行（列表里只有会话行，没有空白行）
          for (var m = 0; m < rows.length; m++) {
            if (rows[m] !== cur) { other = rows[m]; break; }
          }
          if (!cur || !other) { openRail(); return; }
          var target = cur;
          W.__dshHoldDrawer = true;
          other.click();                        // 切走 → 插件页被顶掉
          setTimeout(function () {
            var back = rowsOf(), hit = null;
            for (var q = 0; q < back.length; q++) {
              if (back[q] === target) { hit = back[q]; break; }
              if (last && last.key && keyOf(back[q]) === last.key) { hit = back[q]; break; }
              if (last && last.title && titleOf(back[q]) === last.title) { hit = back[q]; break; }
            }
            if (hit) hit.click();               // 再切回原来那个会话
            setTimeout(function () {
              W.__dshHoldDrawer = false;
              /*
               * 只有插件页真的退掉了才收抽屉；
               * 万一是别的意外（中转没生效），就把抽屉留给用户自己点。
               */
              if (!document.querySelector('[data-plugin-panel]')) collapseRail();
            }, 700);
          }, 900);
        };
        var tryStart = function (left) {
          /*
           * 会话行是**懒渲染**的：抽屉没拉开时 rowsOf() 是空的。
           * 拉开后给它几次机会（每 250ms 一次），别一次没渲染出来就放弃。
           */
          if (!rowsOf().length && left > 0) {
            setTimeout(function () { tryStart(left - 1); }, 250);
            return;
          }
          start();
        };

        if (document.body.getAttribute('data-dsh-rail-hidden') !== '1') {
          tryStart(0);
        } else {
          openRail();
          tryStart(5);
        }
        return 'true';
      }      // 5) 左侧抽屉
      if (document.body.getAttribute('data-dsh-rail-hidden') !== '1') {
        if (collapseRail()) return 'true';
      }
    } catch (e) {
      // 出错就当"没处理"，让 App 走原来的退出逻辑 —— 绝不能把返回键卡死
    }
    return 'false';
  };

  new MutationObserver(scheduleApply).observe(document.documentElement, {
    childList: true, subtree: true, attributes: true,
    attributeFilter: ['class', 'aria-selected'],
  });
  window.addEventListener('resize', scheduleApply);

  /*
   * 【前端自检：插件把界面卡死时，把消息递回 App】
   *
   * 真机事故：用户装了两个**为 DSH 0.1.x 写的**第三方插件（dsh-speech /
   * dsh-mobile-gateway），它们的客户端声明了 `inject = ["slots", "settingsScope"]`，
   * 而 `settingsScope` 这个服务名在 DSH 0.2 里已经改成 settings / settingsSchema。
   * 服务永远等不到 → 前端**一直 pending** → 整个 GUI 起不来
   * （要么白屏，要么一屏 "Failed to load plugins … waiting for service"）。
   *
   * 而 App 在"服务就绪"之后会把状态栏收起来 —— 用户连「重试」都点不到，
   * 唯一出路是卸载重装（等于把 150MB 容器再下一遍）。
   * 所以这里必须主动上报，让 App 弹自救面板（停用这些插件 / 安全模式）。
   *
   * 两条判据：
   *   ① 页面出现 DSH 的启动失败文案 → 立刻上报（连原文一起给，App 从里面抠插件名）；
   *   ② 25 秒后主界面仍然空白 → 也上报（兜住"没有任何文案的白屏"）。
   * 正常启动时 ② 会走 else 分支，什么都不做。
   */
  (function bootWatchdog() {
    if (W.__dshBootReported) return;
    var t0 = Date.now();
    function report(text) {
      if (W.__dshBootReported) return;
      W.__dshBootReported = true;
      try {
        if (W.DshAndroid && W.DshAndroid.pluginBootFailed) {
          W.DshAndroid.pluginBootFailed(String(text || ''));
        }
      } catch (e) { /* 桥不可用就算了，别把页面搞崩 */ }
    }
    function tick() {
      if (W.__dshBootReported) return;
      var body = (document.body && document.body.innerText) || '';
      if (/Failed to load plugins|did not activate|waiting for service/i.test(body)) {
        report(body);
        return;
      }
      if (Date.now() - t0 > 25000) {
        var root = document.getElementById('root') || document.body;
        var txt = ((root && root.innerText) || '').replace(/\s+/g, '');
        if (txt.length < 12) report('(空白页面：主界面 25 秒内没有渲染出来)');
        else {
          W.__dshBootReported = true;
          // 界面正常 → 告诉 App 把"连续启动失败"计数清零（见 MainActivity.pageReady）
          try { if (W.DshAndroid && W.DshAndroid.pageReady) W.DshAndroid.pageReady(); } catch (e) { }
        }
        return;
      }
      setTimeout(tick, 1200);
    }
    setTimeout(tick, 1500);
  })();

  /*
   * 【给「手机文件」插件用的插入函数】
   *
   * 插件（plugin/dsh-phone-files）自己不去碰输入框 —— 因为 DSH 的编辑器是
   * React **受控的 contenteditable**：
   *   · 直接改 textContent / innerText，React 收不到变化，一提交就被覆盖；
   *   · 必须走 `execCommand('insertText')`，它会产生 input 事件，React 才知道值变了。
   * 这套经验在这个文件里（顺手也在这里处理"先补一个换行"），插件只管调用。
   *
   * @param paths 绝对路径数组（App 侧列目录给的，形如 /sdcard/DCIM/a.jpg）
   * @return 成功 true；找不到输入框或桥不可用返回 false（插件会退化成"复制到剪贴板"）
   */
  W.__dshPhoneFilesInsert = function (paths) {
    try {
      if (!paths || !paths.length) return false;
      var box = document.querySelector('[contenteditable="true"]');
      if (!box) return false;
      box.focus();
      var text = (box.innerText && box.innerText.trim() ? "\n" : "") + paths.join("\n");
      var ok = false;
      try { ok = document.execCommand('insertText', false, text); } catch (e) { ok = false; }
      if (!ok) {
        // 退路：合成一个粘贴事件（有的 WebView 上 execCommand 返回 false 但其实已经生效）
        try {
          var dt = new DataTransfer();
          dt.setData('text/plain', text);
          box.dispatchEvent(new ClipboardEvent('paste', {
            clipboardData: dt, bubbles: true, cancelable: true,
          }));
          ok = true;
        } catch (e2) { ok = false; }
      }
      return !!ok;
    } catch (e) {
      return false;
    }
  };

  apply();

  W.__dshMobilePatch = apply;
})();
