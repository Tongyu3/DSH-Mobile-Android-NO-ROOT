package com.dshmobile.probe;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「📱 手机控制」设置页：
 *   · 显示无障碍服务是否已开启，并提供跳转到系统设置的入口；
 *   · 列出已安装应用，勾选**允许 DSH 操作**的应用（白名单）。
 *
 * 白名单是唯一的授权边界：不在名单里的应用，agent 既看不到界面也点不动。
 */
public class AppControlActivity extends Activity {

    private final List<String> allPkgs = new ArrayList<>();
    private final List<String> allLabels = new ArrayList<>();
    private final List<Integer> shown = new ArrayList<>();   // 过滤后的下标
    private final Set<String> checked = new HashSet<>();

    private TextView status;
    private TextView tip;
    private android.widget.Switch guardSwitch;
    private android.widget.Switch backendSwitch;
    private EditText filter;
    private AppAdapter adapter;
    /** 界面开着时定时刷新守护状态（修复次数、最近一次时间会变）。 */
    private final android.os.Handler ticker = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            ticker.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /*
         * 看门狗平时由 DshService 拉起。这里再兜一次：
         * 万一用户是直接进本页（服务还没起来 / 被杀过），守护也得在。
         * start() 是幂等的，重复调用无副作用。
         */
        A11yGuard.start(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("📱 手机控制");
        title.setTextSize(20f);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(13f);
        status.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(status);

        Button openSettings = new Button(this);
        openSettings.setText("打开系统「无障碍」设置");
        openSettings.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Throwable t) {
                Toast.makeText(this, "无法打开设置: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        root.addView(openSettings);

        // ── 无障碍守护 ────────────────────────────────────────
        /*
         * 厂商（实测 vivo）会在 QQ / 微信切前台时把无障碍总开关置 0。
         * 拿到一次性授权后本 App 能自己写回去，这里就是那块控制面板。
         */
        guardSwitch = new android.widget.Switch(this);
        guardSwitch.setText("无障碍自动守护（被系统关掉就自动补回来）");
        guardSwitch.setTextSize(13f);
        guardSwitch.setPadding(0, pad / 2, 0, 0);
        guardSwitch.setChecked(Priv.guardEnabled(this));
        guardSwitch.setOnCheckedChangeListener((v, on) -> {
            Priv.setGuardEnabled(this, on);
            Toast.makeText(this, on
                    ? "已开启：无障碍被关掉会自动补回来"
                    : "已关闭：本 App 不会再碰无障碍设置", Toast.LENGTH_LONG).show();
        });
        root.addView(guardSwitch);

        Button grant = new Button(this);
        grant.setText("🔧 用电脑授权（一条命令，永久有效）");
        grant.setOnClickListener(v -> showGrantDialog());
        root.addView(grant);

        Button shizuku = new Button(this);
        shizuku.setText("⚡ 用 Shizuku 授权（不用电脑）");
        shizuku.setOnClickListener(v -> ShizukuBridge.showGrantDialog(this, this::refreshStatus));
        root.addView(shizuku);

        // ── 操作方式（用户自己选） ─────────────────────────────
        /*
         * 两条路各有取舍，所以让用户选，而不是我们替他决定：
         *   无障碍：快，但可能被系统/厂商关掉（本 App 会自己补回来）
         *   直接命令：绕开无障碍，被关掉也能用，但读界面慢得多，且只能输 ASCII
         */
        backendSwitch = new android.widget.Switch(this);
        backendSwitch.setText("用直接命令操作（Shizuku，不依赖无障碍）");
        backendSwitch.setTextSize(13f);
        backendSwitch.setPadding(0, pad / 2, 0, 0);
        backendSwitch.setChecked(ShellControl.MODE_SHELL.equals(ShellControl.mode(this)));
        backendSwitch.setOnCheckedChangeListener((v, on) -> {
            ShellControl.setMode(this, on ? ShellControl.MODE_SHELL : ShellControl.MODE_A11Y);
            if (on && !ShellControl.available()) {
                new android.app.AlertDialog.Builder(this)
                        .setTitle("Shizuku 现在不可用")
                        .setMessage("已切换为「直接命令」，但 Shizuku 当前没在运行，"
                                + "所以暂时会自动退回无障碍。\n\n"
                                + "当前状态：" + ShizukuBridge.statusText(this) + "\n\n"
                                + "启动 Shizuku 后这条通道就会自动生效。")
                        .setPositiveButton("知道了", null)
                        .show();
            } else {
                Toast.makeText(this, on
                        ? "已切换为直接命令（每次读界面会慢一些）"
                        : "已切换回无障碍服务", Toast.LENGTH_LONG).show();
            }
            refreshStatus();
        });
        root.addView(backendSwitch);

        TextView backendTip = new TextView(this);
        backendTip.setTextSize(12f);
        backendTip.setPadding(0, pad / 4, 0, pad / 2);
        backendTip.setText("· 无障碍：快，但可能被系统/厂商关掉（本 App 会自动补回）\n"
                + "· 直接命令：绕开无障碍，但读界面慢，且只能输入英文/数字");
        root.addView(backendTip);

        tip = new TextView(this);
        tip.setTextSize(12f);
        tip.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(tip);

        filter = new EditText(this);
        filter.setHint("搜索应用…");
        root.addView(filter);

        ListView list = new ListView(this);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button save = new Button(this);
        save.setText("保存白名单");
        save.setOnClickListener(v -> {
            PhoneBridge.setAllowed(this, checked);
            // 明确回显保存了什么，便于用户核对（之前只有个数，出错也看不出来）
            StringBuilder names = new StringBuilder();
            for (String p : checked) {
                String label = p;
                try {
                    label = getPackageManager().getApplicationLabel(
                            getPackageManager().getApplicationInfo(p, 0)).toString();
                } catch (Throwable ignore) { }
                if (names.length() > 0) names.append('、');
                names.append(label);
            }
            Toast.makeText(this,
                    "已保存 " + checked.size() + " 个：" + (names.length() == 0 ? "（空）" : names),
                    Toast.LENGTH_LONG).show();
            finish();
        });
        root.addView(save);

        setContentView(root);

        checked.addAll(PhoneBridge.allowed(this));
        loadApps();

        filter.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable e) {
                applyFilter(e.toString().trim().toLowerCase());
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 回到本页时立刻检查一次（用户可能刚从系统设置里改完开关）
        A11yGuard.nudge();
        refreshStatus();
        ticker.removeCallbacks(refreshTick);
        ticker.postDelayed(refreshTick, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ticker.removeCallbacks(refreshTick);
    }

    private void refreshStatus() {
        boolean on = DshAccessibilityService.isRunning();
        status.setText(A11yGuard.describe(this));
        status.setTextColor(on ? Color.parseColor("#4CAF50") : Color.parseColor("#FF7043"));

        if (guardSwitch != null) {
            boolean want = Priv.guardEnabled(this);
            // 只在用户真的改了才回调，否则会把 setChecked 的监听器再触发一遍
            if (guardSwitch.isChecked() != want) {
                guardSwitch.setOnCheckedChangeListener(null);
                guardSwitch.setChecked(want);
                guardSwitch.setOnCheckedChangeListener((v, checked) ->
                        Priv.setGuardEnabled(this, checked));
            }
        }

        tip.setText(Priv.canHeal(this)
                ? "勾选允许 DSH 操作的 App（白名单）。\n名单之外的应用，agent 既看不到界面也点不动。"
                : "勾选允许 DSH 操作的 App（白名单）。\n"
                  + "名单之外的应用，agent 既看不到界面也点不动。\n\n"
                  + "⚠️ 还没做一次性授权，所以无障碍被系统关掉后无法自动补回。\n"
                  + "点上面「用电脑授权」，或装了 Shizuku 就点「用 Shizuku 授权」。");
    }

    /** 展示那条一次性的授权命令，并支持一键复制。 */
    private void showGrantDialog() {
        String cmd = Priv.grantCommand(this);
        String msg = "把手机用数据线连到电脑，在电脑上执行这一条命令：\n\n"
                + cmd + "\n\n"
                + "只有这一次需要电脑。授权结果保存在系统里，"
                + "重启手机、更新 App 都不会掉。\n\n"
                + "如果没有电脑：装一个 Shizuku（Android 11 以上可以全程在手机上完成），"
                + "然后点「用 Shizuku 授权」。";

        new android.app.AlertDialog.Builder(this)
                .setTitle("一次性授权")
                .setMessage(msg)
                .setPositiveButton("复制命令", (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("dsh-grant", cmd));
                    Toast.makeText(this, "已复制，粘到电脑终端里执行", Toast.LENGTH_LONG).show();
                })
                .setNeutralButton("检查一下", (d, w) -> {
                    refreshStatus();
                    Toast.makeText(this, Priv.canHeal(this) ? "已授权 ✅" : "还没授权",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<android.content.pm.ResolveInfo> ris = pm.queryIntentActivities(main, 0);
        Set<String> seen = new HashSet<>();
        for (android.content.pm.ResolveInfo ri : ris) {
            String pkg = ri.activityInfo.packageName;
            if (!seen.add(pkg)) continue;
            String label;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                label = pm.getApplicationLabel(ai).toString();
            } catch (Throwable t) {
                label = pkg;
            }
            allPkgs.add(pkg);
            allLabels.add(label);
        }
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < allPkgs.size(); i++) idx.add(i);
        Collections.sort(idx, new Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                return allLabels.get(a).compareToIgnoreCase(allLabels.get(b));
            }
        });
        // 按标签重排
        List<String> lp = new ArrayList<>(), ll = new ArrayList<>();
        for (int i : idx) { lp.add(allPkgs.get(i)); ll.add(allLabels.get(i)); }
        allPkgs.clear(); allPkgs.addAll(lp);
        allLabels.clear(); allLabels.addAll(ll);
        applyFilter("");
    }

