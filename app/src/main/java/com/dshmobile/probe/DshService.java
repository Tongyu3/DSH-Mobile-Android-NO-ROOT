package com.dshmobile.probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 常驻前台服务：在容器里维持 `dsh --profile web` 进程。
 *
 * 为什么需要它：
 *   原先 dsh 进程属于 Activity，一旦 App 退到后台，系统随时可能回收，
 *   在 OriginOS 这类后台管理激进的 ROM 上尤其明显。放进前台服务后，
 *   进程有常驻通知与前台优先级，切后台也能继续跑。
 *
 * 注意：本项目 targetSdk = 28（为了 W^X 豁免），所以
 *   · 不需要声明 foregroundServiceType
 *   · 不需要 POST_NOTIFICATIONS 运行时权限
 * 这两个要求都是 targetSdk 34/33 才生效的。
 *
 * 状态通过静态字段暴露给 Activity 轮询（不引入 AndroidX / 广播框架）。
 */
public class DshService extends Service {

    private static final String TAG = "DSH_SERVICE";    private static final String CH_ID = "dsh_runtime";
    private static final int NOTIF_ID = 0x4453;   // "DS"
    public static final String PREFS = "dsh_settings";
    public static final String KEY_API = "deepseek_api_key";

    /** 供 Activity 轮询的状态。 */
    private static volatile String sUrl;
    private static volatile String sState = "未启动";
    private static volatile Process sProcess;

    private volatile boolean keepRunning = true;
    /** 由 Activity 置位：请求重启容器内的 dsh（例如 API Key 改过）。 */
    private static volatile boolean sRestartRequested = false;

    /** 当前实例。用来让别处（比如换了图标配色）催它刷新一下通知。 */
    private static volatile DshService sInstance;

    /**
     * 让服务重建一次它的常驻通知。
     *
     * <p>为什么要这个：通知的强调色和图标是**构建那一刻**定下来的，
     * 用户在「桌面图标」里换了配色之后，如果服务不重建通知，
     * 通知栏里那条会一直挂着旧颜色 —— 看起来就像"没生效"。
     */
    public static void refreshNotification() {
        DshService s = sInstance;
        if (s == null) return;
        try {
            s.updateNotification(sState);
        } catch (Throwable ignore) { }
    }

    /**
     * 容器还没装好（首次初始化没完成）。
     *
     * 原来的 startDsh() 遇到这种情况只每 10 秒静默重试一次，界面上完全看不出来，
     * 用户只能看到"一直没反应"。现在把它暴露出去，Activity 回到前台时
     * 可以据此自动补跑初始化。
     */
    private static volatile boolean sNeedsProvision = false;

    public static boolean getNeedsProvision() { return sNeedsProvision; }

    /** onCreate 是否跑过 —— 用来区分"服务根本没起来"和"起来了但 dsh 没就绪"。 */
    private static volatile boolean sCreated = false;
    public static boolean isCreated() { return sCreated; }

    /**
     * dsh 进程最近输出的若干行。
     *
     * 为什么必须留：原来这段输出**只看 URL、其余全部丢弃**，
     * 于是 dsh 起不来的时候，用户和我们都拿不到任何原因 ——
     * 只能看到"等待超时"。现在失败信息里会带上这几行。
     */
    private static final java.util.ArrayDeque<String> sRecent =
            new java.util.ArrayDeque<>();
    private static final int RECENT_MAX = 60;

    public static String getRecentOutput() {
        synchronized (sRecent) {
            if (sRecent.isEmpty()) return "(dsh 没有任何输出)";
            StringBuilder sb = new StringBuilder();
            int skip = Math.max(0, sRecent.size() - 12);   // 只给最后 12 行
            int i = 0;
            for (String l : sRecent) {
                if (i++ < skip) continue;
                sb.append("      ").append(l).append('\n');
            }
            return sb.toString();
        }
    }

    /**
     * 记一条"非 dsh 进程"的事件到最近输出里（例如无障碍守护的自愈动作）。
     *
     * <p>放进同一个环形缓冲是为了让「复制日志」一次带走全部线索 ——
     * 之前排查权限问题时，最需要的信息恰恰在 dsh 的输出之外。
     */
    public static void note(String line) {
        if (line == null) return;
        synchronized (sRecent) {
            sRecent.addLast("[守护] " + line);
            while (sRecent.size() > RECENT_MAX) sRecent.removeFirst();
        }
    }

