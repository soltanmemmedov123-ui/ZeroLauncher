package com.movtery.zalithlauncher.ui.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * ZLToggle – a custom 36×20dp toggle switch styled for Zero Launcher.
 * ON  = green (#4ADE80) track with white thumb
 * OFF = semi-white track with white thumb
 */
public class ZLToggle extends View {

    // Colors
    private static final int COLOR_TRACK_ON      = 0xFF4ADE80;
    private static final int COLOR_TRACK_ON_ALPHA = 0x664ADE80;
    private static final int COLOR_TRACK_OFF     = 0x1FFFFFFF;
    private static final int COLOR_THUMB         = 0xFFFFFFFF;
    private static final int COLOR_BORDER_ON     = 0x994ADE80;
    private static final int COLOR_BORDER_OFF    = 0x1AFFFFFF;

    // Shared interpolator – one instance for all ZLToggle instances in the process
    private static final DecelerateInterpolator TOGGLE_INTERPOLATOR = new DecelerateInterpolator();

    private final Paint trackPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect   = new RectF();

    // Precomputed px values – computed once in init(), reused in every onDraw() call
    private float thumbRadiusPx;
    private float marginPx;

    private boolean checked = false;
    private float thumbPosition = 0f; // 0.0 = off, 1.0 = on
    private ValueAnimator thumbAnimator;

    private OnCheckedChangeListener listener;

    public interface OnCheckedChangeListener {
        void onCheckedChanged(ZLToggle toggle, boolean isChecked);
    }

    public ZLToggle(Context context) {
        super(context);
        init(context, null);
    }

    public ZLToggle(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public ZLToggle(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(Context context, AttributeSet attrs) {
        // Read checked attribute from XML (android:checked)
        if (attrs != null) {
            int[] attrsArray = new int[]{ android.R.attr.checked };
            TypedArray ta = context.obtainStyledAttributes(attrs, attrsArray);
            checked = ta.getBoolean(0, false);
            ta.recycle();
        }
        thumbPosition = checked ? 1f : 0f;

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dpToPx(1f));

        // Precompute constant px values – used in every onDraw() call
        thumbRadiusPx = dpToPx(7f);   // 14dp diameter thumb
        marginPx      = dpToPx(2f);   // 2dp margin from edges

        setClickable(true);
        setFocusable(true);

        setOnClickListener(v -> toggle());
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();
        float radius      = h / 2f;
        float thumbRadius = thumbRadiusPx;   // precomputed in init()
        float margin      = marginPx;        // precomputed in init()

        // Interpolate track color
        int trackColor  = interpolateColor(COLOR_TRACK_OFF, COLOR_TRACK_ON, thumbPosition);
        int borderColor = interpolateColor(COLOR_BORDER_OFF, COLOR_BORDER_ON, thumbPosition);

        // Track
        trackRect.set(0, 0, w, h);
        trackPaint.setColor(trackColor);
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint);

        // Border
        borderPaint.setColor(borderColor);
        canvas.drawRoundRect(trackRect, radius, radius, borderPaint);

        // Thumb position: left edge + margin + travel * thumbPosition
        float travel = w - margin * 2 - thumbRadius * 2;
        float thumbX = margin + thumbRadius + travel * thumbPosition;
        float thumbY = h / 2f;

        // Thumb shadow
        thumbPaint.setColor(0x33000000);
        canvas.drawCircle(thumbX, thumbY + dpToPx(0.5f), thumbRadius + dpToPx(0.5f), thumbPaint);

        // Thumb
        thumbPaint.setColor(COLOR_THUMB);
        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbPaint);
    }

    private int interpolateColor(int from, int to, float fraction) {
        int aF = (from >> 24) & 0xFF, rF = (from >> 16) & 0xFF, gF = (from >> 8) & 0xFF, bF = from & 0xFF;
        int aT = (to >> 24) & 0xFF, rT = (to >> 16) & 0xFF, gT = (to >> 8) & 0xFF, bT = to & 0xFF;
        int a = (int)(aF + (aT - aF) * fraction);
        int r = (int)(rF + (rT - rF) * fraction);
        int g = (int)(gF + (gT - gF) * fraction);
        int b = (int)(bF + (bT - bF) * fraction);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public void toggle() {
        setChecked(!checked);
    }

    public void setChecked(boolean checked) {
        if (this.checked == checked) return;
        this.checked = checked;
        animateThumb(checked ? 1f : 0f);
        if (listener != null) listener.onCheckedChanged(this, checked);
    }

    public boolean isChecked() {
        return checked;
    }

    private void animateThumb(float target) {
        if (thumbAnimator != null) thumbAnimator.cancel();
        thumbAnimator = ValueAnimator.ofFloat(thumbPosition, target);
        thumbAnimator.setDuration(180);
        thumbAnimator.setInterpolator(TOGGLE_INTERPOLATOR); // cached – no allocation per tap
        thumbAnimator.addUpdateListener(anim -> {
            thumbPosition = (float) anim.getAnimatedValue();
            invalidate();
        });
        thumbAnimator.start();
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Default: 36dp × 20dp
        int defaultW = (int) dpToPx(36);
        int defaultH = (int) dpToPx(20);
        int w = resolveSize(defaultW, widthMeasureSpec);
        int h = resolveSize(defaultH, heightMeasureSpec);
        setMeasuredDimension(w, h);
    }
}
