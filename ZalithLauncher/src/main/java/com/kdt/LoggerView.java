package com.kdt;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.movtery.anim.animations.Animations;
import com.movtery.zalithlauncher.databinding.ViewLoggerBinding;
import com.movtery.zalithlauncher.setting.AllSettings;
import com.movtery.zalithlauncher.utils.anim.ViewAnimUtils;

import net.kdt.pojavlaunch.Logger;

/**
 * A class able to display logs to the user with color-coded severity levels.
 * Supports the Logger class and provides visual highlighting for errors,
 * warnings, crash hints, stack trace lines, and debug output.
 */
public class LoggerView extends ConstraintLayout {

    // ─── Log level colors ─────────────────────────────────────────────────────
    /** Bright red — ERROR lines and unhandled exceptions */
    private static final int COLOR_ERROR    = Color.parseColor("#FF5555");
    /** Softer red — stack trace "at " lines following an exception */
    private static final int COLOR_TRACE    = Color.parseColor("#FF8888");
    /** Orange — WARNING lines */
    private static final int COLOR_WARN     = Color.parseColor("#FFB347");
    /** Cyan — HINT lines injected by crash-pattern detection */
    private static final int COLOR_HINT     = Color.parseColor("#56D8E4");
    /** Light green — INFO lines */
    private static final int COLOR_INFO     = Color.parseColor("#69FF47");
    /** Grey — DEBUG lines */
    private static final int COLOR_DEBUG    = Color.parseColor("#AAAAAA");
    /** Darker grey — VERBOSE lines */
    private static final int COLOR_VERBOSE  = Color.parseColor("#777777");
    /** Bright white — separator / section header lines (═══) */
    private static final int COLOR_SECTION  = Color.parseColor("#FFFFFF");
    /** Default — no special color applied */
    private static final int COLOR_NONE     = 0;

    private Logger.eventLogListener mLogListener;
    private ViewLoggerBinding binding;
    private boolean isShowing = false;

    public LoggerView(@NonNull Context context) {
        this(context, null);
    }

    public LoggerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        // Sync the toggle button state when visibility is set externally
        binding.toggleLog.setChecked(visibility == VISIBLE);
    }

    public void toggleViewWithAnim() {
        setVisibilityWithAnim(!isShowing);
    }

    public void setVisibilityWithAnim(boolean visibility) {
        if (isShowing == visibility) return;
        isShowing = visibility;

        ViewAnimUtils.setViewAnim(this,
                visibility ? Animations.BounceInUp : Animations.SlideOutDown,
                (long) (AllSettings.getAnimationSpeed().getValue() * 0.7),
                () -> setVisibility(VISIBLE),
                () -> setVisibility(visibility ? VISIBLE : GONE));
    }

    /**
     * Force-show the log view. If the user taps close, {@code listener} is called.
     */
    public void forceShow(OnCloseClickListener listener) {
        setVisibilityWithAnim(true);
        binding.cancel.setOnClickListener(v -> listener.onClick());
    }

    // ─── Color logic ──────────────────────────────────────────────────────────

    /**
     * Determine the display color for a log line.
     * Checks are ordered from highest to lowest priority.
     */
    private int resolveLogColor(String text) {
        // Section separator lines written by writeCrashSummary (═══)
        if (text.contains("════") || text.contains("💥")) {
            return COLOR_SECTION;
        }
        // Crash hints injected by Logging.kt's pattern detector
        if (text.contains("(HINT)") || text.startsWith("🔴") || text.startsWith("🟡")) {
            return COLOR_HINT;
        }
        // ERROR-level lines
        if (text.contains("(ERROR)") || text.contains("FATAL") || text.contains("Fatal")) {
            return COLOR_ERROR;
        }
        // Exception class names (e.g. "java.lang.NullPointerException: ...")
        if (text.contains("Exception") || text.contains("Error:")) {
            return COLOR_ERROR;
        }
        // Stack trace "at " lines
        if (text.contains("\tat ") || (text.trim().startsWith("at ") && text.contains("("))) {
            return COLOR_TRACE;
        }
        // "Caused by:" lines in stack traces
        if (text.contains("Caused by:")) {
            return COLOR_TRACE;
        }
        // WARNING-level lines
        if (text.contains("(WARN)")) {
            return COLOR_WARN;
        }
        // INFO-level lines
        if (text.contains("(INFO)")) {
            return COLOR_INFO;
        }
        // DEBUG-level lines
        if (text.contains("(DEBUG)")) {
            return COLOR_DEBUG;
        }
        // VERBOSE-level lines
        if (text.contains("(VERBOSE)")) {
            return COLOR_VERBOSE;
        }
        return COLOR_NONE;
    }

    /**
     * Append a log line to the TextView, applying a color span if appropriate.
     */
    private void appendColoredLine(String text) {
        int color = resolveLogColor(text);
        if (color != COLOR_NONE) {
            SpannableString spannable = new SpannableString(text + '\n');
            spannable.setSpan(
                    new ForegroundColorSpan(color),
                    0, spannable.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            );
            binding.logView.append(spannable);
        } else {
            binding.logView.append(text + '\n');
        }
    }

    // ─── Initialisation ───────────────────────────────────────────────────────

    private void init() {
        binding = ViewLoggerBinding.inflate(LayoutInflater.from(getContext()), this, true);

        binding.logView.setTypeface(Typeface.MONOSPACE);
        //TODO clamp the max text so it doesn't go oob
        binding.logView.setMaxLines(Integer.MAX_VALUE);
        binding.logView.setEllipsize(null);
        binding.logView.setVisibility(GONE);

        // Toggle log visibility
        binding.toggleLog.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            binding.logView.setVisibility(isChecked ? VISIBLE : GONE);
            if (isChecked) {
                Logger.setLogListener(mLogListener);
            } else {
                binding.logView.setText("");
                // Avoids expensive JNI callbacks when the view is hidden
                Logger.setLogListener(null);
            }
        });
        binding.toggleLog.setChecked(false);

        // Close button
        binding.cancel.setOnClickListener(view -> setVisibilityWithAnim(false));

        // Auto-scroll
        binding.scroll.setKeepFocusing(true);
        binding.toggleAutoscroll.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            if (isChecked) binding.scroll.fullScroll(View.FOCUS_DOWN);
            binding.scroll.setKeepFocusing(isChecked);
        });
        binding.toggleAutoscroll.setChecked(true);

        // Log listener — color-codes each line as it arrives
        mLogListener = text -> {
            if (binding.logView.getVisibility() != VISIBLE) return;
            post(() -> {
                appendColoredLine(text);
                if (binding.scroll.isKeepFocusing()) {
                    binding.scroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        };
    }

    public ViewLoggerBinding getBinding() {
        return binding;
    }

    public interface OnCloseClickListener {
        void onClick();
    }
}
