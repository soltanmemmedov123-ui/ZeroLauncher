package com.movtery.zalithlauncher.utils.file

import android.content.Context
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.task.Task
import com.movtery.zalithlauncher.utils.file.FileTools.Companion.getFileNameWithoutExtension
import org.apache.commons.io.FileUtils
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class FileCopyHandler(
    context: Context,
    private val pasteType: PasteFile.PasteType,
    private val selectedFiles: List<File>,
    private val root: File,
    private val target: File,
    private val fileExtensionGetter: FileExtensionGetter?,
    private val endTask: Task<*>
) : FileHandler(context), FileSearchProgress {

    private val foundFiles = mutableMapOf<File, File>()
    private val totalFileSize = AtomicLong(0)
    private val pendingFileSize = AtomicLong(0)
    private val pendingFileCount = AtomicLong(0)

    fun start() {
        super.start(this)
    }

    private fun addFile(file: File) {
        pendingFileCount.incrementAndGet()
        pendingFileSize.addAndGet(FileUtils.sizeOf(file))

        val targetDir = getTargetDirectory(file)
        val extension = fileExtensionGetter?.onGet(file)

        foundFiles[file] = getAvailableDestination(file, targetDir, extension)
    }

    private fun addDirectory(directory: File) {
        if (directory.isFile) {
            addFile(directory)
            return
        }

        val files = directory.listFiles() ?: return

        if (files.isEmpty()) {
            addFile(directory)
            return
        }

        files.forEach { file ->
            when {
                file.isFile -> addFile(file)
                file.isDirectory -> addDirectory(file)
            }
        }
    }

    private fun getTargetDirectory(file: File): File {
        return File(
            file.absolutePath
                .replace(root.absolutePath, target.absolutePath)
                .removeSuffix(file.name)
        )
    }

    /**
     * If a file with the same name already exists in the target location,
     * append a numeric suffix to the file name to prevent overwriting.
     */
    private fun getAvailableDestination(sourceFile: File, targetDir: File, fileExtension: String?): File {
        var extension = fileExtension
        var destinationFile = File(targetDir, sourceFile.name)

        if (!destinationFile.exists()) {
            return destinationFile
        }

        val fileNameWithoutExt = getFileNameWithoutExtension(sourceFile.name, extension)

        if (extension == null) {
            val dotIndex = sourceFile.name.lastIndexOf('.')
            extension = if (dotIndex == -1) "" else sourceFile.name.substring(dotIndex)
        }

        var counter = 1
        while (destinationFile.exists()) {
            val proposedFileName = "$fileNameWithoutExt ($counter)$extension"
            destinationFile = File(targetDir, proposedFileName)
            counter++
        }

        return destinationFile
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
        Logging.i("FileCopyHandler", "Copy files (total files: $pendingFileCount, to ${target.absolutePath})")

        foundFiles.entries.parallelStream().forEach { (currentFile, targetFile) ->
            currentTask?.let { task ->
                if (task.isCancelled) return@forEach
            }

            pendingFileSize.addAndGet(-FileUtils.sizeOf(currentFile))
            pendingFileCount.decrementAndGet()

            targetFile.parentFile?.takeIf { !it.exists() }?.mkdirs()

            when (pasteType) {
                PasteFile.PasteType.COPY -> FileTools.copyFile(currentFile, targetFile)
                PasteFile.PasteType.MOVE -> FileTools.moveFile(currentFile, targetFile)
            }
        }

        currentTask?.let { task ->
            if (task.isCancelled) return
        }

        if (pasteType == PasteFile.PasteType.MOVE) {
            selectedFiles.forEach { FileUtils.deleteQuietly(it) }
        }
    }

    override fun getCurrentFileCount(): Long = pendingFileCount.get()

    override fun getTotalSize(): Long = totalFileSize.get()

    override fun getPendingSize(): Long = pendingFileSize.get()

    override fun onEnd() {
        endTask.execute()
    }

    interface FileExtensionGetter {
        fun onGet(file: File?): String?
    }
}