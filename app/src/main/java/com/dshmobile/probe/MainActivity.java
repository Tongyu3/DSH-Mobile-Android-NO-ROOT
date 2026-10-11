package com.dshmobile.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.system.Os;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/**
 * DSH Mobile — Phase 0 探针（第二阶段含 proot 验证）
 *
 * 逐项验证"App 内跑 proot + Debian + Node + DSH"这条路的每一块基石：
 *
 *   TEST 1  执行私有目录里的二进制        → W^X 豁免是否有效（targetSdk 28）
 *   TEST 2  执行私有目录里的 shell 脚本   → DSH 大量依赖 shell
 *   TEST 3  ptrace 可用性                → proot 完全建立在 ptrace 上
 *   TEST 4  proot 二进制本身能否运行     → 安卓 bionic 二进制能否在我们 App 里启动
 *   TEST 5  proot 完整机制：假 root + -0 → 路径翻译 / uid 伪装 / 绑定挂载
 *
 * 安全声明：本 App 只读写自己的沙箱目录
 *   /data/user/0/com.dshmobile.probe/
 * 不触碰系统文件、不触碰任何其他应用的数据。测试用的"假 root"只是我们
 * 自己目录下的一个文件夹，里面的 sh/id 是从 /system/bin 复制来的副本。
 *
 * 结果同时显示在屏幕上（便于截图）并写入 logcat，tag = DSH_PROBE。
 */
public class MainActivity extends Activity {

    private static final String TAG = "DSH_PROBE";
    private TextView output;
    private TextView statusBar;
    private TextView settingsButton;
    /** 初始化失败时才出现的「重试」；长按 = 清空容器重新初始化。 */
    private TextView retryButton;
    /** 顶部状态栏整行；DSH 就绪后会整条隐藏，避免常年遮挡视野。 */
    private LinearLayout topBar;
    private WebView webView;
    /** 开屏动画（工业简约风）。见 {@link TdshSplashView} 与 {@link #dismissSplash()}。 */
    private TdshSplashView splash;
    /** 开屏是从什么时候开始显示的 —— 用来保证"至少露一下脸"，不做无谓闪烁。 */
    private long splashShownAt = 0L;
    /**
     * 开屏最短显示时长。
     *
     * <p>必须装得下**整套分镜**，否则动画会被拦腰切断 —— 用户就看不到"点→线→面"
     * 与收尾那一缩。节奏是：生长 0.55(点) + 0.45(线) + 0.65(面) = 1.65s，
     * 加收尾 0.76s ≈ 2.4s。原来只有 0.9s，**点还没看清就被切走了**
     * （用户反馈"开始的点没有显示，直接就到空心线条了"）。
     */
    /*
     * 开屏最短展示时长 = 生长 1.65s + 收尾倒放 1.70s ≈ 3.35s。
     *
     * 必须装得下**整套分镜**（含收尾那段倒放：直角→矩形→空心线→点），
     * 否则动画会被拦腰切断，用户就看不到"返回的过程"。
     */
    private static final long SPLASH_MIN_MS = 3400L;
    /**
     * 开屏的**硬上限**：超过这个时间无论启动到哪一步都强制收起。
     *
     * <p>为什么要有：这块动画盖着启动日志与「重试」按钮。
     * 万一就绪回调因为任何原因没来（插件卡死、容器挂住、WebView 起不来），
     * 用户至少还能看到日志、点重试 —— 而不是盯着一个永远在转的开屏。
     */
    private static final long SPLASH_MAX_MS = 45000L;

    /** 开屏底色（深色主题）—— 与 {@link TdshSplashView} 里 THEME_DARK 的 bg 保持一致。 */
    private static final int SPLASH_BG_DARK = 0xFF0B0E10;
    /** 开屏底色（浅色主题）—— 与 THEME_LIGHT 的 bg 一致。 */
    private static final int SPLASH_BG_LIGHT = 0xFFEDEEEA;

    /** 当前系统是否是浅色模式（决定开屏用哪套配色）。 */
    private boolean systemIsLight() {
        return (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_NO;
    }

    /**
     * 读"当前该用浅色吗"：**只跟随手机系统**。
     *
     * <h3>为什么不再读 DSH 内定主题（改回来的原因）</h3>
     * 之前做过一版"DSH 内定主题优先"：从页面读 {@code html[data-ds-theme-source]}
     * 缓存下来，再用它覆盖系统 uiMode。那样虽然能让 App 外壳跟着 DSH 走，代价是
     * **外壳与手机系统的深色开关脱钩** —— 用户在系统里切深色，外壳不动，
     * 观感上就是"没跟随系统"。
     *
     * <p>用户明确要求：**保证 DSH 内的配色跟随手机系统**。所以这里退回单一口径 ——
     * 只看系统的 {@code UI_MODE_NIGHT_YES/NO}（含"跟随日出日落"这类自动模式）。
     * DSH 网页自身的配色由它自己的设置决定，与本 App 外壳互不干涉。
     */
    private boolean shouldUseLight() {
        return systemIsLight();
    }

    /**
     * 开屏期间的窗口外观 —— 目标是消掉用户看到的"顶部色差"。
     *
     * <h3>为什么不再靠"给状态栏染色"</h3>
     * 上一版用的是 {@code setStatusBarColor(开屏底色)}。用户装上后**仍然报顶部有色差**，
     * 说明那条路不可靠：染色可能被 ROM 覆盖，或状态栏本身还带一层半透明遮罩。
     *
     * 现在改成**结构上消除**：
     * <ul>
     *   <li>状态栏 / 导航栏设为**全透明**；</li>
     *   <li>{@code FLAG_LAYOUT_NO_LIMITS} 让窗口内容延伸到系统栏区域。</li>
     * </ul>
     * 开屏 View 自己会铺满并画底色，于是系统栏那一条下面就是开屏的底色，
     * 不再存在"两个色块相接"的可能。
     *
     * <p>收起开屏时由 {@link #restoreWindowChrome()} 去掉这些标志并交还系统。
     */
    private void applyWindowChrome() {
        try {
            /*
         * 开屏配色：**跟随手机系统深色开关**（见 shouldUseLight 的说明）。
         * 系统切深色/浅色时，onConfigurationChanged 会让开屏立刻换配色。
         */
            boolean lightMode = shouldUseLight();

            int bg = lightMode ? SPLASH_BG_LIGHT : SPLASH_BG_DARK;
            android.view.Window win = getWindow();
            android.view.View decor = win.getDecorView();

            win.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            win.setStatusBarColor(android.graphics.Color.TRANSPARENT);
            win.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.view.WindowManager.LayoutParams lp = win.getAttributes();
                lp.layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                win.setAttributes(lp);
            }
            // 状态栏图标的明暗跟着开屏底色走（深底→浅色图标，浅底→深色图标）
            if (lightMode) {
                decor.setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            } else {
                decor.setSystemUiVisibility(0);
            }
            android.util.Log.i("DSH_SPLASH", "窗口外观: " + (lightMode ? "浅色" : "深色")
                    + " 开屏底色=" + Integer.toHexString(bg) + " 状态栏透明+内容延伸");
        } catch (Throwable e) {
            android.util.Log.w("DSH_SPLASH", "设置窗口外观失败", e);
        }
    }

    /** 开屏收起后，把系统栏交还给系统默认表现。 */
    private void restoreWindowChrome() {
        try {
            android.view.Window win = getWindow();
            win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            win.setStatusBarColor(android.graphics.Color.TRANSPARENT);
            win.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            android.util.Log.i("DSH_SPLASH", "窗口外观: 已交还系统");
        } catch (Throwable ignore) { }
    }
    /** dsh web 打印出来的带 token 的 URL（解析到之后 WebView 直接加载它）。 */
    private volatile String dshUrl;
    /** proot 的库搜索目录与临时目录（prepareLibs 里设置）。 */
    private String libPath;
    private String tmpPath;
    /** 沙箱内的报告文件（logcat 在 OriginOS 上不可靠）。 */
    private File reportFile;
    /** proot 的 loader 路径（必须通过 PROOT_LOADER 指定，见 prepareLibs 注释）。 */
    private String loaderPath;
    private String loader32Path;

    // ── 初始化（首次运行）的健壮性相关 ─────────────────────────

    /**
     * 防重入：同一个进程里只允许跑一个初始化线程。
     *
     * 之前没有这个保护，用户在中途切后台再回来（或旋转屏幕导致 Activity 重建）
     * 会再起一个 runAllTests，两个线程同时解压 rootfs / 装 npm —— 互相踩踏。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean sProvisioning =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 当前步骤名（给状态栏的耗时提示用）。 */
    private volatile String sCurrentStep = "准备中";
    /** 最近一次真正有输出的时刻，给"卡死判定"用。 */
    private volatile long sLastOutputAt = System.currentTimeMillis();
    /** 最近一次失败的人话原因（设置对话框里会展示）。 */
    private volatile String sLastFailure = null;
    /** 系统 WebView 的 Chrome 版本（用于诊断"老 WebView 跑不了"）。 */
    private volatile String webViewVersion = "?";

    /*
     * 对照实验用的"模拟老 WebView"删除器，**正式构建必须留空**。
     *
     * 留空时 onPageStarted 只注入 webview-shim.js（兼容垫片）；
     * 调试时把它填成删除 AbortSignal.any / structuredClone 之类的语句，
     * 就能在最新版 WebView 上复现老机器的问题（见 0.1.9 的平板排查）。
     */
    private static final String SIMULATE_OLD_WEBVIEW = "";

    /**
     * 开发用的"假装 WebView 太旧"开关，**正式构建必须为 false**。
     *
     * <p>为 true 时 {@link #webViewTooOld()} 恒为真 —— 这样在最新版 WebView 上
     * 也能把「WebView 太旧」面板走一遍（配合调试桥
     * {@code DshAndroid.pluginBootFailed("test")} 触发那条分支），
     * 不用真去找一台 Chrome 6x 的老手机。
     */
    private static final boolean FORCE_WEBVIEW_TOO_OLD = false;

    /** 短命令的默认超时。 */
    private static final long EXEC_TIMEOUT_MS = 120_000L;
    private static final long EXEC_IDLE_MS = 120_000L;
    /** 流式命令保留的最后若干行（失败时展示给用户）。 */
    private static final int EXEC_TAIL_MAX = 40;

    /*
     * 启动耗时打点。
     *
     * 为什么要在 App 里打点，而不是在外面掐表：这台机器上"进程到底是不是
     * 新起的"很难从外面判断（前台服务 + 厂商保活会让测量对象飘），
     * 从外面量出来的数字自相矛盾过。类加载时记 t0，每过一个阶段打一行，
     * 这样"慢在哪一段"是一手的、不依赖任何外部假设。
     *
     * 读法：adb logcat -d | grep START_TIMING
     */
    private static final long T0 = System.currentTimeMillis();

    /**
     * 报告文件的静态引用。
     *
     * mark() 是静态的（要在类加载后就可用），而 reportFile 是实例字段，
     * 所以另存一份静态引用。**打点必须落到文件里** ——
     * OriginOS 会把 logcat 缓冲清掉（实测整个 buffer 只剩 116 行、我们自己的
     * 一行都不剩），只写 logcat 的测量结果完全不可信。
     */
    private static volatile File sReportFile;

    private static void mark(String what) {
        String line = "TIMING +" + (System.currentTimeMillis() - T0) + "ms  " + what;
        Log.i(TAG, "START_TIMING " + line);
        File f = sReportFile;
        if (f != null) {
            try {
                java.io.FileOutputStream os = new java.io.FileOutputStream(f, true);
                try {
                    os.write(("  ⏱ " + line + "\n").getBytes("UTF-8"));
                } finally {
                    os.close();
                }
            } catch (Throwable ignore) { }
        }
    }
    /*
     * 留空即正常使用（曾临时指向不可达地址以验证失败诊断链路）。
     */
    private static final String TEMP_BROKEN_REGISTRY = null;

    private final java.util.ArrayDeque<String> sExecTail = new java.util.ArrayDeque<>();
    /** 供失败提示使用：把 tail 拼成文本。 */
    private String execTailText() {
        synchronized (sExecTail) {
            if (sExecTail.isEmpty()) return "    (没有任何输出)";
            StringBuilder sb = new StringBuilder();
            for (String l : sExecTail) sb.append("    | ").append(l).append('\n');
            return sb.toString();
        }
    }
    /** 命令输出里像报错的行（用于实时提示）。 */
    private static boolean looksLikeErrorLine(String line) {
        String s = line.toLowerCase();
        return s.contains("npm err") || s.contains("error") || s.contains("eacces")
            || s.contains("enoent") || s.contains("enospc") || s.contains("eaddrinuse")
            || s.contains("cannot") || s.contains("exception") || s.contains("failed")
            || s.contains("killed") || s.contains("out of memory") || s.contains("segmentation");
    }
    /** 容器内 npm install：官方自己都说可能 5-15 分钟，给足但必须有上限。 */
    private static final long NPM_TIMEOUT_MS = 25 * 60_000L;
    private static final long NPM_IDLE_MS = 5 * 60_000L;
    /** 下载：单次最长 15 分钟；连续 60 秒没有任何字节就判定停滞。 */
    private static final long DOWNLOAD_TIMEOUT_MS = 15 * 60_000L;
    private static final long DOWNLOAD_STALL_MS = 60_000L;
    /** 长时间命令的心跳间隔。 */
    private static final long HEARTBEAT_MS = 15_000L;

    /** 耗时秒数 → "3分12秒" / "42秒"。 */
    private static String fmtDuration(long ms) {
        long s = ms / 1000;
        return s < 60 ? (s + "秒") : (s / 60 + "分" + (s % 60) + "秒");
    }

    /**
     * 只往报告与界面追加一行，**不碰 StringBuilder**。
     *
     * 心跳是看门狗线程发的，而 StringBuilder 不是线程安全的，
     * 所以在别的线程里只能走这个（progress() 会写 sb，仅供初始化主线程用）。
     */
    private void liveProgress(String line) {
        appendReport(line);
        final String l = line;
        runOnUiThread(() -> output.append(l));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 长任务（下载 rootfs / npm install）期间保持屏幕常亮。
        // 这是应用级窗口标志，不修改设备的任何系统设置。
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        /*
         * 开屏期间的窗口外观 —— 目的是**消掉用户看到的"顶部色差"**。
         *
         * 那一条色差来自**系统状态栏**：主题是 Theme.DeviceDefault.NoActionBar，
         * 状态栏默认是深灰，而开屏底色是 #0B0E10，两者贴着就看出明显一条。
         *
         * 处理办法（保守且安全）：
         *   · 状态栏与导航栏都染成开屏底色 → 与开屏无缝；
         *   · 顶部那条栏自身也改成开屏底色（它原本是 #1F2430，也是个色差源）；
         *   · 不隐藏系统栏、不改成边到边布局 —— 本项目其它页面的布局依赖
         *     "窗口不覆盖系统栏"，动那个会牵动一堆页面，收益不值这个风险。
         * 开屏收起后由 restoreWindowChrome() 把状态栏交还给系统默认表现。
         */
        applyWindowChrome();

        // ── UI：顶部状态条（含设置按钮）+（日志 / DSH WebView）叠层 ──
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        /*
         * 顶部栏底色 = 开屏底色（原来是 #1F2430，会和开屏形成一条色差）。
         *
         * 为什么不直接隐藏这条栏：它就叠在开屏动画上方，
         * 而「重试」按钮在这条栏上 —— 失败时必须能看到、能点到。
         * 所以做法是"让它和开屏同色（无缝），但保留可点"。
         * 就绪后 onServerReady 照旧把它整体淡出。
         */
        /*
         * ⚠️ 这行原来是硬编码 {@code SPLASH_BG_DARK} —— **那就是用户报的"闪光弹"根因**：
         * 浅色主题下开屏是 0xFFEDEEEA（近白），而这条压在上面的栏是 0xFF0B0E10（近黑），
         * 两者交界处就是一条刺眼的黑边；切主题时更会整块闪一下。
         * 必须与开屏同色，所以这里要**按当前主题取**（DSH 内定优先）。
         */
        bar.setBackgroundColor(shouldUseLight() ? SPLASH_BG_LIGHT : SPLASH_BG_DARK);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);

