package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Animated audio equalizer bars for the Now Playing pill.
 * Features 4 animated rounded vertical bars that dynamically oscillate during playback.
 */
public class EqualizerView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float[] heights = new float[]{0.3f, 0.5f, 0.25f, 0.4f};
    private ValueAnimator animator;
    private boolean isPlaying = false;
    private int accentColor = 0xFF1ED760; // Vibrant music green (Spotify accent)
    private float density = 1f;

    public EqualizerView(Context context) {
        super(context);
        init();
    }

    public EqualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public EqualizerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(accentColor);
    }

    public void setAccentColor(int color) {
        this.accentColor = color;
        paint.setColor(color);
        invalidate();
    }

    public void setPlaying(boolean playing) {
        if (this.isPlaying == playing && animator != null && animator.isRunning() == playing) return;
        this.isPlaying = playing;
        if (playing) {
            startAnimation();
        } else {
            stopAnimation();
        }
    }

    private void startAnimation() {
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
        animator.setDuration(1200);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            heights[0] = 0.25f + 0.60f * (float) (Math.sin(t * 3.7) * 0.5 + 0.5);
            heights[1] = 0.30f + 0.65f * (float) (Math.sin(t * 5.4 + 1.2) * 0.5 + 0.5);
            heights[2] = 0.20f + 0.75f * (float) (Math.sin(t * 4.2 + 2.5) * 0.5 + 0.5);
            heights[3] = 0.35f + 0.55f * (float) (Math.sin(t * 6.5 + 0.7) * 0.5 + 0.5);
            invalidate();
        });
        animator.start();
    }

    private void stopAnimation() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        heights[0] = 0.20f;
        heights[1] = 0.25f;
        heights[2] = 0.20f;
        heights[3] = 0.25f;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isPlaying) startAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float barWidth = 2.4f * density;
        float gap = 2.0f * density;
        int numBars = heights.length;
        float totalBarsWidth = numBars * barWidth + (numBars - 1) * gap;
        float startX = (w - totalBarsWidth) / 2f;
        float bottomY = h - (1f * density);
        float usableHeight = h - (2f * density);

        for (int i = 0; i < numBars; i++) {
            float x = startX + i * (barWidth + gap);
            float barH = Math.max(barWidth, usableHeight * heights[i]);
            float topY = bottomY - barH;
            rect.set(x, topY, x + barWidth, bottomY);
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint);
        }
    }
}
