package com.dshmobile.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 「图标」页：挑桌面快捷方式的配色，通知栏图标跟着一起换。
 *
 * @see IconSwitcher 为什么只能"从预置的一套里选"
 */
public class IconActivity extends Activity {

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
        title.setText("桌面图标");
        title.setTextSize(20f);
        root.addView(title);

        TextView tip = new TextView(this);
        tip.setTextSize(12f);
        tip.setPadding(0, pad / 2, 0, pad);
        tip.setText("· 换的是桌面快捷方式上的图标；通知栏的图标和强调色会跟着一起换\n"
                + "· 切换后系统会重启一下 App（组件状态变了，躲不掉），桌面图标随即更新\n"
                + "· 如果桌面上原来那个图标没变，删掉重新拖一个就好\n\n"
                + "说明：Android 不允许 App 用**任意图片**当桌面图标（图标必须是打包进 APK 的资源），\n"
                + "所以这里提供的是一套预置配色 —— 同一只鲸鱼，换背景色。");
        root.addView(tip);

        List<IconSwitcher.Variant> all = IconSwitcher.all(this);
        IconSwitcher.Variant cur = IconSwitcher.current(this);

        int perRow = 4;
        LinearLayout row = null;
        for (int i = 0; i < all.size(); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                root.addView(row);
            }
            row.addView(buildCell(all.get(i), cur, pad));
        }

        setContentView(scroll);
    }

    private View buildCell(final IconSwitcher.Variant v, IconSwitcher.Variant cur, int pad) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cell.setLayoutParams(lp);
        cell.setPadding(0, pad / 2, 0, pad / 2);

        ImageView iv = new ImageView(this);
        int px = (int) (56 * getResources().getDisplayMetrics().density);
        iv.setLayoutParams(new LinearLayout.LayoutParams(px, px));
        int res = getResources().getIdentifier(v.mipmapName(), "mipmap", getPackageName());
        if (res != 0) iv.setImageResource(res);
        cell.addView(iv);

        TextView label = new TextView(this);
        label.setText(v.label + (v.id.equals(cur.id) ? "\n（使用中）" : ""));
        label.setTextSize(11f);
        label.setGravity(Gravity.CENTER);
        cell.addView(label);

        cell.setOnClickListener(view -> confirm(v, v.id.equals(cur.id)));
        return cell;
    }

    private void confirm(final IconSwitcher.Variant v, boolean isCurrent) {
        if (isCurrent) {
            Toast.makeText(this, "现在用的就是「" + v.label + "」", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("换成「" + v.label + "」？")
                .setMessage("桌面图标和通知栏图标都会换成这套配色。\n\n"
                        + "切换时 App 会重启一下（系统的要求），几秒后桌面图标就更新了。\n"
                        + "会话、API Key、插件、白名单都不受影响。")
                .setPositiveButton("换", (d, w) -> {
                    String err = IconSwitcher.apply(this, v);
                    if (err == null) {
                        Toast.makeText(this, "已切换为「" + v.label + "」——桌面图标马上更新",
                                Toast.LENGTH_LONG).show();
                        finish();
                    } else {
                        Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
