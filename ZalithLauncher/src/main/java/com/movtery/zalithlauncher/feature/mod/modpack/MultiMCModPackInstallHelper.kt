package com.movtery.zalithlauncher.feature.mod.modpack

import com.movtery.zalithlauncher.feature.download.enums.ModLoader
import com.movtery.zalithlauncher.feature.download.item.ModLoaderWrapper
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.feature.mod.models.MmcPackMeta
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.utils.ZipUtils
import java.io.File
import java.util.zip.ZipFile

/**
 * Installs modpacks exported from MultiMC or Prism Launcher (.zip files containing mmc-pack.json).
 *
 * Expected zip structure (top-level folder wrapper is optional):
 *
 *   [InstanceName/]
 *     mmc-pack.json
 *     instance.cfg          ← optional, used to read the pack name
 *     .minecraft/           ← game files (may also be named "minecraft/")
 *       mods/
 *       config/
 *       ...
 *
 * No API calls are required — all game files are bundled inside the zip.
 */
class MultiMCModPackInstallHelper {
    companion object {
        private const val TAG = "MultiMCModPackInstallHelper"

        /**
         * Extracts game files from a MultiMC/Prism zip into [targetPath] and returns a
         * [ModLoaderWrapper] describing the mod loader that still needs to be installed.
         * Returns null if no mod loader component was found (vanilla instance).
         */
        @Throws(Exception::class)
        fun installZip(packFile: File, targetPath: File): ModLoaderWrapper? {
            ZipFile(packFile).use { zipFile ->

                // ── Locate mmc-pack.json ────────────────────────────────────────────────
                // It can be at the zip root ("mmc-pack.json") or inside a single wrapper
                // folder ("InstanceName/mmc-pack.json").
                val mmcEntry = zipFile.entries().asSequence()
                    .firstOrNull { entry ->
                        entry.name == "mmc-pack.json" || entry.name.endsWith("/mmc-pack.json")
                    } ?: run {
                        Logging.e(TAG, "mmc-pack.json not found in zip")
                        return null
                    }

                val mmcPack = Tools.GLOBAL_GSON.fromJson(
                    Tools.read(zipFile.getInputStream(mmcEntry)),
                    MmcPackMeta::class.java
                ) ?: run {
                    Logging.e(TAG, "Failed to parse mmc-pack.json")
                    return null
                }

                Logging.i(TAG, "Parsed mmc-pack.json: ${mmcPack.components.size} components, formatVersion=${mmcPack.formatVersion}")

                // ── Determine the path prefix for game files ────────────────────────────
                // If mmc-pack.json lives at "InstanceName/mmc-pack.json" the instance
                // prefix is "InstanceName/".
                val instancePrefix = if (mmcEntry.name.contains('/')) {
                    mmcEntry.name.substring(0, mmcEntry.name.lastIndexOf('/') + 1)
                } else {
                    ""
                }

                // Game folder is ".minecraft/" by convention; some older packs use "minecraft/"
                val dotMinecraftPrefix = "${instancePrefix}.minecraft/"
                val minecraftPrefix    = "${instancePrefix}minecraft/"

                val hasDotMinecraft = zipFile.entries().asSequence()
                    .any { it.name.startsWith(dotMinecraftPrefix) && !it.isDirectory }
                val gamePrefix = if (hasDotMinecraft) dotMinecraftPrefix else minecraftPrefix

                Logging.i(TAG, "Extracting game files from '$gamePrefix' → $targetPath")
                targetPath.mkdirs()
                ZipUtils.zipExtract(zipFile, gamePrefix, targetPath)

                return createModLoaderWrapper(mmcPack)
            }
        }

        /**
         * Reads the pack name from instance.cfg ("name=…" line).
         * Returns null if the entry is absent or the key is missing.
         */
        fun readInstanceName(packFile: File): String? {
            return runCatching {
                ZipFile(packFile).use { zipFile ->
                    val mmcEntry = zipFile.entries().asSequence()
                        .firstOrNull { it.name == "mmc-pack.json" || it.name.endsWith("/mmc-pack.json") }
                        ?: return null

                    val instancePrefix = if (mmcEntry.name.contains('/'))
                        mmcEntry.name.substring(0, mmcEntry.name.lastIndexOf('/') + 1)
                    else ""

                    val cfgEntry = zipFile.getEntry("${instancePrefix}instance.cfg") ?: return null
                    Tools.read(zipFile.getInputStream(cfgEntry))
                        .lines()
                        .firstOrNull { it.startsWith("name=") }
                        ?.substring("name=".length)
                }
            }.getOrNull()
        }

        // ── Private helpers ──────────────────────────────────────────────────────────

        private fun createModLoaderWrapper(mmcPack: MmcPackMeta): ModLoaderWrapper? {
            var mcVersion: String?   = null
            var modLoader: ModLoader? = null
            var loaderVersion: String? = null

            for (component in mmcPack.components) {
                when (component.uid) {
                    "net.minecraft"                    -> mcVersion     = component.version
                    "net.fabricmc.fabric-loader"       -> { modLoader = ModLoader.FABRIC;   loaderVersion = component.version }
                    "net.minecraftforge"               -> { modLoader = ModLoader.FORGE;    loaderVersion = component.version }
                    "net.neoforged.neoforge"           -> { modLoader = ModLoader.NEOFORGE; loaderVersion = component.version }
                    "org.quiltmc.quilt-loader"         -> { modLoader = ModLoader.QUILT;    loaderVersion = component.version }
                }
            }

            if (mcVersion == null) {
                Logging.e(TAG, "No net.minecraft component found in mmc-pack.json")
                return null
            }

            if (modLoader == null || loaderVersion == null) {
                Logging.i(TAG, "No mod loader component found — treating as vanilla install (mc=$mcVersion)")
                return null
            }

            Logging.i(TAG, "ModLoader: $modLoader $loaderVersion for mc $mcVersion")
            return ModLoaderWrapper(modLoader, loaderVersion, mcVersion)
        }
    }
}
