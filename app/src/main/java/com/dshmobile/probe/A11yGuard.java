package com.dshmobile.probe;

import android.content.Context;
import android.util.Log;

/**
 * 「无障碍守护」看门狗：发现无障碍开关被关掉就把它写回去。
 *
 * <h3>触发方式（两条腿走路）</h3>
 * <ul>
 *   <li><b>事件驱动</b>：{@link DshAccessibilityService#onUnbind} 里调 {@link #nudge()}，
 *       服务被解绑的瞬间就开始检查 —— 厂商杀权限时这是最快的信号。</li>
 *   <li><b>轮询兜底</b>：每 {@link #POLL_MS} 毫秒读一次设置。
 *       有些 ROM 是"改设置但不解绑"，只靠事件会漏。</li>
 * </ul>
 * 读一次设置就是两次 ContentProvider 读（毫秒级），3 秒一次的开销可以忽略。
 *
 * <h3>边界</h3>
 * <ul>
 *   <li>没有 WRITE_SECURE_SETTINGS 时什么都不做，只记录状态供界面展示；</li>
 *   <li>用户在设置里关掉「无障碍守护」后彻底停止，绝不偷偷把权限开回来；</li>
 *   <li><b>没有次数上限</b>：无论被关多少次都会继续补回，只在频率异常时记一条提示。
 *       想要它停，就去关「无障碍自动守护」开关 —— 由用户决定，不由代码替他决定。</li>
 * </ul>
 */
public final class A11yGuard {

    private static final String TAG = "DSH_A11Y_GUARD";

    /** 轮询间隔。厂商杀权限是毫秒级的，但 3 秒内补回去用户完全无感。 */
    private static final long POLL_MS = 3_000L;

    private A11yGuard() { }

    private static volatile boolean sStarted = false;
    private static volatile boolean sCheckNow = false;
    private static volatile boolean sLoaded = false;

    // ── 状态（供界面读取） ──────────────────────────────────
    private static volatile int sHealCount = 0;
    private static volatile long sLastHealAt = 0L;
    private static volatile String sLastNote = "尚未需要修复";
    /** 最近一分钟的修复次数，只用于在界面上提示"频率偏高"，**不影响是否继续修**。 */
    private static volatile int sRecentCount = 0;
    private static volatile long sRecentSince = 0L;
    /** 已经就"频率偏高"提示过几次（避免刷屏）。 */
    private static volatile boolean sWarnedBurst = false;

    /*
     * 计数要**跨进程重启保留**：用户真正关心的是"这一周它替我挡了多少次"，
     * 而不是"这次打开 App 之后挡了几次" —— 后者每次重装/重启都归零，等于没信息。
     */
    private static final String PREF_COUNT = "a11y_heal_count";
    private static final String PREF_AT = "a11y_last_heal_at";

