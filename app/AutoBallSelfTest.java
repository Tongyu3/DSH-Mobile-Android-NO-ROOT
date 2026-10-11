import com.dshmobile.probe.AutoBall;

/**
 * AutoBall 的回归测试 —— 「悬浮球什么时候出现」的判据。
 *
 * 为什么值得测：这段逻辑的错误方式全是**静默的**，而且都在设备上不好复现：
 *   · 漏显示 → 用户以为功能根本没用；
 *   · 多显示 → 在设置页挡路（用户**点名**不要这个）；
 *   · 判断太敏感 → 等网络时球一直不消失；
 *   · 判断太迟钝 → 任务都跑完一半了球才出现。
 *
 * 用法（在仓库根目录）:
 *   javac -encoding UTF-8 -d %TEMP%/abtools tools/AutoBallSelfTest.java ^
 *         DshMobile/app/src/main/java/com/dshmobile/probe/AutoBall.java
 *   java -Dstdout.encoding=UTF-8 -cp %TEMP%/abtools AutoBallSelfTest
 */
public class AutoBallSelfTest {

    private static int fail = 0;

    private static void ok(String what, boolean cond, String detail) {
        if (!cond) fail++;
        System.out.println((cond ? "  OK  " : "  FAIL") + " " + what + (cond ? "" : "  <- " + detail));
    }

    public static void main(String[] args) {
        long T = 1_000_000L;   // 随便一个"现在"

        System.out.println("-- 规则 1：自己的界面在前台一律不显示（含设置页）--");
        ok("主界面在前台 + 有任务 -> 不显示", !AutoBall.shouldShow(true, true, true), "不该显示");
        ok("设置页在前台 + 有任务 -> 不显示", !AutoBall.shouldShow(true, true, true), "不该显示");
        ok("我们在前台 + 无任务 -> 不显示", !AutoBall.shouldShow(true, true, false), "不该显示");
        ok("**常驻模式下我们在前台也不显示**（否则会挡住设置页）",
                !AutoBall.shouldShow(false, true, false), "不该显示");

        System.out.println("-- 规则 2：关掉自动 = 常驻 --");
        ok("常驻 + 退到后台 + 无任务 -> 显示", AutoBall.shouldShow(false, false, false), "应显示");

        System.out.println("-- 规则 3：自动模式下看有没有任务 --");
        ok("自动 + 后台 + 有任务 -> 显示", AutoBall.shouldShow(true, false, true), "应显示");
        ok("自动 + 后台 + 无任务 -> 不显示", !AutoBall.shouldShow(true, false, false), "不该显示");

        System.out.println("-- busy 判据 --");
        ok("容器没进程 -> 不算活跃", !AutoBall.busy(1000, 5000, 0, 5000), "procs=0");
        ok("时间没走 -> 不算活跃", !AutoBall.busy(1000, 5000, 3, 0), "dt<=0");
        ok("烧了 100ms -> 活跃", AutoBall.busy(1000, 1100, 3, 5000), "应活跃");
        ok("只烧了 5ms -> 不活跃（等网络）", !AutoBall.busy(1000, 1005, 3, 5000), "不应活跃");
        ok("正好到阈值 30ms -> 活跃", AutoBall.busy(1000, 1030, 3, 5000), "应活跃");
        ok("差 29ms -> 不活跃", !AutoBall.busy(1000, 1029, 3, 5000), "不应活跃");
        ok("CPU 没涨 -> 不活跃", !AutoBall.busy(5000, 5000, 3, 5000), "不应活跃");

        System.out.println("-- 活跃保持窗口（防止球一闪一闪）--");
        long last = AutoBall.markActive(0, T, true);
        ok("发现活跃 -> 记下时刻", last == T, "got " + last);
        ok("刚活跃过 -> 还在窗口内", AutoBall.stillActive(last, T + 1), "应 true");
        ok("窗口边界：7999ms -> 仍在", AutoBall.stillActive(last, T + 7999), "应 true");
        ok("窗口边界：8000ms -> 到期", !AutoBall.stillActive(last, T + 8000), "应 false");
        long kept = AutoBall.markActive(last, T + 5000, false);
        ok("不活跃时不刷新时刻", kept == last, "got " + kept);
        ok("从未活跃过 -> false", !AutoBall.stillActive(0, T), "应 false");

        System.out.println("-- 一个完整过程（模拟两轮 5 秒采样）--");
        // t=0 干活、t=5s 空闲但窗口内、t=20s 早已过期
        long a = AutoBall.markActive(0, T, true);
        ok("t=0 干了活 -> 显示", AutoBall.shouldShow(true, false, AutoBall.stillActive(a, T)), "");
        ok("t=+5s 空闲但在窗口内 -> 仍显示",
                AutoBall.shouldShow(true, false, AutoBall.stillActive(a, T + 5000)), "");
        ok("t=+10s 超过窗口 -> 消失",
                !AutoBall.shouldShow(true, false, AutoBall.stillActive(a, T + 10000)), "");
        ok("t=+10s 但用户回到主界面 -> 也不显示",
                !AutoBall.shouldShow(true, true, true), "");

        System.out.println();
        if (fail == 0) System.out.println("全部通过");
        else { System.out.println("有 " + fail + " 项失败"); System.exit(1); }
    }
}
