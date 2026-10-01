package com.dshmobile.probe;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/**
 * 麦克风自检：直接在 App 进程里开一个 {@link AudioRecord}。
 *
 * <h3>它要回答的问题</h3>
 * 网页里 {@code getUserMedia({audio:true})} 目前报
 * {@code NotReadableError: Could not start audio source}，
 * 而 App 明明已经拿到 RECORD_AUDIO、也把权限 grant 给网页了。
 * 到底是"整个 App 都开不了麦克风"（厂商策略/权限问题），
 * 还是"只有 WebView 开不了"（Chromium 那一层的问题）？
 *
 * <p>这两种情况的修法完全不同，所以必须先把它们分开 ——
 * 这个探针就是干这个的：**同样的权限、同样的进程，走原生 API 试一次**。
 *
 * <p>注意日志量：它只输出结论，不打印原始采样。
 */
public final class MicProbe {

    private MicProbe() { }

    /** 跑一次完整的"开麦→录音→读数据→释放"，把每一步的结果写成文本。 */
    public static String run(Context c) {
        StringBuilder sb = new StringBuilder();
        boolean granted = c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        sb.append("RECORD_AUDIO 权限: ").append(granted ? "已授予" : "未授予").append('\n');
        if (!granted) {
            sb.append("→ 先去「手机控制 → 麦克风权限」授权，否则下面必然失败\n");
        }

        int[] rates = { 16000, 44100 };
        for (int rate : rates) {
            int min = AudioRecord.getMinBufferSize(rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            sb.append("采样率 ").append(rate).append("Hz: 最小缓冲=").append(min).append('\n');
            if (min <= 0) {
                sb.append("  → getMinBufferSize 失败，这个采样率不可用\n");
                continue;
            }
            AudioRecord rec = null;
            try {
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2);
                boolean inited = rec.getState() == AudioRecord.STATE_INITIALIZED;
                sb.append("  构造 AudioRecord: ").append(inited ? "初始化成功" : "初始化失败").append('\n');
                if (!inited) continue;
                rec.startRecording();
                Thread.sleep(250);
                short[] buf = new short[Math.max(1, min)];
                int n = rec.read(buf, 0, buf.length);
                sb.append("  读取: ").append(n).append(" 个采样")
                  .append(n > 0 ? "  ✓ 原生录音可用" : "  ✗ 读不到数据（可能被静音/占用）")
                  .append('\n');
            } catch (Throwable t) {
                // SecurityException 说明权限层面被拒；其它异常是设备/策略层面
                sb.append("  异常: ").append(t.getClass().getSimpleName())
                  .append(": ").append(t.getMessage()).append('\n');
            } finally {
                if (rec != null) {
                    try { rec.stop(); } catch (Throwable ignore) { }
                    try { rec.release(); } catch (Throwable ignore) { }
                }
            }
        }
        sb.append("\n判读方式：\n"
                + "  · 原生也失败 → 是**整个 App** 开不了麦（权限/厂商策略），跟 WebView 无关\n"
                + "  · 原生成功、网页失败 → 问题在 Chromium 那一层\n");
        return sb.toString();
    }
}
