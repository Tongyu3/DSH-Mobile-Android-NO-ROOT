import com.dshmobile.probe.BallStage;

/**
 * BallStage（悬浮球任务阶段判定）的离线回归测试。
 *
 * 为什么要它：气泡上的字直接决定用户对这一眼的判断 ——
 * 判错很难看（例如把一次失败显示成"正在编译文件…"）。
 * 而这段逻辑是纯计算，能在 PC 上几毫秒跑完，没有理由不做回归。
 *
 * 用例来源：
 *   · 真机 DSH stdout 里出现过的真实行（编译、写文件、跑命令、报错）；
 *   · 用户明确点名的五个文案（思考中/正在编译文件/正在努力干活/已完成/出错了）。
 *
 * 用法（仓库根目录）:
 *   javac -encoding UTF-8 -d %TEMP%\bstest tools\BallStageSelfTest.java ^
 *         DshMobile\app\src\main\java\com\dshmobile\probe\BallStage.java
 *   java -cp %TEMP%\bstest BallStageSelfTest
 */
public class BallStageSelfTest {

    private static int fail = 0;

    private static void expect(String what, Object got, Object want) {
        boolean ok = (got == null ? want == null : got.equals(want));
        if (!ok) fail++;
        System.out.println((ok ? "  ✅ " : "  ❌ ") + what + "（实得 " + got + "）");
    }

    private static void expectStage(String what, int got, int want) {
        boolean ok = (got == want);
        if (!ok) fail++;
        System.out.println((ok ? "  ✅ " : "  ❌ ") + what
                + "（实得 " + BallStage.name(got) + "，期望 " + BallStage.name(want) + "）");
    }

