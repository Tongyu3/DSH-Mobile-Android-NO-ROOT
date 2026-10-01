package com.dshmobile.probe;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.util.Locale;

/**
 * 把容器里的文件交给**安卓应用**打开 —— 也就是弹出系统那个「选择打开方式」。
 *
 * <h3>为什么需要它（这是个真实故障）</h3>
 * DSH 的「在默认程序中打开 / 打开方式」是**服务端**行为：网页 POST 到
 * {@code /open-in-app/open}，由运行 dsh 的那台主机去拉起一个桌面程序。
 * 在手机上主机就是容器，容器里没有桌面环境，于是每次都失败，
 * 用户在聊天里看到的就是那句
 * <pre>此主机没有可用的桌面，无法打开文件或文件夹</pre>
 *
 * <p>手机上正确的接管方式是把这一步换成安卓自己的 {@link Intent#ACTION_VIEW}：
 * 系统会弹出底部「选择打开方式」，用户挑一个应用打开 ——
 * 这也正是 DSH 那个按钮的语义。
 *
 * <h3>为什么要 FileProvider</h3>
 * 本 App 的 targetSdk = 28，直接把 {@code file://} 放进 Intent 会触发
 * {@code FileUriExposedException}（Android 7.0 起禁止）。所以必须用
 * FileProvider 换成 {@code content://} 并授予临时读权限。
 * 对应的 {@code <provider>} 与 {@code res/xml/file_paths.xml} 缺一不可 ——
 * 路径没登记在 file_paths 里，{@code getUriForFile} 会直接抛异常。
 */
public final class OpenWith {

    private OpenWith() { }

    /** 与 AndroidManifest 里 {@code <provider>} 的 authorities 必须一致。 */
    public static String authority(Context c) {
        return c.getPackageName() + ".fileprovider";
    }

    /**
     * 打开一个文件：解析路径 → 换 content:// → 弹系统「选择打开方式」。
     *
     * @return {@code null} 表示已经成功拉起选择器；否则是给用户看的失败原因
     *         （调用方负责弹 Toast / 写回网页）
     */
    public static String open(Context c, String path) {
        File f = resolve(c, path);
        if (f == null) {
            return "找不到这个文件（手机存储和容器里都没有）\n" + path;
        }
        if (f.isDirectory()) {
            return "「打开方式」只对文件有意义，这是个目录\n" + f.getAbsolutePath();
        }
        Uri uri;
        try {
            uri = androidx.core.content.FileProvider.getUriForFile(c, authority(c), f);
        } catch (Throwable t) {
            return "没法把这个文件授权给别的应用：" + t;
        }
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(uri, mimeOf(f.getName()));
        // 临时读权限必须给，否则对方打不开这个 content://
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        // 从 WebView 的 JavascriptInterface 里调起来，没有 Activity 上下文
        view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(Intent.createChooser(view, "选择打开方式"));
            return null;
        } catch (Throwable t) {
            return "没有应用能打开这种文件：" + t;
        }
    }

    /**
     * 把 DSH 给的路径解析成真实文件。
     *
     * <p>两条来源都要认，因为 DSH 里同时存在这两类文件：
     * <ul>
     *   <li>手机存储路径（如 {@code /sdcard/dsh/xxx.jpg}）—— 手机桥写出来的；</li>
     *   <li>容器内路径（如 {@code /root/work/xxx.png}）—— agent 在工作区里造出来的。
     *       它实际落在 App 私有目录的 rootfs 下，DSH 自己是看不到这层映射的。</li>
     * </ul>
     */
    static File resolve(Context c, String path) {
        if (path == null) return null;
        String p = path.trim();
        if (p.isEmpty()) return null;
        File direct = new File(p);
        if (direct.exists()) return direct;
        if (p.startsWith("/")) {
            try {
                File rootfs = new File(Env.base(c), "rootfs");
                File mapped = new File(rootfs, p.substring(1));
                if (mapped.exists()) return mapped;
            } catch (Throwable ignore) { }
        }
        return null;
    }

    /** 按扩展名猜 MIME；猜不到就用通配，让系统自己挑能接的应用。 */
    static String mimeOf(String name) {
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                String ext = name.substring(dot + 1).toLowerCase(Locale.US);
                try {
                    String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                    if (m != null && !m.isEmpty()) return m;
                } catch (Throwable ignore) { }
            }
        }
        return "*/*";
    }
}
