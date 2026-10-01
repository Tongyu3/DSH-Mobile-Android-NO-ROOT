package com.dshmobile.probe;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

/**
 * 用**输入法**往目标输入框里打字。
 *
 * <h3>为什么不能用更简单的办法</h3>
 * <ul>
 *   <li>无障碍 {@code ACTION_SET_TEXT}：微信把输入框标成不可设置，直接无效；</li>
 *   <li>shell {@code input text}：同样进不去，而且只支持 ASCII。</li>
 * </ul>
 * 输入法则是系统认可的合法输入通道 —— 目标应用没法把"输入法提交的文字"挡掉，
 * 否则真实用户也没法打字了。
 *
 * <h3>我们凭什么能自己切换输入法</h3>
 * 切换默认输入法要写 {@code Settings.Secure.DEFAULT_INPUT_METHOD}，
 * 而这正是 {@code WRITE_SECURE_SETTINGS} 的管辖范围 —— 也就是
 * 「手机控制」里那次一次性授权拿到的权限。所以：
 *
 * <pre>
 *   一次授权（pm grant / Shizuku） → 之后既能自愈无障碍，也能切输入法
 * </pre>
 *
 * <h3>取舍：用完要切回去</h3>
 * 把用户的输入法换掉是有代价的（他就没法用自己熟悉的键盘了）。
 * 所以这里会记住原来那个，并且在 agent 结束操作后切回 ——
 * 既不能忘了切回，也不能每打一次字就来回切（那会让目标输入框反复失焦）。
 */
public final class ImeInjector {

    private static final String TAG = "DSH_IME_INJ";
    private static final String PREF_PREV = "ime_prev_component";

    private ImeInjector() { }

