package com.dshmobile.probe;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 容器里那份 DSH 内核的版本检测与一键升级。
 *
 * <h3>为什么要做这个</h3>
 * App 装容器时执行的是 {@code npm install -g @deepseek-ai/dsh}（不锁版本）。
 * 于是"装完那一版"就**永远停在那一版**了 —— 上游发新版，用户完全不知道，
 * 只能卸载重装。这里补上"检测到旧版本 → 提示一键升级"。
 *
 * <h3>版本从哪读</h3>
 * 直接读 rootfs 里的 package.json（{@code <base>/rootfs/opt/node-…/lib/node_modules/
 * @deepseek-ai/dsh/package.json}）—— **不用起容器**，几毫秒就有结果。
 * 起一次 proot 要几百毫秒到几秒，检测这种事不该那么贵。
 *
 * <h3>最新版从哪来</h3>
 * 查 npm registry 的 dist-tags：
 * {@code <registry>/-/package/@deepseek-ai%2Fdsh/dist-tags}
 * 这个端点只回三个标签（latest / next / alpha），比拉整包文档小得多。
 *
 * <p>registry **按地区选**（见 {@link Region}）：大陆用 npmmirror，
 * 海外用 npmjs.org。写死官方源会让大陆用户"检测新版本"这一步就卡住 ——
 * 而它恰恰是升级流程的第一步。
 */
public final class KernelUpgrade {

    private static final String TAG = "DSH_UPGRADE";
    private static final String PKG = "@deepseek-ai/dsh";
    private static final String TAGS_PATH = "/-/package/@deepseek-ai%2Fdsh/dist-tags";

    /** dist-tags 地址（按地区选源）。 */
    private static String tagsUrl(Context c) {
        return Region.npmRegistries(c)[0] + TAGS_PATH;
    }

    private static final String KEY_CACHE_TAGS = "npm_tags_json";
    private static final String KEY_CACHE_AT = "npm_tags_at";
    private static final long CACHE_MS = 6 * 60 * 60 * 1000L;   // 6 小时

    private KernelUpgrade() { }

    // ── 已安装版本 ──────────────────────────────────────────

    /** 容器里 dsh 的安装路径（宿主机视角，直接读文件即可）。 */
    private static File installedPkgJson(Context c) {
        return new File(Env.base(c), "rootfs/opt/" + Env.NODE_DIR
                + "/lib/node_modules/@deepseek-ai/dsh/package.json");
    }