        statusBar = new TextView(this);
        statusBar.setTextSize(12f);
        statusBar.setPadding(32, 26, 16, 26);
        statusBar.setTextColor(Color.WHITE);
        statusBar.setText("Tdsh · 准备中…");
        /*
         * ★ 消除"秒数进两位数时画面抽一下"（用户报的抖动）。
         *
         * 根因：这条状态栏每秒被 elapsedTicker 重设一次文本，而它**没有任何宽度约束**，
         *   且父容器是横向 LinearLayout（"设置"/"重试"按钮紧挨其后）。
         *   于是 "9秒" → "10秒" 时文本变宽 → 父容器重新测量 → 后面的按钮整体右移，
         *   整条栏重排一次 —— 肉眼就是"抽一下"。之后 100 秒、以及 60 秒切到"1分0秒"
         *   还会再抽几次（fmtDuration 的输出长度是跳变的）。
         *
         * 修法：**先把宽度按"最长可能文本"一次性算定**，之后每秒换字都不再改变布局。
         *   · setSingleLine + setEllipsize：保证永不换行、溢出时省略，不会因文本变化撑高；
         *   · 布局完成后按实测文本宽度设一次固定宽度（只测一次，不进每帧路径）。
         * 这样重排从"每秒一次"降为"零次"。
         */
        statusBar.setSingleLine(true);
        statusBar.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusBar.setEms(17);   // 兜底宽度，先占住位置，避免首帧就被按钮挤窄
        bar.addView(statusBar, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // 布局完成后，按最长文本量一次宽度并锁死（只做一次）
        statusBar.post(new Runnable() {
            @Override public void run() {
                try {
                    if (statusBar == null) return;
                    // 最长形态：两位数秒 → 三位数秒 → 分+秒，再加最长 step（18 字 + 省略号）
                    String[] worst = {
                        "Tdsh · 初始化中 9999秒 · 一二三四五六七八九十一二三四五六七八…",
                    };
                    float wmax = 0f;
                    for (String s0 : worst) {
                        wmax = Math.max(wmax, statusBar.getPaint().measureText(s0));
                    }
                    int need = (int) Math.ceil(wmax) + statusBar.getPaddingLeft()
                            + statusBar.getPaddingRight();
                    // 只加宽不缩窄，且给按钮留出至少 1/4 屏宽，避免把按钮挤出屏幕
                    int cap = (int) (getResources().getDisplayMetrics().widthPixels * 0.62f);
                    statusBar.setWidth(Math.min(need, Math.max(statusBar.getWidth(), cap)));
                    appendReport("  [布局] 状态栏宽度已锁定为 " + statusBar.getWidth() + "px（消除秒数抖动）\n");
                } catch (Throwable t) {
                    android.util.Log.w("DSH_UI", "锁定状态栏宽度失败（不影响功能）", t);
                }
            }
        });

        settingsButton = new TextView(this);
        settingsButton.setText("设置");
        settingsButton.setTextSize(13f);
        settingsButton.setTextColor(Color.parseColor("#8AB4F8"));
        settingsButton.setPadding(24, 26, 44, 26);
        settingsButton.setOnClickListener(v -> showSettingsDialog());
        bar.addView(settingsButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /*
         * 「重试」：只在初始化失败时出现。
         *
         * 为什么必须有它：原来初始化一旦失败或卡死，界面就永久停在最后一句话上，
         * 用户唯一的出路是卸载重装（等于把 150MB 再下一遍）。
         * 短按 = 重跑（各步骤幂等，已完成的不重做）；长按 = 清空容器重来。
         */
        retryButton = new TextView(this);
        retryButton.setText("重试");
        retryButton.setTextSize(13f);
        retryButton.setTextColor(Color.parseColor("#FFB86B"));
        retryButton.setPadding(24, 26, 24, 26);
        retryButton.setVisibility(View.GONE);
        retryButton.setOnClickListener(v -> {
            // 用户是"出问题了才点重试"的，必须完整自检一次，不能拿上次的标记跳过
            sForceFullTest = true;
            startProvisioning("手动重试");
        });
        retryButton.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("清空容器重新初始化？")
                    .setMessage("会删除已下载的 Linux 容器与 Node（约 150MB），"
                            + "然后重新下载。你的文件、API Key、白名单都不受影响。")
                    .setPositiveButton("清空并重来", (d, w) -> wipeContainerAndRetry())
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        });
        bar.addView(retryButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        topBar = bar;

        FrameLayout stack = new FrameLayout(this);
        root.addView(stack, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        ScrollView scroll = new ScrollView(this);
        output = new TextView(this);
        output.setTextSize(13f);
        output.setPadding(40, 40, 40, 40);
        output.setTextIsSelectable(true);
        scroll.addView(output);
        stack.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        /*
         * 开屏动画（工业简约风，见 {@link TdshSplashView}）。
         *
         * 放在 stack 的**最后一个孩子** = 盖在日志与 WebView 之上，
         * 但**不改动**原来那两个视图的可见性逻辑 —— 它只是一层"封皮"，
         * 就绪或失败时被收起（见 {@link #dismissSplash}）。
         *
         * ⚠️ 为什么必须保证"一定能被收起"：它盖住的正是启动日志与「重试」按钮。
         * 万一启动失败而它没被收起，用户会卡在一个永远转的动画上，
         * 比看到一屏报错还糟（这正是本 App 之前踩过的"插件把界面卡住"那类问题）。
         * 所以：失败路径、超时、以及用户点击，三条路都能收掉它。
         */
        splash = new TdshSplashView(this);
        splash.setOnClickListener(v -> dismissSplash());   // 点一下就能看日志
        /*
         * 把主题告诉开屏（它自己只能读到系统 uiMode，而 DSH 的内定主题可能与系统相反）。
         * 这里的 lightMode 已经是"DSH 内定优先、系统次之"算出来的值。
         */
        splash.setLightTheme(shouldUseLight());
        /*
         * 开屏上那个"设置按钮"已按用户要求去掉，所以这里不再绑定它的动作。
         * 设置入口仍在右上角「设置」，以及 DSH 设置页里的「API Key（本机）」。
         */
        stack.addView(splash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        splashShownAt = SystemClock.uptimeMillis();

        /*
         * 开屏期间**把顶部那条状态栏藏掉**（用户要求"去掉上面那一栏"）。
         *
         * 注意这里只改 alpha 不改 GONE：因为「重试」按钮就在这条栏上，
         * 万一启动失败，dismissSplash() 会把它恢复出来（见 restoreTopBarForSplash）。
         * 直接 GONE 会让失败时连重试都点不到 —— 那才是真的把人卡住。
         */
        if (topBar != null) {
            topBar.animate().cancel();
            topBar.setVisibility(View.VISIBLE);
            topBar.animate().alpha(0f).setDuration(260).start();
        }

        // DSH 的 Web UI 最终显示在这里。
        // proot 不隔离网络，所以容器里的 127.0.0.1:3080 就是手机的回环地址。
        // 仅调试构建开启 WebView 远程调试（供 PC 侧用 Chrome DevTools 协议检查/试验样式）
        if (isDebuggable()) {
            WebView.setWebContentsDebuggingEnabled(true);        }
        webView = new WebView(this);
        // 窄屏适配补丁：DSH 的设置弹窗原本是「左分类 188px + 右内容 124px」，
        // 在手机上会把中文挤成一字一行。补丁把它改成整屏的「列表 → 详情」两态。
        // 通过注入实现，不修改容器里 DSH 的任何文件。
        final String mobilePatch = readAssetText("dsh-mobile.js");
        final String webCompatShim = readAssetText("webview-shim.js");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                /*
                 * 兼容垫片必须在页面脚本之前执行。
                 *
                 * 原本想用 shouldInterceptRequest 改写主文档（最稳），实测不可行：
                 * DSH 的本地服务用 **HttpOnly Cookie** 鉴权
                 * （fetch('/', {credentials:'omit'}) → 401，带凭证 → 200），
                 * Java 侧复制一份请求拿不到那个 Cookie，主文档直接 401，
                 * 于是永远注入不进去。
                 *
                 * 退而用 onPageStarted：它在主框架导航提交时触发，
                 * 而 DSH 的 HTML 里内联脚本后面还有 /plugins 的**外链脚本**，
                 * 注入只要能赶在那些脚本之前就行。
                 * 是否真的够早由 SIMULATE_OLD_WEBVIEW 的对照实验来验证
                 * （先删掉 Iterator 再看插件是否报错）。
                 */
                /*
                 * ⚠️ 曾经这里的注入语句在改动中被写成只注入 SIMULATE_OLD_WEBVIEW，
                 * 把 webCompatShim 漏掉了 —— 垫片实际上从来没进过页面。
                 * 老 WebView 的 `Iterator is not defined` / `AbortSignal.any is not a function`
                 * 之所以还在报，就是因为它根本没被注入。
                 *
                 * 两个变量必须拼在一起注入：SIMULATE_OLD_WEBVIEW 只是对照实验用的删除器，
                 * 正式构建里它是空字符串，此时等价于"只注入垫片"。
                 */
                StringBuilder inject = new StringBuilder();
                if (SIMULATE_OLD_WEBVIEW != null) inject.append(SIMULATE_OLD_WEBVIEW);
                if (webCompatShim != null && !webCompatShim.isEmpty()) {
                    inject.append('\n').append(webCompatShim);
                }
                if (inject.length() > 0) {
                    view.evaluateJavascript(inject.toString(), null);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                mark("WebView 页面加载完成");
                lastPageOkAt = System.currentTimeMillis();
                /*
                 * 只往**本机 DSH 页面**注入适配补丁。
                 * 万一有外部网页进了这个 WebView（历史上真发生过：页面里的链接
                 * 直接把整个界面顶掉了），补丁会去改它的排版，属于无谓的干扰。
                 */
                if (mobilePatch != null && isDshAppUrl(url)) {
                    view.evaluateJavascript(mobilePatch, null);
                }
                reportWebViewVersion(view);
            }

            /**
             * 页面里的链接被点开时的拦路虎 —— 用户反馈"在 DSH 里点开网页就退不出去了"。
             *
             * <h3>原来为什么会困住</h3>
             * 没有实现这个方法时，DSH 聊天里的链接会在**同一个 WebView 里**把整个
             * 界面顶掉：DSH 的界面没了、注入的"分层返回"也没了（那是 DSH 页面里的
             * 函数），剩下的就是一个普通网页。用户没有地址栏、没有后退按钮，
             * 系统返回键也被当成"退出 App"，于是他只能从最近任务里划掉。
             *
             * <h3>现在的做法</h3>
             * 主框架只要想离开本机 DSH（127.0.0.1），就交给**系统浏览器**打开，
             * 本 WebView 原地不动 —— DSH 界面还在，返回键也就是正常返回。
             * 页面内部用的伪协议（blob: / data: / about:）不拦，否则会打断
             * DSH 自己的预览功能。
             */
            @Override
            public boolean shouldOverrideUrlLoading(WebView view,
                    android.webkit.WebResourceRequest request) {
                if (request == null) return false;
                return handleExternalNavigation(request.getUrl(), request.isForMainFrame());
            }

            /** API < 24 的老签名（本 App minSdk 24，留着是为了别在低版本上漏拦）。 */
            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleExternalNavigation(url == null ? null : android.net.Uri.parse(url), true);
            }

            /**
             * 老 WebView 的 JS **语法降级** —— 见 {@link JsCompat}。
             *
             * <p>背景：Chrome 88 的设备上，DSH 主 bundle 里的 class static block
             * （Chrome 94+）会让整个 bundle 解析失败，界面永远空白；垫片救不了语法。
             * 这里在**传输过程中**把那段语法换成等价的静态字段初始化 ——
             * 不修改容器里 DSH 的任何文件（和 patch.js 一样，只改"经过我们手里的字节"）。
             *
             * <p>只在需要时启用（{@link #jsCompatRewrite}），新机器上这个方法
             * 一行都不做，路径与以前完全一致。
             */
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, android.webkit.WebResourceRequest request) {
                return maybeServeRewrittenJs(request);
            }

            /**
             * 渲染进程被系统回收 —— 「用久了白屏」的第一大成因。
             *
             * <p>必须返回 {@code true}：返回 {@code false}（或不实现）时，
             * 系统的默认处理是**把整个 App 进程杀掉**。用户看到的就是"用着用着
             * App 自己没了"；而如果侥幸没被杀，这个 WebView 实例也已经废了 ——
             * 页面永远是白的，且重开 App 走的只是 onResume（Activity 没重建、
             * WebView 没换），于是"白屏、进不去"。
             *
             * <p>处理方式：把整个 Activity 重建一次（recreate），
             * 新 Activity 会 new 一个全新的 WebView，随后按正常启动流程加载页面。
             * 死的那个 WebView 不再碰（碰它没有任何意义）。
             */
            @Override
            public boolean onRenderProcessGone(WebView view,
                    android.webkit.RenderProcessGoneDetail detail) {
                boolean crashed = didCrashCompat(detail);
                Log.e(TAG, "WebView 渲染进程没了（didCrash=" + crashed + "）"
                        + " —— 该 WebView 实例已失效，重建界面");
                appendReport("  · 界面渲染进程被系统回收（"
                        + (crashed ? "崩溃" : "为回收内存") + "），正在重建界面…\n");

                long now = System.currentTimeMillis();
                /*
                 * 只有"上一次页面加载成功并稳定存活超过 1 分钟"才清零计数。
                 * 否则"加载完就崩、崩完重建"会被误判成"恢复了"，变成死循环。
                 */
                boolean wasHealthy = lastPageOkAt > 0 && now - lastPageOkAt > 60_000L;
                if (sRendererRecreateFirstAt == 0L
                        || now - sRendererRecreateFirstAt > 5 * 60 * 1000L
                        || wasHealthy) {
                    sRendererRecreateFirstAt = now;
                    sRendererRecreateCount = 0;
                }
                sRendererRecreateCount++;

                if (sRendererRecreateCount > 3) {
                    /*
                     * 5 分钟内被回收 3 次以上：说明整机内存真的不够，
                     * 再重建还是会死。这时不要继续打转 —— 退回日志界面，
                     * 给用户一个能点的「重试」。
                     */
                    Log.e(TAG, "渲染进程反复被回收（" + sRendererRecreateCount + " 次），停止重建");
                    onProvisionFailed(new IllegalStateException(
                            "界面渲染进程反复被系统回收（通常是手机内存紧张）。\n"
                          + "   · 关掉一些后台应用后点右上角「重试」\n"
                          + "   · 当前界面进程已失效，需要重载一次"));
                    return true;
                }

                uiHandler.postDelayed(() -> {
                    try {
                        android.widget.Toast.makeText(MainActivity.this,
                                "界面被系统回收，正在恢复…",
                                android.widget.Toast.LENGTH_SHORT).show();
                        recreate();
                    } catch (Throwable t) {
                        Log.e(TAG, "recreate 失败", t);
                    }
                }, 300);
                return true;
            }

            /**
             * 主框架加载失败（容器还没起来 / 端口没通 / 断网）。
             *
             * <p>这里**不弹错**：容器重启期间失败是预期内的，弹窗只会吓人。
             * 只记日志，并让心跳（地址变了会自动重连）去接管恢复。
             * 唯一的例外是"地址没变却加载失败"——那多半是容器活着但一时没响应，
             * 给它一次自动重试（autoReload 自带 30 秒节流）。
             */
            @Override
            public void onReceivedError(WebView view,
                    android.webkit.WebResourceRequest request,
                    android.webkit.WebResourceError error) {
                if (request == null || !request.isForMainFrame()) return;
                Log.w(TAG, "主框架加载失败: " + request.getUrl()
                        + " · code=" + (error == null ? -1 : error.getErrorCode())
                        + " " + (error == null ? "" : error.getDescription()));
                if (DshService.getUrl() != null) {
                    autoReload("页面加载失败（容器还在，重试一次）");
                }
            }
        });
        /*
         * 附件选择：必须实现 onShowFileChooser，否则页面里的 <input type="file">
         * 点了**完全没反应** —— DSH 的「+ 添加附件」正是用它，这就是"无法添加附件"的原因。
         * WebView 默认不给文件选择器任何实现，必须由宿主 Activity 自己拉起系统选择器。
         *
         * 做法：用 SAF（Intent.ACTION_GET_CONTENT）拉起系统文件管理器让用户挑，
         * 选完把 content:// URI 交回 WebView（Chromium 会通过 ContentResolver 读内容，
         * SAF 授权本身就带读权限）。params.createIntent() 已经带上了 accept 类型
         * 与 multiple 标志，直接用它最稳妥。
         */
        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            /*
             * 页面里的 console 输出 —— 尤其是**脚本解析错误**。
             *
             * 为什么要收：0.2.8 那次"另一台老手机白屏"的排查，全靠我把 served 的
             * bundle 抓下来手工分析才定位到（class static block）。而 Chrome 在
             * 脚本解析失败时**本来就会往 console 打一行 SyntaxError** ——
             * 只要把它收进报告，用户点一下「复制运行日志」就能直接看到原因，
             * 不用再来回猜。
             *
             * 只留前 40 条、每条截 300 字：既能拿到现场，也不会让报告爆炸。
             */
            private int consoleCount = 0;

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage msg) {
                try {
                    if (msg == null || consoleCount >= 40) return false;
                    String text = msg.message();
                    if (text == null || text.isEmpty()) return false;
                    // 页面自己打的普通 log 太多，只收告警/错误（解析错误会以 error 级别出现）
                    android.webkit.ConsoleMessage.MessageLevel level = msg.messageLevel();
                    if (level != android.webkit.ConsoleMessage.MessageLevel.ERROR
                            && level != android.webkit.ConsoleMessage.MessageLevel.WARNING) {
                        return false;
                    }
                    if (text.length() > 300) text = text.substring(0, 300) + "…";
                    consoleCount++;
                    appendReport("  [页面console] " + text
                            + "  @" + msg.sourceId() + ":" + msg.lineNumber() + "\n");
                } catch (Throwable ignore) { }
                return false;   // 返回 false：不拦截，页面该看到的照样看到
            }

            @Override
            public boolean onShowFileChooser(WebView view,
                    android.webkit.ValueCallback<android.net.Uri[]> callback,
                    FileChooserParams params) {
                // 上一次还没回结果就又点了一次：先把旧的置空，避免回调泄漏
                if (pendingFileChooser != null) {
                    pendingFileChooser.onReceiveValue(null);
                    pendingFileChooser = null;
                }
                pendingFileChooser = callback;
                try {
                    Intent intent = Attach.buildIntent(params);
                    startActivityForResult(intent, REQ_FILE);
                    return true;
                } catch (Throwable t) {
                    Log.w(TAG, "拉起文件选择器失败，退化为任意文件选择", t);
                }
                try {
                    Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                    fallback.addCategory(Intent.CATEGORY_OPENABLE);
                    fallback.setType("*/*");
                    fallback.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(
                            Intent.createChooser(fallback, "选择要添加的文件"), REQ_FILE);
                    return true;
                } catch (Throwable t2) {
                    Log.e(TAG, "没有可用的文件选择器", t2);
                    pendingFileChooser = null;
                    android.widget.Toast.makeText(MainActivity.this,
                            "这台设备上没有可用的文件选择器", android.widget.Toast.LENGTH_LONG).show();
                    return false;
                }
            }

            /**
             * 网页要麦克风（本地语音识别）。
             *
             * <p>WebView 里 {@code getUserMedia({audio:true})} 的授权**不走系统权限框**，
             * 而是先问宿主：这个回调不实现，请求会被**静默拒绝** ——
             * 网页那边只看到一个 NotFoundError，用户什么都看不到，
             * 看起来就是"这 App 不支持语音"。
             *
             * <p>完整的链路要两步，缺一不可：
             * <ol>
             *   <li>清单里声明 RECORD_AUDIO（否则下面第二步连框都弹不出来）；</li>
             *   <li>这里先要系统权限，拿到之后再 grant 给网页。</li>
             * </ol>
             *
             * <p>只对<b>本机回环</b>上的页面放行：这个 WebView 平时只加载
             * 127.0.0.1 上的 DSH，但万一里面打开了外部链接，
             * 也不该把麦克风交给一个陌生站点。
             */
            @Override
            public void onPermissionRequest(final android.webkit.PermissionRequest request) {
                if (request == null) return;
                Log.i(TAG, "onPermissionRequest origin=" + request.getOrigin()
                        + " resources=" + java.util.Arrays.toString(request.getResources())
                        + " RECORD_AUDIO=" + (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                            == android.content.pm.PackageManager.PERMISSION_GRANTED));
                if (!isLocalOrigin(request.getOrigin())) {
                    Log.w(TAG, "拒绝非本机来源的权限请求: " + request.getOrigin());
                    try { request.deny(); } catch (Throwable ignore) { }
                    return;
                }
                // 网页可能要 audio+video，我们只给 audio（相机没声明，也不打算给）
                String audio = null;
                for (String r : request.getResources()) {
                    if (android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) audio = r;
                }
                if (audio == null) {
                    try { request.deny(); } catch (Throwable ignore) { }
                    return;
                }
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    try {
                        request.grant(new String[]{audio});
                    } catch (Throwable t) {
                        Log.w(TAG, "grant 麦克风失败", t);
                        try { request.deny(); } catch (Throwable ignore) { }
                    }
                    return;
                }
                /*
                 * 还没授权：把这次网页请求**挂起**，先弹系统权限框，
                 * 等 onRequestPermissionsResult 回来再答复网页。
                 * 不能在这里直接 deny —— 那样用户即使马上同意，这一次也已经失败了。
                 */
                if (pendingMicRequest != null) {
                    try { pendingMicRequest.deny(); } catch (Throwable ignore) { }
                }
                pendingMicRequest = request;
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            }
        });
        // JS 桥：让注入到 DSH 设置页里的「API Key（本机）」入口能打开本 App 的原生设置。
        // 只暴露这一个方法，且页面只可能是本机回环上的 DSH。
        webView.addJavascriptInterface(new AppBridge(), "DshAndroid");
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);

        /*
         * 老 WebView 的**语法**降级开关（见 JsCompat 的类注释）。
         *
         * 必须在页面加载之前定好 —— shouldInterceptRequest 要靠它决定改不改写；
         * 这里用 WebView.getDefaultUserAgent() 读版本，比等页面里的 UA 早得多。
         */
        int uaMajor = detectWebViewMajor();
        webViewMajorFromUa = uaMajor;
        jsCompatRewrite = FORCE_ASSET_REWRITE
                || (uaMajor > 0 && uaMajor < MIN_WEBVIEW_SYNTAX_MAJOR);
        Log.i(TAG, "WebView UA Chrome/" + uaMajor + " 语法降级=" + jsCompatRewrite);
        if (jsCompatRewrite) {
            mark("启用 JS 语法降级（WebView Chrome/" + uaMajor + " < " + MIN_WEBVIEW_SYNTAX_MAJOR + "）");
        }
        // （setWebViewClient 已在上面设置，用于注入窄屏适配补丁）
        webView.setVisibility(View.GONE);
        stack.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        /*
         * 【系统栏：为什么这里**不做**任何内边距处理（2026-10-05 实测后撤掉）】
         *
         * 一度以为"平板/横屏下内容会被系统栏压住"，于是加过两版代码：
         *   ① setOnApplyWindowInsetsListener + requestApplyInsets() —— 真机上监听器
         *      **一次都没被回调**（同一时刻 onConfigurationChanged 是正常触发的，
         *      所以不是日志/生命周期问题）；
         *   ② 改读系统尺寸资源 status_bar_height / navigation_bar_height ——
         *      getIdentifier 取不到（返回 0），而且没有明显收益。
         *
         * 撤回的真正理由是**实测事实**：`dumpsys window` 里我们的窗口是
         * `Frames: frame=[0,0][1080,2376]`（与屏幕同尺寸），而截图（_archive\release-033.png）
         * 显示状态栏与手势条都正常显示、界面内容完整。也就是说
         * **系统在 targetSdk 28 下已经把内容区排好了，不需要我们再加内边距**。
         *
         * 按项目规矩："没验证的推断不加进代码"——加一层算不准的 padding
         * 反而可能把本来正常的内容挤出去（那才是"修出新 bug"）。
         * 下面这条结论要留着，免得下次再走一遍：
         *   · 本项目**刻意不隐藏系统栏**（没有 SYSTEM_UI_FLAG_FULLSCREEN 一类调用）——
         *     用户要能随时用系统的返回/主页/任务键；
         *   · 真要处理刘海/挖孔，得用 displayCutout 且**必须先在目标机上验证**
         *     （本机 cutout 在顶部中间 Rect(510,0-570,90)），不要凭形状猜。
         */

        StringBuilder head = new StringBuilder();
        head.append("Tdsh — 容器自检\n");
        head.append("──────────────────────────\n");
        head.append("Android : ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        head.append("设备    : ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        head.append("ABI     : ").append(Build.SUPPORTED_ABIS[0]).append("\n");
        head.append("dataDir : ").append(getFilesDir().getAbsolutePath()).append("\n\n");
        head.append("检查中…\n");
        output.setText(head.toString());

        Log.i(TAG, "=== app start: API " + Build.VERSION.SDK_INT
                + " abi=" + Build.SUPPORTED_ABIS[0] + " ===");

        requestStoragePermissions();
        maybePromptApiKey();
        // 桌面图标的组件启用状态和设置对一次账（用户可能清过数据、或升级时被顶回默认）
        IconSwitcher.reconcile(this);
        askRegionIfNeeded("首次启动");
        mark("onCreate 结束（界面已可见）");
    }


    /**
     * 首次启动时问一次"你在哪个地区"，用来**选对安装源**。
     *
     * <h3>为什么必须有这一步</h3>
     * 镜像的快慢是有地区性的：大陆走阿里云/中科大是 1 MB/s 级，海外走它们
     * 常常几十 KB/s 甚至超时；反过来 nodejs.org 在国内也很慢。
     * 以前顺序是**写死的**国内源优先，于是海外用户装环境屡屡失败 ——
     * 也就是用户反馈的"非大陆地区难以安装环境"。
     *
     * <p>刻意不用"自动测速"来自动判断：测速本身要联网，而用户装环境时
     * 网络恰恰可能不通，那就变成卡在一个永远完不成的探测上。
     * 让用户点一下，成本最低也最可控。
     *
     * <p>已经装好容器的老用户也会被问一次 —— 这是**故意**的：开关是这版新加的，
     * 我们无从知道他到底在哪（写死"大陆"正是海外用户装不上的原因）。
     * 问一次只花一秒，选过之后就不再打扰。
     */
    private void askRegionIfNeeded(String trigger) {
        if (Region.get(this) != null) {
            startProvisioning(trigger);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("你在哪个地区？")
                .setMessage("只用来挑更快的安装源（Linux 容器 / Node / npm 的下载地址）。\n\n"
                        + "· 中国大陆 → 阿里云 / 中科大镜像\n"
                        + "· 非中国大陆 → Ubuntu 官方源 / nodejs.org\n\n"
                        + "选错了不要紧：之后可以在「手机权限 → 环境安装源」里改。")
                .setCancelable(false)
                .setPositiveButton("中国大陆", (d, w) -> {
                    Region.set(this, Region.CN);
                    appendReport("  [地区] 中国大陆 → 国内镜像\n");
                    startProvisioning(trigger);
                })
                .setNegativeButton("非中国大陆", (d, w) -> {
                    Region.set(this, Region.GLOBAL);
                    appendReport("  [地区] 非中国大陆 → 官方源\n");
                    startProvisioning(trigger);
                })
                .show();
    }

    /**
     * 首次启动的引导。
     *
     * 关键改动：**容器还没装好时不再弹阻塞式对话框**。
     *
     * 原来无论装没装好都会在 600ms 弹出 API Key 对话框，用户点「保存并重启 DSH」
     * 时容器其实还在下载/安装 —— 于是界面被切成"重启中"，
     * 而 `awaitUrl` 4 分钟超时后没有任何恢复入口，看起来就是"卡死了"。
     * 现在改成分流：
     *   · 容器已就绪 → 照旧弹对话框（这里改 Key 最方便）
     *   · 容器还没装好 → 只在日志区写一行引导，让用户先等安装完成
     */
    private void maybePromptApiKey() {
        String k = DshService.getApiKey(this);
        if (k != null && !k.trim().isEmpty()) return;

        if (isContainerReady()) {
            output.postDelayed(this::showSettingsDialog, 600);
        } else {
            // 首次运行：把引导写进日志区，别用对话框打断初始化
            output.postDelayed(() -> {
                if (isContainerReady()) { showSettingsDialog(); return; }
                output.append("\n提示：还没填 DeepSeek API Key。"
                        + "可以现在点右上角「设置」填入，也可以等容器装好后在 DSH 设置里填。\n");
            }, 800);
        }
    }

    /** 容器是否已经装好（有 rootfs + Node + dsh）。 */
    private boolean isContainerReady() {
        try {
            return Env.isInstalled(Env.base(this));
        } catch (Throwable t) {
            return false;
        }
    }

    // ── 初始化耗时显示 ────────────────────────────────────────

    private final android.os.Handler uiHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile long provisionStartedAt = 0L;
    private final Runnable elapsedTicker = new Runnable() {
        @Override public void run() {
            if (provisionStartedAt == 0L) return;
            long ms = System.currentTimeMillis() - provisionStartedAt;
            String step = sCurrentStep == null ? "" : sCurrentStep;
            if (step.length() > 18) step = step.substring(0, 18) + "…";
            statusBar.setText("Tdsh · 初始化中 " + fmtDuration(ms)
                    + (step.isEmpty() ? "" : " · " + step));
            uiHandler.postDelayed(this, 1000);
        }
    };

    private void startElapsedTicker() {
        provisionStartedAt = System.currentTimeMillis();
        uiHandler.post(elapsedTicker);
    }

    private void stopElapsedTicker() {
        provisionStartedAt = 0L;
        uiHandler.removeCallbacks(elapsedTicker);
    }

    // ── 「用久了白屏进不去」的自愈 ────────────────────────────────
    //
    // 用户反馈：用久了界面白屏、而且**进不去**（重开 App 也不行）。
    // 拆开来是三种成因，各自都要兜住 —— 它们的共同点是"没人管就永远是白屏"：
    //
    //   ① WebView 的渲染进程被系统回收。用久了必然发生（整机内存紧张时
    //      系统优先杀渲染进程）。默认结果是**整个 App 被系统杀掉**；
    //      就算不杀，这个 WebView 实例也已经废了：页面永远是白的，
    //      而重开 App 走的是 onResume（Activity 没重建、WebView 没换），
    //      所以用户看到的就是"白屏、进不去"。
    //      → onRenderProcessGone() 里换一个全新的 WebView（recreate）。
    //
    //   ② 容器里的 dsh 进程掉了（被系统清理 / 自己崩）。
    //      前台服务会把它重新拉起来，但**新地址（token 变了）只写进了
    //      DshService**，页面还指着旧地址。以前只有 onResume 会去比对地址，
    //      用户一直停在 App 里就永远等不到那一刻 → 白屏。
    //      → 心跳：每 5 秒比对一次地址，变了立刻重载（不需要切后台再回来）。
    //
    //   ③ 页面自己白屏（前端 SPA 卡死 / 网络断了之后没恢复）。
    //      → 心跳里顺手用 JS 量一下正文长度，连着几次都空就主动重载一次。

    /** 心跳间隔。5 秒：足够快，又不至于让低频设备费电。 */
    private static final long RECONNECT_TICK_MS = 5000;
    /** 两次自动重载之间的最小间隔，防止"重载→还是白→再重载"打转。 */
    private static final long AUTO_RELOAD_MIN_GAP_MS = 30000;
    /** 连续几次探测到空白才判定白屏（约 20 秒）。 */
    private static final int BLANK_TICKS_TO_RELOAD = 4;

    private volatile boolean heartbeatRunning = false;
    private int blankTicks = 0;
    private long lastAutoReloadAt = 0L;
    private boolean containerDownNotified = false;
    /** 最近一次页面加载完成的时刻（用来判断"刚加载就崩"还是"稳定跑了一阵"）。 */
    private volatile long lastPageOkAt = 0L;

    /** 渲染进程被回收后重建界面的次数（静态：要跨 Activity 重建累计，否则会打转）。 */
    private static volatile int sRendererRecreateCount = 0;
    private static volatile long sRendererRecreateFirstAt = 0L;

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!heartbeatRunning) return;
            try {
                heartbeatCheck();
            } catch (Throwable t) {
                Log.w(TAG, "心跳检查异常", t);
            }
            if (heartbeatRunning) uiHandler.postDelayed(this, RECONNECT_TICK_MS);
        }
    };

    private void startHeartbeat() {
        if (heartbeatRunning) return;
        heartbeatRunning = true;
        uiHandler.postDelayed(heartbeat, RECONNECT_TICK_MS);
    }

    private void stopHeartbeat() {
        heartbeatRunning = false;
        uiHandler.removeCallbacks(heartbeat);
    }

    /** 一次心跳：先看容器地址，再看页面内容。 */
    private void heartbeatCheck() {
        if (sProvisioning.get()) return;                 // 初始化/重启流程正在跑，别插手
        String cur = DshService.getUrl();
        if (cur == null) {
            /*
             * 容器不在（dsh 进程已退出，前台服务正在把它拉起来）。
             * 这时页面上什么都连不上 —— 至少要告诉用户"在自动重启"，
             * 否则他只会看到白屏，然后去杀 App / 卸载重装。
             */
            if (!containerDownNotified) {
                containerDownNotified = true;
                Log.i(TAG, "心跳：容器地址为空（dsh 已退出，前台服务正在重启它）");
                appendReport("  · 容器已停止，正在自动重启（不用管它）\n");
                android.widget.Toast.makeText(this, "容器已停止，正在自动重启…",
                        android.widget.Toast.LENGTH_LONG).show();
            }
            return;
        }
        containerDownNotified = false;

        if (!cur.equals(dshUrl)) {
            // 地址变了 = 容器被重启过（token 也换了）。这就是"白屏连不上"的主因之一。
            Log.i(TAG, "心跳：容器地址变化，自动重连\n  旧=" + dshUrl + "\n  新=" + cur);
            appendReport("  · 心跳发现容器地址变化，自动重连\n");
            dshUrl = cur;
            blankTicks = 0;
            onServerReady(cur);
            return;
        }
        probeBlank();
    }

    /**
     * 屏幕方向 / 尺寸变化时**不重建 Activity**（配合清单里的 {@code configChanges}）。
     *
     * <h3>为什么必须接管</h3>
     * 原来清单里 MainActivity **没有声明 configChanges**，于是屏幕方向一变，
     * 系统就销毁并重建这个 Activity —— WebView 跟着重载，用户看到的就是
     * **"界面自己重启了"**（调研报告里有 logcat 硬证据：`changed=0x80 CONFIG_ORIENTATION`、
     * 1.2 秒内 land→port 翻了两次 = 重建两次）。这条对**平板**和横竖屏切换尤其重要。
     *
     * <h3>为什么不能只声明 configChanges 就完事</h3>
     * 声明之后系统不再帮忙重建，**布局得自己跟上**：WebView 的宽度虽然会跟着
     * Activity 的窗口变化，但 DSH 前端是靠 {@code window.innerWidth} 决定
     * "手机窄屏 / 平板宽屏"那两套布局的，它需要一次 resize 事件才会重算。
     * 所以我们在这里补一次 resize 通知，并重跑一遍窄屏补丁
     * （补丁的 {@code apply()} 内部就是按宽度分流的，重跑是幂等的）。
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        appendReport("  · 屏幕配置变化（orientation=" + newConfig.orientation
                + "，宽度 " + newConfig.screenWidthDp + "dp）—— 不重建界面，只通知页面重排\n");
        // 等一帧让 WebView 拿到新宽度，再通知页面；重试一次兜住"这一帧还没量好"
        if (webView == null) return;
        webView.postDelayed(() -> reflowPage(), 120);
        webView.postDelayed(() -> reflowPage(), 500);
    }

    /**
     * 让 DSH 页面按当前宽度重排一遍（幂等，不会改任何数据）。
     */
    private void reflowPage() {
        if (webView == null) return;
        try {
            webView.evaluateJavascript(
                    "(function(){try{"
                  + "window.dispatchEvent(new Event('resize'));"
                  + "if(typeof window.__dshMobilePatch==='function')window.__dshMobilePatch();"
                  + "return 1;}catch(e){return 0;}})()", null);
        } catch (Throwable ignore) { }
    }

    /** 量一下页面正文有多长；连续几次都是空的就认为白屏了。 */
    private void probeBlank() {
        if (webView == null || webView.getVisibility() != View.VISIBLE) return;
        webView.evaluateJavascript(
                "(function(){try{var b=document.body;"
              + "if(!b)return 0;"
              + "return ((b.innerText||'').trim().length);}catch(e){return -1;}})()",
                value -> {
                    int len;
                    try {
                        len = (int) Double.parseDouble(String.valueOf(value)
                                .replace("\"", "").trim());
                    } catch (Throwable t) {
                        return;                     // 解析不出来就当没测到，别乱重载
                    }
                    if (len < 0) return;            // JS 里抛异常了（页面正在换），忽略
                    if (len > 20) { blankTicks = 0; return; }
                    blankTicks++;
                    Log.i(TAG, "心跳：页面正文长度 " + len + "（连续第 " + blankTicks + " 次）");
                    if (blankTicks >= BLANK_TICKS_TO_RELOAD) {
                        blankTicks = 0;
                        autoReload("页面连续 " + (BLANK_TICKS_TO_RELOAD * RECONNECT_TICK_MS / 1000)
                                + " 秒没有任何内容");
                    }
                });
    }

    /**
     * 自动重载页面（有节流：30 秒内只做一次）。
     *
     * <p>宁可偶尔多刷一次，也不要让用户对着白屏干等 —— 但也不能毫无节制，
     * 否则真出了持续性的问题会变成"每 5 秒闪一下"，反而更糟。
     */
    private void autoReload(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastAutoReloadAt < AUTO_RELOAD_MIN_GAP_MS) {
            Log.i(TAG, "自动重载被节流（距上次不足 "
                    + (AUTO_RELOAD_MIN_GAP_MS / 1000) + " 秒）：" + reason);
            return;
        }
        lastAutoReloadAt = now;
        String u = DshService.getUrl();
        Log.w(TAG, "自动重载 WebView：" + reason + " · url=" + u);
        appendReport("  · 界面异常（" + reason + "），已自动重载\n");
        if (webView == null) return;
        if (u != null) {
            dshUrl = u;
            webView.loadUrl(u);
        } else {
            webView.reload();
        }
    }

    /**
     * 初始化失败时的统一出口：把"静默卡死"变成"看得见 + 能重试"。
     */
    private void onProvisionFailed(Throwable t) {
        String msg = t == null ? "未知原因"
                : (t.getClass().getSimpleName()
                   + (t.getMessage() == null ? "" : ": " + t.getMessage()));
        sLastFailure = msg;
        appendReport("  ❌ 初始化失败: " + msg + "\n");
        Log.e(TAG, "provision failed", t);
        final boolean dshWasRunning = (dshUrl != null);
        runOnUiThread(() -> {
            statusBar.setText("Tdsh · 初始化失败");
            // 顶部栏可能已经被 onServerReady 收起来了，这里必须重新亮出来，
            // 否则「重试」按钮根本点不到。
            if (topBar != null) {
                topBar.animate().cancel();
                topBar.setAlpha(1f);
                topBar.setVisibility(View.VISIBLE);
            }
            if (retryButton != null) retryButton.setVisibility(View.VISIBLE);

            String detail = "\n❌ 初始化失败：" + msg + "\n"
                    + "   · 常见原因：网络不通 / 容器内 DNS 解析失败 / 存储空间不足\n"
                    + "   · 点右上角「重试」可以再跑一次（已完成的步骤会自动跳过）\n"
                    + "   · 长按「重试」可以清空容器重新初始化\n";
            if (dshWasRunning) {
                // DSH 本来在跑，别把正在看的界面抢走，给个提示就行
                android.widget.Toast.makeText(this, "初始化失败：" + msg,
                        android.widget.Toast.LENGTH_LONG).show();
            } else {
                // 还没进过 DSH：把日志区顶上来，让用户看到到底卡在哪一步
                output.setVisibility(View.VISIBLE);
                webView.setVisibility(View.GONE);
                output.append(detail);
            }
            appendReport(detail);
        });
    }

    /** 一次进程只弹一次（pageReady 会随重载反复上报）。 */
    private static volatile boolean sChangelogShown = false;

    /**
     * 装上新版本之后**第一次真正进到界面**时，把这一版改了什么摊给用户看。
     *
     * <h3>为什么挂在 pageReady 上</h3>
     * 这是"界面确实渲染出来了"的唯一信号（见 tools/patch.js 的 bootWatchdog）。
     * 换版本时恰好也是界面起不来最可能发生的时刻 —— 如果这一版起不来，
     * 用户先看到的是自救面板，而不是"更新内容"弹窗，顺序才对。
     *
     * <h3>为什么记在 prefs 里，还额外用静态标志兜一层</h3>
     * prefs 管的是"跨版本只提示一次"（用户升级 → 看到 → 以后不再弹）；
     * 静态标志管的是"同一次运行里别弹两次"（页面重载、Activity 重建都会
     * 再调一次 pageReady）。
     */
    /** 首次进界面的免责声明与用途警告是否已被确认（一次性，永久记住）。 */
    private boolean disclaimerAccepted() {
        try {
            return getSharedPreferences(DshService.PREFS, MODE_PRIVATE)
                    .getBoolean("disclaimer_accepted_v1", false);
        } catch (Throwable t) {
            return true;      // 读不到就当已确认，别把界面卡在弹窗上
        }
    }

    /**
     * 首次真正进到界面时弹一次**免责声明 + 用途警告**。
     *
     * <p>口径与仓库里的 `免责声明-第三方插件.md` 一致：第三方非官方封装、与官方无关；
     * 只能用于合法用途；几条红线逐条点名；数据都在本机、作者不收集；后果自负。
     *
     * <p>为什么只弹一次：这是**告知**，不是每次都要点同意的东西 —— 反复弹只会被
     * 条件反射点掉。之后随时能在「手机权限」里重新看到免责声明。
     *
     * @param after 用户确认后接着做的事（这里是接着弹更新日志，避免两个弹窗叠在一起）
     */
    private void showDisclaimerOnce(final Runnable after) {
        runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(this)
                        .setTitle("使用前请先读这一段")
                        .setMessage(
                                "Tdsh 是第三方做的非官方封装：把 AI 跑在手机本地容器里，"
                              + "与任何官方产品、公司都没有关系，也不代表它们。\n\n"
                              + "只能用于合法用途。以下这些一律不要用它做：\n"
                              + "· 代刷课、代签到、代考、抢课 —— 学术不端，学校会处分；\n"
                              + "· 开盒、买卖个人信息、爬取个人数据 —— 刑事犯罪；\n"
                              + "· 跑分洗钱、诈骗话术、钓鱼、外挂、赌博引流 —— 刑事犯罪；\n"
                              + "· 任何未经授权操作他人设备、账号的行为。\n\n"
                              + "用它做违法的事，后果由使用者自己承担，与作者无关。\n\n"
                              + "你的 API Key、对话与数据都只在这台手机上；本 App 不收集、"
                              + "不上传任何内容（用途判断这类检查也都在本机完成）。\n\n"
                              + "继续使用即表示你已阅读并同意以上内容。")
                        .setCancelable(false)
                        .setPositiveButton("我已阅读并同意", (d, w) -> {
                            try {
                                getSharedPreferences(DshService.PREFS, MODE_PRIVATE).edit()
                                        .putBoolean("disclaimer_accepted_v1", true).apply();
                                appendReport("  · 用户已确认首次免责声明\n");
                            } catch (Throwable ignore) { }
                            if (after != null) after.run();
                        })
                        .show();
            } catch (Throwable t) {
                Log.w(TAG, "免责声明弹窗失败", t);
                if (after != null) after.run();
            }
        });
    }
    private void maybeShowChangelog() {
        if (sChangelogShown) return;
        /*
         * 首次使用：**先**弹免责声明与用途警告，确认之后再走更新日志。
         *
         * 为什么必须在这之前、且用回调串起来：两个弹窗都挂在 pageReady 上，
         * 同时弹会叠在一起（用户只看得到最上面那个）；而且"同意条款"这件事
         * 应该发生在"看新功能"之前 —— 顺序反了就像是先给你看甜头再补一句免责。
         * 所以这里不 return 到底，而是等用户点掉免责声明后**再调一次自己**。
         */
        if (!disclaimerAccepted()) {
            showDisclaimerOnce(this::maybeShowChangelog);
            return;
        }
        try {
            int now = appVersionCode();
            if (now <= 0) return;
            android.content.SharedPreferences p =
                    getSharedPreferences(DshService.PREFS, MODE_PRIVATE);
            int seen = p.getInt("seen_version_code", 0);
            if (seen >= now) { sChangelogShown = true; return; }

            final List<Changelog.Entry> notes = Changelog.since(seen);
            Changelog.Entry cur = Changelog.current();
            if (notes.isEmpty() || cur == null) { sChangelogShown = true; return; }

            /*
             * 界面正在收尾（用户刚好在这一刻退出、或者系统在重建 Activity）时不要弹 ——
             * 而且**也不能记账**：账记了、弹窗没出来，用户就永远看不到这一版的更新内容了。
             * 这个判断必须在写 prefs 之前。
             */
            if (isFinishing()) return;
            if (Build.VERSION.SDK_INT >= 17 && isDestroyed()) return;

            sChangelogShown = true;
            p.edit().putInt("seen_version_code", now).apply();

            StringBuilder sb = new StringBuilder();
            for (Changelog.Entry e : notes) {
                if (notes.size() > 1) sb.append("v").append(e.version).append('\n');
                for (String item : e.items) sb.append("· ").append(item).append('\n');
                sb.append('\n');
            }
            sb.append(seen <= 0
                    ? "（这条提示只出现一次，以后可在「设置 → 手机权限 → 更新日志」里翻）"
                    : "（从第 " + seen + " 版升上来的，这条提示只出现一次）");

            appendReport("  · 首次进入 v" + cur.version + "（第 " + now + " 版，上次 "
                    + (seen <= 0 ? "未记录" : String.valueOf(seen)) + "），已展示更新内容\n");
            Log.i(TAG, "展示更新日志 v" + cur.version + " seen=" + seen);

            new AlertDialog.Builder(this)
                    .setTitle(seen <= 0 ? ("欢迎使用 · v" + cur.version) : ("已更新到 v" + cur.version))
                    .setMessage(sb.toString())
                    .setPositiveButton("知道了", null)
                    .setNeutralButton("全部更新日志", (d, w) -> {
                        try {
                            startActivity(new Intent(MainActivity.this, ChangelogActivity.class));
                        } catch (Throwable t) {
                            Log.w(TAG, "打开更新日志页失败", t);
                        }
                    })
                    .show();
        } catch (Throwable t) {
            Log.w(TAG, "maybeShowChangelog failed", t);
        }
    }

    /**
     * {@code RenderProcessGoneDetail.didCrash()} 是 API 26 的方法。
     *
     * <p>{@code onRenderProcessGone} 这个回调本身也只在 API 26+ 才会被调用，
     * 所以这里其实不会出问题 —— 但 lint 只看得到"minSdk 24 + API 26 方法"，
     * 会把整个构建标成 Error（见 {@code Proc} 的类注释：我们已经被这类
     * 静默放过的 Error 坑过一次）。显式判断一下，让检查恢复有效。
     */
    private static boolean didCrashCompat(android.webkit.RenderProcessGoneDetail d) {
        if (d == null || Build.VERSION.SDK_INT < 26) return false;
        return d.didCrash();
    }

    /** 让初始化线程自己退出：把最近一次失败原因清掉并复位 UI。 */
    private void onProvisionStarted() {
        sLastFailure = null;
        runOnUiThread(() -> {
            if (retryButton != null) retryButton.setVisibility(View.GONE);
            output.setVisibility(View.VISIBLE);
        });
    }

    /**
     * 手机系统返回键：**分层返回**，而不是一下就把整个界面退掉。
     *
     * <h3>原来是什么样</h3>
     * App 没有接管返回键，于是走系统默认 = 结束 MainActivity，
     * 用户看到的就是"按返回键 App 直接没了"。而手机上正确的行为是
     * 由内到外一层层退：设置详情 → 设置弹窗 → 图片大图 → 右侧栏 → 左侧抽屉。
     *
     * <h3>怎么做的</h3>
     * 先问页面（注入脚本里的 {@code window.__dshHandleBack}）有没有东西可关；
     * 它说没有，才走"再按一次退出"。
     */
    @Override
    public void onBackPressed() {
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        /*
         * 兜底：界面现在停在**不是 DSH 的文档**上（正常路径下不该发生 —— 链接会被
         * shouldOverrideUrlLoading 交给系统浏览器）。真发生了就直接把 DSH 拉回来，
         * 绝不让用户对着一个没有地址栏、没有后退键的页面干瞪眼。
         * isDshDocument 把 blob:/data: 这类顶层文档也算"不是 DSH"（见它的注释）。
         */
        String cur = null;
        try {
            cur = webView.getUrl();
        } catch (Throwable ignore) { }
        if (!isDshAppUrl(cur)) {
            appendReport("  · 返回键：界面停在非 DSH 的文档上（" + cur + "），已拉回 DSH\n");
            Log.w(TAG, "返回键发现界面停在非 DSH 文档，拉回 DSH: " + cur);
            String home = DshService.getUrl() != null ? DshService.getUrl() : dshUrl;
            if (home != null) {
                webView.loadUrl(home);
                android.widget.Toast.makeText(this, "已回到 DSH",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
        }
        webView.evaluateJavascript(
                "(function(){try{return window.__dshHandleBack?window.__dshHandleBack():'false';}"
                        + "catch(e){return 'false';}})()",
                value -> {
                    if (value != null && value.contains("true")) return;   // 页面已经消化掉这次返回
                    confirmExit();
                });
    }

    private long lastBackAt = 0L;

    /** 页面没东西可关时，**再按一次**才真的退出，免得手滑一下就没了。 */
    private void confirmExit() {
        long now = System.currentTimeMillis();
        if (now - lastBackAt < 2000L) {
            super.onBackPressed();
            return;
        }
        lastBackAt = now;
        android.widget.Toast.makeText(this, "再按一次返回退出",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private static final int REQ_STORAGE = 1001;
    private static final int REQ_FILE = 1002;
    private static final int REQ_MIC = 1003;

    /** 等待文件选择结果的 WebView 回调；null 表示当前没有待处理的请求。 */
    private android.webkit.ValueCallback<android.net.Uri[]> pendingFileChooser;

    /** 网页发起的、还没答复的麦克风请求（等系统权限框的结果）。 */
    private android.webkit.PermissionRequest pendingMicRequest;

    /**
     * 是不是本机回环上的页面。
     *
     * <p>WebView 平时只加载 127.0.0.1 上的 DSH，但页面里可以打开外部链接，
     * 所以授权前必须确认来源 —— 麦克风不该交给一个陌生站点。
     * （顺带一提：http://127.0.0.1 在 Chromium 里算**安全上下文**，
     * 这正是 getUserMedia 能在明文回环上可用的原因。）
     */
    private boolean isLocalOrigin(android.net.Uri origin) {
        if (origin == null) return false;
        String host = origin.getHost();
        if (host == null) return false;
        return "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host);
    }

    /**
     * 这个 URL 是不是**本机 DSH 的文档**（用来决定"注不注入补丁"和"用户有没有被顶掉"）。
     *
     * <p>判成"是"的只有两类：
     * <ul>
     *   <li>还没有地址 / {@code about:blank} —— 那是加载中的页面内部状态，
     *       不能当成"用户被困住了"（否则一按返回就去抢加载，反而把启动搞乱）；</li>
     *   <li>回环地址（127.0.0.1 / localhost / ::1）。</li>
     * </ul>
     *
     * <p><b>blob: / data: / file: 一律算"不是"</b>，两条理由：
     * 一是注入的补丁会在陌生文档上乱改排版、甚至装上一个
     * {@code __dshHandleBack}（那正是用户"困在网页里"的老成因）；
     * 二是这类顶层文档同样没有地址栏和后退键，返回键该把用户拉回 DSH。
     * （页面里嵌的 blob 预览是 **iframe**，顶层地址仍是回环，不受影响。）
     */
    private boolean isDshDocument(String url) {
        if (url == null || url.isEmpty() || url.startsWith("about:")) return true;
        if (url.startsWith("blob:") || url.startsWith("data:") || url.startsWith("file:")) return false;
        try {
            return isLocalOrigin(android.net.Uri.parse(url));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 这个 URL 是不是**DSH 界面本身** —— 比 {@link #isDshDocument} 更严一层：
     * 回环还不够，**端口也得是 DSH 那个**。
     *
     * <h3>为什么必须再严一层（真实事故）</h3>
     * 第三方插件会在**自己的端口**上起面板服务（用户那台是 127.0.0.1:18787），
     * 而它把地址打进了 dsh 的输出 —— 当时 URL 识别"看到回环就抓"，
     * 于是整个界面被加载成插件的网页：没有地址栏、没有后退键，
     * 注入的分层返回也不在那个页面里 → 用户只能杀后台。
     *
     * <p>插件面板、本地代理（CLIProxyAPI 之类）都是"回环但不是 DSH"，
     * 现在一律按"不是界面"处理：注入补丁要跳过、返回键要把用户拉回来、
     * 主框架导航要交给系统浏览器（而不是把界面顶掉）。
     *
     * <p>DSH 端口未知时（比如还没解析出地址）退回宽松判断，避免把启动流程搞坏。
     */
    private boolean isDshAppUrl(String url) {
        if (!isDshDocument(url)) return false;
        int want = dshPort();
        if (want <= 0) return true;
        try {
            int got = android.net.Uri.parse(url).getPort();
            return got <= 0 || got == want;
        } catch (Throwable t) {
            return true;
        }
    }

    /** DSH 界面的端口（从当前已知地址里取；取不到返回 -1）。 */
    private int dshPort() {
        String u = DshService.getUrl() != null ? DshService.getUrl() : dshUrl;
        if (u == null) return -1;
        try {
            return android.net.Uri.parse(u).getPort();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 老 WebView 的 JS 资源改写入口。返回 null = 交回 WebView 自己加载（原样）。
     *
     * <h3>为什么要拦、拦什么</h3>
     * 只有 {@code .js} 资源，而且只在本机回环上、只在
     * {@link #jsCompatRewrite} 打开时（WebView &lt; Chrome 94）。
     * 刻意**不拦**主文档（它的鉴权 token 在 URL 上、而且不需要改写）和
     * 大文件（超过 {@link #MAX_REWRITE_BYTES}，例如 10MB 的插件合并包：
     * 那里面已经确认没有 static block，为了内存不值得缓冲）。
     *
     * <h3>为什么可以自己取回来</h3>
     * DSH 的本地服务用 HttpOnly Cookie 鉴权。JS 子资源是在主文档之后才请求的，
     * 那时 Cookie 已经种下；{@code CookieManager.getCookie()} **能读到 HttpOnly 的
     * Cookie**（HttpOnly 只挡页面里的 JS，不挡宿主 App），所以这里带着 Cookie
     * 自己取一份是通的。取回来之后**直接把这份交给 WebView**，避免它再取一次
     * （那才是老机器上白白的双份下载）。
     */
    private android.webkit.WebResourceResponse maybeServeRewrittenJs(
            android.webkit.WebResourceRequest request) {
        if (!jsCompatRewrite || request == null) return null;
        android.net.Uri uri = request.getUrl();
        if (uri == null || !isLocalOrigin(uri)) return null;
        String path = uri.getPath();
        if (path == null || !path.endsWith(".js")) return null;
        String method = request.getMethod();
        if (method != null && !"GET".equalsIgnoreCase(method)) return null;

        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(uri.toString()).openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            // 自己解压：WebResourceResponse 只能给"已经是明文"的内容
            conn.setRequestProperty("Accept-Encoding", "identity");
            String cookie = android.webkit.CookieManager.getInstance().getCookie(uri.toString());
            if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
            int code = conn.getResponseCode();
            if (code != 200) {
                appendReport("  [旧WebView兼容] " + path + " 取回 HTTP " + code + "，本次不改写\n");
                return null;
            }
            byte[] raw = readCapped(conn.getInputStream(), MAX_REWRITE_BYTES);
            if (raw == null) {
                appendReport("  [旧WebView兼容] " + path + " 超过 "
                        + (MAX_REWRITE_BYTES / 1024 / 1024) + "MB，跳过改写\n");
                return null;
            }
            String js = new String(raw, StandardCharsets.UTF_8);
            JsCompat.Result r = JsCompat.rewrite(js);
            if (r.ambiguous) {
                appendReport("  [旧WebView兼容] " + path + " 扫描遇到歧义，"
                        + "为安全起见原样加载（界面可能仍然空白）\n");
                return null;
            }
            byte[] body;
            if (r.count > 0) {
                body = r.code.getBytes(StandardCharsets.UTF_8);
                appendReport("  [旧WebView兼容] " + path + " 已改写 static block "
                        + r.count + " 处（本机 WebView Chrome/" + currentWebViewMajorText()
                        + " 不认识这个语法）\n");
                Log.i(TAG, "JsCompat: " + path + " 改写 " + r.count + " 处");
            } else {
                body = raw;                       // 不需要改写，也用这份，省一次下载
            }
            android.webkit.WebResourceResponse resp = new android.webkit.WebResourceResponse(
                    "application/javascript", "utf-8", new java.io.ByteArrayInputStream(body));
            if (Build.VERSION.SDK_INT >= 21) {
                java.util.Map<String, String> h = new java.util.HashMap<>();
                /*
                 * 允许缓存一天。
                 *
                 * 为什么敢缓存：DSH 的这些资源地址本身带内容指纹
                 * （`index-5SrrfWpU.js` 的文件名、插件包的 `&rev=` 查询串），
                 * 内容一变地址就变 —— 和我们自己写的补丁完全一样"地址即版本"。
                 * 反之如果不给缓存头（最早写的是 no-store），每次进页面都要重新
                 * 经过 Java 取一遍（主 bundle 600KB、插件包更大）——
                 * 在本来就走降级路径的老机器上，这是白白的等待。
                 */
                h.put("Cache-Control", "max-age=86400");
                resp.setResponseHeaders(h);
            }
            return resp;
        } catch (Throwable t) {
            Log.w(TAG, "改写 JS 资源失败，交回 WebView 自己加载: " + uri, t);
            appendReport("  [旧WebView兼容] " + path + " 改写失败（" + t.getClass().getSimpleName()
                    + "），交回系统加载\n");
            return null;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignore) { }
            }
        }
    }

    /** 缓冲上限；超过就返回 null（调用方放弃改写）。 */
    private static final int MAX_REWRITE_BYTES = 3 * 1024 * 1024;

    /** 读到上限为止；超限返回 null。 */
    private byte[] readCapped(java.io.InputStream in, int cap) throws java.io.IOException {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (bos.size() + n > cap) return null;
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            try { in.close(); } catch (Throwable ignore) { }
        }
    }

    /**
     * 读系统 WebView 的 Chrome 主版本（页面还没加载时就能拿到）。
     *
     * <p>用 {@code WebSettings.getUserAgentString()} 而不是
     * {@code WebView.getDefaultUserAgent()} —— 后者在 API 36 的 android.jar 里
     * **已经没有了**（javap 查过），编译期就找不到符号。
     */
    private int detectWebViewMajor() {
        try {
            String ua = null;
            if (webView != null) ua = webView.getSettings().getUserAgentString();
            if (ua == null || ua.isEmpty()) {
                ua = android.webkit.WebSettings.getDefaultUserAgent(this);
            }
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("Chrome/([0-9]+)").matcher(ua == null ? "" : ua);
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (Throwable t) {
            Log.w(TAG, "读 WebView UA 失败", t);
        }
        return -1;
    }

    /** 报告里用的 WebView 版本：优先用页面回填的，回填之前用 onCreate 读到的。 */
    private String currentWebViewMajorText() {
        int v = webViewMajor > 0 ? webViewMajor : webViewMajorFromUa;
        return v > 0 ? String.valueOf(v) : "?";
    }

    /**
     * 拦下"界面要离开本机 DSH"的导航，改用系统浏览器打开。
     *
     * <p>拦两种：
     * <ol>
     *   <li>真正的外部网址（http/https 非回环）；</li>
     *   <li>**回环但不是 DSH 端口**的地址 —— 典型是第三方插件的面板服务
     *       （用户那台是 127.0.0.1:18787）。这类地址进了 WebView 就等于把 DSH 界面
     *       整个顶掉，而且那页面上没有地址栏也没有后退键。交系统浏览器打开，
     *       用户想看插件面板照样能看，但 DSH 界面留在原地。</li>
     * </ol>
     *
     * @return true 表示这次导航已经由我们处理（WebView 不要再加载）
     */
    private boolean handleExternalNavigation(android.net.Uri uri, boolean isMainFrame) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        if (scheme == null) return false;
        scheme = scheme.toLowerCase();
        // 只拦 http/https：blob:/data:/about:/file: 都得留给页面自己用
        if (!"http".equals(scheme) && !"https".equals(scheme)) return false;
        // 回环 + 就是 DSH 那个端口 → 是自家的导航，放行
        if (isLocalOrigin(uri) && isDshAppUrl(uri.toString())) return false;
        /*
         * 子框架（iframe）不拦：页面里嵌的外部内容不是"用户点开的网页"，
         * 把它扔进浏览器反而会把界面顶掉。真正困住用户的是主框架被替换。
         */
        if (!isMainFrame) return false;

        boolean foreignLocal = isLocalOrigin(uri);
        Log.i(TAG, (foreignLocal ? "本机其它服务（非 DSH）" : "外部链接")
                + "交给系统浏览器: " + uri);
        appendReport("  · " + (foreignLocal ? "本机其它服务（不是 DSH）" : "外部链接")
                + "交给系统浏览器打开：" + uri + "\n");
        try {
            Intent view = new Intent(Intent.ACTION_VIEW, uri);
            view.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(view);
        } catch (Throwable t) {
            Log.w(TAG, "没有浏览器能打开这个链接", t);
            android.widget.Toast.makeText(this, "这台设备上没有能打开该链接的浏览器",
                    android.widget.Toast.LENGTH_LONG).show();
        }
        // 无论浏览器开没开成，都**不要**让 WebView 导航过去 ——
        // 界面留在 DSH 上是这个修复的核心
        return true;
    }

    /** 系统权限框的结果：把挂起的网页请求答复掉，否则网页会一直卡在 pending。 */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_MIC) return;
        boolean ok = grantResults != null && grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        android.webkit.PermissionRequest req = pendingMicRequest;
        pendingMicRequest = null;
        if (req == null) return;
        try {
            if (ok) {
                req.grant(new String[]{android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                req.deny();
            }
        } catch (Throwable t) {
            Log.w(TAG, "答复麦克风请求失败", t);
        }
    }

    /**
     * 接住系统文件管理器返回的选择结果，并交回给 WebView 里的 <input type="file">。
     *
     * parseResult 会正确处理单选、多选（ClipData）以及取消（返回 null），
     * 不要自己拼 Uri[]，否则多选会丢文件。
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            android.webkit.ValueCallback<android.net.Uri[]> cb = pendingFileChooser;
            pendingFileChooser = null;
            if (cb == null) return;
            /*
             * 解析 + 兜底 + 拷贝全在 Attach 里。
             *
             * 这里曾经只调用一次 parseResult：对文件管理器够用，但**从「相册」选的图片
             * 会静默丢失**（用户反馈"无法添加图片"）。原因见 Attach 的类注释。
             * 用户取消时结果仍是 null —— 必须回给 WebView，否则页面会一直等。
             *
             * ⚠️ 拷贝要**放到后台线程**：Attach 现在会把选中的内容整个复制一份
             * （这是绕开"渲染进程读不到"的必要代价），几十 MB 的文件在主线程上拷
             * 会直接把界面卡住甚至 ANR。回调本身允许在别的线程触发，但为了稳妥，
             * 回到主线程再交给 WebView。
             */
            new Thread(() -> {
                android.net.Uri[] results = null;
                try {
                    results = Attach.stageForWebView(this, Attach.collect(this, resultCode, data));
                } catch (Throwable t) {
                    Log.w(TAG, "处理文件选择结果失败", t);
                }
                final android.net.Uri[] done = results;
                runOnUiThread(() -> cb.onReceiveValue(done));
            }, "attach-stage").start();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /**
     * 申请共享存储读写权限。
     *
     * 这是"DSH 能读手机文件"的前提：容器靠 proot 把 /storage/emulated/0
     * 绑进来自，但如果 App 自己都没有存储权限，绑定过去也是空的。
     * 本 App targetSdk = 28，配合这两个权限即可获得旧版存储模型的完整读写能力。
     */
    private void requestStoragePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    /**
     * 初始化入口：**互斥 + 顶层兜底**。
     *
     * 原来直接 `new Thread(this::runAllTests)`，有两个问题：
     *   1. 没有任何互斥 —— 初始化途中切后台再回来（Activity 重建）
     *      会再起一个线程，两个线程同时解压 rootfs / 装 npm，互相踩踏；
     *   2. runAllTests 没有顶层 try/catch —— 任何意外异常都会让线程静默死掉，
     *      界面永久停在一句话上，用户只能卸载重装。
     * 现在统一从这里进：失败一定会被接住并变成"看得见的错误 + 重试入口"。
     */
    private void startProvisioning(String trigger) {
        if (!sProvisioning.compareAndSet(false, true)) {
            appendReport("  [跳过] 已有初始化流程在跑（触发源: " + trigger + "）\n");
            return;
        }
        // reportFile 必须先就绪：下面的 appendReport 与看门狗的心跳都会用它，
        // 而它原本是在 runAllTests() 里才初始化的 —— 首次运行会 NPE。
        try {
            File base = new File(getFilesDir(), "probe");
            //noinspection ResultOfMethodCallIgnored
            base.mkdirs();
            if (reportFile == null) reportFile = new File(base, "report.txt");
            sReportFile = reportFile;
        } catch (Throwable ignore) { }
        mark("开始初始化（" + trigger + "）");
        appendReport("\n--- 开始初始化（触发源: " + trigger + "）---\n");
        onProvisionStarted();
        startElapsedTicker();
        new Thread(() -> {
            try {
                runAllTests();
            } catch (Throwable t) {
                onProvisionFailed(t);
            } finally {
                stopElapsedTicker();
                sProvisioning.set(false);
            }
        }, "dsh-provision").start();
    }

    /**
     * 清空容器（rootfs / Node / 半成品归档）后重新初始化。
     * 对应"解压到一半的 rootfs 救不回来"这种情况。
     */
    private void wipeContainerAndRetry() {
        if (sProvisioning.get()) {
            android.widget.Toast.makeText(this, "初始化正在进行中，请稍候",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            try {
                File base = Env.base(this);
                Env.deleteRecursively(new File(base, "rootfs"));
                Env.deleteRecursively(new File(base, "staticroot"));
                Env.deleteRecursively(new File(base, "ubuntu-base-arm64.tar.gz"));
                Env.deleteRecursively(new File(base, "ubuntu-base-arm64.tar.gz.part"));
                for (File f : new File(base, "opt").listFiles()) {
                    if (f.getName().startsWith("node-")) Env.deleteRecursively(f);
                }
                appendReport("  [重置] 已清空容器，准备重新下载\n");
            } catch (Throwable t) {
                Log.w(TAG, "wipe failed", t);
            }
            runOnUiThread(() -> {
                output.setText("已清空容器，正在重新初始化…\n");
                startProvisioning("清空后重来");
            });
        }, "wipe-container").start();
    }

    /*
     * 这里原来有一份自己的 deleteRecursively（清空容器用），但它**会跟进符号链接**：
     * 容器里任何一个指向 /sdcard/... 的链接都会让删除顺着爬到用户手机上。
     * 现在统一用 Env.deleteRecursively —— 它先用 Os.lstat 判断"这一项本身是不是链接"，
     * 是链接就只摘链接本身。全 App 只有这一份实现，免得两处行为不一致。
     */

    /** 手动「重试」时置 true：强制走完整自检，别用上次的标记糊弄过去。 */
    private volatile boolean sForceFullTest = false;

    /**
     * 启动 dsh web 并等它就绪。
     *
     * 冷启动（跑完 11 项探针）和快速路径共用这一段 —— 抽出来是为了让
     * "跳过自检"不会连"启动服务"一起跳过。
     */
    private String startWebAndWait() {
        StringBuilder t10 = new StringBuilder();
        mark("开始启动 dsh web");
        progress(t10, "  启动前台服务（App 退到后台也能存活）…\n");
        startDshService();
        runOnUiThread(() -> statusBar.setText("Tdsh · 正在启动 dsh web…"));
        awaitUrl();
        mark("dsh web 就绪（awaitUrl 返回）");
        appendReport("Phase 2 · 前台服务启动\n" + t10 + "\n");
        return t10.append('\n').toString();
    }

    /** 自检通过的标记文件，内容是本 App 的 versionCode。 */
    private File verifiedStamp() {
        return new File(new File(getFilesDir(), "probe"), "verified.stamp");
    }

    private int appVersionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 标记存在、且是**当前版本**写下的才算有效。 */
    private boolean stampValid() {
        try {
            File f = verifiedStamp();
            if (!f.exists()) return false;
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            try {
                String s = br.readLine();
                return s != null && s.trim().equals(String.valueOf(appVersionCode()));
            } finally {
                br.close();
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private void markVerified() {
        try {
            File f = verifiedStamp();
            //noinspection ResultOfMethodCallIgnored
            f.getParentFile().mkdirs();
            java.io.FileOutputStream os = new java.io.FileOutputStream(f);
            try {
                os.write(String.valueOf(appVersionCode()).getBytes("UTF-8"));
            } finally {
                os.close();
            }
        } catch (Throwable t) {
            // 写不进去只是"下次还得自检一遍"，不该影响功能
            Log.w(TAG, "写自检标记失败", t);
        }
    }

    private void runAllTests() {
        StringBuilder r = new StringBuilder();
        File base = new File(getFilesDir(), "probe");
        //noinspection ResultOfMethodCallIgnored
        base.mkdirs();

        // 实测发现：OriginOS 上 logcat 缓冲会被系统清掉，不再可靠。
        // 所以把报告同时写进本 App 自己的沙箱文件，用
        //   adb exec-out run-as com.dshmobile.probe cat files/probe/report.txt
        // 读取（只读，不触碰任何其它数据）。
        reportFile = new File(base, "report.txt");
        appendReport("=== DSH Mobile Phase 0 probe ===\n"
                + "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "device " + Build.MANUFACTURER + " " + Build.MODEL
                + "  abi=" + Build.SUPPORTED_ABIS[0] + "\n\n");

        /*
         * ── 快速路径 ────────────────────────────────────────────
         *
         * 容器已经装好、而且**上一版 App 的自检已经全过** —— 就别再把下面
         * 11 项探针从头跑一遍。
         *
         * 为什么要有它：这 11 项每一项都要起一次 proot（有的还要起 node），
         * 而它们全部跑完才会走到 Phase 2 启动 dsh web。
         * 于是"从点图标到能用"被拉长了十几秒 —— 用户反馈的"初始化时间太长"就是它。
         * 而这些探针绝大多数是幂等的"确保装好"，已经装好了再验一遍没有意义。
         *
         * 两个条件同时成立才跳：容器就绪（rootfs/bash/node/dsh 都在）+
         * 标记文件里的 versionCode 与当前一致。后者保证**升级 App 后会自动
         * 完整自检一次**（新版本可能带新的容器侧改动，比如随包插件换版本）。
         */
        mark("自检入口");
        if (!sForceFullTest && isContainerReady() && stampValid()) {
            mark("走快速路径：跳过 11 项探针");
            appendReport("  容器已就绪，且自检标记有效 → 跳过 11 项探针，直接启动 dsh web\n");
            Log.i(TAG, "fast start: 跳过自检");
            /*
             * ★ 快速路径也必须上报进度（踩过的坑）。
             *
             * 进度点原来只加在"完整自检链"里，而这台机器早就通过自检、每次都走快速路径，
             * 于是进度永远是 0 —— 开屏动画（帧号由进度算出）**一直停在第一帧**，
             * 用户看到的就是"画面卡住不动"。
             *
             * 这里按快速路径的真实节奏补几个点：容器已就绪 → 起服务 → 等 web → 就绪。
             */
            splashProgress(0.35f);
            StringBuilder fast = new StringBuilder();
            fast.append("快速启动（容器已就绪，跳过自检）\n");
            fast.append(startWebAndWait());
            final String msg = fast.toString();
            runOnUiThread(() -> {
                String cur = output.getText().toString();
                output.setText(cur.replace("检查中…\n", "") + msg);
            });
            return;
        }
        sForceFullTest = false;

        r.append("TEST 1 · 执行私有目录里的二进制\n");
        String t1 = testExecCopiedBinary(base); r.append(t1).append('\n');
        appendReport("TEST 1 · 执行私有目录里的二进制\n" + t1 + "\n");

        r.append("TEST 2 · 执行私有目录里的 shell 脚本\n");
        String t2 = testExecScript(base); r.append(t2).append('\n');
        appendReport("TEST 2 · 执行私有目录里的 shell 脚本\n" + t2 + "\n");

        r.append("TEST 3 · ptrace 可用性（proot 依赖）\n");
        String t3 = testPtrace(); r.append(t3).append('\n');
        appendReport("TEST 3 · ptrace 可用性\n" + t3 + "\n");

        // proot 依赖 Termux 的 libtalloc / libandroid-shmem，先把它们解到私有目录
        String libDir = prepareLibs(base);

        r.append("TEST 4 · proot 二进制能否运行\n");
        String t4 = testProotRuns(base, libDir); r.append(t4).append('\n');
        appendReport("TEST 4 · proot 二进制能否运行\n" + t4 + "\n");

        r.append("TEST 5 · proot 完整机制（假 root + uid 伪装）\n");
        String t5 = testProotFakeRoot(base, libDir); r.append(t5).append('\n');
        appendReport("TEST 5 · proot 完整机制\n" + t5 + "\n");

        r.append("TEST 6 · proot 隔离测试（静态 busybox，无解释器依赖）\n");
        String t6 = testProotStaticBusybox(base, libDir); r.append(t6).append('\n');
        appendReport("TEST 6 · proot 隔离测试（静态 busybox）\n" + t6 + "\n");

        r.append("TEST 7 · Phase 1a：真实 glibc 容器（Ubuntu base 24.04 arm64）\n");
        String t7 = testContainer(base, libDir); r.append(t7).append('\n');
        appendReport("TEST 7 · Phase 1a 真实 glibc 容器\n" + t7 + "\n");

        File rootfs = new File(base, "rootfs");
        /*
         * 容器没装好就**到此为止** —— 不再往下跑 TEST 8~11 和 Phase 2。
         *
         * 以前不管 TEST 7 的结果继续往下跑，后果是：真正的错误（rootfs 没解压全）
         * 被后面一连串必然失败淹没 —— 用户截图里只剩下"npm 退出码 1""node 自检失败"，
         * 方向完全是错的，还要白等好几分钟才看到最后的"初始化失败"。
         */
        String rfProblem = rootfsProblem(rootfs);
        if (rfProblem != null) {
            final String snapshot = r.toString();
            appendReport("TEST 8~11 与 Phase 2 已跳过：" + rfProblem + "\n");
            runOnUiThread(() -> output.setText(output.getText().toString() + snapshot));
            onProvisionFailed(new IllegalStateException(
                    "容器没装好（" + rfProblem + "），后续步骤已跳过。"
                    + "点右上角「重试」会重新下载/解压容器（坏包会自动丢弃）"));
            return;
        }
        r.append("TEST 8 · Phase 1b：容器内安装 Node.js\n");
        splashProgress(0.45f);          // 容器已解包完成，进入装 Node
        String t8 = testNode(base, rootfs, libDir, new StringBuilder()); r.append(t8).append('\n');
        appendReport("TEST 8 · 容器内安装 Node.js\n" + t8 + "\n");
        splashProgress(0.58f);

        r.append("TEST 9 · Phase 1b：容器内安装并运行 DSH\n");
        splashProgress(0.62f);          // 装 DSH 是最慢的一段（npm install 可能十几分钟）
        String t9 = testDsh(base, rootfs, libDir, new StringBuilder()); r.append(t9).append('\n');
        appendReport("TEST 9 · 容器内安装 DSH\n" + t9 + "\n");
        splashProgress(0.86f);          // DSH 装完

        // ── 手机存储访问（容器能否看到手机文件）──
        r.append("TEST 10 · 手机存储访问\n");
        String t11 = testStorage(base, libDir); r.append(t11).append('\n');
        appendReport("TEST 10 · 手机存储访问\n" + t11 + "\n");
        splashProgress(0.90f);

        // ── 硬链接支持（DSH 会话日志依赖）──
        r.append("TEST 11 · 硬链接支持\n");
        String t12 = testHardLink(base, libDir); r.append(t12).append('\n');
        appendReport("TEST 11 · 硬链接支持\n" + t12 + "\n");
        splashProgress(0.93f);

        // ── Phase 2：交给前台服务常驻运行，并等待 Web UI 就绪 ──
        r.append("Phase 2 · 启动前台服务并等待 dsh web\n");
        splashProgress(0.96f);          // 开始起服务，最后 4% 留给"等待 web 就绪"
        r.append(startWebAndWait());
        // 自检全过 → 记下标记，下次启动直接走快速路径
        markVerified();

        r.append("── 判定 ──\n");
        String verdict = judge(r.toString());
        r.append(verdict);
        appendReport("── 判定 ──\n" + verdict);

        Log.i(TAG, "=== RESULT ===\n" + r);
        runOnUiThread(() -> {
            String cur = output.getText().toString();
            if (sLastFailure != null) {
                /*
                 * ⚠️ 失败时**只能追加，不能整段替换**。
                 *
                 * onProvisionFailed 在 awaitUrl 超时时已经把"为什么失败"写进了日志区，
                 * 而这里原来是 setText(cur + r) —— 会把那段原因整个擦掉，
                 * 用户屏幕上就只剩"初始化失败"四个字，看不到任何线索
                 * （朋友截图里就是这个现象）。
                 */
                output.setText(cur + r);
            } else {
                output.setText(cur.replace("检查中…\n", "") + r);
            }
        });
    }

    // ── TEST 10 · 手机存储访问 ────────────────────────────────

    /**
     * 验证两件事：
     *   1. App 自身有没有拿到共享存储读写权限（宿主侧）；
     *   2. 容器里能不能看到并读写手机文件（guest 侧，靠 proot 绑定）。
     *
     * 这是"DSH 读不了手机文件"这个问题的直接验收点。
     */
    private String testStorage(File base, String libDir) {
        StringBuilder sb = new StringBuilder();
        try {
            File sd = new File(Env.SDCARD_HOST);
            String[] names = sd.list();
            sb.append("  宿主 ").append(Env.SDCARD_HOST)
              .append("  可读=").append(sd.canRead())
              .append("  可写=").append(sd.canWrite()).append('\n');
            sb.append("  顶层条目: ").append(names == null ? "null（无权限）" : names.length + " 个").append('\n');
            if (names != null && names.length > 0) {
                StringBuilder sample = new StringBuilder();
                for (int i = 0; i < Math.min(10, names.length); i++) sample.append(names[i]).append(' ');
                sb.append("  抽样: ").append(sample).append('\n');
            }

            String ws = Env.ensureStorageMounts(base);
            sb.append("  测试文件夹: ").append(ws == null ? "❌ 创建失败" : ws + " ✅").append('\n');

            if (names == null) {
                sb.append("  结果: ❌ 宿主没有存储权限 —— 请在手机上允许「文件和媒体」权限\n");
                return sb.toString();
            }

            // guest 侧实测：进容器看 /sdcard，并往 /sdcard/dsh 里写一个文件
            String res = exec(new String[]{
                    new File(base, "proot").getAbsolutePath(),
                    "-r", new File(base, "rootfs").getAbsolutePath(),
                    "-0", "-w", Env.WORKSPACE,
                    "-b", "/dev", "-b", "/proc", "-b", "/sys",
                    "-b", Env.SDCARD_HOST + ":" + Env.SDCARD_GUEST,
                    "-b", Env.SDCARD_HOST + ":" + Env.SDCARD_HOST,
                    "/usr/bin/env", "-i", "HOME=/root",
                    "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "/bin/bash", "-c",
                    "echo cwd=$(pwd); echo '--- /sdcard 顶层 ---'; ls /sdcard 2>&1 | head -12; "
                  + "echo '--- 写入测试 ---'; "
                  + "echo dsh-mobile-write-test > /sdcard/" + Env.TEST_DIR_NAME + "/hello.txt "
                  + "&& cat /sdcard/" + Env.TEST_DIR_NAME + "/hello.txt || echo WRITE_FAILED"},
                    libDir);
            sb.append("  容器内输出:\n");
            for (String line : res.split("\n")) {
                if (!line.trim().isEmpty()) sb.append("    ").append(line.trim()).append('\n');
            }
            boolean sawFiles = res.contains("--- /sdcard 顶层 ---")
                    && res.contains("dsh-mobile-write-test");
            sb.append("  结果: ").append(sawFiles
                    ? "✅ PASS — 容器可读写手机文件，工作区 = " + Env.WORKSPACE
                    : "❌ FAIL — 容器看不到手机存储").append('\n');
        } catch (Throwable t) {
            Log.e(TAG, "testStorage failed", t);
            sb.append("  结果: ❌ FAIL — ").append(t.getClass().getSimpleName())
              .append(": ").append(t.getMessage()).append('\n');
        }
        return sb.toString();
    }

    // ── TEST 11 · 硬链接支持（DSH 写会话日志依赖它）──────────

    /**
     * DSH 保存会话用的是"临时文件 + 硬链接"的原子替换
     * （@deepseek-ai/dsh-atomic-write）。容器里 link() 失败会让每一轮对话直接报
     *   EACCES: permission denied, link ...
     * 从而整个 agent 不可用。
     *
     * 这里做二分定位：
     *   (a) App 进程直接在沙箱里 Os.link() → 排查文件系统 / SELinux
     *   (b) 容器内 ln                        → 排查 proot 的路径翻译
     *   (c) proot 是否支持 --link2symlink    → 备选修复手段
     */
    private String testHardLink(File base, String libDir) {
        StringBuilder sb = new StringBuilder();
        try {
            // (a) 宿主侧
            File a = new File(base, "linktest-a");
            File b = new File(base, "linktest-b");
            //noinspection ResultOfMethodCallIgnored
            b.delete();
            try (OutputStream os = new FileOutputStream(a)) {
                os.write("x".getBytes(StandardCharsets.UTF_8));
            }
            boolean hostOk;
            String hostErr = "";
            try {
                Os.link(a.getAbsolutePath(), b.getAbsolutePath());
                hostOk = b.exists();
            } catch (Throwable t) {
                hostOk = false;
                hostErr = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            sb.append("  (a) 宿主 Os.link(): ").append(hostOk ? "✅ 支持" : "❌ 失败 " + hostErr).append('\n');

            // (b) 容器内（带 --link2symlink，验证修复是否生效）
            String res = exec(new String[]{
                    new File(base, "proot").getAbsolutePath(),
                    "-r", new File(base, "rootfs").getAbsolutePath(),
                    "-0", "-w", "/root",
                    "--link2symlink",
                    "-b", "/dev", "-b", "/proc", "-b", "/sys",
                    "/usr/bin/env", "-i", "HOME=/root",
                    "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "/bin/bash", "-c",
                    "cd /root && rm -f lt1 lt2 && echo hi > lt1 && "
                  + "(ln lt1 lt2 && echo CONTAINER_LINK_OK && cat lt2 || echo CONTAINER_LINK_FAIL)"},
                    libDir);
            for (String line : res.split("\n")) {
                if (!line.trim().isEmpty()) sb.append("  ").append(line.trim()).append('\n');
            }
            boolean containerOk = res.contains("CONTAINER_LINK_OK");
            sb.append("  结论: 宿主硬链接=").append(hostOk ? "支持" : "被 SELinux 禁止（正常）")
              .append(" 容器内 link（带 --link2symlink）=").append(containerOk ? "✅ 可用" : "❌ 仍失败").append('\n');
        } catch (Throwable t) {
            Log.e(TAG, "testHardLink failed", t);
            sb.append("  结果: ❌ ").append(t).append('\n');
        }
        return sb.toString();
    }

    /** 把 assets 里的文本资源读成字符串（用于注入 JS 补丁）。 */
    private String readAssetText(String name) {
        try (InputStream in = getAssets().open(name)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Log.e(TAG, "readAssetText failed: " + name, t);
            return null;
        }
    }

    /** 把 InputStream 整个读成字符串（用于改写主文档）。 */
    private String readStream(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (Throwable ignore) { }
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * 把系统 WebView 的版本记进报告，并统计垫片补了哪些特性。
     *
     * 分享出去以后，"老 WebView 装不上"这种问题只能靠用户描述；
     * 有了这一行，对方只要点「复制日志」就能把确切版本发过来。
     */
    private void reportWebViewVersion(WebView view) {
        try {
            view.evaluateJavascript(
                    "(function(){try{var c=window.__dshWebCompat;"
                  + "return JSON.stringify({ua:navigator.userAgent,"
                  + "applied:(c&&c.applied)||null});}catch(e){return 'null';}})()",
                    value -> {
                        if (value == null || value.equals("null")) return;
                        // evaluateJavascript 返回的是 JSON 字符串字面量，去引号并反转义
                        String s = value;
                        if (s.length() > 1 && s.charAt(0) == '"') {
                            s = s.substring(1, s.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
                        }
                        String ver = "?";
                        java.util.regex.Matcher m = java.util.regex.Pattern
                                .compile("Chrome/([0-9.]+)").matcher(s);
                        if (m.find()) ver = m.group(1);
                        String applied = "";
                        int i = s.indexOf("applied\":");
                        if (i >= 0) applied = s.substring(i + 9, Math.min(i + 200, s.length()));
                        webViewVersion = ver;
                        // 主版本单独存一份：它是"跑不跑得动前端"的判据
                        try {
                            String head = ver.contains(".") ? ver.substring(0, ver.indexOf('.')) : ver;
                            webViewMajor = Integer.parseInt(head);
                        } catch (Throwable ignore) { }
                        appendReport("  [WebView] Chrome/" + ver + " · 垫片补充: " + applied
                                + (webViewTooOld() ? "  ⚠️ 低于 " + MIN_WEBVIEW_MAJOR
                                        + "，DSH 前端可能解析不了（见更新的说明）" : "") + "\n");
                        Log.i(TAG, "WebView Chrome/" + ver + " shim=" + applied);
                    });
        } catch (Throwable t) {
            Log.w(TAG, "reportWebViewVersion failed", t);
        }
    }

    /** 把报告追加到沙箱文件；用 run-as 可在 PC 侧只读取出。 */
    private void appendReport(String text) {
        try (OutputStream os = new FileOutputStream(reportFile, true)) {
            os.write(text.getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (Throwable t) {
            Log.e(TAG, "appendReport failed", t);
        }
    }

    // ── TEST 1 ────────────────────────────────────────────────
    private String testExecCopiedBinary(File base) {
        File target = new File(base, "sh");
        try {
            copy(new File("/system/bin/sh"), target);
            Os.chmod(target.getAbsolutePath(), 0700);
            String res = exec(new String[]{target.getAbsolutePath(), "-c", "echo WX_EXEC_OK; id"});
            return "  路径 : " + target.getAbsolutePath() + "\n"
                 + "  输出 : " + oneLine(res) + "\n"
                 + "  结果 : " + (res.contains("WX_EXEC_OK") ? "✅ PASS" : "❌ FAIL") + "\n";
        } catch (Throwable t) {
            Log.e(TAG, "test1 failed", t);
            return "  结果 : ❌ FAIL — " + t.getClass().getSimpleName() + ": " + t.getMessage() + "\n";
        }
    }

    // ── TEST 2 ────────────────────────────────────────────────
    private String testExecScript(File base) {
        File script = new File(base, "hello.sh");
        try {
            try (OutputStream os = new FileOutputStream(script)) {
                os.write("#!/system/bin/sh\necho SCRIPT_EXEC_OK\necho arch=$(uname -m)\n"
                        .getBytes(StandardCharsets.UTF_8));
            }
            Os.chmod(script.getAbsolutePath(), 0700);
            String res = exec(new String[]{script.getAbsolutePath()});
            return "  输出 : " + oneLine(res) + "\n"
                 + "  结果 : " + (res.contains("SCRIPT_EXEC_OK") ? "✅ PASS" : "❌ FAIL") + "\n";
        } catch (Throwable t) {
            Log.e(TAG, "test2 failed", t);
            return "  结果 : ❌ FAIL — " + t.getClass().getSimpleName() + ": " + t.getMessage() + "\n";
        }
    }

    // ── TEST 3 ────────────────────────────────────────────────
    private String testPtrace() {
        try {
            String res = exec(new String[]{"/system/bin/sh", "-c",
                    "cat /proc/sys/kernel/yama/ptrace_scope 2>/dev/null || echo no_yama; echo TRACER_OK"});
            return "  /proc: " + oneLine(res) + "\n"
                 + "  结果 : " + (res.contains("TRACER_OK") ? "✅ 可用" : "⚠️ 无法判定") + "\n";
        } catch (Throwable t) {
            return "  结果 : ⚠️ " + t.getClass().getSimpleName() + ": " + t.getMessage() + "\n";
        }
    }

    // ── TEST 4 ────────────────────────────────────────────────
    /**
     * proot 是从 assets 里解出来的 Termux 官方 aarch64 版（PT_INTERP=/system/bin/linker64）。
     *
     * 第一次跑失败得很明确：
     *   CANNOT LINK EXECUTABLE ".../proot": library "libtalloc.so.2" not found
     * 因为 proot 依赖 Termux 自己的 libtalloc 与 libandroid-shmem（.deb 的
     * Depends 写明，且二者都没有更深依赖）。所以现在把这两个 .so 一起打包，
     * 并用 LD_LIBRARY_PATH 指给动态链接器。
     */
    private String testProotRuns(File base, String libDir) {
        try {
            File proot = extractAsset("proot", new File(base, "proot"));
            String res = exec(new String[]{proot.getAbsolutePath(), "--version"}, libDir);
            boolean ran = res.contains("[exitCode=0]");   // 只看退出码，不看输出里有没有 "proot" 字样
            return "  路径 : " + proot.getAbsolutePath() + " (" + proot.length() + " bytes)\n"
                 + "  库   : " + libDir + "\n"
                 + "  输出 : " + oneLine(res) + "\n"
                 + "  结果 : " + (ran ? "✅ PASS — 安卓 bionic 二进制可在本 App 内启动" : "❌ FAIL") + "\n";
        } catch (Throwable t) {
            Log.e(TAG, "test4 failed", t);
            return "  结果 : ❌ FAIL — " + t.getClass().getSimpleName() + ": " + t.getMessage() + "\n";
        }
    }

    // ── TEST 5 ────────────────────────────────────────────────
    /**
     * 用最小的"假 root"验证 proot 的三项核心机制：
     *   · 路径翻译（-r 指定的 root）
     *   · uid 伪装（-0 → 进程看到自己是 root）
     *   · 绑定挂载（把真实 /system /dev /proc 挂进假 root）
     *
     * 注意：假 root 里的 sh / id 是从 /system/bin 复制来的副本，
     * 我们不改动、不删除任何系统文件。
     */
    private String testProotFakeRoot(File base, String libDir) {
        try {
            File proot = new File(base, "proot");
            if (!proot.exists()) return "  跳过：proot 未就绪\n";

            File root = new File(base, "fakeroot");
            File bin = new File(root, "bin");
            //noinspection ResultOfMethodCallIgnored
            bin.mkdirs();

            // 关键：先把 guest 侧的挂载点目录建好。
            // 否则 proot 会尝试自己创建，然后报 "can't sanitize binding: Permission denied"，
            // 导致所有 -b 绑定都挂不上，execve("/bin/sh") 就会因为找不到解释器而 ENOENT。
            // 真实 rootfs 镜像里这些目录本来就有，这正是之前那版测试失败的原因。
            for (String d : new String[]{"system", "apex", "linkerconfig", "dev", "proc", "tmp", "usr", "lib"}) {
                //noinspection ResultOfMethodCallIgnored
                new File(root, d).mkdirs();
            }

            File sh = new File(bin, "sh");
            File id = new File(bin, "id");
            copy(new File("/system/bin/sh"), sh);
            copy(new File("/system/bin/id"), id);
            Os.chmod(sh.getAbsolutePath(), 0700);
            Os.chmod(id.getAbsolutePath(), 0700);

            String res = exec(new String[]{
                    proot.getAbsolutePath(),
                    "-r", root.getAbsolutePath(),
                    "-0",                       // 伪装成 root
                    "-w", "/",
                    // Android 10+ 的 /system/bin/linker64 是指向 /apex/com.android.runtime/
                    // 的符号链接，所以 /apex 必须一起绑进来，否则解释器解析不到（execve ENOENT）
                    "-b", "/system",
                    "-b", "/apex",
                    "-b", "/linkerconfig",
                    "-b", "/dev",
                    "-b", "/proc",
                    "/bin/sh", "-c", "echo PROOT_START; id; echo PROOT_OK"}, libDir);

            boolean started = res.contains("PROOT_START");
            boolean ok = res.contains("PROOT_OK");
            boolean fakeRoot = res.contains("uid=0");
            return "  假 root : " + root.getAbsolutePath() + "\n"
                 + "  输出    : " + oneLine(res) + "\n"
                 + "  结果    : " + (ok && fakeRoot ? "✅ PASS — 路径翻译 + uid 伪装均生效"
                        : started ? "⚠️ 部分生效（proot 起来了，但 uid 伪装未确认）"
                        : "❌ FAIL — proot 未能启动容器") + "\n";
        } catch (Throwable t) {
            Log.e(TAG, "test5 failed", t);
            return "  结果 : ❌ FAIL — " + t.getClass().getSimpleName() + ": " + t.getMessage() + "\n";
        }
    }

    // ── TEST 6 ────────────────────────────────────────────────
    /**
     * 隔离测试 proot 的容器机制。
     *
     * TEST 5 用的是安卓 bionic 二进制，它有 "/system/bin/linker64" 解释器依赖，
     * 而 Android 10+ 上这个路径还牵扯 /apex 符号链接 —— 那是人造的难题，
     * 真实 Debian rootfs 里解释器就在 rootfs 内部，是自包含的。
     *
     * 所以这里换成 Alpine 的 busybox-static：完全静态（无 PT_INTERP、无 PT_DYNAMIC），
     * 不需要任何解释器。它能把变量隔离干净：
     *   6a 直接执行          → busybox 本身在这台设备上能不能跑（对照组）
     *   6b 在 proot 里执行   → proot 的路径翻译 / uid 伪装到底行不行（实验组）
     */
    /**
     * 【已停用】当年用来把 proot 机制单独隔离验证（静态 busybox 无解释器依赖）。
     *
     * 现在 TEST 7 已经用真实 glibc 容器覆盖了完全相同的机制，而 busybox 资产
     * （1.1 MB、GPLv2）**运行时根本不需要**。为了给 APK 减半、同时少一个 GPL 组件，
     * 已把它从 assets 里删除。这里保留函数但直接跳过，避免自检报错。
     */
    private String testProotStaticBusybox(File base, String libDir) {
        return "  已跳过：busybox 资产已移除（运行时不需要，机制由 TEST 7 覆盖）\n";
    }

    // ── TEST 7 · Phase 1a：真实 glibc 容器 ──────────────────────
    //
    // 前面 TEST 5 / TEST 6 用的都是"非典型载荷"（bionic 依赖 /apex 符号链接；
    // musl 静态被 seccomp 杀），不能代表真实场景。真实目标是 glibc rootfs，
    // 它的解释器在 rootfs 内部，自包含。
    //
    // 这里下载 Ubuntu base 24.04 arm64（官方最小 glibc rootfs，28.6 MB），
    // 解压到本 App 沙箱，然后用 proot 在里面跑 bash。这一步能彻底定论 proot 机制。
    //
    // ⚠️ 下载源**不在这里写死**了 —— 见 {@link Region}：
    // 海外用户走国内源会慢到失败（用户反馈"非大陆地区难以安装环境"），
    // 现在按用户在首次启动时选的地区排优先顺序。
    private String testContainer(File base, String libDir) {
        StringBuilder sb = new StringBuilder();
        try {
            File proot = new File(base, "proot");
            File rootfs = new File(base, "rootfs");
            File archive = new File(base, "ubuntu-base-arm64.tar.gz");

            progress(sb, "  可用空间: " + mb(freeBytes(base)) + " MB\n");
            String space = spaceProblem(base, ROOTFS_STAGE_BYTES);
            if (space != null) return fail(sb, space);

            /*
             * 【自愈】已经存在的 rootfs 先验完整性，坏的直接连归档一起丢掉重来。
             *
             * 这是"环境装不上、点重试也没用"的根治点。原来的判断只有
             * `/usr/bin/bash 存在吗` —— 而用户机器上正是 bash 在、`/usr/bin/env` 不在
             * （归档按字母序解压，bash 排在 env 前面：下载被截断或存储不足时
             * 就恰好停在这一段）。于是每次重试都认定"rootfs 已存在、跳过下载解压"，
             * 然后 Node/npm 必然全崩，用户看到的就是那屏指向错误方向的日志。
             */
            String problem = rootfsProblem(rootfs);
            if (problem != null && new File(rootfs, "usr/bin/bash").exists()) {
                progress(sb, "  ⚠️ 已存在的 rootfs 不完整（" + problem + "）→ 丢掉重下\n");
            }
            if (problem != null) {
                Env.deleteRecursively(rootfs);
                Env.deleteRecursively(archive);
                Env.deleteRecursively(new File(archive.getAbsolutePath() + ".part"));
            }

            boolean ready = rootfsProblem(rootfs) == null;
            if (ready) {
                sb.append("  rootfs 已存在，且完整性校验通过 → 跳过下载解压\n");
            } else {
                /*
                 * 先试本地已下载的归档：如果上次失败在"解压"这一步（比如空间不够），
                 * 归档还是好的，能省一次 30MB 下载。校验不过就删掉，别留着反复解。
                 */
                if (archive.exists() && archive.length() >= ROOTFS_MIN_BYTES) {
                    progress(sb, "  先试本地已下载的归档（"
                            + (archive.length() / 1048576) + " MB）…\n");
                    ready = tryExtractRootfs(archive, rootfs, sb);
                    if (!ready) Env.deleteRecursively(archive);
                }
                String[] urls = Region.rootfsUrls(this);
                for (int i = 0; i < urls.length && !ready; i++) {
                    String url = urls[i];
                    try {
                        progress(sb, "  下载 rootfs（源 " + (i + 1) + "/" + urls.length + "）: "
                                + url + "\n");
                        download(url, archive, ROOTFS_MIN_BYTES);
                        progress(sb, "  下载完成 (" + (archive.length() / 1048576)
                                + " MB)，开始解压（约 120MB，1-3 分钟）…\n");
                        ready = tryExtractRootfs(archive, rootfs, sb);
                        if (!ready) {
                            // 归档与解压结果都不可信，删干净再换下一个源
                            Env.deleteRecursively(archive);
                        }
                    } catch (Throwable t) {
                        progress(sb, "  该源失败: " + t.getClass().getSimpleName()
                                + ": " + t.getMessage() + "\n");
                        Env.deleteRecursively(new File(archive.getAbsolutePath() + ".part"));
                        Env.deleteRecursively(archive);
                    }
                }
                if (!ready) {
                    return fail(sb, "rootfs 下载/解压失败（" + urls.length
                            + " 个源都不行，或解压后文件不全）");
                }
            }

            // 顺手把容器里的 apt 源改成所选地区的镜像（大陆用户之后 apt 才不至于龟速）
            Region.applyAptSources(this, rootfs);
            // 容器内的 npm/pnpm 默认源同理：装插件、升级内核都会用到它
            Region.applyNpmrc(this, rootfs);
            progress(sb, "  容器安装源已按地区设置：" + Region.label(this) + "\n");

            // proot 需要的 guest 挂载点
            for (String d : new String[]{"dev", "proc", "sys", "tmp"}) {
                //noinspection ResultOfMethodCallIgnored
                new File(rootfs, d).mkdirs();
            }

            progress(sb, "  在 proot 里执行 bash…\n");
            String res = exec(new String[]{
                    proot.getAbsolutePath(),
                    "-r", rootfs.getAbsolutePath(),
                    "-0",                       // 伪装成 root
                    "-w", "/",
                    "-b", "/dev", "-b", "/proc", "-b", "/sys",
                    "/usr/bin/env", "-i",
                    "HOME=/root",
                    "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "LANG=C.UTF-8",
                    "/bin/bash", "-c",
                    "echo CONTAINER_OK; id; uname -m; "
                  + ". /etc/os-release; echo os=$PRETTY_NAME; "
                  + "echo bash=$(command -v bash); echo pwd=$(pwd)"}, libDir);

            boolean ok = res.contains("CONTAINER_OK");
            boolean fakeRoot = res.contains("uid=0");
            sb.append("  输出 : ").append(oneLine(res)).append('\n');
            sb.append("  结果 : ").append(
                    ok && fakeRoot ? "✅ PASS — 真实 glibc 容器跑通（proot 机制完全可用）"
                  : ok ? "⚠️ 容器能跑命令，但 uid 伪装未确认"
                  : "❌ FAIL — 容器未能启动 bash").append('\n');
            return sb.toString();
        } catch (Throwable t) {
            Log.e(TAG, "test7 failed", t);
            return sb + "  结果 : ❌ FAIL — " + t.getClass().getSimpleName() + ": " + t.getMessage() + "\n";
        }
    }

    // ── rootfs 完整性校验 / 存储空间预检 ────────────────────────

    /**
     * rootfs 解压完整性的**哨兵文件**。
     *
     * <p>为什么不能只看 /usr/bin/bash：用户反馈的"环境装不上"里，proot 报的是
     * `'/usr/bin/env' not found`，而 bash 在。原因是归档按**字母序**解压，
     * `usr/bin/bash` 排在 `usr/bin/env` 前面 —— 下载被截断、或者存储空间不足
     * 中途失败，都会恰好停在这一段。只看 bash 就判定"装好了"，于是后面
     * Node/npm/自检必然全崩，用户看到的那屏日志（npm 退出码 1、node 自检失败）
     * 指向的方向完全是错的，白等好几分钟。
     *
     * <p>所以多验几个关键点：解释器入口 env、merged-/usr 的 /bin/sh 符号链接
     * （它同时验证了符号链接有没有被正确还原）、glibc、apt、以及 os-release。
     */
    private static final String[] ROOTFS_SENTINELS = {
            "usr/bin/env", "usr/bin/bash", "bin/sh", "etc/os-release",
            "usr/lib/aarch64-linux-gnu/libc.so.6", "usr/bin/apt-get"
    };

    /*
     * 空间门槛刻意取**下限**而不是"宽裕值"：这两个数是用来拦住
     * "装到一半没空间"的，不是用来劝退空间刚好够用的手机。
     * 拿不准的时候宁可放过（让它去试）也不要误拦 —— 拦住一个本来能装成功的用户，
     * 比让一个空间不足的用户失败一次更糟。
     */
    /** rootfs 这一步要的空间（归档 30MB + 解压约 150MB + 余量）。 */
    private static final long ROOTFS_STAGE_BYTES = 300L * 1024 * 1024;
    /** 装完整个环境的下限（rootfs + Node 约 120MB + npm 依赖；装完实测在 600MB 上下）。 */
    private static final long TOTAL_STAGE_BYTES = 700L * 1024 * 1024;
    /** 归档最小可信体积：归档是 29,936,675 字节，明显小于它就是下载被截断了。 */
    private static final long ROOTFS_MIN_BYTES = 25L * 1024 * 1024;
    private static final long NODE_MIN_BYTES = 40L * 1024 * 1024;

    /** 返回 null 表示完整；否则返回"缺了什么"（直接给用户看）。 */
    private static String rootfsProblem(File rootfs) {
        for (String s : ROOTFS_SENTINELS) {
            if (!new File(rootfs, s).exists()) return "缺少 /" + s;
        }
        return null;
    }

    /**
     * 解压 + 校验，二合一。
     *
     * <p>校验不通过就把半成品删掉再返回 false —— 留着它只会让下一次重试
     * 继续误判"已经装好了"。返回 true 表示解压且校验都通过。
     */
    private boolean tryExtractRootfs(File archive, File rootfs, StringBuilder sb) {
        try {
            // 先清空：半个包叠在旧目录上会混出"看起来有、其实不全"的 rootfs
            Env.deleteRecursively(rootfs);
            //noinspection ResultOfMethodCallIgnored
            rootfs.mkdirs();
            extractTarGz(archive, rootfs);
            String p = rootfsProblem(rootfs);
            if (p == null) {
                progress(sb, "  解压完成，完整性校验通过\n");
                return true;
            }
            progress(sb, "  ❌ 解压后完整性校验不过（" + p + "）\n");
        } catch (Throwable t) {
            progress(sb, "  ❌ 解压失败: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage() + "\n");
        }
        Env.deleteRecursively(rootfs);
        return false;
    }

    /** 失败时的统一出口：记下原因（失败界面会把它显示出来）并返回日志行。 */
    private String fail(StringBuilder sb, String why) {
        sLastFailure = why;
        return sb + "  结果 : ❌ FAIL — " + why + "\n";
    }

    /** 可用空间（字节）；问不出来返回 -1。 */
    private static long freeBytes(File dir) {
        try {
            return new android.os.StatFs(dir.getAbsolutePath()).getAvailableBytes();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static long mb(long bytes) {
        return bytes < 0 ? -1 : bytes / 1048576;
    }

    /**
     * 返回 null = 空间够；否则是一句**带具体数字**的话。
     *
     * <p>原来完全没有空间检查，于是"存储不足"这类失败会伪装成
     * 下载失败 / npm 失败，用户永远猜不到该去清空间。
     */
    private static String spaceProblem(File dir, long need) {
        long free = freeBytes(dir);
        if (free < 0) return null;                       // 问不出来就别拦
        if (free >= need) return null;
        return "存储空间不足：可用 " + mb(free) + " MB，至少需要 " + mb(need)
                + " MB。请先清理手机空间，再点右上角「重试」";
    }

    /** 下载（不校验体积，只有确实不知道大小时才用）。 */
    private void download(String urlStr, File dst) throws Exception {
        download(urlStr, dst, 0L);
    }

    /**
     * 下载文件，带**总时长上限 + 停滞检测 + 界面进度 + 完整性校验**。
     *
     * <p>原来的版本只有 per-read 的 60 秒超时，没有任何总时长限制，
     * 而且进度只写进报告文件（普通用户看不到）—— 于是"下载中"和"卡死"
     * 在界面上长得一模一样。现在：
     *   · 连续 {@link #DOWNLOAD_STALL_MS} 没有任何字节 → 判定停滞，中止并换源；
     *   · 总时长超过 {@link #DOWNLOAD_TIMEOUT_MS} → 中止；
     *   · 每 2MB 往界面上打一行进度，让用户看到数字在动。
     *
     * <p>【为什么还要校验字节数】原来读完流就重命名成正式文件，
     * 连拿到的 Content-Length 都**从不比对**。于是"服务器/代理中途把连接关了"
     * 这种最常见的截断会被当成下载成功：一个 25MB 的半截 tar.gz
     * 能满足原来的 `> 20MB` 检查，解压到一半停住 ——
     * 这就是用户反馈的"环境装不上、点重试也没用"的其中一条根因。现在：
     *   · 服务器给了 Content-Length → 必须一个字节不差；
     *   · 没给 → 至少不能小于 minBytes（调用方按已知体积给）；
     *   · 任何中途异常都删掉 .part，绝不留半截文件给下一次误判。
     */
    private void download(String urlStr, File dst, long minBytes) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "DSH-Mobile-Probe");
        c.connect();
        int code = c.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
            throw new IOException("HTTP " + code);
        }
        long total = c.getContentLengthLong();
        File tmp = new File(dst.getAbsolutePath() + ".part");
        long got = 0, lastReported = 0;
        final long startedAt = System.currentTimeMillis();
        long lastByteAt = startedAt;
        try (InputStream in = new BufferedInputStream(c.getInputStream(), 1 << 16);
             OutputStream os = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                got += n;
                long now = System.currentTimeMillis();

                // 先用**上一次收到数据的时间**判断停滞，再更新它
                if (now - lastByteAt > DOWNLOAD_STALL_MS) {
                    throw new IOException("下载停滞（" + (DOWNLOAD_STALL_MS / 1000) + " 秒无数据）");
                }
                lastByteAt = now;
                sLastOutputAt = now;

                if (now - startedAt > DOWNLOAD_TIMEOUT_MS) {
                    throw new IOException("下载超时（总时长超过 "
                            + (DOWNLOAD_TIMEOUT_MS / 60000) + " 分钟）");
                }
                if (got - lastReported >= (2L << 20)) {
                    lastReported = got;
                    String line = "    下载中 " + (got >> 20) + " / "
                            + (total > 0 ? (total >> 20) + " MB" : "? MB")
                            + "（已 " + fmtDuration(now - startedAt) + "）\n";
                    appendReport("  " + line);
                    final String l = "  " + line;
                    runOnUiThread(() -> output.append(l));
                }
            }
        } catch (Throwable t) {
            /*
             * 半截文件绝不能留下。
             *
             * 下载失败时如果把 .part 留着（原来就是），下一次运行会把它
             * 当成"已经下载好的归档"直接拿去解压 —— 坏包被反复使用，
             * 用户看到的就是"点重试也没用"。
             */
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw t;
        } finally {
            c.disconnect();
        }
        if (total > 0 && got != total) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("下载不完整（收到 " + got + " / 应为 " + total
                    + " 字节）—— 已丢弃，换源重试");
        }
        if (minBytes > 0 && got < minBytes) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("下载的文件偏小（" + got + " 字节 < " + minBytes
                    + "）—— 已丢弃，换源重试");
        }
        if (!tmp.renameTo(dst)) {
            copy(tmp, dst);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /**
     * 解包 rootfs 的 .tar.gz。
     * Ubuntu base 使用了 merged-/usr（/bin、/lib、/sbin 都是指向 usr/ 的符号链接），
     * 所以符号链接必须正确还原，否则容器里的 /bin/bash 找不到。
     */
    private void extractTarGz(File archive, File dest) throws Exception {
        String destPath = dest.getCanonicalPath();
        int files = 0, links = 0;
        try (InputStream fin = new FileInputStream(archive);
             GZIPInputStream gz = new GZIPInputStream(fin, 1 << 16);
             TarArchiveInputStream tin = new TarArchiveInputStream(gz)) {
            TarArchiveEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = tin.getNextEntry()) != null) {
                File out = new File(dest, e.getName());
                if (!out.getCanonicalPath().startsWith(destPath)) continue;   // 防目录穿越
                if (e.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                } else if (e.isSymbolicLink()) {
                    File parent = out.getParentFile();
                    if (parent != null) //noinspection ResultOfMethodCallIgnored
                        parent.mkdirs();
                    try { Os.symlink(e.getLinkName(), out.getAbsolutePath()); links++; }
                    catch (Throwable ignore) { /* 已存在则跳过 */ }
                } else if (e.isLink()) {
                    File parent = out.getParentFile();
                    if (parent != null) //noinspection ResultOfMethodCallIgnored
                        parent.mkdirs();
                    File target = new File(dest, e.getLinkName());
                    try { Os.link(target.getAbsolutePath(), out.getAbsolutePath()); links++; }
                    catch (Throwable t) { copy(target, out); files++; }
                } else if (e.isFile()) {
                    File parent = out.getParentFile();
                    if (parent != null) //noinspection ResultOfMethodCallIgnored
                        parent.mkdirs();
                    try (OutputStream os = new FileOutputStream(out)) {
                        int n;
                        while ((n = tin.read(buf)) > 0) os.write(buf, 0, n);
                    }
                    try { Os.chmod(out.getAbsolutePath(), e.getMode() & 0777); } catch (Throwable ignore) { }
                    files++;
                }
            }
        }
        appendReport("  解包统计: 文件 " + files + " 个, 链接 " + links + " 个\n");
    }

    /** 同时写入报告文件与界面（界面更新需回主线程）。**只在初始化主线程调用**。 */
    private void progress(StringBuilder sb, String line) {
        sb.append(line);
        String t = line.trim();
        if (!t.isEmpty()) sCurrentStep = t;     // 供状态栏的"耗时 + 当前步骤"显示
        liveProgress(line);
    }

    /*
     * ── 把真实初始化进度喂给开屏动画 ───────────────────────────────
     *
     * 为什么需要：开屏动画现在是成片帧序列（216 帧），如果按时间自走，
     * 就会出现"加载早就完了动画还在转"或"加载卡住动画已经播完"——两边对不上。
     * 用户明确要求动画与加载进度适配，所以这里把每个阶段完成时把进度报过去，
     * 帧号直接由进度算出（见 TdshSplashView.currentFrameIndex）。
     *
     * 取值按各阶段大致耗时占比给（容器解包最慢、起服务最快），
     * 只求单调递增、跟得上真实节奏，不追求精确到秒。
     */
    private void splashProgress(float p) {
        final TdshSplashView v = splash;
        if (v == null) return;
        runOnUiThread(() -> {
            final TdshSplashView vv = splash;
            if (vv != null) vv.setProgress(p);
        });
    }

    // ── TEST 8 / TEST 9 · Phase 1b：Node + DSH ─────────────────
    //
    // 包级可见（去掉 private）是给 {@link Region} 用的：Node 的下载源要按地区排序。
    static final String NODE_VERSION = "v22.23.2";
    static final String NODE_DIR = "node-" + NODE_VERSION + "-linux-arm64";

    /** TEST 8：在容器里装上 Node.js（官方 arm64 glibc 构建）。 */
    private String testNode(File base, File rootfs, String libDir, StringBuilder sb) {
        try {
            File nodeBin = new File(rootfs, "opt/" + NODE_DIR + "/bin/node");
            if (!(nodeBin.exists() && nodeBin.length() > 0)) {
                String space = spaceProblem(base, TOTAL_STAGE_BYTES);
                if (space != null) return fail(sb, space);

                File archive = new File(base, NODE_DIR + ".tar.gz");
                // 体积不对的归档直接丢掉：半截包解出来的 Node 是坏的
                if (archive.exists() && archive.length() < NODE_MIN_BYTES) {
                    progress(sb, "  ⚠️ 本地 Node 归档偏小（" + archive.length()
                            + " 字节）→ 丢掉重下\n");
                    Env.deleteRecursively(archive);
                }
                if (!(archive.exists() && archive.length() >= NODE_MIN_BYTES)) {
                    String[] urls = Region.nodeUrls(this);
                    boolean ok = false;
                    for (int i = 0; i < urls.length && !ok; i++) {
                        try {
                            progress(sb, "  下载 Node（源 " + (i + 1) + "/" + urls.length + "）: "
                                    + urls[i] + "\n");
                            download(urls[i], archive, NODE_MIN_BYTES);
                            ok = true;
                        } catch (Throwable t) {
                            progress(sb, "  该源失败: " + t.getMessage() + "\n");
                            Env.deleteRecursively(new File(archive.getAbsolutePath() + ".part"));
                            Env.deleteRecursively(archive);
                        }
                    }
                    if (!ok) return fail(sb, "Node 下载失败（" + urls.length + " 个源都不行）");
                }
                progress(sb, "  解压 Node 到容器 /opt …\n");
                // 先清掉可能存在的半个 Node，再解压（否则会混出"有 bin/node 但缺库"的目录）
                Env.deleteRecursively(new File(rootfs, "opt/" + NODE_DIR));
                extractTarGz(archive, new File(rootfs, "opt"));
            } else {
                sb.append("  Node 已存在，跳过\n");
            }

            String res = exec(new String[]{
                    new File(base, "proot").getAbsolutePath(),
                    "-r", rootfs.getAbsolutePath(), "-0", "-w", "/",
                    "-b", "/dev", "-b", "/proc", "-b", "/sys",
                    "/usr/bin/env", "-i", "HOME=/root",
                    "PATH=/opt/" + NODE_DIR + "/bin:/usr/local/bin:/usr/bin:/bin",
                    "/opt/" + NODE_DIR + "/bin/node", "--version"}, libDir);
            boolean ok = res.contains(NODE_VERSION);
            sb.append("  输出 : ").append(oneLine(res)).append('\n');
            sb.append("  结果 : ").append(ok ? "✅ PASS — Node 在容器内可运行" : "❌ FAIL").append('\n');
            if (!ok) {
                /*
                 * 解压出来的 Node 跑不起来 → 把它和归档一起丢掉。
                 * 不丢的话下一次重试看到 bin/node 存在就"跳过"，永远好不了。
                 */
                Env.deleteRecursively(new File(rootfs, "opt/" + NODE_DIR));
                Env.deleteRecursively(new File(base, NODE_DIR + ".tar.gz"));
                return fail(sb, "Node 解压后在容器里跑不起来（已丢弃，下次重试会重新下载）");
            }
            return sb.toString();
        } catch (Throwable t) {
            Log.e(TAG, "test8 failed", t);
            return sb + "  结果 : ❌ FAIL — " + t.getClass().getSimpleName() + ": " + t.getMessage() + "\n";
        }
    }

    /** TEST 9：在容器里 npm 安装 DSH，并验证 CLI 能加载。 */
    private String testDsh(File base, File rootfs, String libDir, StringBuilder sb) {
        try {
            File dshBin = new File(rootfs, "opt/" + NODE_DIR + "/bin/dsh");
            if (!(dshBin.exists())) {
                // 容器内 DNS：Ubuntu base 的 /etc/resolv.conf 是空的，glibc 无法解析域名。
                // 第一顺位按**地区**选（大陆走阿里 DNS，海外走 Google/Cloudflare），后面几个兜底。
                File resolv = new File(rootfs, "etc/resolv.conf");
                try (OutputStream os = new FileOutputStream(resolv)) {
                    os.write(Region.resolvConf(this).getBytes(StandardCharsets.UTF_8));
                }
                progress(sb, "  已写入容器 resolv.conf（按地区: " + Region.label(this) + "）\n");

                String space = spaceProblem(base, TOTAL_STAGE_BYTES);
                if (space != null) return fail(sb, space);

                String nodePath = "/opt/" + NODE_DIR + "/bin";
                progress(sb, "  npm install -g @deepseek-ai/dsh（包较多，可能 5-15 分钟，请勿锁屏）…\n");

                /*
                 * npm 失败时**必须能看到它自己说了什么**。
                 *
                 * 实测有用户的手机上 npm 直接以退出码 217 失败（一个不常见的码），
                 * 而没有 npm 的输出就无从判断是网络、缓存损坏、还是 Node 本身有问题。
                 * 这里：最多试 3 次（每次之间清掉 npm 缓存与半成品），
                 * 换一次官方源；都失败就把 npm 最后几十行原样显示出来。
                 */
                int code = -1;
                boolean ok = false;
                for (int attempt = 1; attempt <= 3 && !ok; attempt++) {
                    if (attempt > 1) {
                        progress(sb, "  ⚠️ 第 " + (attempt - 1) + " 次失败（退出码 " + code
                                + "），清理缓存后重试…\n");
                        Env.deleteRecursively(new File(rootfs, "root/.npm"));
                        Env.deleteRecursively(new File(rootfs,
                                "opt/" + NODE_DIR + "/lib/node_modules/@deepseek-ai"));
                    }
                    /*
                     * 主源按地区选（大陆 = npmmirror，海外 = npmjs.org），
                     * 第 3 次强制换成另一个 —— 镜像偶发缺包/损坏时这是唯一的出路。
                     */
                    String[] regs = Region.npmRegistries(this);
                    String registry = TEMP_BROKEN_REGISTRY != null ? TEMP_BROKEN_REGISTRY
                            : (attempt >= 3 ? regs[1] : regs[0]);
                    try {
                        code = execStreaming(new String[]{
                                new File(base, "proot").getAbsolutePath(),
                                "-r", rootfs.getAbsolutePath(), "-0", "-w", "/root",
                                "-b", "/dev", "-b", "/proc", "-b", "/sys",
                                "/usr/bin/env", "-i",
                                "HOME=/root",
                                "PATH=" + nodePath + ":/usr/local/bin:/usr/bin:/bin",
                                "npm_config_registry=" + registry,
                                "npm_config_cache=/root/.npm",
                                "npm_config_fetch_timeout=60000",
                                "npm_config_fetch_retries=3",
                                "npm_config_update_notifier=false",
                                nodePath + "/npm", "install", "-g", "@deepseek-ai/dsh",
                                "--no-audit", "--no-fund", "--loglevel=http"},
                                libDir, sb, NPM_TIMEOUT_MS, NPM_IDLE_MS);
                    } catch (java.util.concurrent.TimeoutException te) {
                        progress(sb, "  ❌ npm 卡死，已中止：" + te.getMessage() + "\n");
                        progress(sb, "     常见原因：容器内 DNS 不通 / 网络被限制。可以点右上角「重试」重来。\n");
                        return sb + "  结果 : ❌ FAIL — npm install 卡死（" + te.getMessage() + "）\n";
                    }
                    progress(sb, "  npm 退出码: " + code + "（源: " + registry + "）\n");
                    ok = dshBin.exists();
                }

                if (!ok) {
                    /*
                     * 把 npm 的真实输出摊开 —— 这是唯一能定位 217 这种东西的办法。
                     * 同时跑一个最小的 node 自检：如果连 `node -e` 都起不来，
                     * 那就是这台手机的 Node 有问题，而不是 npm / 网络。
                     */
                    progress(sb, "  ── npm 最后输出 ──\n" + execTailText() + "\n");
                    String nodeCheck;
                    try {
                        nodeCheck = oneLine(exec(new String[]{
                                new File(base, "proot").getAbsolutePath(),
                                "-r", rootfs.getAbsolutePath(), "-0", "-w", "/root",
                                "-b", "/dev", "-b", "/proc", "-b", "/sys",
                                "/usr/bin/env", "-i", "HOME=/root",
                                "PATH=" + nodePath + ":/usr/local/bin:/usr/bin:/bin",
                                nodePath + "/node", "-e",
                                "console.log('NODE_OK', process.version, process.arch)"},
                                libDir));
                    } catch (Throwable t) {
                        nodeCheck = "自检失败: " + t;
                    }
                    progress(sb, "  ── node 自检 ──\n    " + nodeCheck + "\n");
                    StringBuilder hint = new StringBuilder();
                    if (!nodeCheck.contains("NODE_OK")) {
                        hint.append("     · 连 `node -e` 都跑不起来 → 这台手机的容器里 Node 无法运行\n");
                    } else {
                        hint.append("     · Node 本身正常 → 问题在 npm 下载/解包（网络或镜像）\n");
                    }
                    hint.append("     · 可以点右上角「重试」；长按「重试」可清空容器完全重来\n");
                    progress(sb, hint.toString());
                }
            } else {
                sb.append("  dsh 已安装，跳过 npm install\n");
            }

            if (!dshBin.exists()) {
                return sb + "  结果 : ❌ FAIL — npm 安装后仍找不到 dsh\n";
            }

            String nodePath = "/opt/" + NODE_DIR + "/bin";
            String[] common = {
                    new File(base, "proot").getAbsolutePath(),
                    "-r", rootfs.getAbsolutePath(), "-0", "-w", "/root",
                    "-b", "/dev", "-b", "/proc", "-b", "/sys",
                    "/usr/bin/env", "-i", "HOME=/root",
                    "PATH=" + nodePath + ":/usr/local/bin:/usr/bin:/bin"};

            String ver = exec(concat(common, new String[]{nodePath + "/dsh", "--version"}), libDir);
            sb.append("  dsh --version : ").append(oneLine(ver)).append('\n');

            // --dump-default-config 会完整组合 profile 树，但不监听端口、不联网、不需要 API Key，
            // 是验证"DSH 真能在本机跑起来"的最佳无副作用探针
            String dump = exec(concat(common, new String[]{
                    nodePath + "/dsh", "--profile", "web", "--dump-default-config"}), libDir);
            boolean composed = dump.contains("dsh-base") || dump.contains("dsh-web-app") || dump.contains("id:");
            sb.append("  profile 组合 : ").append(composed
                    ? "✅ 成功（输出 " + dump.length() + " 字符）"
                    : "⚠️ 输出异常: " + oneLine(dump)).append('\n');

            boolean ok = dshBin.exists();
            sb.append("  结果 : ").append(ok ? "✅ PASS — DSH 已装入手机并可在容器内运行" : "❌ FAIL").append('\n');
            return sb.toString();
        } catch (Throwable t) {
            Log.e(TAG, "test9 failed", t);
            return sb + "  结果 : ❌ FAIL — " + t.getClass().getSimpleName() + ": " + t.getMessage() + "\n";
        }
    }

    private String[] concat(String[] a, String[] b) {
        String[] r = new String[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    /** 边跑边把输出写进报告（长时间命令用，便于 PC 侧轮询进度）。 */
    private int execStreaming(String[] cmd, String ldLibraryPath, StringBuilder sb) throws Exception {
        return execStreaming(cmd, ldLibraryPath, sb, EXEC_TIMEOUT_MS, EXEC_IDLE_MS);
    }

    /**
     * 边跑边把输出写进报告与界面，并带**超时保护 + 心跳**。
     *
     * 为什么必须有超时（这是"首次使用卡在『正在重启容器内的 DSH…』"的元凶）：
     * 首次初始化最重的一步是容器内的 `npm install -g @deepseek-ai/dsh`，
     * 它依赖容器里的 DNS 与外网。这条命令原来**完全没有超时** ——
     * DNS / registry 一旦不通，`readLine()` 永远等不到 EOF，
     * 整个初始化线程永久挂住，界面就停在最后一句话上再也不动。
     *
     * 现在：总时长超过 timeoutMs，或连续 idleMs 没有任何输出 →
     * 杀掉进程 + 清理容器残留，并抛 TimeoutException 让上层报错、允许重试。
     * 同时每 15 秒打一行心跳，让用户看得出"还在动"。
     */
    private int execStreaming(String[] cmd, String ldLibraryPath, StringBuilder sb,
                              long timeoutMs, long idleMs) throws Exception {
        ProcessBuilder pb = buildProcess(cmd, ldLibraryPath);
        final Process p = pb.start();
        final long startedAt = System.currentTimeMillis();
        sLastOutputAt = startedAt;
        final boolean[] timedOut = {false};
        final String[] why = {""};

        Thread watchdog = new Thread(() -> {
            long lastBeat = System.currentTimeMillis();
            try {
                // 走 Proc：API 24/25 上没有 Process#isAlive —— 以前这里会抛
                // NoSuchMethodError，被下面的 catch(Throwable) 吞掉，
                // 于是"卡死判定"在老机器上**永久失效**（见 Proc 的类注释）
                while (Proc.isAlive(p)) {
                    Thread.sleep(2000);
                    long now = System.currentTimeMillis();
                    long total = now - startedAt;
                    long idle = now - sLastOutputAt;
                    if (total > timeoutMs || idle > idleMs) {
                        timedOut[0] = true;
                        why[0] = total > timeoutMs
                                ? "总时长超过 " + (timeoutMs / 60000) + " 分钟"
                                : "已有 " + (idle / 1000) + " 秒没有任何输出";
                        liveProgress("  ⏱ 判定卡死（" + why[0] + "），正在强制终止…\n");
                        p.destroy();
                        Env.killStaleDsh(Env.base(MainActivity.this));
                        return;
                    }
                    if (now - lastBeat >= HEARTBEAT_MS) {
                        lastBeat = now;
                        liveProgress("  … 仍在进行（已 " + fmtDuration(total) + "）\n");
                    }
                }
            } catch (InterruptedException ignore) {
                // 正常结束（主线程会 interrupt 看门狗）
            } catch (Throwable t) {
                Log.w(TAG, "watchdog error", t);
            }
        }, "exec-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int n = 0;
            while ((line = br.readLine()) != null) {
                sLastOutputAt = System.currentTimeMillis();
                n++;
                if (n <= 400 || n % 20 == 0) {        // 限制写入量，避免报告爆炸
                    appendReport("    | " + line + "\n");
                }
                // 保留最后若干行 —— 失败时要把它原样摊给用户看
                synchronized (sExecTail) {
                    sExecTail.addLast(line);
                    while (sExecTail.size() > EXEC_TAIL_MAX) sExecTail.removeFirst();
                }
                // 像报错的行实时显示到界面上，否则用户只能干等
                if (looksLikeErrorLine(line)) {
                    liveProgress("    ↳ " + line + "\n");
                }
            }
        } catch (Throwable readErr) {
            /*
             * 看门狗 destroy() 之后，这里的 readLine() 会抛
             * "InterruptedIOException: read interrupted by close() on another thread"。
             * 那是**我们主动杀进程**造成的，不是真的错误 —— 必须换成
             * 人话的 TimeoutException，否则用户（和日志）看到的是一串 Java 异常，
             * 完全不知道是"卡死超时"。
             */
            if (timedOut[0]) {
                throw new java.util.concurrent.TimeoutException("命令卡死：" + why[0]);
            }
            throw readErr;
        }
        int code = p.waitFor();
        watchdog.interrupt();
        if (timedOut[0]) {
            throw new java.util.concurrent.TimeoutException("命令卡死：" + why[0]);
        }
        return code;
    }

    // ── Phase 1c/2：dsh web 的启动与 URL 解析已移到 DshService ──
    // （原因：进程必须属于前台服务才能在 App 退到后台后存活。
    //   Activity 只负责启动服务并轮询 DshService.getUrl()。）
    //
    // 原来这里还有一个 extractLoopbackUrl()：只要看到 127.0.0.1 就抓。
    // 它已经没有任何调用者，而且那个"看到回环就抓"的思路正是
    // "插件面板把界面顶掉"事故的根源 —— 现在统一由 UrlPick 负责（只认带 token 的
    // DSH 地址），所以把这个死方法删掉，免得以后有人又把它捡回去用。

    /**
     * 显示「已就绪」之后、开始收尾之前的停顿。
     *
     * <p>为什么要专门停这一下（用户要求）：原来是在 {@link #onServerReady} 里
     * 直接让 WebView 变可见 + 载入页面，然后才想起播收尾 —— 页面一瞬间就盖在
     * 开屏上面，**收尾那段倒放（直角→矩形→空心线→点）根本来不及被看见**。
     * 现在：先让读数停在「已就绪 100%」半秒，再播收尾，收尾播完才把页面交出来。
     */
    private static final long READY_HOLD_MS = 500L;

    private void onServerReady(String url) {
        runOnUiThread(() -> {
            statusBar.setText("DSH 运行中 · 127.0.0.1:" + Env.DSH_PORT);
            output.setVisibility(View.GONE);
            /*
             * ★ 这里**先不显示 WebView、也先不 loadUrl**。
             *
             * 踩过的坑：原来一上来就 `webView.setVisibility(VISIBLE) + loadUrl(url)`，
             * 页面立刻盖住开屏 —— 收尾倒放虽然代码在跑（日志里 t 确实从 1.65 退回 0），
             * 但**肉眼完全看不到**，用户反馈"根本就没有发生这个过程"。
             * 现在把"交棒"推迟到收尾播完之后，收尾才是真的看得见。
             */
            final TdshSplashView v = splash;
            final Runnable handOver = () -> {
                webView.setVisibility(View.VISIBLE);
                webView.loadUrl(url);
                appendReport("  WebView 加载: " + url + "\n");
            };

            if (v == null) {
                handOver.run();
                restoreWindowChrome();
                hideTopBar();
                return;
            }

            v.setProgress(1f);                    // 读数补满：已就绪 / 100.0%
            final boolean[] done = {false};
            final Runnable finish = () -> {
                if (done[0]) return;
                done[0] = true;
                // 顺序要紧：先让页面可见，再收开屏。
                // dismissSplash() 会按"webView 是否可见"决定要不要把顶部状态栏亮出来；
                // 若反过来，它会以为"不是正常就绪"而把那条栏亮回来，闪一下。
                handOver.run();
                dismissSplash();
            };

            // 让"已就绪"先停一下，再开始倒放
            final long shown = SystemClock.uptimeMillis() - splashShownAt;
            final long wait = Math.max(READY_HOLD_MS, SPLASH_MIN_MS - shown);
            v.postDelayed(() -> v.collapse(finish), wait);
            v.postDelayed(finish, wait + 2600);   // 兜底：收尾回调没来也强制交棒

            restoreWindowChrome();   // 开屏收起，系统栏交还系统
            hideTopBar();
        });
    }

    /** 就绪后把整条状态栏收起 —— 它常年挡在最上方影响视野。 */
    private void hideTopBar() {
        if (topBar != null) {
            topBar.animate().alpha(0f).setDuration(300).withEndAction(() -> {
                if (topBar != null) topBar.setVisibility(View.GONE);
            }).start();
        }
    }

    /**
     * 收起开屏动画（淡出）。**幂等**，可以从任何路径调用。
     *
     * <h3>为什么"一定能被收起"是硬要求</h3>
     * 这块动画盖住的正是启动日志与「重试」按钮。如果启动失败而它没收起，
     * 用户就卡在一个永远在转的开屏上 —— 比看到一屏报错还糟。
     * 所以它至少有四条退路：
     * <ol>
     *   <li>服务就绪（{@link #onServerReady}）；</li>
     *   <li>启动失败 / 亮出日志面板时（见 onPluginBootFailed 等处）；</li>
     *   <li>用户**点一下**屏幕（本类构造函数里注册的点击）；</li>
     *   <li>兜底超时：即便上面都没发生，{@link #SPLASH_MAX_MS} 之后自动收起。</li>
     * </ol>
     */
    private void dismissSplash() {
        if (splash == null) return;
        final TdshSplashView v = splash;
        splash = null;                       // 先置空 → 幂等（重复调用直接返回）
        // 显式停掉 60fps 心跳：视图可能还没 detached，光靠 onDetachedFromWindow 不够及时
        try { v.stopAnimating(); } catch (Throwable ignore) { }
        /*
         * 开屏期间顶部状态栏是被我们藏起来的（用户要求去掉那条栏）。
         * 现在要收起开屏了，如果**不是**因为"服务已就绪"（那种情况下
         * onServerReady 自己会把整条栏收掉），就必须把它恢复出来 ——
         * 因为「重试」按钮在这条栏上，失败时用户得能点到。
         */
        if (webView == null || webView.getVisibility() != View.VISIBLE) {
            restoreWindowChrome();
            if (topBar != null) {
                topBar.animate().cancel();
                topBar.setVisibility(View.VISIBLE);
                topBar.animate().alpha(1f).setDuration(200).start();
            }
        }
        long shown = SystemClock.uptimeMillis() - splashShownAt;
        long wait = Math.max(0L, SPLASH_MIN_MS - shown);
        Runnable hide = () -> {
            try {
                v.animate().alpha(0f).setDuration(260).withEndAction(() -> {
                    try {
                        v.setVisibility(View.GONE);
                        ViewGroup gp = (ViewGroup) v.getParent();
                        if (gp != null) gp.removeView(v);
                    } catch (Throwable ignore) { }
                }).start();
            } catch (Throwable t) {
                try { v.setVisibility(View.GONE); } catch (Throwable ignore) { }
            }
        };
        // 启动很快时也别"闪一下"：先保证露脸到最短时长
        if (wait > 0) uiHandler.postDelayed(hide, wait); else runOnUiThread(hide);
        // 兜底：无论动画/回调出什么岔子，超过上限一定移除
        uiHandler.postDelayed(this::forceRemoveSplash, SPLASH_MAX_MS);
    }

    /** 兜底移除（不依赖任何动画回调）。 */
    private void forceRemoveSplash() {
        try {
            if (splash != null) { splash.setVisibility(View.GONE); splash = null; }
        } catch (Throwable ignore) { }
    }

    // ── 插件把前端卡死时的自救（配合 patch.js 的 bootWatchdog）──────────

    private static final String KEY_BOOT_FAIL = "boot_fail_count";

    /** 自动自救（停用第三方插件）已经做过几次 —— 用来防止"停用→重启"无限打转。 */
    private static final String KEY_AUTO_FIX = "auto_fix_count";

    /**
     * 自动自救次数上限。
     *
     * <p>为什么必须有上限：如果界面起不来**不是因为插件**（容器坏了、网络断了、
     * 版本不匹配……），那"停用插件 + 重启"永远不会成功 —— 没有上限就是无限重启，
     * 用户会看到界面一直闪、插件一个个消失。超过上限就停止自动动手，
     * 改成亮面板把决定权交回用户，并提示发日志。
     */
    private static final int AUTO_FIX_MAX = 3;

    /**
     * 用户点过「显示界面」之后，本次运行不再对前端自检做任何自动动作。
     *
     * <p>为什么必须是"用户说了算"：自检靠的是文案特征，判定权本就不该高于用户 ——
     * 一旦判错（会话里引用了启动报错、DSH 改了启动卡结构…），
     * 用户唯一的出路就只剩卸载重装。给他一个"我不认这个判定"的按钮，
     * 代价只是"这次不自动停插件"，比把插件摘光、界面还进不去要轻得多。
     */
    private volatile boolean suppressBootWatchdog = false;

    private int autoFixCount() {
        try {
            return getSharedPreferences(PhoneBridge.PREFS, MODE_PRIVATE).getInt(KEY_AUTO_FIX, 0);
        } catch (Throwable t) { return 0; }
    }

    private void setAutoFixCount(int n) {
        try {
            getSharedPreferences(PhoneBridge.PREFS, MODE_PRIVATE).edit()
                    .putInt(KEY_AUTO_FIX, n).apply();
        } catch (Throwable ignore) { }
    }

    private int bootFailCount() {
        try {
            return getSharedPreferences(PhoneBridge.PREFS, MODE_PRIVATE).getInt(KEY_BOOT_FAIL, 0);
        } catch (Throwable t) { return 0; }
    }

    private void setBootFailCount(int n) {
        try {
            getSharedPreferences(PhoneBridge.PREFS, MODE_PRIVATE).edit()
                    .putInt(KEY_BOOT_FAIL, n).apply();
        } catch (Throwable ignore) { }
    }

    /** 逗号连起来，给对话框用。 */
    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) { if (sb.length() > 0) sb.append("、"); sb.append(s); }
        return sb.length() == 0 ? "（无）" : sb.toString();
    }

    /** 官方三件套 + 自带插件 —— 这些不该被"自救"停掉（名单在 Env，只有一份）。 */
    private static boolean isProtectedBundle(String name) {
        return Env.safeBundles().contains(name);
    }

    /** profile 里**不是**官方/自带的那些（也就是用户自己装的第三方插件）。 */
    /**
     * 该被停用的第三方插件 —— **从磁盘上发现，而不是只看 profile.bundles**。
     *
     * <p>为什么改（真机事故）：肇事插件 `dsh-agnes-multimodal-fixed` 不在 bundles 里，
     * 于是"候选为空 → 停用 0 个 → 撞上自动自救上限 → 放弃"，
     * 用户就一直卡在"Failed to load plugins"那屏出不来。
     * 现在交给 {@link Env#discoverThirdParty} 把 bundles / cordis.patch.yml /
     * node_modules 三处一起扫，并排除官方包与自带插件。
     */
    private List<String> thirdPartyBundles() {
        List<String> out = new java.util.ArrayList<>();
        try {
            for (String b : Env.discoverThirdParty(this)) {
                if (!isProtectedBundle(b)) out.add(b);
            }
        } catch (Throwable ignore) { }
        return out;
    }

    /*
     * ── "这段失败原文里点名了哪个插件"以前是两个私有方法写在这里 ──
     *
     * 现在搬到了 {@link PluginMsg}（纯逻辑，可以用 tools/PluginMsgSelfTest.java
     * 拿真机日志跑回归）。原因是一个真实的漏判：
     * DSH 0.2 的启动卡打出来的是 `dsh-pet: import failed (see console …)`，
     * 而旧正则要求冒号后面**直接**是 pending/failed/error —— 认不出 "import failed"，
     * 于是 App 当成"没点名"，退化成"停掉全部第三方插件"：
     * 用户真机上 8 个插件被一次性摘掉，界面反而再也进不去。
     */

    /**
     * 前端自检报告"插件把界面卡住了"时的自救入口。
     *
     * <h3>为什么这件事必须由 App 做</h3>
     * 前端卡死时用户**什么都点不到** —— 状态栏在"服务就绪"后已经被收起，
     * WebView 里是一屏白或一屏报错，唯一出路是卸载重装（容器 150MB 白下）。
     * 所以这里要：亮回顶栏 → 说清原因 → 给一键"停用坏插件 / 安全模式"的出口。
     *
     * <p>连续两次失败就**自动**进安全模式：坏插件不会自己好，
     * 让用户在同一个坑里反复点「重试」是最糟的体验。
     */
    /**
     * 把插件传来的路径限制在**共享存储**里（容器里的 /sdcard 就是它）。
     *
     * <p>为什么要这么小心：面板可以列目录，而 App 自己的私有目录（含容器、API Key、
     * 白名单）就在同一个进程能摸到的地方。规范化之后做前缀校验，
     * 目录穿越（`../`）与绝对路径越界都会被挡掉。
     *
     * @return 可用的目录/文件；越界或非法返回 null
     */
    private static File resolveSharedDir(String path) {
        try {
            File ext = android.os.Environment.getExternalStorageDirectory();
            String rootPath = ext.getCanonicalPath();
            if (path == null || path.trim().isEmpty()) return ext;
            String p = path.trim();
            // 容器视角的几种写法都归一到共享存储根
            if (p.equals("/sdcard") || p.equals("/") || p.equals(rootPath)) return ext;
            if (p.startsWith("/sdcard/")) p = p.substring("/sdcard".length());
            else if (p.startsWith("/storage/emulated/0/")) p = p.substring("/storage/emulated/0".length());
            else if (p.startsWith(rootPath)) p = p.substring(rootPath.length());
            File f = new File(ext, p);
            String canon = f.getCanonicalPath();
            if (!canon.equals(rootPath) && !canon.startsWith(rootPath + File.separator)) return null;
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 前端自检报告"插件把界面卡住了"时的自救入口。
     *
     * <h3>为什么这件事必须由 App 做</h3>
     * 前端卡死时用户**什么都点不到** —— 状态栏在"服务就绪"后已经被收起，
     * WebView 里是一屏白或一屏报错，唯一出路是卸载重装（容器 150MB 白下）。
     *
     * <h3>现在的行为：进不去就**自动**把第三方插件停掉</h3>
     * 第一次上报就动手，不等用户点（用户要的就是这个）：
     *   · 上报里点名了某个插件 → 只停它；
     *   · 没点名（例如"空白 25 秒"）→ 停掉全部第三方插件；
     *   然后自动重启 dsh。停用的清单记在 App 的 prefs 里，
     *   可以从「手机权限 → 插件安全模式」一键恢复。
     *
     * <p>自动动手有上限（{@link #AUTO_FIX_MAX}）：如果根本不是插件的问题，
     * "停用+重启"永远救不回来，没有上限就会无限重启、插件还会一个个消失。
     * 超过上限就停止自动动手，改为亮面板把决定权交回用户。
     */
    private void onPluginBootFailed(String detail) {
        /*
         * 用户点过「显示界面」= 他已经明确表示"这次判定我不认"。
         * 那就别再自动停用插件、也别再弹面板打断他 —— 只记日志。
         * （进程级开关：重启 App 后自检重新生效。）
         */
        if (suppressBootWatchdog) {
            appendReport("  · 前端自检上报（已按用户选择忽略）\n"
                    + (detail == null || detail.isEmpty() ? "" : detail + "\n"));
            return;
        }
        appendReport("  ⚠️ 前端自检：插件可能把界面卡住了\n"
                + (detail == null || detail.isEmpty() ? "" : detail + "\n"));

        /*
         * ── 先排掉"根本不是插件的问题"：系统 WebView 太旧 ──
         *
         * 老手机（用户反馈的那台 iQOO 1 这类）出厂自带的系统 WebView 可能是
         * Chrome 6x/7x，而 DSH 的前端是按现代浏览器打包的：**语法层面**就不认识
         * （可选链 ?. 、空值合并 ?? 这些在 Chrome 80 之前直接是 SyntaxError）。
         * 这种页面永远白屏，而且我们的兼容垫片救不了 —— 垫片补的是运行时缺的
         * 全局对象，救不了解析阶段就报错的脚本。
         *
         * 所以这条分支必须在"停用插件"之前：否则用户会看到插件一个个被停掉、
         * 界面照样起不来，最后只拿到一句"问题不在插件上"，什么也做不了。
         */
        if (webViewTooOld()) {
            setBootFailCount(bootFailCount() + 1);
            appendReport("  ⚠️ 系统 WebView 太旧（Chrome/" + webViewVersion
                    + "，需要 " + MIN_WEBVIEW_MAJOR + "+），这不是插件的问题，跳过自动停用\n");
            runOnUiThread(this::showWebViewTooOldPanel);
            return;
        }

        // 同时写到界面上：用户截图/复制日志时能看到这一行
        output.append("  ⚠️ 前端自检：插件可能把界面卡住了"
                + "（profile 里登记了 " + Env.profileBundles(this).size() + " 个插件："
                + join(Env.profileBundles(this)) + "）\n");

        int fails = bootFailCount() + 1;
        setBootFailCount(fails);

        /*
         * 先解析"原文里到底点名了谁"（纯逻辑在 PluginMsg 里，有回归测试）。
         *
         * 注意"点名"分三种，动作完全不同：
         *   · suspects      —— 登记在 profile 的第三方插件，可以停；
         *   · protectedOnes —— 官方/自带插件，停它们没用、停第三方更是白折腾；
         *   · unregistered  —— 名字不在 profile 里（写在 cordis.patch.yml 里，
         *                      或已经被上一次自动停用摘掉了），只能如实告诉用户。
         */
        List<String> registered = Env.profileBundles(this);
        PluginMsg.Parsed msg = PluginMsg.parse(detail, registered, Env.safeBundles());
        List<String> suspects = msg.suspects;
        boolean namedProtected = !msg.protectedOnes.isEmpty();

        /*
         * ── 自动自救：进不去就自己把第三方插件停掉 ──
         *
         * 用户的要求是"进不去就自动禁用第三方插件"，所以**第一次失败就动手**，
         * 不再先弹面板等他点一下 —— 他此刻面对的是一屏白/一屏报错，
         * 让他先看懂面板再点按钮，本身就是负担。
         *
         * 停谁，分两档：
         *   · 报错文本里点名了的（suspects）→ 只停它们，最精准；
         *   · 没点名（例如"空白 25 秒"这种没有任何文字的上报）→ 退化为安全模式，
         *     停掉**全部**第三方插件。
         *
         * 什么时候不自作主张：
         *   · 点名的是**官方/自带**插件 → 停第三方插件对这种情况毫无帮助，
         *     只会把用户自己的插件摘掉（真机上就是这么把 8 个一起摘没的）；
         *   · profile 里压根没有第三方插件可停 → 问题不在插件上，
         *     亮面板让用户看日志、给「重试」；
         *   · 已经自动救过 AUTO_FIX_MAX 次 → 说明停了也没用，
         *     不能无限"停用→重启"，此时改成亮面板 + 让用户发日志。
         */
        List<String> autoTargets = suspects.isEmpty() ? thirdPartyBundles() : suspects;
        boolean canAuto = !namedProtected && fails <= AUTO_FIX_MAX && !autoTargets.isEmpty();

        if (canAuto) {
            int n = Env.disableBundles(this, autoTargets);
            setAutoFixCount(autoFixCount() + 1);
            logSafeModeResult("自动停用并重启", n);
            appendReport("     判定依据：" + (suspects.isEmpty()
                    ? "上报里没有点名（按安全模式处理：停掉全部第三方插件）"
                    : "上报里点名了 " + PluginMsg.join(suspects, 6))
                    + (msg.pluginRelated ? " · 文本特征：插件相关" : "") + "\n");
            final int stopped = n;
            runOnUiThread(() -> {
                android.widget.Toast.makeText(this,
                        "界面起不来，已自动停用 " + stopped + " 个第三方插件并重启"
                      + "（可在「手机权限 → 插件安全模式」恢复）",
                        android.widget.Toast.LENGTH_LONG).show();
                output.append("  🛠 自动自救：已停用 " + stopped + " 个插件 → "
                        + join(Env.disabledBundles(this))
                        + "\n     恢复入口：设置 → 手机权限 → 插件安全模式（可一键恢复被停的插件）\n");
            });
            restartDshAndWait();
            return;
        }

        runOnUiThread(() -> {
            // 顶栏在就绪后被收起来了，必须亮回来，否则这里一个可点的东西都没有
            if (topBar != null) {
                topBar.animate().cancel();
                topBar.setAlpha(1f);
                topBar.setVisibility(View.VISIBLE);
            }
            if (retryButton != null) retryButton.setVisibility(View.VISIBLE);
            // 必须收起开屏动画：它盖住的正是这块日志与「重试」
            dismissSplash();
            output.setVisibility(View.VISIBLE);
            webView.setVisibility(View.GONE);

            /*
             * 走到这里说明"自动自救"没用上或没管用，必须把**到底是哪种情况**说清楚。
             *
             * 0.2.8.4 修的地方：以前只要"没有第三方插件可停"就一口咬定
             * "问题不在插件上" —— 可是用户明明刚被我们摘光了插件，
             * 那句结论既不真、也让他无处可去（用户反馈原话：
             * "安全模式又出现问题了"）。现在四种情况分开说：
             *   · 自动救过但没用；
             *   · 点名的是官方/自带插件（这次没动你的插件）；
             *   · 第三方插件**已经全停了**（这次没再动）；
             *   · profile 里本来就没有第三方插件。
             */
            StringBuilder sb = new StringBuilder();
            List<String> alreadyDisabled = Env.disabledBundles(this);
            if (namedProtected) {
                sb.append("这次点名的是**自带/官方插件**（")
                  .append(PluginMsg.join(msg.protectedOnes, 4))
                  .append("）—— 停用第三方插件帮不上忙，所以这次没有动你的插件。\n\n");
            } else if (autoFixCount() > 0) {
                sb.append("已经自动停用第三方插件并重启过 ")
                  .append(autoFixCount()).append(" 次，界面仍然起不来 —— ")
                  .append("所以问题很可能不在插件上（容器/网络/版本不匹配都可能导致）。\n\n");
            } else if (!alreadyDisabled.isEmpty()) {
                sb.append("第三方插件**已经全部处于停用状态**（见下），界面还是没起来 —— ")
                  .append("这次没有再动任何插件。\n\n");
            } else {
                sb.append("界面没能加载出来，但 profile 里没有第三方插件可停 —— ")
                  .append("问题不在插件上。\n\n");
            }
            sb.append("DSH 0.2 改过一些内部服务名（例如 settingsScope 变成了 settings），")
              .append("为 0.1.x 写的插件会一直等一个不存在的服务，")
              .append("前端就永远停在 pending —— 那类问题会自动被停用处理掉。\n\n");
            // 把"profile 里到底登记了哪些插件"摊出来 —— 排查和自救都要靠它
            sb.append("profile 里登记了 ").append(registered.size()).append(" 个插件：")
              .append(join(registered)).append("\n\n");
            // 顺手把系统 WebView 版本也报出来：老机器上"永远白屏"很多时候就是它
            sb.append("系统 WebView：Chrome/").append(webViewVersion)
              .append(webViewMajor > 0 ? "（需要 " + MIN_WEBVIEW_MAJOR + "+）" : "").append("\n\n");
            if (jsCompatRewrite) {
                sb.append("这台机器的 WebView 偏旧，App 已经**在传输中改写**过 JS 语法"
                        + "（class static block）。如果界面还是空白，报告里会有 "
                        + "[页面console] 那几行 —— 那是页面自己报的错，"
                        + "把它「复制运行日志」发我就能定位。\n\n");
            }
            if (!msg.named.isEmpty()) {
                sb.append("上报里点名了：").append(PluginMsg.join(msg.named, 6)).append('\n');
                if (!msg.unregistered.isEmpty()) {
                    sb.append("  · ").append(PluginMsg.join(msg.unregistered, 4))
                      .append(" 不在 profile 的插件列表里 —— 可能写在 cordis.patch.yml 的 ")
                      .append("insert 里（App 停不了它），也可能是上一次自动停用已经摘掉的。\n");
                }
                sb.append('\n');
            }
            if (!alreadyDisabled.isEmpty()) {
                sb.append("已停用待恢复：").append(join(alreadyDisabled)).append("\n\n");
            }
            // patch 层里的插件：App 停不了它们，但排查时必须知道有这几行
            String patchPlugins = Env.patchLayerPlugins(this);
            if (!patchPlugins.isEmpty()) {
                sb.append("cordis.patch.yml 里还写着这些插件（App 不能自动停用，")
                  .append("要停得自己改那个文件）：").append(patchPlugins).append("\n\n");
            }
            sb.append("界面完全进不去时，点「强制停用全部第三方插件并重启」：")
              .append("它会像安全模式那样把所有第三方插件摘掉后重启（官方与自带插件不受影响，")
              .append("清单可在「手机权限 → 插件安全模式」里一键恢复）。\n\n");
            sb.append("如果你觉得是 App 判错了（界面其实能看见），点「显示界面」就能进去。")
              .append("要把日志发给开发者，点「复制日志」。");

            /*
             * 面板改成自定义布局：要放 **4 个**动作。
             *
             * 为什么把「安全模式并重启」加回来（0.2.8.4 曾经拿掉过）：
             *   当时的理由是"能走到这个面板就说明停插件没用" —— 这句话对**自动**自救成立，
             *   对**用户手动**点一下并不成立：真出现"界面完全进不去、又没被自动救回来"
             *   的情况（自检没上报 / 上报里没点名 / 已经救满 2 次），用户需要有一步
             *   "我不管了，把第三方插件全停掉"的动作。没有它，用户只能卸载重装。
             *   所以：自动路径保留全部护栏（只在确认启动卡失败时动手、点名自带插件时不动手），
             *   手动路径把决定权完全交回用户 —— 这是两件事，不该一起拿掉。
             */
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            int px = Ui.dp(this, 20);
            box.setPadding(px, Ui.dp(this, 8), px, Ui.dp(this, 12));

            ScrollView sc = new ScrollView(this);
            sc.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 280)));
            TextView tv = new TextView(this);
            tv.setText(sb.toString());
            tv.setTextSize(14);
            tv.setTextColor(Ui.TEXT);
            tv.setLineSpacing(Ui.dp(this, 3), 1f);
            sc.addView(tv);
            box.addView(sc);

            box.addView(Ui.button(this, "重试", true, v -> restartDshAndWait()));
            box.addView(Ui.button(this, "强制停用全部第三方插件并重启", false, v -> {
                int n = Env.enterSafeMode(this);
                logSafeModeResult("手动停用全部第三方插件", n);
                android.widget.Toast.makeText(this,
                        n > 0 ? "已停用 " + n + " 个第三方插件，正在重启"
                              : "没有可停用的第三方插件，正在重启界面",
                        android.widget.Toast.LENGTH_LONG).show();
                restartDshAndWait();
            }));
            box.addView(Ui.button(this, "显示界面", false, v -> showWebViewAnyway()));
            box.addView(Ui.button(this, "复制日志", false, v -> copyLogToClipboard()));

            new AlertDialog.Builder(this)
                    .setTitle("界面没能加载出来")
                    .setView(box)
                    .show();
        });
    }

    /**
     * 「显示界面」：把我们藏起来的 WebView 放回去，并且**本次运行不再自动判卡死**。
     *
     * <h3>为什么必须有这个出口</h3>
     * App 的自检只可能比 DSH 本身"更不懂" —— 它靠的是文案特征。真要判错了
     * （比如用户把启动报错粘进了对话、DSH 以后改了启动卡的结构），
     * 用户面对的是一屏日志 + 一个点不动的界面，唯一出路是卸载重装（容器 150MB 白下）。
     * 所以必须留一个"我判错了，让我进去"的按钮。
     *
     * <p>按下之后：本次进程内不再对任何上报做自动停用/弹面板（见
     * {@link #suppressBootWatchdog}），插件保持原样 —— 用户可以在 DSH 里
     * 自己看、自己修，真有问题再走「手机权限 → 插件安全模式」。
     */
    private void showWebViewAnyway() {
        suppressBootWatchdog = true;
        appendReport("  · 用户选择「显示界面」（忽略这次自检判定），本次运行不再自动停用插件\n");
        try {
            if (retryButton != null) retryButton.setVisibility(View.GONE);
            output.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            webView.requestFocus();
            if (topBar != null) {
                topBar.animate().alpha(0f).setDuration(300)
                        .withEndAction(() -> { if (topBar != null) topBar.setVisibility(View.GONE); })
                        .start();
            }
        } catch (Throwable t) {
            Log.w(TAG, "显示界面失败", t);
        }
    }

    /**
     * 把"停用插件"的结果直接写到界面上。
     *
     * <p>为什么值得专门打一行：这一步一旦没生效，用户看到的就只是
     * "重启了但界面还是坏的"，完全无从判断。这一行把三件事说清楚：
     * 停用了几个、现在 profile 里还剩谁、以后能在哪里恢复。
     */
    private void logSafeModeResult(String how, int n) {
        String line = "  [自救·" + how + "] 停用 " + n + " 个插件；"
                + "现在加载：" + join(Env.profileBundles(this))
                + "；待恢复：" + join(Env.disabledBundles(this)) + "\n";
        appendReport(line);
        output.append(line);
    }

    // ────────────────────────────────────────────────────────────────
    // 系统 WebView 版本门槛
    //
    // 用户反馈过"安卓低版本进不去，例如 iQOO 1 首次加载进不去"。这类机器上
    // 出厂自带的系统 WebView 可能是 Chrome 6x/7x，而 DSH 前端用了现代语法
    // （可选链 / 空值合并是 Chrome 80+，DSH 自己的打包目标更高），
    // 解析阶段就失败 —— 表现是**永远白屏**，我们的兼容垫片补不了这种情况
    //（垫片补运行时缺的全局对象，管不了解析错误）。
    //
    // 以前这种机器的结局是：界面白 → 前端自检上报空白 → App 判定"插件卡住"
    // → 停用插件 → 重启 → 还是白 …… 用户拿到的唯一提示是"问题不在插件上"。
    // 现在改成：先看 WebView 版本，太旧就直接说清楚 + 给一条能走的路。
    // ────────────────────────────────────────────────────────────────

    /**
     * 跑得动 DSH 前端的最低系统 WebView（Chrome 主版本）。
     *
     * <p>这个数字是"**实测**能跑起来的下限"，不是猜的：
     * 0.2.8 时定 87（垫片能补的运行时 API 大致到这一档），
     * 0.2.8.1 收到 **88** —— 因为 HONOR KOZ-AL00 / Chrome 88 的真机日志证明
     * 88 配合垫片 + 语法降级（见 {@link JsCompat}）能跑，而更低就没验证过了。
     */
    private static final int MIN_WEBVIEW_MAJOR = 88;

    /**
     * 需要**语法降级**的系统 WebView 上限：Chrome 94 起原生支持 class static block
     * （{@code static{…}}），低于它的机器要把这个语法在传输中改写掉。
     * 详见 {@link JsCompat} 的类注释（那是一次真实用户事故的根因）。
     */
    private static final int MIN_WEBVIEW_SYNTAX_MAJOR = 94;

    /**
     * 开发用的"强制开启语法降级"开关，**正式构建必须为 false**。
     *
     * <p>本机 WebView 是新的，永远不会走降级分支；要验证改写本身有没有把页面改坏，
     * 就得能强开它（配合 CDP 抓一次 served 的 bundle 看是否还含 {@code static{}）。
     */
    private static final boolean FORCE_ASSET_REWRITE = false;

    /** 是否对 JS 资源做语法降级改写（页面加载前定好，见 onCreate）。 */
    private volatile boolean jsCompatRewrite = false;

    /**
     * 从 UA 直接读到的 Chrome 主版本（onCreate 就有值）。
     *
     * <p>为什么单独存一份：{@link #webViewMajor} 要等页面加载完由页面里的 UA 回填，
     * 而**第一次**拦 JS 资源时它还是 -1 —— 报告里就会出现"Chrome/-1 不认识这个语法"
     * 这种自相矛盾的句子（真实日志里出现过）。这里存一份给报告用。
     */
    private volatile int webViewMajorFromUa = -1;

    /** 从 UA 里解析出来的 Chrome 主版本；-1 = 还没测到。 */
    private volatile int webViewMajor = -1;

    /** 系统 WebView 是不是旧到跑不动前端（版本没测到时不判定，避免误报）。 */
    private boolean webViewTooOld() {
        if (FORCE_WEBVIEW_TOO_OLD) return true;      // 仅开发期，见该常量的说明
        int v = webViewMajor;
        return v > 0 && v < MIN_WEBVIEW_MAJOR;
    }

    /**
     * 「WebView 太旧」面板：说清楚原因，并给一个能点的「去更新」。
     *
     * <p>为什么值得专做一个面板，而不是复用"界面没能加载出来"那个：
     * 那个面板的下一步动作是"停用插件 / 重试"—— 对这种情况**全是错的**。
     * 用户需要知道的是"去更新系统 WebView"，别的按钮只会让他白折腾。
     */
    private void showWebViewTooOldPanel() {
        try {
            if (topBar != null) {
                topBar.animate().cancel();
                topBar.setAlpha(1f);
                topBar.setVisibility(View.VISIBLE);
            }
            if (retryButton != null) retryButton.setVisibility(View.VISIBLE);
            // 必须收起开屏动画：它盖住的正是这块日志与「重试」
            dismissSplash();
            output.setVisibility(View.VISIBLE);
            webView.setVisibility(View.GONE);

            String pkg = webViewPackageName();
            String msg = "这台手机的系统 WebView 是 Chrome/" + webViewVersion
                    + "，DSH 的界面需要 Chrome " + MIN_WEBVIEW_MAJOR + " 以上才能解析。\n\n"
                    + "症状就是：容器装好了、日志里一切正常，界面却一直是白的"
                    + "（页面脚本在**解析阶段**就报错，我们的兼容补丁救不了这种情况）。\n\n"
                    + "怎么修：更新系统 WebView ——\n"
                    + "  · 应用商店里搜「Android System WebView」或「Chrome」更新\n"
                    + "  · 系统设置 → 应用 → 显示系统应用 → 找 " + pkg + " → 看能不能更新\n"
                    + "  · 也可以自己下一个新版 WebView / Chrome 的安装包装上，"
                    + "再到「开发者选项 → WebView 实现」里选中它\n"
                    + "  · 都不行的话，这台机器装不了这个 App 的界面，"
                    + "可以把「手机权限 → 复制运行日志」发给我确认\n\n"
                    + "（报告里已记下 WebView 版本：" + webViewVersion + "）";

            appendReport("  ❌ 系统 WebView 太旧：Chrome/" + webViewVersion
                    + "（需要 " + MIN_WEBVIEW_MAJOR + "+，包名 " + pkg + "）\n");

            new AlertDialog.Builder(this)
                    .setTitle("这台手机的系统 WebView 太旧")
                    .setMessage(msg)
                    .setPositiveButton("去更新", (d, w) -> openWebViewStorePage(pkg))
                    .setNeutralButton("仍然重试", (d, w) -> {
                        sForceFullTest = true;
                        startProvisioning("WebView 太旧但用户要求重试");
                    })
                    .setNegativeButton("复制日志", (d, w) -> copyLogToClipboard())
                    .show();
        } catch (Throwable t) {
            Log.w(TAG, "showWebViewTooOldPanel failed", t);
        }
    }

    /** 当前 WebView 提供者的包名（取不到就按 Google 的默认值）。 */
    private String webViewPackageName() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                android.content.pm.PackageInfo pi = android.webkit.WebView.getCurrentWebViewPackage();
                if (pi != null && pi.packageName != null) return pi.packageName;
            }
        } catch (Throwable ignore) { }
        return "com.google.android.webview";
    }

    /**
     * 把用户送到"能更新 WebView"的地方。
     *
     * <p>顺序是试出来的：国内 ROM（vivo/小米/华为）大多**没有 Play**，
     * 但都有自己的应用商店并响应 {@code market://}；实在不行退到"应用详情页"，
     * 那里至少能看到版本号并手动更新/卸载更新。
     */
    private void openWebViewStorePage(String pkg) {
        Intent[] tries = new Intent[]{
                new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=" + pkg)),
                new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)),
                new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + pkg)),
        };
        for (Intent it : tries) {
            try {
                startActivity(it);
                appendReport("  · 已跳转更新 WebView：" + it.getData() + "\n");
                return;
            } catch (Throwable t) {
                Log.w(TAG, "跳转失败，换下一个: " + it.getData(), t);
            }
        }
        android.widget.Toast.makeText(this,
                "这台设备上没有能打开应用商店的入口，请手动去应用商店搜 "
                        + "Android System WebView 更新",
                android.widget.Toast.LENGTH_LONG).show();
    }

    /** 改完 plugins/bundles 之后重启容器里的 dsh，并等它给出新 URL。 */
    private void restartDshAndWait() {
        try {
            final String oldUrl = dshUrl;
            sLastFailure = null;
            output.append("正在重启容器里的 DSH（按新的插件列表加载）…\n");
            DshService.requestRestart();
            new Thread(() -> awaitUrl(oldUrl), "await-restart").start();
        } catch (Throwable t) {
            onProvisionFailed(t);
        }
    }

    /** 暴露给注入脚本的极小桥：只提供"打开本 App 页面"的能力。 */
    /**
     * 当前是不是**调试构建**。
     *
     * <p>用途：所有"只在开发期有效"的钩子都靠它把关（WebView 远程调试、
     * 悬浮窗调试入口……）。正式包里这些路径一律不可达 ——
     * 用户装的是 release，不该多出任何可被第三方网页调用的能力。
     */
    private boolean isDebuggable() {
        return (getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private class AppBridge {        @android.webkit.JavascriptInterface
        public void openAppSettings() {
            runOnUiThread(MainActivity.this::showSettingsDialog);
        }

        /** 打开「手机控制」白名单页。 */
        @android.webkit.JavascriptInterface
        public void openAppControl() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(MainActivity.this, AppControlActivity.class));
                } catch (Throwable t) {
                    Log.e(TAG, "打开手机控制页失败", t);
                }
            });
        }

        /** 打开「应用白名单」页（勾选允许 DSH 操作的 App）。 */
        @android.webkit.JavascriptInterface
        public void openWhitelist() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(MainActivity.this, WhitelistActivity.class));
                } catch (Throwable t) {
                    Log.e(TAG, "打开白名单页失败", t);
                }
            });
        }

        /** 打开「插件市场」页。 */
        @android.webkit.JavascriptInterface
        public void openPluginMarket() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(MainActivity.this, PluginMarketActivity.class));
                } catch (Throwable t) {
                    Log.e(TAG, "打开插件市场失败", t);
                }
            });
        }

        /** 打开「手机权限」大类页（手机控制/白名单/插件市场/常驻服务/内核升级/桌面图标）。 */
        @android.webkit.JavascriptInterface
        public void openPhonePerm() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(MainActivity.this, PhonePermActivity.class));
                } catch (Throwable t) {
                    Log.e(TAG, "打开手机权限页失败", t);
                }
            });
        }

        /**
         * 【仅调试构建有效】直接开关悬浮窗，返回一句人话给页面。
         *
         * <p>为什么需要它：悬浮球那套入口是 {@code exported=false} 的（安全），
         * 所以 adb 那句 {@code am start-foreground-service} 会被系统拒绝
         * （实测 "Requires permission not exported from uid"）—— 开发期想验证浮层，
         * 只能从 App 内部触发。这个钩子就是那条内部路径：正式包里它什么都不做。
         *
         * <p>用法（真机 CDP）：
         * <pre>node tools\cdp.mjs "DshAndroid.debugOverlay('')"</pre>
         * 参数传 {@code 'hide'} 表示收起；其它值一律当"打开"。
         *
         * <p>这条**不碰任何数据**，只调 BallWindow 的静态入口，
         * 所以风险等价于点界面上的那个按钮。
         */
        @android.webkit.JavascriptInterface
        public String debugOverlay(String op) {
            if (!isDebuggable()) return "（正式包不提供这个接口）";
            final boolean hide = op != null && op.trim().equals("hide");
            try {
                if (hide) BallWindow.hide(MainActivity.this);
                else BallWindow.show(MainActivity.this);
                return hide ? "已请求收起悬浮窗" : "已请求打开悬浮窗"
                        + (BallWindow.canDraw(MainActivity.this) ? "" : "（但没有悬浮窗权限！）");
            } catch (Throwable t) {
                return "调用失败：" + t;
            }
        }

        /** 悬浮窗当前状态（调试用）。 */
        @android.webkit.JavascriptInterface
        public String debugOverlayState() {
            if (!isDebuggable()) return "（正式包不提供这个接口）";
            return "shown=" + BallWindow.isShown()
                    + " canDraw=" + BallWindow.canDraw(MainActivity.this)
                    + " dshUrl=" + (DshService.getUrl() == null ? "(还没就绪)" : "ok");
        }

        /**
         * 「手机文件」插件用：列出某个目录。
         *
         * <p>返回 JSON 数组：`[{name,dir,size,path}]`，目录在前、名字不区分大小写排序。
         * 路径**只允许在共享存储内**（见 resolveSharedDir）—— 这个面板是给用户挑文件用的，
         * 不该变成"浏览 App 私有目录"的后门。
         */
        @android.webkit.JavascriptInterface
        public String listFiles(String path) {
            try {
                File dir = resolveSharedDir(path);
                if (dir == null || !dir.isDirectory()) return "[]";
                File[] kids = dir.listFiles();
                if (kids == null) return "[]";
                java.util.Arrays.sort(kids, (a, b) -> {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                });
                org.json.JSONArray arr = new org.json.JSONArray();
                int n = 0;
                for (File f : kids) {
                    if (n >= 2000) break;                        // 超大目录先截断，别把面板卡死
                    if (f.getName().startsWith(".")) continue;   // 隐藏文件不列，面板保持干净
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("name", f.getName());
                    o.put("dir", f.isDirectory());
                    o.put("size", f.isDirectory() ? 0 : f.length());
                    o.put("path", f.getAbsolutePath());
                    arr.put(o);
                    n++;
                }
                return arr.toString();
            } catch (Throwable t) {
                return "[]";
            }
        }

        /**
         * 前端自检发现"插件把界面卡住了"时回调（见 tools/patch.js 的 bootWatchdog）。
         *
         * <p>带原文进来，App 从里面抠出可疑的插件包名。
         */
        @android.webkit.JavascriptInterface
        public void pluginBootFailed(final String detail) {
            runOnUiThread(() -> onPluginBootFailed(detail == null ? "" : detail));
        }

        /** 前端确认界面正常渲染了 → 把"连续启动失败"与"自动自救"计数都清零。 */
        @android.webkit.JavascriptInterface
        public void pageReady() {
            runOnUiThread(() -> {
                if (bootFailCount() != 0 || autoFixCount() != 0) {
                    setBootFailCount(0);
                    setAutoFixCount(0);
                    appendReport("  ✅ 界面正常，启动失败/自动自救计数已清零\n");
                }
                // 界面确实出来了，才是"这一版改了什么"该现身的时候
                maybeShowChangelog();
            });
        }

        /** 打开「桌面图标」配色选择页。 */
        @android.webkit.JavascriptInterface
        public void openIcons() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(MainActivity.this, IconActivity.class));
                } catch (Throwable t) {
                    Log.e(TAG, "打开图标页失败", t);
                }
            });
        }

        /** 打开「DSH 内核升级」页。 */
        @android.webkit.JavascriptInterface
        public void openUpgrade() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(MainActivity.this, UpgradeActivity.class));
                } catch (Throwable t) {
                    Log.e(TAG, "打开内核升级页失败", t);
                }
            });
        }

        /** 打开「容器常驻服务」页（CLIProxyAPI 这类本地代理配在这里）。 */
        @android.webkit.JavascriptInterface
        public void openServices() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(MainActivity.this, ServicesActivity.class));
                } catch (Throwable t) {
                    Log.e(TAG, "打开常驻服务页失败", t);
                }
            });
        }

        /**
         * 用手机上的其它应用打开一个文件 —— 弹出系统的「选择打开方式」。
         *
         * <p>为什么要由 App 来做：DSH 的「在默认程序中打开 / 打开方式」是
         * **服务端**行为（POST /open-in-app/open，让运行 dsh 的主机去拉桌面程序）。
         * 手机上主机就是容器，容器里没有桌面，所以那个按钮永远失败
         * （报"此主机没有可用的桌面"）。注入脚本会把那次请求截下来改调这里。
         *
         * @param path DSH 给的路径（手机存储的 /sdcard/... 或容器内的 /root/...）
         */
        @android.webkit.JavascriptInterface
        public void openPath(final String path) {
            runOnUiThread(() -> {
                String err = OpenWith.open(MainActivity.this, path);
                if (err != null) {
                    android.widget.Toast.makeText(MainActivity.this, err,
                            android.widget.Toast.LENGTH_LONG).show();
                }
            });
        }

        /**
         * 列出某个目录下的子目录，供注入的"工作区选择菜单"逐层浏览。
         *
         * 为什么需要它：WebView 里的 JS 无法枚举设备文件系统，
         * 而 DSH 自带的目录选择器在手机上很难用（只给一个手输路径的输入框）。
         * 通过这个桥把 App 的存储能力暴露给页面，页面就能渲染出可点击的目录菜单。
         *
         * @param path 绝对路径
         * @return JSON 数组 [{"n":"名称","p":"绝对路径"}, ...]；失败返回 []
         */
        @android.webkit.JavascriptInterface
        public String listDirs(String path) {
            try {
                File dir = new File(path);
                File[] kids = dir.listFiles();
                if (kids == null) return "[]";
                java.util.Arrays.sort(kids, (a, b) ->
                        a.getName().compareToIgnoreCase(b.getName()));
                StringBuilder sb = new StringBuilder("[");
                for (File f : kids) {
                    if (!f.isDirectory() || f.getName().startsWith(".")) continue;
                    if (sb.length() > 1) sb.append(',');
                    sb.append("{\"n\":").append(jsonStr(f.getName()))
                      .append(",\"p\":").append(jsonStr(f.getAbsolutePath())).append('}');
                }
                return sb.append(']').toString();
            } catch (Throwable t) {
                Log.w(TAG, "listDirs 失败: " + path, t);
                return "[]";
            }
        }

        private String jsonStr(String s) {
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                }
            }
            return sb.append('"').toString();
        }
    }

    // ── Phase 2：设置页与前台服务 ──────────────────────────────

    /** 设置对话框：填 DeepSeek API Key（保存在 App 私有 SharedPreferences）。 */
    private void showSettingsDialog() {
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);

        boolean ready = isContainerReady() && DshService.getUrl() != null;

        TextView tip = new TextView(this);
        tip.setTextSize(13f);
        tip.setText("填入 DeepSeek API Key（在 platform.deepseek.com 申请）。\n\n"
                + "· Key 只保存在本机 App 私有目录，不会上传到任何地方；\n"
                + "· 它是通过环境变量 DEEPSEEK_API_KEY 传给容器内的 DSH；\n"
                + (ready ? "· 保存后会自动重启容器里的 DSH 使其生效。"
                         : "· 容器还没启动，保存后会在它启动时自动生效。"));
        box.addView(tip);

        // 最近一次失败原因（有才显示）—— 普通用户拿不到 report.txt，这是唯一的线索
        if (sLastFailure != null) {
            TextView fail = new TextView(this);
            fail.setTextSize(12f);
            fail.setTextColor(Color.parseColor("#FF8A80"));
            fail.setText("\n最近一次初始化失败：\n" + sLastFailure);
            box.addView(fail);
        }


        // ── 删除历史对话（含归档）────────────────────────────────
        /*
         * 用户要求：范围包含**已归档**会话，是彻底清除而不是归档；
         * 操作前二次确认；完成后刷新列表并反馈结果。
         *
         * 磁盘位置（容器内 HOME=/root，见 Env）：
         *   <base>/rootfs/root/.dsh/sessions/            活跃会话
         *   <base>/rootfs/root/.dsh/dsh-session-archive/ 已归档会话
         * 两处都删，才叫"含归档的彻底清除"。
         */
        TextView delBtn = new TextView(this);
        delBtn.setText("🗑 删除历史对话（含已归档）");
        delBtn.setTextSize(13f);
        delBtn.setTextColor(Color.parseColor("#FF8A80"));
        delBtn.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = pad / 2;
        dlp.bottomMargin = pad / 4;
        box.addView(delBtn, dlp);
        delBtn.setOnClickListener(v -> confirmWipeSessions());

        final EditText input = new EditText(this);
        input.setHint("sk-...");
        input.setTextSize(14f);
        input.setText(DshService.getApiKey(this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad / 2;
        box.addView(input, lp);

        new AlertDialog.Builder(this)
                .setTitle("DeepSeek API Key")
                .setView(box)
                .setPositiveButton(ready ? "保存并重启 DSH" : "保存", (d, w) -> {
                    String k = input.getText().toString().trim();
                    DshService.setApiKey(this, k);
                    appendReport("  [设置] API Key " + (k.isEmpty() ? "已清空" : "已更新") + "\n");
                    applyApiKeyAndMaybeRestart();
                })
                .setNeutralButton("复制日志", (d, w) -> copyLogToClipboard())
                .setNegativeButton("取消", null)
                .show();
    }

    /** 把 report.txt 末尾若干行复制到剪贴板，方便用户直接发给开发者。 */
    private void copyLogToClipboard() {
        try {
            String text = readTail(reportFile, 200);
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("DSH 日志", text));
                android.widget.Toast.makeText(this, "日志已复制，可直接粘贴发给开发者",
                        android.widget.Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
            android.widget.Toast.makeText(this, "复制失败: " + t.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    // ── 删除历史对话（含已归档）─────────────────────────────────

    /** 活跃会话目录。 */
    private File sessionsDir() {
        return new File(Env.base(this), "rootfs/root/.dsh/sessions");
    }

    /** 已归档会话目录 —— 删除范围必须包含它（用户明确要求"含归档"）。 */
    private File sessionArchiveDir() {
        return new File(Env.base(this), "rootfs/root/.dsh/dsh-session-archive");
    }

    /**
     * 第二次确认。用户要求"操作前需二次确认"——因为这是**彻底清除、不可恢复**，
     * 不是归档，误点代价很大（会话记录只有这一份）。
     */
    private void confirmWipeSessions() {
        int n = countSessions();
        new AlertDialog.Builder(this)
                .setTitle("删除全部历史对话？")
                .setMessage("将**彻底删除**容器内的会话记录，包含已归档的会话。\n\n"
                        + "当前检测到 " + n + " 个会话目录。\n\n"
                        + "· 此操作不可撤销、无法恢复；\n"
                        + "· API Key、插件、白名单等设置不受影响；\n"
                        + "· 正在进行的对话会被清掉，请先结束对话。\n\n确认要删除吗？")
                .setPositiveButton("确认删除", (d, w) -> wipeSessions())
                .setNegativeButton("取消", null)
                .show();
    }

    /** 统计会话目录数（只用于提示，失败时返回 -1 表示读不到）。 */
    private int countSessions() {
        try {
            int n = 0;
            File[] a = sessionsDir().listFiles();
            if (a != null) n += a.length;
            File[] b = sessionArchiveDir().listFiles();
            if (b != null) n += b.length;
            return n;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 执行删除。放在后台线程：目录里可能有很多会话文件，主线程删会卡动画。
     * 完成后回主线程刷新列表并把结果反馈给用户（Toast + 顶部状态栏 + 报告）。
     *
     * 真正的文件操作走 {@link Wipe}（纯逻辑类），这样能离线跑真目录回归 ——
     * release 包在真机上没法用 run-as 验证（package not debuggable）。
     */
    private void wipeSessions() {
        final String[] res = new String[1];
        appendReport("  [删除] 开始删除历史对话（含归档）\n");
        new Thread(() -> {
            try {
                Wipe.Result r = Wipe.wipe(sessionsDir(), sessionArchiveDir());
                res[0] = "已删除 " + r.toString();
            } catch (Throwable t) {
                res[0] = "删除失败：" + t;
                android.util.Log.w("DSH_UI", "删除历史对话失败", t);
            }
            runOnUiThread(() -> {
                appendReport("  [删除] " + res[0] + "\n");
                if (statusBar != null) statusBar.setText("Tdsh · " + res[0]);
                android.widget.Toast.makeText(this, res[0], android.widget.Toast.LENGTH_LONG).show();
                // 刷新列表：重载页面，让 DSH 重新读取（此时会话目录已空）
                refreshConversationList();
            });
        }, "dsh-wipe-sessions").start();
    }

    /**
     * 刷新会话列表。
     *
     * 直接 reload 最稳：会话列表是 DSH 前端在加载时读的，
     * 不重载的话界面还显示已经删掉的条目（用户会以为没生效）。
     */
    private void refreshConversationList() {
        try {
            if (webView != null) {
                webView.reload();
                appendReport("  [删除] 已重载页面以刷新会话列表\n");
            }
        } catch (Throwable t) {
            android.util.Log.w("DSH_UI", "刷新会话列表失败", t);
        }
    }

    /** 读文件末尾 maxLines 行（文件可能很大，从尾部倒着读）。 */
    private String readTail(File f, int maxLines) throws Exception {
        if (f == null || !f.exists()) return "(没有日志)";
        java.util.ArrayDeque<String> lines = new java.util.ArrayDeque<>();
        try (BufferedReader br = new BufferedReader(new java.io.FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                lines.addLast(line);
                if (lines.size() > maxLines) lines.removeFirst();
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }

    /**
     * 让新的 API Key 生效 —— **只在容器真的在跑的时候才重启**。
     *
     * 原来的实现无条件 `requestRestart()` + 把界面切成"重启中"，
     * 于是首次运行时（容器还没装好）用户一保存 Key，界面就进入
     * "已保存设置，正在重启容器内的 DSH…" 且再也回不来 —— 这就是
     * 新人手机上"卡住"的直接原因。
     *
     * 现在：容器没跑就只保存（DshService.startDsh() 每次启动都会重新读 Key），
     * 并如实告诉用户；容器在跑才走原来的重启流程。
     *
     * 另外：这里**不**用 stopService/startService。实测那样做有两个问题：
     *  · stop 与 start 之间只隔 1.2s，系统会把两次请求合并成同一个实例，重启落空；
     *  · 即使停掉，旧 proot 的 tracee（node/dsh）会变成占着 3080 端口的孤儿进程。
     * 改为给前台服务置一个"重启标记"，由它自己的监督循环完成清理与重启。
     */
    private void applyApiKeyAndMaybeRestart() {
        boolean running = DshService.isAlive() || DshService.getUrl() != null;
        if (!running) {
            // 容器还没跑起来：不重启、也不切界面，避免把自己锁在"重启中"上
            liveProgress("  · 已保存，容器首次启动时会自动使用该 Key\n");
            android.widget.Toast.makeText(this, "已保存，容器启动后自动生效",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        final String oldUrl = dshUrl;
        dshUrl = null;
        runOnUiThread(() -> {
            webView.setVisibility(View.GONE);
            output.setVisibility(View.VISIBLE);
            // 用 append 而不是 setText —— 否则会把已有的进度/日志全擦掉
            output.append("\n已保存设置，正在重启容器内的 DSH…\n");
            statusBar.setText("Tdsh · 重启中…");
        });
        DshService.requestRestart();
        new Thread(() -> awaitUrl(oldUrl), "await-restart").start();
    }

    /** 轮询前台服务解析出的 URL，就绪后交给 WebView。 */
    private void awaitUrl() {
        awaitUrl(null);
    }

    /**
     * @param previousUrl 上一次已加载的 URL。重启后 token 会变，
     *                    所以必须等一个**不同的** URL，否则会立刻把旧页面当成新的。
     */
    private void awaitUrl(String previousUrl) {
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 240; i++) {
            String u = DshService.getUrl();
            if (u != null && !u.equals(previousUrl)) {
                dshUrl = u;
                onServerReady(u);
                appendReport("  ✅ 服务就绪（前台服务）: " + u + "\n");
                return;
            }
            /*
             * 20 秒还没看到前台服务被创建，就不用再等 4 分钟了 ——
             * 这说明 startForegroundService 根本没生效（被系统拦截），
             * 直接给出确切原因，比笼统的"超时"有用得多。
             */
            if (i == 20 && !DshService.isCreated()) {
                onProvisionFailed(new IllegalStateException(
                        "前台服务没有启动起来（系统可能拦截了后台服务）。\n"
                      + "   · 请到系统设置里允许本应用「自启动 / 后台运行」后点「重试」"));
                return;
            }
            /*
             * ★ 每一圈都要上报进度（不能只在 i%5==4 时上报）。
             *
             * 踩过的坑：进度原来放在 `if (i % 5 == 4)` 里 —— 也就是**每 5 秒才更新一次**。
             * 帧号由进度算出，于是画面每 5 秒才跳一格，中间几秒完全静止，用户看到的就是
             * 「动画完全卡住了」（真机日志确认：帧号连续 3~4 秒不变）。
             * 现在每圈（1 秒）都报，并且把总跨度按最坏等待时间（240 秒）来分配，
             * 让它**平滑推进**而不是几步跳完。
             */
            splashProgress(0.35f + 0.64f * Math.min(1f, (i + 1) / 240f));
            if (i % 5 == 4) {
                final String st = DshService.getState();
                final long el = System.currentTimeMillis() - t0;
                runOnUiThread(() -> statusBar.setText(
                        "Tdsh · " + st + "（已 " + fmtDuration(el) + "）"));
            }
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        }
        /*
         * 超时不能是死胡同。
         *
         * 原来这里只是往日志追加一行，界面既不恢复也没有任何可点的东西 ——
         * 用户看到的就是"卡住了"，唯一出路是卸载重装。
         * 现在走 onProvisionFailed：日志区恢复可见、给出原因、亮出「重试」。
         *
         * 并且把 dsh 自己最后的输出一起带出来 —— 这才是真正能定位问题的信息
         * （端口占用 / 依赖缺失 / 启动异常都会体现在那里）。
         */
        onProvisionFailed(new java.util.concurrent.TimeoutException(
                "等待 dsh web 超时（4 分钟）\n"
              + "   · 服务状态: " + DshService.getState() + "\n"
              + "   · 端口: 127.0.0.1:" + Env.port() + "\n"
              + "   · dsh 最后输出:\n" + DshService.getRecentOutput()));
    }

    private void startDshService() {
        Intent it = new Intent(this, DshService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(it);
        else startService(it);
    }

    @Override
    protected void onResume() {
        super.onResume();
        /*
         * 前台服务发现"容器未安装完成"时会置位 sNeedsProvision。
         *
         * 原来这种情况只会往通知栏写一句"请打开 App 完成初始化"，
         * 用户回到 App 却什么都不发生 —— 看着就是卡死。
         * 现在回到前台自动补跑初始化（有互斥，不会重复起线程）。
         */
        if (DshService.getNeedsProvision() && !sProvisioning.get()) {
            startProvisioning("回到前台补跑");
            return;
        }
        /*
         * 容器在后台被重启过时（token 变了 / 端口换了），WebView 还指着旧地址，
         * 表现就是"界面连不上/一直转圈"。回到前台发现地址变了就重新加载。
         */
        String cur = DshService.getUrl();
        if (cur != null && !cur.equals(dshUrl) && !sProvisioning.get()) {
            appendReport("  · 容器地址已变化，重新加载 WebView: " + cur + "\n");
            dshUrl = cur;
            onServerReady(cur);
        }
        /*
         * 心跳：在**前台时**每 5 秒看一眼容器地址和页面内容。
         *
         * 这里是「用久了白屏」的关键补丁：以前只有 onResume 会比对地址，
         * 而用户白屏时人一直停在 App 里 —— 不切后台就永远等不到那一比对，
         * 于是界面永远连不上，只能"杀 App / 卸载重装"。
         *
         * 放在前台才跑：退回后台时前台服务在跑容器，不需要 Activity 操心。
         */
        startHeartbeat();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 退回后台（以及弹出文件选择器/相册）时停掉心跳：既省电，
        // 也避免"用户正在选文件时页面被自动重载"。
        stopHeartbeat();
    }

    @Override
    protected void onDestroy() {
        // 注意：这里**不能**杀 dsh 进程 —— 它属于前台服务。
        // 早先的版本在 onDestroy 里 destroy() 它，会导致一退出 App 容器就死。
        // 需要真正停止时用 stopService()。
        stopHeartbeat();
        super.onDestroy();
    }

    // ── 判定 ──────────────────────────────────────────────────
    private String judge(String all) {
        boolean wx = all.contains("WX_EXEC_OK");
        boolean script = all.contains("SCRIPT_EXEC_OK");
        boolean prootRuns = all.contains("安卓 bionic 二进制可在本 App 内启动");
        // TEST 7 是 Phase 1a 的权威判据：真实 glibc 容器
        boolean container = all.contains("真实 glibc 容器跑通");

        StringBuilder v = new StringBuilder();
        if (wx && script && prootRuns && container) {
            v.append("✅ Phase 1a 通过：真实 glibc 容器在 App 内跑通。\n")
             .append("   · W^X 豁免有效（targetSdk 28 生效）\n")
             .append("   · proot 二进制可运行\n")
             .append("   · Ubuntu base（glibc）rootfs 解压正确\n")
             .append("   · bash 在 proot 容器里执行成功，uid 伪装生效\n")
             .append("   → \"App 内跑 proot + Linux + Node + DSH\" 已无技术障碍，\n")
             .append("     可以进入 Phase 1b（装 Node + 装 DSH + WebView 显示界面）。\n");
        } else if (wx && script && prootRuns) {
            v.append("⚠️ 关键结论：W^X 豁免有效、proot 能启动，但真实 glibc 容器仍失败。\n")
             .append("   请看 TEST 7 的具体输出（下载/解压/运行 哪一步出的问题）。\n");
        } else if (wx && script) {
            v.append("⚠️ W^X 豁免有效（最关键的一项已通过），但 proot 环节有问题。\n");
        } else {
            v.append("❌ 无法执行私有目录里的文件（厂商 SELinux 拦截），\n")
             .append("   这条路在当前 ROM 上不可行，应改走\"手机连 PC 上的 DSH\"。\n");
        }
        return v.toString();
    }

    // ── 工具方法 ──────────────────────────────────────────────

    /**
     * 把 assets/libs 下的共享库解到私有目录，返回给链接器用的目录。
     * proot 的 .deb 写明 Depends: libandroid-shmem, libtalloc，且二者都无更深依赖，
     * 所以依赖闭环就是这两个 .so。
     */
    /**
     * 准备 proot 运行环境。
     *
     * 直接委托给 {@link Env#prepareProot}：这里曾经有一份自己的实现，
     * 但它用"原地重写"的方式安装 proot/loader，而现在前台服务随时可能在跑 proot，
     * 于是触发 **ETXTBSY (Text file busy)** → 本方法抛异常 → loaderPath 为 null
     * → 后续所有容器测试因为没有 PROOT_LOADER 而报 execve ENOENT。
     * 统一走 Env（内部用 rename 原子替换）后，两条路径行为一致。
     */
    private String prepareLibs(File base) {
        try {
            return Env.prepareProot(this, base);
        } catch (Throwable t) {
            Log.e(TAG, "prepareLibs failed", t);
            return new File(base, "lib").getAbsolutePath();
        }
    }

    private File extractAsset(String name, File dst) throws Exception {
        try (InputStream in = getAssets().open(name);
             OutputStream os = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        Os.chmod(dst.getAbsolutePath(), 0700);
        return dst;
    }

    /** 统一委托给 {@link Env}，保证自检与前台服务注入完全相同的 proot 环境变量。 */
    private ProcessBuilder buildProcess(String[] cmd, String ldLibraryPath) {
        return Env.buildProcess(Env.base(this), cmd, ldLibraryPath);
    }

    private String exec(String[] cmd) throws Exception {
        return exec(cmd, null);
    }

    /**
     * @param ldLibraryPath 传给动态链接器的库搜索路径；proot 需要它找到
     *                      libtalloc.so.2 / libandroid-shmem.so。
     *                      同时给 proot 设两个 Android 上的保险变量：
     *                      PROOT_TMP_DIR（私有可写临时目录）与 PROOT_NO_SECCOMP
     *                      （Android 对应用的 seccomp 有限制，关掉更稳）。
     */
    private String exec(String[] cmd, String ldLibraryPath) throws Exception {
        Process p = buildProcess(cmd, ldLibraryPath).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        // 这些是短命令，但同样不能无限等 —— proot 起不来时 readLine 会一直挂着。
        // Proc.waitFor：API 24/25 上没有带超时的 waitFor（见 Proc）
        boolean finished = Proc.waitFor(p, EXEC_TIMEOUT_MS);
        if (!finished) {
            p.destroy();
            Env.killStaleDsh(Env.base(this));
            return sb + "[timeout] 命令超过 " + (EXEC_TIMEOUT_MS / 1000) + " 秒未结束，已强制终止\n";
        }
        int code = p.waitFor();
        sb.append("[exitCode=").append(code).append(']');
        return sb.toString();
    }

    private void copy(File src, File dst) throws Exception {
        try (InputStream in = new FileInputStream(src);
             OutputStream os = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    private String oneLine(String s) {
        return s.replace('\n', ' ').trim();
    }
}


