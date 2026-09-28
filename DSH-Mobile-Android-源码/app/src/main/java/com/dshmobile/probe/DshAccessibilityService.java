package com.dshmobile.probe;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

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
     */
    public static String foregroundPackage() {
        long age = System.currentTimeMillis() - sForegroundAt;
        if (!sForegroundPackage.isEmpty() && age < 5000L) return sForegroundPackage;

        AccessibilityNodeInfo root = root();
        try {
            if (root != null && root.getPackageName() != null) {
                String p = root.getPackageName().toString();
                sForegroundPackage = p;
                sForegroundAt = System.currentTimeMillis();
                return p;
            }
        } catch (Throwable ignore) { }

        return sForegroundPackage;
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
                return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            cur = cur.getParent();
        }
        // 不可点击就退回坐标点击
        Rect r = new Rect();
        n.getBoundsInScreen(r);
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
