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
    private EditText filter;
    private AppAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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

        TextView tip = new TextView(this);
        tip.setTextSize(12f);
        tip.setText("勾选允许 DSH 操作的 App（白名单）。\n"
                + "名单之外的应用，agent 既看不到界面也点不动。");
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
        refreshStatus();
    }

    private void refreshStatus() {
        boolean on = DshAccessibilityService.isRunning();
        status.setText(on
                ? "✅ 无障碍服务：已开启"
                : "❌ 无障碍服务：未开启 —— 需要在上面的系统设置里打开「DSH 手机控制」");
        status.setTextColor(on ? Color.parseColor("#4CAF50") : Color.parseColor("#FF7043"));
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
