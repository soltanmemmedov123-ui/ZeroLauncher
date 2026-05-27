package com.movtery.zalithlauncher.ui.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.movtery.zalithlauncher.InfoCenter
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.databinding.ActivityErrorBinding
import com.movtery.zalithlauncher.utils.ZHTools
import net.kdt.pojavlaunch.Tools

class ErrorActivity : BaseActivity() {
    private lateinit var binding: ActivityErrorBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityErrorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val extras = intent.extras
        extras ?: run {
            finish()
            return
        }

        binding.errorConfirm.setOnClickListener { finish() }
        binding.errorRestart.setOnClickListener {
            startActivity(Intent(this@ErrorActivity, SplashActivity::class.java))
        }
        binding.shareLog.setOnClickListener { ZHTools.shareLogs(this) }

        if (extras.getBoolean(BUNDLE_IS_LAUNCHER_CRASH, false)) {
            showLauncherCrash(extras)
            return
        }
        if (extras.getBoolean(BUNDLE_IS_GAME_CRASH, false)) {
            // Don't allow screenshots on game crash screen
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            showGameCrash(extras)
            return
        }
        if (extras.getBoolean(BUNDLE_EASTER_EGG, false)) {
            showEasterEgg()
            return
        }

        finish()
    }

    private fun showLauncherCrash(extras: Bundle) {
        val context = this

        val throwable = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            extras.getSerializable(BUNDLE_THROWABLE, Throwable::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getSerializable(BUNDLE_THROWABLE) as Throwable?
        }
        val stackTrace = if (throwable != null) Tools.printToString(throwable) else "<null>"
        val strSavePath = extras.getString(BUNDLE_SAVE_PATH)

        val sb = StringBuilder()
        sb.appendLine("━━━ LAUNCHER CRASH ━━━")
        sb.appendLine()

        if (throwable != null) {
            val rootCause = getRootCause(throwable)
            sb.appendLine("⚠  What happened:")
            sb.appendLine("   ${describeThrowable(rootCause)}")
            sb.appendLine()
        }

        sb.appendLine("📄  Crash report saved to:")
        sb.appendLine("   $strSavePath")
        sb.appendLine()
        sb.appendLine("━━━ STACK TRACE ━━━")
        sb.appendLine()
        sb.append(stackTrace)

        binding.apply {
            this.errorTitle.text = InfoCenter.replaceName(context, R.string.error_fatal)
            this.errorText.text = sb.toString()
            this.topView.setBackgroundColor(ContextCompat.getColor(context, R.color.background_menu_top_error))
            this.background.setBackgroundColor(ContextCompat.getColor(context, R.color.background_app_error))
        }
    }

    private fun showGameCrash(extras: Bundle) {
        val code = extras.getInt(BUNDLE_CODE, 0)
        if (code == 0) {
            finish()
            return
        }

        val isSignal = extras.getBoolean(BUNDLE_IS_SIGNAL)
        val context = this

        val sb = StringBuilder()

        if (isSignal) {
            val sigName = getSignalName(code)
            val sigDesc = getSignalDescription(code)
            val sigSuggestion = getSignalSuggestion(code)

            sb.appendLine("━━━ FATAL SIGNAL $code ($sigName) ━━━")
            sb.appendLine()
            sb.appendLine("⚠  What happened:")
            sb.appendLine("   $sigDesc")
            sb.appendLine()
            sb.appendLine("💡  What to try:")
            sb.appendLine("   $sigSuggestion")
        } else {
            val exitDesc = getExitCodeDescription(code)
            val exitSuggestion = getExitCodeSuggestion(code)

            sb.appendLine("━━━ EXIT CODE $code ━━━")
            sb.appendLine()
            sb.appendLine("⚠  What happened:")
            sb.appendLine("   $exitDesc")
            sb.appendLine()
            sb.appendLine("💡  What to try:")
            sb.appendLine("   $exitSuggestion")
        }

        sb.appendLine()
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        sb.appendLine()
        sb.append(getString(R.string.game_exit_note))

        binding.apply {
            this.errorTitle.setText(R.string.generic_wrong_tip)
            this.errorText.apply {
                text = sb.toString()
                textSize = 12f
            }
            this.errorTip.visibility = View.GONE  // merged into errorText above
            this.errorNoScreenshot.visibility = View.VISIBLE
            this.topView.setBackgroundColor(ContextCompat.getColor(context, R.color.background_menu_top))
            this.background.setBackgroundColor(ContextCompat.getColor(context, R.color.background_app))
        }
    }

    private fun showEasterEgg() {
        val context = this

        binding.apply {
            this.topView.visibility = View.GONE
            this.scrollView.visibility = View.GONE
            this.shareLog.visibility = View.GONE
            this.centerText.visibility = View.VISIBLE
            this.centerText.text = InfoCenter.replaceName(context, R.string.error_fatal)
            this.topView.setBackgroundColor(ContextCompat.getColor(context, R.color.background_menu_top_error))
            this.background.setBackgroundResource(R.drawable.image_xibao)
        }
    }

    // ─── Exit code helpers ────────────────────────────────────────────────────

    private fun getExitCodeDescription(code: Int): String = when (code) {
        1    -> "General error — the JVM or game encountered an unspecified problem."
        2    -> "Misuse of a shell builtin or command (rare in normal game operation)."
        3    -> "The JVM was asked to halt with a non-zero status (System.exit(3))."
        127  -> "Command not found — a required executable or library could not be located."
        130  -> "Terminated by Ctrl+C / keyboard interrupt."
        134  -> "SIGABRT — the game called abort(), usually due to a JVM internal error or failed assertion."
        137  -> "SIGKILL — the OS forcefully killed the process. This almost always means the device ran out of RAM."
        139  -> "SIGSEGV — segmentation fault. The game accessed memory it shouldn't have."
        255, -1 -> "JVM fatal error — Java encountered a critical internal error it could not recover from."
        else -> if (code > 128) "Killed by signal ${code - 128} — the OS terminated the process with a system signal."
                else "The game exited with code $code (no specific description available)."
    }

    private fun getExitCodeSuggestion(code: Int): String = when (code) {
        1    -> "Check the log or crash report for the root exception message."
        134  -> "Look in the log for 'FATAL ERROR' or 'A fatal error has been detected' and check the hs_err_pid file if present."
        137  -> "Reduce the memory allocation in Settings → Java → Memory. Close other apps before launching."
        139  -> "This may be a GPU driver issue or an incompatible native mod (.so file). Try a different renderer or remove recently added mods."
        255, -1 -> "Look for 'Could not create JVM' or 'OutOfMemoryError' in the log. Try adjusting JVM arguments."
        else -> if (code > 128) "Check the log for the last few lines before the game exited; they usually describe the cause."
                else "Check the crash report folder and log file for more details."
    }

    // ─── Signal helpers ───────────────────────────────────────────────────────

    private fun getSignalName(signal: Int): String = when (signal) {
        1  -> "SIGHUP"
        2  -> "SIGINT"
        3  -> "SIGQUIT"
        4  -> "SIGILL"
        6  -> "SIGABRT"
        7  -> "SIGBUS"
        8  -> "SIGFPE"
        9  -> "SIGKILL"
        11 -> "SIGSEGV"
        13 -> "SIGPIPE"
        15 -> "SIGTERM"
        else -> "SIG$signal"
    }

    private fun getSignalDescription(signal: Int): String = when (signal) {
        4  -> "SIGILL — Illegal CPU instruction. A native library or JIT-compiled code used an instruction the device's CPU doesn't support."
        6  -> "SIGABRT — The game aborted itself. This usually means the JVM detected an unrecoverable internal error."
        7  -> "SIGBUS — Bus error (bad memory alignment). Often caused by a faulty native library or memory-mapped file issue."
        8  -> "SIGFPE — Floating-point or arithmetic exception (e.g. division by zero in a native component)."
        9  -> "SIGKILL — The OS force-killed the process, most commonly because the device ran critically low on memory."
        11 -> "SIGSEGV — Segmentation fault. The game or JVM accessed invalid memory. Common with driver bugs or incompatible native mods."
        13 -> "SIGPIPE — Broken pipe: the game tried to write to a closed socket or file descriptor."
        15 -> "SIGTERM — The game received a termination request (e.g. from the notification 'Stop' button or the OS)."
        else -> "Signal $signal terminated the game unexpectedly."
    }

    private fun getSignalSuggestion(signal: Int): String = when (signal) {
        4  -> "Try a different Java runtime (e.g. switch from 32-bit to 64-bit JRE) or remove mods with native libraries."
        6  -> "Check the log for 'FATAL ERROR' lines. The JVM's hs_err_pid file (in game dir) may have more details."
        7  -> "Remove recently added mods with native libraries (.so files), or try a different renderer."
        8  -> "This is usually a bug in a mod or in the game itself. Check the crash report for the offending class."
        9  -> "Reduce memory allocation in Settings → Java → Memory. Close background apps before launching."
        11 -> "Try switching the renderer (e.g. from VirGL to Zink or vice versa). Remove mods with native libraries. Update graphics drivers."
        13 -> "Usually harmless if the game otherwise worked. If it happens on launch, check for network-related mods."
        15 -> "The game was stopped on purpose. If unexpected, check for background task killers or OOM conditions."
        else -> "Check the log and crash report for details about what happened just before the signal was received."
    }

    // ─── Throwable helpers ────────────────────────────────────────────────────

    private fun getRootCause(t: Throwable): Throwable {
        var cause = t
        var depth = 0
        while (cause.cause != null && depth++ < 10) cause = cause.cause!!
        return cause
    }

    private fun describeThrowable(t: Throwable): String {
        val name = t.javaClass.simpleName
        val msg = t.message?.take(200) ?: "(no message)"
        return "$name: $msg"
    }

    companion object {
        private const val BUNDLE_IS_LAUNCHER_CRASH = "is_launcher_crash"
        private const val BUNDLE_IS_GAME_CRASH     = "is_game_crash"
        private const val BUNDLE_IS_SIGNAL         = "is_signal"
        private const val BUNDLE_CODE              = "code"
        private const val BUNDLE_THROWABLE         = "throwable"
        private const val BUNDLE_SAVE_PATH         = "save_path"
        private const val BUNDLE_EASTER_EGG        = "easter_egg"

        @JvmStatic
        fun showLauncherCrash(ctx: Context, savePath: String?, th: Throwable?) {
            val intent = Intent(ctx, ErrorActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra(BUNDLE_THROWABLE, th)
            intent.putExtra(BUNDLE_SAVE_PATH, savePath)
            intent.putExtra(BUNDLE_IS_LAUNCHER_CRASH, true)
            ctx.startActivity(intent)
        }

        @JvmStatic
        fun showExitMessage(ctx: Context, code: Int, isSignal: Boolean) {
            val intent = Intent(ctx, ErrorActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra(BUNDLE_CODE, code)
            intent.putExtra(BUNDLE_IS_LAUNCHER_CRASH, false)
            intent.putExtra(BUNDLE_IS_SIGNAL, isSignal)
            intent.putExtra(BUNDLE_IS_GAME_CRASH, true)
            ctx.startActivity(intent)
        }

        @JvmStatic
        fun showEasterEgg(ctx: Context) {
            val intent = Intent(ctx, ErrorActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra(BUNDLE_EASTER_EGG, true)
            ctx.startActivity(intent)
        }
    }
}
