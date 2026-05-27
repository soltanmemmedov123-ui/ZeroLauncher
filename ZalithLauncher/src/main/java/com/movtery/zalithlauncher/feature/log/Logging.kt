package com.movtery.zalithlauncher.feature.log

import android.util.Log
import com.movtery.zalithlauncher.BuildConfig
import com.movtery.zalithlauncher.InfoDistributor
import com.movtery.zalithlauncher.utils.path.PathManager.Companion.DIR_LAUNCHER_LOG
import net.kdt.pojavlaunch.Tools
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object Logging {
    private val logExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(), Executors.defaultThreadFactory()
    )

    @Volatile private var isLauncherInfoWritten = false
    @Volatile private var FILE_LAUNCHER_LOG: File? = null

    // ─── Token scrubbing ──────────────────────────────────────────────────────

    private val TOKEN_PATTERNS = listOf(
        Pattern.compile("(accessToken|access_token|msaRefreshToken|refresh_token|clientToken)([\"\\s:=]+)[A-Za-z0-9\\-_.~+/]{20,}"),
        Pattern.compile("(Bearer )([A-Za-z0-9\\-_.~+/=]{20,})"),
        Pattern.compile("(otherPassword|password)([\"\\s:=]+)[^\"\\s,}]{4,}")
    )

    // ─── Crash pattern detection ──────────────────────────────────────────────
    // Maps a substring to look for → a friendly one-line note appended to the log.

    private val CRASH_PATTERNS: List<Pair<String, String>> = listOf(
        "OutOfMemoryError"              to "🔴 CRASH HINT: Out of memory — reduce allocated RAM in Settings → Java → Memory, or close background apps.",
        "Could not create the Java"     to "🔴 CRASH HINT: JVM creation failed — check your JVM arguments or reduce memory allocation.",
        "Could not create JVM"          to "🔴 CRASH HINT: JVM creation failed — check your JVM arguments or reduce memory allocation.",
        "StackOverflowError"            to "🔴 CRASH HINT: Stack overflow — infinite recursion detected, likely a mod or mixin bug.",
        "ClassNotFoundException"        to "🔴 CRASH HINT: A required class was not found — a mod may be missing a dependency.",
        "NoClassDefFoundError"          to "🔴 CRASH HINT: A class definition was missing at runtime — check for missing or incompatible mods.",
        "UnsatisfiedLinkError"          to "🔴 CRASH HINT: Native library (.so) failed to load — incompatible architecture or missing library.",
        "SIGSEGV"                       to "🔴 CRASH HINT: Segmentation fault — try switching the renderer or removing mods with native libraries.",
        "SIGABRT"                       to "🔴 CRASH HINT: Process aborted — check for a 'FATAL ERROR' line above or the hs_err_pid file in the game folder.",
        "SIGILL"                        to "🔴 CRASH HINT: Illegal CPU instruction — try a different JRE (32-bit vs 64-bit) or remove native mods.",
        "java.lang.NullPointerException" to "🟡 CRASH HINT: Null pointer exception — often caused by a mod that didn't initialise properly.",
        "Pixel format not accelerated"  to "🔴 CRASH HINT: OpenGL not available — check your renderer settings or try a different graphics backend.",
        "GL_OUT_OF_MEMORY"              to "🔴 CRASH HINT: GPU ran out of memory — lower your render distance or reduce graphics settings.",
        "Failed to download"            to "🟡 CRASH HINT: Download failure — check your internet connection, then try re-installing the version.",
        "Connection refused"            to "🟡 CRASH HINT: Network connection refused — the server may be offline or a firewall may be blocking the connection.",
        "Address already in use"        to "🟡 CRASH HINT: A required network port is already in use — another instance of the game may still be running.",
        "Incompatible magic value"      to "🔴 CRASH HINT: Corrupt or incompatible .class / .jar file — try re-downloading the version or the affected mod.",
        "java.util.zip.ZipException"    to "🔴 CRASH HINT: Corrupt ZIP/JAR file — the download may have been interrupted. Re-download the version or mod.",
        "Failed to verify username"     to "🟡 CRASH HINT: Authentication failed — refresh your account or check your internet connection.",
        "Invalid session"               to "🟡 CRASH HINT: Session token invalid — log out and log back in from the accounts screen."
    )

    init {
        FILE_LAUNCHER_LOG = getLogFile()
    }

    private fun getLogFile(): File {
        val dir = File(DIR_LAUNCHER_LOG).apply { if (!exists()) mkdirs() }
        val files = dir.listFiles { f ->
            f.isFile && f.name.startsWith("log") && f.name.endsWith(".txt")
        } ?: emptyArray()

        if (files.isEmpty()) return File(dir, "log1.txt")

        val idx = files.maxByOrNull { it.lastModified() }
            ?.name?.removePrefix("log")?.removeSuffix(".txt")?.toIntOrNull() ?: 0
        return File(dir, "log${(idx % 10) + 1}.txt").also { if (it.exists()) it.delete() }
    }

    private fun scrub(log: String): String {
        var s = log
        for (p in TOKEN_PATTERNS) s = p.matcher(s).replaceAll { m -> "${m.group(1)}${m.group(2)}[REDACTED]" }
        return s
    }

    /**
     * Scan [log] for known crash patterns and return a hint line, or null if nothing matched.
     */
    private fun detectCrashHint(log: String): String? {
        for ((pattern, hint) in CRASH_PATTERNS) {
            if (log.contains(pattern, ignoreCase = false)) return hint
        }
        return null
    }

    private fun write(log: String, tag: Tag, mark: String) {
        logExecutor.submit {
            try {
                val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val line = "[$timestamp] (${tag.name}) <$mark> ${scrub(log)}"

                // Rotate log file if it exceeds 15 MB
                FILE_LAUNCHER_LOG?.let { f ->
                    if (f.exists() && f.length() >= 15 * 1024 * 1024) {
                        FILE_LAUNCHER_LOG = getLogFile()
                        isLauncherInfoWritten = false
                    }
                }

                BufferedWriter(FileWriter(FILE_LAUNCHER_LOG, true)).use { w ->
                    if (!isLauncherInfoWritten) {
                        isLauncherInfoWritten = true
                        w.append("=== ${InfoDistributor.APP_NAME} ${BuildConfig.VERSION_NAME} ===\r\n\r\n")
                    }
                    w.append(line).append("\r\n")

                    // For ERROR or WARN entries, check if this looks like a known crash
                    if (tag == Tag.ERROR || tag == Tag.WARN) {
                        val hint = detectCrashHint(log)
                        if (hint != null) {
                            val hintLine = "[$timestamp] (HINT) <CrashDetector> $hint"
                            w.append(hintLine).append("\r\n")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Logging", Tools.printToString(e))
            }
        }
    }

    /**
     * Write a clearly separated crash summary block to the log **synchronously**.
     *
     * This MUST NOT use the logExecutor because the caller (the uncaught-exception handler)
     * calls killProcess() immediately afterward — an async submit would never finish in time.
     * Writing directly on the calling thread guarantees the data reaches disk before the
     * process is terminated.
     */
    @JvmStatic
    fun writeCrashSummary(throwable: Throwable) {
        try {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            // Use FILE_LAUNCHER_LOG; fall back to a temp file if it's somehow null
            val target = FILE_LAUNCHER_LOG ?: File(DIR_LAUNCHER_LOG, "crash_summary.txt")
            BufferedWriter(FileWriter(target, true)).use { w ->
                w.append("\r\n")
                w.append("[$timestamp] ════════════════════════════════════════\r\n")
                w.append("[$timestamp]  \uD83D\uDCA5 LAUNCHER CRASH DETECTED\r\n")
                w.append("[$timestamp] ════════════════════════════════════════\r\n")

                // Walk to the root cause (max 10 levels to avoid cycles)
                var root: Throwable = throwable
                var depth = 0
                while (root.cause != null && depth++ < 10) root = root.cause!!
                w.append("[$timestamp]  Root cause : ${root.javaClass.name}\r\n")
                root.message?.let { w.append("[$timestamp]  Message    : ${it.take(300)}\r\n") }

                // Append a friendly hint if we recognise the error pattern
                val fullTrace = Tools.printToString(throwable)
                val hint = detectCrashHint(fullTrace)
                    ?: detectCrashHint(throwable.message ?: "")
                if (hint != null) {
                    w.append("[$timestamp]  $hint\r\n")
                }

                w.append("[$timestamp] ════════════════════════════════════════\r\n")
                w.append("\r\n")
            }
        } catch (e: Exception) {
            // Last-resort: at least print to logcat
            Log.e("Logging", "Failed to write crash summary: ${Tools.printToString(e)}")
        }
    }

    // ─── Public logging API ───────────────────────────────────────────────────

    @JvmStatic fun v(mark: String, msg: String) { Log.v(mark, msg); write(msg, Tag.VERBOSE, mark) }
    @JvmStatic fun v(mark: String, msg: String, t: Throwable) { Log.v(mark, msg, t); write("$msg\n${Tools.printToString(t)}", Tag.VERBOSE, mark) }

    @JvmStatic fun d(mark: String, msg: String) { Log.d(mark, msg); write(msg, Tag.DEBUG, mark) }
    @JvmStatic fun d(mark: String, msg: String, t: Throwable) { Log.d(mark, msg, t); write("$msg\n${Tools.printToString(t)}", Tag.DEBUG, mark) }

    @JvmStatic fun i(mark: String, msg: String) { Log.i(mark, msg); write(msg, Tag.INFO, mark) }
    @JvmStatic fun i(mark: String, msg: String, t: Throwable) { Log.i(mark, msg, t); write("$msg\n${Tools.printToString(t)}", Tag.INFO, mark) }

    @JvmStatic fun w(mark: String, msg: String) { Log.w(mark, msg); write(msg, Tag.WARN, mark) }
    @JvmStatic fun w(mark: String, msg: String, t: Throwable) { Log.w(mark, msg, t); write("$msg\n${Tools.printToString(t)}", Tag.WARN, mark) }

    @JvmStatic fun e(mark: String, msg: String) { Log.e(mark, msg); write(msg, Tag.ERROR, mark) }
    @JvmStatic fun e(mark: String, msg: String, t: Throwable) { Log.e(mark, msg, t); write("$msg\n${Tools.printToString(t)}", Tag.ERROR, mark) }

    enum class Tag { VERBOSE, DEBUG, INFO, WARN, ERROR }
}
