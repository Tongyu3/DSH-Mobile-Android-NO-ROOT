package com.dshmobile.probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

/**
 * 被白名单挡住时，给用户一个**一键放行**的入口。
 *
 * <h3>要解决的问题</h3>
 * agent 操作某个应用时，如果它不在白名单里，所有操作都会被拒绝。
 * 原来的流程是：用户在聊天里看到"不在白名单里" → 自己进
 * 设置 → 手机控制 → 应用白名单 → 在一长串应用里翻找 → 勾上 → 保存。
 * 一共六七步，而这件事本来只需要一个动作。
 *
 * <p>现在被拒时顺手发一条通知，写着"DSH 想操作「QQ」"，
 * 点一下就直接把它加进白名单并保存 —— 用户回来让 agent 重试即可。
 *
 * <h3>为什么不直接放行</h3>
 * 白名单是用户设定的**安全边界**，App 自己越过去就等于没有边界。
 * 所以这里只是把"同意"这个动作从六步压成一步，**决定权始终在用户手上**。
 */
public final class QuickAdd {

    private static final String TAG = "DSH_QUICKADD";

    /** 通知点进来时带的包名（由 {@link WhitelistActivity} 读取）。 */
    public static final String EXTRA_QUICK_ADD = "quick_add_pkg";

    private static final String CHANNEL = "dsh_whitelist";
    private static final int NOTI_ID = 0x4457;

    /** 同一个应用 30 秒内只提示一次（agent 常常会连着重试好几次）。 */
    private static final long THROTTLE_MS = 30_000L;
    private static volatile String sLastPkg;
    private static volatile long sLastAt;

    private QuickAdd() { }

    /** 应用名；拿不到就退回包名。 */
    static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    /** 被白名单拒绝时调用：发一条"点一下放行"的通知。 */
    public static void offer(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        try {
            long now = System.currentTimeMillis();
            if (pkg.equals(sLastPkg) && now - sLastAt < THROTTLE_MS) return;
            sLastPkg = pkg;
            sLastAt = now;

            NotificationManager nm =
                    (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "白名单放行",
                        NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("DSH 被白名单挡住时，一键把该应用加入白名单");
                ch.setShowBadge(true);
                nm.createNotificationChannel(ch);
            }

            String label = label(c, pkg);
            Intent open = new Intent(c, WhitelistActivity.class)
                    .putExtra(EXTRA_QUICK_ADD, pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(c, NOTI_ID, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            String title = "DSH 想操作「" + label + "」";
            String text = "它不在白名单里，所以这次操作被拒绝了。点一下即可放行。";
            Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? new Notification.Builder(c, CHANNEL)
                    : new Notification.Builder(c);
            b.setContentTitle(title)
             .setContentText(text)
             .setStyle(new Notification.BigTextStyle().bigText(text))
             .setSmallIcon(android.R.drawable.stat_notify_sync)
             .setContentIntent(pi)
             .setAutoCancel(true)
             // 同一个应用反复被拒时只更新、不重复响铃
             .setOnlyAlertOnce(true)
             .addAction(new Notification.Action.Builder(
                     null, "加入白名单", pi).build());
            nm.notify(NOTI_ID, b.build());
        } catch (Throwable t) {
            // 通知发不出去只是少了个便捷入口，绝不该影响主流程
            Log.w(TAG, "发白名单提示失败", t);
        }
    }

    /** 用户已经在别处放行了：把那条提示撤掉。 */
    public static void clear(Context c) {
        try {
            NotificationManager nm =
                    (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTI_ID);
        } catch (Throwable ignore) { }
    }
}
