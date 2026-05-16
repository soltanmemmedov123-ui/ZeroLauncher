package com.movtery.zalithlauncher.plugins.renderer

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.feature.update.UpdateUtils
import com.movtery.zalithlauncher.renderer.Renderers
import com.movtery.zalithlauncher.utils.path.PathManager
import com.movtery.zalithlauncher.utils.stringutils.StringUtilsKt
import net.kdt.pojavlaunch.Architecture
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.utils.ZipUtils
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile

/**
 * Renderer plugin manager for Zalith Launcher.
 * Also supports loading local renderer plugins.
 *
 * Reference:
 * [FCL Renderer Plugin](https://github.com/FCL-Team/FCLRendererPlugin)
 */
object RendererPluginManager {
    private val rendererPluginList = mutableListOf<RendererPlugin>()
    private val apkRendererPluginList = mutableListOf<ApkRendererPlugin>()
    private val localRendererPluginList = mutableListOf<LocalRendererPlugin>()

    /**
     * Returns all renderers loaded by renderer plugins.
     */
    @JvmStatic
    fun getRendererList(): List<RendererPlugin> = rendererPluginList

    /**
     * Removes specific loaded renderers.
     */
    @JvmStatic
    fun removeRenderer(rendererPlugins: Collection<RendererPlugin>) {
        rendererPluginList.removeAll(rendererPlugins)
    }

    /**
     * Returns all renderers loaded from local renderer plugins.
     */
    @JvmStatic
    fun getAllLocalRendererList(): List<LocalRendererPlugin> = localRendererPluginList

    /**
     * Returns true if at least one renderer plugin is available.
     */
    @JvmStatic
    fun isAvailable(): Boolean = rendererPluginList.isNotEmpty()

    /**
     * Returns the currently selected renderer plugin.
     *
     * This matches the unique identifier of the renderer selected by the global
     * renderer manager.
     */
    @JvmStatic
    val selectedRendererPlugin: RendererPlugin?
        get() {
            val currentRenderer = runCatching {
                Renderers.getCurrentRenderer().getUniqueIdentifier()
            }.getOrNull()

            return rendererPluginList.find { it.uniqueIdentifier == currentRenderer }
        }

    /**
     * Clears all loaded renderer plugins.
     */
    fun clearPlugin() {
        rendererPluginList.clear()
        apkRendererPluginList.clear()
        localRendererPluginList.clear()
    }

    /**
     * Clears only renderer plugins loaded from installed APKs.
     *
     * Local renderer plugins are preserved.
     */
    @JvmStatic
    fun clearApkPlugins() {
        rendererPluginList.removeAll(apkRendererPluginList.toSet())
        apkRendererPluginList.clear()
    }

    /**
     * Reloads renderer plugins from installed APKs.
     *
     * This is useful when returning to the launcher after installing or
     * uninstalling a renderer plugin without fully restarting the app.
     */
    @JvmStatic
    fun reloadInstalledRendererPlugins(context: Context) {
        clearApkPlugins()

        val packageManager = context.packageManager
        val installedApps = runCatching {
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        }.getOrElse { e ->
            Logging.e("RendererPluginManager", "Failed to read installed applications", e)
            return
        }

        installedApps.forEach { info ->
            runCatching {
                parseApkPlugin(context, info)
            }.onFailure { e ->
                Logging.e(
                    "RendererPluginManager",
                    "Failed to parse renderer plugin package: ${info.packageName}",
                    e
                )
            }
        }
    }

    /**
     * Returns true if the given package is installed right now.
     */
    @JvmStatic
    fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Returns the current renderer plugin if it has configurable options
     * (software-style plugin / whitelisted package name).
     */
    @JvmStatic
    fun getConfigurablePluginOrNull(rendererUniqueIdentifier: String): RendererPlugin? {
        val renderer = apkRendererPluginList.find {
            it.uniqueIdentifier == rendererUniqueIdentifier
        }

        return renderer?.takeIf {
            it.packageName in setOf(
                "com.bzlzhh.plugin.ngg",
                "com.bzlzhh.plugin.ngg.angleless",
                "com.fcl.plugin.mobileglues"
            )
        }
    }

    /**
     * Parses Zalith Launcher renderer plugins from installed APKs.
     */
    fun parseApkPlugin(context: Context, info: ApplicationInfo) {
        if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0) return

        val metaData = info.metaData ?: return
        val isRendererPlugin =
            metaData.getBoolean("fclPlugin", false) ||
                    metaData.getBoolean("zalithRendererPlugin", false)
        if (!isRendererPlugin) return

        val rendererString = metaData.getString("renderer") ?: return
        val description = metaData.getString("des") ?: return
        val pojavEnvString = metaData.getString("pojavEnv") ?: return
        val nativeLibraryDir = info.nativeLibraryDir
        val renderer = rendererString.split(":")
        if (renderer.size < 3) return

        var rendererId = renderer[0]
        val envList = mutableMapOf<String, String>()
        val dlopenList = mutableListOf<String>()

