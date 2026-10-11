import com.dshmobile.probe.ProcLogic;

/**
 * ProcLogic 的回归测试 —— 「清理残留子进程」里唯一危险的一步：**判断哪些进程是我们的**。
 *
 * 为什么必须有它：这条判断写错的后果是**误杀别的应用的进程**（不是"没清干净"那么轻）。
 * 所以这里用真实 cmdline 形状做用例，把"该匹配的"和"绝不能匹配的"都钉住。
 *
 * 用例取材自真机（2026-10-05，vivo V2408A / Android 16）：
 *   · 容器外壳：`proot -r /data/user/0/com.dshmobile.probe/files/probe/rootfs -0 -w /sdcard/dsh …`
 *   · DSH 本体：`node /opt/node-v22.23.2-linux-arm64/bin/dsh --profile web --no-open --port 3080`
 *   · 容器里的 node（第一版漏了这类：清完还剩下一个 185MB 的 node）
 *
 * 用法（在仓库根目录）:
 *   javac -encoding UTF-8 -d %TEMP%/pctest tools/ProcCleanupSelfTest.java ^
 *         DshMobile/app/src/main/java/com/dshmobile/probe/ProcCleanup.java ^
 *         DshMobile/app/src/main/java/com/dshmobile/probe/Env.java
 *   java -Dstdout.encoding=UTF-8 -cp %TEMP%/pctest ProcCleanupSelfTest
 *
 * 注意：Env 依赖 Android（它只是被 ProcCleanup 引用到字段），所以正式自检清单里
 * **只编 ProcCleanup 需要的那部分**——见交接文档第 5 节与 tools/_selftest 的清单。
 * 本文件本身不碰 Android API。
 */
public class ProcCleanupSelfTest {

    private static int fail = 0;

    private static void ok(String what, boolean cond, String detail) {
        if (!cond) fail++;
        System.out.println((cond ? "  ✅ " : "  ❌ ") + what + (cond ? "" : "\n       " + detail));
    }

    private static void eq(String what, Object want, Object got) {
        boolean c = want == null ? got == null : want.equals(got);
        if (!c) fail++;
        System.out.println((c ? "  ✅ " : "  ❌ ") + what + (c ? "" : "\n       期望 " + want + " ／ 实得 " + got));
    }

    private static final String ROOTFS =
            "/data/user/0/com.dshmobile.probe/files/probe/rootfs";
    private static final String NODE_BIN = ROOTFS + "/opt/node-v22.23.2-linux-arm64/bin/node";

    /** 分类结果的 kind（null 表示"不是我们的"）。 */
    private static String kind(String cmd) {
        ProcLogic.Item it = ProcLogic.classify(cmd, ROOTFS, NODE_BIN);
        return it == null ? null : it.kind;
    }

    public static void main(String[] args) {
        System.out.println("── 必须认出来的（真机 cmdline 原文）──");
        eq("容器外壳 proot",
                "容器外壳（proot）",
                kind("proot -r " + ROOTFS + " -0 -w /sdcard/dsh --link2symlink --kill-on-exit "
                        + "-b /dev -b /proc -b /sys -b /storage/emulated/0:/sdcard "
                        + "-b /data/user/0/com.dshmobile.probe/files/probe:/app-data "
                        + "/usr/bin/env -i HOME=/root /opt/node-v22.23.2-linux-arm64/bin/dsh --profile web"));
        eq("DSH 本体（绝对路径形式）",
                "DSH 本体（node）",
                kind("node /opt/node-v22.23.2-linux-arm64/bin/dsh --profile web --no-open --port 3080"));
        eq("候选一：proot 带自己的 rootfs",
                "容器外壳（proot）",
                kind("proot -r " + ROOTFS + " -0 -w /sdcard/dsh"));
        // 注意：这里必须是**绝对路径**形式。真机（ps -A 抓到的原文）就是
        // `/opt/node-…/bin/dsh --profile web`；规则要求出现 `/bin/dsh`
        // 也是故意的 —— 光看 "dsh --profile" 会误杀名字里带 dsh 的别的程序。
        eq("候选二：dsh --profile（绝对路径）",
                "DSH 本体（node）",
                kind("/opt/node-v22.23.2-linux-arm64/bin/dsh --profile web"));
        eq("候选三：容器里的 node",
                "容器里的 node",
                kind(NODE_BIN + " /root/.dsh/plugins/foo.js"));

        System.out.println("── 绝不能误杀的（别的应用 / 系统进程）──");
        eq("别人的 proot（不是我们的 rootfs）", null,
                kind("proot -r /data/data/com.termux/files/usr -0 -w /"));
        eq("别的 node 服务", null,
                kind("node /data/data/com.other.app/files/server.js --port 8080"));
        eq("别的应用名里带 dsh", null,
                kind("com.example.dshhelper --profile something"));
        eq("系统进程", null, kind("android.hardware.sensors@2.1-service"));
        eq("shell 自己", null, kind("sh -c ps -A -o PID,ARGS"));
        eq("空 cmdline（内核线程就是这样）", null, kind(""));
        eq("null", null, kind(null));
        eq("rootfs 路径为空时不许按 proot 模糊匹配", null,
                ProcLogic.classify("proot -r /whatever -0 -w /", "", null) == null
                        ? null : "（不该匹配）");
        eq("nodeBin 为空时不匹配 node 规则", null,
                ProcLogic.classify("node /somewhere/else.js", ROOTFS, null) == null
                        ? null : "（不该匹配）");

        System.out.println("── describe 的人话（没残留时也要说清楚）──");
        ProcLogic.Item it = ProcLogic.classify("/opt/node-v22.23.2-linux-arm64/bin/dsh --profile web", ROOTFS, NODE_BIN);
        ok("cmdline 过长会被截断", it != null && it.cmd.length() <= 165, "长度=" + (it == null ? -1 : it.cmd.length()));
        String longCmd = "/opt/node-v22.23.2-linux-arm64/bin/dsh --profile web " + repeat("x", 400);
        ProcLogic.Item it2 = ProcLogic.classify(longCmd, ROOTFS, NODE_BIN);
        ok("超长 cmdline 截断到 160+省略号",
                it2 != null && it2.cmd.endsWith("…") && it2.cmd.length() == 161,
                it2 == null ? "null" : ("长度=" + it2.cmd.length()));

        System.out.println();
        if (fail == 0) System.out.println("全部通过 ✅");
        else { System.out.println("有 " + fail + " 项失败 ❌"); System.exit(1); }
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}
