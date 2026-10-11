import com.dshmobile.probe.Storyboard;

import java.util.ArrayList;
import java.util.List;

/**
 * Storyboard 的回归测试 —— 「看一段屏幕」里最容易写错、又最影响结果的两件事：
 * **帧间差异怎么算**、**挑帧判据怎么定**。
 *
 * 为什么值得测：
 *   · 判据太松 → 等于没省（把一整段帧全喂给模型，正是我们要避免的）；
 *   · 判据太紧 → 漏掉关键画面（用户问"刚才发生了什么"答不上来）；
 *   · 首帧漏了 → 后面的差异没有参照物，"变成了什么样"讲不清；
 *   · 超过上限时简单砍尾巴 → 丢掉"最后变成了什么"，而那往往正是用户最关心的。
 *
 * 用法（在仓库根目录）:
 *   javac -encoding UTF-8 -d %TEMP%/sbtest tools/StoryboardSelfTest.java ^
 *         DshMobile/app/src/main/java/com/dshmobile/probe/Storyboard.java
 *   java -Dstdout.encoding=UTF-8 -cp %TEMP%/sbtest StoryboardSelfTest
 */
public class StoryboardSelfTest {

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

    /** 造一帧灰度（全 value）。 */
    private static byte[] flat(int value) {
        byte[] b = new byte[1024];
        java.util.Arrays.fill(b, (byte) value);
        return b;
    }

    private static List<Storyboard.Frame> frames(int... diffs) {
        List<Storyboard.Frame> l = new ArrayList<>();
        for (int i = 0; i < diffs.length; i++) {
            l.add(new Storyboard.Frame(i, i * 500L, diffs[i]));
        }
        return l;
    }

    private static String keys(List<Storyboard.Frame> picked) {
        StringBuilder sb = new StringBuilder();
        for (Storyboard.Frame f : picked) sb.append(f.index).append(' ');
        return sb.toString().trim();
    }

    public static void main(String[] args) {
        System.out.println("── 帧间差异 ──");
        eq("完全相同的两帧 = 0", 0, Storyboard.diff(flat(100), flat(100)));
        eq("全黑 vs 全白 = 255", 255, Storyboard.diff(flat(0), flat(255)));
        eq("差 10 的两帧 = 10", 10, Storyboard.diff(flat(100), flat(110)));
        eq("null 当\"完全变了\"", 255, Storyboard.diff(null, flat(1)));
        eq("长度不一致当\"完全变了\"", 255,
                Storyboard.diff(new byte[10], new byte[20]));
        eq("空数组", 255, Storyboard.diff(new byte[0], new byte[0]));

        System.out.println("── 挑帧：首帧必须在（它是参照物）──");
        List<Storyboard.Frame> f1 = frames(255, 0, 0, 0, 0);
        eq("全都没变也保留首帧", "0", keys(Storyboard.pick(f1, 8, 12)));

        System.out.println("── 挑帧：阈值边界 ──");
        List<Storyboard.Frame> f2 = frames(255, 8, 9, 7, 20);
        // 首帧 + 9（>8）+ 20（>8）；8 与 7 不算"变了"（阈值是"严格大于"）
        eq("只有 >threshold 的才算关键帧", "0 2 4", keys(Storyboard.pick(f2, 8, 12)));
        List<Storyboard.Frame> f3 = frames(255, 5, 5, 5);
        eq("阈值 0 时连微小变化都留", "0 1 2 3", keys(Storyboard.pick(f3, 0, 12)));

        System.out.println("── 挑帧：上限与抽稀（不能简单砍尾巴）──");
        List<Storyboard.Frame> f4 = frames(255, 50, 50, 50, 50, 50, 50, 50, 50, 50);
        List<Storyboard.Frame> p4 = Storyboard.pick(f4, 8, 3);
        eq("最多 3 张", 3, p4.size());
        eq("第 0 张仍是首帧", 0, p4.get(0).index);
        eq("最后一张必须留到最后（不是砍尾巴）", 9, p4.get(2).index);
        ok("中间那张在中间位置", p4.get(1).index > 0 && p4.get(1).index < 9,
                "实得 " + p4.get(1).index);
        List<Storyboard.Frame> p4b = Storyboard.pick(frames(255, 50, 50, 50, 50), 8, 2);
        eq("上限 2 → 首尾", "0 4", keys(p4b));
        eq("上限 1 → 只留首帧", "0", keys(Storyboard.pick(frames(255, 50, 50), 8, 1)));

        System.out.println("── 挑帧：key 标记要正确（拼图靠它筛）──");
        List<Storyboard.Frame> f5 = frames(255, 0, 30, 0);
        Storyboard.pick(f5, 8, 12);
        StringBuilder marked = new StringBuilder();
        for (Storyboard.Frame f : f5) marked.append(f.key ? "1" : "0");
        eq("标记 1/0 与实际挑选一致", "1010", marked.toString());

        System.out.println("── 布局 ──");
        Storyboard.Layout l = Storyboard.layout(7, 3, 100, 200, 6, 6);
        eq("列数", 3, l.cols);
        eq("行数（向上取整）", 3, l.rows);
        eq("画布宽 = 留白*2 + 3*106 - 6", 6 * 2 + 3 * 106 - 6, l.canvasW);
        eq("画布高 = 留白*2 + 3*206 - 6", 6 * 2 + 3 * 206 - 6, l.canvasH);
        eq("第 0 格坐标", "6,6", (6 + l.x(0)) + "," + (6 + l.y(0)));
        eq("第 3 格换到第二行", "6," + (6 + 206), (6 + l.x(3)) + "," + (6 + l.y(3)));
        // 7 张、3 列 → 第 6 张是**第三行第一列**（不是第三列！我第一版期望值就写错了，自检抓出来了）
        eq("第 6 格是第三行第一列", "6," + (6 + 412), (6 + l.x(6)) + "," + (6 + l.y(6)));
        Storyboard.Layout l2 = Storyboard.layout(2, 4, 50, 50, 0, 0);
        eq("列数不能超过张数", 2, l2.cols);
        eq("两张一行", 1, l2.rows);
        Storyboard.Layout l3 = Storyboard.layout(0, 3, 10, 10, 0, 0);
        eq("0 张也当成 1 格（不崩）", 1, l3.rows);

        System.out.println("── 元数据 JSON（agent 要能解析）──");
        List<Storyboard.Frame> f6 = frames(255, 30);
        Storyboard.pick(f6, 8, 12);
        List<String> paths = new ArrayList<>();
        paths.add("/sdcard/.dsh-phone/key-0.png");
        paths.add("/sdcard/.dsh-phone/key-1.png");
        String json = Storyboard.toJson(f6, 2, 8, "/sdcard/.dsh-phone/storyboard.png", paths);
        ok("带 sheet 路径", json.contains("\"sheet\": \"/sdcard/.dsh-phone/storyboard.png\""), json);
        ok("sampled=2", json.contains("\"sampled\": 2"), json);
        ok("keys=2", json.contains("\"keys\": 2"), json);
        ok("每帧带 t_ms 与 diff", json.contains("\"t_ms\": 0") && json.contains("\"diff\": 30"), json);
        ok("带单帧文件路径", json.contains("key-1.png"), json);
        ok("JSON 能被简单解析（括号配平）",
                json.chars().filter(c -> c == '{').count() == json.chars().filter(c -> c == '}').count(), json);

        System.out.println();
        if (fail == 0) System.out.println("全部通过 ✅");
        else { System.out.println("有 " + fail + " 项失败 ❌"); System.exit(1); }
    }
}
