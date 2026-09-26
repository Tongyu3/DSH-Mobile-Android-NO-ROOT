package com.dshmobile.probe;

import android.content.Context;
import android.system.Os;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 容器运行环境：proot / 依赖库 / loader / dsh 启动命令的统一出口。
 *
 * 由 MainActivity（首次安装与自检）和 DshService（常驻保活）共用，
 * 避免同一套路径逻辑写两遍而漂移。
 *
 * 关于 proot 的三个必须项（都是实测踩出来的）：
 *  1. LD_LIBRARY_PATH  → proot 依赖 libtalloc.so.2 / libandroid-shmem.so
 *  2. PROOT_TMP_DIR    → 必须存在且路径里不能有 ".."
 *  3. PROOT_LOADER     → Termux 的 proot 把 loader 路径硬编码成自己的目录，
 *                        不覆盖就无法加载"解释器在 guest 内"的动态链接程序
 */
public final class Env {

    /** Node 的安装目录名（解压在容器 /opt 下）。 */
    public static final String NODE_DIR = "node-v22.23.2-linux-arm64";
    /** dsh web 的监听端口（回环）。 */
    public static final int DSH_PORT = 3080;

    /** 手机共享存储在宿主机上的真实路径。 */
    public static final String SDCARD_HOST = "/storage/emulated/0";
    /** 共享存储挂进容器后使用的路径（和安卓习惯一致）。 */
    public static final String SDCARD_GUEST = "/sdcard";
    /**
     * 用户要求的测试文件夹名。
     * 必须声明在 WORKSPACE 之前 —— Java 静态字段按声明顺序初始化，
     * 反过来的话 WORKSPACE 会拼出 "/sdcard/null"。
     */
    public static final String TEST_DIR_NAME = "dsh";
    /**
     * 默认工作区 = 手机共享存储里的 dsh 文件夹（用户指定的测试工作区）。
     *
     * 注意：因为容器跑在 DSH_PERMISSION_MODE=danger-full-access 下，
     * 工作区只是"项目目录"语义，并不会限制文件访问范围 ——
     * agent 依然可以按绝对路径访问整个 /sdcard。
     * 若想让工作区变成整个共享存储，把这里改成 SDCARD_GUEST 即可。
     */
    public static final String WORKSPACE = SDCARD_GUEST + "/" + TEST_DIR_NAME;

    /** 手机控制 CLI 在容器里的安装位置。 */
    public static final String PHONE_CLI_PATH = "/usr/local/bin/phone";
    /** token 目录在容器里的挂载点（由 App 只读绑定进来）。 */
    public static final String PHONE_RUN_DIR = "/run/dsh-phone";

    private Env() { }

    /** App 沙箱内的容器根目录。 */
    public static File base(Context c) {
        File f = new File(c.getFilesDir(), "probe");
        //noinspection ResultOfMethodCallIgnored
        f.mkdirs();
        return f;
    }

