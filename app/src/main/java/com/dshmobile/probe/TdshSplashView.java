package com.dshmobile.probe;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 开屏动画 —— **纯代码绘制**，逐行移植自独立预览版
 * {@code 应用/开屏动画/standalone/splash.html}（浏览器里已定稿的那一版）。
 *
 * <h2>分镜（与预览版完全一致）</h2>
 * <pre>
 *   段1  0.00–0.32s  起于一点：屏幕正中心一个空心圆环，轻微呼吸
 *   段2  0.32–0.66s  延展成线：圆环沿中轴拉长成一条空心线
 *   段3  0.66–1.40s  张成矩形：两端随展开**旋转 90°** → 圆头长成竖边
 *   段4  1.40s→      中线向两端消失，只剩四角直角括号；内容浮现
 *   收尾             collapse() 把整幅画面重新收拢回一个点
 * </pre>
 *
 * <h2>为什么是"代码画"而不是播成片帧</h2>
 * 中间试过把成片导成 216 张 PNG 打进 assets 逐帧播放，出了三个问题：
 * 满分辨率 PNG 在绘制线程同步解码吃满 16ms 帧预算（gfxinfo 直方图 27~61ms 约 50 帧）、
 * 单帧约 5MB 缓存稍大就被系统 OOM kill（logcat {@code lowmemorykiller}）、
 * 光帧序列就 12MB。更要紧的是逐帧播放**把"点→线→面"的生长做丢了**。
 * 用 Canvas 直接画：零解码、零额外内存、APK 不增体积，生长是一等公民。
 *
 * <h2>坐标与缩放</h2>
 * 全部按设计坐标 {@code 360x780} 绘制，再由 canvas 以**等比覆盖**（两轴同一倍数）
 * 缩放铺满屏幕 —— 不可能拉伸变形；超出部分居中裁掉，不留黑边。
 */
public class TdshSplashView extends View {

    // ─────────────────── 配色 ───────────────────
    /*
     * 线条统一纯白（浅色主题纯黑）—— 用户要求："把线条全部做成白的，
     * 白色模式下所有线条都是黑色（DSH 图标 logo 除外）"。
     * 所以只有两种"笔画色"：INK（所有线条/描边/刻度/进度格/文字）+ DIM（次要文字）。
     */
    private static final int DARK_BG = 0xFF04070A, DARK_INK = 0xFFFFFFFF, DARK_DIM = 0xFF9AA3A8;
    private static final int LIGHT_BG = 0xFFEDEEEA, LIGHT_INK = 0xFF000000, LIGHT_DIM = 0xFF5A625F;

    private int cBG, cINK, cDIM;
    private volatile boolean lightMode = false;
    /** DSH 内定主题覆盖：null = 跟随系统。 */
    private volatile Boolean themeOverride = null;

    // ─────────────────── 设计坐标系与分镜 ───────────────────
    private static final float LW = 360f, LH = 780f;

    /*
     * 分镜时间（秒）。
     *
     * ⚠️ T_DOT 原来只有 0.32s：圆点（14px 的空心环）几乎一闪而过，
     * 用户反馈"开始的点没有显示，直接就到空心线条了"。
     * 现在点独占 0.55s，线 0.55→1.00s，张面到 1.65s，节奏更清楚。
     */
    private static final float T_DOT = 0.55f, T_LINE = 1.00f, T_FACE = 1.65f;

    /** 括号矩形：四边同一边距、中心 = 屏幕正中心（上下关于中心对称）。 */
    private static final float BR_M = 26f;
    private static final float BR_CY = LH / 2f;
    /** 四角直角的臂长：**横臂 = 竖臂 = 这个值**（长宽统一）。 */
    private static final float ARM_LEN = 44f;

    // 关键 y（与预览版一致）
    private static final float Y_LOCKUP = 172f;
    private static final float Y_TESS = 378f;
    private static final float Y_RULER = 556f;
    private static final float Y_READ = 652f;
    private static final float Y_GRID = 678f;
    private static final float Y_TY = 718f;
    private static final float M = 40f;          // 内容安全边距

    // 生长形状参数
    private static final float R_DOT = 14f;
    private static final float LINE_HALF = 172f;
    private static final float LINE_HALF_H = 7f;
    private static final float STROKE_LINE = 2.0f;    // 空心线阶段的粗细
    private static final float STROKE_BRACKET = 0.9f; // 括号阶段的细线
    private static final float CAP_R = 18f;           // 端头圆弧半径上限（防巨大半圆）
    private static final float RECT_FULL = 0.40f;     // faceA：矩形张开完成
    private static final float BRACKET_T = 0.52f;     // faceA：长边收到只剩直角
    private static final float V_FADE_T = 0.70f;      // faceA：左右竖边淡出完成
    private static final float GROW_FADE = 0.86f;     // faceA：生长形状整体淡出

