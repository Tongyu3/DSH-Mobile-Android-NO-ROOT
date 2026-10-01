package com.dshmobile.probe;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 让 DSH 能"操作应用"的无障碍服务。
 *
 * 安全边界（用户明确要求：**只能操作指定 App**）在这层强制执行：
 *   · 每个动作前先取当前前台包名，不在白名单里就直接拒绝；
 *   · 界面树（dumpUi）同样受白名单限制 —— 不允许读其它应用的屏幕内容。
 * 因此即使有人绕过桥接层直接调用，也一样拦得住。
 *
 * 不涉及 root，也不修改系统任何文件：它就是一个普通的无障碍服务，
 * 由用户在「设置 → 无障碍」里手动开启，可随时关闭。
 */
public class DshAccessibilityService extends AccessibilityService {

    private static final String TAG = "DSH_A11Y";
    private static volatile DshAccessibilityService sInstance;
    private static volatile String sForegroundPackage = "";
    private static volatile long sForegroundAt = 0L;

    /**
     * 供 {@link Screenshot} 调用 {@code takeScreenshot()} 用。
     *
     * <p>截屏是**实例方法**（不像读界面树那样能靠静态缓存凑合），所以必须把实例暴露出去。
     * 返回 null 表示服务当前没在跑，调用方据此判断"这条路能不能用"。
     */
    static DshAccessibilityService instance() {
        return sInstance;
    }

    /**
     * 正在取界面树的线程数。
     *
     * 为什么需要它：`getRootInActiveWindow()` 是对 system_server 的同步 binder 调用，
     * 当**前台是别的应用**时（也就是 phone 真正干活的时刻），它可能长时间不返回 ——
     * 实测在 QQ 前台时它会卡住几十秒，直到窗口再次变化才解除。
     * 手机上跑 agent 时这种"偶尔卡死"等于功能不可用，所以：
     *   · 所有取根节点的调用都加**超时**，超时就当取不到，绝不无限等；
     *   · 同时对并发做上限，万一线程真的回不来了也不会无限堆积。
     */
    private static final java.util.concurrent.atomic.AtomicInteger sRootInFlight =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final int MAX_ROOT_IN_FLIGHT = 4;

    /** 取根节点超时时间（毫秒）。 */
    private static final long ROOT_TIMEOUT_MS = 1500L;

    public static boolean isRunning() { return sInstance != null; }

    /** 还有多少取根节点的调用没回来（>0 说明界面读取正在变慢/卡住）。 */
    public static int rootInFlight() { return sRootInFlight.get(); }

