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
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (mobilePatch != null) view.evaluateJavascript(mobilePatch, null);
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
        new Thread(this::runAllTests, "dsh-probe").start();
    }

    /**
     * 首次启动（还没填过 API Key）直接把设置对话框弹出来。
     *
     * 分享给别人安装时，"装完就能用"的关键就在这一步：
     * 没有 Key，用户发的第一条消息必然失败；而入口藏在
     * DSH 设置 →「API Key（本机）」里，新用户根本不会去找。
     * 这里主动弹一次，用户可以边等容器下载边填。
     */
    private void maybePromptApiKey() {
        String k = DshService.getApiKey(this);
        if (k != null && !k.trim().isEmpty()) return;
        output.postDelayed(this::showSettingsDialog, 600);
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
        try (InputStream in = new BufferedInputStream(c.getInputStream(), 1 << 16);
             OutputStream os = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                got += n;
                if (got - lastReported >= (4L << 20)) {
                    lastReported = got;
                    appendReport("    进度 " + (got >> 20) + " / " + (total >> 20) + " MB\n");
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

    /** 同时写入报告文件与界面（界面更新需回主线程）。 */
    private void progress(StringBuilder sb, String line) {
        sb.append(line);
        appendReport(line);
        final String l = line;
        runOnUiThread(() -> output.append(l));
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
                // 流式执行：输出实时写入报告，便于 PC 侧轮询进度
                int code = execStreaming(new String[]{
                        new File(base, "proot").getAbsolutePath(),
                        "-r", rootfs.getAbsolutePath(), "-0", "-w", "/root",
                        "-b", "/dev", "-b", "/proc", "-b", "/sys",
                        "/usr/bin/env", "-i",
                        "HOME=/root",
                        "PATH=" + nodePath + ":/usr/local/bin:/usr/bin:/bin",
                        "npm_config_registry=https://registry.npmmirror.com",
                        "npm_config_cache=/root/.npm",
                        "npm_config_update_notifier=false",
                        nodePath + "/npm", "install", "-g", "@deepseek-ai/dsh",
                        "--no-audit", "--no-fund", "--loglevel=http"}, libDir, sb);
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
        ProcessBuilder pb = buildProcess(cmd, ldLibraryPath);
        Process p = pb.start();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int n = 0;
            while ((line = br.readLine()) != null) {
                n++;
                if (n <= 400 || n % 20 == 0) {        // 限制写入量，避免报告爆炸
                    appendReport("    | " + line + "\n");
                }
            }
        }
        return p.waitFor();
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

        TextView tip = new TextView(this);
        tip.setTextSize(13f);
        tip.setText("填入 DeepSeek API Key（在 platform.deepseek.com 申请）。\n\n"
                + "· Key 只保存在本机 App 私有目录，不会上传到任何地方；\n"
                + "· 它是通过环境变量 DEEPSEEK_API_KEY 传给容器内的 DSH；\n"
                + "· 保存后会自动重启容器里的 DSH 使其生效。");
        box.addView(tip);

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
                .setPositiveButton("保存并重启 DSH", (d, w) -> {
                    String k = input.getText().toString().trim();
                    DshService.setApiKey(this, k);
                    appendReport("  [设置] API Key " + (k.isEmpty() ? "已清空" : "已更新")
                            + "，重启容器…\n");
                    restartDsh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 让新的 API Key 生效。
     *
     * 注意：这里**不**用 stopService/startService。实测那样做有两个问题：
     *  · stop 与 start 之间只隔 1.2s，系统会把两次请求合并成同一个实例，重启落空；
     *  · 即使停掉，旧 proot 的 tracee（node/dsh）会变成占着 3080 端口的孤儿进程。
     * 改为给前台服务置一个"重启标记"，由它自己的监督循环完成清理与重启。
     */
    private void restartDsh() {
        final String oldUrl = dshUrl;
        dshUrl = null;
        runOnUiThread(() -> {
            webView.setVisibility(View.GONE);
            output.setVisibility(View.VISIBLE);
            output.setText("已保存设置，正在重启容器内的 DSH…\n");
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
        for (int i = 0; i < 240; i++) {
            String u = DshService.getUrl();
            if (u != null && !u.equals(previousUrl)) {
                dshUrl = u;
                onServerReady(u);
                appendReport("  ✅ 服务就绪（前台服务）: " + u + "\n");
                return;
            }
            if (i % 10 == 9) {
                final String st = DshService.getState();
                runOnUiThread(() -> statusBar.setText("DeepSeek Harness · " + st));
            }
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        }
        final String st = DshService.getState();
        runOnUiThread(() -> {
            output.setVisibility(View.VISIBLE);
            output.append("\n等待 dsh web 超时 · 状态: " + st + "\n");
        });
        appendReport("  ❌ 等待超时 · 状态: " + st + "\n");
    }

    private void startDshService() {
        Intent it = new Intent(this, DshService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(it);
        else startService(it);
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
