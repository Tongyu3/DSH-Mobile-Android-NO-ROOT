package com.dshmobile.probe;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
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
 * ✅ 应用白名单：勾选允许 DSH 操作的应用。
 *
 * <h3>为什么要从「手机控制」里拆出来</h3>
 * 原来白名单和一堆开关（无障碍守护、两种授权、截图、操作后端、插件市场…）
 * 挤在同一页。按钮越加越多，**应用列表被压成屏幕的一小条** ——
 * 而这页真正要干的事就是"从一长串应用里找几个勾上"，
 * 列表才是主角，却分到了最少的地方。
 *
 * <p>拆开之后：这一页整屏都是列表；授权与开关留在「手机控制」。
 * 两边都在 DSH 设置里有自己的入口。
 *
 * <h3>白名单是唯一的授权边界</h3>
 * 不在名单里的应用，agent 既读不到界面、也点不动、**连截图都会被拒**。
 */
public class WhitelistActivity extends Activity {

    private final List<String> allPkgs = new ArrayList<>();
    private final List<String> allLabels = new ArrayList<>();
    private final List<Integer> shown = new ArrayList<>();   // 过滤后的下标
    private final Set<String> checked = new HashSet<>();
    /*
     * 白名单里有、但 ACTION_MAIN + CATEGORY_LAUNCHER 查不到的包（例如荣耀桌面
     * com.hihonor.android.launcher）。它们已经进了 allPkgs，单独记一份只是为了
     * 在行文字上标注原因 —— 否则用户看到一个没有桌面图标的应用会以为是脏数据。
     */
    private final Set<String> notOnLauncher = new HashSet<>();

    private TextView count;
    private EditText filter;
    private AppAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(Ui.BG);   // 统一成 DSH 那种深色底
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("应用白名单");
        title.setTextSize(20f);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        count = new TextView(this);
        count.setTextSize(13f);
        head.addView(count);
        root.addView(head);

        TextView tip = new TextView(this);
        tip.setTextSize(12f);
        tip.setPadding(0, pad / 2, 0, pad / 2);
        tip.setText("名单之外的应用，agent 既看不到界面、也点不动，连截图都会被拒绝。");
        root.addView(tip);

        // 两个批量按钮并排放：一个全加、一个全清，语义对称，用户不用在两页之间找
        LinearLayout bulk = new LinearLayout(this);
        bulk.setOrientation(LinearLayout.HORIZONTAL);

        // 一键添加所有应用（用户明确要求）；和「手机控制」里那个是同一件事
        Button addAll = new Button(this);
        addAll.setText("一键添加所有应用");
        addAll.setOnClickListener(v -> confirmAddAll());
        bulk.addView(addAll, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button clearAll = new Button(this);
        clearAll.setText("取消全选");
        clearAll.setOnClickListener(v -> confirmClearAll());
        bulk.addView(clearAll, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(bulk);

        filter = new EditText(this);
        filter.setHint("搜索应用…");
        root.addView(filter);

        ListView list = new ListView(this);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        // 列表吃掉所有剩余空间 —— 这正是把它拆出来的目的
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button save = new Button(this);
        save.setText("保存白名单");
        save.setOnClickListener(v -> {
            PhoneBridge.setAllowed(this, checked);
            // 用户已经自己处理了，把那条"一键放行"的提示撤掉
            QuickAdd.clear(this);
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

        /*
         * 一键放行：从"被白名单挡住"那条通知点进来时，直接把那个应用勾上并保存。
         *
         * 用户不需要在一长串应用里翻找 —— 这正是"白名单一键添加"要解决的：
         * agent 被拒 → 通知 → 点一下 → 已经在白名单里了 → 让 agent 重试。
         */
        String quick = getIntent() == null ? null : getIntent().getStringExtra(QuickAdd.EXTRA_QUICK_ADD);
        if (quick != null && !quick.isEmpty()) {
            checked.add(quick);
            // 这条流程按原样立即保存，不等「保存白名单」—— 用户是从被拒通知点进来的，
            // 目的就是让 agent 马上能重试
            PhoneBridge.setAllowed(this, checked);
            // 它在桌面列表里可能查不到，同样补一行出来，否则计数又会和行数对不上
            addMissingRows(checked);
            sortByName();
            applyFilter("");
            Toast.makeText(this,
                    "已把「" + labelOf(quick) + "」加入白名单 —— 可以让 agent 重试了",
                    Toast.LENGTH_LONG).show();
        }

        filter.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable e) {
                applyFilter(e.toString().trim().toLowerCase());
            }
        });
    }