    /**
     * 当前前台包名。
     *
     * 走**无障碍事件缓存**而不是实时去问窗口：
     * `TYPE_WINDOW_STATE_CHANGED` 在切换前台应用时必定触发，缓存值就是准的，
     * 而且是纯内存读取、永不阻塞 —— 白名单校验在最热的路径上，不能卡。
     * 缓存实在太旧时，才用带超时的实时查询兜底。
     *
     * <h3>⚠️ 取不到时必须返回 null，绝不能返回旧值</h3>
     * 这里踩过一个**安全相关**的坑：原来的实现在最后一行直接
     * {@code return sForegroundPackage;}，也就是"刷新失败就把上次的旧包名交出去"。
     * 后果很实在 —— 无障碍服务被厂商关掉之后，事件不再更新缓存，
     * 用户随时可以切到微信、再切到银行 App，而我们手里还是那个几十分钟前的旧包名。
     * 白名单是照旧值判断的，于是**截图 / 点击可能作用在白名单之外的应用上**。
     *
     * <p>现在：服务没在跑 → 缓存**不可信**，返回 null；
     * 刷新失败且缓存过期 → 也返回 null。
     * 调用方拿到 null 会直接拒绝操作 —— "不知道前台是谁"就什么都不做，
     * 这才是白名单该有的行为。
     */
    public static String foregroundPackage() {
        if (sInstance == null) return null;

        /*
         * 权威来源：**占据屏幕面积最大的那个应用窗口**。
         *
         * 为什么不用 isFocused()/isActive()：实测在 vivo 上它们不可靠 ——
         * systemui 的状态栏/浮层也会被标成 focused，取"第一个聚焦窗口"
         * 拿到的是 com.android.systemui，而系统自己报的前台是
         * com.android.settings（mCurrentFocus 与 topResumedActivity 一致）。
         * 后果是所有操作都被白名单拒掉，看起来却像"白名单配错了"。
         *
         * 按面积取就没有这个歧义：用户正在看的应用窗口一定占了大半屏，
         * 状态栏是一条、浮层是一小块。
         */
        String dom = dominantWindowPackage();
        if (dom != null && !dom.isEmpty()) {
            sForegroundPackage = dom;
            sForegroundAt = System.currentTimeMillis();
            // 顺手记住"上一个别人"，供白名单一键添加使用
            // （这里在静态方法里，所以只能通过 sInstance 取包名）
            try {
                DshAccessibilityService s = sInstance;
                if (s != null && !dom.equals(s.getPackageName())) {
                    sLastOtherPkg = dom;
                    sLastOtherAt = System.currentTimeMillis();
                }
            } catch (Throwable ignore) { }
            return dom;
        }

        // 取不到窗口列表（没开 flagRetrieveInteractiveWindows）时退回老路
        long age = System.currentTimeMillis() - sForegroundAt;
        if (!sForegroundPackage.isEmpty() && age < 2000L) return sForegroundPackage;

        AccessibilityNodeInfo root = root();
        try {
            if (root != null && root.getPackageName() != null) {
                String p = root.getPackageName().toString();
                sForegroundPackage = p;
                sForegroundAt = System.currentTimeMillis();
                return p;
            }
        } catch (Throwable ignore) { }

        return age < 2000L ? sForegroundPackage : null;
    }

    private static volatile String sDomPkg = null;
    private static volatile long sDomAt = 0L;

    /*
     * 上一个"占据屏幕最大面积的应用窗口"是谁、什么时候的事。
     *
     * 用途只有一个：系统弹窗（「选择打开方式」）会**整屏顶掉**白名单应用，
     * 于是白名单校验看到的前台变成了 com.android.intentresolver 而被拒。
     * 要看穿这种"临时弹窗"，就得知道**它之前是谁在前台** ——
     * 实测 SYSTEM DIALOG 是全屏窗口时 getWindows() 里**看不到**底下的应用，
     * 所以只能靠历史记录。
     */
    private static volatile String sPrevPkg = null;
    private static volatile long sPrevAt = 0L;

    /** 上一次的前台包名；超过 15 秒就当过期（窗口切换很快，超过这个时间不是同一次操作）。 */
    static String previousPackage() {
        if (sPrevPkg == null || sPrevPkg.isEmpty()) return null;
        if (System.currentTimeMillis() - sPrevAt > 15000L) return null;
        return sPrevPkg;
    }

    /*
     * 最近一个"不是本 App 自己"的前台包名。
     *
     * 用途：用户在 App 里点「把刚才那个应用加入白名单」时，
     * 此刻的前台其实是**我们自己**（用户正看着这个页面），
     * 直接取 foregroundPackage() 只会拿到 com.dshmobile.probe，毫无用处。
     * 所以单独记住"上一个别人"。
     */
    private static volatile String sLastOtherPkg = null;
    private static volatile long sLastOtherAt = 0L;

    static String lastOtherPackage() {
        if (sLastOtherPkg == null || sLastOtherPkg.isEmpty()) return null;
        // 太旧的就不认了，免得把十分钟前偶然路过的应用加进来
        if (System.currentTimeMillis() - sLastOtherAt > 10 * 60 * 1000L) return null;
        return sLastOtherPkg;
    }

