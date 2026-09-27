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

    private static final String TAG = "DSH_SERVICE";
    private static final String CH_ID = "dsh_runtime";
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

    /**
     * 容器还没装好（首次初始化没完成）。
     *
     * 原来的 startDsh() 遇到这种情况只每 10 秒静默重试一次，界面上完全看不出来，
     * 用户只能看到"一直没反应"。现在把它暴露出去，Activity 回到前台时
     * 可以据此自动补跑初始化。
     */
    private static volatile boolean sNeedsProvision = false;

    public static boolean getNeedsProvision() { return sNeedsProvision; }

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
        createChannel();
        startForeground(NOTIF_ID, buildNotification("正在启动容器…"));
        new Thread(this::supervise, "dsh-supervisor").start();
        new Thread(this::watchRestart, "dsh-restart-watcher").start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        keepRunning = false;
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

            // 持续读取输出：解析带 token 的 URL
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (sUrl == null) {
                        Matcher m = Pattern.compile("http://127\\.0\\.0\\.1:\\d+(?:/\\?[^\\s)]+)?")
                                .matcher(line);
                        if (m.find()) {
                            sUrl = m.group();
                            setState("运行中 · 127.0.0.1:" + Env.DSH_PORT);
                            Log.i(TAG, "就绪: " + sUrl);
                        }
                    }
                }
            }
            int code = p.waitFor();
            Log.w(TAG, "dsh 进程退出, code=" + code);
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

    @SuppressWarnings("deprecation")
    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("DeepSeek Harness")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
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
