package com.movtery.zalithlauncher.utils.file

import android.content.Context
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.task.TaskExecutors
import com.movtery.zalithlauncher.ui.dialog.ProgressDialog
import com.movtery.zalithlauncher.utils.ZHTools
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.Future

abstract class FileHandler(
    protected val context: Context
) {
    protected var currentTask: Future<*>? = null

    private var timer: Timer? = null
    private var lastProcessedSize: Long = 0
    private var lastUpdateTime: Long = ZHTools.getCurrentTimeMillis()

    protected fun start(progress: FileSearchProgress) {
        TaskExecutors.runInUIThread {
            val dialog = ProgressDialog(context) {
                cancelTask()
                onEnd()
                true
            }

            dialog.updateText(context.getString(R.string.file_operation_file, "0 B", "0 B", 0))

            currentTask = TaskExecutors.getDefault().submit {
                TaskExecutors.runInUIThread { dialog.show() }

                timer = Timer()
                timer?.schedule(object : TimerTask() {
                    override fun run() {
                        val pendingSize = progress.getPendingSize()
                        val totalSize = progress.getTotalSize()
                        val processedSize = totalSize - pendingSize

                        val currentTime = ZHTools.getCurrentTimeMillis()
                        val timeElapsedSeconds = (currentTime - lastUpdateTime) / 1000.0
                        val sizeDelta = processedSize - lastProcessedSize
                        val transferRate =
                            (if (timeElapsedSeconds > 0) sizeDelta / timeElapsedSeconds else 0.0).toLong()

                        lastProcessedSize = processedSize
                        lastUpdateTime = currentTime

                        TaskExecutors.runInUIThread {
                            dialog.updateText(
                                context.getString(
                                    R.string.file_operation_file,
                                    FileTools.formatFileSize(pendingSize),
                                    FileTools.formatFileSize(totalSize),
                                    progress.getCurrentFileCount()
                                )
                            )
                            dialog.updateRate(transferRate)
                            dialog.updateProgress(
                                processedSize.toDouble(),
                                totalSize.toDouble()
                            )
                        }
                    }
                }, 0, 100)

                searchFilesToProcess()
                currentTask?.let { task ->
                    if (task.isCancelled) return@submit
                }

                processFile()

                TaskExecutors.runInUIThread { dialog.dismiss() }
                timer?.cancel()
                onEnd()
            }
        }
    }

    abstract fun searchFilesToProcess()

    abstract fun processFile()

    abstract fun onEnd()

    private fun cancelTask() {
        currentTask?.let { task ->
            if (!task.isDone) {
                task.cancel(true)
                timer?.cancel()
            }
        }
    }
}