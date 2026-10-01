package com.dshmobile.probe;

import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputConnection;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DSH 内置输入法 —— 用来把文字"用键盘的方式"送进目标输入框。
 *
 * <h3>为什么必须有它</h3>
 * 微信会拦截两种常见的注入方式：
 * <ul>
 *   <li>无障碍的 {@code ACTION_SET_TEXT} —— 被忽略，输入框没反应；</li>
 *   <li>shell 的 {@code input text} —— 同样进不去。</li>
 * </ul>
 * 但**输入法是系统认可的合法输入通道**：微信没法区分"用户敲的"和"输入法提交的"，
 * 否则它会把真实用户的输入法也一起挡掉。
 *
 * <h3>怎么被用起来</h3>
 * 它平时**不该是默认输入法**（用户自己也不会选它）。
 * 需要给微信打字的时刻，App 靠 {@code WRITE_SECURE_SETTINGS}
 * 把默认输入法临时切到它，提交完文字再切回用户原来那个 ——
 * 见 {@link ImeInjector}。
 *
 * <h3>为什么界面几乎是空的</h3>
 * 它是被程序驱动的，不需要候选词和键盘布局。
 * 给一个极简的提示条就够了，免得用户看到一片空白以为坏了。
 */
public class DshImeService extends InputMethodService {

    private static final String TAG = "DSH_IME";

    private static volatile DshImeService sInstance;
    private static volatile InputConnection sConnection;

    /** 当前是否有输入框连着（没有的话提交文字是没有意义的）。 */
    public static boolean isReady() {
        return sInstance != null && sConnection != null;
    }

    public static boolean isRunning() {
        return sInstance != null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        Log.i(TAG, "输入法已创建");
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        sConnection = null;
        Log.i(TAG, "输入法已销毁");
        super.onDestroy();
    }

    @Override
    public View onCreateInputView() {
        /*
         * 极简视图：不提供键盘。
         *
         * 注意**不能返回 null** —— 返回 null 时系统会给一个空区域，
         * 布局高度算不准，有的 ROM 会把输入框顶到看不见的地方。
         * 给一个固定高度的提示条最稳。
         *
         * ⚠️ 必须带一个「切回原输入法」按钮。
         * 因为我们会在 agent 干完活之前一直占着默认输入法，
         * 万一自动还原失败，用户就被困在一个**没有键盘**的输入法里，
         * 连字都打不出来 —— 那是比功能失效严重得多的后果。
         * 给一个一键自救的出口，这个风险才是可接受的。
         */
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        row.setPadding(pad, pad, pad, pad);

        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText("DSH 输入中（AI 代打）");
        tv.setTextSize(13f);
        row.addView(tv, new android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.Button back = new android.widget.Button(this);
        back.setText("切回我的输入法");
        back.setOnClickListener(v -> {
            String err = ImeInjector.restore(DshImeService.this);
            android.widget.Toast.makeText(DshImeService.this,
                    err == null ? "已切回你自己的输入法" : err,
                    android.widget.Toast.LENGTH_LONG).show();
        });
        row.addView(back);

        int h = (int) (56 * getResources().getDisplayMetrics().density);
        row.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, h));
        return row;
    }

    @Override
    public void onStartInput(android.view.inputmethod.EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        sConnection = getCurrentInputConnection();
        Log.i(TAG, "输入开始，connection=" + (sConnection != null));
    }

    @Override
    public void onFinishInput() {
        sConnection = null;
        super.onFinishInput();
    }

    /**
     * 提交一段文字到当前输入框。
     *
     * <p>必须在主线程执行（InputConnection 的要求），所以这里用 latch 做同步。
     * 调用方通常在桥的请求线程里，不能直接碰 UI。
     *
     * @return null 表示成功，否则是失败原因
     */
    public static String commit(String text) {
        final DshImeService svc = sInstance;
        if (svc == null) return "输入法没在运行（可能需要重新切换一次）";
        InputConnection ic = sConnection;
        if (ic == null) ic = svc.getCurrentInputConnection();
        if (ic == null) return "当前没有获得焦点的输入框（请先点一下要输入的地方）";

        final InputConnection conn = ic;
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] err = new String[1];
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                /*
                 * 先清空再提交。
                 *
                 * 为什么：目标框里可能已经有旧内容（比如上次没清干净的草稿），
                 * 直接 commitText 会变成追加，用户看到的是"你好你好"。
                 * 用 setComposingText("") + deleteSurroundingText 清一下更干净。
                 */
                conn.deleteSurroundingText(9999, 9999);
                conn.commitText(text, 1);
                if (TextUtils.isEmpty(text)) {
                    // 空串就是"清空"请求，到这里已经完成
                }
            } catch (Throwable t) {
                err[0] = String.valueOf(t);
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) return "提交文字超时（输入框没响应）";
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return "提交被中断";
        }
        return err[0];
    }

    /** 仅清空当前输入框（不动其它内容）。 */
    public static String clear() {
        return commit("");
    }

    /** 让系统重新绑定一次（某些 ROM 切完默认输入法后需要这一步才生效）。 */
    public static void poke() {
        final DshImeService svc = sInstance;
        if (svc == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                InputConnection ic = svc.getCurrentInputConnection();
                if (ic != null) sConnection = ic;
            } catch (Throwable ignore) { }
        });
    }

    /** 供自检用：当前是否连着输入框。 */
    public static boolean hasConnection() {
        return sConnection != null;
    }

    private static final AtomicBoolean sDummy = new AtomicBoolean(false);
}
