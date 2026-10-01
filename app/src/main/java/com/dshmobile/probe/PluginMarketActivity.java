package com.dshmobile.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 🧩 插件市场：列出 npm 上的 DSH 插件，一键安装。
 *
 * <h3>为什么"市场"放在 App 里而不是做成 DSH 插件</h3>
 * 装插件要跑 {@code dsh plugin add}，那需要容器里的 proot 环境 ——
 * 而**只有 App 能起它**。DSH 插件自己跑在 dsh 进程里，
 * 没法再起一个 dsh 去改自己的 profile。所以这个界面只能在 App 侧。
 *
 * <h3>搜索从哪来</h3>
 * 直接问 npm registry 的 {@code keywords:dsh-plugin} —— 这正是 DSH 官方的
 * 插件发现机制（发布时给仓库/包打这个标签）。自己维护一份清单会很快过期。
 *
 * <h3>⚠️ 安全上必须说清楚的事</h3>
 * 装一个第三方插件 = **让它的代码在用户手机上跑**，而且是在容器里、
 * 带着 danger-full-access 的权限跑。所以这个界面：
 *   · 每条结果都显示包名和描述，让用户知道自己在装什么；
 *   · 装之前弹一次确认，把上面这句话原样告诉用户；
 *   · 装完明确提示"重启 DSH 后生效"。
 * 我们不替用户做这个信任判断。
 */
public class PluginMarketActivity extends Activity {

    private EditText search;
    private TextView status;
    private ListView list;
    private final List<PhoneBridge.PluginHit> hits = new ArrayList<>();
    private List<String> installed = new ArrayList<>();
    private HitAdapter adapter;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(Ui.BG);   // 统一成 DSH 那种深色底
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("插件市场");
        title.setTextSize(20f);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(12f);
        status.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(status);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        search = new EditText(this);
        search.setHint("搜索插件（留空 = 全部）");
        row.addView(search, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button go = new Button(this);
        go.setText("搜索");
        go.setOnClickListener(v -> doSearch());
        row.addView(go);
        root.addView(row);

        list = new ListView(this);
        adapter = new HitAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button refresh = new Button(this);
        refresh.setText("刷新已安装列表");
        refresh.setOnClickListener(v -> refreshInstalled());
        root.addView(refresh);

        setContentView(root);

        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable e) { }
        });

        refreshInstalled();
        doSearch();
    }

    private void refreshInstalled() {
        installed = PhoneBridge.marketInstalled(this);
        status.setText("已安装 " + installed.size() + " 个："
                + android.text.TextUtils.join("、", installed));
        adapter.notifyDataSetChanged();
    }

    private void doSearch() {
        final String q = search.getText().toString();
        status.setText("正在搜索 npm…");
        new Thread(() -> {
            try {
                final List<PhoneBridge.PluginHit> r = PhoneBridge.marketSearch(this, q);
                ui.post(() -> {
                    hits.clear();
                    hits.addAll(r);
                    adapter.notifyDataSetChanged();
                    status.setText("找到 " + r.size() + " 个插件（npm 上 dsh-plugin 标签）");
                });
            } catch (Throwable t) {
                ui.post(() -> status.setText("搜索失败：" + t.getMessage()
                        + "\n（需要联网；容器里装插件同样需要联网）"));
            }
        }, "market-search").start();
    }

    /** 装之前必须让用户知道自己在做什么。 */
    private void confirmInstall(final String pkg) {
        String warn = "即将安装：\n\n" + pkg + "\n\n"
                + "插件是第三方代码，装上之后会在你的手机里运行。"
                + "（跑在 App 的 Linux 容器内）。\n\n"
                + "请只安装你信任的来源。装完需要重启 DSH 才会生效。\n\n"
                + "确定安装吗？";
        new AlertDialog.Builder(this)
                .setTitle("确认安装插件")
                .setMessage(warn)
                .setPositiveButton("安装", (d, w) -> doInstall(pkg))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doInstall(final String pkg) {
        final ProgressDialog pd = ProgressDialog.show(this, "正在安装",
                pkg + "\n\n第一次装可能会慢一些（要下依赖）", true, false);
        new Thread(() -> {
            final String r = PhoneBridge.marketAdd(this, pkg);
            ui.post(() -> {
                try { pd.dismiss(); } catch (Throwable ignore) { }
                boolean ok = r.startsWith("OK");
                new AlertDialog.Builder(this)
                        .setTitle(ok ? "安装完成" : "安装失败")
                        .setMessage(r + (ok ? "\n\n要现在重启 DSH 吗？" : ""))
                        .setPositiveButton(ok ? "重启 DSH" : "好", (d, w) -> {
                            if (ok) {
                                DshService.requestRestart();
                                Toast.makeText(this, "正在重启 DSH…", Toast.LENGTH_LONG).show();
                                finish();
                            }
                        })
                        .setNegativeButton(ok ? "稍后" : null, null)
                        .show();
                refreshInstalled();
            });
        }, "market-install").start();
    }

    private void confirmRemove(final String pkg) {
        new AlertDialog.Builder(this)
                .setTitle("卸载插件")
                .setMessage("确定卸载 " + pkg + " 吗？\n\n重启 DSH 后生效。")
                .setPositiveButton("卸载", (d, w) -> {
                    final ProgressDialog pd = ProgressDialog.show(this, "正在卸载", pkg, true, false);
                    new Thread(() -> {
                        final String r = PhoneBridge.marketRemove(this, pkg);
                        ui.post(() -> {
                            try { pd.dismiss(); } catch (Throwable ignore) { }
                            Toast.makeText(this, r, Toast.LENGTH_LONG).show();
                            refreshInstalled();
                        });
                    }, "market-remove").start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private class HitAdapter extends BaseAdapter {
        public int getCount() { return hits.size(); }
        public Object getItem(int i) { return hits.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            TextView tv;
            Button btn;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                tv = (TextView) row.getChildAt(0);
                btn = (Button) row.getChildAt(1);
            } else {
                row = new LinearLayout(PluginMarketActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                int p = (int) (8 * getResources().getDisplayMetrics().density);
                row.setPadding(0, p, 0, p);
                tv = new TextView(PluginMarketActivity.this);
                tv.setTextSize(12f);
                row.addView(tv, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                btn = new Button(PluginMarketActivity.this);
                btn.setTextSize(12f);
                row.addView(btn);
            }

            final PhoneBridge.PluginHit h = hits.get(position);
            boolean isInstalled = installed.contains(h.name);
            String desc = h.description.length() > 70
                    ? h.description.substring(0, 70) + "…" : h.description;
            tv.setText(h.name + "  " + h.version + (isInstalled ? "  已装" : "")
                    + "\n" + desc);
            tv.setTextColor(isInstalled ? Color.parseColor("#4CAF50") : Color.DKGRAY);

            if (isInstalled) {
                btn.setText("卸载");
                btn.setOnClickListener(v -> confirmRemove(h.name));
            } else {
                btn.setText("安装");
                btn.setOnClickListener(v -> confirmInstall(h.name));
            }
            return row;
        }
    }
}
