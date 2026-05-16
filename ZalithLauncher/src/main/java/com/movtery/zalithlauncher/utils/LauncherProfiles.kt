package com.movtery.zalithlauncher.utils

import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathHome
import com.movtery.zalithlauncher.feature.log.Logging
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

class LauncherProfiles {
    companion object {
        private const val TAG = "LauncherProfiles"
        private const val FILE_NAME = "launcher_profiles.json"
        private const val DEFAULT_PROFILE_JSON =
            """{"profiles":{"default":{"lastVersionId":"latest-release"}},"selectedProfile":"default"}"""

        /**
         * Creates a default launcher_profiles.json file if it does not exist.
         *
         * Without this file, installers such as Forge and NeoForge may fail
         * to install correctly.
         */
        @JvmStatic
        fun generateLauncherProfiles() {
            runCatching {
                val profileFile = File(ProfilePathHome.getGameHome(), FILE_NAME)

                if (profileFile.exists()) {
                    return
                }

                val parent = profileFile.parentFile
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw IOException("Failed to create parent directory: ${parent.absolutePath}")
                }

                if (!profileFile.createNewFile()) {
                    throw IOException("Failed to create $FILE_NAME")
                }

                FileUtils.write(profileFile, DEFAULT_PROFILE_JSON, StandardCharsets.UTF_8)

                Logging.i(
                    TAG,
                    "Created $FILE_NAME at ${profileFile.absolutePath}"
                )
            }.onFailure { error ->
                Logging.e(TAG, "Failed to generate $FILE_NAME", error)
            }
        }
    }
}