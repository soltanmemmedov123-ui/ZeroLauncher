package com.movtery.zalithlauncher.feature.mod.conflict

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.feature.mod.ModUtils
import com.movtery.zalithlauncher.feature.version.Version
import com.movtery.zalithlauncher.ui.dialog.FullScreenDialog
import java.io.File

/**
 * Presents a [ConflictReport] to the user.
 *
 * For each CRASH or MAJOR issue whose files are known, an optional
 * "Disable Mod" button allows immediate remediation without leaving the launcher.
 *
 * @param onContinue Called when the user chooses to launch anyway.
 * @param onAbort    Called when the user chooses not to launch.
 */
class ModConflictDialog(
    context        : Context,
    private val report      : ConflictReport,
    private val version     : Version?,
    private val onContinue  : () -> Unit,
    private val onAbort     : () -> Unit
) : FullScreenDialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCancelable(false)
        setContentView(buildLayout())
    }

    // -------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------

    private fun buildLayout(): android.widget.ScrollView {
        val scroll = android.widget.ScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p       = dp(16)
            setPadding(p, p, p, p)
        }
        scroll.addView(container)

        // ── Header ────────────────────────────────────────────────────────
        val crashes = report.issues.count { it.severity == ConflictSeverity.CRASH }
        val majors  = report.issues.count { it.severity == ConflictSeverity.MAJOR }
        val minors  = report.issues.count { it.severity == ConflictSeverity.MINOR }
        val infos   = report.issues.count { it.severity == ConflictSeverity.INFO }

        val headerColor = if (report.hasCritical) 0xFFE53935.toInt() else 0xFFFF9800.toInt()
        container.addView(makeText(
            "⚠  Mod Conflict Detected", 19f, bold = true, color = headerColor
        ))

        container.addView(makeText(
            "Scanned ${report.scannedCount} mods — found ${report.issues.size} issue(s): " +
                buildSummaryLine(crashes, majors, minors, infos),
            13f, color = colorAttr(android.R.attr.textColorSecondary), topMarginDp = 4
        ))

        if (report.hasCritical) {
            container.addView(makeText(
                "One or more issues will cause the game to crash. " +
                    "It is strongly recommended to fix them before launching.",
                13f, color = 0xFFE53935.toInt(), topMarginDp = 8
            ))
        }

        // ── Issues list ───────────────────────────────────────────────────
        container.addView(makeSectionHeader("Issues"))

        report.issues.forEach { issue ->
            container.addView(buildIssueCard(issue))
        }

        // ── Action buttons ────────────────────────────────────────────────
        container.addView(makeSectionHeader("What would you like to do?"))

        if (report.hasCritical) {
            container.addView(makeButton(
                label       = "Fix Issues First (Recommended)",
                bgColor     = 0xFF4CAF50.toInt(),
                topMarginDp = 8
            ) {
                onAbort()
                dismiss()
            })
        }

        container.addView(makeButton(
            label       = if (report.hasCritical) "Launch Anyway (Risk Crash)" else "Launch Anyway",
            bgColor     = if (report.hasCritical) 0xFFE53935.toInt() else 0xFF2196F3.toInt(),
            topMarginDp = 8
        ) {
            onContinue()
            dismiss()
        })

        if (!report.hasCritical) {
            container.addView(makeButton("Abort Launch", bgColor = 0xFF9E9E9E.toInt(), topMarginDp = 8) {
                onAbort()
                dismiss()
            })
        }

        return scroll
    }

    // ── Issue card ────────────────────────────────────────────────────────

    private fun buildIssueCard(issue: ConflictIssue): android.view.View {
        val severityColor = when (issue.severity) {
            ConflictSeverity.CRASH -> 0xFFE53935.toInt()
            ConflictSeverity.MAJOR -> 0xFFFF9800.toInt()
            ConflictSeverity.MINOR -> 0xFF2196F3.toInt()
            ConflictSeverity.INFO  -> 0xFF9E9E9E.toInt()
        }

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

        // severity badge + title on one row
        val headerRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        headerRow.addView(makeText(
            "  ${issue.severity.label}  ", 11f, bold = true,
            color = 0xFFFFFFFF.toInt(), bgColor = severityColor, paddingDp = 3
        ))
        headerRow.addView(makeText(
            issue.title, 13f, bold = true,
            color = colorAttr(android.R.attr.textColorPrimary),
            leftMarginDp = 8
        ).also {
            it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        card.addView(headerRow)

        // description
        card.addView(makeText(
            issue.description, 12f,
            color = colorAttr(android.R.attr.textColorSecondary),
            topMarginDp = 6
        ))

        // involved files
        if (issue.involvedMods.isNotEmpty()) {
            card.addView(makeText(
                "Files: " + issue.involvedMods.joinToString(", "),
                11f, color = 0xFF888888.toInt(), topMarginDp = 4
            ))
        }

        // "Disable" button for actionable crash/major issues with a known file
        if ((issue.severity == ConflictSeverity.CRASH || issue.severity == ConflictSeverity.MAJOR)
            && issue.involvedMods.isNotEmpty() && version != null
        ) {
            val targetFile = findModFile(issue.involvedMods.first())
            if (targetFile != null && targetFile.exists()) {
                card.addView(makeButton(
                    label       = "Disable ${targetFile.name}",
                    bgColor     = 0xFFE53935.toInt(),
                    topMarginDp = 8
                ) {
                    disableMod(targetFile, issue.title, card)
                })
            }
        }

        return card
    }

    // ── Mod file helpers ──────────────────────────────────────────────────

    private fun findModFile(fileName: String): File? {
        val modsDir = version?.getGameDir()?.let { File(it, "mods") } ?: return null
        return modsDir.listFiles()?.firstOrNull { it.name == fileName }
    }

    private fun disableMod(file: File, issueTitle: String, card: LinearLayout) {
        try {
            ModUtils.disableMod(file)
            Toast.makeText(context, "Disabled: ${file.name}", Toast.LENGTH_SHORT).show()
            Logging.i("ConflictDialog", "Disabled mod: ${file.name} for issue: $issueTitle")
            // Remove the disable button after acting
            val btn = card.getChildAt(card.childCount - 1)
            card.removeView(btn)
            card.addView(makeText("✓ Disabled", 12f, color = 0xFF4CAF50.toInt(), topMarginDp = 6))
        } catch (e: Exception) {
            Logging.e("ConflictDialog", "Failed to disable mod: ${file.name}", e)
            Toast.makeText(context, "Failed to disable mod", Toast.LENGTH_SHORT).show()
        }
    }

    // ── View factory helpers ──────────────────────────────────────────────

    private fun buildSummaryLine(crashes: Int, majors: Int, minors: Int, infos: Int): String {
        val parts = mutableListOf<String>()
        if (crashes > 0) parts += "$crashes crash"
        if (majors  > 0) parts += "$majors major"
        if (minors  > 0) parts += "$minors minor"
        if (infos   > 0) parts += "$infos info"
        return parts.joinToString(", ")
    }

    private fun makeSectionHeader(text: String): TextView =
        makeText(text, 15f, bold = true,
            color = colorAttr(android.R.attr.textColorPrimary), topMarginDp = 16)

    private fun makeText(
        text         : String,
        size         : Float,
        bold         : Boolean = false,
        color        : Int     = 0xFFFFFFFF.toInt(),
        topMarginDp  : Int     = 0,
        leftMarginDp : Int     = 0,
        bgColor      : Int?    = null,
        paddingDp    : Int     = 0
    ): TextView = TextView(context).apply {
        this.text = text
        textSize  = size
        setTextColor(color)
        if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
        bgColor?.let { setBackgroundColor(it) }
        if (paddingDp > 0) {
            val p = dp(paddingDp)
            setPadding(p, p / 2, p, p / 2)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        if (topMarginDp  > 0) lp.topMargin  = dp(topMarginDp)
        if (leftMarginDp > 0) lp.leftMargin = dp(leftMarginDp)
        layoutParams = lp
    }

    private fun makeButton(
        label       : String,
        bgColor     : Int   = 0xFF2196F3.toInt(),
        topMarginDp : Int   = 8,
        onClick     : () -> Unit
    ): android.widget.Button = android.widget.Button(context).apply {
        text = label
        setBackgroundColor(bgColor)
        setTextColor(0xFFFFFFFF.toInt())
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
