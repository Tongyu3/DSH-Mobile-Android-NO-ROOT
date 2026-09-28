package com.dshmobile.probe;

import android.content.Context;
import android.graphics.Rect;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 「直接命令」后端：不经过无障碍，直接以 shell 身份操作手机。
 *
 * <h3>为什么要有它</h3>
 * 无障碍服务虽然快，但它<strong>随时可能被系统或厂商策略关掉</strong>。
 * 有了 Shizuku（或 root）之后，我们可以退回到最原始也最可靠的办法：
 * 让 shell 去执行 {@code input tap} / {@code uiautomator dump}。
 * 这条路径不受无障碍开关影响，是"权限被关掉时仍然能干活"的兜底。
 *
 * <h3>它是用户可选的，不是自动的</h3>
 * 因为代价很实在：
 * <ul>
 *   <li>每次读界面都要跑一次 {@code uiautomator dump}，<b>秒级</b>，比无障碍慢一个数量级；</li>
 *   <li>{@code input text} <b>只支持 ASCII</b>，中文输不进去（系统限制，不是我们偷懒）。</li>
 * </ul>
 * 所以默认仍是无障碍，用户在「📱 手机控制」里自己切。
 *
 * <h3>安全边界</h3>
 * 白名单校验<strong>原样保留</strong>：每个动作前先用 shell 问出前台包名，
 * 不在白名单里就直接拒绝。这条路能做的事比无障碍多，所以边界更要守住。
 */
public final class ShellControl {

    private ShellControl() { }

    public static final String MODE_A11Y = "a11y";
    public static final String MODE_SHELL = "shell";

    private static final String PREF_MODE = "control_backend";
    private static final String TMP_XML = "/data/local/tmp/dsh_ui.xml";

    // ── 模式 ────────────────────────────────────────────────

