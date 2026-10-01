package com.dshmobile.probe;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 容器里的**常驻服务**：自启 + 看护。
 *
 * <h3>为什么需要它（真实需求）</h3>
 * 典型例子是 CLIProxyAPI 这类**本地代理**：它必须先跑起来，客户端才连得上。
 * 它自己不出回答，只负责转发 + 管账号池 —— 也就是说它是**被调用方**，
 * 没有任何"启动键"能从客户端那头按下去。在电脑上它是个常驻服务，
 * 在手机上却变成"每次都得手动拉一次"，于是就有了那个很别扭的绕法
 * （切到别的模型借一次请求把它唤醒，再切回来）。
 *
 * <p>根子上的解法只有一个：**让它常驻**。这个类就是干这个的 ——
 * 用户配一条启动命令，App 负责：
 * <ul>
 *   <li>容器就绪后自动拉起；</li>
 *   <li>定期检查，挂了自动重拉；</li>
 *   <li>重复启动是幂等的（不会越开越多份）。</li>
 * </ul>
 *
 * <h3>怎么做到"App 重启后不会重复拉起"</h3>
 * 容器里的进程**能活过 App 重启**（实测 dsh web 在 force-stop 之后还在跑）。
 * 所以不能只靠内存里的 Process 句柄判断，否则每次启动 App 都会再拉一份，
 * 代理立刻端口冲突。
 *
 * <p>办法是用**容器内的 PID 文件**：启动脚本把 pid 写到
 * {@code /tmp/dsh-svc-<id>.pid}，检查时 {@code kill -0} 一下就知道还活着没。
 * 这套完全在容器里，跟 App 的进程生命周期解耦。
 *
 * <h3>为什么要去掉 --kill-on-exit</h3>
 * 见 {@link Env#containerCommandDetached}：带上它，后台起来的服务会在
 * 启动器退出的那一刻被一起收掉。
 */
public final class Services {

    private static final String TAG = "DSH_SERVICES";

    /** 存配置的 key（跟着 DshService 的 prefs 走）。 */
    private static final String KEY = "container_services";

    /** 容器里的服务脚本目录与 pid/日志路径（都在容器内）。 */
    private static final String DIR_GUEST = "/root/.dsh-services";
    private static final String TMP_GUEST = "/tmp";

    /** 看护间隔：60 秒一次。每次检查要在容器里起一个 bash，不能太频繁。 */
    private static final long CHECK_MS = 60_000L;

    /** 连续失败多少次就放弃自动重启（避免"崩→拉→崩"无限循环烧电）。 */
    private static final int MAX_FAILS = 5;

    private static volatile boolean sWatchdogStarted = false;
    private static volatile long sLastCheckAt = 0L;
    private static final Map<String, Integer> sFails = new ConcurrentHashMap<>();
    private static final Map<String, String> sLastState = new ConcurrentHashMap<>();

    private Services() { }

    // ── 数据 ────────────────────────────────────────────────

    public static final class Item {
        public String id;
        public String name;
        public String cmd;
        public boolean enabled;

        public Item(String id, String name, String cmd, boolean enabled) {
            this.id = id;
            this.name = name;
            this.cmd = cmd;
            this.enabled = enabled;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE);
    }

    public static List<Item> load(Context c) {
        List<Item> out = new ArrayList<>();
        try {
            String raw = prefs(c).getString(KEY, "[]");
            JSONArray arr = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Item(
                        o.optString("id", "s" + i),
                        o.optString("name", "服务" + (i + 1)),
                        o.optString("cmd", ""),
                        o.optBoolean("enabled", true)));
            }
        } catch (Throwable t) {
            Log.w(TAG, "解析服务配置失败", t);
        }
        return out;
    }

    public static void save(Context c, List<Item> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Item it : list) {
                JSONObject o = new JSONObject();
                o.put("id", it.id);
                o.put("name", it.name);
                o.put("cmd", it.cmd);
                o.put("enabled", it.enabled);
                arr.put(o);
            }
        } catch (Throwable ignore) { }
        prefs(c).edit().putString(KEY, arr.toString()).apply();
    }

    public static Item byId(Context c, String id) {
        for (Item it : load(c)) if (it.id.equals(id)) return it;
        return null;
    }

    public static String newId() {
        return "s" + Long.toString(System.currentTimeMillis(), 36);
    }

    // ── 脚本 ────────────────────────────────────────────────

    /**
     * 把服务命令写成容器里的一个脚本文件，然后启动它。
     *
     * <p>为什么要落成文件：用户写的命令里可能有引号、管道、重定向，
     * 直接拼进 {@code bash -lc "..."} 的字符串里必然出错。
     * 写成文件就完全不用转义 —— 内容原样就是他要跑的东西。
     */
    private static File scriptFile(Context c, Item it) {
        File dir = new File(Env.base(c), "rootfs" + DIR_GUEST);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, it.id + ".sh");
    }

    private static void writeScript(Context c, Item it) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/sh\n");
        sb.append("# 由 DSH Mobile「容器常驻服务」自动生成 —— 在这里改没用，改 App 里的配置\n");
        sb.append("# 服务名: ").append(it.name).append('\n');
        sb.append("cd /root 2>/dev/null || true\n");
        sb.append(it.cmd).append('\n');
        FileOutputStream os = new FileOutputStream(scriptFile(c, it));
        try {
            os.write(sb.toString().getBytes("UTF-8"));
        } finally {
            os.close();
        }
    }

    // ── 启动 / 停止 / 状态 ──────────────────────────────────

    /**
     * 确保服务在跑（幂等）。
     *
     * @return 给日志/界面看的一句话
     */
    public static String ensure(Context c, Item it) {
        if (!it.enabled) return "已停用，跳过";
        if (it.cmd == null || it.cmd.trim().isEmpty()) return "没有配置命令，跳过";
        try {
            writeScript(c, it);
        } catch (Throwable t) {
            return "写启动脚本失败: " + t;
        }
        String pid = TMP_GUEST + "/dsh-svc-" + it.id + ".pid";
        String log = TMP_GUEST + "/dsh-svc-" + it.id + ".log";
        String script = DIR_GUEST + "/" + it.id + ".sh";
        String sh = "P=" + pid + "; "
                + "if [ -f $P ] && kill -0 $(cat $P) 2>/dev/null; then echo ALREADY; exit 0; fi; "
                + "nohup /bin/sh " + script + " >> " + log + " 2>&1 & "
                + "echo $! > $P; "
                + "sleep 1; "
                + "if kill -0 $(cat $P) 2>/dev/null; then echo STARTED; else echo DIED; fi";
        return startLauncher(c, sh, log);
    }

    /**
     * 启动服务——**不能等启动器退出**。
     *
     * <h3>为什么不能复用 {@link #exec}</h3>
     * proot 是整棵进程树的 ptrace 监督者：只要后台服务还活着，
     * 启动它的那个 proot **就不会退出**（它得继续给服务翻译系统调用）。
     * 所以"等进程结束再读输出"那套在这里必然超时，而超时后 destroy()
     * 会把监督者杀掉、连服务一起带走 —— 实测就是这么同时踩到
     * "启动超时"和"服务其实在跑"两个矛盾现象的。
     *
     * <p>正确做法：只等它打印出回执（STARTED / ALREADY / DIED），
     * 拿到就撒手，**绝不 destroy** —— 那个 proot 就让它当服务的监督者待着。
     */
    private static String startLauncher(Context c, String script, String log) {
        try {
            File base = Env.base(c);
            String tokenDir = PhoneBridge.tokenDir(c);
            String apiKey = DshService.getApiKey(c);
            List<String> args = new ArrayList<>();
            args.add("-lc");
            args.add(script);
            String[] cmd = Env.containerCommandDetached(base, apiKey, tokenDir, "/bin/bash", args);
            ProcessBuilder pb = Env.buildProcess(base, cmd, new File(base, "lib").getAbsolutePath());
            final Process p = pb.start();
            final java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            long deadline = System.currentTimeMillis() + 12000L;
            while (System.currentTimeMillis() < deadline) {
                if (br.ready()) {
                    String line = br.readLine();
                    if (line != null) sb.append(line).append('\n');
                } else {
                    Thread.sleep(120L);
                }
                String s = sb.toString();
                if (s.contains("STARTED") || s.contains("ALREADY") || s.contains("DIED")) break;
            }
            String s = sb.toString();
            if (s.contains("STARTED")) return "已启动";
            if (s.contains("ALREADY")) return "本来就在跑";
            if (s.contains("DIED")) return "启动后立刻退出了 —— 看日志：" + log;
            // 没等到回执不等于失败：容器忙的时候可能要十几秒
            return "启动命令已发出（没等到回执，稍后用「状态」确认）";
        } catch (Throwable t) {
            return "启动失败: " + t;
        }
    }

    public static String stop(Context c, Item it) {
        String pid = TMP_GUEST + "/dsh-svc-" + it.id + ".pid";
        String sh = "P=" + pid + "; "
                + "if [ -f $P ]; then kill $(cat $P) 2>/dev/null; rm -f $P; echo STOPPED; "
                + "else echo NOPID; fi";
        String out = exec(c, sh, 15000L);
        return out != null && out.contains("STOPPED") ? "已停止" : "本来就没在跑";
    }

    /** 一次问清所有服务的状态（只起一个容器进程，别一个服务起一个）。 */
    public static Map<String, String> states(Context c) {
        Map<String, String> out = new ConcurrentHashMap<>();
        List<Item> list = load(c);
        if (list.isEmpty()) return out;
        StringBuilder sh = new StringBuilder();
        for (Item it : list) {
            String pid = TMP_GUEST + "/dsh-svc-" + it.id + ".pid";
            sh.append("P=").append(pid).append("; ")
              .append("if [ -f $P ] && kill -0 $(cat $P) 2>/dev/null; then echo \"")
              .append(it.id).append(" ALIVE $(cat $P)\"; else echo \"")
              .append(it.id).append(" DEAD\"; fi; ");
        }
        String res = exec(c, sh.toString(), 25000L);
        if (res == null) return out;
        for (String line : res.split("\n")) {
            String[] p = line.trim().split("\\s+");
            if (p.length >= 2) out.put(p[0], line.trim());
        }
        return out;
    }

    /** 界面用：把"状态行"翻译成人话（状态由调用方一次性取好，别一个服务查一次）。 */
    public static String describe(Item it, String stateLine) {
        if (!it.enabled) return "⏸ 已停用";
        if (stateLine == null) return "？取不到状态";
        if (stateLine.contains("ALIVE")) {
            return "✅ 运行中（pid " + stateLine.replaceAll(".*ALIVE\\s*", "").trim() + "）";
        }
        if (gaveUp(it.id)) return "❌ 反复启动失败，已停止自动重启（看日志，或点「立即启动」重来）";
        return "⛔ 没在跑（已启用的话，看护会在一分钟内重拉）";
    }

    /** 是不是已经放弃自动重启了。 */
    public static boolean gaveUp(String id) {
        Integer f = sFails.get(id);
        return f != null && f >= MAX_FAILS;
    }

    // ── 看护 ────────────────────────────────────────────────

    /** 容器就绪后调用：先把启用的一批拉起来。 */
    public static void startEnabled(final Context c) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            List<Item> list = load(app);
            if (list.isEmpty()) return;
            for (Item it : list) {
                if (!it.enabled) continue;
                String r = ensure(app, it);
                Log.i(TAG, "启动常驻服务 [" + it.name + "]: " + r);
            }
        }, "dsh-svc-start").start();
    }

    /**
     * 看护线程：定期检查 + 挂了重拉。
     *
     * <p>只有配置了**启用**的服务才检查 —— 没配置就完全不做事，
     * 免得白白每分钟起一个容器进程。
     */
    public static void startWatchdog(final Context c) {
        if (sWatchdogStarted) return;
        sWatchdogStarted = true;
        final Context app = c.getApplicationContext();
        Thread watch = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(CHECK_MS);
                    List<Item> list = load(app);
                    boolean any = false;
                    for (Item it : list) if (it.enabled) any = true;
                    if (!any) continue;

                    sLastCheckAt = System.currentTimeMillis();
                    Map<String, String> st = states(app);
                    for (Item it : list) {
                        if (!it.enabled) continue;
                        String s = st.get(it.id);
                        if (s != null && s.contains("ALIVE")) {
                            sFails.remove(it.id);
                            sLastState.put(it.id, "alive");
                            continue;
                        }
                        int f = sFails.containsKey(it.id) ? sFails.get(it.id) : 0;
                        if (f >= MAX_FAILS) continue;      // 已经放弃了，别无限重试
                        sFails.put(it.id, f + 1);
                        String r = ensure(app, it);
                        Log.w(TAG, "看护重启 [" + it.name + "]（第 " + (f + 1) + " 次）: " + r);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable t) {
                    Log.w(TAG, "看护循环出错", t);
                }
            }
        }, "dsh-svc-watch");
        watch.setDaemon(true);
        watch.start();
    }

    /** 用户手动"立即启动"时把失败计数清零，否则可能被"已放弃"挡住。 */
    public static void resetFails(String id) {
        sFails.remove(id);
    }

    // ── 执行 ────────────────────────────────────────────────

    /** 在容器里跑一段 shell，返回输出；失败/超时返回 null。 */
    private static String exec(Context c, String script, long timeoutMs) {
        try {
            File base = Env.base(c);
            String tokenDir = PhoneBridge.tokenDir(c);
            String apiKey = DshService.getApiKey(c);
            List<String> args = new ArrayList<>();
            args.add("-lc");
            args.add(script);
            String[] cmd = Env.containerCommandDetached(base, apiKey, tokenDir, "/bin/bash", args);
            ProcessBuilder pb = Env.buildProcess(base, cmd, new File(base, "lib").getAbsolutePath());
            final Process p = pb.start();
            final StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(() -> {
                try {
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (sb.length() < 8000) sb.append(line).append('\n');
                    }
                } catch (Throwable ignore) { }
            }, "dsh-svc-out");
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroy();
                Log.w(TAG, "容器命令超时");
                return null;
            }
            reader.join(1000L);
            return sb.toString();
        } catch (Throwable t) {
            Log.w(TAG, "容器命令失败", t);
            return null;
        }
    }
}