    private static void ensureLoaded(Context c) {
        if (sLoaded) return;
        sLoaded = true;
        try {
            android.content.SharedPreferences p =
                    c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE);
            sHealCount = p.getInt(PREF_COUNT, 0);
            sLastHealAt = p.getLong(PREF_AT, 0L);
        } catch (Throwable ignore) { }
    }

    private static void persist(Context c) {
        try {
            c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE).edit()
                    .putInt(PREF_COUNT, sHealCount)
                    .putLong(PREF_AT, sLastHealAt)
                    .apply();
        } catch (Throwable ignore) { }
    }

    public static int healCount() { return sHealCount; }

    public static long lastHealAt() { return sLastHealAt; }

    public static String lastNote() { return sLastNote; }

    /**
     * 解绑事件触发：让看门狗**立刻**检查一次。
     *
     * <p>注意这里必须唤醒等待中的线程，而不是只置一个标志位 ——
     * 只置标志位的话，"立刻"实际上是"等这次 3 秒休眠睡完"，
     * 实测最坏要 4 秒才补回来。用户体验上这是"点了 QQ 之后权限没了 4 秒"，
     * 而我们本来就拿到了系统的即时信号（onUnbind），没有理由不用。
     */
    public static void nudge() {
        sCheckNow = true;
        synchronized (LOCK) {
            LOCK.notifyAll();
        }
    }

    /** 等待/唤醒用的锁。 */
    private static final Object LOCK = new Object();

    /**
     * 启动看门狗（幂等）。
     *
     * <p>放在 {@link DshService} 里 —— 那是个前台服务，只要 App 活着它就在，
     * 而守护本身也只有在 App 活着时才有意义（服务死了没人调用手机控制）。
     */
    public static void start(Context ctx) {
        if (sStarted) return;
        sStarted = true;
        final Context app = ctx.getApplicationContext();
        ensureLoaded(app);
        Thread t = new Thread(() -> loop(app), "dsh-a11y-guard");
        t.setDaemon(true);
        t.start();
    }

    private static void loop(Context app) {
        while (true) {
            // 没人叫醒就最多睡 POLL_MS；被 nudge() 叫醒就立刻往下走
            synchronized (LOCK) {
                if (!sCheckNow) {
                    try {
                        LOCK.wait(POLL_MS);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
            sCheckNow = false;
            try {
                check(app);
            } catch (Throwable t) {
                Log.w(TAG, "守护循环异常: " + t);
            }
        }
    }

    /** 检查一次并按需修复。 */
    public static void check(Context app) {
        if (!Priv.guardEnabled(app)) {
            sLastNote = "守护已关闭";
            return;
        }
        // 自愈能力本身靠"无障碍服务在跑"来判断没有意义 —— 它正是被关掉的那一方。
        // 所以这里只依赖能不能写设置。
        if (!Priv.canHeal(app)) {
            sLastNote = Priv.needsHeal(app)
                    ? "无障碍当前是关的，且本机没有自愈权限"
                    : "无障碍正常（无自愈权限，只能靠系统）";
            return;
        }
        if (!Priv.needsHeal(app)) {
            sLastNote = "无障碍正常";
            return;
        }

        long now = System.currentTimeMillis();

        /*
         * ⚠️ 这里**刻意不设次数上限**。
         *
         * 早先的版本写了"60 秒内修 10 次就先停 60 秒"的退避，理由是怕和病态 ROM 无限对打耗电。
         * 但那是个错的取舍：用户要的就是"它别掉"，而退避会让守护在**最需要的时候**自己停手，
         * 而且停得悄无声息 —— 用户只会看到权限又没了，还以为是功能失效。
         *
         * 现在改成：**照修不误**，只把"频率偏高"这件事记下来给用户看。
         * 真要限制，那是用户去关「无障碍自动守护」开关的事，不该由代码替他决定。
         */
        if (now - sRecentSince > 60_000L) {
            sRecentSince = now;
            sRecentCount = 0;
            sWarnedBurst = false;
        }
        sRecentCount++;

        boolean ok = Priv.heal(app);
        if (ok) {
            sHealCount++;
            sLastHealAt = now;
            persist(app);
            sLastNote = "已修复（第 " + sHealCount + " 次）";
            Log.i(TAG, "无障碍开关被关掉，已写回；累计 " + sHealCount + " 次");
            DshService.note("无障碍守护: 检测到开关被关，已自动写回（累计 " + sHealCount + " 次）");
            // 只在频率确实异常时提醒一次，不刷屏、更不因此停手
            if (sRecentCount == 30 && !sWarnedBurst) {
                sWarnedBurst = true;
                DshService.note("无障碍守护: 最近一分钟被关了 30 次以上（系统在反复关它），仍会继续补回");
            }
            // 写是同步的，但系统绑定服务要一点时间；稍后再看一次，把真实结果写进状态
            verifyLater(app, sHealCount);
        } else {
            sLastNote = "写回失败（权限被回收了？）";
            Log.w(TAG, sLastNote);
        }
    }

    /** 修复完 1.5 秒后复查一次，让界面上的状态反映"系统是否真的绑上了"。 */
    private static void verifyLater(Context app, int seq) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(1_500L);
            } catch (InterruptedException ie) {
                return;
            }
            if (seq != sHealCount) return;   // 期间又修过，别覆盖更新的结果
            if (DshAccessibilityService.isRunning()) {
                sLastNote = "已修复并生效（累计 " + sHealCount + " 次）";
            } else if (!Priv.needsHeal(app)) {
                // 设置已经是开的，但服务还没绑上 —— 系统在绑，或绑定被拦了。
                sLastNote = "设置已写回，服务还没绑上（累计 " + sHealCount + " 次）";
            } else {
                sLastNote = "写回后又被改掉了（累计 " + sHealCount + " 次）";
            }
        }, "dsh-a11y-verify");
        t.setDaemon(true);
        t.start();
    }

    /** 界面用的一句话状态。 */
    public static String describe(Context c) {
        ensureLoaded(c);
        StringBuilder sb = new StringBuilder();
        boolean running = DshAccessibilityService.isRunning();
        boolean canHeal = Priv.canHeal(c);
        boolean guard = Priv.guardEnabled(c);

        /*
         * 纯文字，不用 emoji —— 这一页要和系统设置那种原生观感一致。
         * 状态用「已开启 / 未开启 / 已关闭」这种词说清楚，比图标更明确。
         */
        sb.append("无障碍服务：").append(running ? "已开启" : "未开启");
        sb.append('\n');
        if (canHeal) {
            sb.append(guard
                    ? "自动守护：已开启 —— 被系统关掉会自动补回来"
                    : "自动守护：已手动关闭");
        } else {
            sb.append("自动守护：不可用（缺少一次性授权）");
        }
        if (sHealCount > 0) {
            sb.append('\n').append("已自动修复 ").append(sHealCount).append(" 次");
            if (sLastHealAt > 0) {
                sb.append("，最近一次 ")
                  .append(android.text.format.DateFormat.format("MM-dd HH:mm:ss", sLastHealAt));
            }
        }
        sb.append('\n').append("当前状态：").append(sLastNote);
        return sb.toString();
    }
}
