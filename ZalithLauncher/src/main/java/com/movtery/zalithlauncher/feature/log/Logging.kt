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
    private val logExecutor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(), Executors.defaultThreadFactory())
    @Volatile private var isLauncherInfoWritten = false
    @Volatile private var FILE_LAUNCHER_LOG: File? = null

    private val TOKEN_PATTERNS = listOf(
        Pattern.compile("(accessToken|access_token|msaRefreshToken|refresh_token|clientToken)([\"\\s:=]+)[A-Za-z0-9\\-_.~+/]{20,}"),
        Pattern.compile("(Bearer )([A-Za-z0-9\\-_.~+/=]{20,})"),
        Pattern.compile("(otherPassword|password)([\"\\s:=]+)[^\"\\s,}]{4,}")
    )

    init { FILE_LAUNCHER_LOG = getLogFile() }

    private fun getLogFile(): File {
        val dir = File(DIR_LAUNCHER_LOG).apply { if (!exists()) mkdirs() }
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("log") && f.name.endsWith(".txt") } ?: emptyArray()
        if (files.isEmpty()) return File(dir, "log1.txt")
        val idx = files.maxByOrNull { it.lastModified() }?.name?.removePrefix("log")?.removeSuffix(".txt")?.toIntOrNull() ?: 0
        return File(dir, "log${(idx % 10) + 1}.txt").also { if (it.exists()) it.delete() }
    }

    private fun scrub(log: String): String {
        var s = log
        for (p in TOKEN_PATTERNS) s = p.matcher(s).replaceAll { m -> "${m.group(1)}${m.group(2)}[REDACTED]" }
        return s
    }

    private fun write(log: String, tag: Tag, mark: String) {
        logExecutor.submit {
            try {
                val line = "[${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}] (${tag.name}) <$mark> ${scrub(log)}"
                FILE_LAUNCHER_LOG?.let { f -> if (f.exists() && f.length() >= 15*1024*1024) { FILE_LAUNCHER_LOG = getLogFile(); isLauncherInfoWritten = false } }
                BufferedWriter(FileWriter(FILE_LAUNCHER_LOG, true)).use { w ->
                    if (!isLauncherInfoWritten) { isLauncherInfoWritten = true; w.append("=== ${InfoDistributor.APP_NAME} ${BuildConfig.VERSION_NAME} ===\r\n\r\n") }
                    w.append(line).append("\r\n")
                }
            } catch (e: Exception) { Log.e("Logging", Tools.printToString(e)) }
        }
    }

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
