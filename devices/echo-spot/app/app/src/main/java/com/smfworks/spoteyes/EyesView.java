package com.smfworks.spoteyes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Full-bleed official 135deg avatar gradient with black Grok-style eye pills
 * and black name lettering. Horizontal drag or fling slides to the next or
 * previous bot (wraps). A tap still starts or stops a realtime session.
 * Swiping is ignored while a session is open, so a paid session is not ended
 * by a swipe; a clean tap still ends it.
 *
 * Eye geometry and state animations are unchanged from 1.2.2: listening
 * widens the eyes and slightly brightens the face; talking pulses eye size;
 * thinking squints. No colored glow; a very subtle black contact shadow only.
 */
public final class EyesView extends View {
    public static final int IDLE = 0;
    public static final int LISTEN = 1;
    public static final int THINK = 2;
    public static final int TALK = 3;

    /** Live mark eye ink: s_ = [0, 0, 0]. Lettering matches. */
    private static final int EYE = 0xFF000000;

    private static final float TILT = -36f;
    private static final float LEFT_W = 0.150f;
    private static final float LEFT_H = 0.321f;
    private static final float RIGHT_W = 0.102f;
    private static final float RIGHT_H = 0.329f;
    private static final float GAP_X = 0.135f;
    private static final float GAP_Y = 0.065f;

    interface Listener {
        void onTap();
        void onBotChanged(Bot bot);
        boolean isSwipeLocked();
    }

    private final Paint facePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final int touchSlop;
    private final int minFling;

    private Listener listener;
    private int index;
    private float slide;
    private float slideTarget;
    private boolean settling;
    private int pendingIndex = -1;
    private boolean tracking;
    private boolean dragging;
    private float downX;
    private float downY;
    private VelocityTracker velocity;

    private volatile int mode = IDLE;
    private volatile float level;

    private float tilt = TILT;
    private float tall = 1f;
    private float wide = 1f;
    private float lift;
    private float lookX;
    private float lookY;
    private float targetLookX;
    private float targetLookY;
    private float shownLevel;
    private long lastFrame;
    private long nextBlinkAt;
    private long blinkStart = -1;
    private long nextGlanceAt;
    private float talkPhase;

    public EyesView(Context context) {
        super(context);
        facePaint.setStyle(Paint.Style.FILL);
        paint.setColor(EYE);
        paint.setStyle(Paint.Style.FILL);
        shadowPaint.setColor(0x28000000);
        shadowPaint.setStyle(Paint.Style.FILL);
        labelPaint.setColor(EYE);
        labelPaint.setStyle(Paint.Style.FILL);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        labelPaint.setAntiAlias(true);
        ViewConfiguration vc = ViewConfiguration.get(context);
        touchSlop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity();
        long now = SystemClock.uptimeMillis();
        nextBlinkAt = now + 1600;
        nextGlanceAt = now + 1200;
    }