    public static void main(String[] args) {
        System.out.println("── 阶段判定：优先级的正确性 ──");
        // 错误必须压过"正在编译"：同一行里两个词都有时，不能报"正在编译"
        expectStage("build failed → 出错了（不是正在编译）",
                BallStage.detect("build failed: cannot find module", true, false, null),
                BallStage.ERROR);
        expectStage("普通报错 → 出错了",
                BallStage.detect("Error: connect ECONNREFUSED", true, false, null),
                BallStage.ERROR);
        expectStage("中文报错 → 出错了",
                BallStage.detect("写入失败：权限不足", true, false, null),
                BallStage.ERROR);

        System.out.println("── 阶段判定：编译 / 干活 / 思考 ──");
        expectStage("gradle 构建 → 正在编译文件",
                BallStage.detect("> Task :app:compileDebugJavaWithJavac", true, false, null),
                BallStage.COMPILING);
        expectStage("中文编译 → 正在编译文件",
                BallStage.detect("正在编译文件…", true, false, null),
                BallStage.COMPILING);
        expectStage("写文件 → 正在努力干活",
                BallStage.detect("write /sdcard/dsh/hello.txt", true, false, null),
                BallStage.WORKING);
        expectStage("跑普通命令 → 正在努力干活",
                BallStage.detect("exec: git status", true, false, null),
                BallStage.WORKING);
        expectStage("读文件 → 正在努力干活",
                BallStage.detect("read /sdcard/dsh/notes.md", true, false, null),
                BallStage.WORKING);
        /*
         * 歧义场景**专门断言**：`npm run build` 既像"跑命令"又像"构建"。
         * 这里刻意让它判成 COMPILING —— 因为对用户来说"正在编译文件…"信息量更大
         * （他知道这一步慢、要等），而不是笼统的"正在干活"。
         * 这条规则是**有意选择**，不是巧合，所以必须有用例钉住。
         */
        expectStage("跑构建命令 → 正在编译文件（有意选择）",
                BallStage.detect("exec: npm run build", true, false, null),
                BallStage.COMPILING);
        expectStage("无输出但有任务 → 思考中",
                BallStage.detect(null, true, false, null),
                BallStage.THINKING);
        expectStage("输出不可归类但有任务 → 思考中",
                BallStage.detect("……", true, false, null),
                BallStage.THINKING);

        System.out.println("── 阶段判定：初始环境 / 完成 / 空闲 ──");
        expectStage("服务在解压 → 正在准备运行环境",
                BallStage.detect(null, true, false, "解压容器中"),
                BallStage.BOOT);
        expectStage("看到 complete 且任务已停 → 已完成",
                BallStage.detect("All complete", false, false, "运行中"),
                BallStage.DONE);
        expectStage("看到 complete 但任务还在跑 → 仍在干活",
                BallStage.detect("done", true, false, "运行中"),
                BallStage.WORKING);
        expectStage("刚跑完（CPU 落下）→ 已完成",
                BallStage.detect(null, false, true, "运行中"),
                BallStage.DONE);
        expectStage("没任务 → 空闲",
                BallStage.detect(null, false, false, "运行中"),
                BallStage.IDLE);

        System.out.println("── 文案：内置默认覆盖用户点名的五句 ──");
        expect("THINKING 文案", BallStage.text(BallStage.THINKING, null), "思考中…");
        expect("COMPILING 文案", BallStage.text(BallStage.COMPILING, null), "正在编译文件…");
        expect("WORKING 文案", BallStage.text(BallStage.WORKING, null), "正在努力干活…");
        expect("DONE 文案", BallStage.text(BallStage.DONE, null), "已完成");
        expect("ERROR 文案", BallStage.text(BallStage.ERROR, null), "出错了");

        System.out.println("── 文案：用户配置优先（可扩展）──");
        String custom = "THINKING=让我想想|WORKING=干活中|FOO=bar";
        expect("配置能覆盖 THINKING", BallStage.text(BallStage.THINKING, custom), "让我想想");
        expect("配置能覆盖 WORKING", BallStage.text(BallStage.WORKING, custom), "干活中");
        expect("没配的仍走内置（COMPILING）",
                BallStage.text(BallStage.COMPILING, custom), "正在编译文件…");
        expect("未知阶段键也能被配置",
                BallStage.lookup(custom, "FOO"), "bar");
        expect("查不到的键返回 null", BallStage.lookup(custom, "NOPE"), null);

        System.out.println("── 配置解析：坏条目要被丢掉而不是炸掉 ──");
        expect("解析出合法条目数", BallStage.parseConfig("A=1|B=2|坏条目|C=").size(), 2);
        expect("解析空串得到空表", BallStage.parseConfig("").size(), 0);
        expect("解析 null 得到空表", BallStage.parseConfig(null).size(), 0);
        expect("内置表能摊平成配置串再查回来",
                BallStage.lookup(BallStage.tableToConfig(BallStage.DEFAULT_TEXT), "ERROR"), "出错了");

        System.out.println("── 节流与去重（用户明确要求）──");
        BallStage.Throttle th = new BallStage.Throttle();
        expect("首次必然刷新", th.shouldUpdate(BallStage.THINKING, "a", 1000L), true);
        expect("同阶段同明细 → 不刷", th.shouldUpdate(BallStage.THINKING, "a", 1200L), false);
        expect("同阶段明细变了但未过节流窗口 → 不刷",
                th.shouldUpdate(BallStage.THINKING, "b", 1300L), false);
        expect("过了节流窗口 → 刷",
                th.shouldUpdate(BallStage.THINKING, "b", 1000L + BallStage.Throttle.MIN_INTERVAL_MS + 1), true);
        expect("阶段变化 → 立刻刷（不被节流拖住）",
                th.shouldUpdate(BallStage.COMPILING, "c", 1000L + BallStage.Throttle.MIN_INTERVAL_MS + 2), true);
        th.reset();
        expect("reset 后必然刷新", th.shouldUpdate(BallStage.COMPILING, "c", 9999L), true);

        System.out.println("── 边界：null / 空串不许抛异常 ──");
        expectStage("lastLine=null 不抛", BallStage.detect(null, false, false, null), BallStage.IDLE);
        expectStage("空串不抛", BallStage.detect("", false, false, ""), BallStage.IDLE);
        expect("大小写不敏感（ERROR / error）",
                BallStage.name(BallStage.detect("ERROR: boom", true, false, null)), "ERROR");

        System.out.println(fail == 0 ? "\n✅ 全部通过" : "\n❌ 失败 " + fail + " 项");
        System.exit(fail == 0 ? 0 : 1);
    }
}
