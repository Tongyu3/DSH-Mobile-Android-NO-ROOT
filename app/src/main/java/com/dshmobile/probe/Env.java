package com.dshmobile.probe;

import android.content.Context;
import android.system.Os;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

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

    /**
     * 实际使用的端口。默认 {@link #DSH_PORT}，但**被别的进程占用时会自动往后找一个**。
     *
     * 为什么需要：`dsh --port 3080` 在端口被占时起不来（EADDRINUSE），
     * 而我们的监督循环会不停地重启、永远拿不到 URL ——
     * 用户看到的就是"初始化失败 / 一直起不来"，且现象和别的原因长得一样。
     * 3080 是个很容易撞车的端口，所以这里先自己探一遍。
     */
    private static volatile int sPort = DSH_PORT;

    public static int port() { return sPort; }

    public static void setPort(int p) { sPort = p; }

    /** 从 from 开始找一个**能绑定**的回环端口；全被占就返回 from（让 dsh 自己报错）。 */
    public static int pickFreePort(int from, int to) {
        for (int p = from; p <= to; p++) {
            java.net.ServerSocket ss = null;
            try {
                ss = new java.net.ServerSocket(p, 1,
                        java.net.InetAddress.getByName("127.0.0.1"));
                return p;
            } catch (Throwable ignore) {
                // 该端口被占用，试下一个
            } finally {
                try { if (ss != null) ss.close(); } catch (Throwable ignore) { }
            }
        }
        return from;
    }

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
            /*
             * ⚠️ 这里**故意不设** PROOT_NO_SECCOMP。
             *
             * 它原本是 `1`，含义是"关掉 proot 的 seccomp 加速、只用纯 ptrace"。
             * 关掉之后**每一个系统调用**都要陷入 ptrace 再被翻译一遍，
             * 而 `dsh web` 启动时要读几百个 JS 文件 ——
             * 实测那 12 秒里绝大部分就花在这儿（见 CHANGELOG 的启动计时）。
             *
             * seccomp 加速才是 proot 的正常模式；只有当内核/SELinux 不允许
             * 时才需要退回去。真退回去会很明显：proot 会直接报
             * "ptrace acceleration (seccomp) not available" 或者容器起不来，
             * 而自检的 TEST 4/5（proot 能否运行 / 容器内 bash）会立刻抓到。
             */
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
        java.util.List<String> extra = new java.util.ArrayList<>();
        extra.add("--no-open");
        extra.add("--port"); extra.add(String.valueOf(sPort));
        return dshCommand(base, apiKey, phoneTokenDir, extra);
    }

    /**
     * 跑 dsh 的 plugin 子命令。
     *
     * <p>⚠️ 参数顺序是 {@code dsh plugin --profile web add <包>} ——
     * {@code --profile} 必须放在子命令**之后**。
     * 一开始按 {@code dsh --profile web plugin add} 写，dsh 直接报
     * {@code error: --profile <name> is required}，看着像"没给参数"，
     * 其实是位置不对。
     */
    public static String[] dshPluginCommand(File base, String tokenDir, String... pluginArgs) {
        java.util.List<String> args = new java.util.ArrayList<>();
        args.add("plugin");
        args.add("--profile"); args.add("web");
        for (String s : pluginArgs) args.add(s);
        return containerCommand(base, null, tokenDir,
                "/opt/" + NODE_DIR + "/bin/dsh", args);
    }

    /**
     * 在容器里跑任意 dsh 子命令。
     *
     * <p>proot 的挂载与环境变量和 {@code dsh web} 完全一致，只有尾部参数不同 ——
     * 所以抽出来共用，避免两处各写一份 proot 参数、改一处漏一处。
     */
    public static String[] dshCommand(File base, String apiKey, String phoneTokenDir,
                                      java.util.List<String> extra) {
        java.util.List<String> args = new java.util.ArrayList<>();
        args.add("--profile"); args.add("web");
        args.addAll(extra);
        return containerCommand(base, apiKey, phoneTokenDir,
                "/opt/" + NODE_DIR + "/bin/dsh", args);
    }

    /**
     * 在容器里跑任意可执行文件（proot 参数与挂载对所有命令都一样）。
     *
     * <p>抽出来的原因：{@code dsh} 和 {@code npm} 共用同一套 proot 环境，
     * 但命令行完全不同。之前图省事在 dsh 命令里塞了个占位符再替换成 npm，
     * 结果**两个可执行文件同时出现在命令行上**，dsh 先跑起来并报
     * {@code error: --profile <name> is required} —— 排查了一轮才看明白。
     * 可执行文件必须作为参数传进来，不能靠替换。
     */
    public static String[] containerCommand(File base, String apiKey, String phoneTokenDir,
                                            String exe, java.util.List<String> args) {
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
        a.add(exe);
        a.addAll(args);
        return a.toArray(new String[0]);
    }

    /**
     * 给**要长期驻留**的容器服务用的容器命令：去掉 {@code --kill-on-exit}。
     *
     * <p>{@link #containerCommand} 会带上 {@code --kill-on-exit}（"主命令退出时
     * 一并收掉残留子进程"），这对一次性命令是对的 —— 但常驻服务恰恰要反过来：
     * 启动器（proot + bash）**退出了，服务还得活着**。
     *
     * <p>带上它的话，`nohup ... &` 起来的后台进程会在 bash 退出那一刻被 proot 收掉，
     * 表现就是"启动成功了，一秒后进程没了"。
     */
    public static String[] containerCommandDetached(File base, String apiKey, String tokenDir,
                                                    String exe, java.util.List<String> args) {
        String[] full = containerCommand(base, apiKey, tokenDir, exe, args);
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (String s : full) {
            if (!"--kill-on-exit".equals(s)) out.add(s);
        }
        return out.toArray(new String[0]);
    }

    /**
     * 在容器里跑一段 shell，把输出取回来（**有界超时**）。
     *
     * <p>抽出来是因为现在有两处需要它：常驻服务的启停检查，和内核升级
     * （{@code npm install -g}）。以前这段逻辑散在 Services 里，
     * 升级那块再抄一份就会出现"两处 proot 参数不一致"的问题。
     *
     * @param timeoutMs 超时上限；到点就 destroy，绝不无限等
     * @param onLine    每读到一行回调一次（可为 null），用来做进度显示
     * @return 输出文本；起不来或超时返回 null
     */
    public static String execInContainer(android.content.Context c, String script,
                                         long timeoutMs,
                                         java.util.function.Consumer<String> onLine) {
        try {
            File base = base(c);
            String tokenDir = PhoneBridge.tokenDir(c);
            String apiKey = DshService.getApiKey(c);
            java.util.List<String> args = new java.util.ArrayList<>();
            args.add("-lc");
            args.add(script);
            String[] cmd = containerCommandDetached(base, apiKey, tokenDir, "/bin/bash", args);
            ProcessBuilder pb = buildProcess(base, cmd, new File(base, "lib").getAbsolutePath());
            final Process p = pb.start();
            final StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(() -> {
                try {
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = br.readLine()) != null) {
                        synchronized (sb) {
                            if (sb.length() < 200000) sb.append(line).append('\n');
                        }
                        if (onLine != null) {
                            try { onLine.accept(line); } catch (Throwable ignore) { }
                        }
                    }
                } catch (Throwable ignore) { }
            }, "dsh-exec-out");
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroy();
                android.util.Log.w("DSH_ENV", "容器命令超时（" + timeoutMs + "ms）");
                return null;
            }
            reader.join(2000L);
            synchronized (sb) { return sb.toString(); }
        } catch (Throwable t) {
            android.util.Log.w("DSH_ENV", "容器命令失败", t);
            return null;
        }
    }

    /**
     * 该不该写工作区里的 AGENTS.md。
     *
     * <p>文件不存在 → 写；
     * 存在且**是我们写的**（含固定标题）→ 覆盖成最新版；
     * 存在但不是我们写的（用户自己编辑过）→ **不碰**。
     *
     * <p>这样既保证说明书能跟着版本升级，又不会覆盖用户自己的内容。
     */
    private static boolean shouldWriteAgents(File f) {
        if (!f.exists()) return true;
        try (InputStream in = new FileInputStream(f)) {
            byte[] head = new byte[512];
            int n = in.read(head);
            if (n <= 0) return true;          // 空文件当没写过
            String s = new String(head, 0, n, java.nio.charset.StandardCharsets.UTF_8);
            return s.contains(AGENTS_MARKER);
        } catch (Throwable t) {
            return false;                     // 读不出来就别动它
        }
    }

    /** 我们写的那份 AGENTS.md 的固定标题，用来识别"这是我们写的"。 */
    private static final String AGENTS_MARKER = "这是手机上的工作区";

    /** 我们自带的 DSH 插件（tgz 打进 assets，首次启动自动装进 profile）。 */
    public static final String PHONE_PLUGIN_NAME = "dsh-phone";
    /*
     * ⚠️ 改这个文件名就等于"发布新版本"。
     *
     * 下面判重用的是**文件名**而不是包名 —— 因为包名永远是 dsh-phone，
     * 用它判重的话，App 升级带了新插件也永远会被当成"已安装、跳过"，
     * 用户就再也拿不到插件的更新了。
     */
    public static final String PHONE_PLUGIN_TGZ = "dsh-phone-1.0.2.tgz";
    /** 右侧栏「手机文件」插件（浏览手机存储 + 多选加进会话）。 */
    public static final String PHONE_FILES_TGZ = "dsh-phone-files-1.0.2.tgz";
    public static final String PHONE_FILES_NAME = "dsh-phone-files";
    /**
     * 随 APK 内置的插件：{tarball 名, 包名}。
     * 加新插件只要在这里追加一行 + 跑 plugin\build-tgz.ps1。
     */
    private static final String[][] BUNDLED_PLUGINS = {
            { PHONE_PLUGIN_TGZ, PHONE_PLUGIN_NAME },
            { PHONE_FILES_TGZ, PHONE_FILES_NAME },
    };

    /**
     * 把随 APK 自带的 DSH 插件装进 profile —— 只需装一次。
     *
     * <h3>为什么不走官方的 {@code dsh plugin add}</h3>
     * 那条路内部转发给 **pnpm**，而容器里根本没有 pnpm
     * （实测 {@code /opt/node-…/bin/} 下只有 corepack / dsh / node / npm / npx）。
     * 为了装一个插件去下载整个 pnpm，既慢又多一个失败点。
     *
     * <p>而我们的插件**零依赖**，所以"装"这件事可以完全离线、确定性地做掉：
     * 把 tgz 解到 {@code profiles/web/node_modules/dsh-phone/}，
     * 再把包名写进 profile 的 {@code dsh.profile.bundles} 与 {@code dependencies}。
     * 这正是 pnpm 装完之后该有的样子。
     *
     * <p>运行期解析不用担心：dsh 会维护 {@code ~/.dsh/profiles/node_modules}
     * 作为依赖备援目录，插件里 import 的 {@code @deepseek-ai/*} 从那里解析得到。
     *
     * @return 给日志用的一句话
     */
    public static String installPhonePlugin(Context c, File base, String tokenDir, String libDir) {
        File profileDir = new File(base, "rootfs/root/.dsh/profiles/web");
        File pkgJson = new File(profileDir, "package.json");
        if (!pkgJson.exists()) {
            return "profile 还没建好，本次跳过（下次启动再装）";
        }
        StringBuilder log = new StringBuilder("自带插件：");
        for (String[] spec : BUNDLED_PLUGINS) {
            String tgzName = spec[0], pkgName = spec[1];
            try {
                if (readText(pkgJson).contains(tgzName)) {
                    log.append(pkgName).append(" 已是最新；");
                    continue;
                }

                // ① 解包到 node_modules/<包名>
                File destDir = new File(profileDir, "node_modules/" + pkgName);
                deleteRecursively(destDir);
                if (!destDir.mkdirs()) { log.append(pkgName).append(" 建不了目录；"); continue; }

                String destPath = destDir.getCanonicalPath();
                int files = 0;
                try (InputStream raw = c.getAssets().open(tgzName);
                     java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(raw, 1 << 16);
                     org.apache.commons.compress.archivers.tar.TarArchiveInputStream tin =
                             new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gz)) {
                    org.apache.commons.compress.archivers.tar.TarArchiveEntry e;
                    byte[] buf = new byte[1 << 16];
                    while ((e = tin.getNextEntry()) != null) {
                        String name = e.getName();
                        if (!name.startsWith("package/")) continue;      // npm tarball 的外层目录
                        String rel = name.substring("package/".length());
                        if (rel.isEmpty()) continue;
                        File out = new File(destDir, rel);
                        if (!out.getCanonicalPath().startsWith(destPath)) continue;   // 防目录穿越
                        if (e.isDirectory()) {
                            //noinspection ResultOfMethodCallIgnored
                            out.mkdirs();
                        } else {
                            File parent = out.getParentFile();
                            if (parent != null) //noinspection ResultOfMethodCallIgnored
                                parent.mkdirs();
                            try (OutputStream os = new FileOutputStream(out)) {
                                int n;
                                while ((n = tin.read(buf)) > 0) os.write(buf, 0, n);
                            }
                            files++;
                        }
                    }
                }

                // ② 把 tgz 也留一份在 profile 目录（dependencies 里的 file: 指向它）
                File tgzCopy = new File(profileDir, tgzName);
                try (InputStream in = c.getAssets().open(tgzName);
                     OutputStream os = new FileOutputStream(tgzCopy)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }

                // ③ 写进 profile 的 package.json：bundles 才是真正决定"加载谁"的地方
                org.json.JSONObject o = new org.json.JSONObject(readText(pkgJson));
                org.json.JSONObject deps = o.optJSONObject("dependencies");
                if (deps == null) { deps = new org.json.JSONObject(); o.put("dependencies", deps); }
                deps.put(pkgName, "file:./" + tgzName);

                org.json.JSONObject dsh = o.optJSONObject("dsh");
                if (dsh == null) { dsh = new org.json.JSONObject(); o.put("dsh", dsh); }
                org.json.JSONObject profile = dsh.optJSONObject("profile");
                if (profile == null) { profile = new org.json.JSONObject(); dsh.put("profile", profile); }
                org.json.JSONArray bundles = profile.optJSONArray("bundles");
                if (bundles == null) { bundles = new org.json.JSONArray(); profile.put("bundles", bundles); }
                boolean has = false;
                for (int i = 0; i < bundles.length(); i++) {
                    if (pkgName.equals(bundles.optString(i))) { has = true; break; }
                }
                if (!has) bundles.put(pkgName);

                try (OutputStream os = new FileOutputStream(pkgJson)) {
                    os.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
                }
                log.append(pkgName).append(" 已装入（").append(files).append(" 个文件）；");
            } catch (Throwable t) {
                log.append(pkgName).append(" 失败: ").append(t).append("；");
            }
        }
        return log.toString();
    }

    // ══════════ 插件自救：停用某个插件 / 安全模式 ══════════
    //
    // 【为什么必须有这套东西】真机事故：用户装了两个**为 DSH 0.1.x 写的**第三方插件
    // （dsh-speech / dsh-mobile-gateway），它们的客户端声明
    // `inject = ["slots", "settingsScope"]` —— 而 `settingsScope` 这个服务名在 DSH 0.2
    // 里已经改成了 settings / settingsSchema。服务等不到，前端**永远停在 pending**，
    // 整个 GUI 起不来（白屏或 "Failed to load plugins"）。
    //
    // 而 App 在"服务就绪"后会把状态栏收起来 —— 用户连「重试」都点不到，
    // 唯一出路是卸载重装（等于把 150MB 容器再下一遍）。
    //
    // 所以要有"从 App 侧把坏插件摘掉"的能力：直接改 profile 的 bundles
    // （那才是真正决定加载谁的地方），被摘掉的记在 App 自己的 prefs 里，可一键恢复。

    /** 安全模式只加载这些：两个官方包 + 自带插件。 */
    private static final String[] SAFE_BUNDLES = {
            "@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app",
            PHONE_PLUGIN_NAME, PHONE_FILES_NAME
    };

    private static final String KEY_DISABLED = "disabled_bundles";
    /** 停用时把"依赖声明"也记下来（恢复时要照原样写回 dependencies）。 */
    private static final String KEY_DISABLED_SPECS = "disabled_bundle_specs";
    /** 被停用的插件包挪到这里 —— 目录都不在了，DSH 就没法把它加载回来。 */
    private static final String DISABLED_DIR = "node_modules/.dsh-disabled";

    /** profile 的 package.json —— 决定"加载哪些插件"的就是它。 */
    public static File profilePkg(Context c) {
        return new File(base(c), "rootfs/root/.dsh/profiles/web/package.json");
    }

    /** 当前 profile 里登记的 bundle 列表（读不到返回空表）。 */
    public static java.util.List<String> profileBundles(Context c) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            File f = profilePkg(c);
            if (!f.exists()) return out;
            org.json.JSONObject dsh = new org.json.JSONObject(readText(f)).optJSONObject("dsh");
            org.json.JSONObject prof = dsh == null ? null : dsh.optJSONObject("profile");
            org.json.JSONArray b = prof == null ? null : prof.optJSONArray("bundles");
            for (int i = 0; b != null && i < b.length(); i++) out.add(b.optString(i));
        } catch (Throwable ignore) { }
        return out;
    }

    /** 被我们停用的 bundle（存 App 的 prefs，不往 DSH 的配置里塞自定义字段）。 */
    public static java.util.List<String> disabledBundles(Context c) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            String s = c.getSharedPreferences(PhoneBridge.PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_DISABLED, "");
            if (s != null && !s.isEmpty()) {
                org.json.JSONArray a = new org.json.JSONArray(s);
                for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
            }
        } catch (Throwable ignore) { }
        return out;
    }

    private static void setDisabled(Context c, java.util.List<String> list) {
        try {
            c.getSharedPreferences(PhoneBridge.PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_DISABLED, new org.json.JSONArray(list).toString()).apply();
        } catch (Throwable ignore) { }
    }

    /** 安全模式是否生效中（即：有插件被我们摘掉了）。 */
    public static boolean safeModeActive(Context c) {
        return !disabledBundles(c).isEmpty();
    }

    /**
     * 把运行报告（report.txt）末尾若干行复制到剪贴板。
     *
     * <p>存在的意义：App 的大量现场诊断都写在这个文件里 ——
     * 界面白屏后的自动重连、附件为什么没进去、自带插件的安装结果……
     * 而它在 App 私有目录里，用户既看不见也导不出来。
     * 没有这个入口，"让用户把日志发我"就只是一句空话。
     *
     * @return 给界面显示的一句话（成功/失败原因）
     */
    public static String copyReportToClipboard(Context c, int maxLines) {
        File f = new File(base(c), "report.txt");
        if (!f.exists()) return "还没有日志文件";
        try {
            // 文件可能很大（几十万行），从尾部倒着攒够行数就停
            java.util.ArrayDeque<String> lines = new java.util.ArrayDeque<>();
            try (java.io.BufferedReader br =
                         new java.io.BufferedReader(new java.io.InputStreamReader(
                                 new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    lines.addLast(line);
                    while (lines.size() > maxLines) lines.removeFirst();
                }
            }
            String text = String.join("\n", lines);
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return "这台设备不支持剪贴板";
            cm.setPrimaryClip(android.content.ClipData.newPlainText("DSH 日志", text));
            return "已复制最近 " + lines.size() + " 行日志，粘贴发出来即可";
        } catch (Throwable t) {
            return "复制失败：" + t;
        }
    }

    /**
     * 【仅调试用】往 profile 的 bundles 里塞一个**不存在的包名**。
     *
     * <p>为什么需要它：真机事故里罪魁祸首是第三方插件，而这台测试机上
     * profile 只有官方两个包 + 自带的 dsh-phone（后者我特意放进安全名单），
     * 于是"停用第三方插件"这条路径永远是空集、测不到。
     * 塞一个假包名就能复现出**和真机一样的启动失败**（DSH 会报
     * "did not activate / waiting for service"），从而把
     * 「前端自检 → 自救面板 → 摘插件 → 重启恢复」整条链路跑通。
     *
     * @return 给界面看的一句话
     */
    public static String debugAddFakeBundle(Context c, String name) {
        try {
            File f = profilePkg(c);
            if (!f.exists()) return "profile 还不存在";
            org.json.JSONObject o = new org.json.JSONObject(readText(f));
            org.json.JSONObject dsh = o.optJSONObject("dsh");
            org.json.JSONObject prof = dsh == null ? null : dsh.optJSONObject("profile");
            if (prof == null) return "profile 结构不对";
            org.json.JSONArray b = prof.optJSONArray("bundles");
            if (b == null) { b = new org.json.JSONArray(); prof.put("bundles", b); }
            for (int i = 0; i < b.length(); i++) {
                if (name.equals(b.optString(i))) return "里面已经有 " + name + " 了";
            }
            b.put(name);
            try (OutputStream os = new FileOutputStream(f)) {
                os.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            return "已加入 " + name + "（现在共 " + b.length() + " 个 bundle）";
        } catch (Throwable t) {
            return "加入失败: " + t;
        }
    }

    /** 【仅调试用】把调试塞进去的假包名彻底移除（bundles + 待恢复清单）。 */
    public static String debugRemoveFakeBundle(Context c, String name) {
        int n = disableBundles(c, java.util.Arrays.asList(name));
        java.util.List<String> dis = disabledBundles(c);
        if (dis.remove(name)) setDisabled(c, dis);
        return "移除 " + name + "（bundles 命中 " + n + " 个）";
    }

    /**
     * 从 profile 的 bundles 里摘掉指定的几个。
     *
     * @return 实际摘掉的个数（0 表示这几个本来就不在，或 profile 还没建好）
     */
    public static int disableBundles(Context c, java.util.List<String> names) {
        if (names == null || names.isEmpty()) {
            android.util.Log.i("DSH_SAFEMODE", "disableBundles: 名字为空，不动");
            return 0;
        }
        try {
            File f = profilePkg(c);
            android.util.Log.i("DSH_SAFEMODE", "disableBundles(" + names + ") 文件=" + f
                    + " 存在=" + f.exists());
            if (!f.exists()) return 0;
            org.json.JSONObject o = new org.json.JSONObject(readText(f));
            org.json.JSONObject dsh = o.optJSONObject("dsh");
            org.json.JSONObject prof = dsh == null ? null : dsh.optJSONObject("profile");
            if (prof == null) return 0;
            org.json.JSONArray b = prof.optJSONArray("bundles");
            if (b == null) return 0;

            java.util.List<String> keep = new java.util.ArrayList<>();
            java.util.List<String> off = new java.util.ArrayList<>();
            for (int i = 0; i < b.length(); i++) {
                String name = b.optString(i);
                if (names.contains(name)) off.add(name); else keep.add(name);
            }
            if (off.isEmpty()) return 0;

            org.json.JSONArray nb = new org.json.JSONArray();
            for (String k : keep) nb.put(k);
            prof.put("bundles", nb);

            /*
             * ⚠️ 只改 bundles **不够** —— 用户实测"停用不生效"：
             * DSH 还会按自己的库存/依赖把包加载回来。所以这里同时做两件事：
             *   1) 从 dependencies 里摘掉（原声明记下来，恢复时照原样写回）；
             *   2) 把 node_modules/<包名> 整个挪到 .dsh-disabled/ 下 ——
             *      包目录都不在了，它绝无可能被加载。
             * 挪走而不是删除，所以能一键挪回来（这就是"回退到装插件之前"）。
             */
            java.util.Map<String, String> specs = new java.util.HashMap<>();
            org.json.JSONObject deps = o.optJSONObject("dependencies");
            File nodeModules = new File(profilePkg(c).getParentFile(), "node_modules");
            File grave = new File(nodeModules, ".dsh-disabled");
            for (String n : off) {
                try {
                    if (deps != null && deps.has(n)) { specs.put(n, deps.optString(n)); deps.remove(n); }
                    File from = new File(nodeModules, n);
                    if (from.exists()) {
                        if (!grave.exists()) //noinspection ResultOfMethodCallIgnored
                            grave.mkdirs();
                        File to = new File(grave, n);
                        deleteRecursively(to);
                        boolean moved = from.renameTo(to);
                        android.util.Log.i("DSH_SAFEMODE", "挪走包目录 " + n + " → " + moved
                                + " [源还在=" + from.exists() + " 目标目录在=" + to.exists()
                                + " 目标含package.json=" + new File(to, "package.json").exists() + "]");
                    } else {
                        android.util.Log.i("DSH_SAFEMODE", "包目录不存在（只改配置）：" + n);
                    }
                } catch (Throwable e2) {
                    android.util.Log.w("DSH_SAFEMODE", "挪走失败 " + n, e2);
                }
            }
            try {
                c.getSharedPreferences(PhoneBridge.PREFS, Context.MODE_PRIVATE).edit()
                        .putString(KEY_DISABLED_SPECS, new org.json.JSONObject(specs).toString()).apply();
            } catch (Throwable ignore) { }
            // 缩进写回，保持和 pnpm/dsh 自己写出来的格式一致（便于用户自己看）
            try (OutputStream os = new FileOutputStream(f)) {
                os.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
            }

            java.util.List<String> dis = disabledBundles(c);
            for (String x : off) if (!dis.contains(x)) dis.add(x);
            setDisabled(c, dis);
            android.util.Log.i("DSH_SAFEMODE", "停用成功 off=" + off + " 剩余=" + keep
                    + " 待恢复=" + dis);
            return off.size();
        } catch (Throwable t) {
            android.util.Log.w("DSH_SAFEMODE", "停用失败", t);
            return 0;
        }
    }

    /**
     * 进安全模式：只留官方包 + 自带插件，其余（第三方插件）全部停用并记下来。
     *
     * @return 被停用的个数
     */
    public static int enterSafeMode(Context c) {
        java.util.List<String> off = new java.util.ArrayList<>();
        java.util.List<String> all = profileBundles(c);
        for (String b : all) {
            boolean safe = false;
            for (String s : SAFE_BUNDLES) if (s.equals(b)) { safe = true; break; }
            if (!safe) off.add(b);
        }
        android.util.Log.i("DSH_SAFEMODE", "enterSafeMode: 当前=" + all + " 判定要停=" + off);
        return disableBundles(c, off);
    }

    /**
     * 把之前停用的插件全部恢复（一键回到正常模式）。
     *
     * @return 恢复的个数
     */
    public static int restoreDisabledBundles(Context c) {
        try {
            java.util.List<String> dis = disabledBundles(c);
            if (dis.isEmpty()) return 0;
            org.json.JSONObject specsJson;
            try {
                specsJson = new org.json.JSONObject(c.getSharedPreferences(PhoneBridge.PREFS, Context.MODE_PRIVATE)
                        .getString(KEY_DISABLED_SPECS, "{}"));
            } catch (Throwable t2) { specsJson = new org.json.JSONObject(); }
            File f = profilePkg(c);
            if (!f.exists()) return 0;
            org.json.JSONObject o = new org.json.JSONObject(readText(f));
            org.json.JSONObject dsh = o.optJSONObject("dsh");
            org.json.JSONObject prof = dsh == null ? null : dsh.optJSONObject("profile");
            if (prof == null) return 0;
            org.json.JSONArray b = prof.optJSONArray("bundles");
            if (b == null) { b = new org.json.JSONArray(); prof.put("bundles", b); }

            java.util.List<String> cur = new java.util.ArrayList<>();
            for (int i = 0; i < b.length(); i++) cur.add(b.optString(i));
            int n = 0;
            for (String x : dis) {
                if (!cur.contains(x)) { b.put(x); cur.add(x); n++; }
                // 包目录挪回来（停用时挪到了 .dsh-disabled/），依赖声明照原样写回
                try {
                    File nodeModules = new File(f.getParentFile(), "node_modules");
                    File from = new File(nodeModules, DISABLED_DIR.replace("node_modules/", "") + "/" + x);
                    File to = new File(nodeModules, x);
                    if (from.exists()) {
                        File parent = to.getParentFile();
                        if (parent != null) //noinspection ResultOfMethodCallIgnored
                            parent.mkdirs();
                        deleteRecursively(to);
                        android.util.Log.i("DSH_SAFEMODE", "挪回包目录 " + x + " → " + from.renameTo(to)
                                + " [坟场里还有=" + from.exists() + " 原位目录在=" + to.exists()
                                + " 原位含package.json=" + new File(to, "package.json").exists() + "]");
                    }
                    String spec = specsJson.optString(x, null);
                    if (spec != null && !spec.isEmpty()) {
                        org.json.JSONObject deps2 = o.optJSONObject("dependencies");
                        if (deps2 == null) { deps2 = new org.json.JSONObject(); o.put("dependencies", deps2); }
                        deps2.put(x, spec);
                    }
                } catch (Throwable ignore) { }
            }
            try (OutputStream os = new FileOutputStream(f)) {
                os.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            setDisabled(c, new java.util.ArrayList<>());
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 确保容器里有 pnpm —— 插件市场要用。
     *
     * <h3>为什么必须要它</h3>
     * {@code dsh plugin add <npm包>} 内部转发给 pnpm 做依赖解析与安装。
     * 我们自己的插件能靠"手工解包"绕过 pnpm，**只是因为它是零依赖的**；
     * 市场上任何一个正常的插件都带依赖，没有 pnpm 就装不了。
     *
     * <p>实测容器里 {@code /opt/node-…/bin/} 只有 corepack / dsh / node / npm / npx，
     * 没有 pnpm。所以这一步是插件市场的前置条件。
     *
     * <p>幂等：pnpm 已存在就直接返回。失败也不阻断启动 ——
     * 只是插件市场用不了，其它功能不受影响。
     */
    public static String ensurePnpm(File base, String tokenDir, String libDir) {
        try {
            File rootfs = new File(base, "rootfs");
            File binDir = new File(rootfs, "opt/" + NODE_DIR + "/bin");
            File pnpm = new File(binDir, "pnpm");
            if (pnpm.exists()) return "pnpm 已就绪";

            String nodePath = "/opt/" + NODE_DIR + "/bin";
            java.util.List<String> args = new java.util.ArrayList<>();
            args.add("-g"); args.add("install"); args.add("pnpm");
            String[] cmd = containerCommand(base, null, tokenDir, nodePath + "/npm", args);

            Process p = buildProcess(base, cmd, libDir).start();
            StringBuilder out = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (out) { out.append(line).append('\n'); }
                    }
                } catch (Throwable ignore) { }
            }, "pnpm-install-reader");
            reader.setDaemon(true);
            reader.start();

            boolean done = p.waitFor(PLUGIN_INSTALL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroyForcibly();
                return "装 pnpm 超时，插件市场暂时不可用（其它功能不受影响）";
            }
            reader.join(2000);

            // 以文件是否存在为准，而不是退出码
            if (pnpm.exists()) return "pnpm 已装好，插件市场可用";
            String log;
            synchronized (out) { log = out.toString().trim(); }
            return "装 pnpm 没成功（插件市场将不可用）：" + tail(log, 300);
        } catch (Throwable t) {
            return "装 pnpm 异常（插件市场将不可用）: " + t;
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursively(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** 插件安装最多等这么久（留给将来可能恢复的 pnpm 路径）。 */
    private static final long PLUGIN_INSTALL_TIMEOUT_MS = 240_000L;

    private static String readText(File f) {
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String tail(String s, int max) {
        return s.length() <= max ? s : "…" + s.substring(s.length() - max);
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

            /*
             * 工作区里也放一份 AGENTS.md。
             *
             * 早先是"已存在就不覆盖"，结果踩了坑：修好微信读取方式（改用截图）之后，
             * 老用户工作区里那份**旧说明一直留着**，还在教 agent 用 `phone ui` 读微信
             * —— 而那条路对微信永远返回空树。能力升级了，说明书却没升级。
             *
             * 改法：**只覆盖我们自己写的那份**。判断依据是文件里有我们的固定标题；
             * 用户自己写的 / 改过的 AGENTS.md 不含这个标记，原样保留，绝不覆盖。
             */
            File agents = new File(SDCARD_HOST + "/" + TEST_DIR_NAME, "AGENTS.md");
            if (shouldWriteAgents(agents)) {
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

    /**
     * 容器里的组件是否都已就绪。
     *
     * <p>【为什么要连 /usr/bin/env 和 /bin/sh 一起看】这两个是**快速路径的守门人**：
     * 启动时 {@code isInstalled()} 为真 + 自检标记有效，就会跳过全部探针直接启动。
     * 而"rootfs 解压到一半"的坏容器恰恰能骗过原来那条只看 bash 的判断
     * （归档按字母序解压，bash 在 env 前面），于是每次启动都直接跳过自检、
     * 然后在 proot 里报 `'/usr/bin/env' not found` —— 用户看到的就是
     * "装不上、点重试也没用"。这里把判据补齐，坏容器一定会重新走完整自检。
     *
     * <p>{@code bin/sh} 是 merged-/usr 的符号链接，顺手验证符号链接有没有正确还原。
     */
    public static boolean isInstalled(File base) {
        return new File(base, "rootfs/usr/bin/bash").exists()
                && new File(base, "rootfs/usr/bin/env").exists()
                && new File(base, "rootfs/bin/sh").exists()
                && new File(base, "rootfs/opt/" + NODE_DIR + "/bin/node").exists()
                && new File(base, "rootfs/opt/" + NODE_DIR + "/bin/dsh").exists();
    }
}
