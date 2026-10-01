package com.dshmobile.probe;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 截屏能力：给 agent 一双"眼睛"。
 *
 * <h3>为什么必须有它</h3>
 * 微信、QQ 这类应用会**屏蔽控件树** —— 无障碍和 {@code uiautomator} 读到的都是空树
 * （实测微信只有 407 字节、`bounds=[0,0][0,0]` 的根节点）。但它们**没有设置
 * {@code FLAG_SECURE}**，屏幕像素是完整可截的。
 *
 * <p>换句话说：它们只挡了"读控件"这条路，没挡"看像素"这条路。
 * 配上支持图片输入的模型（本 App 用的 DeepSeek V4.1 Flash 就支持），
 * 就等于给 agent 装上了眼睛。
 *
 * <h3>两条后端，自动选用</h3>
 * <table>
 *   <tr><th></th><th>无障碍 takeScreenshot</th><th>录屏 MediaProjection</th></tr>
 *   <tr><td>额外授权</td><td>不需要（无障碍开着就行）</td><td>要弹一次"录屏"确认</td></tr>
 *   <tr><td>无障碍被关掉时</td><td>失效</td><td><b>照样能用</b></td></tr>
 * </table>
 * 优先用无障碍（零成本），它不可用时自动回落到录屏。
 */
public final class Screenshot {

    private static final String TAG = "DSH_SHOT";

    /** 截图落盘目录。容器把 /sdcard 挂进来了，所以 agent 读得到。 */
    public static final String DIR = "/sdcard/.dsh-phone";
    public static final String LAST = DIR + "/last.png";

    /** 截屏请求码，供 Activity 转发 onActivityResult。 */
    public static final int REQ_PROJECTION = 0x4454;

    private Screenshot() { }

    // ── 状态 ────────────────────────────────────────────────

    private static volatile MediaProjection sProjection;
    private static volatile VirtualDisplay sVirtualDisplay;
    private static volatile ImageReader sReader;
    private static volatile int sWidth, sHeight, sDensity;
    private static volatile String sLastError;

    public static String lastError() { return sLastError; }