    public EyesView(Context context, AttributeSet attrs) {
        this(context);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setIndex(int index) {
        this.index = wrap(index);
        slide = 0f;
        settling = false;
        pendingIndex = -1;
        postInvalidateOnAnimation();
    }

    int getIndex() {
        return index;
    }

    Bot current() {
        return Bot.at(index);
    }

    public void setMode(int mode) {
        this.mode = mode;
    }

    /** Mic energy, 0 to 1, used while listening. */
    public void setLevel(float level) {
        if (level < 0f) {
            level = 0f;
        } else if (level > 1f) {
            level = 1f;
        }
        this.level = level;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            tracking = true;
            dragging = false;
            downX = event.getX();
            downY = event.getY();
            if (velocity != null) {
                velocity.recycle();
            }
            velocity = VelocityTracker.obtain();
            velocity.addMovement(event);
            if (settling) {
                settling = false;
                pendingIndex = -1;
            }
            return true;
        }
        if (!tracking) {
            return false;
        }
        if (velocity != null) {
            velocity.addMovement(event);
        }
        if (action == MotionEvent.ACTION_MOVE) {
            float dx = event.getX() - downX;
            float dy = event.getY() - downY;
            boolean locked = listener != null && listener.isSwipeLocked();
            if (!dragging && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                if (locked) {
                    return true;
                }
                dragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            if (dragging && !locked) {
                int w = Math.max(1, getWidth());
                slide = clamp(dx, -w, w);
                postInvalidateOnAnimation();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            tracking = false;
            boolean locked = listener != null && listener.isSwipeLocked();
            if (dragging && !locked && action == MotionEvent.ACTION_UP) {
                finishSwipe(event);
            } else if (!dragging && action == MotionEvent.ACTION_UP && listener != null) {
                listener.onTap();
            } else if (dragging) {
                slideTarget = 0f;
                pendingIndex = -1;
                settling = true;
            }
            dragging = false;
            if (velocity != null) {
                velocity.recycle();
                velocity = null;
            }
            postInvalidateOnAnimation();
            return true;
        }
        return true;
    }

    private void finishSwipe(MotionEvent event) {
        int w = Math.max(1, getWidth());
        float vx = 0f;
        if (velocity != null) {
            velocity.computeCurrentVelocity(1000);
            vx = velocity.getXVelocity();
        }
        int dir = 0;
        if (slide <= -w * 0.28f || vx <= -minFling) {
            dir = 1; // finger moved left: next bot
        } else if (slide >= w * 0.28f || vx >= minFling) {
            dir = -1; // finger moved right: previous bot
        }
        if (dir != 0) {
            pendingIndex = wrap(index + dir);
            slideTarget = dir > 0 ? -w : w;
        } else {
            pendingIndex = -1;
            slideTarget = 0f;
        }
        settling = true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        if (lastFrame == 0) {
            lastFrame = now;
        }
        float dt = Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        step(now, dt);
        stepSlide(dt);

        int w = getWidth();
        int h = getHeight();
        if (w < 2 || h < 2) {
            canvas.drawColor(current().lightTo);
            postInvalidateOnAnimation();
            return;
        }

        int neighbor = neighborIndex();
        canvas.save();
        canvas.translate(slide, 0f);
        drawFace(canvas, w, h, current(), true);
        canvas.restore();
        if (neighbor != index && Math.abs(slide) > 0.5f) {
            float nx = slide < 0f ? slide + w : slide - w;
            canvas.save();
            canvas.translate(nx, 0f);
            drawFace(canvas, w, h, Bot.at(neighbor), false);
            canvas.restore();
        }
        postInvalidateOnAnimation();
    }

    private int neighborIndex() {
        if (pendingIndex >= 0) {
            return pendingIndex;
        }
        if (slide < 0f) {
            return wrap(index + 1);
        }
        if (slide > 0f) {
            return wrap(index - 1);
        }
        return index;
    }

    private void stepSlide(float dt) {
        if (!settling) {
            return;
        }
        float prev = slide;
        slide = damp(slide, slideTarget, dt, 16f);
        if (Math.abs(slide - slideTarget) < 2f || (slide - slideTarget) * (prev - slideTarget) <= 0f
                && Math.abs(slide - slideTarget) < 8f) {
            slide = 0f;
            settling = false;
            if (pendingIndex >= 0 && pendingIndex != index) {
                index = pendingIndex;
                if (listener != null) {
                    listener.onBotChanged(current());
                }
            }
            pendingIndex = -1;
        }
    }

    private void drawFace(Canvas canvas, int w, int h, Bot bot, boolean animate) {
        int from = bot.lightFrom;
        int to = bot.lightTo;
        if (animate && mode == LISTEN) {
            float u = 0.40f + 0.40f * shownLevel;
            to = lerpColor(bot.lightTo, bot.listenFrom, u);
        }
        facePaint.setShader(new LinearGradient(
                0f, 0f, w, h,
                from, to, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, facePaint);

        float s = Math.min(w, h);
        float cx = w * 0.5f;
        float cy = h * 0.5f + (animate ? lift : 0f);
        float lid = animate ? lid(SystemClock.uptimeMillis()) : 0f;
        float sy = (animate ? tall : 1f) * (1f - 0.90f * lid);
        float sx = animate ? wide : 1f;
        float lx = animate ? lookX : 0f;
        float ly = animate ? lookY : 0f;
        float deg = animate ? tilt : TILT;
        eye(canvas, cx - s * GAP_X + lx, cy + s * GAP_Y + ly, s * LEFT_W, s * LEFT_H, deg, sx, sy);
        eye(canvas, cx + s * GAP_X + lx, cy - s * GAP_Y + ly, s * RIGHT_W, s * RIGHT_H, deg, sx, sy);
        float size = Math.max(18f, s * 0.055f);
        labelPaint.setTextSize(size);
        float baseline = h - Math.max(16f, s * 0.06f);
        canvas.drawText(bot.name, w * 0.5f, baseline, labelPaint);
    }

    private void step(long now, float dt) {
        int face = mode;
        float wantTilt = TILT;
        float wantTall = 1f;
        float wantWide = 1f;
        float wantLift = 0f;
        shownLevel = damp(shownLevel, face == LISTEN ? level : 0f, dt, 10f);
        if (face == LISTEN) {
            wantTilt = -16f;
            wantTall = 1.16f + 0.10f * shownLevel;
            wantWide = 1.08f + 0.06f * shownLevel;
            wantLift = -0.035f * Math.min(getWidth(), getHeight());
            targetLookX = 0f;
            targetLookY = -0.02f * Math.min(getWidth(), getHeight());
        } else if (face == THINK) {
            wantTilt = -48f;
            wantTall = 0.42f;
            wantWide = 1.18f;
            wantLift = -0.055f * Math.min(getWidth(), getHeight());
            if (now > nextGlanceAt) {
                targetLookX = -0.03f * Math.min(getWidth(), getHeight());
                targetLookY = -0.04f * Math.min(getWidth(), getHeight());
                nextGlanceAt = now + 1600;
            }
        } else if (face == TALK) {
            talkPhase += dt;
            float syllable = 0.5f + 0.5f * (float) Math.sin(talkPhase * 16.0);
            float breath = 0.5f + 0.5f * (float) Math.sin(talkPhase * 6.2);
            wantTilt = -30f + 3f * (float) Math.sin(talkPhase * 8.0);
            wantTall = 0.70f + 0.22f * syllable * (0.65f + 0.35f * breath);
            wantWide = 1.22f - 0.16f * (wantTall - 0.70f);
            wantLift = (float) Math.sin(talkPhase * 11.0) * 0.012f * Math.min(getWidth(), getHeight());
            targetLookX = 0f;
            targetLookY = 0f;
        } else {
            talkPhase = 0f;
            if (now > nextGlanceAt) {
                double spin = now * 0.0017;
                targetLookX = (float) Math.cos(spin) * 0.018f * Math.min(getWidth(), getHeight());
                targetLookY = (float) Math.sin(spin * 0.7) * 0.014f * Math.min(getWidth(), getHeight());
                nextGlanceAt = now + 2600;
            }
        }
        tilt = damp(tilt, wantTilt, dt, 7f);
        tall = damp(tall, wantTall, dt, 8f);
        wide = damp(wide, wantWide, dt, 8f);
        lift = damp(lift, wantLift, dt, 6f);
        lookX = damp(lookX, targetLookX, dt, 3.2f);
        lookY = damp(lookY, targetLookY, dt, 3.2f);
        if (blinkStart < 0 && now > nextBlinkAt && face != TALK) {
            blinkStart = now;
        }
    }

    private float lid(long now) {
        if (blinkStart < 0) {
            return 0f;
        }
        float u = (now - blinkStart) / 140f;
        float hold = mode == THINK ? 1.85f : 1.35f;
        float open = mode == THINK ? 1.35f : 1.15f;
        float closed;
        if (u < 1f) {
            closed = u;
        } else if (u < hold) {
            closed = 1f;
        } else if (u < hold + open) {
            closed = 1f - (u - hold) / open;
        } else {
            blinkStart = -1;
            long gap = mode == LISTEN ? 5200 : mode == THINK ? 1600 : 2800;
            nextBlinkAt = now + gap + (now % 1800);
            return 0f;
        }
        if (closed < 0f) {
            return 0f;
        }
        return closed > 1f ? 1f : closed;
    }

    private void eye(Canvas canvas, float x, float y, float ew, float eh, float deg, float sx, float sy) {
        canvas.save();
        canvas.translate(x, y);
        canvas.rotate(deg);
        canvas.scale(sx, sy);
        float rad = ew * 0.5f;
        rect.set(-rad, -eh * 0.5f, rad, eh * 0.5f);
        float ox = ew * 0.04f;
        float oy = eh * 0.05f;
        rect.offset(ox, oy);
        canvas.drawRoundRect(rect, rad, rad, shadowPaint);
        rect.offset(-ox, -oy);
        canvas.drawRoundRect(rect, rad, rad, paint);
        canvas.restore();
    }

    private static int wrap(int i) {
        int n = Bot.count();
        int r = i % n;
        return r < 0 ? r + n : r;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float damp(float cur, float target, float dt, float speed) {
        float k = 1f - (float) Math.exp(-speed * dt);
        return cur + (target - cur) * k;
    }

    private static int lerpColor(int a, int b, float t) {
        if (t < 0f) {
            t = 0f;
        } else if (t > 1f) {
            t = 1f;
        }
        int ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a);
        int br = Color.red(b), bg = Color.green(b), bb = Color.blue(b);
        return Color.rgb(
                Math.round(ar + (br - ar) * t),
                Math.round(ag + (bg - ag) * t),
                Math.round(ab + (bb - ab) * t));
    }
}
