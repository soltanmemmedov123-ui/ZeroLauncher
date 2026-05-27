package com.movtery.zalithlauncher.feature.benchmark

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.task.TaskExecutors
import com.movtery.zalithlauncher.ui.dialog.FullScreenDialog

/**
 * Shows a progress dialog while [BenchmarkEngine] is running, then replaces
 * itself with [BenchmarkResultsDialog] when complete.
 */
class BenchmarkRunDialog(context: Context) : FullScreenDialog(context) {

    private lateinit var phaseLabel    : TextView
    private lateinit var progressBar   : ProgressBar
    private lateinit var progressLabel : TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCancelable(false)

        val root = buildLayout()
        setContentView(root)

        startBenchmark()
    }

    // -------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------

    private fun buildLayout(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad     = dp(24)
            setPadding(pad, pad * 2, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            addView(TextView(context).apply {
                text     = "Running Benchmark…"
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(colorAttr(android.R.attr.textColorPrimary))
            })

            addView(TextView(context).apply {
                text     = "Do not close the launcher. This takes about 15 seconds."
                textSize = 13f
                setTextColor(colorAttr(android.R.attr.textColorSecondary))
                val lp   = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = dp(6)
                layoutParams = lp
            })

            phaseLabel = TextView(context).apply {
                text     = "Preparing…"
                textSize = 14f
                setTextColor(colorAttr(android.R.attr.textColorPrimary))
                val lp  = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = dp(32)
                layoutParams = lp
            }
            addView(phaseLabel)

            progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max  = 100
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(12)
                )
                lp.topMargin = dp(8)
                layoutParams = lp
            }
            addView(progressBar)

            progressLabel = TextView(context).apply {
                text     = "0%"
                textSize = 12f
                setTextColor(colorAttr(android.R.attr.textColorSecondary))
                val lp  = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = dp(4)
                layoutParams = lp
            }
            addView(progressLabel)
        }
    }

    // -------------------------------------------------------------------------
    // Benchmark execution
    // -------------------------------------------------------------------------

    private val phaseNames = mapOf(
        BenchmarkPhase.CPU_SINGLE      to "CPU Single-Core Test",
        BenchmarkPhase.CPU_MULTI       to "CPU Multi-Core Test",
        BenchmarkPhase.MEMORY          to "Memory Bandwidth Test",
        BenchmarkPhase.STORAGE         to "Storage I/O Test",
        BenchmarkPhase.RECOMMENDATIONS to "Computing Recommendations…",
        BenchmarkPhase.DONE            to "Done"
    )

    /**
     * Overall progress: each phase contributes a slice.
     * Phases: CPU_SINGLE(0..24), CPU_MULTI(25..49), MEMORY(50..69), STORAGE(70..89), RECS(90..99)
     */
    private fun toOverallProgress(phase: BenchmarkPhase, phaseProgress: Int): Int = when (phase) {
        BenchmarkPhase.CPU_SINGLE      -> (phaseProgress * 0.24).toInt()
        BenchmarkPhase.CPU_MULTI       -> 24 + (phaseProgress * 0.25).toInt()
        BenchmarkPhase.MEMORY          -> 49 + (phaseProgress * 0.20).toInt()
        BenchmarkPhase.STORAGE         -> 69 + (phaseProgress * 0.20).toInt()
        BenchmarkPhase.RECOMMENDATIONS -> 89 + (phaseProgress * 0.10).toInt()
        BenchmarkPhase.DONE            -> 100
    }

    private fun startBenchmark() {
        TaskExecutors.getDefault().execute {
            try {
                val result = BenchmarkEngine.run(context) { phase, phasePct ->
                    val overall = toOverallProgress(phase, phasePct)
                    TaskExecutors.runInUIThread {
                        phaseLabel.text    = phaseNames[phase] ?: phase.name
                        progressBar.progress = overall
                        progressLabel.text = "$overall%"
                    }
                }

                TaskExecutors.runInUIThread {
                    dismiss()
                    BenchmarkResultsDialog(context, result).show()
                }

            } catch (e: Exception) {
                Logging.e("BenchmarkRunDialog", "Benchmark failed", e)
                TaskExecutors.runInUIThread {
                    dismiss()
                    android.widget.Toast.makeText(
                        context,
                        "Benchmark failed: ${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // -------------------------------------------------------------------------

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun colorAttr(attr: Int): Int {
        val arr = context.obtainStyledAttributes(intArrayOf(attr))
        val c   = arr.getColor(0, 0xFF333333.toInt())
        arr.recycle()
        return c
    }
}