    /**
     * 收尾时长：把整套分镜**倒放**一遍。
     * 生长是 1.65s（点 0.55 → 线 1.00 → 面 1.65），倒放同样需要这段时间才看得清，
     * 原来只有 0.76s —— 太快，直角还没连成矩形就没了。
     */
    private static final long COLLAPSE_MS = 1700L;
    /** 收尾时"文字/读数"在整段收尾的前多少比例内淡出干净（之后才轮到几何往回缩）。 */
    private static final float CONTENT_FADE_OUT = 0.25f;

    // ─────────────────── 画笔 ───────────────────
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final Typeface tfBold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    private final Typeface tfMono = Typeface.MONOSPACE;

    /** 官方鲸鱼（drawable-nodpi/ic_whale_mark.png）。浅色主题下反相成黑色。 */
    private Bitmap whaleBmpDark, whaleBmpLight;

    // ─────────────────── 运行状态 ───────────────────
    private long startAt;
    /** 已终止（开屏被收掉）—— 之后不再绘制、也不再请求重绘。 */
    private volatile boolean stopped = false;
    /** 诊断日志限频。 */
    private long lastDiagAt = 0L;
    private ValueAnimator anim;

    private volatile float progress = 0f;
    private volatile boolean external = false;
    private volatile String status = null;

    private volatile boolean collapsing = false;
    private long collapseStart = 0L;
    private Runnable collapseCb;


