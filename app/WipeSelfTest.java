import com.dshmobile.probe.Wipe;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;

/**
 * Wipe（删除历史对话）的离线回归 —— 在**真实临时目录**上跑，不碰用户数据。
 *
 * 为什么要这个：删除是**不可恢复**的操作，而 release 包在真机上没法用 run-as
 * 验证（package not debuggable）。所以把文件操作抽成纯逻辑 Wipe，
 * 在这里用真目录验证"含归档、彻底清除、不留残渣、活跃目录被重建"。
 *
 * 用法（仓库根目录）:
 *   javac -encoding UTF-8 -d %TEMP%\wtest tools\WipeSelfTest.java ^
 *         DshMobile\app\src\main\java\com\dshmobile\probe\Wipe.java
 *   java -cp %TEMP%\wtest WipeSelfTest
 */
public class WipeSelfTest {

    private static int fail = 0;

    private static void check(String what, boolean ok, String detail) {
        if (!ok) fail++;
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + what
                + (detail == null || detail.isEmpty() ? "" : "  -> " + detail));
    }

    /** 造一个会话目录，返回它。 */
    private static File mk(File base, String rel) throws Exception {
        File f = new File(base, rel);
        if (!f.getParentFile().exists()) f.getParentFile().mkdirs();
        return f;
    }

    private static void write(File f, String body) throws Exception {
        if (!f.getParentFile().exists()) f.getParentFile().mkdirs();
        FileWriter w = new FileWriter(f);
        w.write(body);
        w.close();
    }

    public static void main(String[] args) throws Exception {
        File base = Files.createTempDirectory("wipe-test").toFile();
        File sessions = new File(base, "rootfs/root/.dsh/sessions");
        File archive = new File(base, "rootfs/root/.dsh/dsh-session-archive");

        System.out.println("---- case 1: 活跃会话 + 已归档 都存在 ----");
        // 活跃会话：3 个会话目录，每个里面有文件
        for (int i = 0; i < 3; i++) {
            write(new File(sessions, "--ws-" + i + "/log.jsonl"), "{\"a\":1}\n");
        }
        // 已归档：2 个
        for (int i = 0; i < 2; i++) {
            write(new File(archive, "--old-" + i + "/state.json"), "{}\n");
        }
        // 归档目录下还有一个 state.json（DSH 的真实结构里就有）
        write(new File(archive, "state.json"), "{\"v\":1}\n");

        int before = Wipe.countEntries(sessions, archive);
        check("删除前能数到项（活跃3 + 归档3 = 6）", before == 6, "实得 " + before);

        Wipe.Result r = Wipe.wipe(sessions, archive);
        check("活跃会话目录已清空", Wipe.countEntries(sessions, null) == 0,
                "剩 " + Wipe.countEntries(sessions, null));
        check("已归档目录已清空", Wipe.countEntries(null, archive) == 0,
                "剩 " + Wipe.countEntries(null, archive));
        check("活跃会话目录被重建（DSH 下次写入不会报错）", sessions.isDirectory(),
                sessions.getAbsolutePath());
        check("删除结果无失败", r.failed == 0, r.toString());
        check("统计到了文件", r.files > 0, r.toString());

        System.out.println("---- case 2: 只有活跃、没有归档目录 ----");
        write(new File(sessions, "--only-1/log.jsonl"), "x\n");
        Wipe.Result r2 = Wipe.wipe(sessions, archive);
        check("缺归档目录时不抛异常", r2.failed == 0, r2.toString());
        check("活跃目录仍被清空", Wipe.countEntries(sessions, null) == 0, "");

        System.out.println("---- case 3: 目录本来就不存在（首次启动场景） ----");
        File none1 = new File(base, "nope/sessions");
        File none2 = new File(base, "nope/archive");
        Wipe.Result r3 = new Wipe.Result();
        try {
            r3 = Wipe.wipe(none1, none2);
        } catch (Throwable t) {
            check("不存在时不抛异常", false, String.valueOf(t));
        }
        check("不存在时不抛异常且无失败", r3.failed == 0, r3.toString());
        check("活跃目录被创建出来", none1.isDirectory(), none1.getAbsolutePath());

        System.out.println("---- case 4: 深层嵌套也要彻底删掉 ----");
        write(new File(sessions, "a/b/c/d/deep.jsonl"), "deep\n");
        write(new File(sessions, "a/b/c/e/other.jsonl"), "other\n");
        Wipe.wipe(sessions, archive);
        check("深层子目录已彻底清除（不留空壳）",
                !new File(sessions, "a").exists() && Wipe.countEntries(sessions, null) == 0, "");

        System.out.println("---- case 5: countEntries 对 null 安全 ----");
        check("null 目录不抛异常", Wipe.countEntries(null, null) == 0, "");

        System.out.println(fail == 0 ? "\nALL PASS" : "\nFAILED " + fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}
