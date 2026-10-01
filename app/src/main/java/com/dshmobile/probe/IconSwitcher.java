package com.dshmobile.probe;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 桌面图标（快捷方式）配色的选择与切换，通知栏图标跟着一起用同一套配色。
 *
 * <h3>为什么只能"从预置的一套里选"</h3>
 * Android **不允许第三方 App 用任意图片当桌面图标**：manifest 里的
 * {@code android:icon} 必须是编译进 APK 的资源，运行时改不了
 * （能换图标的只有系统/桌面自己）。
 *
 * <p>官方留的唯一口子是 {@code <activity-alias>}：预先声明若干个桌面入口、
 * 每个带不同图标，运行时用 {@link PackageManager#setComponentEnabledSetting}
 * 只启用其中一个。所以这里做的是"预置 8 套配色，用户选一套"。
 * 8 套共用同一张前景图（那只鲸鱼），只换背景色 —— 不用多打包一张图。
 *
 * <h3>两个坑</h3>
 * <ol>
 *   <li><b>绝不能出现"一个都没启用"的空档</b> —— 那会让桌面图标直接消失。
 *       所以顺序是：**先启用新的，再禁用旧的**。</li>
 *   <li>切换会让系统**重启这个 App**（组件状态变了）。这是系统行为，躲不掉，
 *       界面上要提前告诉用户。</li>
 * </ol>
 */
public final class IconSwitcher {

    private static final String TAG = "DSH_ICON";
    private static final String KEY = "icon_variant";

    /** 一套配色。 */
    public static final class Variant {
        public final String id;
        public final String label;
        public final String alias;        // 完整的组件名
        public final int bgColorRes;
        public final int accentColorRes;

        Variant(String id, String label, String alias, int bg, int accent) {
            this.id = id;
            this.label = label;
            this.alias = alias;
            this.bgColorRes = bg;
            this.accentColorRes = accent;
        }

        /** 桌面图标资源名（回退用 getIdentifier 取）。 */
        public String mipmapName() {
            return "ic_launcher_" + id;
        }
    }

    private static List<Variant> sAll;

    /** 全部可选配色。加一套的话：这里 + manifest 的 activity-alias + 两个 XML 资源。 */
    public static synchronized List<Variant> all(Context c) {
        if (sAll == null) {
            String p = c.getPackageName();
            List<Variant> l = new ArrayList<>();
            l.add(v(c, "white", "经典白", p, R.color.icon_bg_white, R.color.icon_accent_white));
            l.add(v(c, "sky", "天空蓝", p, R.color.icon_bg_sky, R.color.icon_accent_sky));
            l.add(v(c, "mint", "薄荷绿", p, R.color.icon_bg_mint, R.color.icon_accent_mint));
            l.add(v(c, "lilac", "淡紫", p, R.color.icon_bg_lilac, R.color.icon_accent_lilac));
            l.add(v(c, "peach", "暖橙", p, R.color.icon_bg_peach, R.color.icon_accent_peach));
            l.add(v(c, "forest", "松林绿", p, R.color.icon_bg_forest, R.color.icon_accent_forest));
            l.add(v(c, "navy", "深空蓝", p, R.color.icon_bg_navy, R.color.icon_accent_navy));
            l.add(v(c, "ink", "墨黑", p, R.color.icon_bg_ink, R.color.icon_accent_ink));
            sAll = l;
        }
        return sAll;
    }

    private static Variant v(Context c, String id, String label, String pkg, int bg, int accent) {
        String alias = pkg + ".Icon" + Character.toUpperCase(id.charAt(0)) + id.substring(1);
        return new Variant(id, label, alias, bg, accent);
    }

    public static Variant current(Context c) {
        String id = prefs(c).getString(KEY, "white");
        for (Variant x : all(c)) if (x.id.equals(id)) return x;
        return all(c).get(0);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE);
    }

    /** 当前配色的通知强调色。 */
    public static int accentColor(Context c) {
        try {
            return c.getResources().getColor(current(c).accentColorRes);
        } catch (Throwable t) {
            return 0xFF2F6BFF;
        }
    }

    /** 当前配色的桌面图标资源 id（给通知的大图用）；取不到返回 0。 */
    public static int iconRes(Context c) {
        try {
            return c.getResources().getIdentifier(
                    current(c).mipmapName(), "mipmap", c.getPackageName());
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 切到指定配色。
     *
     * @return null 表示已切换（调用方该提示用户"App 会重启一下"），否则是失败原因
     */
    public static String apply(Context c, Variant target) {
        try {
            PackageManager pm = c.getPackageManager();
            ComponentName want = new ComponentName(c, target.alias);
            Variant cur = current(c);

            // ① 先启用新的（绝不能先禁用旧的，否则中间会出现"没有桌面入口"）
            pm.setComponentEnabledSetting(want,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);

            // ② 再禁用其它的
            for (Variant x : all(c)) {
                if (x.id.equals(target.id)) continue;
                pm.setComponentEnabledSetting(new ComponentName(c, x.alias),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
            }

            prefs(c).edit().putString(KEY, target.id).apply();
            Log.i(TAG, "桌面图标已切换: " + cur.id + " → " + target.id);
            // 常驻通知的强调色/大图是构建那一刻定下的，催服务重建一次，
            // 否则通知栏里会一直挂着旧颜色
            DshService.refreshNotification();
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "切图标失败", t);
            return "切换失败: " + t;
        }
    }

    /**
     * 在启动时把组件状态**对齐**到用户选的那一套。
     *
     * <p>为什么需要：组件启用状态存在系统里，而用户可能清了 App 数据、
     * 或者升级时 manifest 默认值把它顶回去了 —— 那就会出现
     * "设置里写着深空蓝，桌面却是白的"。这里每次启动对一次账。
     */
    public static void reconcile(Context c) {
        try {
            String want = prefs(c).getString(KEY, null);
            if (want == null) return;               // 用户没选过，保持 manifest 默认
            Variant cur = current(c);
            PackageManager pm = c.getPackageManager();
            ComponentName cn = new ComponentName(c, cur.alias);
            int state = pm.getComponentEnabledSetting(cn);
            if (state != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                Log.i(TAG, "图标组件状态与设置不一致，重新对齐到 " + cur.id);
                apply(c, cur);
            }
        } catch (Throwable t) {
            Log.w(TAG, "对齐图标状态失败", t);
        }
    }
}
