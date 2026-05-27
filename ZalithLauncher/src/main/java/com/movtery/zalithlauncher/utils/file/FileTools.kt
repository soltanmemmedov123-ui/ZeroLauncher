package com.movtery.zalithlauncher.utils.file

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.EditText
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.task.Task
import com.movtery.zalithlauncher.ui.dialog.EditTextDialog
import com.movtery.zalithlauncher.ui.dialog.EditTextDialog.ConfirmListener
import com.movtery.zalithlauncher.utils.ZHTools
import net.kdt.pojavlaunch.Tools
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.FileInputStream
import java.io.FilenameFilter
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileTools {
    companion object {
        const val INVALID_CHARACTERS_REGEX = "[\\\\/:*?\"<>|\\t\\n]"

        @JvmStatic
        fun mkdir(dir: File): Boolean = dir.mkdir()

        @JvmStatic
        fun mkdirs(dir: File): Boolean = dir.mkdirs()

        @JvmStatic
        fun copyFileInBackground(context: Context, fileUri: Uri, rootPath: String): File {
            val fileName = Tools.getFileName(context, fileUri)
            val outputFile = File(rootPath, fileName)
            return copyFileInBackground(context, fileUri, outputFile)
        }

        @JvmStatic
        fun copyFileInBackground(context: Context, fileUri: Uri, outputFile: File): File {
            context.contentResolver.openInputStream(fileUri).use { inputStream ->
                FileUtils.copyInputStreamToFile(inputStream, outputFile)
            }
            return outputFile
        }

        @JvmStatic
        fun ensureValidFilename(name: String): String {
            return name.trim()
                .replace(INVALID_CHARACTERS_REGEX.toRegex(), "-")
                .let {
                    if (it.length > 255) it.substring(0, 255) else it
                }
        }

        @Throws(InvalidFilenameException::class)
        @JvmStatic
        fun checkFilenameValidity(name: String) {
            val illegalCharactersRegex = INVALID_CHARACTERS_REGEX.toRegex()

            val illegalCharacters = illegalCharactersRegex.findAll(name)
                .map { it.value }
                .distinct()
                .joinToString("")

            if (illegalCharacters.isNotEmpty()) {
                throw InvalidFilenameException(
                    "The filename contains illegal characters",
                    illegalCharacters
                )
            }

            if (name.length > 255) {
                throw InvalidFilenameException("Invalid filename length", name.length)
            }
        }

        @JvmStatic
        fun isFilenameInvalid(
            name: String,
            containsIllegalCharacters: (illegalCharacters: String) -> Unit,
            isInvalidLength: (invalidLength: Int) -> Unit
        ): Boolean {
            try {
                checkFilenameValidity(name)
            } catch (e: InvalidFilenameException) {
                if (e.containsIllegalCharacters()) {
                    containsIllegalCharacters(e.illegalCharacters)
                    return true
                } else if (e.isInvalidLength) {
                    isInvalidLength(e.invalidLength)
                    return true
                }
            }
            return false
        }

        @JvmStatic
        fun isFilenameInvalid(editText: EditText): Boolean {
            val text = editText.text.toString()
            return isFilenameInvalid(
                text,
                { illegalCharacters ->
                    editText.error = editText.context.getString(
                        R.string.generic_input_invalid_character,
                        illegalCharacters
                    )
                },
                { invalidLength ->
                    editText.error = editText.context.getString(
                        R.string.file_invalid_length,
                        invalidLength,
                        255
                    )
                }
            )
        }

        @JvmStatic
        fun getLatestFile(folderPath: String?, modifyTime: Int): File? {
            if (folderPath == null) return null
            return getLatestFile(File(folderPath), modifyTime.toLong())
        }

        @JvmStatic
        fun getLatestFile(folder: File?, modifyTime: Long): File? {
            if (folder == null || !folder.isDirectory) {
                return null
            }

            val files = folder.listFiles(FilenameFilter { _, name ->
                !name.startsWith(".")
            }) ?: return null

            if (files.isEmpty()) {
                return null
            }

            val latestFile = files.maxByOrNull { it.lastModified() } ?: return null

            if (modifyTime > 0) {
                val differenceSeconds =
                    (ZHTools.getCurrentTimeMillis() - latestFile.lastModified()) / 1000

                // Convert to seconds.
                if (differenceSeconds >= modifyTime) {
                    return null
                }
            }

            return latestFile
        }

        @JvmStatic
        fun shareFile(context: Context, file: File) {
            shareFile(context, file.name, file.absolutePath)
        }

        @JvmStatic
        fun shareFile(context: Context, fileName: String, filePath: String) {
            val contentUri = DocumentsContract.buildDocumentUri(
                context.getString(R.string.storageProviderAuthorities),
                filePath
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setDataAndType(contentUri, "*/*")
            }

            val chooserIntent = Intent.createChooser(shareIntent, fileName)
            context.startActivity(chooserIntent)
        }

        @JvmStatic
        @SuppressLint("UseCompatLoadingForDrawables")
        fun renameFileListener(context: Context, endTask: Task<*>?, file: File, suffix: String) {
            val parentPath = file.parent
            val fileName = file.name

            EditTextDialog.Builder(context)
                .setTitle(R.string.generic_rename)
                .setEditText(getFileNameWithoutExtension(fileName, suffix))
                .setAsRequired()
                .setConfirmListener(ConfirmListener { editBox, _ ->
                    val newName = editBox.text.toString()

                    if (isFilenameInvalid(editBox)) {
                        return@ConfirmListener false
                    }

                    if (fileName == newName) {
                        return@ConfirmListener true
                    }

                    val newFile = File(parentPath, newName + suffix)
                    if (newFile.exists()) {
                        editBox.error = context.getString(R.string.file_rename_exitis)
                        return@ConfirmListener false
                    }

                    val renamed = file.renameTo(newFile)
                    if (renamed) {
                        endTask?.execute()
                    }
                    true
                })
                .showDialog()
        }

        @JvmStatic
        @SuppressLint("UseCompatLoadingForDrawables")
        fun renameFileListener(context: Context, endTask: Task<*>?, file: File) {
            val parentPath = file.parent
            val fileName = file.name

            EditTextDialog.Builder(context)
                .setTitle(R.string.generic_rename)
                .setEditText(fileName)
                .setAsRequired()
                .setConfirmListener(ConfirmListener { editBox, _ ->
                    val newName = editBox.text.toString()

                    if (isFilenameInvalid(editBox)) {
                        return@ConfirmListener false
                    }

                    if (fileName == newName) {
                        return@ConfirmListener true
                    }

                    val newFile = File(parentPath, newName)
                    if (newFile.exists()) {
                        editBox.error = context.getString(R.string.file_rename_exitis)
                        return@ConfirmListener false
                    }

                    val renamed = renameFile(file, newFile)
                    if (renamed) {
                        endTask?.execute()
                    }
                    true
                })
                .showDialog()
        }

        @JvmStatic
        fun renameFile(origin: File, target: File): Boolean = origin.renameTo(target)

        @JvmStatic
        fun copyFile(file: File, target: File) {
            when {
                file.isFile -> FileUtils.copyFile(file, target)
                file.isDirectory -> FileUtils.copyDirectory(file, target)
            }
        }

        @JvmStatic
        fun moveFile(file: File, target: File) {
            when {
                file.isFile -> FileUtils.moveFile(file, target)
                file.isDirectory -> FileUtils.moveDirectory(file, target)
            }
        }

        @JvmStatic
        fun getFileNameWithoutExtension(fileName: String, fileExtension: String?): String {
            val dotIndex = if (fileExtension == null) {
                fileName.lastIndexOf('.')
            } else {
                fileName.lastIndexOf(fileExtension)
            }

            return if (dotIndex == -1) fileName else fileName.substring(0, dotIndex)
        }

        @JvmStatic
        fun getFileNameWithoutExtension(file: File): String = file.nameWithoutExtension

        @JvmStatic
        @SuppressLint("DefaultLocale")
        fun formatFileSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"

            val units = arrayOf("B", "KB", "MB", "GB")
            var unitIndex = 0
            var value = bytes.toDouble()

            // Keep dividing until an appropriate unit is found.
            while (value >= 1024 && unitIndex < units.size - 1) {
                value /= 1024.0
                unitIndex++
            }

            return String.format("%.2f %s", value, units[unitIndex])
        }

        @JvmStatic
        @Throws(IOException::class)
        fun zipDirectory(
            folder: File,
            parentPath: String,
            filter: (File) -> Boolean,
            zos: ZipOutputStream
        ) {
            val files = folder.listFiles()?.filter(filter) ?: return

            for (file in files) {
                if (file.isDirectory) {
                    zipDirectory(file, parentPath + file.name + "/", filter, zos)
                } else {
                    zipFile(file, parentPath + file.name, zos)
                }
            }
        }

        @JvmStatic
        @Throws(IOException::class)
        fun zipFile(file: File, entryName: String, zos: ZipOutputStream) {
            FileInputStream(file).use { fis ->
                val zipEntry = ZipEntry(entryName)
                zipEntry.time = file.lastModified() // Preserve the file's modified time.
                zos.putNextEntry(zipEntry)

                val buffer = ByteArray(4096)
                var length: Int
                while (fis.read(buffer).also { length = it } >= 0) {
                    zos.write(buffer, 0, length)
                }

                zos.closeEntry()
            }
        }

        @JvmStatic
        @Throws(Exception::class)
        fun calculateFileHash(file: File, algorithm: String = "SHA-256"): String {
            return calculateFileHash(file.inputStream(), algorithm)
        }

        @JvmStatic
        @Throws(Exception::class)
        fun calculateFileHash(inputStream: InputStream, algorithm: String = "SHA-256"): String {
            val digest = MessageDigest.getInstance(algorithm)

            inputStream.use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }

            return digest.digest().toHex()
        }

        /**
         * Convert a byte array to a hexadecimal string.
         */
        private fun ByteArray.toHex(): String {
            val hexChars = "0123456789abcdef"
            return joinToString("") { byte ->
                "${hexChars[byte.toInt() shr 4 and 0x0F]}${hexChars[byte.toInt() and 0x0F]}"
            }
        }
    }
}