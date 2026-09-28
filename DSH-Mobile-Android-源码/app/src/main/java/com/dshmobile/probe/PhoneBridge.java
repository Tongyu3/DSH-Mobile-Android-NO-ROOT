package com.dshmobile.probe;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 手机控制桥：容器里的 agent 通过它来操作手机。
 *
 * 三层安全：
 *   1. 只监听 127.0.0.1（回环），不对外网开放；
 *   2. 每次请求必须带 token，token 由 App 随机生成、写在应用私有目录里，
 *      再通过只读绑定暴露给容器（放在共享存储会被其它应用读到）；
 *   3. **白名单在动作发生前强制校验**：当前前台包名不在白名单里就直接拒绝，
 *      连界面树都不返回 —— 用户要求"只能操作指定 App"。
 */
public final class PhoneBridge {

    private static final String TAG = "DSH_PHONE";
    public static final int PORT = 3099;
    public static final String PREFS = "dsh_phone";
    private static final String KEY_ALLOWED = "allowed_packages";
    private static final String KEY_TOKEN = "token";

    private static volatile boolean sRunning = false;

    /**
     * 最近几次"慢请求"的记录。
     *
     * 这台机器上 OriginOS 不让 adb 读 logcat（读出来是空的），所以 App 得自己
     * 把可疑的耗时记下来，通过 /status 暴露出去 —— 否则卡顿只能靠猜。
     */
    private static final java.util.concurrent.ConcurrentLinkedDeque<String> sSlow =
            new java.util.concurrent.ConcurrentLinkedDeque<>();
    private static volatile long sReqCount = 0L;

    private static void noteSlow(String path, long ms) {
        sReqCount++;
        if (ms < 300L) return;
        sSlow.addFirst(path + " 耗时 " + ms + "ms");
        while (sSlow.size() > 8) sSlow.pollLast();
    }

    private PhoneBridge() { }

    // ── 白名单 ──────────────────────────────────────────────