        pojavEnvString.split(":").forEach { envString ->
            if (!envString.contains("=")) return@forEach

            val stringList = envString.split("=", limit = 2)
            val key = stringList[0]
            val value = stringList[1]

            when (key) {
                "POJAV_RENDERER" -> rendererId = value
                "DLOPEN" -> {
                    value.split(",").forEach { lib ->
                        dlopenList.add(lib)
                    }
                }
                "LIB_MESA_NAME", "MESA_LIBRARY" -> {
                    envList[key] = "$nativeLibraryDir/$value"
                }
                else -> envList[key] = value
            }
        }

        val packageName = info.packageName
        val appLabel = runCatching {
            context.packageManager.getApplicationLabel(info)
        }.getOrElse {
            context.getString(R.string.generic_unknown)
        }

        val plugin = ApkRendererPlugin(
            rendererId,
            "$description (${context.getString(R.string.setting_renderer_from_plugins, appLabel)})",
            packageName,
            renderer[1],
            renderer[2].resolveEglName(nativeLibraryDir),
            nativeLibraryDir,
            envList,
            dlopenList,
            packageName
        )

        rendererPluginList.add(plugin)
        apkRendererPluginList.add(plugin)
    }

    /**
     * Attempts to parse a renderer plugin from a local directory under
     * `/files/renderer_plugins/`.
     *
     * @return true if the plugin is valid and meets the required format.
     *
     * Expected renderer plugin folder structure:
     * renderer_plugins/
     * └── plugin_folder/
     *     ├── config                (stores renderer configuration)
     *     └── libs/
     *         ├── arm64-v8a/        (arm64)
     *         │   └── renderer.so
     *         ├── armeabi-v7a/      (arm32)
     *         │   └── renderer.so
     *         ├── x86/              (x86)
     *         │   └── renderer.so
     *         └── x86_64/           (x86_64)
     *             └── renderer.so
     */
    fun parseLocalPlugin(context: Context, directory: File): Boolean {
        val archModel = UpdateUtils.getArchModel(Architecture.getDeviceArchitecture())
            ?: return false

        val libsDirectory = File(directory, "libs/$archModel")
            .takeIf { it.exists() && it.isDirectory }
            ?: return false

        val rendererConfigFile = File(directory, "config")
            .takeIf { it.exists() && it.isFile }
            ?: return false

        val rendererConfig = runCatching {
            Tools.GLOBAL_GSON.fromJson(
                readLocalRendererPluginConfig(rendererConfigFile),
                RendererConfig::class.java
            )
        }.getOrElse { e ->
            Logging.e("LocalRendererPlugin", "Failed to parse the configuration file", e)
            return false
        }

        val uniqueIdentifier = directory.name

        rendererConfig.run {
            val libPath = libsDirectory.absolutePath

            val plugin = LocalRendererPlugin(
                rendererId,
                "$rendererDisplayName (${context.getString(R.string.setting_renderer_from_plugins, uniqueIdentifier)})",
                uniqueIdentifier,
                glName,
                eglName.resolveEglName(libPath),
                libPath,
                pojavEnv.filter { it.key != "POJAV_RENDERER" },
                dlopenList ?: emptyList(),
                directory
            )

            rendererPluginList.add(plugin)
            localRendererPluginList.add(plugin)
        }

        return true
    }

    private fun String.resolveEglName(libPath: String): String {
        return if (startsWith("/")) "$libPath$this" else this
    }

    private fun readLocalRendererPluginConfig(configFile: File): String {
        return FileInputStream(configFile).use { fileInputStream ->
            DataInputStream(fileInputStream).use { dataInputStream ->
                dataInputStream.readUTF()
            }
        }
    }

    /**
     * Imports a local renderer plugin package.
     */
    fun importLocalRendererPlugin(pluginFile: File): Boolean {
        if (!pluginFile.exists() || !pluginFile.isFile) {
            Logging.i(
                "importLocalRendererPlugin",
                "The compressed file does not exist or is not a valid file."
            )
            return false
        }

        return try {
            ZipFile(pluginFile).use { pluginZip ->
                val configEntry = pluginZip.entries().asSequence().find { it.name == "config" }
                    ?: throw IllegalArgumentException("The plugin package does not meet the requirements!")

                pluginZip.getInputStream(configEntry).use { inputStream ->
                    DataInputStream(inputStream).use { dataInputStream ->
                        val configContent = dataInputStream.readUTF()
                        Tools.GLOBAL_GSON.fromJson(configContent, RendererConfig::class.java)
                    }
                }

                val pluginFolder = File(
                    PathManager.DIR_INSTALLED_RENDERER_PLUGIN,
                    StringUtilsKt.generateUniqueUUID(
                        { string -> string.replace("-", "").substring(0, 8) },
                        { uuid ->
                            File(PathManager.DIR_INSTALLED_RENDERER_PLUGIN, uuid).exists()
                        }
                    )
                )

                ZipUtils.zipExtract(pluginZip, "", pluginFolder)
            }
            true
        } catch (e: Exception) {
            Logging.i("importLocalRendererPlugin", "Error: ${e.message}")
            false
        }
    }
}
