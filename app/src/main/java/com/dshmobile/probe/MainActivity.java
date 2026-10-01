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
                if (mobilePatch != null) view.evaluateJavascript(mobilePatch, null);
                reportWebViewVersion(view);
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
                boolean crashed = detail != null && detail.didCrash();
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
        runOnUiThread(() -> statusBar.setText("DeepSeek Harness · 正在启动 dsh web…"));
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
                deleteRecursively(rootfs);
                deleteRecursively(archive);
                deleteRecursively(new File(archive.getAbsolutePath() + ".part"));
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
                    if (!ready) deleteRecursively(archive);
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
                            deleteRecursively(archive);
                        }
                    } catch (Throwable t) {
                        progress(sb, "  该源失败: " + t.getClass().getSimpleName()
                                + ": " + t.getMessage() + "\n");
                        deleteRecursively(new File(archive.getAbsolutePath() + ".part"));
                        deleteRecursively(archive);
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
            deleteRecursively(rootfs);
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
        deleteRecursively(rootfs);
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
                    deleteRecursively(archive);
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
                            deleteRecursively(new File(archive.getAbsolutePath() + ".part"));
                            deleteRecursively(archive);
                        }
                    }
                    if (!ok) return fail(sb, "Node 下载失败（" + urls.length + " 个源都不行）");
                }
                progress(sb, "  解压 Node 到容器 /opt …\n");
                // 先清掉可能存在的半个 Node，再解压（否则会混出"有 bin/node 但缺库"的目录）
                deleteRecursively(new File(rootfs, "opt/" + NODE_DIR));
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
                deleteRecursively(new File(rootfs, "opt/" + NODE_DIR));
                deleteRecursively(new File(base, NODE_DIR + ".tar.gz"));
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
                        deleteRecursively(new File(rootfs, "root/.npm"));
                        deleteRecursively(new File(rootfs,
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
    private static final int AUTO_FIX_MAX = 2;

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

    /** 官方三件套 + 自带插件 —— 这些不该被"自救"停掉。 */
    private static boolean isProtectedBundle(String name) {
        return "@deepseek-ai/dsh-base".equals(name)
                || "@deepseek-ai/dsh-web-app".equals(name)
                || Env.PHONE_PLUGIN_NAME.equals(name)
                || Env.PHONE_FILES_NAME.equals(name);
    }

    /** profile 里**不是**官方/自带的那些（也就是用户自己装的第三方插件）。 */
    private List<String> thirdPartyBundles() {
        List<String> out = new java.util.ArrayList<>();
        try {
            for (String b : Env.profileBundles(this)) {
                if (!isProtectedBundle(b)) out.add(b);
            }
        } catch (Throwable ignore) { }
        return out;
    }

    /**
     * 这段失败文本看起来是不是"插件引起的"。
     *
     * <p>用途：决定自动自救时是"只停被点名的"还是"停掉全部第三方插件"。
     * 注意"界面空白 25 秒"这种上报里没有任何文字，这里会返回 false ——
     * 但那不代表与插件无关，所以调用方**并不用它来否决**自动自救，
     * 只看"有没有第三方插件可停"。
     */
    private static boolean looksPluginRelated(String detail) {
        if (detail == null || detail.isEmpty()) return false;
        String s = detail.toLowerCase(java.util.Locale.ROOT);
        return s.contains("plugin") || s.contains("activate") || s.contains("pending")
                || s.contains("waiting for service") || s.contains("failed to load")
                || detail.contains("插件");
    }

    /**
     * 从 DSH 的启动失败文本里抠出"可疑的插件包名"。
     *
     * <p>文本长这样（真机截图）：
     * <pre>
     *   dsh-speech: pending (waiting for service: settingsScope)
     *   dsh-mobile-gateway: pending (waiting for service: settingsScope)
     * </pre>
     * 只保留**确实登记在 profile 里、且不是官方那几件**的名字 ——
     * 否则可能把官方包一起停掉，那界面更起不来。
     */
    private List<String> suspectBundles(String detail) {
        List<String> out = new java.util.ArrayList<>();
        try {
            List<String> registered = Env.profileBundles(this);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?m)^\\s*([@A-Za-z0-9._/-]+)\\s*:\\s*(pending|failed|error)")
                    .matcher(detail == null ? "" : detail);
            while (m.find()) {
                String name = m.group(1).trim();
                if (registered.contains(name) && !out.contains(name) && !isProtectedBundle(name)) {
                    out.add(name);
                }
            }
        } catch (Throwable ignore) { }
        return out;
    }

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
        appendReport("  ⚠️ 前端自检：插件可能把界面卡住了\n"
                + (detail == null || detail.isEmpty() ? "" : detail + "\n"));
        // 同时写到界面上：用户截图/复制日志时能看到这一行
        output.append("  ⚠️ 前端自检：插件可能把界面卡住了"
                + "（profile 里登记了 " + Env.profileBundles(this).size() + " 个插件："
                + join(Env.profileBundles(this)) + "）\n");

        int fails = bootFailCount() + 1;
        setBootFailCount(fails);
        List<String> suspects = suspectBundles(detail);

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
         *   · profile 里压根没有第三方插件可停 → 问题不在插件上，
         *     亮面板让用户看日志、给「重试」；
         *   · 已经自动救过 AUTO_FIX_MAX 次 → 说明停了也没用，
         *     不能无限"停用→重启"，此时改成亮面板 + 让用户发日志。
         */
        List<String> autoTargets = suspects.isEmpty() ? thirdPartyBundles() : suspects;
        boolean canAuto = fails <= AUTO_FIX_MAX && !autoTargets.isEmpty();

        if (canAuto) {
            int n = Env.disableBundles(this, autoTargets);
            setAutoFixCount(autoFixCount() + 1);
            logSafeModeResult("自动停用并重启", n);
            appendReport("     判定依据：" + (suspects.isEmpty()
                    ? "上报里没有点名（按安全模式处理：停掉全部第三方插件）"
                    : "上报里点名了 " + join(suspects))
                    + (looksPluginRelated(detail) ? " · 文本特征：插件相关" : "") + "\n");
            final int stopped = n;
            runOnUiThread(() -> {
                android.widget.Toast.makeText(this,
                        "界面起不来，已自动停用 " + stopped + " 个第三方插件并重启"
                      + "（可在「手机权限 → 插件安全模式」恢复）",
                        android.widget.Toast.LENGTH_LONG).show();
                output.append("  🛠 自动自救：已停用 " + stopped + " 个插件 → "
                        + join(Env.disabledBundles(this))
                        + "\n     恢复入口：设置 → 手机权限 → 插件安全模式\n");
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
            output.setVisibility(View.VISIBLE);
            webView.setVisibility(View.GONE);

            /*
             * 走到这里说明"自动自救"没能用上或没管用：
             *   · profile 里没有第三方插件可停 —— 问题不在插件上；
             *   · 或者已经自动救过 AUTO_FIX_MAX 次还是起不来 —— 再停也没意义。
             * 两种情况都必须把话说清楚，并且给一个能点的出口。
             */
            StringBuilder sb = new StringBuilder();
            if (autoFixCount() > 0) {
                sb.append("已经自动停用第三方插件并重启过 ")
                  .append(autoFixCount()).append(" 次，界面仍然起不来 —— ")
                  .append("所以问题很可能不在插件上（容器/网络/版本不匹配都可能导致）。\n\n");
            } else {
                sb.append("界面没能加载出来，但 profile 里没有第三方插件可停 —— ")
                  .append("问题不在插件上。\n\n");
            }
            sb.append("DSH 0.2 改过一些内部服务名（例如 settingsScope 变成了 settings），")
              .append("为 0.1.x 写的插件会一直等一个不存在的服务，")
              .append("前端就永远停在 pending —— 那类问题会自动被停用处理掉。\n\n");
            // 把"profile 里到底登记了哪些插件"摊出来 —— 排查和自救都要靠它
            List<String> registered = Env.profileBundles(this);
            sb.append("profile 里登记了 ").append(registered.size()).append(" 个插件：")
              .append(join(registered)).append("\n\n");
            if (!suspects.isEmpty()) {
                sb.append("这次检测到可疑插件：\n");
                for (String s : suspects) sb.append("  · ").append(s).append('\n');
                sb.append('\n');
            }
            if (!Env.disabledBundles(this).isEmpty()) {
                sb.append("已停用待恢复：").append(join(Env.disabledBundles(this))).append("\n\n");
            }
            sb.append("可以先点「重试」；还不行就把日志发给开发者 —— ")
              .append("「手机权限 → 复制运行日志」。");

            new AlertDialog.Builder(this)
                    .setTitle("界面没能加载出来")
                    .setMessage(sb.toString())
                    .setPositiveButton("重试", (d, w) -> restartDshAndWait())
                    .setNeutralButton("安全模式并重启", (d, w) -> {
                        int n = Env.enterSafeMode(this);
                        logSafeModeResult("手动安全模式", n);
                        restartDshAndWait();
                    })
                    .setNegativeButton("复制日志", (d, w) -> copyLogToClipboard())
                    .show();
        });
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

    /** 改完 plugins/bundles 之后重启容器里的 dsh，并等它给出新 URL。 */
    private void restartDshAndWait() {        try {
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
    private class AppBridge {
        @android.webkit.JavascriptInterface
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
        public void openPluginMarket() {            runOnUiThread(() -> {
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
            });
        }

        /** 打开「桌面图标」配色选择页。 */
        @android.webkit.JavascriptInterface
        public void openIcons() {            runOnUiThread(() -> {
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
