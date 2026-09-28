package com.dshmobile.probe;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * 「无障碍守护」的能力层：把系统里那个无障碍开关读出来、必要时写回去。
 *
 * <h3>为什么需要它</h3>
 * 厂商的反诈策略会在 QQ / 微信切到前台的瞬间把**无障碍总开关置 0**（实测 vivo 就是这样），
 * 于是 DSH 的手机控制能力随机失效，用户只能反复手动去设置里重开。
 *
 * <h3>凭什么能写回去</h3>
 * 需要 {@code WRITE_SECURE_SETTINGS}。它的 protectionLevel 是
 * {@code signature|privileged|development} —— 普通安装拿不到，但**带 development 标志的权限
 * 可以用 pm grant 单独授予**：
 *
 * <pre>adb shell pm grant &lt;包名&gt; android.permission.WRITE_SECURE_SETTINGS</pre>
 *
 * 而且这个授权落在 packages.xml 里，**重启不掉**，所以只需要做一次。
 * 这正是"懒人版"能成立的原因：一次动作，之后 App 自己维护。
 *
 * <h3>没有拿到权限时</h3>
 * 本类所有写操作都会安全失败（返回 false），上层据此提示用户去授权，
 * 不会抛异常、也不会假装成功。
 */
public final class Priv {

    private Priv() { }

    /** 能让我们改写系统安全设置的权限。 */
    public static final String PERM_WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS";

    private static final String PREFS = "dsh_settings";
    /** 「无障碍守护」总开关（用户可关，关掉就完全停止自愈）。 */
    private static final String KEY_GUARD = "a11y_guard_enabled";

    // ── 组件名 ──────────────────────────────────────────────

    /** 写进 enabled_accessibility_services 的那个组件名：{@code 包名/类全名}。 */
    public static String a11yComponent(Context c) {
        return c.getPackageName() + "/" + DshAccessibilityService.class.getName();
    }

    // ── 读 ──────────────────────────────────────────────────

    /** 我们自己是否持有 WRITE_SECURE_SETTINGS（即"能不能自愈"）。 */
    public static boolean canHeal(Context c) {
        try {
            return c.checkSelfPermission(PERM_WRITE_SECURE) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 系统层面「无障碍」总开关。 */
    public static boolean masterOn(Context c) {
        try {
            return Settings.Secure.getInt(c.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 我们的服务是否出现在「已启用的无障碍服务」列表里。 */
    public static boolean serviceListed(Context c) {
        try {
            String v = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return containsComponent(v, a11yComponent(c));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 名单匹配。
     *
     * <p>不同 ROM 写进来的形式不完全一样：多数是 {@code 包名/类全名}，
     * 也有写成 {@code 包名/.类简名} 的。两种都要认，否则会"明明开着却判定为没开"，
     * 于是每 3 秒白写一次。
     */
    private static boolean containsComponent(String list, String full) {
        if (list == null || list.isEmpty()) return false;
        int slash = full.indexOf('/');
        if (slash < 0) return false;
        String pkg = full.substring(0, slash);
        String cls = full.substring(slash + 1);
        String shortForm = pkg + "/." + cls.substring(cls.lastIndexOf('.') + 1);

        for (String part : list.split(":")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            if (part.equalsIgnoreCase(full) || part.equalsIgnoreCase(shortForm)) return true;
        }
        return false;
    }

    /** 是否处于「该开却没开」的状态。 */
    public static boolean needsHeal(Context c) {
        return !masterOn(c) || !serviceListed(c);
    }

    // ── 写 ──────────────────────────────────────────────────

    /**
     * 把我们的无障碍服务写回启用列表，并打开总开关。
     *
     * <p>只**追加**自己，不覆盖名单里已有的其它服务 —— 用户可能还开着别的无障碍应用，
     * 我们没有任何理由动它们。名单为空时结果就是我们自己一个。
     *
     * <p>刻意**不做**"把别人也一起恢复"：厂商把整个名单清空时，别的应用也一起被关了，
     * 但那是它们的事；替用户把他可能故意关掉的服务复活，是越权。
     *
     * @return 写入动作是否成功（不代表系统已经完成绑定）
     */
    public static boolean heal(Context c) {
        if (!canHeal(c)) return false;
        try {
            String me = a11yComponent(c);
            String cur = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String merged = merge(cur, me);

            if (!merged.equals(cur)) {
                Settings.Secure.putString(c.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, merged);
            }
            if (!masterOn(c)) {
                Settings.Secure.putInt(c.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 在原有名单后面补上自己（已经在了就原样返回）。 */
    static String merge(String list, String me) {
        if (list == null) list = "";
        list = list.trim();
        if (list.isEmpty()) return me;
        if (containsComponent(list, me)) return list;
        return list + ":" + me;
    }

    // ── 守护开关 ────────────────────────────────────────────

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * 守护是否开启。
     *
     * <p>默认**开** —— 用户要的就是"权限别掉"。但必须给一个明确的关法：
     * 否则用户去系统设置里关掉无障碍，3 秒后被我们默默开回来，
     * 那就成了流氓行为。关掉这个开关后，本 App 不再碰无障碍设置。
     */
    public static boolean guardEnabled(Context c) {
        return prefs(c).getBoolean(KEY_GUARD, true);
    }

    public static void setGuardEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_GUARD, on).apply();
    }

    // ── 给用户看的文案 ──────────────────────────────────────

    /** 那条一次性的授权命令（用户复制到电脑上执行，或用 Shizuku 代跑）。 */
    public static String grantCommand(Context c) {
        return "adb shell pm grant " + c.getPackageName() + " " + PERM_WRITE_SECURE;
    }
}
