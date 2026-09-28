package com.dshmobile.probe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * Shizuku 客户端：让**没有电脑**的用户也能完成那一次关键授权。
 *
 * <h3>它到底解决什么问题</h3>
 * 无障碍被厂商关掉后要自动补回来，需要 {@code WRITE_SECURE_SETTINGS}，
 * 而这只能通过 {@code pm grant} 授予。有电脑的人插线跑一条命令即可；
 * 没有电脑的人就靠 Shizuku —— 它是 shell 身份，能替我们跑那条命令。
 *
 * <h3>关于"APK 自带 Shizuku 最高权限"</h3>
 * 做不到，而且不是实现问题：Shizuku 的权限来自 ADB 或 root，
 * 是**从应用外面拿进来的**。官方文档明确要求"引导用户先安装 Shizuku 或 Sui"。
 * 我们能做的是把这一步做到最省事：装一次 Shizuku、点一下授权，之后永久有效。
 *
 * <h3>为什么用「用户服务」而不是直接跑命令</h3>
 * Shizuku API 13.1.5 里 {@code Shizuku.newProcess()} 已经是 <b>private</b>（API 14 计划删除），
 * 客户端根本调不到。官方给的替代方案就是用户服务：
 * 我们的 {@link ShellUserService} 会被 Shizuku 以 shell 身份在独立进程里跑起来。
 */
public final class ShizukuBridge {

    private static final String TAG = "DSH_SHIZUKU";
    private static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";
    /** 自定义的权限请求码，Shizuku 会原样回给我们。 */
    private static final int REQ_CODE = 0x4453;

    private ShizukuBridge() { }

    private static volatile boolean sListenerReady = false;

    // ── 状态 ────────────────────────────────────────────────

