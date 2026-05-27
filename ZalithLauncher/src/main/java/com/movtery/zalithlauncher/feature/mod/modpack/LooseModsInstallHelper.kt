package com.movtery.zalithlauncher.feature.mod.modpack

import android.content.Context
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.feature.download.item.ModLoaderWrapper
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.task.TaskExecutors
import com.movtery.zalithlauncher.ui.dialog.TipDialog
import net.kdt.pojavlaunch.utils.ZipUtils
import java.io.File
import java.util.zip.ZipFile

/**
 * Installs a "loose mods" modpack — a plain .zip that contains a mods/ folder but no format
 * manifest (no manifest.json, modrinth.index.json, mmc-pack.json, or mcbbs.packmeta).
 *
 * Supported folder names extracted to [targetPath]:
 *   mods/, config/, resourcepacks/, shaderpacks/, scripts/
 *
 * The zip may optionally wrap everything inside a single top-level directory.
 *
 * Because there is no manifest, the Minecraft version and mod loader are unknown.
 * After extraction a dialog informs the user that they must install the mod loader manually.
 * The function returns null (no [ModLoaderWrapper]) so the launcher does not attempt an
 * automatic mod-loader installation.
 */
class LooseModsInstallHelper {
    companion object {
        private const val TAG = "LooseModsInstallHelper"

        private val FOLDERS_TO_EXTRACT = listOf(
            "mods/",
            "config/",
            "resourcepacks/",
            "shaderpacks/",
            "scripts/"
        )

        /**
         * Extracts game folders from [packFile] into [targetPath] and shows a dialog reminding
         * the user to install a mod loader manually.
         *
         * @return null — no mod loader to auto-install.
         */
        @Throws(Exception::class)
        fun installZip(context: Context, packFile: File, targetPath: File): ModLoaderWrapper? {
            ZipFile(packFile).use { zipFile ->
                targetPath.mkdirs()

                // Detect optional single top-level wrapper folder.
                // e.g. "MyPack/mods/..." → prefix = "MyPack/"
                //      "mods/..."        → prefix = ""
                val modsEntry = zipFile.entries().asSequence()
                    .firstOrNull { entry ->
                        !entry.isDirectory &&
                        (entry.name.startsWith("mods/") || entry.name.contains("/mods/"))
                    }

                val prefix = if (modsEntry != null) {
                    val idx = modsEntry.name.indexOf("mods/")
                    if (idx > 0) modsEntry.name.substring(0, idx) else ""
                } else {
                    ""
                }

                Logging.i(TAG, "Extracting loose mods from zip prefix='$prefix' to $targetPath")

                for (folder in FOLDERS_TO_EXTRACT) {
                    ZipUtils.zipExtract(zipFile, "$prefix$folder", targetPath)
                }
            }

            // Inform the user that the mod loader must be installed manually.
            TaskExecutors.runInUIThread {
                TipDialog.Builder(context)
                    .setTitle(R.string.generic_warning)
                    .setMessage(R.string.modpack_loose_mods_no_loader)
                    .setCenterMessage(false)
                    .setShowCancel(false)
                    .setShowConfirm(true)
                    .showDialog()
            }

            return null
        }
    }
}