    public static void copy(File src, File dst) throws Exception {
        try (InputStream in = new FileInputStream(src);
             OutputStream os = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    public static File extractAsset(Context c, String name, File dst) throws Exception {
        File parent = dst.getParentFile();
        if (parent != null) //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        try (InputStream in = c.getAssets().open(name);
             OutputStream os = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        Os.chmod(dst.getAbsolutePath(), 0700);
        return dst;
    }

    /**
     * 安全安装一个可执行文件：先写到 <dst>.new，再 rename 覆盖。
     *
     * 为什么不能直接原地写：如果 dst 正在被执行（例如旧的 proot 进程还在跑），
     * Linux 会以 **ETXTBSY (Text file busy)** 拒绝打开它写入。
     * 这个坑实际踩到过——导致"改完 API Key 后容器永远重启不了"。
     * rename 是原子替换，旧 inode 会留给仍在运行的进程，互不影响。
     */
    public static File installAsset(Context c, String name, File dst) throws Exception {
        File parent = dst.getParentFile();
        if (parent != null) //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        File tmp = new File(dst.getAbsolutePath() + ".new");
        //noinspection ResultOfMethodCallIgnored
        if (tmp.exists()) tmp.delete();
        try (InputStream in = c.getAssets().open(name);
             OutputStream os = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        Os.chmod(tmp.getAbsolutePath(), 0700);
        Os.rename(tmp.getAbsolutePath(), dst.getAbsolutePath());
        return dst;
    }

    /**
     * 清理残留的容器进程。
     *
     * proot 用 ptrace 托管子进程：当 proot 被杀掉时，它的 tracee（node/dsh）
     * 会被 detach 并**继续存活**，变成占着 3080 端口的孤儿进程，
     * 导致新的 dsh 起不来。所以重启前主动扫 /proc 清掉。
     * 只动本 App UID 自己的进程。
     */
    public static int killStaleDsh(File base) {
        int killed = 0;
        File proc = new File("/proc");
        File[] entries = proc.listFiles();
        if (entries == null) return 0;
        int myPid = android.os.Process.myPid();
        String rootfsPath = new File(base, "rootfs").getAbsolutePath();
        for (File e : entries) {
            if (!e.isDirectory()) continue;
            int pid;
            try {
                pid = Integer.parseInt(e.getName());
            } catch (NumberFormatException nfe) {
                continue;
            }
            if (pid == myPid) continue;
            String cmd = readSmall(new File(e, "cmdline"));
            if (cmd == null) continue;
            boolean isDsh = cmd.contains("/bin/dsh") && cmd.contains("--profile");
            boolean isOurProot = cmd.contains("proot") && cmd.contains(rootfsPath);
            if (isDsh || isOurProot) {
                try {
                    android.os.Process.sendSignal(pid, 9);
                    killed++;
                } catch (Throwable ignore) { }
            }
        }
        return killed;
    }

    private static String readSmall(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[4096];
            int n = in.read(buf);
            if (n <= 0) return null;
            return new String(buf, 0, n, "UTF-8").replace('\0', ' ');
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把 proot、它的两个依赖库、以及 loader 解到沙箱。
     * 幂等：已存在就覆盖（文件很小）。
     *
     * @return 库目录路径（供 LD_LIBRARY_PATH）
     */
    public static String prepareProot(Context c, File base) throws Exception {
        File libDir = new File(base, "lib");
        File tmpDir = new File(base, "tmp");
        File libexec = new File(base, "libexec");
        //noinspection ResultOfMethodCallIgnored
        libDir.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        tmpDir.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        libexec.mkdirs();

        installAsset(c, "proot", new File(base, "proot"));
        String[] libs = c.getAssets().list("libs");
        if (libs != null) {
            for (String n : libs) installAsset(c, "libs/" + n, new File(libDir, n));
        }
        installAsset(c, "loader", new File(libexec, "loader"));
        try {
            installAsset(c, "loader32", new File(libexec, "loader32"));
        } catch (Throwable ignore) {
            /* 32 位 loader 可选 */
        }
        return libDir.getAbsolutePath();
    }

    /** 注入 proot 需要的全部环境变量。 */
    public static ProcessBuilder buildProcess(File base, String[] cmd, String libDir) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        if (libDir != null) {
            pb.environment().put("LD_LIBRARY_PATH", libDir);
            pb.environment().put("PROOT_TMP_DIR", new File(base, "tmp").getAbsolutePath());
            pb.environment().put("PROOT_NO_SECCOMP", "1");
            pb.environment().put("PROOT_LOADER", new File(base, "libexec/loader").getAbsolutePath());
            File l32 = new File(base, "libexec/loader32");
            if (l32.exists()) pb.environment().put("PROOT_LOADER_32", l32.getAbsolutePath());
        }
        return pb;
    }

    /**
     * 组装启动 dsh web 的完整命令。
     *
     * ⚠️ 这里用的是 `env -i`（清空继承的环境），所以 ProcessBuilder 里设的
     * DEEPSEEK_API_KEY 会被丢掉 —— API Key 必须作为参数显式传给容器内的 env。
     * 这也是 DSH 读取凭据的第一优先级来源。
     */
    public static String[] dshWebCommand(File base, String apiKey, String phoneTokenDir) {
        String nodePath = "/opt/" + NODE_DIR + "/bin";
        java.util.ArrayList<String> a = new java.util.ArrayList<>();
        a.add(new File(base, "proot").getAbsolutePath());
        a.add("-r"); a.add(new File(base, "rootfs").getAbsolutePath());
        a.add("-0");                       // 伪装成 root
        a.add("-w"); a.add(WORKSPACE);     // 工作目录 = 手机共享存储里的 dsh 文件夹
        /*
         * ★ 必须加 --link2symlink：
         *   Android 自 6.0 起通过 SELinux 禁止普通 App 创建硬链接
         *   （实测 App 进程自身 Os.link() 也返回 EACCES）。
         *   而 DSH 写会话日志用的是"临时文件 + 硬链接"的原子替换
         *   （@deepseek-ai/dsh-atomic-write），没有这个选项每一轮对话都会直接失败：
         *     EACCES: permission denied, link '.../session.v3.jsonl.zstd.xxx.tmp' -> '...'
         *   proot 的 --link2symlink 用符号链接模拟硬链接，正是为此场景设计
         *   （help 原文：Emulates hard links with symbolic links when SELinux
         *    policies do not allow hard links.）
         */
        a.add("--link2symlink");
        a.add("--kill-on-exit");           // 主命令退出时一并收掉残留的子进程
        a.add("-b"); a.add("/dev");
        a.add("-b"); a.add("/proc");
        a.add("-b"); a.add("/sys");
        // ★ 关键：把手机共享存储绑进容器。不绑的话容器里看不到手机上的任何文件，
        //   DSH 的"读取手机文件"就无从谈起。
        a.add("-b"); a.add(SDCARD_HOST + ":" + SDCARD_GUEST);
        a.add("-b"); a.add(SDCARD_HOST + ":" + SDCARD_HOST);
        /*
         * 手机控制桥的 token 走**只读绑定**进来，不放在共享存储 ——
         * 共享存储上任何应用都能读，token 就等于公开了。
         */
        if (phoneTokenDir != null && !phoneTokenDir.isEmpty()) {
            a.add("-b"); a.add(phoneTokenDir + ":" + PHONE_RUN_DIR);
        }
        a.add("/usr/bin/env"); a.add("-i");
        a.add("HOME=/root");
        a.add("PATH=" + nodePath + ":/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        a.add("LANG=C.UTF-8");
        a.add("TERM=xterm-256color");
        /*
         * ★ 必须用 danger-full-access：
         *   DSH 的 bash 工具默认（workspace-write）要求宿主机有可用的沙箱后端
         *   （bubblewrap 或 Landlock）。容器里两者都没有 —— Ubuntu base 不含
         *   bubblewrap，而 Android 内核不给普通应用用户命名空间 / Landlock。
         *   实测默认模式下每条命令都会失败：
         *     Error: sandbox mode "workspace-write" is requested but no sandbox
         *     backend is usable on this host; refusing to run the command unconfined.
         *   而本 App 的容器**本身就是一个沙箱**（proot + 应用私有目录 + 显式绑定的
         *   /sdcard），再叠一层无法启用的沙箱只会让 agent 完全不能干活。
         *   DSH 的报错信息也明确指向这个选项。
         *
         *   副作用：此模式下审批策略为 never（不再逐条弹窗确认），
         *   文件工具也不再限制在工作区内 —— 这正是"文件管理器级别访问"所需。
         *   可达范围仍限于：容器 rootfs + 手机共享存储（App 无法触及 /system
         *   与其他应用私有数据）。
         */
        a.add("DSH_PERMISSION_MODE=danger-full-access");
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            a.add("DEEPSEEK_API_KEY=" + apiKey.trim());
        }
        a.add(nodePath + "/dsh");
        a.add("--profile"); a.add("web");
        a.add("--no-open");
        a.add("--port"); a.add(String.valueOf(DSH_PORT));
        return a.toArray(new String[0]);
    }

    /**
     * 把"操作手机"所需的工具装进容器：
     *   · /usr/local/bin/phone —— CLI（agent 用 bash 工具调用它）
     *   · /run/dsh-phone       —— token 的挂载点
     *   · 工作区里的 AGENTS.md  —— 告诉 agent 这个能力怎么用（已存在则不覆盖）
     *
     * 幂等，每次启动都会重写（文件很小）。
     */
    public static void installPhoneTools(Context c, File base) {
        try {
            File rootfs = new File(base, "rootfs");
            File binDir = new File(rootfs, "usr/local/bin");
            //noinspection ResultOfMethodCallIgnored
            binDir.mkdirs();
            File cli = new File(binDir, "phone");
            try (InputStream in = c.getAssets().open("phone-cli.js");
                 OutputStream os = new FileOutputStream(cli)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            Os.chmod(cli.getAbsolutePath(), 0755);

            //noinspection ResultOfMethodCallIgnored
            new File(rootfs, "run/dsh-phone").mkdirs();

            File agents = new File(SDCARD_HOST + "/" + TEST_DIR_NAME, "AGENTS.md");
            if (!agents.exists()) {
                try (InputStream in = c.getAssets().open("AGENTS.md");
                     OutputStream os = new FileOutputStream(agents)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
            }

            /*
             * 同时写进 DSH 的**全局**指令目录（容器里的 $DSH_HOME/AGENTS.md）。
             *
             * 为什么必须：放在工作区里的 AGENTS.md 只对那个工作区生效。
             * 用户一旦把工作区切到别处（例如 /sdcard/Download），
             * agent 就看不到"手机操作"这份说明 —— 能力等于消失了。
             * 全局位置对**所有工作区**生效，这份每次覆盖以保证是最新版。
             */
            File globalAgents = new File(rootfs, "root/.dsh/AGENTS.md");
            File globalDir = globalAgents.getParentFile();
            if (globalDir != null) //noinspection ResultOfMethodCallIgnored
                globalDir.mkdirs();
            try (InputStream in = c.getAssets().open("AGENTS.md");
                 OutputStream os = new FileOutputStream(globalAgents)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
        } catch (Throwable t) {
            android.util.Log.e("DSH_ENV", "installPhoneTools failed", t);
        }
    }

    /**
     * 准备容器内的存储挂载点，并创建测试工作区文件夹。
     *
     * 挂载点目录必须**预先存在**：proot 在 guest 侧找不到挂载点时会尝试自己创建，
     * 我们此前实测它会失败并报 "can't sanitize binding"。
     *
     * @return 测试文件夹的宿主机路径（失败时返回 null）
     */
    public static String ensureStorageMounts(File base) {
        File rootfs = new File(base, "rootfs");
        //noinspection ResultOfMethodCallIgnored
        new File(rootfs, "sdcard").mkdirs();
        //noinspection ResultOfMethodCallIgnored
        new File(rootfs, "storage/emulated/0").mkdirs();
        // 用户要求的测试文件夹：/sdcard/dsh
        File test = new File(SDCARD_HOST, TEST_DIR_NAME);
        boolean ok = test.exists() || test.mkdirs();
        return ok ? test.getAbsolutePath() : null;
    }

    /** 容器里的组件是否都已就绪。 */
    public static boolean isInstalled(File base) {
        return new File(base, "rootfs/usr/bin/bash").exists()
                && new File(base, "rootfs/opt/" + NODE_DIR + "/bin/node").exists()
                && new File(base, "rootfs/opt/" + NODE_DIR + "/bin/dsh").exists();
    }
}