    /** 手机上有装 Shizuku 吗。 */
    public static boolean isInstalled(Context c) {
        try {
            c.getPackageManager().getPackageInfo(SHIZUKU_PKG, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Shizuku 服务在跑吗（binder 活着）。没跑的话什么都做不了。 */
    public static boolean isRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 用户给过本应用 Shizuku 权限吗。 */
    public static boolean hasPermission() {
        try {
            if (Shizuku.isPreV11()) return false;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前身份：0 = root，2000 = shell，-1 = 未知。 */
    public static int uid() {
        try {
            return Shizuku.getUid();
        } catch (Throwable t) {
            return -1;
        }
    }

    public static String statusText(Context c) {
        if (!isInstalled(c)) return "未安装 Shizuku";
        if (!isRunning()) return "Shizuku 已安装，但没在运行";
        if (!hasPermission()) return "Shizuku 在运行，本应用还没被授权";
        int u = uid();
        return "Shizuku 就绪（身份 " + (u == 0 ? "root" : u == 2000 ? "shell" : "uid=" + u) + "）";
    }

    // ── 权限 ────────────────────────────────────────────────

    /**
     * 注册权限回调（幂等）。
     *
     * <p>**刻意不提供反注册**：Shizuku 13.1.1 的 remove 系列在 Android 7.1 及更早版本上
     * 会因为 {@code CopyOnWriteArrayList#removeIf} 不存在而崩溃（我们 minSdk 24，
     * 正好覆盖 7.0/7.1）。注册一次、一直留着，就绕开了这个坑。
     */
    private static void ensureListener(Context c) {
        if (sListenerReady) return;
        sListenerReady = true;
        try {
            Shizuku.addRequestPermissionResultListener((code, grantResult) -> {
                if (code != REQ_CODE) return;
                boolean granted = grantResult == PackageManager.PERMISSION_GRANTED;
                Log.i(TAG, "Shizuku 权限结果: " + granted);
                Toast.makeText(c.getApplicationContext(),
                        granted ? "Shizuku 已授权，正在为你完成一次性授权…" : "Shizuku 授权被拒绝",
                        Toast.LENGTH_LONG).show();
                if (granted && c instanceof Activity) {
                    grantSecureSettings((Activity) c, null);
                }
            });
        } catch (Throwable t) {
            sListenerReady = false;
            Log.w(TAG, "注册 Shizuku 权限回调失败: " + t);
        }
    }

    // ── 执行命令（用户服务） ────────────────────────────────

    /**
     * 以 shell / root 身份跑一条命令。
     *
     * <p>要在后台线程调用 —— 里面会等 binder 连接，最多阻塞 15 秒。
     *
     * @return 命令输出（形如 "exit=0\n..."）；失败时抛异常，由调用方展示
     */
    public static String execShell(Context c, String command) throws Exception {
        if (!isRunning()) throw new IllegalStateException("Shizuku 没有在运行");
        if (!hasPermission()) throw new IllegalStateException("本应用还没有拿到 Shizuku 权限");

        final CountDownLatch latch = new CountDownLatch(1);
        final IShellService[] box = new IShellService[1];

        ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                box[0] = IShellService.Stub.asInterface(binder);
                latch.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                box[0] = null;
            }
        };

        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                new ComponentName(c.getPackageName(), ShellUserService.class.getName()))
                .daemon(false)              // 跟着本进程走，用完即走，不留后台进程
                .version(1)
                .processNameSuffix("shell"); // 必填，不设会抛 NullPointerException

        Shizuku.bindUserService(args, conn);
        if (!latch.await(15, TimeUnit.SECONDS) || box[0] == null) {
            throw new IllegalStateException("连接 Shizuku 用户服务超时（Shizuku 还活着吗？）");
        }
        try {
            return box[0].exec(command);
        } finally {
            /*
             * remove=false —— 只摘掉回调，**不要 kill 服务**。
             * 传 true 会把用户服务进程杀掉，下次又要重新启动（慢且吵）。
             */
            try {
                Shizuku.unbindUserService(args, conn, false);
            } catch (Throwable ignore) { }
        }
    }

    // ── 一次点击完成授权 ────────────────────────────────────

    /** 界面上的「用 Shizuku 授权」按钮流程。 */
    public static void showGrantDialog(Activity a, Runnable onDone) {
        if (!isInstalled(a)) {
            new android.app.AlertDialog.Builder(a)
                    .setTitle("先装 Shizuku")
                    .setMessage("Shizuku 是一个独立的应用，它负责从 ADB 或 root 拿到系统权限，"
                            + "再把权限借给别的应用。\n\n"
                            + "它的权限来自系统外部，所以任何 APK 都不可能「自带」——"
                            + "能自带的话那叫提权漏洞。\n\n"
                            + "获取方式：应用商店搜「Shizuku」，或到 GitHub 的 RikkaApps/Shizuku 下载。\n\n"
                            + "Android 11 及以上可以全程在手机上启动它，不需要电脑。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        if (!isRunning()) {
            new android.app.AlertDialog.Builder(a)
                    .setTitle("Shizuku 没在运行")
                    .setMessage("请先打开 Shizuku 应用并启动它。\n\n"
                            + "· 有 root：打开就自动启动，重启也能自启。\n"
                            + "· 没有 root（Android 11+）：在 Shizuku 里用「无线调试」启动，"
                            + "全程在手机上完成，不需要电脑。注意每次重启手机后要重新启动一次。\n\n"
                            + "启动完回到这里再点一次即可。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        if (!hasPermission()) {
            ensureListener(a);
            new android.app.AlertDialog.Builder(a)
                    .setTitle("需要你允许一下")
                    .setMessage("马上会弹出 Shizuku 的授权窗口，请选择「允许」。\n\n"
                            + "允许之后本应用就能以 shell 身份执行一条命令，"
                            + "给自己拿到那次性的权限，之后就再也不需要 Shizuku 了。")
                    .setPositiveButton("好，弹窗吧", (d, w) -> {
                        try {
                            Shizuku.requestPermission(REQ_CODE);
                        } catch (Throwable t) {
                            Toast.makeText(a, "请求失败: " + t, Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        grantSecureSettings(a, onDone);
    }

    /**
     * 真正的动作：通过 Shizuku 以 shell 身份给自己授予 WRITE_SECURE_SETTINGS。
     *
     * <p>这一步是**一次性**的：授权写进 packages.xml，重启、更新 App 都不会掉。
     */
    public static void grantSecureSettings(Activity a, Runnable onDone) {
        final String cmd = "pm grant " + a.getPackageName() + " " + Priv.PERM_WRITE_SECURE;
        final android.app.ProgressDialog prog =
                android.app.ProgressDialog.show(a, "正在授权", "通过 Shizuku 执行：\n" + cmd, true, false);

        new Thread(() -> {
            String result;
            boolean ok;
            try {
                result = execShell(a, cmd);
                // 真正作数的是"我们现在有没有这个权限"，不是命令的退出码
                ok = Priv.canHeal(a);
            } catch (Throwable t) {
                result = String.valueOf(t);
                ok = false;
            }
            final String r = result;
            final boolean success = ok;
            a.runOnUiThread(() -> {
                try { prog.dismiss(); } catch (Throwable ignore) { }
                String detail = success
                        ? "✅ 授权成功，永久有效。\n\n以后无障碍被系统关掉，本应用会自己补回来，"
                          + "不再需要 Shizuku 也不需要电脑。"
                        : "❌ 没能授权成功。\n\nShizuku 的返回：\n" + r
                          + "\n\n如果一直失败，可以改用电脑执行一次：\n" + Priv.grantCommand(a)
                          + "\n\n（在部分机型上，Shizuku 的 shell 权限是受限的，"
                          + "需要在开发者选项里打开「USB 调试（安全设置）」或关闭「权限监控」。）";
                new android.app.AlertDialog.Builder(a)
                        .setTitle(success ? "完成" : "授权失败")
                        .setMessage(detail)
                        .setPositiveButton("好", (d, w) -> {
                            if (onDone != null) onDone.run();
                        })
                        .show();
            });
        }, "dsh-shizuku-grant").start();
    }
}
