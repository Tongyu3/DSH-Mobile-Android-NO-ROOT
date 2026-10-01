package com.dshmobile.probe;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 「添加附件」的文件选择 —— 从拉起选择器到把 URI 交给 WebView 的全过程。
 *
 * <h3>为什么要单独一个类</h3>
 * 用户反馈：<b>能选文件，但从「相册」选的图片加不进去</b>。
 * 实测复现：同一个 PNG，从文件管理器的「最近」选 → 输入框出现缩略图 ✓；
 * 从「相册」（vivo 相册）选 → 选完回到界面什么都不发生 ✗。
 *
 * <p>问题不在"我们的回调没被调用"，而在<b>拿到的东西能不能用</b>。
 * 各家相册/云盘返回的 {@code content://} 千奇百怪：
 * <ul>
 *   <li>有的把 URI 放在 {@code getData()}，有的只放在 {@code ClipData}，
 *       还有的非标准地塞在 extra 里；</li>
 *   <li>有的虽然给了读权限，但那个 provider 的流只对"发起方"开一次，
 *       WebView 的渲染进程再去读就已经拿不到了 —— 于是它<b>静默丢弃</b>，
 *       页面上什么都不发生（这就是用户看到的现象）。</li>
 * </ul>
 *
 * <p>所以这里做三件事，逐层兜住：
 * <ol>
 *   <li>{@link #buildIntent} —— 自己拼 Intent，把 MIME 类型、多选、
 *       以及<b>读权限 flag</b> 都明确写出来，不去猜各家实现的默认值；</li>
 *   <li>{@link #collect} —— 结果解析：先走官方的 {@code parseResult}，
 *       拿不到再自己从 data / ClipData / extras 里挖；</li>
 *   <li>{@link #stageForWebView} —— <b>一律</b>把选中的内容拷进我们自己的 cache，
 *       改成用<b>我们自己的 FileProvider</b> 把 content:// 交给 WebView。</li>
 * </ol>
 *
 * <h3>为什么第 3 步是"一律拷"而不是"读不到才拷"</h3>
 * 因为"能不能读"根本探测不出来：能读它的是 WebView 的<b>渲染进程</b>，
 * 而我们的探测代码跑在<b>应用进程</b>里。云盘 / 相册 / 厂商安全选择器给出的
 * content:// 很多是"只有收到选择结果的那个进程能读" —— 应用进程探测通过，
 * 渲染进程照样打不开，而 WebView 读不到时<b>不报错、直接丢弃</b>，
 * 用户看到的现象就是"选完了，什么都没加上"。
 *
 * <p>第 3 步改成拷到 cache 之后，交给 WebView 的 content:// 由<b>本 App 自己的
 * provider</b> 提供，读它走的是我们自己的进程，跨进程授权问题从根上消失。
 * 代价是每个附件会在 cache 里留一份副本，所以配了 {@link #purgeOld} 每天清一次。
 */
final class Attach {

    private static final String TAG = "DSH_ATTACH";

    private Attach() { }

    /** 我们自己的 FileProvider authority（manifest 里声明的是 ${applicationId}.fileprovider）。 */
    static String authority(Context c) {
        return c.getPackageName() + ".fileprovider";
    }

    /**
     * 记一行诊断。
     *
     * <p>为什么不只用 {@code Log.i}：实测（vivo / Android 16）第三方应用的日志
     * 会被 ROM 丢掉 —— logcat 里一条都看不到。而"我选了图但没加上"这种反馈，
     * 没有日志就只能靠猜。所以同时写进 App 自己的报告文件
     * （和「复制日志」按钮读的是同一个文件），用户点一下就能把线索发出来。
     */
    private static void trace(Context c, String line) {
        Log.i(TAG, line);
        try (OutputStream os = new FileOutputStream(
                new File(Env.base(c), "report.txt"), true)) {
            os.write(("  [附件] " + line + "\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignore) { }
    }

    /**
     * 给 WebView 的文件选择器拼一个 Intent。
     *
     * <p>不用 {@code FileChooserParams.createIntent()}：它把 MIME 类型塞在
     * {@code EXTRA_MIME_TYPES} 里、type 固定 {@code * / *}，多数选择器能懂，
     * 但正因为它"只说了一半"，遇到不按套路实现的相册时行为就不可预期。
     * 这里把话说全 —— 只有一个类型时直接写进 type，多个类型才用 EXTRA_MIME_TYPES。
     */
    static Intent buildIntent(android.webkit.WebChromeClient.FileChooserParams params) {
        List<String> mimes = new ArrayList<>();
        if (params != null) {
            String[] accepts = params.getAcceptTypes();
            if (accepts != null) {
                for (String a : accepts) {
                    if (a == null) continue;
                    String t = a.trim();
                    // ".png"、空串这类不是 MIME，忽略（Chromium 有时会这么给）
                    if (t.isEmpty() || t.startsWith(".")) continue;
                    if (!mimes.contains(t)) mimes.add(t);
                }
            }
        }

        Intent it = new Intent(Intent.ACTION_GET_CONTENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        if (mimes.isEmpty()) {
            it.setType("*/*");
        } else if (mimes.size() == 1) {
            it.setType(mimes.get(0));
        } else {
            it.setType("*/*");
            it.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
        }
        if (params != null
                && params.getMode() == android.webkit.WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            it.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        /*
         * 读权限 flag 必须显式带上。
         * ACTION_GET_CONTENT 理论上会自动授予，但**实测有相册不授**：
         * 返回的 URI 我们能拿到、却读不出内容，WebView 于是静默丢弃。
         */
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return it;
    }

    /**
     * 从 {@code onActivityResult} 的结果里取出要交给 WebView 的 URI 列表。
     *
     * @return {@code null} 表示用户取消 / 没拿到任何东西（此时要给 WebView 回 null，页面才不会卡住）
     */
    static Uri[] collect(Context c, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK) {
            trace(c, "用户取消（code=" + resultCode + "）");
            return null;
        }
        if (data == null) {
            trace(c, "RESULT_OK 但 data == null");
            return null;
        }

        Uri[] official = null;
        try {
            official = android.webkit.WebChromeClient.FileChooserParams
                    .parseResult(resultCode, data);
        } catch (Throwable t) {
            trace(c, "parseResult 抛异常，改用兜底解析：" + t);
        }
        if (official != null && official.length > 0) {
            trace(c, "parseResult 给了 " + official.length + " 个 URI");
            return official;
        }

        // ── 兜底：自己挖 ──
        List<Uri> list = new ArrayList<>();
        if (data.getData() != null) list.add(data.getData());
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null && !list.contains(u)) list.add(u);
            }
        }
        /*
         * 还有一类别扭的实现：URI 既不在 data 也不在 ClipData，而是塞在 extra 里
         * （有的相册把多选结果打包成一个 ParcelableArrayListExtra）。
         * 这是非标准行为，但既然用户点了"完成"，我们就该尽力把它捞出来。
         */
        android.os.Bundle ex = data.getExtras();
        if (ex != null) {
            for (String key : ex.keySet()) {
                Object v = ex.get(key);
                if (v instanceof Uri) {
                    if (!list.contains(v)) list.add((Uri) v);
                } else if (v instanceof ArrayList) {
                    for (Object o : (ArrayList<?>) v) {
                        if (o instanceof Uri && !list.contains(o)) list.add((Uri) o);
                    }
                }
            }
        }
        if (list.isEmpty()) {
            trace(c, "RESULT_OK，但 data/ClipData/extras 里都没有 URI"
                    + " · data=" + data + " · extras=" + ex);
            return null;
        }
        trace(c, "兜底解析出 " + list.size() + " 个 URI");
        return list.toArray(new Uri[0]);
    }

    /**
     * 把选中的内容**拷进我们自己的 cache**，返回由本 App FileProvider 提供的 URI。
     *
     * <p>不判断"能不能读"：那个判断在应用进程里做不可靠（见类注释）。
     * 拷贝失败时才退回原 URI，并在报告里记一行。
     */
    static Uri[] stageForWebView(Context c, Uri[] in) {
        if (in == null || in.length == 0) return in;
        Uri[] out = new Uri[in.length];
        for (int i = 0; i < in.length; i++) {
            Uri u = in[i];
            if (u == null) { out[i] = null; continue; }
            /*
             * 超大文件（默认 100MB 以上）放弃拷贝，原样直通。
             *
             * 拷贝的意义是"绕开跨进程读取的坑"，代价是**多占一份磁盘 + 多一次全量 IO**。
             * 几百 MB 的视频拷一份要好几秒（调用方已经放到后台线程，不会卡界面，
             * 但用户要干等），而大文件本来就更容易被上传端自己处理掉。
             * 所以这里设个上限：常见附件（图片/文档/音频/小视频）都走拷贝，超大文件直通。
             */
            long size = sizeOf(c, u);
            if (size > MAX_COPY_BYTES) {
                trace(c, "超过 " + (MAX_COPY_BYTES / 1024 / 1024) + "MB（" + size
                        + " 字节）不拷贝，原样直通：" + u);
                out[i] = u;
                continue;
            }
            /*
             * ⚠️ 这里**一律拷贝**，不做"先探测能不能读、能读就直通"的优化。
             *
             * 原因是 0.2.4 第一版就栽在"探测"上：探测跑在**应用进程**里，
             * 而真正去读这个 URI 的是 **WebView 的渲染进程**。
             * 云盘 / 相册 / 厂商安全选择器给出的 content:// 很多是
             * "只有收到选择结果的那个进程能读" —— 应用进程探测通过，
             * 渲染进程照样打不开，而 WebView 读不到时**不报错、直接丢弃**，
             * 于是用户看到的现象和没选一样。
             *
             * 所以改成：内容先落到我们自己的 cache，再交一个**由我们自己 FileProvider
             * 提供的** content:// 给 WebView —— 读它走的是本 App 自己的 provider，
             * 既没有跨进程授权问题，也没有"谁能读"的歧义。
             */
            Uri staged = copyInto(c, u);
            if (staged != null) {
                trace(c, "已拷贝进 cache 再交给界面：" + u);
                out[i] = staged;
            } else {
                String problem = probe(c, u);
                trace(c, "拷贝失败（" + problem + "）→ 只能原样直通：" + u);
                out[i] = u;   // 拷不动就原样交出去，至少不比现在差
            }
        }
        return out;
    }

    /** 超过这个大小就不拷贝（见 {@link #stageForWebView} 里的说明）。 */
    private static final long MAX_COPY_BYTES = 100L * 1024 * 1024;

    /** 问 provider 这个文件多大；问不到返回 -1（当作"不知道"，仍然拷贝）。 */
    private static long sizeOf(Context c, Uri u) {
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(u,
                    new String[]{ OpenableColumns.SIZE }, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                int idx = cur.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !cur.isNull(idx)) return cur.getLong(idx);
            }
        } catch (Throwable ignore) {
        } finally {
            try { if (cur != null) cur.close(); } catch (Throwable ignore) { }
        }
        return -1;
    }

    /** @return {@code null} 表示读得到；否则是读不到的原因（给日志用） */
    private static String probe(Context c, Uri u) {
        InputStream in = null;
        try {
            in = c.getContentResolver().openInputStream(u);
            if (in == null) return "openInputStream 返回 null";
            byte[] one = new byte[1];
            int n = in.read(one);
            if (n < 0) return "流是空的";
            return null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage());
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) { }
        }
    }

    /** 把内容拷进 {@code cache/attach/}，再通过我们自己的 FileProvider 授权出去。 */
    private static Uri copyInto(Context c, Uri src) {
        InputStream in = null;
        OutputStream os = null;
        try {
            File root = new File(c.getCacheDir(), "attach");
            //noinspection ResultOfMethodCallIgnored
            root.mkdirs();
            purgeOld(root);
            String name = displayName(c, src);
            /*
             * 扩展名不能丢：DSH 拿到文件后靠名字（和 MIME）判断这是什么，
             * 拷成没有后缀的名字，图片就变成了"未知文件"，模型那边也认不出来。
             * 有些 provider 的 DISPLAY_NAME 干脆没有后缀（云盘常见），
             * 所以再从它的 MIME 类型反推一个补上。
             */
            if (name.indexOf('.') < 0) {
                String ext = extensionOf(c, src);
                if (ext != null && !ext.isEmpty()) name = name + "." + ext;
            }
            /*
             * 每个附件一个独立子目录，**文件名保持原样**。
             *
             * 为什么不是"时间戳_原名"平铺在一个目录里：那个前缀是**用户看得见的** ——
             * 界面上的附件卡片、以及 DSH 存下来的文件都会带上它（界面取的是 URI
             * 最后一段），看着就像出了 bug。子目录既避开了重名互相覆盖
             * （同一个文件连选两次不能踩掉前一个），又让名字干净。
             */
            File dir = new File(root, String.valueOf(System.currentTimeMillis()));
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File dst = new File(dir, name);
            in = c.getContentResolver().openInputStream(src);
            if (in == null) return null;
            os = new FileOutputStream(dst);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
            os.close();
            os = null;
            in.close();
            in = null;
            return androidx.core.content.FileProvider.getUriForFile(c, authority(c), dst);
        } catch (Throwable t) {
            Log.w(TAG, "拷贝附件失败", t);
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) { }
            try { if (os != null) os.close(); } catch (Throwable ignore) { }
        }
    }

    /**
     * 清掉昨天以前的附件副本。
     *
     * <p>为什么要清：改成"一律拷贝"之后，用户加过的每个附件都会在 cache 里留一份。
     * 不清理的话，长期使用会悄悄吃掉几百 MB —— 而 cache 目录用户既看不见也清不掉。
     * 留 24 小时是为了"刚加过的还能被重新读一次"（有些上传会晚一点才真正读文件）。
     */
    private static void purgeOld(File root) {
        try {
            File[] kids = root.listFiles();
            if (kids == null) return;
            long cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
            for (File k : kids) {
                if (k.lastModified() >= cutoff) continue;
                if (k.isDirectory()) {
                    File[] sub = k.listFiles();
                    if (sub != null) for (File f : sub) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
                //noinspection ResultOfMethodCallIgnored
                k.delete();
            }
        } catch (Throwable ignore) { }
    }

    /** 从 provider 的 MIME 类型反推扩展名（拿不到就返回 null）。 */
    private static String extensionOf(Context c, Uri u) {
        try {
            String mime = c.getContentResolver().getType(u);
            if (mime == null) return null;
            return android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 拿原始文件名。
     *
     * <p>不能省：DSH 上传后会按文件名判断类型，拷成 {@code 1699999999_} 这种
     * 没有扩展名的名字，模型那边就认不出是图片了。
     */
    private static String displayName(Context c, Uri u) {
        String name = null;
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(u, new String[]{ OpenableColumns.DISPLAY_NAME },
                    null, null, null);
            if (cur != null && cur.moveToFirst()) {
                int idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = cur.getString(idx);
            }
        } catch (Throwable ignore) {
            // 有些 provider 不支持 query，走下面的兜底
        } finally {
            try { if (cur != null) cur.close(); } catch (Throwable ignore) { }
        }
        if (name == null || name.trim().isEmpty()) {
            name = u.getLastPathSegment();
        }
        if (name == null || name.trim().isEmpty()) {
            name = "attachment";
        }
        // 去掉路径分隔符之类的危险字符（这个值会进文件名）
        name = name.replaceAll("[/\\\\:*?\"<>|]", "_");
        if (name.length() > 80) {
            String ext = "";
            int dot = name.lastIndexOf('.');
            if (dot > 0) ext = name.substring(dot);
            name = name.substring(0, 40) + ext;
        }
        return name;
    }
}
