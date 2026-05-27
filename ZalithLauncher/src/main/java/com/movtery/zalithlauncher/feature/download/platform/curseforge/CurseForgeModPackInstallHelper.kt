package com.movtery.zalithlauncher.feature.download.platform.curseforge

import android.content.Context
import com.kdt.mcgui.ProgressLayout
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.context.ContextExecutor
import com.movtery.zalithlauncher.feature.download.enums.ModLoader
import com.movtery.zalithlauncher.feature.download.install.InstallHelper
import com.movtery.zalithlauncher.feature.download.item.ModLoaderWrapper
import com.movtery.zalithlauncher.feature.download.item.VersionItem
import com.movtery.zalithlauncher.feature.download.platform.curseforge.CurseForgeCommonUtils.Companion.getDownloadSha1
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.feature.mod.modpack.install.ModPackUtils
import com.movtery.zalithlauncher.task.TaskExecutors
import com.movtery.zalithlauncher.ui.dialog.TipDialog
import com.movtery.zalithlauncher.utils.ZHTools
import com.movtery.zalithlauncher.utils.file.FileTools
import com.movtery.zalithlauncher.utils.stringutils.StringUtils
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.modloaders.modpacks.api.ApiHandler
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModDownloader
import net.kdt.pojavlaunch.modloaders.modpacks.models.CurseManifest
import net.kdt.pojavlaunch.modloaders.modpacks.models.CurseManifest.CurseMinecraft
import net.kdt.pojavlaunch.modloaders.modpacks.models.CurseManifest.CurseModLoader
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper
import net.kdt.pojavlaunch.tasks.SpeedCalculator
import net.kdt.pojavlaunch.utils.FileUtils
import net.kdt.pojavlaunch.utils.ZipUtils
import java.io.File
import java.util.Collections
import java.util.zip.ZipFile
import kotlin.math.max