    public TdshSplashView(Context c) { super(c); init(); }
    public TdshSplashView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        applyTheme();
        setBackgroundColor(cBG);
        setClickable(true);
        setFocusable(false);
        startAt = SystemClock.uptimeMillis();
        /*
         * 60fps 心跳驱动重绘。画成什么样完全由 elapsed time 算出来（不依赖
         * ValueAnimator 的插值值），所以任何一帧都能独立求出，偶发掉帧只是少画几帧，
         * 不会让动画变形或错位。
         */
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1000);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> { if (!stopped) invalidate(); });
        anim.start();
    }

    /** 按 DSH 内定主题（外部通过 {@link #setLightTheme} 告知）或系统明暗选配色。 */
    private void applyTheme() {
        final int mode = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        final boolean systemLight = (mode == Configuration.UI_MODE_NIGHT_NO);
        lightMode = themeOverride != null ? themeOverride : systemLight;
        cBG = lightMode ? LIGHT_BG : DARK_BG;
        cINK = lightMode ? LIGHT_INK : DARK_INK;
        cDIM = lightMode ? LIGHT_DIM : DARK_DIM;
        android.util.Log.i("DSH_SPLASH", "主题: uiMode=" + Integer.toHexString(mode)
                + " DSH覆盖=" + themeOverride + " → " + (lightMode ? "浅色" : "深色")
                + " 底色=" + Integer.toHexString(cBG));
    }

    /**
     * 告知"DSH 内定的主题是浅色还是深色"。
     *
     * <p>优先级：**DSH 内定 > 系统 uiMode** —— DSH 的主题与系统可以相反
     * （真机实测：系统 dark、DSH light），只看系统必然跟错。
     */
    public void setLightTheme(boolean light) {
        themeOverride = light;
        post(() -> {
            applyTheme();
            setBackgroundColor(cBG);
            invalidate();
        });
    }

    /**
     * 停止心跳、释放鲸鱼位图。**幂等**。
     *
     * <h3>为什么必须停（踩过）</h3>
     * 这个 60fps 心跳是 {@code INFINITE} 的，而开屏被移除（{@code dismissSplash}）
     * 之后一直没人停它 —— 真机日志里能看到收尾结束后
     * {@code t=10.941 dot=1.000 face=1.000}，也就是**动画自己从头又跑了一遍**，
     * 白耗电、还在后台持续请求重绘。
     */
    public void stopAnimating() {
        stopped = true;
        try {
            if (anim != null) {
                anim.cancel();
                anim = null;
            }
        } catch (Throwable ignore) { }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimating();
        try {
            if (whaleBmpDark != null && !whaleBmpDark.isRecycled()) whaleBmpDark.recycle();
            if (whaleBmpLight != null && !whaleBmpLight.isRecycled()) whaleBmpLight.recycle();
        } catch (Throwable ignore) { }
        whaleBmpDark = null;
        whaleBmpLight = null;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != View.VISIBLE) stopAnimating();
    }

    /**
     * 外部上报**真实加载进度**（0..1）。段4 之后的读数（阶段文案 / 百分比 / 进度格）
     * 完全跟着它走：加载快就走得快、慢就停在当前形态等。
     */
    public void setProgress(float v) {
        external = true;
        progress = clamp(v);
        postInvalidateOnAnimation();
    }

    public void setStatus(String s) { status = s; }

    /**
     * 收尾：整幅画面重新收拢回一个点，然后回调 onDone（外面据此收掉开屏）。
     * 用户要求"加载完后所有东西再重新缩为一个点"，这就是它。
     */
    public void collapse(Runnable onDone) {
        collapseCb = onDone;
        collapsing = true;
        collapseStart = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    @Override
    protected void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        applyTheme();
        invalidate();
    }

    private static float clamp(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    private static float easeOut(float v) {
        v = clamp(v);
        return 1f - (float) Math.pow(1f - v, 3);
    }

    private static float easeInOut(float v) {
        v = clamp(v);
        return v < 0.5f ? 4f * v * v * v : 1f - (float) Math.pow(-2f * v + 2f, 3) / 2f;
    }

    private static float seg(float t, float a, float b) {
        return clamp((t - a) / (b - a));
    }

    /* ─────────────── 真实进度 ↔ 动画的桥 ─────────────── */

    /**
     * 进度条的时间下限：没有任何外部进度上报时，进度条也按这个时长匀速走满。
     *
     * <p>用户反馈过"进度条根本跑不到百分百"：原来进度条读的是一条 4.2 秒的时间轴，
     * 但**同时被"真实进度 + 12% 余量"封顶** —— 而真实进度只在
     * {@code awaitUrl} 轮询里每 5 秒上报一次、轮询一返回就不再上报，
     * 于是进度条被钉在半路（真机实测停在 40% 左右不动）。
     * 现在**不再封顶**：时间下限保底推进 + 真实进度取较大值，
     * 服务就绪时 {@code setProgress(1f)} 直接补满。
     */
    private static final float FLOOR_MS = 6500f;

    /**
     * 当前"该走到哪"的综合进度 0..1。**增长动画与进度条都用它**。
     *
     * <h3>为什么要"取较大值"（这是被真机反复教会的）</h3>
     * 一度让动画**完全**由外部进度决定，结果真机上进度只在少数几个阶段上报，
     * 帧号会连着好几秒不变 —— 用户报的"动画完全卡住"就是这个。
     * 反过来只按时间走，又会出现"加载早就完了动画还在转"。所以：
     * <ul>
     *   <li><b>时间下限</b>：保底匀速推进，永不静止；</li>
     *   <li><b>真实进度</b>：可以超过时间轴（加载快就更快走完）；</li>
     *   <li>两者取 max，天然单调、不回退、不循环。</li>
     * </ul>
     */
    private float progressFraction() {
        final float byTime = clamp((SystemClock.uptimeMillis() - startAt) / FLOOR_MS);
        final float real = external ? clamp(progress) : 0f;
        // 时间下限保底 + 真实进度取较大值：单调、不静止、就绪即补满
        return Math.max(byTime, real);
    }

    // ══════════════════════════ 主绘制 ══════════════════════════

    @Override
    protected void onDraw(Canvas cv) {
        if (stopped) return;                    // 已收掉：不再绘制（心跳也已停）
        final int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 等比覆盖：两轴同一个缩放倍数（不可能拉伸），铺满整屏（不可能留白）
        final float s = Math.max(w / LW, h / LH);
        cv.save();
        cv.translate((w - LW * s) / 2f, (h - LH * s) / 2f);
        cv.scale(s, s);

        cv.drawColor(cBG);                       // 底色先铺满，绝不露白

        /*
         * ── 两条时间轴：几何时间 tGeom / 内容淡出 fadeA ──
         *
         * 正常播放时两者同源（都跟 startAt 走）。
         * 收尾时**几何倒放回去**（直角先重新连成矩形 → 压成空心线 → 缩回起点那个点），
         * 而文字/读数在前 25% 就淡出干净 —— 这样几何往回缩的时候画面上已经没有文字了，
         * 不会出现"文字被收缩的线条穿过去"的穿模。
         */
        final float tGeom;
        final float fadeA;                       // 内容整体不透明度（0 时完全不画内容）
        if (collapsing) {
            final long el = SystemClock.uptimeMillis() - collapseStart;
            final float k = clamp(el / (float) COLLAPSE_MS);
            // 几何用前 85% 的时间倒放完，末尾留一点时间让最后的点也收干净
            final float gk = clamp(k / 0.85f);
            tGeom = T_FACE * (1f - gk);
            fadeA = 1f - clamp(k / CONTENT_FADE_OUT);   // 前 25% 淡出文字
            if (el >= COLLAPSE_MS) {
                collapsing = false;
                /*
                 * 收尾一结束就**在这里**置停，不再等外面的 dismissSplash()。
                 *
                 * 踩过的坑：dismissSplash() 是幂等的，若它先前已被别的路径调用过（点一下屏幕、
                 * 失败路径、SPLASH_MAX_MS 兜底），这次就会直接 return，里面的
                 * stopAnimating() 根本不会执行 —— 于是心跳继续跑，动画从起点又播一遍。
                 * 真机日志里那条 t=10.941（收尾已到 0 之后又跳回满画面）就是它。
                 */
                stopped = true;
                final Runnable cb = collapseCb;
                collapseCb = null;
                if (cb != null) post(cb);
            }
        } else {
            tGeom = (SystemClock.uptimeMillis() - startAt) / 1000f;
            fadeA = 1f;
        }
        final float t = tGeom;

        /*
         * 生长分镜**纯按时间**走，不掺外部加载进度。
         *
         * 踩过的坑：一度把进度折算进三段生长（0→0.10 点、0.10→0.22 线、0.22→1 面），
         * 结果外部进度一上报就把 lineA 直接推满 —— **圆点被瞬间跳过**，
         * 用户看到的就是"开始的点没有显示，直接就到空心线条了"。
         * 现在生长是一条固定时间轴（点 0.55s → 线 1.00s → 面 1.65s），
         * 保证"点→线→面"每次都完整看得见；
         * 外部进度**只驱动底部读数**（百分比 / 进度格 / 阶段文案），两者各司其职。
         */
        final float dotA  = easeOut(seg(t, 0f, T_DOT));
        final float lineA = easeInOut(seg(t, T_DOT, T_LINE));
        final float faceA = easeInOut(seg(t, T_LINE, T_FACE));

        // 括号矩形几何：中心 = 屏幕中心，上下左右边距都是 BR_M → 天然关于中心对称
        final float cx = LW / 2f, cy = BR_CY;
        final float FACE_HW = LW / 2f - BR_M;
        final float FACE_HH = LH / 2f - BR_M;

        float halfW = R_DOT * dotA;
        halfW += (LINE_HALF - halfW) * easeOut(lineA);
        halfW += (FACE_HW - halfW) * faceA;
        float halfH = R_DOT * dotA;
        halfH += (LINE_HALF_H - halfH) * lineA;
        halfH += (FACE_HH - halfH) * faceA;

        drawGrowth(cv, cx, cy, halfW, halfH, dotA, faceA);

        // 诊断（限频 300ms）：把真实时间轴与三段进度打出来，定位"点看不到"的根因
        final long nowMs = SystemClock.uptimeMillis();
        if (nowMs - lastDiagAt > 300L) {
            lastDiagAt = nowMs;
            android.util.Log.i("DSH_SPLASH", String.format(java.util.Locale.ROOT,
                    "t=%.3f dot=%.3f line=%.3f face=%.3f halfW=%.1f halfH=%.1f ext=%s pf=%.3f",
                    t, dotA, lineA, faceA, halfW, halfH, external, progressFraction()));
        }

        // 内容：在"面"之上浮现
        final float inA = clamp((t - T_LINE) / (T_FACE - T_LINE));
        if (inA > 0.02f && fadeA > 0.01f) {
            /*
             * 每一项的入场进度都乘上 fadeA。
             *
             * 为什么必须乘：收尾是"几何倒放"（直角→矩形→线→点），
             * 如果文字不跟着退场，收缩中的线条会直接压在锁标/立方体/读数上 ——
             * 看起来就是"文字穿模"。乘上 fadeA 后，文字在收尾前 25% 就退干净了，
             * 几何才开始往回缩，两者在时间上完全不重叠。
             */
            drawLockup(cv, clamp((inA - 0.05f) / 0.30f) * fadeA);
            drawTesseract(cv, cx, Y_TESS, 50f * clamp((inA - 0.12f) / 0.40f) * fadeA, t);
            drawRuler(cv, clamp((inA - 0.40f) / 0.30f) * fadeA);
            drawBottom(cv, clamp((inA - 0.50f) / 0.35f) * fadeA);
        }

        cv.restore();
    }

    /**
     * 生长形状：空心点 → 空心线 → 张成矩形 → 中线向两端消失 → 只剩四角直角。
     *
     * <h3>怎么做到"几何连贯"</h3>
     * 分段画，每段都有明确起止点；关键是**角上那个点始终固定在
     * {@code (cx±halfW, cy±halfH)}**：展开时 halfH 从 7 涨到 FACE_HH，
     * 端头圆弧被同步拉长并**旋转**，末端正好落在角点上 —— 没有跳变。
     * 等中间段收到 0、竖边淡出，剩下的就是那个正在成形的直角。
     */
    private void drawGrowth(Canvas cv, float cx, float cy,
                            float halfW, float halfH, float dotA, float faceA) {
        final float arm = ARM_LEN * easeOut(clamp(faceA / BRACKET_T));
        final float segLen = Math.max(0f, halfW - arm);
        final float vFade = 1f - clamp((faceA - RECT_FULL) / (V_FADE_T - RECT_FULL));
        final float growFade = 1f - clamp((faceA - V_FADE_T) / (GROW_FADE - V_FADE_T));

        if (dotA > 0.01f && halfW > 0.4f && halfH > 0.4f && growFade > 0.02f) {
            final float L = cx - halfW, R = cx + halfW;
            final float Tp = cy - halfH, B = cy + halfH;
            /*
             * ★ 端头圆弧半径**必须封顶**（踩过的坑）：
             * 原来取 rCap = halfH，圆点/线阶段没问题（半高=半径 → 圆环/圆头），
             * 但张面时 halfH 涨到 364，半径跟着涨 → 画出一个罩住半个屏幕的巨大半圆。
             */
            final float rCap = Math.min(Math.min(halfH, halfW), CAP_R);
            final float lw = STROKE_LINE + (STROKE_BRACKET - STROKE_LINE) * clamp(faceA / RECT_FULL);

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.BUTT);
            p.setStrokeJoin(Paint.Join.MITER);
            p.setStrokeWidth(lw);
            p.setColor(cINK);

            // ① 左右端头：随展开旋转 90°（横向圆头 → 竖向直边）
            p.setAlpha((int) (255 * growFade));
            final float capRot = easeInOut(clamp(halfH / 60f));
            final float capLen = Math.max(0f, halfH - rCap) * capRot;
            rect.set(L, cy - rCap, L + rCap * 2f, cy + rCap);          // 左端半圆
            cv.drawArc(rect, 90f, 180f, false, p);
            if (capLen > 0.2f) {
                cv.drawLine(L, cy - rCap, L, cy - rCap - capLen, p);
                cv.drawLine(L, cy + rCap, L, cy + rCap + capLen, p);
            }
            rect.set(R - rCap * 2f, cy - rCap, R, cy + rCap);          // 右端半圆
            cv.drawArc(rect, 270f, 180f, false, p);
            if (capLen > 0.2f) {
                cv.drawLine(R, cy - rCap, R, cy - rCap - capLen, p);
                cv.drawLine(R, cy + rCap, R, cy + rCap + capLen, p);
            }

            // ② 上/下边中间那段（随 faceA 收到 0 = 消失，这是"向两端消失"的主体）
            if (segLen > 0.2f) {
                cv.drawLine(cx - segLen, Tp, cx + segLen, Tp, p);
                cv.drawLine(cx - segLen, B, cx + segLen, B, p);
            }

            // ③ 上下边靠角的外侧一段（留下的就是直角的两条臂之一）
            if (arm > 0.2f) {
                cv.drawLine(L, Tp, L + arm, Tp, p);
                cv.drawLine(R - arm, Tp, R, Tp, p);
                cv.drawLine(L, B, L + arm, B, p);
                cv.drawLine(R - arm, B, R, B, p);
            }

            // ④ 左右竖边：矩形张开后整条淡出，只画在两个角点之间（不越出横边）
            if (vFade > 0.02f) {
                p.setAlpha((int) (255 * vFade * growFade));
                cv.drawLine(L, Tp, L, B, p);
                cv.drawLine(R, Tp, R, B, p);
            }
            p.setAlpha(255);
        }

        /*
         * 四角直角括号（最终留在画面上的东西）。
         * 位置与上面矩形的角点完全一致，所以交棒时看不出接缝；
         * 横臂与竖臂都等于 arm → **长宽统一**。
         */
        final float bracketA = clamp((faceA - BRACKET_T * 0.55f) / (V_FADE_T - BRACKET_T * 0.55f));
        if (bracketA > 0.02f && halfW > 20f && halfH > 20f) {
            final float L = cx - halfW, R = cx + halfW;
            final float Tp = cy - halfH, B = cy + halfH, a = arm;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(STROKE_BRACKET);
            p.setStrokeCap(Paint.Cap.BUTT);
            p.setStrokeJoin(Paint.Join.MITER);
            p.setColor(cINK);
            p.setAlpha((int) (255 * bracketA));
            cv.drawLine(L, Tp + a, L, Tp, p);  cv.drawLine(L, Tp, L + a, Tp, p);   // 左上
            cv.drawLine(R - a, Tp, R, Tp, p);  cv.drawLine(R, Tp, R, Tp + a, p);   // 右上
            cv.drawLine(L, B - a, L, B, p);    cv.drawLine(L, B, L + a, B, p);     // 左下
            cv.drawLine(R - a, B, R, B, p);    cv.drawLine(R, B, R, B - a, p);     // 右下
            p.setAlpha(255);
        }
    }

    // ─────────────────── 内容 ───────────────────

    /** 官方「鲸鱼 + deepseek + HARNESS」字标。宽度先量再收，绝不溢出。 */
    private void drawLockup(Canvas cv, float a) {
        if (a <= 0.02f) return;
        final float cw = LW - M * 2;
        loadWhale();

        float markH = 24f;
        final float WHALE_AR = 991f / 744f;             // 官方标 991x744
        float markW = markH * WHALE_AR;
        float gap1 = markH * 0.26f, gap2 = markH * 0.28f;

        text.setTypeface(tfBold);
        text.setTextSize(markH * 0.86f);
        float dsW = text.measureText("deepseek");
        float bdH = markH;
        text.setTextSize(markH * 0.44f);
        float bdW = text.measureText("HARNESS") + markH * 0.72f;

        final float total0 = markW + gap1 + dsW + gap2 + bdW;
        if (total0 > cw) {                              // 超宽 → 整体等比缩小
            final float k = cw / total0;
            markH *= k; markW *= k; gap1 *= k; gap2 *= k; dsW *= k; bdW *= k; bdH *= k;
        }
        final float dsSize = markH * 0.86f, bdSize = markH * 0.44f;

        final Bitmap whale = lightMode ? whaleBmpLight : whaleBmpDark;
        float x = LW / 2f - (markW + gap1 + dsW + gap2 + bdW) / 2f;
        final float cy = Y_LOCKUP;

        p.setStyle(Paint.Style.FILL);
        p.setAlpha((int) (255 * a));
        if (whale != null) {
            rect.set(x, cy - markH / 2f, x + markW, cy + markH / 2f);
            cv.drawBitmap(whale, null, rect, p);
        }
        p.setAlpha(255);
        x += markW + gap1;

        text.setAlpha((int) (255 * a));
        text.setTypeface(tfBold);
        text.setTextSize(dsSize);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(cINK);
        cv.drawText("deepseek", x, cy + dsSize * 0.35f, text);
        x += dsW + gap2;

        // HARNESS 反白徽章
        rect.set(x, cy - bdH / 2f, x + bdW, cy + bdH / 2f);
        p.setColor(cINK);
        cv.drawRoundRect(rect, bdH * 0.26f, bdH * 0.26f, p);
        text.setTextSize(bdSize);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(cBG);
        cv.drawText("HARNESS", x + bdW / 2f, cy + bdSize * 0.35f, text);
        text.setTextAlign(Paint.Align.LEFT);
        text.setAlpha(255);
    }

    /** 解码官方鲸鱼；浅色主题用反相矩阵得到黑色版本（形状不变）。 */
    private void loadWhale() {
        if (whaleBmpDark != null && whaleBmpLight != null) return;
        try {
            final Bitmap src = BitmapFactory.decodeResource(getResources(), R.drawable.ic_whale_mark);
            if (src == null) return;
            whaleBmpDark = src;
            final Bitmap inv = Bitmap.createBitmap(src.getWidth(), src.getHeight(),
                    Bitmap.Config.ARGB_8888);
            final Canvas c2 = new Canvas(inv);
            final Paint pp = new Paint(Paint.ANTI_ALIAS_FLAG);
            pp.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[]{
                    -1, 0, 0, 0, 255,
                    0, -1, 0, 0, 255,
                    0, 0, -1, 0, 255,
                    0, 0, 0, 1, 0,
            })));
            c2.drawBitmap(src, 0, 0, pp);
            whaleBmpLight = inv;
        } catch (Throwable t) {
            android.util.Log.w("DSH_SPLASH", "鲸鱼图标解码失败（不画徽标，不影响启动）", t);
        }
    }

    // ── 四维超立方体（16 顶点 / 32 棱，与预览版同一套几何）──
    private static final float[][] TV = buildV();
    private static final int[][] TE = buildE();

    private static float[][] buildV() {
        final float[][] v = new float[16][4];
        for (int i = 0; i < 16; i++) {
            v[i][0] = (i & 1) == 0 ? -1f : 1f;
            v[i][1] = ((i >> 1) & 1) == 0 ? -1f : 1f;
            v[i][2] = ((i >> 2) & 1) == 0 ? -1f : 1f;
            v[i][3] = ((i >> 3) & 1) == 0 ? -1f : 1f;
        }
        return v;
    }

    private static int[][] buildE() {
        final java.util.ArrayList<int[]> e = new java.util.ArrayList<>();
        for (int i = 0; i < 16; i++) {
            for (int j = i + 1; j < 16; j++) {
                int d = 0;
                for (int k = 0; k < 4; k++) if (TV[i][k] != TV[j][k]) d++;
                if (d == 1) e.add(new int[]{i, j});
            }
        }
        return e.toArray(new int[0][]);
    }

    /**
     * 画四维超立方体，并把 Tdsh 写在**体内**。
     * 图层顺序：下层细棱线 → 中层 Tdsh（黑体、平直笔画、无描边/阴影）→
     * 上层粗棱线再叠一遍（线条自然遮住笔画）→ 顶点方点。
     */
    private void drawTesseract(Canvas cv, float cx, float cy, float scale, float t) {
        if (scale <= 0.5f) return;
        final float a1 = t * 0.55f * (float) Math.PI;
        final float a2 = t * 0.55f * (float) Math.PI * 0.63f + 0.9f;
        final float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
        final float c2 = (float) Math.cos(a2), s2 = (float) Math.sin(a2);
        final float d4 = 4.6f, d3 = 7.2f;

        final float[] px = new float[16], py = new float[16], dep = new float[16];
        for (int i = 0; i < 16; i++) {
            final float x = TV[i][0], y = TV[i][1], z = TV[i][2], w = TV[i][3];
            float x1 = x * c1 - w * s1; final float w1 = x * s1 + w * c1;
            float y1 = y * c2 - z * s2; final float z1 = y * s2 + z * c2;
            final float f = d4 / (d4 + w1);
            x1 *= f; y1 *= f;
            final float zz = z1 * f;
            final float g = d3 / (d3 + zz);
            px[i] = cx + x1 * scale * g;
            py[i] = cy + y1 * scale * g;
            dep[i] = Math.abs(x1) + Math.abs(y1) + Math.abs(zz);
        }

        p.setStyle(Paint.Style.STROKE);
        p.setColor(cINK);
        p.setStrokeWidth(0.55f);
        for (int[] e : TE) cv.drawLine(px[e[0]], py[e[0]], px[e[1]], py[e[1]], p);

        text.setTypeface(tfBold);
        text.setTextSize(scale * 0.62f);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(cINK);
        cv.drawText("Tdsh", cx, cy + scale * 0.24f, text);
        text.setTextAlign(Paint.Align.LEFT);

        for (int[] e : TE) {
            p.setStrokeWidth((dep[e[0]] + dep[e[1]]) < 3.4f ? 1.35f : 0.85f);
            cv.drawLine(px[e[0]], py[e[0]], px[e[1]], py[e[1]], p);
        }

        p.setStyle(Paint.Style.FILL);
        p.setColor(cINK);
        for (int i = 0; i < 16; i++) {
            cv.drawRect(px[i] - 1.1f, py[i] - 1.1f, px[i] + 1.1f, py[i] + 1.1f, p);
        }
    }

    /** 刻度尺：等分均匀、三级线宽，随入场从左向右长出。 */
    private void drawRuler(Canvas cv, float a) {
        if (a <= 0.02f) return;
        final float x0 = M, span = (LW - M * 2) * a, y = Y_RULER;
        p.setStyle(Paint.Style.STROKE);
        p.setColor(cINK);
        p.setStrokeWidth(0.8f);
        cv.drawLine(x0, y, x0 + span, y, p);
        for (int i = 0; i <= 36; i++) {
            final float x = x0 + span * i / 36f;
            p.setStrokeWidth(0.4f);
            cv.drawLine(x, y, x, y + 3f, p);
        }
        for (int i = 0; i <= 9; i++) {
            final float x = x0 + span * i / 9f;
            p.setStrokeWidth(0.65f);
            cv.drawLine(x, y, x, y + 6f, p);
        }
        for (int i = 0; i <= 3; i++) {
            final float x = x0 + span * i / 3f;
            p.setStrokeWidth(1.2f);
            cv.drawLine(x, y - 4f, x, y + 10f, p);
        }
    }

    /** 底部读数：中英阶段 + 百分比 + 24 格进度 + 呼吸点 + 设置入口。跟真实进度。 */
    private void drawBottom(Canvas cv, float a) {
        if (a <= 0.02f) return;
        // ★ 进度条读的是**同一个** progressFraction，所以它和动画永远同步、且真的会动
        final float pv = progressFraction();

        final String[] cn = {"准备运行环境", "挂载容器", "连接运行时", "启动服务", "编译页面", "即将完成", "已就绪"};
        final String[] en = {"PREPARING", "MOUNTING", "LINKING", "STARTING", "COMPILING", "FINALIZING", "READY"};
        final float[] at = {0f, 0.14f, 0.34f, 0.58f, 0.82f, 0.94f, 0.995f};
        int si = 0;
        for (int i = 0; i < at.length; i++) if (pv >= at[i]) si = i;

        final int sa = text.getAlpha();
        text.setAlpha((int) (255 * a));
        text.setTypeface(tfBold);
        text.setTextSize(15f);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(cINK);
        cv.drawText(cn[si], M, Y_READ, text);
        text.setTypeface(tfMono);
        text.setTextSize(10f);
        text.setColor(cDIM);
        cv.drawText(en[si], M, Y_READ + 15f, text);

        text.setTextSize(15f);
        text.setTextAlign(Paint.Align.RIGHT);
        text.setColor(cINK);
        cv.drawText(String.format(java.util.Locale.ROOT, "%.1f%%", pv * 100f), LW - M, Y_READ, text);
        text.setTextAlign(Paint.Align.LEFT);

        // 24 格：已达成实心、未达成空心
        final float gap = 3f, gcw = (LW - M * 2 - gap * 23f) / 24f, lit = pv * 24f;
        for (int i = 0; i < 24; i++) {
            final float gx = M + i * (gcw + gap);
            rect.set(gx, Y_GRID, gx + gcw, Y_GRID + 6f);
            if (i < (int) lit) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(cINK);
                cv.drawRect(rect, p);
            } else {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(0.6f);
                p.setColor(cINK);
                cv.drawRect(rect, p);
            }
        }

        // 呼吸点：实心/空心互切（不改透明度 → 不产生灰阶糊边）。放在进度格下方靠左
        final float now = (SystemClock.uptimeMillis() % 1500L) / 1500f;
        p.setColor(cINK);
        for (int i = 0; i < 3; i++) {
            final float ph = (now - i * 0.18f + 1f) % 1f;
            final float dx = M + 4f + i * 9f, dy = Y_GRID + 17f;
            if (ph < 0.5f) {
                p.setStyle(Paint.Style.FILL);
                cv.drawCircle(dx, dy, 1.9f, p);
            } else {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(0.7f);
                cv.drawCircle(dx, dy, 1.9f, p);
            }
        }

        // TY 防伪标识（在括号底边以内）
        text.setTypeface(tfMono);
        text.setTextSize(22f);
        text.setColor(cINK);
        cv.drawText("TY", M, Y_TY, text);
        text.setTypeface(tfBold);
        text.setTextSize(10f);
        text.setColor(cDIM);
        cv.drawText("彤鱼制作 · AUTHOR", M + 28f, Y_TY - 1f, text);

        /*
         * 设置入口（方框 + 齿轮）**已按用户要求整块去掉**。
         *
         * 开屏上原来画的那个方框会压到进度格、也容易与"直角括号"混在一起，
         * 而设置本来就有正式入口（右上角「设置」/ DSH 设置页）。
         * 相关字段、热区、触摸分支、外部绑定接口一并删除 ——
         * 不留看不见的可点区域，也不留死代码。
         */

        text.setAlpha(sa);
    }
}
