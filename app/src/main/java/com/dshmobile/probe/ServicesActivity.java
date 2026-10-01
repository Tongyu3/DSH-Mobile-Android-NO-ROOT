package com.dshmobile.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Map;

/**
 * 「容器常驻服务」管理页。
 *
 * <p>典型用法：把 CLIProxyAPI 这类**本地代理**配进来（它必须先跑起来、客户端才连得上，
 * 而它自己没有任何"启动键"能被客户端按），App 就负责容器就绪后自动拉起、挂了自动重拉。
 *
 * <p>界面很朴素：一条服务一行，能看到状态、能启停、能删。
 * 刻意不做复杂的编辑体验 —— 需要的是一个"配一次就再也不用管"的东西。
 */
public class ServicesActivity extends Activity {

    private LinearLayout list;
    private TextView hint;

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
        title.setText("容器常驻服务");
        title.setTextSize(20f);
        root.addView(title);

        hint = new TextView(this);
        hint.setTextSize(12f);
        hint.setPadding(0, pad / 2, 0, pad / 2);
        hint.setText("给容器里需要一直跑着的东西用（最典型的是本地代理，比如 CLIProxyAPI）：\n"
                + "· App 启动、容器就绪后自动拉起；\n"
                + "· 每 60 秒检查一次，挂了自动重拉（连续失败 5 次就停手，免得白烧电）；\n"
                + "· 重复启动是幂等的，不会越开越多份。\n\n"
                + "命令在容器里以 root 身份、工作目录 /root 执行，例如：\n"
                + "  node /root/cliproxyapi/server.js\n"
                + "日志：容器里 /tmp/dsh-svc-<id>.log");
        root.addView(hint);

        Button add = new Button(this);
        add.setText("添加服务");
        add.setOnClickListener(v -> showEdit(null));
        root.addView(add);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    /** 先把行画出来（状态显示"检查中"），再去容器里一次性问清状态。 */
    private void refresh() {
        list.removeAllViews();
        final List<Services.Item> items = Services.load(this);
        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有配置服务。点上面「添加服务」加一条。");
            empty.setPadding(0, 12, 0, 12);
            list.addView(empty);
            return;
        }
        final Map<String, TextView> stateViews = new java.util.HashMap<>();
        for (final Services.Item it : items) {
            list.addView(buildRow(it, stateViews));
        }
        // 状态要在容器里问，必须离开 UI 线程
        new Thread(() -> {
            final Map<String, String> st = Services.states(getApplicationContext());
            runOnUiThread(() -> {
                for (Services.Item it : items) {
                    TextView tv = stateViews.get(it.id);
                    if (tv != null) tv.setText(Services.describe(it, st.get(it.id)));
                }
            });
        }, "dsh-svc-refresh").start();
    }

    private View buildRow(final Services.Item it, Map<String, TextView> stateViews) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (10 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);

        TextView name = new TextView(this);
        name.setText(it.name + (it.enabled ? "" : "（已停用）"));
        name.setTextSize(15f);
        box.addView(name);

        TextView state = new TextView(this);
        state.setText("检查中…");
        state.setTextSize(12f);
        box.addView(state);
        stateViews.put(it.id, state);

        TextView cmd = new TextView(this);
        cmd.setText(it.cmd);
        cmd.setTextSize(11f);
        cmd.setPadding(0, 4, 0, 8);
        box.addView(cmd);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        Button start = new Button(this);
        start.setText("立即启动");
        start.setOnClickListener(v -> runAsync("启动", () -> {
            Services.resetFails(it.id);
            return Services.ensure(getApplicationContext(), it);
        }));
        row.addView(start);

        Button stop = new Button(this);
        stop.setText("停止");
        stop.setOnClickListener(v -> runAsync("停止", () ->
                Services.stop(getApplicationContext(), it)));
        row.addView(stop);

        Button edit = new Button(this);
        edit.setText("编辑");
        edit.setOnClickListener(v -> showEdit(it));
        row.addView(edit);

        Button del = new Button(this);
        del.setText("删除");
        del.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("删除服务")
                .setMessage("确定删除「" + it.name + "」？\n（容器里正在跑的那个也会被停掉）")
                .setPositiveButton("删除", (d, w) -> {
                    Services.stop(getApplicationContext(), it);
                    List<Services.Item> all = Services.load(this);
                    for (int i = 0; i < all.size(); i++) {
                        if (all.get(i).id.equals(it.id)) { all.remove(i); break; }
                    }
                    Services.save(this, all);
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show());
        row.addView(del);

        box.addView(row);
        return box;
    }

    private interface Job { String run(); }

    /** 容器操作都在后台线程做，完成后回到 UI 提示 + 刷新状态。 */
    private void runAsync(final String what, final Job job) {
        Toast.makeText(this, what + "中…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final String r;
            try {
                r = job.run();
            } catch (Throwable t) {
                runOnUiThread(() -> Toast.makeText(this, what + "失败: " + t,
                        Toast.LENGTH_LONG).show());
                return;
            }
            runOnUiThread(() -> {
                Toast.makeText(this, what + "：" + r, Toast.LENGTH_LONG).show();
                refresh();
            });
        }, "dsh-svc-op").start();
    }

    /** 添加 / 编辑对话框。 */
    private void showEdit(final Services.Item existing) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);

        final EditText name = new EditText(this);
        name.setHint("名称，例如 CLIProxyAPI");
        name.setInputType(InputType.TYPE_CLASS_TEXT);
        if (existing != null) name.setText(existing.name);
        box.addView(name);

        final EditText cmd = new EditText(this);
        cmd.setHint("启动命令，例如 node /root/cliproxyapi/server.js");
        cmd.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        if (existing != null) cmd.setText(existing.cmd);
        box.addView(cmd);

        TextView tip = new TextView(this);
        tip.setTextSize(11f);
        tip.setText("命令在容器里执行（root 身份、工作目录 /root）。\n"
                + "写完建议先关掉 App 的网络代理设置看一眼 —— 它跑起来之后，"
                + "把 DSH 的模型地址指向 http://127.0.0.1:<端口> 就行。");
        box.addView(tip);

        new AlertDialog.Builder(this)
                .setTitle(existing == null ? "添加常驻服务" : "编辑常驻服务")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String n = name.getText().toString().trim();
                    String c2 = cmd.getText().toString().trim();
                    if (c2.isEmpty()) {
                        Toast.makeText(this, "命令不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (n.isEmpty()) n = c2.length() > 24 ? c2.substring(0, 24) : c2;
                    List<Services.Item> all = Services.load(this);
                    if (existing == null) {
                        all.add(new Services.Item(Services.newId(), n, c2, true));
                    } else {
                        for (Services.Item x : all) {
                            if (x.id.equals(existing.id)) { x.name = n; x.cmd = c2; }
                        }
                    }
                    Services.save(this, all);
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
