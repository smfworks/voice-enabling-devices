package com.smfworks.jamesvoice;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Bars under the portrait. They stay nearly still until Sarah is speaking,
 * then they follow the playback waveform.
 */
public class VoiceBars extends View {
    static final int IDLE = 0;
    static final int LISTENING = 1;
    static final int SPEAKING = 2;

    private static final int BARS = 28;
    private final float[] levels = new float[BARS];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int mode = IDLE;
    private float phase;
    private boolean ticking;
    private boolean synthetic;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!ticking) return;
            phase += 0.22f;
            if (mode != SPEAKING || synthetic) {
                float base = mode == SPEAKING ? 0.28f : mode == LISTENING ? 0.16f : 0.05f;
                float wobble = mode == SPEAKING ? 0.55f : mode == LISTENING ? 0.1f : 0.02f;
                for (int i = 0; i < BARS; i++) {
                    float wave = (float) Math.sin(phase + i * 0.55f);
                    float wave2 = (float) Math.sin(phase * 1.7f + i * 0.2f);
                    levels[i] = Math.max(0.04f, Math.min(1f, base + wobble * (0.5f * wave + 0.5f * wave2)));
                }
            }
            invalidate();
            postDelayed(this, 48);
        }
    };

    public VoiceBars(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(0xFFF7FBFC);
        for (int i = 0; i < BARS; i++) levels[i] = 0.05f;
    }

    public void setMode(int next) {
        mode = next;
        if (next != SPEAKING) synthetic = false;
        if (!ticking) {
            ticking = true;
            post(tick);
        }
        invalidate();
    }

    public void setWaveform(byte[] wave) {
        if (wave == null || wave.length == 0 || mode != SPEAKING) return;
        int n = wave.length;
        float peak = 0f;
        for (int i = 0; i < BARS; i++) {
            int start = i * n / BARS;
            int end = Math.max(start + 1, (i + 1) * n / BARS);
            int acc = 0;
            for (int j = start; j < end; j++) {
                acc += Math.abs((wave[j] & 0xff) - 128);
            }
            float v = (acc / (float) (end - start)) / 128f;
            peak = Math.max(peak, v);
            levels[i] = Math.max(0.05f, Math.min(1f, v * 2.2f));
        }
        synthetic = peak < 0.03f;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ticking = true;
        post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        ticking = false;
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float gap = w * 0.012f;
        float barW = (w - gap * (BARS + 1)) / BARS;
        float radius = barW / 2f;
        for (int i = 0; i < BARS; i++) {
            float bh = Math.max(radius * 2f, levels[i] * h * 0.92f);
            float left = gap + i * (barW + gap);
            float top = (h - bh) / 2f;
            rect.set(left, top, left + barW, top + bh);
            paint.setAlpha(mode == SPEAKING ? 255 : 150);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
    }
}
