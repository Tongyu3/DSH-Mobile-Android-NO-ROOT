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
  /*
   * ⚠️ 页面加载失败时，Chromium 显示的是自己的内置错误页
   * （chrome-error://chromewebdata/），那个文档里 **documentElement 是 null**。
   * 我们在 onPageFinished 里是无条件注入的，所以这里必须先退出：
   * 否则下面 `root.setAttribute(...)` 立刻抛异常，整段补丁只跑了一半。
   * （错误页上没有任何 DSH 结构，补丁本来就无事可做。）
   *
   * 放在**最前面**是有意的：一旦发现不是正常文档，就整段不执行 ——
   * 不留半个状态（observer 装了、手表没装之类的）。
   */
  if (!document.documentElement) return;
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
     * ⚠️ 第七轮那个"按 DSH 按钮标签反向镜像状态"的 observer 与轮询已**删除**。
     *
     * 逐帧实测（tools/rail-who-moves.js）证明 DSH 的按钮标签**跟画面不对应**：
     * 点它标签会翻转，但抽屉 tx 恒为 0（一动不动）。
     * 于是"按标签写我们的属性"会把画面**写反** ——
     * 用户点侧边栏的关闭按钮时 DSH 把标签改成「收起」，我们据此把抽屉**打开**，
     * 这就是他报的"关不掉"。
     *
     * 现在状态只有一份：`body[data-dsh-rail-hidden]`，由 requestRail 直接写。
     * 没有第二个写者，也就不会有互相覆盖。
     */

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
         * ★ 纯委托（第五轮定稿）：状态权威只有 DSH 一个。
         *
         * 历次踩的坑都指向同一件事 —— **我们不该自己去改状态**：
         *   · 第一版"先乐观置位 requestRail(false)，再当场找 DSH 按钮点击"：
         *     写 railExplicitShowUntil 把自动同步挡掉 600ms，DSH 随后的展开
         *     反而没人跟进，状态停在收起（真机实测 800ms 后仍是收起 ❌）；
         *   · 现在 requestRail 自己就负责"去点 DSH 的正确按钮"
         *     （见 requestRail 的第四轮说明），所以这里只需要调用它。
         */
        requestRail(false, true);
      }, true);
      document.body.appendChild(btn);
    }

    /*
     * ★★★ 冷启动初始态（本次线上报障的修复点）。
     *
     * 【现象】新装/冷启动后一进应用就是**一个大开着的侧边栏**（只有图标那一列），
     *   主界面被挤到右边，看着像"卡在一个陌生界面"。
     *
     * 【触发条件】仅冷启动（进程首次进入）；之后手动开关都正常。
     *
     * 【根因】CSS 的判据是 `body:not([data-dsh-rail-hidden="1"]) [class*=sidebarCol]{
     *   transform:none }` —— 属性**不存在**就等于"展开"。
     * 而启动时没有任何代码写过这个属性，原来负责写它的
     * `syncDshLabelToRail()` 是这么找按钮的：
     *     findRailToggle(!hiddenNow)   // hiddenNow=false → 找「打开侧边栏」
     * 偏偏「打开侧边栏」按钮被我们自己的 CSS 设成了 `display:none`，
     * querySelector 找不到 → 函数直接返回 false → **属性从来没被写** → 抽屉以展开态启动。
     * （这也是"隐身按钮"这个设计带来的副作用：状态同步依赖一个不可见的按钮。）
     *
     * 【修法】不再依赖找按钮：冷启动时由补丁**直接确定初始态 = 收起**，
     * 这正是本 App 的手机端设计（先看对话，点左上角鲸鱼唤出抽屉）。
     * 用模块级一次性标记，避免每次 apply() 都把用户手动打开的抽屉强行关掉。
     */
    initRailOnce();

  }

  /**
   * 冷启动只做一次：把抽屉的初始态定为**收起**。
   *
   * 详见调用处（patchSidebar 里）的根因说明：
   * 属性"不存在"就等于展开，而启动时没人写过它，于是以展开态启动。
   *
   * 用一次性标记而不是"每次 apply 都写"：
   * apply() 会因为 DOM 变化被反复调用，若每次都写 '1'，
   * 用户手动打开抽屉后会被下一次 apply 又关掉（那是另一种 bug）。
   */
  function initRailOnce() {
    if (W.__dshRailInit) return;
    W.__dshRailInit = true;
    try {
      document.body.setAttribute('data-dsh-rail-hidden', '1');
      railDiag('冷启动初始态 → 收起');
    } catch (e) { }
  }

  /**
   * 把 **DSH 的按钮标签**对齐到我们这份真相（方向是"真相 → DSH"，不是反过来）。
   *
   * 为什么方向不能反：逐帧实测（tools/rail-who-moves.js）证明 DSH 的标签
   * 跟画面不对应 —— 点它的按钮，标签翻转但抽屉 tx 恒为 0。
   * 若按标签写我们的属性，就会把画面写反（"关不掉"就是这么来的）。
   *
   * 只在标签确实与目标不符时才点，避免把 DSH 的状态翻反。
   */
  function syncDshLabelToRail() {
    try {
      var hiddenNow = document.body.getAttribute('data-dsh-rail-hidden') === '1';
      // 想要"收起" → DSH 的按钮应显示「收起侧边栏」；反之显示「打开侧边栏」
      var mismatched = findRailToggle(!hiddenNow);
      if (mismatched) {
        var prev = railDelegating;
        railDelegating = true;
        try { mismatched.btn.click(); } finally { railDelegating = prev; }
        return true;
      }
    } catch (e) { }
    return false;
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
  /**
   * 「用户刚刚明确要求展开抽屉」的窗口。
   *
   * 与 {@link railLatchUntil} 的区别（这个区分是本 bug 的关键）：
   *   · railLatchUntil —— 防止**自动同步**被收起动画的中间态骗到（防"抽搐"）；
   *   · railExplicitShowUntil —— 用户明确点了展开时，让自动同步**先别管**。
   * 早先把两者混为一个，结果防抽搐的那道也把用户的操作挡住了，
   * 表现就是"收起后马上再点开 → 又被压回收起"（用户报的"回弹"）。
   */
  var railExplicitShowUntil = 0;

  /**
   * 诊断记录（`window.__dshRailDiag`）。
   *
   * 用户报："打开历史对话侧边栏，点右侧空白处想回对话，侧边栏莫名其妙又弹出来。"
   *
   * <h3>排查过程（三轮，前两轮都改错了地方，留个记录）</h3>
   * <ol>
   *   <li><b>第一轮</b>：判据是"祖先链里有 centerCol 才算点对话区"。
   *       真机发现抽屉右侧常盖着**不属于 centerCol 的浮层**（提示卡），
   *       于是判据为假 → 什么都不做 → 抽屉不收。已改成按"侧边栏右边缘之右"判定。</li>
   *   <li><b>第二轮</b>：监听器只挂在 `document` 捕获阶段。
   *       真机探针（tools/rail-diagnose.js）显示那个位置的元素是
   *       **卡片里的按钮**，按钮自己 `stopPropagation` → 事件到不了 document
   *       → 我们收不到。已加挂 `window` 捕获阶段（最先执行）。</li>
   *   <li><b>第三轮</b>：之前为了"不依赖猜对原因"加过一个**500ms 强制保持收起**
   *       的观察器，结果它把**用户自己再点开**也按回去了 —— 用户看到的"回弹"
   *       其实是它造成的。已整个删除。</li>
   * </ol>
   * 现在这里只剩诊断记录，不再有会误伤用户操作的兜底。
   */
  W.__dshRailDiag = [];
  function railDiag(what) {
    try {
      /*
       * 记录调用栈（前 4 帧）。
       * 为什么要带栈：曾经看到一条"多余的收起"却查不出是谁发起的 ——
       * 光有时间戳和动作名，只能靠猜；带上栈就是可证的事实。
       */
      var st = '';
      try {
        var e = new Error();
        if (e.stack) {
          st = e.stack.split('\n').slice(2, 6)
                .map(function (s) { return s.trim().replace(/^at\s+/, '').slice(0, 60); })
                .join(' ← ');
        }
      } catch (e2) { }
      W.__dshRailDiag.push(Date.now() + ' ' + what + (st ? '  @' + st : ''));
      if (W.__dshRailDiag.length > 40) W.__dshRailDiag.shift();
    } catch (e) { }
  }

  /**
   * 「我们正在派发自己合成的点击」标志 —— **只在 `b.click()` 那一瞬为真**。
   *
   * ⚠️ 声明位置必须在这一层（IIFE 体内，与 requestRail / requestRailInner 同级）。
   * 踩过的坑：我把它挪"出去"时挪错了地方，落进了 patchWindowTapToCloseRail 内部的
   * 匿名函数里（depth=2），而使用它的 requestRailInner 在 depth=1 ——
   * 于是赋值时抛 `railDelegating is not defined`，被 catch 吞掉，
   * 表现就是"点了鲸鱼抽屉滑出来但内容还是窄轨道"，且**日志里看不出是异常**。
   * （那次之所以难查：异常信息当时也没打进诊断。）
   *
   * 它拦住的是"我们自己合成的那一击再次进入 click 监听器"这个环
   * （requestRailInner → 点按钮 → HTMLDocument 监听器 → requestRailInner）。
   * 用同步标志而不是时间窗口：合成点击同步派发，置位→click→复位 就够；
   * 真实用户事件都在之后的事件循环里，那时标志早已复位，不会误伤。
   */
  var railDelegating = false;

  /**
   * 请求抽屉的开/合状态。
   *
   * <h3>★ 第四轮（这一版）：不再自己改属性，改成"点 DSH 的按钮"</h3>
   *
   * 前三轮都在改 `data-dsh-rail-hidden` 这个**我们自己的**属性，然后指望
   * DSH 跟着它走。真机时间线（tools/rail-timeline.js）证明这条路走不通：
   *
   * <pre>
   * 0ms    起点 class = pI_x6G_sidebarCol
   * 701ms  已置为收起（我们改属性）  class 仍然 = pI_x6G_sidebarCol
   * 709ms  点了 DSH 的「打开侧边栏」
   * 912ms  body.data-dsh-rail-hidden = null   ← 只有 DSH 的按钮会动状态
   * 2060ms class 全程没有变过
   * </pre>
   *
   * 也就是说：**DSH 的状态权威是它自己那个切换按钮，不是我们的属性**。
   * 我们单方面改属性 → DSH 内部仍以为抽屉是开的 →
   * 用户再点开时，DSH 把它当成"关闭"，于是抽屉纹丝不动或又被缩回去
   * （这就是用户反复报的"回弹"）。
   *
   * 现在改成**委托**：请求什么状态，就去点 DSH 对应的那个按钮，
   * 让它自己把状态和动画走完；我们的属性只由下面那个 observer 跟随它。
   *
   * @param hidden true=收起, false=展开
   * @param fromUser 是否用户直接发起（用户发起时优先，不被自动同步压回）
   */
  function requestRail(hidden, fromUser) {
    /*
     * 第七轮起**不再需要任何闩锁**：状态只有 DSH 一个来源，
     * 我们不再"压住"任何东西，也就没有需要防的中间态误判。
     * （railLatchUntil / railExplicitShowUntil 两个变量保留仅为兼容旧引用，
     *   代码里已不参与任何判断。）
     */

    /*
     * ★ 重入闸门（第六轮，最后一块拼图）。
     *
     * 下面会去 `b.click()` 点 DSH 的按钮 —— 而那一击**同样会经过我们自己的
     * click 监听器**（真机栈里看得很清楚：
     *   requestRail ← HTMLDocument.<anonymous> ← requestRail ← railPointerHandler）。
     * 于是"我们收一次 → 触发 DSH 切换 → 又被我们当成用户点击再收一次"，
     * 一个切换动作被做了两遍，抽屉就是收了又开／开了又收 —— 用户看到的"回弹"。
     *
     * ⚠️ 这里**不能**用一个"执行期间为真"的开关：我们的合成点击是**同步**派发的，
     * 而真实用户点击可能恰好排在同一段脚本里 —— 那样会把用户的点击也一起挡掉
     * （实测：用户点空白完全没反应，因为连"收起"都被自己的闸门吃了）。
     * 正确做法是**只在合成点击那一瞬间**放行/拦截，用时间戳标记，
     * 监听器看到"刚刚是我们自己合成的那一击"就跳过。
     */
    return requestRailInner(hidden, fromUser);
  }

  /**
   * 找 DSH 自己的抽屉切换按钮。
   *
   * <h3>为什么要单独抽出来、还要多路兜底</h3>
   * 0.3.9 里 `requestRail` 找不到按钮就"静默不做事"，结果用户装了之后
   * **侧边栏完全打不开** —— 一个查不到元素就让核心功能全废，不可接受。
   * 所以这里：三种选择器依次试，并返回"找到了哪个"供诊断。
   *
   * @param hidden true 表示想要"收起"，对应按钮标签是「收起侧边栏」
   */
  function findRailToggle(hidden) {
    var want = hidden ? '收起侧边栏' : '打开侧边栏';
    var sels = [
      '[class*="sidebarCol"] button[aria-label*="' + want + '"]',
      '[class*="logoRow"] button[aria-label*="侧边栏"]',
      'button[aria-label*="' + want + '"]'
    ];
    for (var i = 0; i < sels.length; i++) {
      try {
        var el = document.querySelector(sels[i]);
        if (el) return { btn: el, via: sels[i] };
      } catch (e) { }
    }
    /*
     * 最后兜底：任意一个"侧边栏"切换按钮，**按它的标签判断语义**。
     * 只有当它的语义与目标一致时才用（否则会把状态点反）。
     */
    try {
      var all = document.querySelectorAll('button[aria-label*="侧边栏"]');
      for (var j = 0; j < all.length; j++) {
        var lbl = all[j].getAttribute('aria-label') || '';
        if ((hidden && lbl.indexOf('收起') >= 0) || (!hidden && lbl.indexOf('打开') >= 0)) {
          return { btn: all[j], via: 'any+label(' + lbl + ')' };
        }
      }
    } catch (e2) { }
    return null;
  }

  function requestRailInner(hidden, fromUser) {
    /*
     * ★★★ 第八轮定稿（真机逐帧实测之后）：**真相就是我们这个属性**。
     *
     * 第七轮我按"只留 DSH 一套状态"改成委托点击 + 按按钮标签镜像，
     * 结果用户报"抽屉连开都开不了 / 开了关不掉"。用 tools/rail-who-moves.js
     * 逐帧量出了铁证：
     *
     *   我们设 attr=1          → tx = -280   抽屉真的收起 ✅
     *   我们清掉 attr          → tx = 0      抽屉真的展开 ✅
     *   点 DSH 自己的切换按钮  → tx 恒为 0   抽屉**一动不动**，只有按钮标签翻转 ❌
     *
     * 也就是说：**抽屉的位移已经完全由我们的 CSS（认 body[data-dsh-rail-hidden]）
     * 控制，DSH 的开合状态不再影响它**。所以第七轮那个"按按钮标签镜像"的前提是错的
     * —— 按钮标签跟画面根本不对应，照着它写属性必然把画面写反。
     *
     * 现在只有一份真相：`body[data-dsh-rail-hidden]`。
     *   · requestRail 直接写它（同步、立刻生效、可被工具单独验证）；
     *   · 写完**顺带**把 DSH 的按钮点一下，让它的标签跟上 ——
     *     这一步是"顺手对齐"，失败了也不影响画面（画面只认属性）。
     *     只有"标签与目标不一致"时才点，否则会把 DSH 状态翻反。
     */
    var want = hidden ? '1' : '0';
    var cur = document.body.getAttribute('data-dsh-rail-hidden');
    if (cur === want) {
      railDiag('requestRail(' + (hidden ? 'hide' : 'show') + ') → 已是目标状态，无动作');
      return true;
    }

    /* ① 唯一真相：直接写。这是**画面实际认的**那个开关 */
    if (hidden) document.body.setAttribute('data-dsh-rail-hidden', '1');
    else document.body.removeAttribute('data-dsh-rail-hidden');
    railDiag('requestRail(' + (hidden ? 'hide' : 'show') + ') → 已写属性（画面认这个）');

    /*
     * ★★★ ② 让 **DSH 也走到同一状态** —— 这一步不是"顺手对齐"，是**必须的**。
     *
     * 【本次线上报障的根因】用户点浮动鲸鱼，抽屉滑出来了，但**里面只有一列图标**
     * （会话列表没有，`treeitem` 数 = 0），看起来就是"大抽屉/空抽屉"。
     *
     * 原因：我们把抽屉的**位移**交给自己的 CSS（认 body[data-dsh-rail-hidden]），
     * 但**内容由 DSH 渲染** —— 它靠内部类名 `hHd-Xa_collapsed` 决定渲染"窄轨道"还是"完整抽屉"。
     * 只写我们的属性只会滑动，DSH 仍带 `collapsed`，于是渲染窄轨道。
     *
     * 逐帧实测（tools/rail-collapsed-probe.js）证据：
     *   起点(收起)   collapsed类=root collapsed quietBars   attr=1  会话项=0
     *   点DSH按钮+200ms  collapsed类=root quietBars          attr=   会话项=6  ← 内容出来了
     *
     * 所以必须点它自己的切换按钮，把 `collapsed` 摘掉/加回。
     *
     * ⚠️ 上一轮我删掉过这一步，因为"每次都把状态翻反"。真正的原因是：
     *   · 我用 `findRailToggle(!hidden)` 找按钮，语义正好相反；
     *   · 而且按钮标签与画面不同步，按标签判方向必然出错。
     * 现在改成**按当前类名判方向**：只有"DSH 的状态与目标不符"时才点，
     * 且找按钮时**不看标签**（那玩意不可靠），取 DOM 里那颗切换按钮即可。
     *
     * 用 railDelegating 包住这一击：它会再次进入我们自己的 click 监听器，
     * 不拦住就会翻两次（历史踩过）。点击是同步的，标志在事件循环内复位，
     * 真实用户事件不会被误伤。
     */
    try {
      /*
       * ⚠️ 选择器**必须限定在侧边栏内**（`[class*="sidebarCol"] ` 前缀）。
       *
       * 踩过的坑：原来写的是 `document.querySelector('[class*="hHd-Xa_root"]')`，
       * 而**我们注入的浮动鲸鱼按钮内部也带这个类**（复用了 DSH 侧边栏的图标 HTML）。
       * 冷启动时侧边栏还没渲染，于是选到了浮按钮里那个 —— 它**不含 collapsed**，
       * 于是读到 dshCollapsed=false → 判定"无需翻转" → **DSH 永远停在收起**，
       * 抽屉滑出来了但内容只有一列图标（用户看到的"大抽屉/空抽屉"）。
       *
       * 这个 bug 之所以难查：读错了元素却**不报错**，只是静默不动作。
       * 所以下面每一步都留痕，catch 里也留痕（不再吞异常）。
       */
      var rootEl = document.querySelector('[class*="sidebarCol"] [class*="hHd-Xa_root"]');
      var dshCollapsed = !!(rootEl && rootEl.className.indexOf('collapsed') >= 0);
      /* 我们要收起 → DSH 应当处于 collapsed；要展开 → 不应处于 collapsed */
      var needFlip = (hidden !== dshCollapsed);
      railDiag('  ↳ DSH同步检查: rootEl=' + (rootEl ? 'ok' : 'null')
               + ' dshCollapsed=' + dshCollapsed + ' 目标hidden=' + hidden
               + ' needFlip=' + needFlip);
      if (needFlip) {
        var tog = (rootEl && rootEl.querySelector('button[aria-label*="侧边栏"]'))
               || document.querySelector('[class*="sidebarCol"] button[aria-label*="侧边栏"]')
               || document.querySelector('button[aria-label*="侧边栏"]');
        railDiag('  ↳ 找到切换按钮: ' + (tog ? ((tog.getAttribute('aria-label') || tog.tagName)) : 'null'));
        if (tog) {
          var prevD = railDelegating;
          railDelegating = true;
          try { tog.click(); } finally { railDelegating = prevD; }
          railDiag('  ↳ 已同步 DSH 内容状态（' + (hidden ? '收起' : '展开') + '）');
        } else {
          railDiag('  ↳ 找不到 DSH 切换按钮，内容状态未同步');
        }
      } else {
        railDiag('  ↳ DSH 内容状态已一致，无需翻转');
      }
    } catch (e) {
      railDiag('  ↳ DSH同步异常: ' + (e && e.message ? e.message : e));
    }
    return true;
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
      // 我们自己派发的那一击（requestRail 去点 DSH 按钮）—— 不算用户操作
      if (railDelegating) return;
      var t = ev.target;
      if (!t || !t.closest) return;
      /*
       * 我们自己正在编排抽屉（比如从插件页返回：要先切走再切回来），
       * 中间那几次会话切换**不能让抽屉自己收起** —— 否则用户看到的就是
       * "抽屉先回去、又弹回来、再跟对话一起收"，很乱。见 __dshHandleBack。
       */
      if (W.__dshHoldDrawer) return;

      /*
       * 说明：侧边栏切换按钮（打开/收起侧边栏）的接管**不在这里** ——
       * 因为 railTapHandler 里那条"抽屉里的点击直接 return"更早执行，
       * 写在这里永远不会被调用（踩过）。它现在位于 railTapHandler 内部、
       * 在那条 return **之前**，见那里的注释。
       */

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

  /**
   * 抽屉打开时，点右边那块"对话区"也要能收起来 —— 用户要的是"点回去就回原会话"。
   *
   * <h3>为什么需要它</h3>
   * patchDrawerAutoClose 只认**抽屉内部**的点击（选会话/新建会话）。用户的手势是
   * "划出会话列表看一眼，然后直接点右边那块对话区想回去"，而那块区域点下去
   * 什么都不会发生，看起来就是"点了没反应、退不回原来的会话"。
   * 用户原话："调出会话列表后点击右侧会话区域无法回退至原会话"。
   *
   * <h3>为什么只认 centerCol</h3>
   * `[class*="centerCol"]` 是中间栏（对话正文），它是"右边那块"最稳的锚点：
   *   · 抽屉在 sidebarCol 里，点它不会命中 centerCol；
   *   · 鲸鱼按钮、右侧栏都在 centerCol 之外，所以不会被误伤；
   *   · 平板上 centerCol 常驻可见（没有抽屉这回事），所以本函数**只在窄屏 + 抽屉打开时**生效。
   *
   * <h3>⚠️ "抽屉开着"的判据（这里栽过一次，别再写反）</h3>
   * `body[data-dsh-rail-hidden]` = **抽屉已经收起**（名字就是"轨道被藏起来"）。
   * 第一版把它当成"抽屉开着"，于是 `if (!hasAttribute(...)) return;` 在正常情况下
   * 直接返回 —— 真机 CDP 实测点下去毫无反应，而代码看起来完全"合理"。
   *
   * <h3>为什么不能只看宽度</h3>
   * 真机实测（360dp 宽、抽屉 280dp）：抽屉**收起后 `sidebarCol` 的宽度仍然是 280** ——
   * 它是靠 `translateX(-280)` 移出屏幕的（点开后 `getBoundingClientRect().x` 从
   * -280 变 0）。所以"宽度 ≥ 200 就算开着"会把收起态误判成打开，
   * 于是点侧边栏反而把抽屉**拉开** —— 比不修还糟。
   * 现在改成：① 用户动作后的闩锁期用我们自己的状态；② 闩锁到期后用
   * `elementFromPoint` 直接看"抽屉原来占的那块位置"露出来的是谁 —— 这是几何事实，
   * 不依赖任何类名或宽度假设。
   *
   * <h3>为什么不用遮罩</h3>
   * 加一层半透明遮罩 + 点击关闭是最常见的写法，但这里行不通：抽屉下的内容
   * 仍然要能被 DSH 自己正常收到事件（比如点会话行切换），遮罩会把它们吃掉。
   * 事件委托不改 DOM 结构，风险最小 —— 宁可少改，也不要加不可靠的判断逻辑。
   */
  function drawerOpen() {
    var side = document.querySelector('[class*="sidebarCol"]');
    if (!side) return false;
    /*
     * ★ 唯一真相：这个属性。
     *
     * 逐帧实测（tools/rail-who-moves.js）确认：
     *   设 attr=1 → tx=-280（真收起）；清掉 → tx=0（真展开）；
     *   而点 DSH 自己的按钮 20 次，tx 恒为 0 —— 它的状态**不驱动画面**。
     * 所以画面状态只能由这个属性表达，别再去读 DSH 的按钮标签。
     */
    return !document.body.hasAttribute('data-dsh-rail-hidden');
  }

  function patchWindowTapToCloseRail() {
    if (W.__dshTapCloseRail) return;
    W.__dshTapCloseRail = true;

    /*
     * ★ 点击取证日志（`window.__dshClickLog`）。
     *
     * 用户反馈"点侧边栏的关闭按钮关不掉"。我无法看到他的屏幕，
     * 而他点的到底是哪个元素（DSH 的切换按钮？注入的鲸鱼按钮？某个插件按钮？）
     * 直接决定该改哪里。之前只能靠猜，代价是改了五轮都没中。
     *
     * 所以这里把所有点击记下来（最多 30 条）：点到了谁、坐标、当时抽屉是开是关、
     * 以及那一击有没有被当作"收抽屉"处理。出问题时报一条命令就能看现场：
     *   node tools/cdp.mjs --file tools/rail-clicklog.js
     */
    if (!W.__dshClickLog) {
      W.__dshClickLog = [];
      W.addEventListener('click', function (ev) {
        try {
          var t = ev.target;
          var el = t && t.closest ? (t.closest('button,[role="button"],a') || t) : t;
          var lbl = el && el.getAttribute ? (el.getAttribute('aria-label') || '') : '';
          W.__dshClickLog.push({
            t: Date.now() % 1000000,
            tag: el ? el.tagName : '',
            id: el ? (el.id || '') : '',
            cls: el ? (el.className || '').toString().slice(0, 34) : '',
            aria: lbl.slice(0, 24),
            x: Math.round(ev.clientX), y: Math.round(ev.clientY),
            inSide: !!(t && t.closest && t.closest('[class*="sidebarCol"]')),
            railHidden: document.body.getAttribute('data-dsh-rail-hidden') === '1',
          });
          if (W.__dshClickLog.length > 30) W.__dshClickLog.shift();
        } catch (e) { }
      }, true);
    }
    /**
     * 「刚刚点过抽屉切换按钮」的冷却时间。
     *
     * 起因（真机时间线实测）：我们收起抽屉是**直接改 body 属性**，
     * 而 DSH 的按钮状态是它自己那边维护的 —— 两者的内部状态**不会自动对齐**。
     * 于是"收起后马上再点开"时，DSH 仍以为抽屉是开的，
     * 那一下点击被它当成"关闭"处理，用户看到的就是"点了不弹出来 / 回弹"。
     *
     * 这个冷却只做一件事：**极短时间内不重复转发**同一次连点，
     * 避免和 DSH 正在跑的过渡打架。真正的状态权威仍然交给 DSH 自己。
     */
    var railToggleCooldownUntil = 0;

    /**
     * 最近一次"收起抽屉"动作的时间戳 —— 用来给 click 与 pointerup 去重。
     *
     * 同一次触摸会先来 pointerup、再来 click（或反之，取决于浏览器是否把 click 抑制掉），
     * 两个监听器都会尝试收一次；不去重就会收两次，
     * 加上"收起"会委托点击 DSH 的按钮（这是个**切换**），第二次就把抽屉又打开了
     * —— 现象正是用户反复报的"回弹"。
     */
    var railLastTapAt = 0;

  /**
   * 「我们正在派发自己合成的点击」标志 —— **只在 `b.click()` 那一瞬为真**。
   *
   * <h3>为什么必须是同步的一瞬，而不是一段时间窗口</h3>
   * requestRail 会去点 DSH 的按钮，而那一击**会再次进入我们自己的 click 监听器**
   * （带调用栈的 __dshRailDiag 抓到的环：
   *   requestRailInner → 点按钮 → HTMLDocument 监听器 → requestRailInner → …）。
   * 不拦住就会把一次切换做成两次，用户感受就是**点按钮关不掉抽屉**。
   *
   * 但拦的窗口**不能是一段时间**（时间戳版本踩过：紧随其后的真实 pointerup
   * 落进 60ms 窗口被忽略，点空白完全没反应）。我们的合成点击是**同步派发**的，
   * 所以只要在 `click()` 前后置位/清位即可 —— 真实事件都在之后的事件循环里，
   * 那时标志早已复位，绝不会被误伤。
   */

  /**
   * 「这个点击事件已经处理过」—— 用**事件对象引用**去重。
   *
   * 见 railTapHandler 的说明：handler 同时挂在 window 与 document 捕获阶段，
   * 一次点击会进来两次，两次判定基准不同会互相抵消。
   *
   * 为什么不用 ev.timeStamp：合成点击会复用同一个时间戳，
   * 导致后续点击被误判成"已处理"而完全失效（实测踩到）。
   * 同一次派发传给两个监听器的是同一个事件对象 —— 引用比对才可靠。
   */
  var railHandledEv = null;
    /*
     * ★ 为什么在 window 和 document 上**都挂**（且都用捕获阶段）：
     *
     * window 的捕获阶段是整条传播链的**最外层、最先执行**的位置。
     * 真机实测（tools/rail-diagnose.js 的探针）：抽屉右侧那块经常盖着
     * 卡片里的按钮（例如提示卡上的主按钮），按钮自己的处理里调了 stopPropagation ——
     * 于是事件**冒泡不到 document**，我们挂在 document 上的监听器收不到，
     * 表现就是"点空白回不去 / 点了没反应"。
     * 挂到 window 捕获阶段就不受它影响（我们最先跑）。
     *
     * 同一次点击会被这两个监听器各调用一次，但 handler 是幂等的：
     * 第一次就已把抽屉收起并 stopImmediatePropagation，
     * 第二次进来时 drawerOpen() 已经为假，直接返回。
     */
    function railTapHandler(ev) {
      // 我们自己派发的那一击（requestRail 去点 DSH 按钮产生的）—— 不能再当用户点击处理
      if (railDelegating) return;
      /*
       * ★★★ 去重：同一次派发只处理一次。
       *
       * 这是"关不掉"的**真正根因之一**（找了很久）：
       * 这个 handler 同时挂在 `window` 和 `document` 的捕获阶段，
       * 于是**一次点击会进来两次**。两次都会重新算 `wantHide = drawerOpen()` ——
       * 第一次算"收起"并执行，第二次基于**变化后**的状态算成"展开"，正好抵消。
       *
       * ⚠️ 不能用 `ev.timeStamp` 当指纹：实测合成点击（el.click()）会**复用同一个
       * 时间戳**，于是第 2 次起的点击全被误判成"已处理"，处理器再也不跑
       * （现象：点一次有效，之后完全没反应，railDiag 也不再增长）。
       * 改用**引用比对**：同一次派发传给两个监听器的是**同一个事件对象**，
       * 引用相同即同一次；不同点击的事件对象必然不同。
       */
      if (ev === railHandledEv) return;
      railHandledEv = ev;
      // 编排抽屉期间（插件页返回那条路）不许收 —— 否则一开一合太乱
      if (W.__dshHoldDrawer) return;
      if (window.innerWidth >= NARROW_PX) return;   // 平板没有抽屉
      var t = ev.target;
      if (!t || !t.closest) return;

      /*
       * ★★★ 第一优先：侧边栏切换按钮的接管。
       *
       * 这里**必须在下面那条 `if (!drawerOpen()) return;` 之前** ——
       * 那条守卫是给"点空白收起"用的（"抽屉本来就是收起的，没什么可收"），
       * 但它会把**抽屉收起时点切换按钮**也一并挡掉，于是永远打不开。
       * 实测（tools/rail-guard-trace.js）逐条复刻守卫链才定位到：
       *   drawerOpen=False → 直接 return，接管代码根本没机会执行。
       * 这个顺序错误我先后踩了两次（另一次是把它写在 patchDrawerAutoClose 里），
       * 所以现在把它放在**所有守卫之后的第一件事**。
       *
       * 为什么必须接管：取证日志（window.__dshClickLog）显示用户点的"关闭按钮"是
       *   BUTTON.hHd-Xa_iconButton.hHd-Xa_toggle
       * 它被我们自己的 CSS 弄成 [0,0,0,0]（收起时）或 28x28（展开时），
       * 而且 **DSH 的状态不驱动画面**（逐帧实测点它 20 次 tx 都是 0）——
       * 点它什么都不会发生，这就是"打不开 / 关不掉"。
       */
      var railToggle = t.closest('[class*="sidebarCol"] button[aria-label*="侧边栏"]');
      if (railToggle) {
        var rl = railToggle.getAttribute('aria-label') || '';
        /*
         * 开关语义：**当前开着 → 这一击应当收起**，即 wantHide = drawerOpen()。
         * ⚠️ 这里我写错过一次（写成 !drawerOpen()），现象是"点关闭反而想展开"，
         * 抽屉纹丝不动。布尔值写反靠读代码很容易漏，所以保留打出方向的诊断。
         */
        var wantHide = drawerOpen();
        railDiag('侧边栏切换按钮被点: aria=' + rl + ' → ' + (wantHide ? '收起' : '展开'));
        requestRail(wantHide, true);
        return;
      }

      // 以下是"点空白收起"的路径：抽屉本来就是收起的话没什么可收
      if (!drawerOpen()) return;

      /*
       * 说明：侧边栏切换按钮的接管已经**提到所有守卫之前**（见本函数上方），
       * 那里是有注释说明的正式位置。这里保持"其余抽屉内点击交给
       * patchDrawerAutoClose 判"的原逻辑即可。
       */

      // 抽屉里的其它点击交给 patchDrawerAutoClose 判（它有"点会话行才收"的细分规则）
      if (t.closest('[class*="sidebarCol"]')) return;
      // 兜底：从抽屉里长按/拖出去的浮层也不该被动到
      if (t.closest('[role="dialog"]')) return;

      /*
       * ★ 判据是"**点在侧边栏右侧那块可见区域**"，不是"祖先链里有 centerCol"。
       *
       * 这一条是真实触摸测出来的（改之前用假事件测，一直测不出来）：
       * 屏幕上经常盖着**不属于 centerCol 的浮层** —— 例如底部那张提示卡
       * （`uV2eYG_card`，祖先链是 root/row/card，压根没有 centerCol）。
       * 用户在它上面点一下，旧的判据判定"这不是对话区"，于是**什么都不做**，
       * 抽屉就一直开着 —— 用户看到的就是"点空白处回不去／像又弹回来了"。
       *
       * 新判据只要求两件事，都不依赖 DOM 结构：
       *   · 抽屉开着（上面已判）；
       *   · 点落在侧边栏**右边缘之右**、且在当前视口内。
       * 结构怎么嵌套、上面盖着什么，都不影响 —— 这正是我们要的"点对话区就收"。
       */
      var side = document.querySelector('[class*="sidebarCol"]');
      if (!side) return;
      var sr = side.getBoundingClientRect();
      /*
       * 用"坐标"判定，但**两个层次都试**：
       *
       *   ① 真实触摸：ev.clientX/Y 是准的 → 直接按"在侧边栏右边缘之右"判；
       *   ② 合成事件/工具注入：有些途径（例如 CDP 的 Input.dispatchTouchEvent
       *      之外的手工构造）会给出 0,0 坐标，此时**退回 DOM 判据**
       *      （祖先链里有 centerCol 就算点在对话区）。
       *
       * 为什么要留 ②：真机排查时我一度用 0,0 的合成点击做验证，
       * 结果因为坐标是 0 而被坐标判据排除，误判成"监听器没挂上"，
       * 白查了一轮。两个层次都判之后，任何注入方式都能得到一致结果。
       */
      var byCoord = (ev.clientX > sr.right + 2)
                 && ev.clientY >= 0 && ev.clientY <= window.innerHeight
                 && ev.clientX >= 0 && ev.clientX <= window.innerWidth;
      var byDom = false;
      if (!byCoord && ev.clientX === 0 && ev.clientY === 0) {
        byDom = !!(t.closest('[class*="centerCol"]'));
      }
      if (!byCoord && !byDom) return;      // 落在抽屉里（或边界上）→ 不碰

      /*
       * 【为什么必须 stopPropagation —— 用户报的"侧边栏莫名弹回来"】
       *
       * 这段监听器挂在**捕获阶段**（第三个参数 true），比 DSH 自己的 click 处理**先**跑。
       * 第一版只调了 requestRail(true) 就放事件继续往下传，于是 DSH 的处理器随后跑，
       * 它看到"抽屉此刻是收起的"就把这次点击当成"切换抽屉" → **又把它打开**。
       * 用户看到的现象就是：点右侧空白处想回对话，结果侧边栏弹出来。
       *
       * 修法：这次点击的语义已经被我们消费掉（"点空白处 = 收抽屉"），
       * 就别再让它进入 DSH 的处理链。stopImmediatePropagation 连同一元素上
       * 其它监听器一起挡住。
       *
       * 注意**不用 preventDefault** —— 收抽屉不该改变 DSH 对这次点击的默认行为
       * （输入框失焦、取消文本选择这些），阻止它反而会出现"点了没反应"。
       */
      /*
       * 收起抽屉 —— 交给 requestRail，它现在会去点 DSH 自己的按钮，
       * 让"状态权威"只有 DSH 一个（见 requestRail 的第四轮说明）。
       * 这里**不要**再自己改属性或再点一次按钮，否则会点两次把状态翻过去。
       */
      if (Date.now() - railLastTapAt < 450) {
        // 同一次触摸的 pointerup 已经收过了 —— 再来一次会把抽屉又切换回来
        ev.stopImmediatePropagation();
        ev.stopPropagation();
        return;
      }
      railLastTapAt = Date.now();
      requestRail(true, true);
      ev.stopImmediatePropagation();
      ev.stopPropagation();
    }

    /*
     * ★ pointerup 兜底：真机实测发现，抽屉右侧那块如果落在一个**按钮**上
     * （探针里是卡片内的 `BUTTON.uV2eYG_primary`），那次触摸**根本不产生 click** ——
     * 按钮的处理把 click 抑制掉了。于是挂在 click 上的监听器（无论冒泡还是捕获）
     * 都收不到，用户感受就是"点了没反应"。
     *
     * pointerup 不会被这样抑制，用它兜底最稳。
     *
     * ⚠️ 但 pointerup 与 click 是**同一次触摸的两个事件**，必须防止同一次被处理两遍：
     * 真机诊断（带调用栈的 __dshRailDiag）抓到过这样的序列 ——
     * click 先把抽屉收起，紧接着同一次触摸的 pointerup 又来一次，
     * 于是"收起→又收起"或"展开→又被收起"，看上去就是回弹。
     * 下面用 railLastTapAt 做去重：一次触摸只认第一个到达的事件。
     */
    function railPointerHandler(ev) {
      if (railDelegating) return;                     // 我们自己派发的那一击
      if (W.__dshHoldDrawer) return;
      if (window.innerWidth >= NARROW_PX) return;
      if (Date.now() - railLastTapAt < 450) return;   // 同一次触摸的另一个事件，忽略
      if (!drawerOpen()) return;
      var t2 = ev.target;
      if (t2 && t2.closest && t2.closest('[class*="sidebarCol"]')) return;
      if (t2 && t2.closest && t2.closest('[role="dialog"]')) return;
      var s2 = document.querySelector('[class*="sidebarCol"]');
      if (!s2) return;
      if (ev.clientX <= s2.getBoundingClientRect().right + 2) return;
      if (ev.clientY < 0 || ev.clientY > window.innerHeight) return;
      railLastTapAt = Date.now();
      requestRail(true, true);
    }

    // window 捕获（最先执行，扛得住内层 stopPropagation）+ document 捕获（兜底）
    W.addEventListener('click', railTapHandler, true);
    document.addEventListener('click', railTapHandler, true);
    W.addEventListener('pointerup', railPointerHandler, true);
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
    // 退格键删附件（附件卡片在编辑器外面，DSH 自己不认退格）
    patchComposerBackspace();
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
    // 点右边那块会话区域 → 收起抽屉回原会话（用户反馈的第一个问题）
    patchWindowTapToCloseRail();
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

  /*
   * ⚠️ documentElement 可能不存在 —— 页面加载失败时 Chromium 会显示内置错误页
   * （chrome-error://chromewebdata/），在那个文档上 documentElement 是 null，
   * 直接 observe 会抛 "parameter 1 is not of type 'Node'"（用户日志里刷了好几行）。
   *
   * ⚠️ 这里**只包住 observer**，不能用 `if (!…) return;` 提前退出：
   * 那样会把整个 IIFE 剩下的部分一起跳过 —— 包括下面注册的前端自检
   * （bootWatchdog → pluginBootFailed / pageReady，App 的自救和更新日志都靠它）、
   * 给「手机文件」插件用的插入函数，以及最后那次 apply()。
   * 一旦哪种情况下 documentElement 真的还没就绪，代价会是"补丁整个没生效"，
   * 而不是"少一个 observer"。
   */
  if (document.documentElement) {
    new MutationObserver(scheduleApply).observe(document.documentElement, {
      childList: true, subtree: true, attributes: true,
      attributeFilter: ['class', 'aria-selected'],
    });
  }
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
   * ⚠️⚠️ 判据只能看 **DSH 自己的启动卡**，绝不能扫整页正文（0.2.8.4 修的事故）：
   * 启动失败的文案（"web boot: 1 entry did not activate / dsh-pet: import failed"）
   * 一旦被用户复制进对话、或被 agent 复述，它就**永久留在了会话正文里**。
   * 以前的写法是 `document.body.innerText` 整页正则 —— 于是每次重启都命中这段
   * 历史消息，App 把好端端的界面判成"插件卡死"：自动停用全部第三方插件 → 重启
   * → 还是命中 → 插件被一个个摘光，界面反而再也进不去（用户反馈的
   * "安全模式又出问题了""插件直接没了，但文件还在"就是这一条）。
   *
   * 启动卡是 `<div data-dsh-boot>`（见 DSH 前端 `_boot_*` 的 boot card），
   * 只有它的正文里出现失败文案才是真的界面起不来。
   *
   * 四条判据（都要求"主界面确实没渲染出来"，正常启动一条都不会触发）：
   *   ① 启动卡自己报了失败 → 立刻上报（连原文一起给，App 从里面抠插件名）；
   *   ② 没有启动卡、正文里却有失败文案 → 兜底上报
   *      （兜的是 DSH 以后改掉 data-dsh-boot 这个属性名的情况）；
   *   ③ 没有启动卡、主界面也一直是空的（25 秒）→ 脚本压根没跑起来
   *      （老 WebView 语法解析失败就是这种）；
   *   ④ 启动卡在、但 60 秒还没往下走 → 卡在加载/等一个不存在的服务。
   */
  (function bootWatchdog() {
    if (W.__dshBootReported) return;
    var t0 = Date.now();
    var FAIL = /Failed to load plugins|did not activate|waiting for service/i;

    function bootCard() {
      try { return document.querySelector('[data-dsh-boot]'); } catch (e) { return null; }
    }
    function textOf(el) {
      try { return (el && el.innerText) || ''; } catch (e) { return ''; }
    }
    /** 主界面正文 = #root 的文本**减去启动卡那一份**（启动卡在 #root 里面）。 */
    function appText() {
      var t = textOf(document.getElementById('root') || document.body);
      var ct = textOf(bootCard());
      if (ct) t = t.split(ct).join(' ');
      return t;
    }
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
      /*
       * 先确认"这确实是 DSH 的页面"：DSH 的 index.html 一定有 <div id="root">。
       * 容器没起来 / 地址连不上时，WebView 显示的是一张 **Chromium 错误页**
       * （chrome-error://chromewebdata/，没有 #root）—— 那种页面上判"界面没渲染出来"
       * 会把责任推给插件，进而把用户的第三方插件全停掉，而真正的原因只是容器在重启。
       * 这种页面交给 App 的心跳去重载（见 MainActivity.heartbeatCheck），这里什么都不做。
       */
      var rootEl = null;
      try { rootEl = document.getElementById('root'); } catch (e) { rootEl = null; }
      if (!rootEl) { setTimeout(tick, 1200); return; }

      var card = bootCard();
      var cardText = textOf(card);

      // ① 启动卡自己说失败了 —— 唯一可信的"界面没起来"
      if (cardText && FAIL.test(cardText)) {
        report(cardText);
        return;
      }
      var waited = Date.now() - t0;
      if (waited > 25000) {
        var appLen = appText().replace(/\s+/g, '').length;
        if (appLen >= 12) {
          W.__dshBootReported = true;
          // 界面正常 → 告诉 App 把"连续启动失败"计数清零（见 MainActivity.pageReady）
          try { if (W.DshAndroid && W.DshAndroid.pageReady) W.DshAndroid.pageReady(); } catch (e) { }
          return;
        }
        var loaded = true;
        try { loaded = document.readyState === 'complete'; } catch (e) { loaded = true; }
        if (!card) {
          // ② 没有启动卡，正文里却有失败文案 —— 兜底（DSH 可能改了属性名）
          var bodyText = textOf(document.body);
          if (FAIL.test(bodyText)) { report(bodyText); return; }
          // ③ 没有启动卡、正文也是空的：脚本压根没跑起来（老 WebView 解析失败就是这种）。
          //    等页面加载完再判 —— 慢机器上 600KB 的前端要跑一会儿，不能刚数到 25 秒
          //    就把用户的插件全停掉；最多等到 60 秒。
          if (loaded || waited > 60000) {
            report('(空白页面：主界面 25 秒内没有渲染出来)');
            return;
          }
        } else if (waited > 60000) {
          // ④ 启动卡在，但一直没往下走（转圈 / 等一个不存在的服务）
          report('(界面 60 秒没有渲染出来，启动卡仍停在「'
            + cardText.replace(/\s+/g, ' ').slice(0, 80) + '」)');
          return;
        }
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

  /*
   * 【退格键删附件】
   *
   * 用户反馈（群里的 bug 统计）：**在输入框插入图片后删不掉**。
   * 真机复现（0.3.2，vivo / Android 16）：
   *   · 用「+ → 文件」插一张图 → 输入框里出现缩略图卡片（带一个 ✕）；
   *   · **点 ✕ 能删**；但按退格键（输入框里已经没有文字）**毫无反应**。
   *
   * 原因不是 bug 而是 DSH 的结构：附件卡片**不在** contenteditable 里，
   * 它是编辑器外面的兄弟节点（实测 DOM：
   *   div.JVDQca_item > div._54WpYG_imageItem > button._54WpYG_thumbnail + button._54WpYG_remove
   * 而 remove 按钮的 aria-label 是「移除图片 xxx.gif」）——
   * 光标在编辑器里按退格，永远碰不到外面那颗卡片。
   *
   * 所以在我们的补丁层补上这个行为：**当输入框已经空了、又有附件时，退格 = 删最后一个附件**。
   * 为什么敢拦退格：
   *   · 只有「编辑器可见文本为空」且「确实找到附件」两个条件同时成立才动手，
   *     有字的时候一个字节都不碰（不影响正常删字）；
   *   · 附件按钮就在编辑器的祖先节点范围内，作用域是"离编辑器最近的那个含附件的容器"，
   *     不会误删别处（比如右侧栏预览面板）的按钮。
   */
  function patchComposerBackspace() {
    if (W.__dshBackspacePatch) return;
    W.__dshBackspacePatch = true;
    document.addEventListener('keydown', function (ev) {
      if (ev.key !== 'Backspace' && ev.keyCode !== 8) return;
      if (ev.shiftKey || ev.ctrlKey || ev.metaKey || ev.altKey) return;
      var t = ev.target;
      if (!t || !t.closest) return;
      var box = t.closest('[contenteditable="true"]');
      if (!box) return;

      // 有字时照旧删字：只用可见文本判断（零宽空格要排掉，Lexical 会塞）
      var text = (box.innerText || '').replace(/\u200b/g, '').trim();
      if (text) return;

      var btn = lastAttachmentRemove(box);
      if (!btn) return;
      ev.preventDefault();
      ev.stopImmediatePropagation();
      try { btn.click(); } catch (e) { /* 点不动就算了，别把输入卡住 */ }
    }, true);
  }

  /** 离编辑器最近的那个"含附件"的祖先容器里，最后一个可见的移除按钮。 */
  function lastAttachmentRemove(box) {
    var scope = box;
    var SEL = 'button[aria-label^="移除"],button[aria-label*="移除附件"]';
    for (var i = 0; i < 8 && scope; i++) {
      if (scope.querySelector && scope.querySelector(SEL)) break;
      scope = scope.parentElement;
    }
    if (!scope || !scope.querySelector) return null;
    var list = scope.querySelectorAll(SEL);
    var last = null;
    for (var j = 0; j < list.length; j++) {
      var r = list[j].getBoundingClientRect();
      if (r.width > 1 && r.height > 1) last = list[j];   // 按文档顺序取最后一个（= 最后插入的那个）
    }
    return last;
  }

  apply();

  W.__dshMobilePatch = apply;
})();