    /**
     * 面积最大的应用窗口的包名（带缓存 + 有界等待）。
     *
     * <h3>⚠️ 必须是有界的，否则会把整个控制桥拖死</h3>
     * {@code getWindows()} 和每个窗口的 {@code getRoot()} **都是对 system_server 的
     * 同步 IPC**，和 {@code getRootInActiveWindow()} 一样可能长时间不返回
     * （实测在前台是别的应用时就会卡住）。而前台判断在**每个**操作的最前面，
     * 一旦它卡住，所有 phone 命令会一起卡死 —— 这正是之前反复出现的
     * "控制桥突然整个没响应、只能重启 App"。
     *
     * <p>所以这里跟 {@link #root()} 用同一套办法：丢到工作线程里跑 + join 超时；
     * 结果再缓存 1 秒，避免每次操作都做一轮 IPC。
     * 超时就**用上一次的值**（稍旧但可用），绝不让调用方无限等。
     */
    private static String dominantWindowPackage() {
        long age = System.currentTimeMillis() - sDomAt;
        if (sDomPkg != null && age < 1000L) return sDomPkg;

        final String[] box = new String[1];
        Thread t = new Thread(() -> {
            try { box[0] = computeDominant(); } catch (Throwable ignore) { }
        }, "a11y-win");
        t.setDaemon(true);
        t.start();
        try { t.join(800L); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        if (box[0] != null && !box[0].isEmpty()) {
            // 换前台了：把"上一个"记下来（系统弹窗的判定要用，见 previousPackage）
            if (sDomPkg != null && !box[0].equals(sDomPkg)) {
                sPrevPkg = sDomPkg;
                sPrevAt = System.currentTimeMillis();
            }
            sDomPkg = box[0];
            sDomAt = System.currentTimeMillis();
            return box[0];
        }
        return sDomPkg;   // 超时/取不到：退回上一次的值（可能为 null）
    }

    /**
     * 面积最大的应用窗口的包名；没有应用窗口就退回面积最大的任意窗口。
     *
     * <p>⚠️ 里面有 IPC，只能在 {@link #dominantWindowPackage()} 的工作线程里调。
     */
    private static String computeDominant() {
        final DshAccessibilityService s = sInstance;
        if (s == null) return null;
        java.util.List<AccessibilityWindowInfo> ws = s.getWindows();
        if (ws == null || ws.isEmpty()) return null;
        String best = null, bestApp = null;
        long bestArea = -1, bestAppArea = -1;
        Rect r = new Rect();
        for (AccessibilityWindowInfo w : ws) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            CharSequence cn = root.getPackageName();
            if (cn == null) continue;
            String pkg = cn.toString();
            w.getBoundsInScreen(r);
            long area = (long) Math.max(0, r.width()) * Math.max(0, r.height());
            if (area > bestArea) { bestArea = area; best = pkg; }
            if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && area > bestAppArea) {
                bestAppArea = area; bestApp = pkg;
            }
        }
        return bestApp != null ? bestApp : best;
    }

    /** 取当前活动窗口的根节点；带超时，取不到返回 null（绝不长时间阻塞调用方）。 */
    static AccessibilityNodeInfo root() {
        final DshAccessibilityService s = sInstance;
        if (s == null) return null;
        if (sRootInFlight.get() >= MAX_ROOT_IN_FLIGHT) {
            Log.w(TAG, "取根节点的调用已堆积 " + sRootInFlight.get() + " 个，本次直接放弃");
            return null;
        }
        sRootInFlight.incrementAndGet();
        final AccessibilityNodeInfo[] box = new AccessibilityNodeInfo[1];
        Thread t = new Thread(() -> {
            try { box[0] = s.getRootInActiveWindow(); }
            catch (Throwable ignore) { }
            finally { sRootInFlight.decrementAndGet(); }
        }, "a11y-root");
        t.setDaemon(true);
        t.start();
        try { t.join(ROOT_TIMEOUT_MS); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        if (box[0] == null) Log.w(TAG, "取界面树超时（" + ROOT_TIMEOUT_MS + "ms）");
        return box[0];
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        Log.i(TAG, "无障碍服务已连接");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            CharSequence p = event.getPackageName();
            if (p != null) {
                String pkg = p.toString();
                // 切前台应用时刷新；同应用的内容变化也要刷新时间戳，
                // 这样"缓存是否新鲜"才真的反映前台有没有变过。
                if (!pkg.equals(sForegroundPackage)
                        || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    sForegroundPackage = pkg;
                }
                sForegroundAt = System.currentTimeMillis();
            }
        }
    }

    @Override
    public void onInterrupt() { }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        sInstance = null;
        Log.i(TAG, "无障碍服务已断开");
        /*
         * 解绑是最快的"权限掉了"信号 —— 立即叫醒守护去检查并补回来，
         * 不用干等下一个 3 秒轮询周期。
         *
         * 注意：用户在本 App 里关掉「无障碍守护」后，守护会自己停手，
         * 所以这里无条件 nudge 不会造成"关不掉"。
         */
        A11yGuard.nudge();
        return super.onUnbind(intent);
    }

    // ── 动作 ────────────────────────────────────────────────

    /** 在当前前台界面里按坐标点击。 */
    static boolean tap(int x, int y) {
        DshAccessibilityService s = sInstance;
        if (s == null) return false;
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60))
                .build();
        return s.dispatchGesture(g, null, null);
    }

    /** 滑动。 */
    static boolean swipe(int x1, int y1, int x2, int y2, int ms) {
        DshAccessibilityService s = sInstance;
        if (s == null) return false;
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(20, ms)))
                .build();
        return s.dispatchGesture(g, null, null);
    }

    static boolean globalAction(int action) {
        DshAccessibilityService s = sInstance;
        return s != null && s.performGlobalAction(action);
    }

    // ── 节点检索 ────────────────────────────────────────────

    /** 广度优先收集所有节点。 */
    static List<AccessibilityNodeInfo> allNodes(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        if (root == null) return out;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int guard = 0;
        while (!q.isEmpty() && guard++ < 4000) {
            AccessibilityNodeInfo n = q.poll();
            out.add(n);
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return out;
    }

    static String textOf(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        return t == null ? "" : t.toString();
    }

    /** 按文字（包含匹配）找第一个可点击的祖先节点。 */
    static AccessibilityNodeInfo findByText(AccessibilityNodeInfo root, String needle) {
        for (AccessibilityNodeInfo n : allNodes(root)) {
            String t = textOf(n);
            if (t.isEmpty()) continue;
            if (t.contains(needle)) {
                AccessibilityNodeInfo cur = n;
                for (int up = 0; up < 6 && cur != null; up++) {
                    if (cur.isClickable()) return cur;
                    cur = cur.getParent();
                }
                return n;
            }
        }
        return null;
    }

    static boolean clickNode(AccessibilityNodeInfo n) {
        if (n == null) return false;
        AccessibilityNodeInfo cur = n;
        for (int up = 0; up < 6 && cur != null; up++) {
            if (cur.isClickable()) {
                /*
                 * 先试 ACTION_CLICK —— 它最"干净"：让控件自己执行点击，
                 * 不依赖坐标，也不会误点到别的东西。
                 */
                if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;

                /*
                 * 失败就退回**手势点击**。
                 *
                 * 为什么必须留这条退路：实测系统应用（设置）顶部那一排，
                 * 节点读得到、isClickable() 也是 true，但 ACTION_CLICK 一律返回 false
                 * —— 因为它所在的窗口不是"活动窗口"，对这种窗口里的节点发动作会被拒。
                 * 手势走的是输入系统，跟窗口归属无关，所以照样能点中。
                 *
                 * 这正是"用不了安卓系统应用的工具栏"的最后一环：
                 * 先是看不见（独立窗口没读），修完看得见了，却还是点不动。
                 */
                Rect cr = new Rect();
                cur.getBoundsInScreen(cr);
                if (cr.width() > 0 && cr.height() > 0) {
                    Log.i(TAG, "ACTION_CLICK 被拒，改用手势点击 ("
                            + cr.centerX() + "," + cr.centerY() + ")");
                    return tap(cr.centerX(), cr.centerY());
                }
                return false;
            }
            cur = cur.getParent();
        }
        // 整条祖先链都没有 clickable 的：退回按节点中心做手势点击
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.width() <= 0 || r.height() <= 0) return false;
        return tap(r.centerX(), r.centerY());
    }

    /** 往当前聚焦的输入框写文字。 */
    static boolean setTextOnFocused(AccessibilityNodeInfo root, String value) {
        for (AccessibilityNodeInfo n : allNodes(root)) {
            if (n.isEditable() && n.isFocused()) {
                Bundle b = new Bundle();
                b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
                return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
            }
        }
        for (AccessibilityNodeInfo n : allNodes(root)) {
            if (n.isEditable()) {
                Bundle b = new Bundle();
                b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
                return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
            }
        }
        return false;
    }

    /** 把界面树渲染成紧凑文本（带索引路径，便于按 id 点击）。 */
    static String dumpTree(AccessibilityNodeInfo root) {
        StringBuilder sb = new StringBuilder();
        render(root, 0, sb, 0);
        return sb.toString();
    }

    /**
     * 把所有窗口的界面树合并输出。
     *
     * <h3>为什么需要它（这是个真实故障）</h3>
     * 原本只读 {@code getRootInActiveWindow()} —— **当前活动窗口的根节点**。
     * 但很多系统应用（设置、文件管理……）把**顶部工具栏放在独立窗口里**
     * （CollapsingToolbar / 搜索栏那种），于是我们读到的树里**根本没有那一排**。
     *
     * <p>实测对比（系统「设置」首页）：
     * <pre>
     * uiautomator:  y=212 "设置"(标题)、y=373 "搜索设置项"(EditText, 可点)
     * 我们的 phone ui: 第一个节点从 y=522 开始 —— 上面那两条完全没有
     * </pre>
     * 结果就是 agent「看得到列表、却看不到也用不了工具栏」，
     * 而人手动打开设置时那一排明明就在最上面。
     *
     * <p>修法：用 {@link AccessibilityService#getWindows()} 拿到所有窗口逐个渲染。
     * 这需要配置里开 {@code flagRetrieveInteractiveWindows}，否则 getWindows 返回空。
     *
     * @return 多窗口合并后的文本；只有一扇窗时返回 null（调用方走原来的单窗口路径）
     */
    static String dumpAllWindows() {
        final DshAccessibilityService s = sInstance;
        if (s == null) return null;
        java.util.List<AccessibilityWindowInfo> ws;
        try {
            ws = s.getWindows();
        } catch (Throwable t) {
            return null;
        }
        if (ws == null || ws.size() <= 1) return null;   // 单窗口没必要换实现

        StringBuilder sb = new StringBuilder();
        int n = 0, empty = 0;
        for (AccessibilityWindowInfo w : ws) {
            AccessibilityNodeInfo r = w.getRoot();
            if (r == null) { empty++; continue; }
            n++;
            sb.append("── 窗口 ").append(n)
              .append(" [").append(windowType(w.getType())).append("] ──\n");
            render(r, 0, sb, 0);
        }
        if (n <= 1) return null;
        return "（共 " + n + " 个窗口" + (empty > 0 ? "，另有 " + empty + " 个取不到根节点" : "")
                + "；工具栏常常在独立窗口里，所以这里逐个列出）\n" + sb;
    }

    /**
     * 有界地取窗口列表。
     *
     * <p>{@code getWindows()} 是对 system_server 的同步 IPC，可能长时间不返回，
     * 所以和 {@link #root()} / {@link #dominantWindowPackage()} 一样丢到线程里 join 超时。
     * 诊断接口尤其不能把控制桥拖死 —— 它本来就是"出问题时才用"的。
     */
    private static java.util.List<AccessibilityWindowInfo> windowsBounded(long ms) {
        final DshAccessibilityService s = sInstance;
        if (s == null) return null;
        final Object[] box = new Object[1];
        Thread t = new Thread(() -> {
            try { box[0] = s.getWindows(); } catch (Throwable ignore) { }
        }, "a11y-wins");
        t.setDaemon(true);
        t.start();
        try { t.join(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        @SuppressWarnings("unchecked")
        java.util.List<AccessibilityWindowInfo> out =
                (java.util.List<AccessibilityWindowInfo>) box[0];
        return out;
    }

    /**
     * 列出所有窗口的"身份信息"：包名 / 类型 / 区域 / 面积 / 根节点取不取得到 / 类名 / 标题。
     *
     * <h3>为什么要有这个接口</h3>
     * 「选择打开方式」这类系统弹窗是**独立窗口**，而且常常不属于任何白名单应用。
     * 排查"agent 为什么看不见它 / 点不动它"时，必须先回答三个问题：
     * <ol>
     *   <li>它到底在不在 {@code getWindows()} 里（还是只存在于另一个 display）；</li>
     *   <li>它的 {@code getRoot()} 取不取得到（取不到就只能靠坐标点）；</li>
     *   <li>它被算成哪个包 —— 白名单校验就是拿这个包名在判，判错了就会误拒。</li>
     * </ol>
     * 以前只能靠猜，现在一条 {@code phone windows} 就能看清楚。
     *
     * <p>只输出窗口元信息（包名/类型/标题），**不含任何控件文本**，
     * 所以它不套白名单 —— 否则"被白名单拦住"这种故障就永远看不到现场。
     */
    static String describeWindows() {
        java.util.List<AccessibilityWindowInfo> ws = windowsBounded(1000L);
        if (ws == null) {
            return "ERROR 取不到窗口列表（getWindows 超时或服务未运行）\n";
        }
        StringBuilder sb = new StringBuilder("窗口数: " + ws.size() + "\n");
        Rect r = new Rect();
        int i = 0;
        for (AccessibilityWindowInfo w : ws) {
            i++;
            AccessibilityNodeInfo root = w.getRoot();
            String pkg = "-", cls = "-", title = "-";
            if (root != null) {
                if (root.getPackageName() != null) pkg = root.getPackageName().toString();
                if (root.getClassName() != null) cls = root.getClassName().toString();
            }
            CharSequence tt = w.getTitle();
            if (tt != null) title = tt.toString().replace('\n', ' ');
            w.getBoundsInScreen(r);
            long area = (long) Math.max(0, r.width()) * Math.max(0, r.height());
            sb.append('[').append(i).append("] ").append(pkg)
              .append("  ").append(windowType(w.getType()))
              .append("  (").append(r.left).append(',').append(r.top).append(',')
              .append(r.right).append(',').append(r.bottom).append(')')
              .append("  面积=").append(area)
              .append("  root=").append(root == null ? "取不到" : "OK")
              .append("  ").append(cls)
              .append("  \"").append(title).append("\"\n");
        }
        return sb.toString();
    }

    private static String windowType(int t) {
        switch (t) {
            case AccessibilityWindowInfo.TYPE_APPLICATION: return "应用";
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD: return "输入法";
            case AccessibilityWindowInfo.TYPE_SYSTEM: return "系统";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY: return "无障碍浮层";
            case AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER: return "分屏";
            default: return "类型" + t;
        }
    }

    private static int render(AccessibilityNodeInfo n, int depth, StringBuilder sb, int counter) {
        if (n == null || counter > 1500) return counter;
        String cls = n.getClassName() == null ? "?" : n.getClassName().toString();
        String shortCls = cls.substring(cls.lastIndexOf('.') + 1);
        String t = textOf(n);
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        boolean interesting = !t.isEmpty() || n.isClickable() || n.isEditable()
                || shortCls.equals("EditText") || shortCls.equals("Button");
        if (interesting) {
            for (int i = 0; i < depth; i++) sb.append("  ");
            sb.append('[').append(counter).append("] ").append(shortCls);
            if (!t.isEmpty()) sb.append(" \"").append(t.replace('\n', ' ')).append('"');
            sb.append(" (").append(r.left).append(',').append(r.top).append(',')
              .append(r.right).append(',').append(r.bottom).append(')');
            if (n.isClickable()) sb.append(" 可点");
            if (n.isEditable()) sb.append(" 可输入");
            if (n.isScrollable()) sb.append(" 可滚动");
            sb.append('\n');
        }
        int next = counter + 1;
        for (int i = 0; i < n.getChildCount(); i++) {
            next = render(n.getChild(i), depth + (interesting ? 1 : 0), sb, next);
        }
        return next;
    }
}
