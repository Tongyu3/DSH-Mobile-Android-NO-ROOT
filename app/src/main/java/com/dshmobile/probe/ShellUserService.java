package com.dshmobile.probe;

import android.content.Context;
import android.system.Os;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Shizuku「用户服务」：本类的代码**运行在独立进程里，身份是 shell(uid 2000) 或 root(uid 0)**。
 *
 * <h3>它在哪跑</h3>
 * 不是普通的 Android Service —— Shizuku 用 {@code app_process} 直接把它拉起来，
 * 所以它没有正常的 App Context：{@code getContentResolver()}、{@code registerReceiver()} 之类
 * 都用不了。能用的就是纯 Java + {@link Runtime#exec}，这对我们足够了。
 *
 * <h3>为什么是"跑一条命令"而不是直接写设置</h3>
 * 因为我们要做的是给<em>自己</em>授权（{@code pm grant}），
 * 一次性做完之后本 App 就能永久自愈，Shizuku 以后都不需要再开着。
 * 直接由 shell 反复去写设置也能用，但那样每次都得依赖 Shizuku 活着，不如一次到位。
 *
 * <h3>没有构造函数行不行</h3>
 * 不行。Shizuku 反射实例化本类，需要一个无参构造函数；
 * 带 Context 的那个只有 Shizuku v13+ 才会尝试，我们不用它。
 */
public class ShellUserService extends IShellService.Stub {

    private static final String TAG = "DSH_SHELL_SVC";

    /** Shizuku 反射实例化用，必须有。 */
    public ShellUserService() {
        Log.i(TAG, "constructor, uid=" + Os.getuid());
    }

    /** Shizuku 回收用户服务时调用。不调 System.exit 的话进程会一直留着。 */
    @Override
    public void destroy() {
        Log.i(TAG, "destroy");
        System.exit(0);
    }

    @Override
    public int uid() {
        return Os.getuid();
    }

    @Override
    public String exec(String command) {
        if (command == null || command.trim().isEmpty()) return "exit=-1\n(空命令)";
        Process p = null;
        try {
            /*
             * 用 /system/bin/sh 的绝对路径：
             * 用户服务进程的 PATH 不一定和交互式 shell 一样，
             * 写绝对路径可以避免"在 adb 里能跑、在这里找不到命令"。
             */
            p = new ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(false)
                    .start();

            /*
             * 必须**并发**读走 stdout 和 stderr。
             * 顺序读会死锁：先读到 EOF 才能轮到 stderr，而子进程在 stderr 写满管道缓冲区
             * （Linux 上通常 64KB）之后就再也写不动，于是永远不退出 ——
             * 表现是"命令明明很快，却卡到超时"。
             */
            final InputStream es = p.getErrorStream();
            final ByteArrayOutputStream eb = new ByteArrayOutputStream();
            Thread errPump = new Thread(() -> {
                try { copy(es, eb); } catch (Throwable ignore) { }
            }, "dsh-shell-stderr");
            errPump.setDaemon(true);
            errPump.start();

            String out = drain(p.getInputStream());
            int code = p.waitFor();
            errPump.join(2_000L);
            String err = new String(eb.toByteArray(), StandardCharsets.UTF_8);

            StringBuilder sb = new StringBuilder();
            sb.append("exit=").append(code);
            if (out != null && !out.isEmpty()) sb.append('\n').append(out.trim());
            if (!err.isEmpty()) sb.append('\n').append(err.trim());
            return sb.toString();
        } catch (Throwable t) {
            return "exit=-1\n" + t;
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignore) { }
            }
        }
    }

    private static String drain(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        copy(in, bos);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) throws Exception {
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
