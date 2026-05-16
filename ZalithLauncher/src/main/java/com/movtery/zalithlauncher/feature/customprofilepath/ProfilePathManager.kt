package com.movtery.zalithlauncher.feature.customprofilepath

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.feature.version.VersionsManager
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.ui.subassembly.customprofilepath.ProfileItem
import com.movtery.zalithlauncher.utils.StoragePermissionsUtils
import com.movtery.zalithlauncher.utils.path.PathManager
import net.kdt.pojavlaunch.Tools
import java.io.File
import java.io.FileWriter

object ProfilePathManager {
    private const val TAG = "ProfilePathManager"
    private const val DEFAULT_PROFILE_ID = "default"

    private val defaultPath: String = PathManager.DIR_GAME_HOME
    private var profilePathData: MutableList<ProfileItem> = mutableListOf()

    fun setCurrentPathId(id: String) {
        AllSettings.launcherProfile.put(id).save()
        VersionsManager.refresh("ProfilePathManager:setCurrentPathId")
    }

    fun refreshPath() {
        val configFile = PathManager.FILE_PROFILE_PATH
        if (!configFile.exists() || !configFile.isFile) {
            profilePathData = mutableListOf()
            return
        }

        val json = runCatching { Tools.read(configFile) }
            .onFailure { e ->
                Logging.e(TAG, "Failed to read profile path configuration", e)
            }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

        profilePathData = if (json != null) {
            parseProfileData(json)
        } else {
            mutableListOf()
        }
    }

    private fun parseProfileData(json: String): MutableList<ProfileItem> {
        return runCatching {
            val jsonObject = JsonParser.parseString(json).asJsonObject
            jsonObject.entrySet().mapNotNull { (key, value) ->
                runCatching {
                    val profilePath = Tools.GLOBAL_GSON.fromJson(value, ProfilePathJsonObject::class.java)

                    if (profilePath.title.isNullOrBlank() || profilePath.path.isNullOrBlank()) {
                        Logging.e(TAG, "Skipped invalid profile item: $key")
                        null
                    } else {
                        ProfileItem(key, profilePath.title, profilePath.path)
                    }
                }.onFailure { e ->
                    Logging.e(TAG, "Failed to parse profile item: $key", e)
                }.getOrNull()
            }.toMutableList()
        }.onFailure { e ->
            Logging.e(TAG, "Failed to parse profile path configuration", e)
        }.getOrElse {
            mutableListOf()
        }
    }

    fun getCurrentPath(): String {
        if (!StoragePermissionsUtils.hasCachedPermission()) {
            return defaultPath
        }

        val profileId = AllSettings.launcherProfile.getValue()
        val resolvedPath = if (profileId == DEFAULT_PROFILE_ID) {
            defaultPath
        } else {
            findProfilePath(profileId) ?: defaultPath
        }

        ensureNoMediaFile(resolvedPath)
        return resolvedPath
    }

    fun getAllPath(): List<ProfileItem> = profilePathData.toList()

    fun addPath(profile: ProfileItem) {
        profilePathData.add(profile)
        save()
    }

    fun containsPath(path: String): Boolean {
        return profilePathData.any { it.path == path }
    }

    private fun findProfilePath(profileId: String): String? {
        if (profilePathData.isEmpty()) {
            refreshPath()
        }
        return profilePathData.firstOrNull { it.id == profileId }?.path
    }

    private fun ensureNoMediaFile(path: String) {
        val directory = File(path)

        if (!directory.exists()) {
            val created = runCatching { directory.mkdirs() }
                .onFailure { e ->
                    Logging.e(TAG, "Failed to create directory for profile path: $path", e)
                }
                .getOrDefault(false)

            if (!created && !directory.exists()) {
                return
            }
        }

        val noMediaFile = File(directory, ".nomedia")
        if (!noMediaFile.exists()) {
            runCatching { noMediaFile.createNewFile() }
                .onFailure { e ->
                    Logging.e(TAG, "Failed to create .nomedia in $path", e)
                }
        }
    }

    fun save() {
        save(profilePathData)
    }

    fun save(items: List<ProfileItem>) {
        val jsonObject = JsonObject()

        items.forEach { item ->
            if (item.id == DEFAULT_PROFILE_ID) return@forEach

            val profilePathJsonObject = ProfilePathJsonObject(
                title = item.title,
                path = item.path
            )
            jsonObject.add(item.id, Tools.GLOBAL_GSON.toJsonTree(profilePathJsonObject))
        }

        runCatching {
            PathManager.FILE_PROFILE_PATH.parentFile?.mkdirs()

            FileWriter(PathManager.FILE_PROFILE_PATH).use { writer ->
                Tools.GLOBAL_GSON.toJson(jsonObject, writer)
            }
        }.onFailure { e ->
            Logging.e(TAG, "Failed to write profile path configuration", e)
        }
    }
}