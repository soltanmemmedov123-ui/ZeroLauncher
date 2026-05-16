package com.movtery.zalithlauncher.utils.path

import android.content.Context
import android.os.Environment
import androidx.core.content.ContextCompat
import com.movtery.zalithlauncher.InfoDistributor
import org.apache.commons.io.FileUtils
import java.io.File

class PathManager {
    companion object {
        lateinit var DIR_NATIVE_LIB: String
        lateinit var DIR_FILE: File
        lateinit var DIR_DATA: String
        lateinit var DIR_CACHE: File
        lateinit var DIR_MULTIRT_HOME: String

        @JvmField
        var DIR_GAME_HOME: String = ""

        lateinit var DIR_LAUNCHER_LOG: String
        lateinit var DIR_CTRLMAP_PATH: String
        lateinit var DIR_ACCOUNT_NEW: String
        lateinit var DIR_CACHE_STRING: String
        lateinit var DIR_ADDONS_INFO_CACHE: String

        @JvmField
        var DIR_RUNTIME_MOD: File? = null

        lateinit var DIR_CUSTOM_MOUSE: String
        lateinit var DIR_BACKGROUND: File
        lateinit var DIR_APP_CACHE: File
        lateinit var DIR_USER_SKIN: File
        lateinit var DIR_INSTALLED_RENDERER_PLUGIN: File

        lateinit var FILE_SETTINGS: File
        lateinit var FILE_PROFILE_PATH: File
        lateinit var FILE_CTRLDEF_FILE: String
        lateinit var FILE_VERSION_LIST: String
        lateinit var FILE_NEWBIE_GUIDE: File

        @JvmStatic
        fun initContextConstants(context: Context) {
            DIR_NATIVE_LIB = context.applicationInfo.nativeLibraryDir
            DIR_FILE = context.filesDir
            DIR_DATA = DIR_FILE.parent ?: ""
            DIR_CACHE = context.cacheDir
            DIR_MULTIRT_HOME = "$DIR_DATA/runtimes"

            // Keep launcher-managed support files in the app-scoped launcher home.
            DIR_GAME_HOME = getLauncherHome(context).absolutePath

            DIR_LAUNCHER_LOG = "$DIR_GAME_HOME/launcher_log"
            DIR_CTRLMAP_PATH = "$DIR_GAME_HOME/controlmap"
            DIR_ACCOUNT_NEW = "$DIR_FILE/accounts"
            DIR_CACHE_STRING = "$DIR_CACHE/string_cache"
            DIR_ADDONS_INFO_CACHE = "$DIR_CACHE/addons_info_cache"
            DIR_CUSTOM_MOUSE = "$DIR_GAME_HOME/mouse"
            DIR_BACKGROUND = File("$DIR_GAME_HOME/background")
            DIR_APP_CACHE = context.externalCacheDir ?: context.cacheDir
            DIR_USER_SKIN = File(DIR_FILE, "user_skin")
            DIR_INSTALLED_RENDERER_PLUGIN = File(DIR_FILE, "renderer_plugins")
            DIR_RUNTIME_MOD = context.getDir("runtime_mod", 0)?.also { it.mkdirs() }

            FILE_PROFILE_PATH = File(DIR_DATA, "profile_path.json")
            FILE_CTRLDEF_FILE = "$DIR_GAME_HOME/controlmap/default.json"
            FILE_VERSION_LIST = "$DIR_DATA/version_list.json"
            FILE_NEWBIE_GUIDE = File(DIR_DATA, "newbie_guide.json")
            FILE_SETTINGS = File(DIR_FILE, "launcher_settings.json")

            createRequiredDirectories()

            runCatching {
                FileUtils.deleteQuietly(File("$DIR_DATA/accounts"))
                FileUtils.deleteQuietly(File(DIR_DATA, "user_skin"))
            }
        }

        @JvmStatic
        fun getLauncherHome(context: Context): File {
            return context.getExternalFilesDir(null)
                ?: File(context.filesDir, InfoDistributor.LAUNCHER_NAME)
        }

        private fun createRequiredDirectories() {
            File(DIR_GAME_HOME).mkdirs()
            File(DIR_LAUNCHER_LOG).mkdirs()
            File(DIR_CTRLMAP_PATH).mkdirs()
            File(DIR_CUSTOM_MOUSE).mkdirs()
            DIR_BACKGROUND.mkdirs()
            DIR_INSTALLED_RENDERER_PLUGIN.mkdirs()
            DIR_USER_SKIN.mkdirs()
        }

        /**
         * Returns all visible shared storage roots, including removable storage when available.
         * This keeps the existing app-external-dir based discovery, but also adds a
         * direct /storage scan fallback for USB/OTG drives that some Android 10 devices
         * do not expose through getExternalFilesDirs().
         */
        @JvmStatic
        fun getStorageRoots(context: Context): List<File> {
            val roots = mutableListOf<File>()
            val appExternalDirs = ContextCompat.getExternalFilesDirs(context, null)

            appExternalDirs.forEach { dir ->
                val root = getStorageRootFromAppExternalDir(dir) ?: return@forEach
                if (root.exists() && root.canRead() && roots.none { it.absolutePath == root.absolutePath }) {
                    roots.add(root)
                }
            }

            getDirectMountedStorageRoots().forEach { root ->
                if (root.exists() && root.canRead() && roots.none { it.absolutePath == root.absolutePath }) {
                    roots.add(root)
                }
            }

            if (roots.isEmpty()) {
                val fallback = Environment.getExternalStorageDirectory()
                if (fallback.exists() && fallback.canRead()) {
                    roots.add(fallback)
                }
            }

            return roots
        }

        @JvmStatic
        fun getPrimaryStorageRoot(context: Context): File {
            val emulated = getStorageRoots(context).firstOrNull {
                it.absolutePath == Environment.getExternalStorageDirectory().absolutePath
            }
            return emulated
                ?: getStorageRoots(context).firstOrNull()
                ?: Environment.getExternalStorageDirectory()
        }

        @JvmStatic
        fun getRemovableStorageRoot(context: Context): File? {
            val appExternalDirs = ContextCompat.getExternalFilesDirs(context, null)

            appExternalDirs.forEach { dir ->
                if (dir == null) return@forEach
                if (!Environment.isExternalStorageRemovable(dir)) return@forEach

                val root = getStorageRootFromAppExternalDir(dir) ?: return@forEach
                if (root.exists() && root.canRead()) {
                    return root
                }
            }

            return getDirectMountedStorageRoots().firstOrNull()
        }

        @JvmStatic
        fun getUsbOrExternalRoots(context: Context): List<File> {
            val primaryPath = getPrimaryStorageRoot(context).absolutePath
            val removablePath = getRemovableStorageRoot(context)?.absolutePath

            return getStorageRoots(context).filter { root ->
                root.absolutePath != primaryPath && root.absolutePath != removablePath
            }
        }

        @JvmStatic
        fun findContainingStorageRoot(context: Context, path: String?): File? {
            if (path.isNullOrBlank()) return null

            val target = try {
                File(path).canonicalFile
            } catch (_: Exception) {
                return null
            }

            return getStorageRoots(context).firstOrNull { root ->
                isPathInsideRoot(target, root)
            }
        }

        @JvmStatic
        fun isPathInsideRoot(target: File, root: File): Boolean {
            return try {
                val targetPath = target.canonicalFile.path
                val rootPath = root.canonicalFile.path
                targetPath == rootPath || targetPath.startsWith("$rootPath${File.separator}")
            } catch (_: Exception) {
                false
            }
        }

        private fun getStorageRootFromAppExternalDir(dir: File?): File? {
            if (dir == null) return null

            val path = dir.absolutePath
            val marker = "/Android/data/"
            val index = path.indexOf(marker)
            if (index <= 0) return null

            return File(path.substring(0, index))
        }

        private fun getDirectMountedStorageRoots(): List<File> {
            val results = mutableListOf<File>()
            val storageDir = File("/storage")
            val blockedNames = setOf("emulated", "self")

            val children = storageDir.listFiles() ?: return emptyList()

            for (child in children) {
                if (!child.isDirectory) continue
                if (child.name in blockedNames) continue
                if (!child.canRead()) continue

                val lowerName = child.name.lowercase()

                val looksLikeRemovable =
                    child.name.contains("-") ||
                            lowerName.startsWith("usb") ||
                            lowerName.startsWith("usbotg") ||
                            lowerName.startsWith("sd") ||
                            lowerName.startsWith("ext") ||
                            lowerName.startsWith("disk")

                if (!looksLikeRemovable) continue

                if (results.none { it.absolutePath == child.absolutePath }) {
                    results.add(child)
                }
            }

            return results
        }
    }
}