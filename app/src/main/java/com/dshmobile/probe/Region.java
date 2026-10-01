package com.dshmobile.probe;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 环境安装源的「地区」：中国大陆 / 非中国大陆。
 *
 * <h3>为什么要有这个东西</h3>
 * 镜像的快慢是有**地区性**的：
 * <ul>
 *   <li>大陆：阿里云 / 中科大 / 南大是 1 MB/s 级；而 nodejs.org、
 *       archive.ubuntu.com 常常几十 KB/s，慢到触发下载停滞判定；</li>
 *   <li>海外：正好反过来 —— npmjs.org 就是本地源，而阿里云/清华延迟高、
 *       还偶尔被限速，于是"国内源优先"的写死顺序让海外用户装环境屡屡失败。</li>
 * </ul>
 *
 * 这就是用户反馈的"非大陆地区难以安装环境"。之前这里是**写死的**国内源优先。
 * 现在：首次启动问一次（见 {@code MainActivity#askRegionIfNeeded}），
 * 之后可以在「手机权限 → 环境安装源」里随时改 ——
 * 改完**下次装环境**（或者长按「重试」清空重装）就按新地区走。
 *
 * <p>刻意不做成"自动探测"：探测本身要联网测速，而用户装环境时网络恰恰可能不通，
 * 那就会卡在一个无法完成的探测上。让用户选一次，成本最低、也最可控。
 *
 * <p>另外注意：这里改的都是**安装源**，不是 DSH 连的模型地址 ——
 * 后者是 DeepSeek 官方接口，跟地区无关。
 */
public final class Region {

    public static final String CN = "cn";
    public static final String GLOBAL = "global";

    /** 与手机控制/白名单共用同一个 SharedPreferences。 */
    private static final String KEY = "install_region";

    private Region() { }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PhoneBridge.PREFS, Context.MODE_PRIVATE);
    }

    /** 已选地区；**还没选过返回 null**（首次启动时据此弹选择框）。 */
    public static String get(Context c) {
        try {
            String v = sp(c).getString(KEY, null);
            return (CN.equals(v) || GLOBAL.equals(v)) ? v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static void set(Context c, String region) {
        sp(c).edit().putString(KEY, GLOBAL.equals(region) ? GLOBAL : CN).apply();
    }

    /**
     * 没选过时**按大陆处理**。
     *
     * 理由：老用户（升级上来的）本来就在用国内源，不该因为新增这个开关
     * 突然换源；而新用户第一次启动会被问一次，所以"没选过"基本只出现在
     * 升级场景。
     */
    public static boolean isCn(Context c) {
        return !GLOBAL.equals(get(c));
    }

    /** 给界面显示的当前值。 */
    public static String label(Context c) {
        return isCn(c) ? "中国大陆（国内镜像）" : "非中国大陆（官方源）";
    }

    // ── rootfs（Ubuntu base）下载源 ─────────────────────────────
    private static final String ROOTFS_PATH =
            "/ubuntu-cdimage/ubuntu-base/releases/24.04/release/"
            + "ubuntu-base-24.04.5-base-arm64.tar.gz";

    /** 按地区排好优先顺序的 rootfs 源（都实测过 200 + 29,936,675 字节）。 */
    public static String[] rootfsUrls(Context c) {
        String official = "https://cdimage.ubuntu.com" + ROOTFS_PATH;
        String aliyun = "https://mirrors.aliyun.com" + ROOTFS_PATH;
        String ustc = "https://mirrors.ustc.edu.cn" + ROOTFS_PATH;
        String nju = "https://mirror.nju.edu.cn" + ROOTFS_PATH;
        return isCn(c)
                ? new String[]{aliyun, ustc, nju, official}
                : new String[]{official, aliyun, ustc, nju};
    }

    // ── Node.js 二进制包下载源 ──────────────────────────────────
    /**
     * 按地区排好优先顺序的 Node 源。
     *
     * <p>注意：原来第二备用源用的是清华 nodejs-release，实测**已经 404**
     * （2026-09 复测），所以换成了中科大 + 阿里云，并保留官方源兜底。
     */
    public static String[] nodeUrls(Context c) {
        String file = MainActivity.NODE_VERSION + "/" + MainActivity.NODE_DIR + ".tar.gz";
        String npmmirror = "https://registry.npmmirror.com/-/binary/node/" + file;
        String aliyun = "https://mirrors.aliyun.com/nodejs-release/" + file;
        String ustc = "https://mirrors.ustc.edu.cn/nodejs-release/" + file;
        String official = "https://nodejs.org/dist/" + file;
        return isCn(c)
                ? new String[]{npmmirror, aliyun, ustc, official}
                : new String[]{official, npmmirror, aliyun, ustc};
    }

    // ── npm registry ───────────────────────────────────────────
    /** npm 源顺序：第 1 个是主源，第 2 个是重试时换的备用源。 */
    public static String[] npmRegistries(Context c) {
        return isCn(c)
                ? new String[]{"https://registry.npmmirror.com", "https://registry.npmjs.org"}
                : new String[]{"https://registry.npmjs.org", "https://registry.npmmirror.com"};
    }

    // ── 容器内 DNS ─────────────────────────────────────────────
    /**
     * 容器 resolv.conf 的内容。
     *
     * Ubuntu base 的 /etc/resolv.conf 是空的，glibc 完全无法解析域名，
     * 所以必须写进去。第一顺位按地区选，后面几个都是兜底。
     */
    public static String resolvConf(Context c) {
        return isCn(c)
                ? "nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 8.8.8.8\n"
                : "nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 223.5.5.5\n";
    }

    // ── 容器内 apt 源 ───────────────────────────────────────────
    private static final String APT_CN =
            "deb http://mirrors.aliyun.com/ubuntu/ noble main restricted universe multiverse\n"
          + "deb http://mirrors.aliyun.com/ubuntu/ noble-updates main restricted universe multiverse\n"
          + "deb http://mirrors.aliyun.com/ubuntu/ noble-security main restricted universe multiverse\n";

    private static final String APT_GLOBAL =
            "deb http://archive.ubuntu.com/ubuntu/ noble main restricted universe multiverse\n"
          + "deb http://archive.ubuntu.com/ubuntu/ noble-updates main restricted universe multiverse\n"
          + "deb http://security.ubuntu.com/ubuntu/ noble-security main restricted universe multiverse\n";

    /** Ubuntu 24.04 用的是 deb822 格式的 /etc/apt/sources.list.d/ubuntu.sources。 */
    private static String aptDeb822(Context c) {
        String uris = isCn(c)
                ? "http://mirrors.aliyun.com/ubuntu/"
                : "http://archive.ubuntu.com/ubuntu/ http://security.ubuntu.com/ubuntu/";
        return "Types: deb\n"
             + "URIs: " + uris + "\n"
             + "Suites: noble noble-updates noble-security\n"
             + "Components: main restricted universe multiverse\n"
             + "Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n";
    }

    /**
     * 把容器里的 apt 源改成所选地区的镜像。
     *
     * <p>为什么现在就做：Ubuntu base 自带的是 archive.ubuntu.com，
     * 大陆用户之后在容器里 `apt install` 任何东西都是龟速。
     * 这里顺手改掉，成本只有写两个文件（**不跑 apt-get update** ——
     * 那要联网、要几十秒，而装环境阶段最不该再添一个失败点）。
     *
     * <p>两个文件只写其一：24.04 认 deb822 的 ubuntu.sources，
     * 但如果镜像里没有那个文件（老格式），就退回写 sources.list，
     * 免得同一个源被配置两遍、apt 每次都刷一堆 duplicate 警告。
     */
    public static void applyAptSources(Context c, File rootfs) {
        try {
            File deb822 = new File(rootfs, "etc/apt/sources.list.d/ubuntu.sources");
            File legacy = new File(rootfs, "etc/apt/sources.list");
            if (deb822.exists() || new File(rootfs, "etc/apt/sources.list.d").isDirectory()) {
                write(deb822, aptDeb822(c));
                // 老格式那个文件如果存在，清空它，避免重复源
                if (legacy.exists()) write(legacy, "");
            } else {
                write(legacy, isCn(c) ? APT_CN : APT_GLOBAL);
            }
        } catch (Throwable t) {
            // 改源失败不影响容器可用性，只是 apt 慢一点 —— 不该让初始化失败
        }
    }

    private static void write(File f, String text) throws Exception {
        File parent = f.getParentFile();
        if (parent != null) //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 把容器里的 {@code /root/.npmrc} 写成所选地区的 registry。
     *
     * <p>为什么值得写：容器里凡是碰 npm/pnpm 的操作 —— 装插件
     * （{@code dsh plugin add} 内部走 pnpm）、升级内核、用户自己敲
     * {@code npm i} —— 默认都打 registry.npmjs.org。大陆用户在这一步
     * 慢到几分钟甚至超时，而 npmrc 是**一处配置、全局生效**。
     *
     * <p>只在文件不存在或内容不是我们写的时候覆盖：将来 DSH 自己
     * 往 npmrc 里加东西，不该被我们每次启动都抹掉。
     */
    public static void applyNpmrc(Context c, File rootfs) {
        try {
            File npmrc = new File(rootfs, "root/.npmrc");
            String want = "registry=" + npmRegistries(c)[0] + "\n"
                    + "fetch-timeout=60000\n"
                    + "fetch-retries=3\n";
            if (npmrc.exists()) {
                String cur = read(npmrc);
                if (cur != null && cur.contains("registry=") && !cur.contains("dsh-mobile")) {
                    return;                      // 用户/DSH 自己配过，不覆盖
                }
            }
            write(npmrc, want + "# dsh-mobile: 按地区自动写入（手机权限 → 环境安装源）\n");
        } catch (Throwable t) {
            // 写不进去只是"容器内 npm 慢一点"，不该影响可用性
        }
    }

    private static String read(File f) {
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 4096)];
            int n = in.read(buf);
            return n <= 0 ? "" : new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