    /** 我们自己的输入法组件名（写进设置用的扁平形式）。 */
    public static String myIme(Context c) {
        return new ComponentName(c.getPackageName(), DshImeService.class.getName()).flattenToString();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE);
    }

    /** 能不能用（需要那次一次性授权）。 */
    public static boolean available(Context c) {
        return Priv.canHeal(c);
    }

    /** 当前默认输入法是不是我们。 */
    public static boolean isDefault(Context c) {
        try {
            String cur = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD);
            return myIme(c).equals(cur);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 切到我们的输入法，并等它拿到输入框连接。
     *
     * <h3>两条切换路径，优先用 shell 那条</h3>
     * 实测发现：**只写 {@code Settings.Secure.DEFAULT_INPUT_METHOD} 是不够的**。
     * 系统要等输入会话重建才会去绑定新的输入法，于是出现"设置里已经是我们了，
     * 但服务根本没起来、commitText 无处可发"。
     *
     * <p>所以优先走官方的 {@code ime enable} / {@code ime set} 命令
     * （和桌面端 ADBKeyboard 的做法一致）—— 它会真正触发重新绑定。
     * 这条命令需要 shell 身份，所以有 Shizuku 时才能用；没有的话退回写设置，
     * 并明确告诉用户"需要重新点一下输入框"。
     *
     * @return null 表示就绪，否则是失败原因
     */
    public static String activate(Context c) {
        String r = activateInner(c);
        // 不管成功失败，只要现在默认输入法是我们，就一定要安排自动切回
        reconcile(c);
        return r;
    }

    private static String activateInner(Context c) {
        if (!available(c)) {
            return "没有一次性授权，无法切换输入法。\n"
                 + "请在「手机控制」里点「用电脑授权」或「用 Shizuku 授权」。";
        }
        String me = myIme(c);
        String id = shortId(c);

        // 记住用户原来那个，之后要还回去
        try {
            String cur = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD);
            if (cur != null && !cur.equals(me) && !cur.isEmpty()) {
                prefs(c).edit().putString(PREF_PREV, cur).apply();
            }
        } catch (Throwable ignore) { }

        boolean viaShell = switchViaShell(c, id);
        if (!viaShell) {
            String e = switchViaSettings(c, me);
            if (e != null) return e;
        }

        // 等它真正拿到输入框连接。走 shell 那条通常几十毫秒；
        // 走设置那条可能要等一次输入会话重建，所以要求用户配合点一下输入框。
        for (int i = 0; i < 25; i++) {
            if (DshImeService.hasConnection()) return null;
            try { Thread.sleep(100); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!DshImeService.isRunning()) {
            return viaShell
                    ? "输入法切过去了但服务没起来（系统没绑定）。可在「手机控制」里手动确认输入法已启用。"
                    : "还没绑定我们的输入法。\n"
                      + "因为当前没有 Shizuku，只能用「改系统设置」的方式，"
                      + "而这需要**重新点一下要输入的地方**（让输入会话重建）再试。";
        }
        return "输入法已经在跑了，但当前没有获得焦点的输入框 —— 请先点一下要输入的地方再试。";
    }

    /** 官方 IME id 的短形式：{@code 包名/.类名}（ime 命令认这个）。 */
    private static String shortId(Context c) {
        return c.getPackageName() + "/." + DshImeService.class.getSimpleName();
    }

    /**
     * 用 shell 身份跑 {@code ime enable/set}。
     *
     * <p>整段跑在一个**有界**的工作线程里：Shizuku 的 binder 调用在某些状态下
     * 会长时间不返回，不能让它拖住控制桥的请求线程。
     */
    private static boolean switchViaShell(Context c, String id) {
        if (!ShizukuBridge.isRunning() || !ShizukuBridge.hasPermission()) return false;
        final Context app = c.getApplicationContext();
        final String cmd = "ime enable " + id + "; ime set " + id;
        final boolean[] ok = new boolean[1];
        Thread t = new Thread(() -> {
            try {
                String r = ShizukuBridge.execShell(app, cmd);
                // ime set 成功时没有输出；失败会带 Error
                ok[0] = r != null && !r.contains("Error") && !r.contains("Unknown");
            } catch (Throwable ignore) { }
        }, "dsh-ime-set");
        t.setDaemon(true);
        t.start();
        try { t.join(8000L); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return ok[0];
    }

    /** 退路：直接写设置（不需要 Shizuku，但要等输入会话重建才生效）。 */
    private static String switchViaSettings(Context c, String me) {
        try {
            String enabled = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_INPUT_METHODS);
            if (enabled == null) enabled = "";
            if (!enabled.contains(me)) {
                String merged = enabled.isEmpty() ? me : enabled + ":" + me;
                Settings.Secure.putString(c.getContentResolver(),
                        Settings.Secure.ENABLED_INPUT_METHODS, merged);
            }
            String cur = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD);
            if (!me.equals(cur)) {
                Settings.Secure.putString(c.getContentResolver(),
                        Settings.Secure.DEFAULT_INPUT_METHOD, me);
            }
            return null;
        } catch (Throwable t) {
            return "切换输入法失败: " + t;
        }
    }

    /**
     * 把用户原来的输入法还回去。
     *
     * <h3>这里踩过两个坑，都修了</h3>
     * <ol>
     *   <li><b>只写设置不生效。</b> 和切换时一样：光改
     *       {@code Settings.Secure.DEFAULT_INPUT_METHOD} 系统不会重新绑定，
     *       于是"提示还原成功、实际还是我们的输入法"。
     *       现在有 Shizuku 时同样走 {@code ime set} 命令。</li>
     *   <li><b>没验证就删掉记录。</b> 原来只要写了一次设置就
     *       {@code remove(PREF_PREV)}，结果还原失败之后**线索也丢了**，
     *       用户再也没法一键切回（实测就是这样：记录没了，人还卡在我们的输入法上）。
     *       现在**读回确认成功了才删**，失败就保留记录、下次还能再试。</li>
     * </ol>
     */
    public static String restore(Context c) {
        // 手动还原：把待执行的自动还原取消掉，避免重复动作
        java.util.concurrent.ScheduledFuture<?> p = sPending;
        if (p != null) {
            p.cancel(false);
            sPending = null;
        }
        String prev = prefs(c).getString(PREF_PREV, null);
        if (prev == null || prev.isEmpty()) return "没有记录到原来的输入法，保持现状";
        if (prev.equals(myIme(c))) return "原来的输入法就是我们，无需还原";

        // ① 优先走 shell（可靠，会真正重新绑定）
        boolean viaShell = switchToViaShell(c, prev);

        // ② 无论走哪条，都写一次设置 —— 没有 Shizuku 时这是唯一手段
        try {
            Settings.Secure.putString(c.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD, prev);
        } catch (Throwable t) {
            if (!viaShell) return "还原输入法失败: " + t;
        }

        // ③ 读回确认，成功才清记录
        try {
            String now = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD);
            if (prev.equals(now)) {
                prefs(c).edit().remove(PREF_PREV).apply();
                Log.i(TAG, "已还原输入法: " + prev);
                return null;
            }
        } catch (Throwable ignore) { }

        return "设置已改成「" + prev + "」，但系统还没切过去。\n"
             + "请点一下任意输入框，或手动在设置里选回你的输入法。\n"
             + "（记录已保留，可以再执行一次 phone ime restore 重试）";
    }

    /** 用 shell 跑 {@code ime set}，有界等待。 */
    private static boolean switchToViaShell(Context c, String id) {
        if (!ShizukuBridge.isRunning() || !ShizukuBridge.hasPermission()) return false;
        final Context app = c.getApplicationContext();
        final String cmd = "ime set " + id;
        final boolean[] ok = new boolean[1];
        Thread t = new Thread(() -> {
            try {
                String r = ShizukuBridge.execShell(app, cmd);
                ok[0] = r != null && !r.contains("Error") && !r.contains("Unknown");
            } catch (Throwable ignore) { }
        }, "dsh-ime-restore");
        t.setDaemon(true);
        t.start();
        try { t.join(8000L); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return ok[0];
    }

    /**
     * 一站式：切输入法 → 提交文字 → **安排自动切回**。
     *
     * <h3>为什么要自动切回</h3>
     * 原来打完字就**留在**我们的输入法上，得靠 agent 记得执行
     * {@code phone ime restore}。实测这不可靠：agent 一忙就忘了，
     * 用户拿回手机发现键盘变成了「DSH 输入」，还得自己进设置改回来。
     *
     * <p>现在打完就安排一次自动还原；**多次打字会把计时往后推**，
     * 所以连续填几个输入框不会来回切（那会让输入框反复失焦）。
     * 用户想要立即切回仍然可以执行 {@code phone ime restore}。
     *
     * @return null 表示成功，否则是失败原因（给用户看的人话）
     */
    public static String type(Context c, String text) {
        String err = activate(c);
        if (err != null) return err;
        String e2 = DshImeService.commit(text);
        if (e2 != null) return "提交文字失败: " + e2;
        // 成功提交 → 把自动切回的计时往后推（连续输入不来回切）
        reconcile(c);
        return null;
    }

    /** 打完字后多久自动切回。留一点余量，让"连着填下一个框"不会来回切。 */
    private static final long AUTO_RESTORE_MS = 5000L;

    private static final java.util.concurrent.ScheduledExecutorService sAuto =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "dsh-ime-auto");
                t.setDaemon(true);
                return t;
            });

    private static volatile java.util.concurrent.ScheduledFuture<?> sPending;
    private static volatile long sPendingAt;

    /** 还有多久自动切回（毫秒）；没有待执行的返回 -1。 */
    static long autoRestoreIn(Context c) {
        java.util.concurrent.ScheduledFuture<?> p = sPending;
        if (p == null || p.isDone() || p.isCancelled()) return -1L;
        return Math.max(0L, sPendingAt - System.currentTimeMillis());
    }

    /**
     * 安全网：**只要当前默认输入法是我们**，就（重新）安排自动切回。
     *
     * <h3>为什么不能只在"走输入法那条路"时才安排</h3>
     * 实测踩到的：`phone text` 先试无障碍，**成功就不走输入法了** ——
     * 可这时我们的输入法可能还留在系统里（上一次切换留下的），
     * 于是"打字成功了"和"键盘还是我们的"同时成立，而没人去还原它。
     * 用户的键盘就一直回不来。
     *
     * <p>所以判断依据不是"这次走的哪条路"，而是**当前状态**：
     * 只要默认输入法是我们，就一定安排一次自动切回。
     * 每次打字都会把计时往后推，所以连续输入不会来回切。
     */
    public static void reconcile(Context c) {
        try {
            if (isDefault(c)) scheduleAutoRestore(c);
        } catch (Throwable ignore) { }
    }

    private static void scheduleAutoRestore(Context c) {
        final Context app = c.getApplicationContext();
        java.util.concurrent.ScheduledFuture<?> old = sPending;
        if (old != null) old.cancel(false);
        sPendingAt = System.currentTimeMillis() + AUTO_RESTORE_MS;
        sPending = sAuto.schedule(() -> {
            try {
                /*
                 * 只在"当前默认输入法还是我们"时才还原。
                 * 如果这中间用户自己把输入法换掉了，就别去抢 ——
                 * 否则会变成"用户刚选好，5 秒后又被改回去"。
                 */
                if (!isDefault(app)) {
                    prefs(app).edit().remove(PREF_PREV).apply();
                    return;
                }
                String e = restore(app);
                Log.i(TAG, "自动切回输入法: " + (e == null ? "成功" : e));
            } catch (Throwable t) {
                Log.w(TAG, "自动切回输入法失败", t);
            }
        }, AUTO_RESTORE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * 界面/诊断用的一句话状态。
     *
     * <p>⚠️ 必须分开说清"设置切过去了"和"服务真的在跑"。
     * 之前只看设置值就报"✅ 已就绪"，结果出现过
     * "状态说就绪、一提交就失败" —— 这种自相矛盾的提示比不提示更糟。
     */
    public static String describe(Context c) {
        StringBuilder sb = new StringBuilder();
        if (!available(c)) {
            sb.append("⚠️ 不可用（缺一次性授权）");
        } else if (DshImeService.hasConnection()) {
            sb.append("✅ 就绪：输入法在跑，且已连上输入框");
        } else if (DshImeService.isRunning()) {
            sb.append("🟡 输入法在跑，但还没连上输入框（点一下要输入的地方即可）");
        } else if (isDefault(c)) {
            sb.append("🟡 默认输入法已切到我们，但服务还没被系统绑定"
                    + "（点一下输入框，或让 agent 用 Shizuku 切一次）");
        } else {
            sb.append("✅ 可用（需要时临时切过来，用完切回）");
        }
        sb.append('\n').append(ShizukuBridge.isRunning() ? "切换方式：Shizuku（可靠）" : "切换方式：写系统设置（需要重新聚焦输入框）");
        String prev = prefs(c).getString(PREF_PREV, null);
        if (prev != null) sb.append("\n原来的输入法：").append(prev).append("（还没还原）");
        long left = autoRestoreIn(c);
        if (left >= 0) {
            sb.append("\n将在 ").append(String.format(java.util.Locale.US, "%.0f", left / 1000.0))
              .append(" 秒后自动切回你的输入法");
        }
        return sb.toString();
    }
}
