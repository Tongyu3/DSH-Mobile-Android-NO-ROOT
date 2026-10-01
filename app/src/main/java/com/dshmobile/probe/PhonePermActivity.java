package com.dshmobile.probe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 「手机权限」—— 把原来散在设置列表里的一堆「（本机）」入口收成一个大类。
 *
 * <p>用户的原话：这些页面"过多杂乱的解释"，要"简约大气一些"。
 * 所以这一页只有：一行标题 + 每项一行字 + 一行说明，不再堆解释。
 */
public class PhonePermActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = Ui.page(this, "手机权限", v -> finish());

        TextView sub = Ui.status(this,
                "这些是针对这台手机的本机设置。", Ui.SUB);
        root.addView(sub);

        // ── 手机控制（含白名单）──
        LinearLayout c1 = Ui.card(this, null, null);
        c1.addView(Ui.row(this, "手机控制",
                "允许 DSH 操作哪些应用 · 白名单已选 " + PhoneBridge.allowed(this).size() + " 个",
                v -> open(AppControlActivity.class)));
        root.addView(c1);

        // ── 插件市场 ──
        LinearLayout c2 = Ui.card(this, null, null);
        c2.addView(Ui.row(this, "插件市场",
                "从 npm 搜索并安装 DSH 插件",
                v -> open(PluginMarketActivity.class)));
        root.addView(c2);

        // ── 常驻服务（CPA 提示放在说明里）──
        LinearLayout c3 = Ui.card(this, null, null);
        c3.addView(Ui.row(this, "常驻服务",
                "让本地代理常驻自启 · CPA（CLIProxyAPI）就配在这里",
                v -> open(ServicesActivity.class)));
        root.addView(c3);

        // ── 内核升级（顺手把当前版本显示出来）──
        String cur = KernelUpgrade.installedVersion(this);
        LinearLayout c4 = Ui.card(this, null, null);
        c4.addView(Ui.row(this, "内核升级",
                "当前 " + (cur == null ? "读不到" : cur) + " · 检测到新版本可一键升级",
                v -> open(UpgradeActivity.class)));
        root.addView(c4);

        // ── 桌面图标 ──
        LinearLayout c5 = Ui.card(this, null, null);
        c5.addView(Ui.row(this, "桌面图标",
                "换桌面快捷方式配色 · 通知栏图标跟着一起换",
                v -> open(IconActivity.class)));
        root.addView(c5);

        // ── 环境安装源（地区）──
        LinearLayout c6 = Ui.card(this, null, null);
        c6.addView(Ui.row(this, "环境安装源",
                "当前：" + Region.label(this) + " · 装环境慢或总失败就改这里",
                v -> pickRegion()));
        root.addView(c6);

        // ── 插件安全模式 ──
        // 有插件被停用时这里最显眼 —— 它是"界面被插件卡死"之后唯一的恢复入口
        int offN = Env.disabledBundles(this).size();
        LinearLayout c7 = Ui.card(this, null, null);
        c7.addView(Ui.row(this, "插件安全模式",
                offN == 0 ? "正常（所有插件都会加载）"
                          : "已停用 " + offN + " 个插件 · 点这里恢复",
                v -> pluginSafeMode()));
        root.addView(c7);

        /*
         * ── 复制运行日志 ──
         *
         * 为什么值得单独放一行：App 的现场诊断（界面白屏后怎么恢复的、
         * 附件为什么没加上、自带插件装没装上）全写在它私有目录的 report.txt 里，
         * 用户既看不到也导不出来。没有这个入口，"把日志发我看看"就没法执行，
         * 只能靠反复猜。
         */
        LinearLayout c8 = Ui.card(this, null, null);
        c8.addView(Ui.row(this, "复制运行日志",
                "遇到问题（加不上文件 / 界面白屏）时点这里，然后粘贴给开发者",
                v -> android.widget.Toast.makeText(this,
                        Env.copyReportToClipboard(this, 300),
                        android.widget.Toast.LENGTH_LONG).show()));
        root.addView(c8);
    }

    /**
     * 插件安全模式：恢复被停用的插件 / 手动进入安全模式。
     *
     * <p>什么时候会用到：装了**为 DSH 0.1.x 写的**第三方插件时，
     * 它们等的服务名在 0.2 里已经改了（settingsScope → settings），
     * 前端会一直 pending，整个界面起不来。App 会把它们摘掉让界面先回来，
     * 恢复（或再摘一次）就是这个入口。
     */
    private void pluginSafeMode() {
        final java.util.List<String> off = Env.disabledBundles(this);
        String msg;
        if (off.isEmpty()) {
            msg = "当前所有插件都启用着。\n\n"
                + "如果界面被某个插件卡住（白屏 / Failed to load plugins），"
                + "可以在这里手动进安全模式：只加载官方插件和自带插件，"
                + "其余第三方插件先停掉，等界面回来了再逐个试。";
        } else {
            StringBuilder sb = new StringBuilder("已停用的插件：\n");
            for (String s : off) sb.append("  · ").append(s).append('\n');
            sb.append("\n「恢复全部」会把它们重新加回加载列表。\n")
              .append("如果恢复后界面又起不来，说明其中某个插件与当前 DSH 版本不兼容 —— ")
              .append("那就再进一次安全模式，并把它卸掉。");
            msg = sb.toString();
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("插件安全模式")
                .setMessage(msg)
                .setPositiveButton(off.isEmpty() ? "进入安全模式" : "恢复全部", (d, w) -> {
                    int n = off.isEmpty() ? Env.enterSafeMode(this) : Env.restoreDisabledBundles(this);
                    android.widget.Toast.makeText(this,
                            off.isEmpty() ? ("已停用 " + n + " 个插件，重启 DSH 后生效")
                                          : ("已恢复 " + n + " 个插件，重启 DSH 后生效"),
                            android.widget.Toast.LENGTH_LONG).show();
                    recreate();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 改地区（中国大陆 / 非中国大陆）。
     *
     * <p>只影响**之后**的下载：已装好的容器不会因为改这里就重装。
     * 想让新地区立刻生效，用主界面长按「重试」清空容器重装。
     */
    private void pickRegion() {
        final String[] values = {Region.CN, Region.GLOBAL};
        final String[] labels = {"中国大陆（阿里云 / 中科大镜像）", "非中国大陆（Ubuntu 官方源 / nodejs.org）"};
        int cur = Region.isCn(this) ? 0 : 1;
        new android.app.AlertDialog.Builder(this)
                .setTitle("环境安装源")
                .setSingleChoiceItems(labels, cur, (d, which) -> {
                    Region.set(this, values[which]);
                    d.dismiss();
                    /*
                     * 立刻写进**已经装好的**容器（apt 源 + /root/.npmrc）。
                     * 只改 App 里的偏好、不动容器，用户会觉得"改了没反应"：
                     * 容器内装插件/升级内核仍然打官方源。
                     */
                    final boolean[] wrote = {false};
                    try {
                        java.io.File rootfs = new java.io.File(Env.base(this), "rootfs");
                        if (new java.io.File(rootfs, "usr/bin/bash").exists()) {
                            Region.applyAptSources(this, rootfs);
                            Region.applyNpmrc(this, rootfs);
                            wrote[0] = true;
                        }
                    } catch (Throwable ignore) { }
                    android.widget.Toast.makeText(this,
                            "已改为" + Region.label(this)
                            + (wrote[0] ? " —— 容器里的 apt 源与 npm 源已同步更新"
                                        : " —— 容器还没装好，装的时候会按新地区走"),
                            android.widget.Toast.LENGTH_LONG).show();
                    recreate();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void open(Class<?> cls) {
        try {
            startActivity(new Intent(this, cls));
        } catch (Throwable t) {
            android.widget.Toast.makeText(this, "打不开: " + t, android.widget.Toast.LENGTH_LONG).show();
        }
    }
}
