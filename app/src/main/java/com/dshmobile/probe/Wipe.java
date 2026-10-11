package com.dshmobile.probe;

import java.io.File;
import java.util.ArrayDeque;

/**
 * 「删除历史对话」的文件操作 —— **纯逻辑，不引用 Android 类**。
 *
 * <h3>为什么单独抽出来</h3>
 * 这是**彻底删除、不可恢复**的操作（不是归档），而 release 包不能用 {@code run-as}
 * 在真机上验证（package not debuggable）。抽成纯逻辑后可以用
 * {@code tools/WipeSelfTest.java} 在 PC 上对**真实目录**跑回归 ——
 * 与本项目其他纯逻辑类（UrlPick / PluginMsg / BallStage …）同一套路，
 * 规矩是"纯逻辑类不许 import android"。
 *
 * <h3>删除范围的约定（用户明确要求）</h3>
 * <ul>
 *   <li>活跃会话目录 + **已归档会话目录**，两者都删；</li>
 *   <li>是彻底清除，不是把会话移进归档；</li>
 *   <li>不跟随符号链接去删链接指向的内容（DSH 会话日志用硬链接，
 *       误删链接目标会波及别处）。</li>
 * </ul>
 */
public final class Wipe {

    private Wipe() { }

    /** 统计结果：文件数 / 目录数 / 失败数。 */
    public static final class Result {
        public int files = 0;
        public int dirs = 0;
        public int failed = 0;

        public int total() { return files + dirs; }

        @Override public String toString() {
            return "文件 " + files + " 个、目录 " + dirs + " 个"
                    + (failed > 0 ? "（失败 " + failed + "）" : "");
        }
    }

    /**
     * 递归删除。符号链接**只删链接本身**，不进入其指向的目录。
     *
     * <p>实现注意：用 {@code getCanonicalFile()} 会解析符号链接，那样就无法区分
     * "这是链接本身"还是"链接指向的内容"，所以这里用绝对路径 + {@code isDirectory}
     * 的常规判断，并显式检查 {@code Files.isSymbolicLink} 的等价物。
     */
    public static Result deleteTree(File root) {
        Result r = new Result();
        deleteTree(root, r);
        return r;
    }

    private static void deleteTree(File f, Result r) {
        if (f == null || !f.exists()) return;
        boolean isLink = isSymlink(f);
        if (!isLink && f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteTree(k, r);
            }
            if (f.delete()) r.dirs++;
            else r.failed++;
        } else {
            if (f.delete()) r.files++;
            else r.failed++;
        }
    }

    /**
     * 判断是否是符号链接。
     *
     * <p>不用 android 也不用 NIO：{@code File.getCanonicalPath()} 与
     * {@code getAbsolutePath()} 不同即说明路径里有链接被解析过。
     * 这个判据在 Windows/Linux 上都成立，且不需要任何额外 API。
     */
    static boolean isSymlink(File f) {
        try {
            return !f.getCanonicalPath().equals(f.getAbsolutePath());
        } catch (Throwable t) {
            // 拿不到 canonical（权限/异常）时保守当作普通项处理，继续删
            return false;
        }
    }

    /**
     * 删除两个会话目录，并**保证活跃会话目录被重建为空目录** ——
     * 否则 DSH 下次写入时可能因为目录不存在而报错。
     *
     * @param sessionsDir 活跃会话目录
     * @param archiveDir  已归档会话目录（可以不存在）
     */
    public static Result wipe(File sessionsDir, File archiveDir) {
        Result a = deleteTree(sessionsDir);
        Result b = deleteTree(archiveDir);
        Result r = new Result();
        r.files = a.files + b.files;
        r.dirs = a.dirs + b.dirs;
        r.failed = a.failed + b.failed;
        if (sessionsDir != null && !sessionsDir.exists()) {
            if (sessionsDir.mkdirs()) r.dirs++;
            else r.failed++;
        }
        return r;
    }

    /** 统计两个目录下当前有多少项（用于二次确认时告诉用户"将删除 N 个会话"）。 */
    public static int countEntries(File sessionsDir, File archiveDir) {
        int n = 0;
        try {
            File[] a = sessionsDir == null ? null : sessionsDir.listFiles();
            if (a != null) n += a.length;
        } catch (Throwable ignore) { }
        try {
            File[] b = archiveDir == null ? null : archiveDir.listFiles();
            if (b != null) n += b.length;
        } catch (Throwable ignore) { }
        return n;
    }

    /** 把两个目录下所有项的名字列出来（诊断/测试用），最多 max 个。 */
    public static java.util.List<String> listEntries(File sessionsDir, File archiveDir, int max) {
        java.util.List<String> out = new java.util.ArrayList<>();
        ArrayDeque<File> q = new ArrayDeque<>();
        if (sessionsDir != null && sessionsDir.exists()) q.add(sessionsDir);
        if (archiveDir != null && archiveDir.exists()) q.add(archiveDir);
        while (!q.isEmpty() && out.size() < max) {
            File d = q.poll();
            File[] kids = d.listFiles();
            if (kids == null) continue;
            for (File k : kids) {
                out.add(k.getName());
                if (out.size() >= max) break;
                if (k.isDirectory()) q.add(k);
            }
        }
        return out;
    }
}