    public static Set<String> allowed(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_ALLOWED, "");
        Set<String> out = new LinkedHashSet<>();
        if (raw != null && !raw.isEmpty()) {
            for (String s : raw.split(",")) if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    public static void setAllowed(Context c, Set<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        for (String p : pkgs) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p);
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ALLOWED, sb.toString()).apply();
    }

    // ── token ───────────────────────────────────────────────

    public static String token(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String t = sp.getString(KEY_TOKEN, "");
        if (t == null || t.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            java.util.Random r = new java.util.Random();
            for (int i = 0; i < 32; i++) sb.append("0123456789abcdef".charAt(r.nextInt(16)));
            t = sb.toString();
            sp.edit().putString(KEY_TOKEN, t).apply();
        }
        return t;
    }

    /** 把 token 写到应用私有目录，供容器通过只读绑定读取。 */
    public static String tokenDir(Context c) {
        File d = new File(c.getFilesDir(), "phone");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        File f = new File(d, "token");
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(token(c).getBytes("UTF-8"));
        } catch (Throwable t) {
            Log.e(TAG, "写 token 失败", t);
        }
        return d.getAbsolutePath();
    }

    // ── 服务 ────────────────────────────────────────────────

    public static synchronized void start(final Context ctx) {
        if (sRunning) return;
        sRunning = true;
        final Context app = ctx.getApplicationContext();
        Thread t = new Thread(() -> {
            ServerSocket ss = null;
            try {
                ss = new ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"));
                Log.i(TAG, "控制桥已启动 127.0.0.1:" + PORT);
                while (sRunning) {
                    final Socket s = ss.accept();
                    /*
                     * 每个请求单独一个线程。
                     *
                     * 原来是在 accept 循环里**顺序**处理：实测只要有一个请求卡住
                     * （取界面树时最典型），后面所有请求全部排队等死 ——
                     * 连 `phone status` 这种纯内存查询都拿不到回应。
                     * agent 会同时发好几个请求，一条卡住就整个功能瘫掉，必须并发。
                     */
                    Thread w = new Thread(() -> {
                        try {
                            s.setSoTimeout(8000);
                            handle(app, s);
                        } catch (Throwable e) {
                            Log.w(TAG, "handle 失败", e);
                        } finally {
                            try { s.close(); } catch (Throwable ignore) { }
                        }
                    }, "phone-bridge-req");
                    w.setDaemon(true);
                    w.start();
                }
            } catch (Throwable e) {
                Log.e(TAG, "控制桥退出", e);
                sRunning = false;
            } finally {
                try { if (ss != null) ss.close(); } catch (Throwable ignore) { }
            }
        }, "phone-bridge");
        t.setDaemon(true);
        t.start();
    }

    private static void handle(Context ctx, Socket s) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
        String requestLine = in.readLine();
        if (requestLine == null) return;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return;
        String path = parts[1];
        String query = "";
        int q = path.indexOf('?');
        if (q >= 0) { query = path.substring(q + 1); path = path.substring(0, q); }

        java.util.Map<String, String> p = parseQuery(query);
        String body;
        int code = 200;

        if (!token(ctx).equals(p.get("token"))) {
            body = "ERROR 403: token 不正确\n";
            code = 403;
        } else {
            long t0 = System.currentTimeMillis();
            try {
                body = dispatch(ctx, path, p);
            } catch (Throwable e) {
                body = "ERROR 500: " + e + "\n";
                code = 500;
            } finally {
                noteSlow(path, System.currentTimeMillis() - t0);
            }
        }

        byte[] bytes = body.getBytes("UTF-8");
        String header = "HTTP/1.1 " + code + " OK\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream os = s.getOutputStream();
        os.write(header.getBytes("UTF-8"));
        os.write(bytes);
        os.flush();
    }

    private static java.util.Map<String, String> parseQuery(String q) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        if (q == null || q.isEmpty()) return m;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i < 0) continue;
            try {
                m.put(java.net.URLDecoder.decode(kv.substring(0, i), "UTF-8"),
                      java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
            } catch (Throwable ignore) { }
        }
        return m;
    }

    /**
     * 把用户说的「应用名或包名」解析成白名单里的真实包名；解析不到返回 null。
     *
     * <p>抽出来是为了让无障碍后端和「直接命令」后端共用同一套匹配规则 ——
     * 否则两条路对"什么算白名单内的应用"判断不一致，等于开了个后门。
     */
    private static String resolveAllowed(Context ctx, String q) {
        Set<String> allow = allowed(ctx);
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        android.content.Intent main = new android.content.Intent(
                android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER);
        String fuzzy = null;
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (!allow.contains(pkg)) continue;              // 白名单外直接跳过
            String label;
            try {
                label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (Throwable t) {
                label = pkg;
            }
            if (pkg.equalsIgnoreCase(q) || label.equalsIgnoreCase(q)) return pkg;
            if (fuzzy == null && label.toLowerCase().contains(q.toLowerCase())) fuzzy = pkg;
        }
        return fuzzy;
    }

    private static String openMissMessage(Context ctx, String q) {
        return "ERROR 白名单里没有匹配「" + q + "」的应用。\n"
             + "白名单当前为: " + allowed(ctx) + "\n"
             + "请让用户先在 App 的「📱 手机控制」里加入该应用。\n";
    }

    /** 白名单校验：返回当前前台包名（通过），或 null（被拒）。 */
    private static String guard(Context ctx) {        if (!DshAccessibilityService.isRunning()) {
            throw new IllegalStateException(
                    "无障碍服务未开启。请在 设置 → 无障碍 中打开「DSH 手机控制」");
        }
        String fg = DshAccessibilityService.foregroundPackage();
        Set<String> allow = allowed(ctx);
        if (allow.isEmpty()) {
            throw new IllegalStateException(
                    "白名单为空：请先在 DSH 设置 →「📱 手机控制」里勾选允许操作的应用");
        }
        if (fg == null || fg.isEmpty() || !allow.contains(fg)) {
            throw new IllegalStateException("当前前台应用 [" + fg + "] 不在白名单里，已拒绝");
        }
        return fg;
    }

    private static String dispatch(Context ctx, String path, java.util.Map<String, String> p) {
        /*
         * 用户选了「直接命令」后端且 Shizuku 可用时，这些动作改走 shell。
         *
         * 注意白名单校验在 ShellControl 内部照原样执行 —— 这条路的权限比无障碍大，
         * 边界更不能松。不在这份 switch 里的接口（/apps 等）继续走无障碍实现，
         * 因为它们本来就不需要 shell。
         */
        if (ShellControl.active(ctx)) {
            try {
                switch (path) {
                    case "/status": return ShellControl.status(ctx);
                    case "/ui":     return ShellControl.dumpUi(ctx);
                    case "/tap":    return ShellControl.tap(ctx,
                            Integer.parseInt(p.get("x")), Integer.parseInt(p.get("y")));
                    case "/click":  return ShellControl.clickText(ctx, p.get("text"));
                    case "/swipe":  return ShellControl.swipe(ctx,
                            Integer.parseInt(p.get("x1")), Integer.parseInt(p.get("y1")),
                            Integer.parseInt(p.get("x2")), Integer.parseInt(p.get("y2")),
                            Integer.parseInt(p.getOrDefault("ms", "300")));
                    case "/text":   return ShellControl.inputText(ctx, p.getOrDefault("value", ""));
                    case "/key":    return ShellControl.key(ctx, p.getOrDefault("name", ""));
                    case "/open": {
                        String q = p.get("q");
                        if (q == null || q.trim().isEmpty()) return "ERROR 需要 q=<包名或应用名>\n";
                        String pkg = resolveAllowed(ctx, q.trim());
                        if (pkg == null) return openMissMessage(ctx, q.trim());
                        return ShellControl.open(ctx, pkg);
                    }
                    default: break;   // 其余接口不涉及 shell，落到下面的无障碍实现
                }
            } catch (Throwable t) {
                return "ERROR " + t.getMessage() + "\n";
            }
        }
        switch (path) {
            case "/status": {
                Set<String> allow = allowed(ctx);
                String fg = DshAccessibilityService.foregroundPackage();
                StringBuilder sb = new StringBuilder();
                sb.append("无障碍服务: ").append(DshAccessibilityService.isRunning() ? "已开启" : "未开启").append("\n")
                  .append("当前前台: ").append(fg == null || fg.isEmpty() ? "(未知)" : fg).append("\n")
                  .append("白名单(").append(allow.size()).append("): ").append(allow).append("\n")
                  .append("当前是否允许操作: ")
                  .append(DshAccessibilityService.isRunning() && allow.contains(fg) ? "是" : "否").append("\n");
                int inflight = DshAccessibilityService.rootInFlight();
                if (inflight > 0) {
                    sb.append("界面读取正在等待的调用: ").append(inflight)
                      .append("（数字持续不降说明取界面树在被系统拖住）\n");
                }
                sb.append("已处理请求: ").append(sReqCount).append("\n");
                if (!sSlow.isEmpty()) {
                    sb.append("最近的慢请求:\n");
                    for (String r : sSlow) sb.append("  ").append(r).append('\n');
                }
                return sb.toString();
            }
            case "/ui": {
                String fg = guard(ctx);
                AccessibilityNodeInfo root = DshAccessibilityService.root();
                if (root == null) return "ERROR: 取不到界面（可能正在切换窗口）\n";
                return "前台应用: " + fg + "\n" + DshAccessibilityService.dumpTree(root);
            }
            case "/tap": {
                guard(ctx);
                int x = Integer.parseInt(p.get("x")), y = Integer.parseInt(p.get("y"));
                return DshAccessibilityService.tap(x, y) ? "OK 已点击 (" + x + "," + y + ")\n" : "ERROR 点击失败\n";
            }
            case "/click": {
                guard(ctx);
                String t = p.get("text");
                AccessibilityNodeInfo n = DshAccessibilityService.findByText(DshAccessibilityService.root(), t);
                if (n == null) return "ERROR 界面上找不到文字: " + t + "\n";
                return DshAccessibilityService.clickNode(n) ? "OK 已点击「" + t + "」\n" : "ERROR 点击失败\n";
            }
            case "/swipe": {
                guard(ctx);
                return DshAccessibilityService.swipe(
                        Integer.parseInt(p.get("x1")), Integer.parseInt(p.get("y1")),
                        Integer.parseInt(p.get("x2")), Integer.parseInt(p.get("y2")),
                        Integer.parseInt(p.getOrDefault("ms", "300"))) ? "OK 已滑动\n" : "ERROR 滑动失败\n";
            }
            case "/text": {
                guard(ctx);
                return DshAccessibilityService.setTextOnFocused(
                        DshAccessibilityService.root(), p.getOrDefault("value", ""))
                        ? "OK 已输入\n" : "ERROR 没有找到可输入的焦点框\n";
            }
            case "/key": {
                String name = p.getOrDefault("name", "");
                int action;
                switch (name) {
                    case "back": action = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK; break;
                    case "home": action = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME; break;
                    case "recents": action = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS; break;
                    case "notifications": action = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS; break;
                    default: return "ERROR 不支持的按键: " + name + "（可用 back/home/recents/notifications）\n";
                }
                // 全局按键不改动目标应用数据，但仍要求服务开启
                if (!DshAccessibilityService.isRunning()) return "ERROR 无障碍服务未开启\n";
                return DshAccessibilityService.globalAction(action) ? "OK 已发送 " + name + "\n" : "ERROR 失败\n";
            }
            case "/apps": {
                Set<String> allow = allowed(ctx);
                StringBuilder sb = new StringBuilder("白名单应用:\n");
                for (String a : allow) sb.append("  ").append(a).append('\n');
                if (allow.isEmpty()) sb.append("  (空)\n");
                return sb.toString();
            }
            case "/open": {
                /*
                 * 启动白名单里的应用。
                 *
                 * 实测暴露的需求：没有这个接口时，agent 只能说"请你手动点开 QQ"——
                 * 而"打开某应用"本来是操控应用最基本的一步。
                 * 安全上仍然只允许启动**白名单内**的应用，
                 * 否则 agent 就能把所有 App 都拉起来，"只能操作指定 App"的约束会被绕开。
                 */
                String q = p.get("q");
                if (q == null || q.trim().isEmpty()) return "ERROR 需要 q=<包名或应用名>\n";
                q = q.trim();
                Set<String> allow = allowed(ctx);
                if (allow.isEmpty()) {
                    return "ERROR 白名单为空：请先在 DSH 设置 →「📱 手机控制」里勾选允许操作的应用\n";
                }
                String hitPkg = resolveAllowed(ctx, q);
                if (hitPkg == null) return openMissMessage(ctx, q);
                String hitLabel = hitPkg;
                try {
                    hitLabel = ctx.getPackageManager()
                            .getApplicationLabel(ctx.getPackageManager().getApplicationInfo(hitPkg, 0))
                            .toString();
                } catch (Throwable ignore) { }
                try {
                    android.content.pm.PackageManager pm = ctx.getPackageManager();
                    android.content.Intent launch = pm.getLaunchIntentForPackage(hitPkg);
                    if (launch == null) return "ERROR 该应用没有可启动的入口: " + hitPkg + "\n";
                    launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(launch);
                    return "OK 已启动「" + hitLabel + "」(" + hitPkg + ")\n"
                         + "稍等 1-2 秒后可再用 phone ui 读取它的界面。\n";
                } catch (Throwable t) {
                    return "ERROR 启动失败: " + t + "\n";
                }
            }
            default:
                return "ERROR 404: 未知接口 " + path + "\n"
                     + "可用: /status /ui /tap?x&y /click?text /swipe?x1&y1&x2&y2&ms "
                     + "/text?value /key?name /apps /open?q=应用名或包名\n";
        }
    }
}
