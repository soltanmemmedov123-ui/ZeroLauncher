package com.movtery.zalithlauncher.utils.file

import android.content.Context
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.task.Task
import org.apache.commons.io.FileUtils
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class FileDeletionHandler(
    context: Context,
    private val selectedFiles: List<File>,
    private val endTask: Task<*>?
) : FileHandler(context), FileSearchProgress {

    private val foundFiles = mutableListOf<File>()
    private val totalFileSize = AtomicLong(0)
    private val pendingFileSize = AtomicLong(0)
    private val pendingFileCount = AtomicLong(0)

    fun start() {
        super.start(this)
    }

    private fun addFile(file: File) {
        foundFiles.add(file)
        pendingFileCount.incrementAndGet()
        pendingFileSize.addAndGet(FileUtils.sizeOf(file))
    }

    private fun addDirectory(directory: File) {
        if (directory.isFile) {
            addFile(directory)
            return
        }

        if (!directory.isDirectory) {
            return
        }

        directory.listFiles()?.forEach { file ->
            when {
                file.isFile -> addFile(file)
                file.isDirectory -> addDirectory(file)
            }
        }
    }

    override fun searchFilesToProcess() {
        selectedFiles.forEach { file ->
            currentTask?.let { task ->
                if (task.isCancelled) return@forEach
            }

            when {
                file.isFile -> addFile(file)
                file.isDirectory -> addDirectory(file)
            }
        }

        currentTask?.let { task ->
            if (task.isCancelled) return
        }

        totalFileSize.set(pendingFileSize.get())
    }

    override fun processFile() {
        Logging.i("FileDeletionHandler", "Delete files (total files: $pendingFileCount)")

        foundFiles.parallelStream().forEach { file ->
            currentTask?.let { task ->
                if (task.isCancelled) return@forEach
            }

            pendingFileSize.addAndGet(-FileUtils.sizeOf(file))
            pendingFileCount.decrementAndGet()
            FileUtils.deleteQuietly(file)
        }

        currentTask?.let { task ->
            if (task.isCancelled) return
        }

        // The remaining entries are empty folders, so delete them directly.
        selectedFiles.forEach { FileUtils.deleteQuietly(it) }
    }

    override fun getCurrentFileCount(): Long = pendingFileCount.get()

    override fun getTotalSize(): Long = totalFileSize.get()

    override fun getPendingSize(): Long = pendingFileSize.get()

    override fun onEnd() {
        endTask?.execute()
    }
}