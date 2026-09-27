package com.dshmobile.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
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
     * 对照实验用的"老 WebView 模拟器"：先删掉这些较新的全局对象，再看垫片能不能救回来。
     *
     * 实测结论（本机 WebView 138 上跑）：
     *   只注入删除器            → 复现朋友的报错 "Failed to load plugins"
     *   删除器 + 垫片           → 页面完全正常，垫片补了 14 项
     * 这同时证明了两件事：onPageStarted 的注入**确实早于** DSH 的插件脚本；
     * 以及垫片确实能修好这个问题。
     *
     * 正式发布时必须保持为空字符串（正常运行时垫片是"零操作"）。
     */
    private static final String SIMULATE_OLD_WEBVIEW = "";

    /** 短命令的默认超时。 */
    private static final long EXEC_TIMEOUT_MS = 120_000L;
    private static final long EXEC_IDLE_MS = 120_000L;
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

        // ── UI：顶部状态条（含设置按钮）+（日志 / DSH WebView）叠层 ──
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.parseColor("#1F2430"));
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);

        statusBar = new TextView(this);
        statusBar.setTextSize(12f);
        statusBar.setPadding(32, 26, 16, 26);
        statusBar.setTextColor(Color.WHITE);
        statusBar.setText("DeepSeek Harness · 准备中…");
        bar.addView(statusBar, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

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
        retryButton.setOnClickListener(v -> startProvisioning("手动重试"));
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

        // DSH 的 Web UI 最终显示在这里。
        // proot 不隔离网络，所以容器里的 127.0.0.1:3080 就是手机的回环地址。
        // 仅调试构建开启 WebView 远程调试（供 PC 侧用 Chrome DevTools 协议检查/试验样式）
        if ((getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
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
                if (webCompatShim != null && !webCompatShim.isEmpty()) {
                    // TEST-B：删除器 + 垫片 → 应当恢复正常
                    view.evaluateJavascript(SIMULATE_OLD_WEBVIEW + webCompatShim, null);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (mobilePatch != null) view.evaluateJavascript(mobilePatch, null);
                reportWebViewVersion(view);
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
                    Intent intent = params.createIntent();
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(intent, REQ_FILE);
                    return true;
                } catch (Throwable t) {
                    Log.w(TAG, "createIntent 失败，退化为任意文件选择", t);
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
        // （setWebViewClient 已在上面设置，用于注入窄屏适配补丁）
        webView.setVisibility(View.GONE);
        stack.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        StringBuilder head = new StringBuilder();
        head.append("DeepSeek Harness — 容器自检\n");
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
        startProvisioning("首次启动");
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
            statusBar.setText("DeepSeek Harness · 初始化中 " + fmtDuration(ms)
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
            statusBar.setText("DeepSeek Harness · 初始化失败");
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

    /** 让初始化线程自己退出：把最近一次失败原因清掉并复位 UI。 */
    private void onProvisionStarted() {
        sLastFailure = null;
        runOnUiThread(() -> {
            if (retryButton != null) retryButton.setVisibility(View.GONE);
            output.setVisibility(View.VISIBLE);
        });
    }

    private static final int REQ_STORAGE = 1001;
    private static final int REQ_FILE = 1002;

    /** 等待文件选择结果的 WebView 回调；null 表示当前没有待处理的请求。 */
    private android.webkit.ValueCallback<android.net.Uri[]> pendingFileChooser;

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
            android.net.Uri[] results = null;
            if (resultCode == RESULT_OK) {
                try {
                    results = android.webkit.WebChromeClient.FileChooserParams
                            .parseResult(resultCode, data);
                } catch (Throwable t) {
                    Log.w(TAG, "解析文件选择结果失败", t);
                }
            }
            // 用户取消时传 null，页面会收到"没有选择"，不会卡住
            cb.onReceiveValue(results);
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
        } catch (Throwable ignore) { }
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
                deleteRecursively(new File(base, "rootfs"));
                deleteRecursively(new File(base, "staticroot"));
                deleteRecursively(new File(base, "ubuntu-base-arm64.tar.gz"));
                deleteRecursively(new File(base, "ubuntu-base-arm64.tar.gz.part"));
                for (File f : new File(base, "opt").listFiles()) {
                    if (f.getName().startsWith("node-")) deleteRecursively(f);
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

    private void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
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
        r.append("TEST 8 · Phase 1b：容器内安装 Node.js\n");
        String t8 = testNode(base, rootfs, libDir, new StringBuilder()); r.append(t8).append('\n');
        appendReport("TEST 8 · 容器内安装 Node.js\n" + t8 + "\n");

        r.append("TEST 9 · Phase 1b：容器内安装并运行 DSH\n");
        String t9 = testDsh(base, rootfs, libDir, new StringBuilder()); r.append(t9).append('\n');
        appendReport("TEST 9 · 容器内安装 DSH\n" + t9 + "\n");

        // ── 手机存储访问（容器能否看到手机文件）──
        r.append("TEST 10 · 手机存储访问\n");
        String t11 = testStorage(base, libDir); r.append(t11).append('\n');
        appendReport("TEST 10 · 手机存储访问\n" + t11 + "\n");

        // ── 硬链接支持（DSH 会话日志依赖）──
        r.append("TEST 11 · 硬链接支持\n");
        String t12 = testHardLink(base, libDir); r.append(t12).append('\n');
        appendReport("TEST 11 · 硬链接支持\n" + t12 + "\n");

        // ── Phase 2：交给前台服务常驻运行，并等待 Web UI 就绪 ──
        r.append("Phase 2 · 启动前台服务并等待 dsh web\n");
        StringBuilder t10 = new StringBuilder();
        progress(t10, "  启动前台服务（App 退到后台也能存活）…\n");
        startDshService();
        runOnUiThread(() -> statusBar.setText("DeepSeek Harness · 正在启动 dsh web…"));
        awaitUrl();
        r.append(t10).append('\n');
        appendReport("Phase 2 · 前台服务启动\n" + t10 + "\n");

        r.append("── 判定 ──\n");
        String verdict = judge(r.toString());
        r.append(verdict);
        appendReport("── 判定 ──\n" + verdict);

        Log.i(TAG, "=== RESULT ===\n" + r);
        runOnUiThread(() -> {
            String cur = output.getText().toString();
            output.setText(cur.replace("检查中…\n", "") + r);
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
                        appendReport("  [WebView] Chrome/" + ver + " · 垫片补充: " + applied + "\n");
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
    private static final String[] ROOTFS_URLS = {
            // 阿里云镜像实测 1.04 MB/s，官方源 0.20 MB/s，所以国内源优先
            "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"
    };

    private String testContainer(File base, String libDir) {
        StringBuilder sb = new StringBuilder();
        try {
            File proot = new File(base, "proot");
            File rootfs = new File(base, "rootfs");
            File bash = new File(rootfs, "usr/bin/bash");

            if (!(bash.exists() && bash.length() > 0)) {
                File archive = new File(base, "ubuntu-base-arm64.tar.gz");
                if (!(archive.exists() && archive.length() > 20_000_000L)) {
                    boolean downloaded = false;
                    for (String url : ROOTFS_URLS) {
                        try {
                            progress(sb, "  下载 rootfs: " + url + "\n");
                            download(url, archive);
                            downloaded = true;
                            break;
                        } catch (Throwable t) {
                            progress(sb, "  该源失败: " + t.getClass().getSimpleName() + ": " + t.getMessage() + "\n");
                        }
                    }
                    if (!downloaded) return sb + "  结果 : ❌ FAIL — rootfs 下载失败（三个源都不通）\n";
                }
                progress(sb, "  下载完成 (" + (archive.length() / 1048576) + " MB)，开始解压（约 120MB，1-3 分钟）…\n");
                extractTarGz(archive, rootfs);
                progress(sb, "  解压完成\n");
            } else {
                sb.append("  rootfs 已存在，跳过下载解压\n");
            }

            if (!(bash.exists() && bash.length() > 0)) {
                return sb + "  结果 : ❌ FAIL — 解压后找不到 /usr/bin/bash\n";
            }

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

    /** 下载（支持重定向、断点产物、进度写报告）。 */
    /**
     * 下载文件，带**总时长上限 + 停滞检测 + 界面进度**。
     *
     * 原来的版本只有 per-read 的 60 秒超时，没有任何总时长限制，
     * 而且进度只写进报告文件（普通用户看不到）—— 于是"下载中"和"卡死"
     * 在界面上长得一模一样。现在：
     *   · 连续 {@link #DOWNLOAD_STALL_MS} 没有任何字节 → 判定停滞，中止并换源；
     *   · 总时长超过 {@link #DOWNLOAD_TIMEOUT_MS} → 中止；
     *   · 每 2MB 往界面上打一行进度，让用户看到数字在动。
     */
    private void download(String urlStr, File dst) throws Exception {
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
        } finally {
            c.disconnect();
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

    // ── TEST 8 / TEST 9 · Phase 1b：Node + DSH ─────────────────
    private static final String NODE_VERSION = "v22.23.2";
    private static final String NODE_DIR = "node-" + NODE_VERSION + "-linux-arm64";
    private static final String[] NODE_URLS = {
            // npmmirror（阿里）国内最快；其次清华；最后官方
            "https://registry.npmmirror.com/-/binary/node/" + NODE_VERSION + "/" + NODE_DIR + ".tar.gz",
            "https://mirrors.tuna.tsinghua.edu.cn/nodejs-release/" + NODE_VERSION + "/" + NODE_DIR + ".tar.gz",
            "https://nodejs.org/dist/" + NODE_VERSION + "/" + NODE_DIR + ".tar.gz"
    };

    /** TEST 8：在容器里装上 Node.js（官方 arm64 glibc 构建）。 */
    private String testNode(File base, File rootfs, String libDir, StringBuilder sb) {
        try {
            File nodeBin = new File(rootfs, "opt/" + NODE_DIR + "/bin/node");
            if (!(nodeBin.exists() && nodeBin.length() > 0)) {
                File archive = new File(base, NODE_DIR + ".tar.gz");
                if (!(archive.exists() && archive.length() > 10_000_000L)) {
                    boolean ok = false;
                    for (String url : NODE_URLS) {
                        try {
                            progress(sb, "  下载 Node: " + url + "\n");
                            download(url, archive);
                            ok = true;
                            break;
                        } catch (Throwable t) {
                            progress(sb, "  该源失败: " + t.getMessage() + "\n");
                        }
                    }
                    if (!ok) return sb + "  结果 : ❌ FAIL — Node 下载失败\n";
                }
                progress(sb, "  解压 Node 到容器 /opt …\n");
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
                // 写入公共 DNS（阿里 + DNSPod + Google 兜底）。
                File resolv = new File(rootfs, "etc/resolv.conf");
                try (OutputStream os = new FileOutputStream(resolv)) {
                    os.write("nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 8.8.8.8\n"
                            .getBytes(StandardCharsets.UTF_8));
                }
                progress(sb, "  已写入容器 resolv.conf（223.5.5.5 / 119.29.29.29）\n");

                String nodePath = "/opt/" + NODE_DIR + "/bin";
                progress(sb, "  npm install -g @deepseek-ai/dsh（包较多，可能 5-15 分钟，请勿锁屏）…\n");
                // 流式执行：输出实时写入报告，便于 PC 侧轮询进度；
                // 用 npm 专用的长超时（25 分钟总时长 / 5 分钟无输出即判卡死）。
                int code;
                try {
                    code = execStreaming(new String[]{
                            new File(base, "proot").getAbsolutePath(),
                            "-r", rootfs.getAbsolutePath(), "-0", "-w", "/root",
                            "-b", "/dev", "-b", "/proc", "-b", "/sys",
                            "/usr/bin/env", "-i",
                            "HOME=/root",
                            "PATH=" + nodePath + ":/usr/local/bin:/usr/bin:/bin",
                            "npm_config_registry=https://registry.npmmirror.com",
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
                progress(sb, "  npm 退出码: " + code + "\n");
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
                while (p.isAlive()) {
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

    /** 从一行输出里抓出 127.0.0.1 的回环 URL（含 token 查询串）。 */
    private String extractLoopbackUrl(String line) {
        Matcher m = Pattern.compile("http://127\\.0\\.0\\.1:\\d+(?:/\\?[^\\s)]+)?").matcher(line);
        return m.find() ? m.group() : null;
    }

    private void onServerReady(String url) {
        runOnUiThread(() -> {
            statusBar.setText("DSH 运行中 · 127.0.0.1:" + Env.DSH_PORT);
            output.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            webView.loadUrl(url);
            // 就绪后把整条状态栏收起 —— 它常年挡在最上方影响视野。
            // 之后进入本 App 设置的入口改到 DSH 设置页里的「API Key（本机）」。
            if (topBar != null) {
                topBar.animate().alpha(0f).setDuration(300).withEndAction(() -> {
                    if (topBar != null) topBar.setVisibility(View.GONE);
                }).start();
            }
        });
        appendReport("  WebView 加载: " + url + "\n");
    }

    /** 暴露给注入脚本的极小桥：只提供"打开本 App 页面"的能力。 */
    private class AppBridge {
        @android.webkit.JavascriptInterface
        public void openAppSettings() {
            runOnUiThread(MainActivity.this::showSettingsDialog);
        }

        /** 打开「📱 手机控制」白名单页。 */
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
            statusBar.setText("DeepSeek Harness · 重启中…");
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
            if (i % 5 == 4) {
                final String st = DshService.getState();
                final long el = System.currentTimeMillis() - t0;
                runOnUiThread(() -> statusBar.setText(
                        "DeepSeek Harness · " + st + "（已 " + fmtDuration(el) + "）"));
            }
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        }
        /*
         * 超时不能是死胡同。
         *
         * 原来这里只是往日志追加一行，界面既不恢复也没有任何可点的东西 ——
         * 用户看到的就是"卡住了"，唯一出路是卸载重装。
         * 现在走 onProvisionFailed：日志区恢复可见、给出原因、亮出「重试」。
         */
        onProvisionFailed(new java.util.concurrent.TimeoutException(
                "等待 dsh web 超时（4 分钟）· 容器状态: " + DshService.getState()));
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
        }
    }

    @Override
    protected void onDestroy() {
        // 注意：这里**不能**杀 dsh 进程 —— 它属于前台服务。
        // 早先的版本在 onDestroy 里 destroy() 它，会导致一退出 App 容器就死。
        // 需要真正停止时用 stopService()。
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
        boolean finished = p.waitFor(EXEC_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
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