    /** 当前装的版本；读不到返回 null。 */
    public static String installedVersion(Context c) {
        try {
            File f = installedPkgJson(c);
            if (!f.exists()) return null;
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = br.readLine()) != null) {
                    int i = line.indexOf("\"version\"");
                    if (i < 0) continue;
                    int a = line.indexOf('"', line.indexOf(':', i));
                    int b = line.indexOf('"', a + 1);
                    if (a > 0 && b > a) return line.substring(a + 1, b).trim();
                }
            } finally {
                br.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "读已安装版本失败", t);
        }
        return null;
    }

    // ── 最新版本 ────────────────────────────────────────────

    /** npm 的三个标签：latest / next / alpha。 */
    public static final class Tags {
        public String latest;
        public String next;
        public String alpha;

        /** "最新" = next 与 latest 里更新的那个（next 通常更新，但它是 pre-release）。 */
        public String newest() {
            if (next == null) return latest;
            if (latest == null) return next;
            return compare(next, latest) > 0 ? next : latest;
        }
    }

    /** 查 dist-tags；带 6 小时缓存。失败返回 null。 */
    public static Tags fetchTags(Context c, boolean force) {
        SharedPreferences sp = c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE);
        long at = sp.getLong(KEY_CACHE_AT, 0L);
        if (!force && System.currentTimeMillis() - at < CACHE_MS) {
            Tags t = parse(sp.getString(KEY_CACHE_TAGS, null));
            if (t != null) return t;
        }
        /*
         * 主源按地区选，失败再试另一个源。
         *
         * 为什么要试第二个：镜像偶发不同步时，主源会直接回 404/超时，
         * 而"查不到新版本"在界面上和"已经是最新版"长得一模一样 ——
         * 用户会以为没有更新可用。换一个源的成本只有一次 HTTP 请求。
         */
        String[] regs = Region.npmRegistries(c);
        for (int i = 0; i < regs.length; i++) {
            try {
                HttpURLConnection conn =
                        (HttpURLConnection) new URL(regs[i] + TAGS_PATH).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("accept", "application/json");
                int code = conn.getResponseCode();
                if (code != 200) {
                    Log.w(TAG, "registry 返回 " + code + "（源 " + regs[i] + "）");
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                try {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                } finally {
                    br.close();
                }
                String body = sb.toString();
                sp.edit().putString(KEY_CACHE_TAGS, body)
                  .putLong(KEY_CACHE_AT, System.currentTimeMillis()).apply();
                return parse(body);
            } catch (Throwable t) {
                Log.w(TAG, "查 npm 版本失败（源 " + regs[i] + "）", t);
            }
        }
        // 两个源都不通：退回旧缓存（有就用，没有就是"查不到"）
        return parse(sp.getString(KEY_CACHE_TAGS, null));
    }

    private static Tags parse(String json) {
        if (json == null) return null;
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            Tags t = new Tags();
            t.latest = o.optString("latest", null);
            t.next = o.optString("next", null);
            t.alpha = o.optString("alpha", null);
            return (t.latest == null && t.next == null) ? null : t;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 有没有比现在更新的版本；返回目标版本号，没有则 null（永远以 next/latest 里更新的为准）。 */
    public static String availableUpdate(Context c) {
        String cur = installedVersion(c);
        Tags t = fetchTags(c, false);
        if (t == null) return null;
        String newest = t.newest();
        if (newest == null) return null;
        if (cur == null) return newest;
        return compare(newest, cur) > 0 ? newest : null;
    }

    /**
     * 比版本号大小。
     *
     * <p>规则够用就行：先按 {@code .} 分段比数字，某一段相同再比后一段。
     * **带预发布后缀的（如 {@code 0.2.0-rc.2}）按"更小"处理** ——
     * 这是 semver 的规矩，否则 {@code 0.2.0-rc.2} 会被判成大于 {@code 0.2.0}。
     * 两个都带后缀时，按后缀里的序号比。
     */
    static int compare(String a, String b) {
        if (a == null || b == null) return 0;
        String[] pa = a.split("-", 2), pb = b.split("-", 2);
        String[] na = pa[0].split("\\."), nb = pb[0].split("\\.");
        for (int i = 0; i < Math.max(na.length, nb.length); i++) {
            int x = i < na.length ? num(na[i]) : 0;
            int y = i < nb.length ? num(nb[i]) : 0;
            if (x != y) return x > y ? 1 : -1;
        }
        boolean ra = pa.length > 1, rb = pb.length > 1;
        if (ra != rb) return ra ? -1 : 1;          // 带后缀的算小
        if (!ra) return 0;
        String[] sa = pa[1].split("\\."), sb = pb[1].split("\\.");
        for (int i = 0; i < Math.max(sa.length, sb.length); i++) {
            String x = i < sa.length ? sa[i] : "";
            String y = i < sb.length ? sb[i] : "";
            int nx = num(x), ny = num(y);
            if (nx != ny) return nx > ny ? 1 : -1;  // 数字段比数字
            int cmp = x.compareTo(y);               // 字母段比字母（rc > alpha）
            if (cmp != 0) return cmp > 0 ? 1 : -1;
        }
        return 0;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return -1; }
    }

    // ── 升级 ────────────────────────────────────────────────

    public interface Progress {
        /** 一行输出（可能来自 npm，也可能是我们自己打的状态）。 */
        void onLine(String line);
    }

    /**
     * 一键升级到指定版本。
     *
     * <p>做的事就一条：容器里 {@code npm install -g @deepseek-ai/dsh@<ver>}。
     * 装完**必须验证** —— 拿 {@code dsh --version} 读回来对不对，
     * 不能装了就当成功（升级失败留在半路是最难排查的状态）。
     *
     * @return null 表示成功，否则是失败原因
     */
    public static String upgrade(Context c, final String version, final Progress cb) {
        if (version == null || version.isEmpty()) return "没有指定目标版本";
        if (!Priv.canHeal(c)) {
            // npm 走网络，不需要额外权限；这里只是提前说明"没授权也能升级"
            Log.i(TAG, "没有一次性授权，但内核升级不需要它");
        }
        say(cb, "开始升级到 " + version + "（容器里执行 npm install -g，可能要几分钟）");
        String npm = "/opt/" + Env.NODE_DIR + "/bin/npm";
        /*
         * 显式带上 registry：容器里 npm 的默认源是 registry.npmjs.org，
         * 大陆用户在这一步会慢到几分钟甚至超时。按地区选源（见 Region）。
         */
        String registry = Region.npmRegistries(c)[0];
        say(cb, "使用 npm 源：" + registry);
        String script = "npm_config_registry=" + registry + " "
                + npm + " install -g " + PKG + "@" + version
                + " --no-audit --no-fund 2>&1; echo \"NPM_EXIT=$?\"";
        String out = Env.execInContainer(c, script, 20 * 60 * 1000L, line -> say(cb, line));
        if (out == null) return "升级超时（20 分钟）或被中断";
        if (!out.contains("NPM_EXIT=0")) {
            String tail = out.length() > 600 ? out.substring(out.length() - 600) : out;
            return "npm 安装失败：\n" + tail;
        }
        say(cb, "安装完成，正在核对版本…");
        String now = installedVersion(c);
        say(cb, "现在装的是：" + now);
        if (now == null || !now.equals(version)) {
            return "装完了但版本不对（期望 " + version + "，实际 " + now + "）";
        }
        return null;
    }

    private static void say(Progress cb, String line) {
        if (cb != null) {
            try { cb.onLine(line); } catch (Throwable ignore) { }
        }
    }

    // ── 启动时提示 ──────────────────────────────────────────

    private static final String CHANNEL = "dsh_kernel";
    private static final int NOTI_ID = 0x4458;

    /** 同一个版本 12 小时内只提示一次。 */
    private static final long NOTIFY_THROTTLE_MS = 12 * 60 * 60 * 1000L;

    /**
     * 发现新版本时发一条「一键升级」通知。
     *
     * <p>为什么要通知而不是只在设置页里放个入口：用户根本不会主动去看那个页 ——
     * "检测到旧版本"这件事的价值就在于**主动告诉用户**。
     */
    public static void notifyUpdate(Context c, String version) {
        try {
            SharedPreferences sp = c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE);
            String last = sp.getString("upgrade_notified_ver", "");
            long at = sp.getLong("upgrade_notified_at", 0L);
            if (version.equals(last)
                    && System.currentTimeMillis() - at < NOTIFY_THROTTLE_MS) return;
            sp.edit().putString("upgrade_notified_ver", version)
              .putLong("upgrade_notified_at", System.currentTimeMillis()).apply();

            android.app.NotificationManager nm =
                    (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                    && nm.getNotificationChannel(CHANNEL) == null) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                        CHANNEL, "内核升级", android.app.NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("DSH 内核有新版本时提示");
                nm.createNotificationChannel(ch);
            }
            android.content.Intent open = new android.content.Intent(c, UpgradeActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                            | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    c, NOTI_ID, open,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE);
            String cur = installedVersion(c);
            String text = "你现在是 " + cur + "，可以一键升级（会话和插件都不受影响）";
            android.app.Notification.Builder b =
                    (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                            ? new android.app.Notification.Builder(c, CHANNEL)
                            : new android.app.Notification.Builder(c);
            b.setContentTitle("DSH 内核有新版本 " + version)
             .setContentText(text)
             .setStyle(new android.app.Notification.BigTextStyle().bigText(text))
             .setSmallIcon(android.R.drawable.stat_notify_sync)
             .setContentIntent(pi)
             .setAutoCancel(true)
             .setOnlyAlertOnce(true)
             .addAction(new android.app.Notification.Action.Builder(null, "一键升级", pi).build());
            nm.notify(NOTI_ID, b.build());
        } catch (Throwable t) {
            Log.w(TAG, "发升级提示失败", t);
        }
    }

    /** 升级成功后把提示撤掉。 */
    public static void clearNotify(Context c) {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTI_ID);
        } catch (Throwable ignore) { }
    }

    // ── 异步升级（给界面和控制桥共用）────────────────────────

    private static final StringBuilder sProgress = new StringBuilder();
    private static volatile boolean sRunning;
    private static volatile String sResult;
    private static volatile String sTarget;

    /**
     * 后台跑升级，立刻返回。
     *
     * <p>为什么必须异步：升级要 2~8 分钟，而控制桥的 HTTP 请求 25 秒就超时
     * （{@code REQUEST_TIMEOUT_MS}）。同步跑的结果就是"桥说超时、其实还在装"，
     * 那比不报还糟。所以这里只管踢一脚，进度靠 {@link #progressText()} 轮询。
     */
    public static synchronized String runAsync(Context c, String version) {
        if (sRunning) return "已经在升级中（目标 " + sTarget + "）";
        final String v = (version == null || version.isEmpty()) ? newestVersion(c) : version;
        if (v == null) return "ERROR 不知道该升到哪个版本（先 op=check）";
        sRunning = true;
        sResult = null;
        sTarget = v;
        synchronized (sProgress) { sProgress.setLength(0); }
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            String err;
            try {
                err = upgrade(app, v, line -> {
                    synchronized (sProgress) {
                        sProgress.append(line).append('\n');
                        if (sProgress.length() > 20000) {
                            sProgress.delete(0, sProgress.length() - 20000);
                        }
                    }
                });
            } catch (Throwable t) {
                err = "异常: " + t;
            }
            sResult = err;
            sRunning = false;
            if (err == null) {
                clearNotify(app);
                DshService.requestRestart();
            }
        }, "dsh-upgrade-async").start();
        return "OK 已开始升级到 " + v + "\n用 op=status 看进度（可能要几分钟）\n";
    }

    private static String newestVersion(Context c) {
        Tags t = fetchTags(c, false);
        if (t == null) return null;
        return t.newest();
    }

    /** 给界面 / 控制桥看的状态文本。 */
    public static String progressText() {
        StringBuilder sb = new StringBuilder();
        sb.append("状态: ").append(sRunning ? "升级中（目标 " + sTarget + "）" : "空闲").append('\n');
        if (sResult != null) sb.append("上次结果: ").append(sResult).append('\n');
        synchronized (sProgress) {
            if (sProgress.length() > 0) {
                String s = sProgress.toString();
                if (s.length() > 1500) s = "…（省略前面）\n" + s.substring(s.length() - 1500);
                sb.append("---- 输出 ----\n").append(s);
            }
        }
        return sb.toString();
    }
}