class CurseForgeModPackInstallHelper {
    companion object {
        @Throws(Exception::class)
        fun startInstall(api: ApiHandler, versionItem: VersionItem, customName: String): ModLoaderWrapper? {
            return InstallHelper.installModPack(versionItem, customName) { modpackFile, targetPath ->
                installZip(api, modpackFile, targetPath, context = null)
            }
        }

        /**
         * Installs a CurseForge modpack zip.
         *
         * @param context Optional Android context used to show the restricted-mods dialog.
         *                If null the dialog is shown via [ContextExecutor].
         */
        @Throws(Exception::class)
        fun installZip(api: ApiHandler, zipFile: File, targetPath: File, context: Context? = null): ModLoaderWrapper? {
            ZipFile(zipFile).use { modpackZipFile ->
                val curseManifest = Tools.GLOBAL_GSON.fromJson(
                    Tools.read(ZipUtils.getEntryStream(modpackZipFile, "manifest.json")),
                    CurseManifest::class.java
                )
                if (!ModPackUtils.verifyManifest(curseManifest)) {
                    Logging.i("CurseForgeModPackInstallHelper", "manifest verification failed")
                    return null
                }
                var progressUpdateTime = 0L
                val speedCalculator = SpeedCalculator()

                // skippedFiles is populated (thread-safely) by getModDownloader for every
                // required mod whose download URL returns null (CF distribution-restricted).
                val skippedFiles = Collections.synchronizedList(mutableListOf<CurseManifest.CurseFile>())

                val modDownloader: ModDownloader = getModDownloader(api, targetPath, curseManifest, skippedFiles)
                modDownloader.awaitFinish { count: Int, totalCount: Int, downloadedSize: Long ->
                    val currentTime = ZHTools.getCurrentTimeMillis()
                    if (currentTime - progressUpdateTime < 150) return@awaitFinish
                    progressUpdateTime = currentTime

                    ProgressKeeper.submitProgress(
                        ProgressLayout.INSTALL_RESOURCE,
                        max((count.toFloat() / totalCount * 100).toDouble(), 0.0).toInt(),
                        R.string.modpack_download_downloading_mods_fc,
                        count,
                        FileTools.formatFileSize(downloadedSize),
                        totalCount,
                        FileTools.formatFileSize(speedCalculator.feed(downloadedSize))
                    )
                }

                val overridesDir: String = curseManifest.overrides ?: "overrides"
                ZipUtils.zipExtract(modpackZipFile, overridesDir, targetPath)

                // Show dialog listing any mods the user must download manually
                if (skippedFiles.isNotEmpty()) {
                    showRestrictedModsDialog(context, skippedFiles)
                }

                return createInfo(curseManifest.minecraft)
            }
        }

        @Throws(Exception::class)
        private fun getModDownloader(
            api: ApiHandler,
            instanceDestination: File,
            curseManifest: CurseManifest,
            skippedFiles: MutableList<CurseManifest.CurseFile>
        ): ModDownloader {
            val modDownloader = ModDownloader(File(instanceDestination, "mods"), true)
            val fileCount = curseManifest.files.size
            for (i in 0 until fileCount) {
                val curseFile = curseManifest.files[i]
                modDownloader.submitDownload {
                    val url = CurseForgeCommonUtils.getDownloadUrl(api, curseFile.projectID, curseFile.fileID)
                    if (url == null) {
                        if (curseFile.required) {
                            // Do NOT crash — collect for the manual-download dialog instead
                            Logging.w(
                                "CurseForgeModPackInstallHelper",
                                "Restricted mod skipped: projectID=${curseFile.projectID} fileID=${curseFile.fileID}"
                            )
                            skippedFiles.add(curseFile)
                        }
                        return@submitDownload null
                    }
                    ModDownloader.FileInfo(url, FileUtils.getFileName(url), getDownloadSha1(api, curseFile.projectID, curseFile.fileID))
                }
            }
            return modDownloader
        }

        /**
         * Shows a dialog listing mods that could not be downloaded because of CurseForge API
         * restrictions. The user can copy the CurseForge URLs and download them manually.
         */
        private fun showRestrictedModsDialog(
            context: Context?,
            skippedFiles: List<CurseManifest.CurseFile>
        ) {
            val urlList = skippedFiles.joinToString(separator = "\n") { file ->
                "• https://www.curseforge.com/minecraft/mc-mods/${file.projectID}/files/${file.fileID}"
            }

            val showDialog: (Context) -> Unit = { ctx ->
                val message = ctx.getString(
                    R.string.modpack_curseforge_restricted_message,
                    skippedFiles.size,
                    urlList
                )
                TipDialog.Builder(ctx)
                    .setTitle(R.string.modpack_curseforge_restricted_title)
                    .setMessage(message)
                    .setCenterMessage(false)
                    .setSelectable(true)
                    .setShowCancel(false)
                    .setShowConfirm(true)
                    .showDialog()
            }

            if (context != null) {
                TaskExecutors.runInUIThread { showDialog(context) }
            } else {
                ContextExecutor.executeTaskWithAllContext { ctx -> showDialog(ctx) }
            }
        }

        private fun createInfo(minecraft: CurseMinecraft): ModLoaderWrapper? {
            var primaryModLoader: CurseModLoader? = null
            for (modLoader in minecraft.modLoaders) {
                if (modLoader.primary) {
                    primaryModLoader = modLoader
                    break
                }
            }
            if (primaryModLoader == null) primaryModLoader = minecraft.modLoaders[0]
            val modLoaderId = primaryModLoader!!.id
            val dashIndex = modLoaderId.indexOf('-')
            val modLoaderName = modLoaderId.substring(0, dashIndex)
            val modLoaderVersion = modLoaderId.substring(dashIndex + 1)
            Logging.i("CurseForgeModPackInstallHelper",
                StringUtils.insertSpace(modLoaderId, modLoaderName, modLoaderVersion)
            )
            val modloader: ModLoader
            when (modLoaderName) {
                "forge" -> {
                    Logging.i("ModLoader", "Forge, or Quilt? ...")
                    modloader = ModLoader.FORGE
                }
                "neoforge" -> {
                    Logging.i("ModLoader", "NeoForge")
                    modloader = ModLoader.NEOFORGE
                }
                "fabric" -> {
                    Logging.i("ModLoader", "Fabric")
                    modloader = ModLoader.FABRIC
                }
                else -> return null
            }
            return ModLoaderWrapper(modloader, modLoaderVersion, minecraft.version)
        }
    }
}