package com.movtery.zalithlauncher.feature.mod.modpack.install

import android.content.Context
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.feature.download.item.ModLoaderWrapper
import com.movtery.zalithlauncher.feature.download.platform.curseforge.CurseForgeModPackInstallHelper
import com.movtery.zalithlauncher.feature.download.platform.modrinth.ModrinthModPackInstallHelper
import com.movtery.zalithlauncher.feature.download.utils.PlatformUtils
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.feature.mod.models.MCBBSPackMeta
import com.movtery.zalithlauncher.feature.mod.modpack.LooseModsInstallHelper
import com.movtery.zalithlauncher.feature.mod.modpack.MCBBSModPack
import com.movtery.zalithlauncher.feature.mod.modpack.MultiMCModPackInstallHelper
import com.movtery.zalithlauncher.feature.mod.modpack.install.ModPackUtils.ModPackEnum
import com.movtery.zalithlauncher.feature.version.VersionConfig
import com.movtery.zalithlauncher.feature.version.VersionsManager
import com.movtery.zalithlauncher.task.TaskExecutors
import com.movtery.zalithlauncher.ui.dialog.TipDialog
import com.movtery.zalithlauncher.utils.stringutils.StringUtils
import net.kdt.pojavlaunch.Tools
import org.apache.commons.io.FileUtils
import java.io.File
import java.util.zip.ZipFile

class InstallLocalModPack {
    companion object {
        @JvmStatic
        @Throws(Exception::class)
        fun installModPack(
            context: Context,
            type: ModPackEnum?,
            zipFile: File,
            customVersionName: String
        ): ModLoaderWrapper? {
            try {
                runCatching {
                    ZipFile(zipFile)
                }.getOrElse {
                    Logging.e("Install local ModPack", "This file doesn't seem to be a proper archive", it)
                    TaskExecutors.runInUIThread {
                        showUnSupportDialog(context)
                    }
                    return null
                }.use { modpackZipFile ->
                    val modLoader: ModLoaderWrapper?
                    val versionPath = VersionsManager.getVersionPath(customVersionName)

                    when (type) {
                        ModPackEnum.CURSEFORGE -> {
                            modLoader = curseforgeModPack(context, zipFile, versionPath) ?: return null
                            VersionConfig.createIsolation(versionPath).save()
                            return modLoader
                        }

                        ModPackEnum.MCBBS -> {
                            val mcbbsEntry = modpackZipFile.getEntry("mcbbs.packmeta")

                            val mcbbsPackMeta = Tools.GLOBAL_GSON.fromJson(
                                Tools.read(
                                    modpackZipFile.getInputStream(mcbbsEntry)
                                ), MCBBSPackMeta::class.java
                            )

                            modLoader = mcbbsModPack(context, zipFile, versionPath) ?: return null
                            VersionConfig.createIsolation(versionPath).apply {
                                setJavaArgs(StringUtils.insertSpace(null, *mcbbsPackMeta.launchInfo.javaArgument))
                            }.save()
                            return modLoader
                        }

                        ModPackEnum.MODRINTH -> {
                            modLoader = modrinthModPack(zipFile, versionPath) ?: return null
                            VersionConfig.createIsolation(versionPath).save()
                            return modLoader
                        }

                        ModPackEnum.MULTIMC -> {
                            modLoader = multiMCModPack(zipFile, versionPath)
                            VersionConfig.createIsolation(versionPath).save()
                            return modLoader
                        }

                        ModPackEnum.LOOSE_MODS -> {
                            modLoader = looseModsModPack(context, zipFile, versionPath)
                            VersionConfig.createIsolation(versionPath).save()
                            return modLoader
                        }

                        else -> {
                            TaskExecutors.runInUIThread {
                                showUnSupportDialog(context)
                            }
                            return null
                        }
                    }
                }
            } finally {
                FileUtils.deleteQuietly(zipFile)
            }
        }

        @JvmStatic
        fun showUnSupportDialog(context: Context) {
            TipDialog.Builder(context)
                .setTitle(R.string.generic_warning)
                .setMessage(R.string.select_modpack_local_not_supported)
                .setWarning()
                .setShowCancel(true)
                .setShowConfirm(false)
                .showDialog()
        }

        @Throws(Exception::class)
        private fun curseforgeModPack(
            context: Context,
            zipFile: File,
            versionPath: File
        ): ModLoaderWrapper? {
            return CurseForgeModPackInstallHelper.installZip(
                PlatformUtils.createCurseForgeApi(),
                zipFile,
                versionPath,
                context = context
            )
        }

        @Throws(Exception::class)
        private fun modrinthModPack(
            zipFile: File,
            versionPath: File
        ): ModLoaderWrapper? {
            return ModrinthModPackInstallHelper.installZip(
                zipFile,
                versionPath
            )
        }

        @Throws(Exception::class)
        private fun mcbbsModPack(context: Context, zipFile: File, versionPath: File): ModLoaderWrapper? {
            val mcbbsModPack = MCBBSModPack(context, zipFile)
            return mcbbsModPack.install(versionPath)
        }

        @Throws(Exception::class)
        private fun multiMCModPack(zipFile: File, versionPath: File): ModLoaderWrapper? {
            return MultiMCModPackInstallHelper.installZip(zipFile, versionPath)
        }

        @Throws(Exception::class)
        private fun looseModsModPack(context: Context, zipFile: File, versionPath: File): ModLoaderWrapper? {
            return LooseModsInstallHelper.installZip(context, zipFile, versionPath)
        }
    }
}
