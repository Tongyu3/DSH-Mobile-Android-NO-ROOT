package com.dshmobile.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 「内核升级」页：检测 DSH 内核版本 → 一键升级。
 *
 * <p>为什么要有它：App 装容器时是 {@code npm install -g @deepseek-ai/dsh}（不锁版本），
 * 于是装完那一版就永远停在那一版，上游发新版用户完全不知道。
 * 这里把"检测 + 升级"变成一个按钮。
 *
 * <p>界面上刻意把**两个通道**摆出来：{@code next}（最新，但通常是 pre-release）
 * 和 {@code latest}（稳定标签）。让用户自己看得见自己装的是什么，
 * 而不是我们替他决定"最新就是最好"。
 */
public class UpgradeActivity extends Activity {

    private TextView current;
    private TextView latest;
    private TextView log;
    private Button upgradeBtn;
    private RadioGroup channel;
    private RadioButton rbNext;
    private RadioButton rbLatest;

    private String currentVer;
    private KernelUpgrade.Tags tags;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(Ui.BG);   // 统一成 DSH 那种深色底
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("DSH 内核升级");
        title.setTextSize(20f);
        root.addView(title);

        current = new TextView(this);
        current.setTextSize(14f);
        current.setPadding(0, pad / 2, 0, 4);
        root.addView(current);

        latest = new TextView(this);
        latest.setTextSize(13f);
        latest.setPadding(0, 0, 0, pad / 2);
        root.addView(latest);

        TextView tip = new TextView(this);
        tip.setTextSize(11f);
        tip.setText("· 升级 = 在容器里执行 npm install -g @deepseek-ai/dsh@<版本>\n"
                + "· 你自己的会话、API Key、插件、白名单都不受影响\n"
                + "· 装完会自动核对版本号，不对就报错，不会「装完就当成功」\n"
                + "· 升级需要联网，一般 2~8 分钟");
        root.addView(tip);

        channel = new RadioGroup(this);
        channel.setOrientation(RadioGroup.VERTICAL);
        rbNext = new RadioButton(this);
        rbNext.setText("最新预览版（next 标签）—— 版本最新，但通常是 pre-release");
        rbLatest = new RadioButton(this);
        rbLatest.setText("稳定版（latest 标签）");
        channel.addView(rbNext);
        channel.addView(rbLatest);
        rbNext.setChecked(true);      // 默认跟"最新"
        root.addView(channel);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        Button check = new Button(this);
        check.setText("检查更新");
        check.setOnClickListener(v -> check(false));
        row.addView(check);

        upgradeBtn = new Button(this);
        upgradeBtn.setText("一键升级");
        upgradeBtn.setEnabled(false);
        upgradeBtn.setOnClickListener(v -> confirmUpgrade());
        row.addView(upgradeBtn);
        root.addView(row);

        log = new TextView(this);
        log.setTextSize(10f);
        log.setPadding(0, pad / 2, 0, 0);
        root.addView(log);

        setContentView(scroll);
        check(true);
    }

    private String chosenTag() {
        return rbLatest.isChecked() ? "latest" : "next";
    }

    private String chosenVersion() {
        if (tags == null) return null;
        return rbLatest.isChecked() ? tags.latest : tags.next;
    }

    /** 查版本（联网，必须离开 UI 线程）。 */
    private void check(final boolean initial) {
        current.setText("当前内核：" + (currentVer == null ? "读取中…" : currentVer));
        latest.setText(initial ? "正在检查最新版本…" : "正在检查…");
        new Thread(() -> {
            final String cur = KernelUpgrade.installedVersion(this);
            final KernelUpgrade.Tags t = KernelUpgrade.fetchTags(this, !initial);
            runOnUiThread(() -> {
                currentVer = cur;
                tags = t;
                current.setText("当前内核：" + (cur == null ? "读不到（容器可能还没装好）" : cur));
                if (t == null) {
                    latest.setText("查不到最新版本（没网？registry 不通？）");
                    upgradeBtn.setEnabled(false);
                    return;
                }
                String target = chosenVersion();
                boolean newer = cur == null || target == null
                        || KernelUpgrade.compare(target, cur) > 0;
                latest.setText("npm 上：latest=" + t.latest + "   next=" + t.next
                        + "\n当前选择的 " + chosenTag() + " = " + target
                        + (newer ? "  ← 比你现在的新，可以升级" : "  （已经是最新，无需升级）"));
                upgradeBtn.setEnabled(newer && target != null);
            });
        }, "dsh-upgrade-check").start();
    }

    private void confirmUpgrade() {
        final String v = chosenVersion();
        if (v == null) return;
        new AlertDialog.Builder(this)
                .setTitle("升级内核")
                .setMessage("要升级到 " + v + " 吗？\n\n"
                        + "· 会替换容器里的 DSH 程序，你的会话/Key/插件都不受影响\n"
                        + "· 需要联网，可能要几分钟，中途别关 App\n"
                        + "· 你现在是 " + currentVer + "，万一新版有问题，"
                        + "重装旧版本可以退回")
                .setPositiveButton("开始升级", (d, w) -> runUpgrade(v))
                .setNegativeButton("取消", null)
                .show();
    }

    private void runUpgrade(final String v) {
        upgradeBtn.setEnabled(false);
        log.setText("");
        Toast.makeText(this, "开始升级到 " + v + "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String err = KernelUpgrade.upgrade(this, v, line ->
                    runOnUiThread(() -> {
                        log.append(line + "\n");
                        // 只留最后几十行，免得 TextView 越滚越长
                        CharSequence t = log.getText();
                        if (t.length() > 6000) log.setText(t.subSequence(t.length() - 6000, t.length()));
                    }));
            runOnUiThread(() -> {
                if (err == null) {
                    Toast.makeText(this, "升级完成，正在重启内核…", Toast.LENGTH_LONG).show();
                    // 重启 dsh web，让新版本真正生效
                    DshService.requestRestart();
                    new AlertDialog.Builder(this)
                            .setTitle("升级成功")
                            .setMessage("已升级到 " + v + "。\n\n"
                                    + "内核正在重启，稍等十几秒回到 DSH 界面即可。\n"
                                    + "（如果界面没恢复，退出 App 再进一次。）")
                            .setPositiveButton("好", null)
                            .show();
                } else {
                    Toast.makeText(this, "升级失败", Toast.LENGTH_LONG).show();
                    log.append("\n❌ " + err + "\n");
                    new AlertDialog.Builder(this)
                            .setTitle("升级失败")
                            .setMessage(err + "\n\n你现在仍然是 " + KernelUpgrade.installedVersion(this)
                                    + "，可以再试一次。")
                            .setPositiveButton("好", null)
                            .show();
                }
                check(true);
            });
        }, "dsh-upgrade").start();
    }
}