    /**
     * 一键把所有已安装应用加进白名单。
     *
     * <p>注意：这会**取消安全边界**：白名单是"只允许 DSH 操作这些应用"的唯一约束，
     * 全加进去等于没有约束。所以必须先讲清后果再动手。
     *
     * <p>这里只改内存里的勾选，不直接落盘 —— 落盘动作统一归「保存白名单」，
     * 否则一键添加会立刻生效，而提示却让用户去点保存，两者说的不是一件事。
     */
    private void confirmAddAll() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("把所有应用加入白名单？")
                .setMessage("这会把手机上所有已安装的应用都加进白名单，"
                        + "也就是 DSH 之后可以读取和操作任何一个应用。\n\n"
                        + "白名单是唯一的安全边界，这么做等于把它取消掉。")
                .setPositiveButton("我确定，全部添加", (d, w) -> {
                    checked.clear();
                    for (String p : allPkgs) checked.add(p);
                    applyFilter(filter.getText().toString().trim().toLowerCase());
                    Toast.makeText(this, "已全部加入（共 " + checked.size() + " 个），记得点「保存白名单」",
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 取消全选：同样只改内存里的勾选，不落盘。
     *
     * <p>清空白名单等于让 agent 对所有应用都失去操作权限，所以先问一句；
     * 确认后也只是把勾去掉，还要再点「保存白名单」才真正生效 —— 点错了还有得救。
     */
    private void confirmClearAll() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("取消全部勾选？")
                .setMessage("保存之后，agent 将无法再操作任何一个应用。")
                .setPositiveButton("确定清空", (d, w) -> {
                    checked.clear();
                    applyFilter(filter.getText().toString().trim().toLowerCase());
                    Toast.makeText(this, "已取消全部勾选，点「保存白名单」后生效",
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 包名 → 应用名（拿不到就退回包名）。 */
    private String labelOf(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    /**
     * 把给定的包补成正常可选的行（已在列表里的跳过）。
     *
     * <p>用户报过：标题写着"已选 21"，页面上却只有 20 行，漏掉的那个是
     * com.hihonor.android.launcher（荣耀桌面）—— 它不响应
     * ACTION_MAIN + CATEGORY_LAUNCHER，所以既看不见、也取消不掉。
     * 白名单是唯一的安全边界，边界上的每一项都必须看得到、改得动。
     */
    private void addMissingRows(Set<String> pkgs) {
        Set<String> known = new HashSet<>(allPkgs);
        for (String pkg : pkgs) {
            if (pkg == null || pkg.isEmpty() || !known.add(pkg)) continue;
            allPkgs.add(pkg);
            allLabels.add(labelOf(pkg));   // 解析不出应用名就退回包名
            notOnLauncher.add(pkg);
        }
    }

    /** 按应用名排序，让列表顺序符合用户翻应用的直觉。 */
    private void sortByName() {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < allPkgs.size(); i++) idx.add(i);
        Collections.sort(idx, new Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                return allLabels.get(a).compareToIgnoreCase(allLabels.get(b));
            }
        });
        List<String> lp = new ArrayList<>(), ll = new ArrayList<>();
        for (int i : idx) { lp.add(allPkgs.get(i)); ll.add(allLabels.get(i)); }
        allPkgs.clear(); allPkgs.addAll(lp);
        allLabels.clear(); allLabels.addAll(ll);
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
        /*
         * 白名单里的包必须一个不落地出现在列表里：checked 刚由 PhoneBridge.allowed
         * 填好，就是当前白名单。少了任何一项，用户就没法在这一页把它取消掉。
         */
        addMissingRows(checked);
        sortByName();
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
        if (count != null) count.setText("已选 " + checked.size() + " / 共 " + shown.size());
        adapter.notifyDataSetChanged();
    }

    private class AppAdapter extends BaseAdapter {
        public int getCount() { return shown.size(); }
        public Object getItem(int position) { return allPkgs.get(shown.get(position)); }
        public long getItemId(int position) { return position; }

        public View getView(int position, View convertView, ViewGroup parent) {
            CheckBox cb = (convertView instanceof CheckBox)
                    ? (CheckBox) convertView : new CheckBox(WhitelistActivity.this);
            int i = shown.get(position);
            final String pkg = allPkgs.get(i);
            // 不在桌面列表里的包标注一下，用户才知道这行为什么长得不一样（仍可正常勾选）
            String note = notOnLauncher.contains(pkg) ? "（不在桌面列表）" : "";
            cb.setText(allLabels.get(i) + note + "\n" + pkg);
            // 勾选区域做大一点：行高、字距、内边距都放开，手指不容易点错
            cb.setTextSize(14f);
            int v = (int) (10 * getResources().getDisplayMetrics().density);
            cb.setPadding(cb.getPaddingLeft(), v, cb.getPaddingRight(), v);

            /*
             * ⚠️ 顺序至关重要：ListView 会复用行视图，而 setChecked() 会触发
             * **上一次挂在这个视图上的**监听器 —— 于是勾选状态被记到错误的包名上。
             * 这正是"勾选了 A，保存后却变成 B"的根因。
             * 必须先摘掉旧监听器，再 setChecked，最后挂新监听器。
             */
            cb.setOnCheckedChangeListener(null);
            cb.setChecked(checked.contains(pkg));
            cb.setOnCheckedChangeListener((view, isChecked) -> {
                if (isChecked) checked.add(pkg); else checked.remove(pkg);
                if (count != null) count.setText("已选 " + checked.size() + " / 共 " + shown.size());
            });
            return cb;
        }
    }
}