    private void applyFilter(String q) {
        shown.clear();
        for (int i = 0; i < allPkgs.size(); i++) {
            if (q.isEmpty()
                    || allLabels.get(i).toLowerCase().contains(q)
                    || allPkgs.get(i).toLowerCase().contains(q)) {
                shown.add(i);
            }
        }
        adapter.notifyDataSetChanged();
    }

    private class AppAdapter extends BaseAdapter {
        public int getCount() { return shown.size(); }
        public Object getItem(int position) { return allPkgs.get(shown.get(position)); }
        public long getItemId(int position) { return position; }

        public View getView(int position, View convertView, ViewGroup parent) {
            CheckBox cb = (convertView instanceof CheckBox) ? (CheckBox) convertView : new CheckBox(AppControlActivity.this);
            int i = shown.get(position);
            final String pkg = allPkgs.get(i);
            cb.setText(allLabels.get(i) + "\n" + pkg);
            cb.setTextSize(13f);
            /*
             * ⚠️ 顺序至关重要：ListView 会复用行视图，而 setChecked() 会触发
             * **上一次挂在这个视图上的**监听器 —— 于是勾选状态被记到错误的包名上。
             * 这正是"勾选了 A，保存后却变成 B"的根因。
             * 必须先摘掉旧监听器，再 setChecked，最后挂新监听器。
             */
            cb.setOnCheckedChangeListener(null);
            cb.setChecked(checked.contains(pkg));
            cb.setOnCheckedChangeListener((v, isChecked) -> {
                if (isChecked) checked.add(pkg); else checked.remove(pkg);
            });
            return cb;
        }
    }
}