    /** 像报错的行（用于把关键输出写进报告）。 */
    private static boolean looksLikeError(String line) {
        String s = line.toLowerCase();
        return s.contains("error") || s.contains("eacces") || s.contains("enoent")
            || s.contains("eaddrinuse") || s.contains("cannot") || s.contains("cannot find")
            || s.contains("throw") || s.contains("exception") || s.contains("failed")
            || s.contains("listen") || s.contains("port");
    }

    /** 往报告文件追加（和 MainActivity 用同一个文件，方便「复制日志」一次带走）。 */
    private void appendReport(String text) {
        try (java.io.OutputStream os = new java.io.FileOutputStream(
                new java.io.File(Env.base(this), "report.txt"), true)) {
            os.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignore) { }
    }

    /** 请求重启 dsh 进程（不重启 Service 本身，避免 stop/start 的时序问题）。 */
    public static void requestRestart() { sRestartRequested = true; }

    public static String getUrl() { return sUrl; }

    public static String getState() { return sState; }

    public static boolean isAlive() {
        Process p = sProcess;
        return p != null && p.isAlive();
    }

    public static String getApiKey(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String v = sp.getString(KEY_API, "");
        return v == null ? "" : v.trim();
    }

    public static void setApiKey(Context c, String key) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_API, key == null ? "" : key.trim()).apply();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        sCreated = true;
        sInstance = this;
        createChannel();
        startForeground(NOTIF_ID, buildNotification("正在启动容器…"));
        new Thread(this::supervise, "dsh-supervisor").start();
        new Thread(this::watchRestart, "dsh-restart-watcher").start();
        // 无障碍守护：厂商把无障碍开关关掉时自动写回去（需要一次性 WRITE_SECURE_SETTINGS 授权）
        A11yGuard.start(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        keepRunning = false;
        if (sInstance == this) sInstance = null;
        try {
            if (sProcess != null) sProcess.destroy();
        } catch (Throwable ignore) { }
        sProcess = null;
        sUrl = null;
        sState = "已停止";
        super.onDestroy();
    }

    // ── 监督循环：进程挂了就重启 ──────────────────────────────
    private void supervise() {
        while (keepRunning) {
            try {
                if (!isAlive()) {
                    startDsh();
                }
                Thread.sleep(5000);
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                Log.e(TAG, "supervise error", t);
                setState("异常: " + t.getMessage());
                try { Thread.sleep(5000); } catch (InterruptedException ie) { return; }
            }
        }
    }

    /**
     * 处理"用户改了设置"导致的重启请求。
     *
     * 为什么必须是独立线程：startDsh() 内部会**阻塞读取 dsh 的输出流**直到进程结束，
     * 所以 supervise() 在 dsh 运行期间根本回不到循环顶部去检查重启标记。
     * （这是实际踩过的坑：改完 API Key 点保存，界面显示"正在重启"但容器纹丝不动。）
     *
     * 这里主动杀掉进程，读取线程随之 EOF、startDsh() 返回，
     * supervise() 就会用新的 API Key 重新拉起容器。
     */
    private void watchRestart() {
        while (keepRunning) {
            try {
                if (sRestartRequested) {
                    sRestartRequested = false;
                    Log.i(TAG, "收到重启请求，杀掉当前 dsh 进程");
                    setState("正在重启…");
                    updateNotification("正在按新设置重启…");
                    Process p = sProcess;
                    /*
                     * sUrl 必须在 destroy **之前**清掉。
                     * 否则会和 supervise() 里刚起来的 dsh 抢时序：
                     * 新进程已经打印了 URL、sUrl 刚被赋值，这里再清一次就把它抹掉了，
                     * 而 dsh 只在启动时打印一次 URL —— 结果是 awaitUrl 永远等不到。
                     */
                    sUrl = null;
                    if (p != null) {
                        p.destroy();
                        Thread.sleep(800);
                        /*
                         * 只有**确实杀过进程**才去扫残留。
                         * 如果 p 为 null（本来就没进程在跑，例如首次初始化的重启请求），
                         * 这里扫 /proc 会把 supervise() 刚拉起来的 dsh 一起杀掉 ——
                         * 表现就是无限重启。
                         * 启动前的清理已经在 startDsh() 里做过，这里是多余的。
                         */
                        int n = Env.killStaleDsh(Env.base(this));
                        Log.i(TAG, "清理残留进程: " + n + " 个");
                    }
                }
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                Log.e(TAG, "watchRestart error", t);
            }
        }
    }

    private void startDsh() {
        try {
            File base = Env.base(this);
            if (!Env.isInstalled(base)) {
                sNeedsProvision = true;
                setState("容器未安装完成");
                updateNotification("容器尚未安装完成，请打开 App 完成初始化");
                Thread.sleep(10000);
                return;
            }
            sNeedsProvision = false;
            /*
             * 先挑一个**没被占用**的端口。3080 很容易撞车，
             * 而端口被占时 dsh 会直接起不来、监督循环无限重启 ——
             * 用户看到的现象和"卡死"完全一样。
             */
            int port = Env.pickFreePort(Env.DSH_PORT, Env.DSH_PORT + 10);
            Env.setPort(port);
            if (port != Env.DSH_PORT) {
                appendReport("  · 默认端口 " + Env.DSH_PORT + " 被占用，改用 " + port + "\n");
                Log.w(TAG, "端口 " + Env.DSH_PORT + " 被占用，改用 " + port);
            } else {
                appendReport("  · 使用端口 127.0.0.1:" + port + "\n");
            }
            setState("正在启动 dsh web…");
            updateNotification("正在启动 DSH…");

            // 先准备好共享存储挂载点与测试文件夹（工作区）
            String ws = Env.ensureStorageMounts(base);
            Log.i(TAG, "工作区: " + Env.WORKSPACE + " · 测试文件夹: " + ws);

            // 手机控制：启动回环控制桥（token 目录随后只读绑定进容器），
            // 并把 phone CLI 与 AGENTS.md 装进容器/工作区
            PhoneBridge.start(this);
            String tokenDir = PhoneBridge.tokenDir(this);
            Env.installPhoneTools(this, base);
            Log.i(TAG, "手机控制桥 token 目录: " + tokenDir
                    + " · 白名单 " + PhoneBridge.allowed(this).size() + " 个应用");

            String libDir = Env.prepareProot(this, base);
            String apiKey = getApiKey(this);

            /*
             * 装随 APK 自带的 DSH 插件（phone 工具集）。
             *
             * 放在起 dsh 之前：插件是 bundle，要靠 profile 启动时加载 ——
             * 起完再装的话本次会话看不到那些工具，用户会以为没生效。
             * 幂等（已装过就跳过一次文件读），所以只在首次/升级后跑一次。
             * 失败也不阻断启动：phone CLI 那条路还在，功能不会全丢。
             */
            try {
                String pluginLog = Env.installPhonePlugin(this, base, tokenDir, libDir);
                Log.i(TAG, pluginLog);
                appendReport("  " + pluginLog + "\n");
                // 插件市场的前置条件：容器里得有 pnpm（dsh plugin add 内部要用）
                String pnpmLog = Env.ensurePnpm(base, tokenDir, libDir);
                Log.i(TAG, pnpmLog);
                appendReport("  " + pnpmLog + "\n");
            } catch (Throwable t) {
                Log.w(TAG, "装自带插件异常", t);
            }

            String[] cmd = Env.dshWebCommand(base, apiKey, tokenDir);

            // 起新的之前先把可能残留的旧容器进程清掉（否则 3080 端口会被占住）
            int stale = Env.killStaleDsh(base);
            if (stale > 0) Log.i(TAG, "启动前清理残留进程 " + stale + " 个");

            sUrl = null;
            Process p = Env.buildProcess(base, cmd, libDir).start();
            sProcess = p;
            Log.i(TAG, "dsh 进程已启动, apiKey=" + (apiKey.isEmpty() ? "未设置" : "已设置"));
            updateNotification(apiKey.isEmpty()
                    ? "运行中（未设置 API Key）"
                    : "运行中（已设置 API Key）");

            // 持续读取输出：解析带 token 的 URL，同时**保留最近若干行**用于诊断
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int n = 0;
                while ((line = br.readLine()) != null) {
                    n++;
                    synchronized (sRecent) {
                        sRecent.addLast(line);
                        while (sRecent.size() > RECENT_MAX) sRecent.removeFirst();
                    }
                    // 头几行 + 任何像报错的行都写进报告
                    if (n <= 30 || looksLikeError(line)) {
                        appendReport("    dsh| " + line + "\n");
                    }
                    if (sUrl == null) {
                        /*
                         * 放宽 URL 匹配：不再死认 127.0.0.1。
                         * dsh 若打印 localhost / 0.0.0.0 之类的地址，
                         * 原来的正则匹配不到 —— 表现就是"永远等不到 URL"。
                         * 这里统一归一化成 127.0.0.1 再交给 WebView。
                         */
                        Matcher m = Pattern.compile(
                                "https?://(?:127\\.0\\.0\\.1|localhost|0\\.0\\.0\\.0|\\[::1\\]|\\[::\\]):(\\d+)(/[^\\s)]*)?")
                                .matcher(line);
                        if (m.find()) {
                            String path = m.group(2) == null ? "" : m.group(2);
                            sUrl = "http://127.0.0.1:" + m.group(1) + path;
                            setState("运行中 · 127.0.0.1:" + m.group(1));
                            appendReport("  dsh 就绪: " + sUrl + "\n");
                            Log.i(TAG, "就绪: " + sUrl);
                            /*
                             * 容器活了才去拉常驻服务。
                             *
                             * 顺序很重要：服务（比如本地代理）要在容器**已经可用**之后
                             * 才起得来；而且它得跑在 dsh web 之前或同时，
                             * 否则"客户端先连、代理还没起来"又会回到用户抱怨的那个绕法。
                             */
                            Services.startEnabled(DshService.this);
                            Services.startWatchdog(DshService.this);
                            /*
                             * 内核版本检测：**不占启动路径**，另起一个后台线程。
                             * 它要联网查 npm，快则几百毫秒、慢则几秒；
                             * 卡在这儿没有意义 —— 界面早就该出来了。
                             */
                            new Thread(() -> {
                                try {
                                    String cur = KernelUpgrade.installedVersion(DshService.this);
                                    String up = KernelUpgrade.availableUpdate(DshService.this);
                                    if (up != null) {
                                        appendReport("  ⬆ 内核有新版本: " + up
                                                + "（当前 " + cur + "）—— 设置 →「内核升级（本机）」可一键升级\n");
                                        KernelUpgrade.notifyUpdate(DshService.this, up);
                                    } else if (cur != null) {
                                        appendReport("  内核已是最新（" + cur + "）\n");
                                    }
                                } catch (Throwable t) {
                                    Log.w(TAG, "检查内核版本失败", t);
                                }
                            }, "dsh-version-check").start();
                        }
                    }
                }
            }
            int code = p.waitFor();
            Log.w(TAG, "dsh 进程退出, code=" + code);
            appendReport("  ❌ dsh 进程退出 code=" + code + "\n" + getRecentOutput());
            setState("进程已退出 (" + code + ")，将自动重启");
            updateNotification("DSH 已退出，正在重启…");
        } catch (Throwable t) {
            Log.e(TAG, "startDsh failed", t);
            setState("启动失败: " + t.getClass().getSimpleName() + " " + t.getMessage());
            updateNotification("DSH 启动失败，详见 App 内日志");
        }
    }

    private void setState(String s) { sState = s; }

    // ── 通知 ──────────────────────────────────────────────────
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CH_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CH_ID, "DSH 运行状态", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("保持 DSH 容器在后台运行");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    /**
     * 通知右侧的大图 = 当前配色的桌面图标。
     *
     * <p>用 {@link android.graphics.drawable.Icon} 而不是 BitmapFactory：
     * v26+ 的桌面图标是**自适应图标 XML**，BitmapFactory 解不出来（返回 null），
     * 用 Icon 才能正确渲染。
     */
    private android.graphics.drawable.Icon largeIcon() {
        try {
            int res = IconSwitcher.iconRes(this);
            if (res == 0) return null;
            return android.graphics.drawable.Icon.createWithResource(this, res);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification(String text) {        PendingIntent pi = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("DeepSeek Harness")
                .setContentText(text)
                /*
                 * 通知图标跟随用户在「桌面图标」里选的那套配色：
                 *   setSmallIcon 用小图标（系统会按强调色着色）
                 *   setColor     决定强调色 —— 小图标和应用名都会被它染色
                 *   setLargeIcon 右侧那张大图，直接用该配色的桌面图标
                 */
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setColor(IconSwitcher.accentColor(this))
                /*
                 * 大图 = 当前配色的桌面图标。
                 * 光靠 setColor 只能给小图标和标题染色，用户看不到"图"本身变了；
                 * 加上 largeIcon 之后，通知右侧那张方图就是桌面图标那张 ——
                 * 这才叫"通知栏里的图片也跟着换"。
                 */
                .setLargeIcon(largeIcon())
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
        } catch (Throwable t) {
            Log.w(TAG, "updateNotification failed", t);
        }
    }
}
