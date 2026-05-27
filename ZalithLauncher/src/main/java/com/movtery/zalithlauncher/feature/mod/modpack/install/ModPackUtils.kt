package com.movtery.zalithlauncher.feature.mod.modpack.install

import android.app.Activity
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.utils.LauncherProfiles
import com.movtery.zalithlauncher.feature.download.item.ModLoaderWrapper
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.feature.mod.models.MCBBSPackMeta
import com.movtery.zalithlauncher.feature.mod.models.MmcPackMeta
import com.movtery.zalithlauncher.utils.runtime.SelectRuntimeUtils
import net.kdt.pojavlaunch.JavaGUILauncherActivity
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.modloaders.modpacks.models.CurseManifest
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModrinthIndex
import java.io.File
import java.util.zip.ZipFile

class ModPackUtils {
    companion object {
        /**
         * Content-first detection — opens the file as a zip and probes entries in priority order:
         * MCBBS → CurseForge → MultiMC/Prism → Modrinth → Loose mods
         *
         * The file extension is intentionally ignored so that:
         *  • .mrpack files renamed to .zip are still detected as MODRINTH
         *  • MultiMC .zip exports are detected correctly
         *  • Any container that holds a mods/ folder is surfaced as LOOSE_MODS
         */
        @JvmStatic
        fun determineModpack(modpack: File): ModPackInfo {
            runCatching {
                ZipFile(modpack).use { zipFile ->

                    // ── 1. MCBBS ──────────────────────────────────────────────────────
                    val mcbbsEntry = zipFile.getEntry("mcbbs.packmeta")
                    if (mcbbsEntry != null) {
                        val meta = Tools.GLOBAL_GSON.fromJson(
                            Tools.read(zipFile.getInputStream(mcbbsEntry)),
                            MCBBSPackMeta::class.java
                        )
                        if (verifyMCBBSPackMeta(meta))
                            return ModPackInfo(meta.name, ModPackEnum.MCBBS)
                    }

                    // ── 2. CurseForge ─────────────────────────────────────────────────
                    val curseEntry = zipFile.getEntry("manifest.json")
                    if (curseEntry != null) {
                        val manifest = Tools.GLOBAL_GSON.fromJson(
                            Tools.read(zipFile.getInputStream(curseEntry)),
                            CurseManifest::class.java
                        )
                        if (verifyManifest(manifest))
                            return ModPackInfo(manifest.name, ModPackEnum.CURSEFORGE)
                    }

                    // ── 3. MultiMC / Prism Launcher ───────────────────────────────────
                    // mmc-pack.json may be at the zip root or inside one wrapper folder
                    val mmcEntry = zipFile.entries().asSequence()
                        .firstOrNull { e ->
                            e.name == "mmc-pack.json" || e.name.endsWith("/mmc-pack.json")
                        }
                    if (mmcEntry != null) {
                        val mmcPack = Tools.GLOBAL_GSON.fromJson(
                            Tools.read(zipFile.getInputStream(mmcEntry)),
                            MmcPackMeta::class.java
                        )
                        if (verifyMmcPack(mmcPack)) {
                            // Try to read pack name from instance.cfg
                            val prefix = if (mmcEntry.name.contains('/'))
                                mmcEntry.name.substring(0, mmcEntry.name.lastIndexOf('/') + 1)
                            else ""
                            val cfgEntry = zipFile.getEntry("${prefix}instance.cfg")
                            val name = if (cfgEntry != null) {
                                Tools.read(zipFile.getInputStream(cfgEntry))
                                    .lines()
                                    .firstOrNull { it.startsWith("name=") }
                                    ?.substring("name=".length)
                            } else null
                            return ModPackInfo(name ?: modpack.nameWithoutExtension, ModPackEnum.MULTIMC)
                        }
                    }

                    // ── 4. Modrinth (.mrpack or .zip with modrinth.index.json) ─────────
                    val modrinthEntry = zipFile.getEntry("modrinth.index.json")
                    if (modrinthEntry != null) {
                        val index = Tools.GLOBAL_GSON.fromJson(
                            Tools.read(zipFile.getInputStream(modrinthEntry)),
                            ModrinthIndex::class.java
                        )
                        if (verifyModrinthIndex(index))
                            return ModPackInfo(index.name, ModPackEnum.MODRINTH)
                    }

                    // ── 5. Loose mods zip (mods/ folder but no manifest) ─────────────
                    val hasModsContent = zipFile.entries().asSequence().any { e ->
                        !e.isDirectory && (
                            e.name.startsWith("mods/") ||
                            e.name.contains("/mods/")
                        )
                    }
                    if (hasModsContent) {
                        return ModPackInfo(modpack.nameWithoutExtension, ModPackEnum.LOOSE_MODS)
                    }
                }
            }.onFailure { e ->
                Logging.e("determineModpack", "There was a problem checking the ModPack", e)
            }

            return ModPackInfo(null, ModPackEnum.UNKNOWN)
        }

        @JvmStatic
        fun verifyManifest(manifest: CurseManifest): Boolean {
            if ("minecraftModpack" != manifest.manifestType) return false
            if (manifest.manifestVersion != 1) return false
            if (manifest.minecraft == null) return false
            if (manifest.minecraft.version == null) return false
            if (manifest.minecraft.modLoaders == null) return false
            return manifest.minecraft.modLoaders.isNotEmpty()
        }

        @JvmStatic
        fun verifyModrinthIndex(modrinthIndex: ModrinthIndex): Boolean {
            if ("minecraft" != modrinthIndex.game) return false
            if (modrinthIndex.formatVersion != 1) return false
            return modrinthIndex.dependencies != null
        }

        fun verifyMCBBSPackMeta(mcbbsPackMeta: MCBBSPackMeta): Boolean {
            if ("minecraftModpack" != mcbbsPackMeta.manifestType) return false
            if (mcbbsPackMeta.manifestVersion != 2) return false
            if (mcbbsPackMeta.addons == null) return false
            if (mcbbsPackMeta.addons[0].id == null) return false
            return (mcbbsPackMeta.addons[0].version != null)
        }

        /**
         * A valid mmc-pack.json must have formatVersion == 1 and at least one component
         * with uid "net.minecraft".
         */
        fun verifyMmcPack(mmcPack: MmcPackMeta): Boolean {
            if (mmcPack.formatVersion != 1) return false
            if (mmcPack.components.isEmpty()) return false
            return mmcPack.components.any { it.uid == "net.minecraft" }
        }

        @JvmStatic
        @Throws(Throwable::class)
        fun startModLoaderInstall(modLoader: ModLoaderWrapper, activity: Activity, modInstallFile: File, customName: String) {
            modLoader.getInstallationIntent(activity, modInstallFile, customName)?.let { installIntent ->
                SelectRuntimeUtils.selectRuntime(activity, activity.getString(R.string.version_install_new_modloader, modLoader.modLoader.loaderName)) { jreName ->
                    LauncherProfiles.generateLauncherProfiles()
                    installIntent.putExtra(JavaGUILauncherActivity.EXTRAS_JRE_NAME, jreName)
                    activity.startActivity(installIntent)
                }
            }
        }
    }

    enum class ModPackEnum {
        UNKNOWN,
        CURSEFORGE,
        MCBBS,
        MODRINTH,
        /** MultiMC and Prism Launcher .zip exports (contain mmc-pack.json) */
        MULTIMC,
        /** Plain .zip files with a mods/ folder but no format manifest */
        LOOSE_MODS
    }
}