    /** 无障碍那条路能不能用（服务在跑 + 系统 API ≥ 30 + 配置里开了 capability）。 */
    public static boolean a11yAvailable() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && DshAccessibilityService.isRunning();
    }

    /** 录屏会话是否还活着。 */
    public static boolean projectionAlive() {
        return sProjection != null;
    }

    /** 界面用的一句话状态（纯文字，和原生设置观感一致）。 */
    public static String describe(Context c) {
        StringBuilder sb = new StringBuilder();
        if (a11yAvailable()) {
            sb.append("截屏可用：无障碍方式（不需要额外授权）");
        } else if (projectionAlive()) {
            sb.append("截屏可用：录屏方式（无障碍关闭时也能用）");
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            sb.append("截屏不可用：本机 Android 版本低于 11，只能走录屏");
        } else {
            sb.append("截屏不可用：无障碍没开，且没有录屏授权");
        }
        if (sLastError != null) sb.append('\n').append("最近一次问题：").append(sLastError);
        return sb.toString();
    }

    // ── 录屏授权 ────────────────────────────────────────────

    /** 弹出"录屏"确认框用的 Intent。 */
    public static Intent consentIntent(Context c) {
        try {
            MediaProjectionManager mgr = (MediaProjectionManager)
                    c.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            return mgr == null ? null : mgr.createScreenCaptureIntent();
        } catch (Throwable t) {
            Log.w(TAG, "构造录屏授权 Intent 失败", t);
            return null;
        }
    }

    /** Activity 的 onActivityResult 转发到这里。 */
    public static boolean onConsentResult(Context c, int resultCode, Intent data) {
        try {
            MediaProjectionManager mgr = (MediaProjectionManager)
                    c.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mgr == null) return false;
            /*
             * Android 14 起要求先注册回调再建虚拟显示，否则 createVirtualDisplay 直接抛。
             * 我们注册一个空回调只是为满足这个要求（本功能不需要处理停止事件，
             * 会话断了下次自动重新申请）。
             */
            MediaProjection p = mgr.getMediaProjection(resultCode, data);
            if (p == null) return false;
            p.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    Log.i(TAG, "录屏会话被系统结束");
                    releaseProjection();
                }
            }, new Handler(Looper.getMainLooper()));
            sProjection = p;
            buildVirtualDisplay(c, p);
            return true;
        } catch (Throwable t) {
            sLastError = "建立录屏会话失败: " + t;
            Log.w(TAG, sLastError, t);
            releaseProjection();
            return false;
        }
    }

    private static void buildVirtualDisplay(Context c, MediaProjection p) {
        DisplayMetrics dm = realMetrics(c);
        sWidth = dm.widthPixels;
        sHeight = dm.heightPixels;
        sDensity = dm.densityDpi;
        if (sWidth <= 0 || sHeight <= 0) { sWidth = 1080; sHeight = 1920; sDensity = 480; }

        sReader = ImageReader.newInstance(sWidth, sHeight, PixelFormat.RGBA_8888, 2);
        sVirtualDisplay = p.createVirtualDisplay("dsh-shot",
                sWidth, sHeight, sDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                sReader.getSurface(), null, null);
    }

    private static void releaseProjection() {
        try { if (sVirtualDisplay != null) sVirtualDisplay.release(); } catch (Throwable ignore) { }
        try { if (sReader != null) sReader.close(); } catch (Throwable ignore) { }
        try { if (sProjection != null) sProjection.stop(); } catch (Throwable ignore) { }
        sVirtualDisplay = null;
        sReader = null;
        sProjection = null;
    }

    private static DisplayMetrics realMetrics(Context c) {
        DisplayMetrics dm = new DisplayMetrics();
        try {
            WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Display d = wm.getDefaultDisplay();
                    d.getRealMetrics(dm);
                } else {
                    wm.getDefaultDisplay().getRealMetrics(dm);
                }
            }
        } catch (Throwable ignore) { }
        if (dm.widthPixels == 0) dm.setTo(Resources_getSystem());
        return dm;
    }

    @SuppressWarnings("deprecation")
    private static DisplayMetrics Resources_getSystem() {
        return android.content.res.Resources.getSystem().getDisplayMetrics();
    }

    // ── 取一帧 ──────────────────────────────────────────────

    /** 截图结果。成功时 {@link #bitmap} 非空。 */
    public static final class Shot {
        public Bitmap bitmap;
        public String backend;
        public String error;
    }

    /**
     * 截一张图。**必须在后台线程调用**（内部会等回调，最长约 3 秒）。
     */
    public static Shot capture(Context c) {
        Shot s = new Shot();

        if (a11yAvailable()) {
            Bitmap b = grabViaAccessibility();
            if (b != null) {
                s.bitmap = b;
                s.backend = "无障碍";
                sLastError = null;
                return s;
            }
            // 无障碍那条失败（常见于被 FLAG_SECURE 挡住），继续试录屏
        }

        if (projectionAlive()) {
            Bitmap b = grabViaProjection();
            if (b != null) {
                s.bitmap = b;
                s.backend = "录屏";
                sLastError = null;
                return s;
            }
        }

        s.error = a11yAvailable()
                ? "无障碍截屏失败，且没有可用的录屏会话。若目标应用设置了 FLAG_SECURE（如某些银行/支付页面），两条路都会被挡。"
                : "无障碍没开，且没有录屏授权。请在「手机控制」里点「开启录屏截屏」授权一次。";
        sLastError = s.error;
        return s;
    }

    /** 无障碍截屏（API 30+，不需要额外授权）。 */
    private static Bitmap grabViaAccessibility() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null;
        AccessibilityService svc = DshAccessibilityService.instance();
        if (svc == null) return null;

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Bitmap> out = new AtomicReference<>();
        final AtomicReference<String> err = new AtomicReference<>();

        Executor exec = command -> new Thread(command, "dsh-a11y-shot").start();
        try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, exec,
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult result) {
                            try {
                                HardwareBuffer buf = result.getHardwareBuffer();
                                Bitmap hw = Bitmap.wrapHardwareBuffer(buf, result.getColorSpace());
                                if (hw != null) {
                                    // 必须 copy 成软件位图再关 buffer，否则像素会跟着失效
                                    out.set(hw.copy(Bitmap.Config.ARGB_8888, false));
                                    hw.recycle();
                                }
                                buf.close();
                            } catch (Throwable t) {
                                err.set(String.valueOf(t));
                            } finally {
                                latch.countDown();
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            err.set("无障碍截屏失败，错误码 " + errorCode
                                    + (errorCode == 2 ? "（多半是被 FLAG_SECURE 挡了）" : ""));
                            latch.countDown();
                        }
                    });
            latch.await(3, TimeUnit.SECONDS);
        } catch (Throwable t) {
            err.set(String.valueOf(t));
        }
        if (out.get() == null && err.get() != null) sLastError = err.get();
        return out.get();
    }

    /** 录屏截屏：从虚拟显示那一帧里取图。 */
    private static Bitmap grabViaProjection() {
        ImageReader reader = sReader;
        if (reader == null) return null;
        Image img = null;
        try {
            // 刚授权完可能还没出帧，给它一点时间
            for (int i = 0; i < 10 && img == null; i++) {
                img = reader.acquireLatestImage();
                if (img == null) Thread.sleep(120);
            }
            if (img == null) return null;
            Image.Plane[] planes = img.getPlanes();
            if (planes.length == 0) return null;
            java.nio.ByteBuffer buf = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * sWidth;
            Bitmap bmp = Bitmap.createBitmap(sWidth + rowPadding / pixelStride, sHeight,
                    Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buf);
            if (rowPadding == 0) return bmp;
            // 行末有填充时裁掉右侧多余部分
            Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, sWidth, sHeight);
            bmp.recycle();
            return cropped;
        } catch (Throwable t) {
            sLastError = "录屏取帧失败: " + t;
            return null;
        } finally {
            if (img != null) img.close();
        }
    }

    // ── 落盘 ────────────────────────────────────────────────

    /**
     * 把截图写到 {@link #DIR}，返回文件。
     *
     * <p>写到 /sdcard 而不是应用私有目录：容器只把 /sdcard 挂进来了，
     * 放私有目录里 agent 根本看不见。
     */
    public static File save(Bitmap bmp, String name) throws Exception {
        File dir = new File(DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("建不了目录: " + DIR);
        }
        File f = new File(dir, name);
        try (FileOutputStream os = new FileOutputStream(f)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
        }
        return f;
    }

    /** 顺手把不带状态栏/导航栏的整屏再缩一份，省 token 用。 */
    public static Bitmap downscale(Bitmap src, int maxWidth) {
        if (src == null || src.getWidth() <= maxWidth) return src;
        int h = (int) (src.getHeight() * (maxWidth / (float) src.getWidth()));
        Bitmap out = Bitmap.createScaledBitmap(src, maxWidth, h, true);
        return out;
    }

    /** 只是为了让 Canvas 导入有意义（有些 ROM 上硬件位图转软件位图会用到）。 */
    @SuppressWarnings("unused")
    private static Bitmap toSoftware(Bitmap src) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(src, 0, 0, null);
        return out;
    }
}