    public static String mode(Context c) {
        return c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE)
                .getString(PREF_MODE, MODE_A11Y);
    }

    public static void setMode(Context c, String m) {
        c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREF_MODE, m).apply();
    }

    /** 当前选了 shell 模式，并且真的有 shell 可用。 */
    public static boolean active(Context c) {
        return MODE_SHELL.equals(mode(c)) && available();
    }

    /** Shizuku 在跑、且给过我们权限。 */
    public static boolean available() {
        try {
            return ShizukuBridge.isRunning() && ShizukuBridge.hasPermission();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 界面用的一句话状态。 */
    public static String describe(Context c) {
        String m = mode(c);
        if (MODE_SHELL.equals(m)) {
            return available()
                    ? "直接命令（Shizuku）"
                    : "直接命令（Shizuku）—— 但 Shizuku 当前不可用，会退回无障碍";
        }
        return "无障碍服务";
    }

    // ── 执行 ────────────────────────────────────────────────

    /** 跑一条 shell 命令，返回合并后的输出。 */
    private static String sh(Context c, String cmd) throws Exception {
        String r = ShizukuBridge.execShell(c, cmd);
        if (r == null) return "";
        int nl = r.indexOf('\n');
        String head = nl < 0 ? r : r.substring(0, nl);
        String body = nl < 0 ? "" : r.substring(nl + 1);
        // 命令本身失败时抛出来，避免上层把报错当成正常输出
        if (head.startsWith("exit=") && !head.equals("exit=0")) {
            throw new IllegalStateException("命令失败(" + head + "): " + body.trim());
        }
        return body;
    }

    /**
     * 取当前前台包名。
     *
     * <h3>为什么要分两层取</h3>
     * 一开始只用 {@code dumpsys window | grep mCurrentFocus}，结果在一个真实场景下判错了：
     * 目标应用弹出一个 {@code PopupWindow} 时，焦点窗口变成
     * {@code Window{com.chaoxing.mobile: parentWindow@... u0 PopupWindow:...}} ——
     * 这种形式里包名后面<b>没有斜杠</b>，正则匹配不到，反而匹配到了列表里另一条
     * 属于我们自己 App 的记录，于是白名单校验把一次合法操作拒了。
     *
     * <p>{@code topResumedActivity} 指向"当前正在跑的那个 Activity"，
     * 不会因为弹窗、输入法、悬浮窗而漂移，所以放在第一优先；
     * {@code mCurrentFocus} 只作为退路。
     */
    public static String foreground(Context c) {
        // 首选：正在 resume 的 Activity（最稳定）
        String pkg = match(c,
                "dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity",
                "topResumedActivity=ActivityRecord\\{[^}]*?\\s([A-Za-z][A-Za-z0-9_.]*)/");
        if (isPackage(pkg)) return pkg;

        // 退路一：焦点窗口（要求 u0 <包名>/ 这种规范形式）
        pkg = match(c,
                "dumpsys window 2>/dev/null | grep -m1 mCurrentFocus",
                "mCurrentFocus=Window\\{[^}]*?\\su\\d+\\s([A-Za-z][A-Za-z0-9_.]*)/");
        if (isPackage(pkg)) return pkg;

        // 退路二：老 ROM 的 mFocusedApp
        pkg = match(c,
                "dumpsys window 2>/dev/null | grep -m1 mFocusedApp",
                "mFocusedApp=ActivityRecord\\{[^}]*?\\s([A-Za-z][A-Za-z0-9_.]*)/");
        return isPackage(pkg) ? pkg : null;
    }

    private static String match(Context c, String cmd, String regex) {
        try {
            String out = sh(c, cmd);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(out);
            return m.find() ? m.group(1) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 包名至少要有一个点，且不含大写（避免把 "PopupWindow" 这类窗口名当成包名）。 */
    private static boolean isPackage(String s) {
        return s != null && s.indexOf('.') > 0 && s.equals(s.toLowerCase());
    }

    /** 白名单校验，与无障碍后端完全一致。 */
    private static String guard(Context c) {
        Set<String> allow = PhoneBridge.allowed(c);
        if (allow.isEmpty()) {
            throw new IllegalStateException(
                    "白名单为空：请先在 DSH 设置 →「📱 手机控制」里勾选允许操作的应用");
        }
        String fg = foreground(c);
        if (fg == null || fg.isEmpty() || !allow.contains(fg)) {
            throw new IllegalStateException("当前前台应用 [" + fg + "] 不在白名单里，已拒绝");
        }
        return fg;
    }

    // ── 各动作 ──────────────────────────────────────────────

    public static String status(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append("操作后端: 直接命令（Shizuku）\n");
        sb.append("Shizuku: ").append(ShizukuBridge.statusText(c)).append('\n');
        Set<String> allow = PhoneBridge.allowed(c);
        String fg = foreground(c);
        sb.append("当前前台: ").append(fg == null ? "(未知)" : fg).append('\n');
        sb.append("白名单(").append(allow.size()).append("): ").append(allow).append('\n');
        sb.append("当前是否允许操作: ")
          .append(fg != null && allow.contains(fg) ? "是" : "否").append('\n');
        return sb.toString();
    }

    public static String dumpUi(Context c) {
        String fg = guard(c);
        Ui ui = uiNodes(c);
        if (ui.error != null) {
            /*
             * 把 uiautomator 自己的输出原样带出来。
             *
             * 这里踩过一次：最初失败时只是静默返回空列表，界面显示"取不到界面"，
             * 完全看不出是权限、路径还是命令本身的问题 ——
             * 而这个项目里已经因为"静默失败"吃过好几次亏了。
             */
            return "ERROR: 取不到界面。\n"
                 + "uiautomator 的输出（截断）：\n" + ui.error + "\n";
        }
        if (ui.nodes.isEmpty()) return "ERROR: 界面树是空的（目标应用可能屏蔽了 uiautomator）\n";
        StringBuilder sb = new StringBuilder("前台应用: ").append(fg).append('\n');
        sb.append("（直接命令后端，来自 uiautomator dump）\n");
        int i = 0;
        for (Node n : ui.nodes) {
            sb.append('[').append(i++).append("] ").append(n.shortCls());
            if (!n.text.isEmpty()) sb.append(" \"").append(n.text.replace('\n', ' ')).append('"');
            sb.append(" (").append(n.bounds.left).append(',').append(n.bounds.top).append(',')
              .append(n.bounds.right).append(',').append(n.bounds.bottom).append(')');
            if (n.clickable) sb.append(" 可点");
            if (n.editable) sb.append(" 可输入");
            if (n.scrollable) sb.append(" 可滚动");
            sb.append('\n');
        }
        return sb.toString();
    }

    public static String tap(Context c, int x, int y) throws Exception {
        guard(c);
        sh(c, "input tap " + x + " " + y);
        return "OK 已点击 (" + x + "," + y + ")\n";
    }

    public static String clickText(Context c, String text) throws Exception {
        guard(c);
        Ui ui = uiNodes(c);
        if (ui.error != null) return "ERROR 取不到界面: " + ui.error + "\n";
        List<Node> nodes = ui.nodes;
        Node hit = null;
        for (Node n : nodes) {
            if (n.text.equals(text)) { hit = n; break; }
        }
        if (hit == null) {
            for (Node n : nodes) {
                if (n.text.contains(text)) { hit = n; break; }
            }
        }
        if (hit == null) return "ERROR 界面上找不到文字: " + text + "\n";
        int cx = (hit.bounds.left + hit.bounds.right) / 2;
        int cy = (hit.bounds.top + hit.bounds.bottom) / 2;
        sh(c, "input tap " + cx + " " + cy);
        return "OK 已点击「" + text + "」(" + cx + "," + cy + ")\n";
    }

    public static String swipe(Context c, int x1, int y1, int x2, int y2, int ms) throws Exception {
        guard(c);
        sh(c, "input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + ms);
        return "OK 已滑动\n";
    }

    /**
     * 输入文字。
     *
     * <p>⚠️ {@code input text} 走的是虚拟键盘的字符映射，**只认 ASCII**。
     * 中文、emoji 都进不去 —— 这是系统命令本身的限制。
     * 遇到非 ASCII 时明确报错，而不是假装成功（否则用户会以为是自己没点对）。
     */
    public static String inputText(Context c, String value) throws Exception {
        guard(c);
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 127) {
                return "ERROR 直接命令后端只能输入 ASCII 字符（系统 input 命令的限制）。\n"
                     + "要输入中文请在「📱 手机控制」里把操作方式切回「无障碍服务」。\n";
            }
        }
        // input text 用 %s 表示空格，且需要转义 shell 元字符
        String escaped = value.replace(" ", "%s")
                .replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("'", "'\\''").replace("&", "\\&").replace(";", "\\;")
                .replace("|", "\\|").replace("<", "\\<").replace(">", "\\>")
                .replace("(", "\\(").replace(")", "\\)").replace("$", "\\$")
                .replace("`", "\\`");
        sh(c, "input text '" + escaped + "'");
        return "OK 已输入\n";
    }

    public static String key(Context c, String name) throws Exception {
        String code;
        switch (name) {
            case "back": code = "KEYCODE_BACK"; break;
            case "home": code = "KEYCODE_HOME"; break;
            case "recents": code = "KEYCODE_APP_SWITCH"; break;
            case "notifications":
                // 没有对应的 keyevent，用 statusbar 命令展开通知栏
                sh(c, "cmd statusbar expand-notifications");
                return "OK 已发送 notifications\n";
            default:
                return "ERROR 不支持的按键: " + name + "（可用 back/home/recents/notifications）\n";
        }
        // home / recents 不算"操作目标应用"，但仍然过一遍白名单，保持边界一致
        guard(c);
        sh(c, "input keyevent " + code);
        return "OK 已发送 " + name + "\n";
    }

    public static String open(Context c, String pkg) throws Exception {
        Set<String> allow = PhoneBridge.allowed(c);
        if (allow.isEmpty()) {
            return "ERROR 白名单为空：请先在 DSH 设置 →「📱 手机控制」里勾选允许操作的应用\n";
        }
        if (pkg == null || !allow.contains(pkg)) {
            return "ERROR 「" + pkg + "」不在白名单里，已拒绝。\n白名单当前为: " + allow + "\n";
        }
        // monkey 不需要知道具体 Activity，比 am start 更省事
        sh(c, "monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1");
        return "OK 已启动 " + pkg + "\n";
    }

    // ── uiautomator dump + 解析 ─────────────────────────────

    private static final class Node {
        String cls = "", text = "";
        final Rect bounds = new Rect();
        boolean clickable, editable, scrollable;
        String shortCls() {
            int i = cls.lastIndexOf('.');
            return i < 0 ? cls : cls.substring(i + 1);
        }
        /** uiautomator 里 text 为空但 content-desc 有值的情况很常见（图标按钮）。 */
        String display() { return text; }
    }

    private static final class Ui {
        final List<Node> nodes = new ArrayList<>();
        String error;
        String parseError;
    }

    /**
     * 跑一次 uiautomator dump 并解析出"值得看的"节点。
     *
     * <p>dump 很慢（通常 0.5~2 秒），所以只在真正需要时调用一次。
     * 把 dump 和 cat 串在同一条命令里是为了省一次进程往返。
     *
     * <p>命令里显式设置 PATH 并用绝对路径：Shizuku 的用户服务进程是由 app_process
     * 拉起来的，它的环境变量和交互式 shell <b>不一样</b>，
     * 依赖 PATH 去找 uiautomator 会以"命令找不到"收场。
     */
    private static Ui uiNodes(Context c) {
        Ui ui = new Ui();
        String out;
        try {
            /*
             * 先删旧文件再 dump —— 这一点很关键。
             *
             * 踩过的坑：dump 失败时文件根本不会更新，而 cat 读到的还是**上一次**的旧 XML。
             * 旧 XML 里当然有 <hierarchy>，于是解析出 0 个节点，界面报"界面树是空的"，
             * 看起来像"目标应用屏蔽了 uiautomator"，实际上命令压根没跑成。
             * 先删掉，就能用"文件不存在"把这种静默失败暴露出来。
             *
             * 同时显式设置 PATH 并用绝对路径：Shizuku 的用户服务进程是 app_process 拉起来的，
             * 环境变量和交互式 shell 不一样，靠 PATH 找 uiautomator 会以"命令找不到"收场。
             */
            out = sh(c, "export PATH=/system/bin:/system/xbin:$PATH; "
                      + "rm -f " + TMP_XML + "; "
                      + "uiautomator dump " + TMP_XML + " 2>&1; "
                      + "echo '---XML---'; cat " + TMP_XML + " 2>&1");
        } catch (Throwable t) {
            ui.error = String.valueOf(t);
            return ui;
        }
        int mark = out.indexOf("---XML---");
        String head = mark < 0 ? out : out.substring(0, mark);
        String xml = mark < 0 ? "" : out.substring(mark + 9);
        /*
         * 从 <?xml / <hierarchy 处切开再解析。
         *
         * 踩过的坑：marker 是用 echo 打出来的，后面天生带一个换行，
         * 于是 XML 声明 <?xml ... ?> 前面多了一个 \n ——
         * 而 XML 规范要求声明必须是文档的**第一个字符**，
         * 解析器直接抛 "Unexpected token @2:1"，一个节点都解析不出来。
         * 表现是"界面树是空的"，看起来像目标应用屏蔽了 uiautomator，
         * 实际上是这里多了一个换行。
         */
        int x = xml.indexOf("<?xml");
        if (x < 0) x = xml.indexOf("<hierarchy");
        if (x > 0) xml = xml.substring(x);
        if (xml.indexOf("<hierarchy") < 0) {
            ui.error = head.trim();
            if (ui.error.isEmpty()) ui.error = "(uiautomator 没有任何输出)";
            return ui;
        }
        parse(xml, ui.nodes, ui);
        if (ui.nodes.isEmpty()) {
            // XML 有了但一个可用节点都没有：把现场信息带出来，别让用户猜
            ui.error = "uiautomator 说：" + (head.trim().isEmpty() ? "(无输出)" : head.trim())
                     + "；XML 长度 " + xml.length() + "，解析出的节点数为 0"
                     + (ui.parseError == null ? "" : "，解析异常: " + ui.parseError);
        }
        return ui;
    }

    private static void parse(String xml, List<Node> out) {
        parse(xml, out, null);
    }

    private static void parse(String xml, List<Node> out, Ui owner) {
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new StringReader(xml));
            int ev;
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (ev != XmlPullParser.START_TAG) continue;
                if (!"node".equals(p.getName())) continue;
                Node n = new Node();
                n.cls = attr(p, "class");
                String text = attr(p, "text");
                String desc = attr(p, "content-desc");
                n.text = !text.isEmpty() ? text : desc;
                n.clickable = "true".equals(attr(p, "clickable"));
                n.scrollable = "true".equals(attr(p, "scrollable"));
                n.editable = n.cls.endsWith("EditText");
                parseBounds(attr(p, "bounds"), n.bounds);
                // 只留下有意义的节点，否则一棵树动辄几百行，agent 读不过来
                if (!n.text.isEmpty() || n.clickable || n.editable
                        || n.cls.endsWith("Button") || n.cls.endsWith("EditText")) {
                    out.add(n);
                }
                if (out.size() > 400) break;
            }
        } catch (Throwable t) {
            // 不能吞掉：之前就是这样静默返回空列表，看起来像"目标应用屏蔽了 uiautomator"
            if (owner != null) owner.parseError = String.valueOf(t);
        }
    }

    private static String attr(XmlPullParser p, String name) {
        String v = p.getAttributeValue(null, name);
        return v == null ? "" : v;
    }

    /** 解析 uiautomator 的 bounds="[l,t][r,b]"。 */
    private static void parseBounds(String s, Rect r) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").matcher(s == null ? "" : s);
            if (m.find()) {
                r.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                      Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
            }
        } catch (Throwable ignore) { }
    }
}
