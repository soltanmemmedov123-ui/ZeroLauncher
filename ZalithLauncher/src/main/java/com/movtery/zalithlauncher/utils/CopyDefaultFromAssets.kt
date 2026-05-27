package com.movtery.zalithlauncher.utils

import android.content.Context
import com.movtery.zalithlauncher.utils.path.PathManager
import net.kdt.pojavlaunch.Tools
import java.io.File
import java.io.IOException

class CopyDefaultFromAssets {
    companion object {
        @JvmStatic
        @Throws(IOException::class)
        fun copyFromAssets(context: Context?) {
            //默认控制布局
            if (checkDirectoryEmpty(PathManager.DIR_CTRLMAP_PATH)) {
                Tools.copyAssetFile(context, "default.json", PathManager.DIR_CTRLMAP_PATH, false)
            }
            // ZeroAgent — extract from assets; re-copy if size changed (APK update)
            val agentDest = File(PathManager.DIR_DATA, "components/zero_agent.jar")
            agentDest.parentFile?.mkdirs()
            val assetSize = try {
                context?.assets?.openFd("components/zero_agent.jar")?.use { it.length } ?: -1L
            } catch (e: IOException) { -1L }
            if (!agentDest.exists() || agentDest.length() != assetSize) {
                Tools.copyAssetFile(context, "components/zero_agent.jar",
                    agentDest.parent ?: (PathManager.DIR_DATA + "/components"), false)
            }
        }

        private fun checkDirectoryEmpty(dir: String?): Boolean {
            val controlDir = dir?.let { File(it) }
            val files = controlDir?.listFiles()
            return files?.isEmpty() ?: true
        }
    }
}
