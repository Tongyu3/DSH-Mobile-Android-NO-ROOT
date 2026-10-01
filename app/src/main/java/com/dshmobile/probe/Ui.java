package com.dshmobile.probe;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 本机设置页共用的样式。
 *
 * <h3>为什么要统一</h3>
 * 之前每个页面各写各的：手机控制页塞了十几行解释性小字、白名单页整屏列表、
 * 插件市场又是另一套 —— 用户的原话是"过于杂乱"。这里把样式收在一处，
 * 照着 DSH 自己的插件页（DSH-IM / Agent 预设）学：
 *
 * <pre>
 *   深色底 + 圆角卡片 + 一行标题 + 一行说明（需要才写）
 *   解释性长文一律不放，要说明就一句
 * </pre>
 *
 * <p>刻意**不引入 appcompat/Material**：这个 App 现在只依赖 androidx.core，
 * 为了几个圆角卡片把 Material 整套拖进来不值（APK 会大一大截）。
 * 圆角直接用 {@link GradientDrawable} 画。
 */
public final class Ui {

    /** 页面底色（跟 DSH 的深色主题对齐）。 */
    public static final int BG = 0xFF101014;
    /** 卡片底色。 */
    public static final int CARD = 0xFF1B1B20;
    /** 卡片描边。 */
    public static final int STROKE = 0xFF2A2A31;
    /** 主文字。 */
    public static final int TEXT = 0xFFF2F3F5;
    /** 次要文字。 */
    public static final int SUB = 0xFF9BA1A8;
    /** 强调色。 */
    public static final int ACCENT = 0xFF4C8DFF;
    /** 危险/警告。 */
    public static final int WARN = 0xFFFF8A65;

    private Ui() { }

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    /** 圆角背景。 */
    public static GradientDrawable round(int fill, int stroke, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) d.setStroke(dp(c, 1f), stroke);
        return d;
    }

    /** 页面骨架：深色底 + 可滚动 + 左右留白。往返回的容器里 addView 就行。 */
    public static LinearLayout page(android.app.Activity a, String title, View.OnClickListener back) {
        ScrollView scroll = new ScrollView(a);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(a, 16);
        root.setPadding(p, p, p, dp(a, 28));
        scroll.addView(root);

        if (title != null) {
            LinearLayout head = new LinearLayout(a);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(0, dp(a, 4), 0, dp(a, 16));

            if (back != null) {
                TextView b = new TextView(a);
                b.setText("‹ 返回");
                b.setTextSize(14f);
                b.setTextColor(TEXT);
                b.setPadding(dp(a, 12), dp(a, 7), dp(a, 12), dp(a, 7));
                b.setBackground(round(0x00000000, STROKE, 10, a));
                b.setOnClickListener(back);
                head.addView(b);
                TextView spacer = new TextView(a);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
                head.addView(spacer);
            }

            TextView t = new TextView(a);
            t.setText(title);
            t.setTextSize(19f);
            t.setTextColor(TEXT);
            if (back == null) head.addView(t);
            else head.addView(t);   // 标题靠右也能看；保持简单
            root.addView(head);
        }

        a.setContentView(scroll);
        return root;
    }

    /** 一张卡片：可选标题 + 可选一行说明，返回卡片容器（line=vertical）。 */
    public static LinearLayout card(Context c, String title, String desc) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(CARD, STROKE, 16, c));
        int p = dp(c, 16);
        box.setPadding(p, dp(c, 14), p, dp(c, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 12);
        box.setLayoutParams(lp);

        if (title != null) {
            TextView t = new TextView(c);
            t.setText(title);
            t.setTextSize(16f);
            t.setTextColor(TEXT);
            box.addView(t);
        }
        if (desc != null) {
            TextView d = new TextView(c);
            d.setText(desc);
            d.setTextSize(13f);
            d.setTextColor(SUB);
            d.setPadding(0, dp(c, 6), 0, 0);
            d.setLineSpacing(dp(c, 3), 1f);
            box.addView(d);
        }
        return box;
    }

    /** 卡片里的一行：左标题（可带一行说明），右箭头；可点。 */
    public static View row(Context c, String title, String desc, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(c, 14);
        r.setPadding(dp(c, 4), p, dp(c, 4), p);
        /*
         * ⚠️ 必须显式 MATCH_PARENT。
         * 默认是 WRAP_CONTENT，于是在卡片里这一行只有"标题+箭头"那么宽 ——
         * 点卡片空白处没反应，看起来就像"点了没用"（实测踩到过）。
         */
        r.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(15f);
        t.setTextColor(TEXT);
        col.addView(t);
        if (desc != null) {
            TextView d = new TextView(c);
            d.setText(desc);
            d.setTextSize(12f);
            d.setTextColor(SUB);
            d.setPadding(0, dp(c, 3), 0, 0);
            col.addView(d);
        }
        r.addView(col);

        TextView arrow = new TextView(c);
        arrow.setText("›");
        arrow.setTextSize(18f);
        arrow.setTextColor(SUB);
        arrow.setPadding(dp(c, 10), 0, dp(c, 2), 0);
        r.addView(arrow);

        if (click != null) {
            r.setOnClickListener(click);
            // 给一点按压反馈，不然点上去像没反应
            r.setBackground(round(0x00000000, 0, 10, c));
        }
        return r;
    }

    /** 主按钮（强调色描边，不填充，跟 DSH 的次级按钮一致）。 */
    public static TextView button(Context c, String text, boolean primary, View.OnClickListener click) {
        TextView b = new TextView(c);
        b.setText(text);
        b.setTextSize(14f);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(primary ? Color.WHITE : TEXT);
        b.setPadding(dp(c, 14), dp(c, 11), dp(c, 14), dp(c, 11));
        b.setBackground(round(primary ? 0xFF2A4B8D : 0x00000000, primary ? 0 : STROKE, 10, c));
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 10);
        b.setLayoutParams(lp);
        return b;
    }

    /** 一行小字状态（带颜色）。 */
    public static TextView status(Context c, String text, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(13f);
        t.setTextColor(color);
        t.setLineSpacing(dp(c, 3), 1f);
        t.setPadding(0, dp(c, 6), 0, dp(c, 2));
        // 同上：不占满宽度的话长句会不换行/被截断
        t.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    /** 是否打开状态用的小圆点 + 文案。 */
    public static String onOff(boolean on) {
        return on ? "● 已开启" : "○ 已关闭";
    }
}
