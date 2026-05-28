package com.movtery.zalithlauncher.feature.benchmark

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.setting.Settings
import com.movtery.zalithlauncher.task.TaskExecutors
import com.movtery.zalithlauncher.ui.dialog.FullScreenDialog
import com.movtery.zalithlauncher.ui.dialog.TipDialog

/**
 * Full-screen dialog that presents a [BenchmarkResult] with score gauges, tier badge,
 * device info, and a list of one-tap recommendations.
 */
class BenchmarkResultsDialog(
    context: Context,
    private val result: BenchmarkResult
) : FullScreenDialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = buildLayout()
        setContentView(root)

        // Auto-apply all actionable recommendations if the setting is enabled
        if (AllSettings.benchmarkAutoApply.getValue()) {
            autoApplyAll()
        }
    }

    private fun autoApplyAll() {
        result.recommendations
            .filter { it.settingKey != null && it.settingValue != null }
            .forEach { rec ->
                runCatching {
                    com.movtery.zalithlauncher.setting.Settings.Manager
                        .put(rec.settingKey!!, rec.settingValue!!).save()
                    Logging.i("Benchmark", "Auto-applied: ${rec.settingKey}=${rec.settingValue}")
                }.onFailure { e ->
                    Logging.e("Benchmark", "Auto-apply failed for ${rec.settingKey}", e)
                }
            }
        android.widget.Toast.makeText(
            context,
            "Benchmark settings auto-applied (${result.recommendations.count { it.settingKey != null }} changes)",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    // -------------------------------------------------------------------------
    // Layout construction (programmatic to avoid requiring new XML resources)
    // -------------------------------------------------------------------------

    private fun buildLayout(): View {
        val ctx = context
        val scroll = android.widget.ScrollView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val container = LinearLayout(ctx).apply {
            orientation  = LinearLayout.VERTICAL
            val pad      = dp(16)
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(container)

        // ── Header ────────────────────────────────────────────────────────
        container.addView(makeTextView(
            text  = "Benchmark Results",
            size  = 20f,
            bold  = true,
            color = colorAttr(android.R.attr.textColorPrimary)
        ))

        container.addView(makeTextView(
            text  = result.deviceModel,
            size  = 13f,
            color = colorAttr(android.R.attr.textColorSecondary),
            topMarginDp = 2
        ))

        // ── Tier badge ────────────────────────────────────────────────────
        val tierColor = when (result.tier) {
            BenchmarkTier.HIGH     -> 0xFF4CAF50.toInt()
            BenchmarkTier.MEDIUM   -> 0xFF2196F3.toInt()
            BenchmarkTier.LOW      -> 0xFFFF9800.toInt()
            BenchmarkTier.VERY_LOW -> 0xFFF44336.toInt()
        }
        container.addView(makeTextView(
            text        = "  ${result.tier.label}  ·  Overall: ${result.overallScore} / 1000  ",
            size        = 15f,
            bold        = true,
            color       = 0xFFFFFFFF.toInt(),
            topMarginDp = 10,
            bgColor     = tierColor,
            paddingDp   = 6
        ))

        // ── Score bars ────────────────────────────────────────────────────
        container.addView(makeSectionHeader("Score Breakdown"))

        addScoreBar(container, "CPU Single-Core",  result.cpuSingleScore)
        addScoreBar(container, "CPU Multi-Core",   result.cpuMultiScore)
        addScoreBar(container, "Memory Bandwidth", result.memoryScore)
        addScoreBar(container, "Storage I/O",      result.storageScore)

        // ── Device info ───────────────────────────────────────────────────
        container.addView(makeSectionHeader("Device Info"))
        container.addView(makeTextView(
            text  = "CPU cores: ${result.coreCount}   " +
                "RAM: ${result.totalMemoryMb} MB total, ${result.freeMemoryMb} MB free",
            size  = 13f,
            color = colorAttr(android.R.attr.textColorSecondary)
        ))

        // ── Recommendations ───────────────────────────────────────────────
        if (result.recommendations.isNotEmpty()) {
            container.addView(makeSectionHeader("Recommendations"))
            result.recommendations.forEach { rec ->
                container.addView(buildRecommendationCard(rec))
            }
        }

        // ── Close button ──────────────────────────────────────────────────
        container.addView(makeButton("Close") { dismiss() })

        return scroll
    }

    // ── Score bar row ────────────────────────────────────────────────────

    private fun addScoreBar(parent: LinearLayout, label: String, score: Int) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val vp = dp(4)
            setPadding(0, vp, 0, vp)
        }

        val labelRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        labelRow.addView(makeTextView(label, 13f, color = colorAttr(android.R.attr.textColorPrimary)).also {
            it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        labelRow.addView(makeTextView("$score", 13f, bold = true, color = barColor(score)))

        row.addView(labelRow)

        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max      = 1000
            progress = score
            val lp   = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(8)
            )
            lp.topMargin = dp(3)
            layoutParams = lp
            progressDrawable?.setTint(barColor(score))
        }
        row.addView(bar)
        parent.addView(row)
    }

    private fun barColor(score: Int): Int = when {
        score >= 700 -> 0xFF4CAF50.toInt()
        score >= 450 -> 0xFF2196F3.toInt()
        score >= 250 -> 0xFFFF9800.toInt()
        else         -> 0xFFF44336.toInt()
    }

    // ── Recommendation card ───────────────────────────────────────────────

    private fun buildRecommendationCard(rec: BenchmarkRecommendation): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p       = dp(12)
            setPadding(p, p, p, p)
            val lp      = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(8)
            layoutParams = lp
            background   = ContextCompat.getDrawable(context, R.drawable.background_card)
        }

        val impactColor = when (rec.impact) {
            RecommendationImpact.HIGH   -> 0xFFE53935.toInt()
            RecommendationImpact.MEDIUM -> 0xFFFF9800.toInt()
            RecommendationImpact.LOW    -> 0xFF9E9E9E.toInt()
        }

        val headerRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        headerRow.addView(makeTextView(rec.title, 14f, bold = true,
            color = colorAttr(android.R.attr.textColorPrimary)).also {
            it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        headerRow.addView(makeTextView(rec.impact.label, 11f, color = impactColor))
        card.addView(headerRow)

        card.addView(makeTextView(
            text        = "[${rec.category.label}]  ${rec.description}",
            size        = 12f,
            color       = colorAttr(android.R.attr.textColorSecondary),
            topMarginDp = 4
        ))

        if (rec.settingKey != null && rec.settingValue != null) {
            card.addView(makeButton("Apply Setting", topMarginDp = 8) {
                applyRecommendation(rec)
            })
        }

        return card
    }

    private fun applyRecommendation(rec: BenchmarkRecommendation) {
        runCatching {
            Settings.Manager.put(rec.settingKey!!, rec.settingValue!!).save()
            Toast.makeText(
                context,
                "Applied: ${rec.title}",
                Toast.LENGTH_SHORT
            ).show()
            Logging.i("Benchmark", "Applied recommendation: ${rec.settingKey}=${rec.settingValue}")
        }.onFailure { e ->
            Logging.e("Benchmark", "Failed to apply recommendation ${rec.settingKey}", e)
            Toast.makeText(context, "Failed to apply setting", Toast.LENGTH_SHORT).show()
        }
    }

    // ── View factory helpers ─────────────────────────────────────────────

    private fun makeSectionHeader(text: String): View =
        makeTextView(text, 15f, bold = true,
            color = colorAttr(android.R.attr.textColorPrimary), topMarginDp = 16)

    private fun makeTextView(
        text: String,
        size: Float,
        bold: Boolean = false,
        color: Int = 0xFFFFFFFF.toInt(),
        topMarginDp: Int = 0,
        bgColor: Int? = null,
        paddingDp: Int = 0
    ): TextView = TextView(context).apply {
        this.text      = text
        textSize       = size
        setTextColor(color)
        if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
        bgColor?.let { setBackgroundColor(it) }
        if (paddingDp > 0) { val p = dp(paddingDp); setPadding(p, p / 2, p, p / 2) }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        if (topMarginDp > 0) lp.topMargin = dp(topMarginDp)
        layoutParams = lp
    }

    private fun makeButton(
        label: String,
        topMarginDp: Int = 16,
        onClick: () -> Unit
    ): android.widget.Button = android.widget.Button(context).apply {
        text = label
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(topMarginDp)
        layoutParams = lp
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun colorAttr(attr: Int): Int {
        val arr = context.obtainStyledAttributes(intArrayOf(attr))
        val c   = arr.getColor(0, 0xFF333333.toInt())
        arr.recycle()
        return c
    }
}
