package com.movtery.zalithlauncher.feature.update

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.movtery.zalithlauncher.BuildConfig
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.feature.update.LauncherVersion.FileSize
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.setting.AllSettings.Companion.ignoreUpdate
import com.movtery.zalithlauncher.task.TaskExecutors.Companion.runInUIThread
import com.movtery.zalithlauncher.ui.dialog.TipDialog
import com.movtery.zalithlauncher.ui.dialog.UpdateDialog
import com.movtery.zalithlauncher.utils.ZHTools
import com.movtery.zalithlauncher.utils.http.CallUtils
import com.movtery.zalithlauncher.utils.http.CallUtils.CallbackListener
import com.movtery.zalithlauncher.utils.http.NetworkUtils
import com.movtery.zalithlauncher.utils.path.PathManager
import com.movtery.zalithlauncher.utils.path.UrlManager
import com.movtery.zalithlauncher.utils.stringutils.StringUtils
import net.kdt.pojavlaunch.Architecture
import net.kdt.pojavlaunch.Tools
import okhttp3.Call
import okhttp3.Response
import org.apache.commons.io.FileUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

class UpdateUtils {
    companion object {
        @JvmField
        val sApkFile: File = File(PathManager.DIR_APP_CACHE, "cache.apk")

        private var LAST_UPDATE_CHECK_TIME: Long = 0

        @JvmStatic
        fun checkDownloadedPackage(context: Context, force: Boolean, ignore: Boolean) {
            Logging.i("Check Update", "checkDownloadedPackage(force=$force, ignore=$ignore)")

            if (force && !NetworkUtils.isNetworkAvailable(context)) {
                Logging.i("Check Update", "No network available")
                Toast.makeText(context, context.getString(R.string.generic_no_network), Toast.LENGTH_SHORT).show()
                return
            }

            val isRelease = !BuildConfig.DEBUG
            if (!isRelease) return

            Logging.i("Check Update", "isDebug=${ZHTools.isDebug()}")
            Logging.i("Check Update", "isReleaseBuild=${ZHTools.isRelease()}")
            Logging.i("Check Update", "isPreReleaseBuild=${ZHTools.isPreRelease()}")
            Logging.i("Check Update", "final isRelease=$isRelease")
            Logging.i("Check Update", "installedVersion=${ZHTools.getVersionName()} (${ZHTools.getVersionCode()})")
            Logging.i("Check Update", "cacheApkExists=${sApkFile.exists()}")
            Logging.i("Check Update", "cacheApkPath=${sApkFile.absolutePath}")

            if (!isRelease) {
                Logging.i("Check Update", "Aborting: build not treated as release")
                return
            }

            var hasValidCachedApk = false

            if (sApkFile.exists()) {
                val packageManager = context.packageManager
                val packageInfo = packageManager.getPackageArchiveInfo(sApkFile.absolutePath, 0)

                Logging.i("Check Update", "cached packageInfo is null = ${packageInfo == null}")

                if (packageInfo != null) {
                    val packageName = packageInfo.packageName
                    val versionCode = packageInfo.versionCode
                    val thisVersionCode = ZHTools.getVersionCode()

                    Logging.i("Check Update", "cached packageName=$packageName")
                    Logging.i("Check Update", "cached versionCode=$versionCode")
                    Logging.i("Check Update", "installed versionCode=$thisVersionCode")
                    Logging.i("Check Update", "installed packageName=${ZHTools.getPackageName()}")

                    if (packageName == ZHTools.getPackageName() && versionCode > thisVersionCode) {
                        Logging.i("Check Update", "Found valid cached APK, installing it")
                        hasValidCachedApk = true
                        installApk(context, sApkFile)
                    } else {
                        Logging.i("Check Update", "Deleting stale/invalid cached APK")
                        FileUtils.deleteQuietly(sApkFile)
                    }
                } else {
                    Logging.i("Check Update", "Deleting unreadable cached APK")
                    FileUtils.deleteQuietly(sApkFile)
                }
            }

            val shouldCheckOnline = !hasValidCachedApk && (force || checkCooling())
            Logging.i("Check Update", "hasValidCachedApk=$hasValidCachedApk")
            Logging.i("Check Update", "checkCooling=${checkCooling()}")
            Logging.i("Check Update", "shouldCheckOnline=$shouldCheckOnline")

            if (shouldCheckOnline) {
                AllSettings.updateCheck.put(ZHTools.getCurrentTimeMillis()).save()
                Logging.i("Check Update", "Checking new update online")
                updateCheckerMainProgram(context, ignore)
            }
        }

        private fun checkCooling(): Boolean {
            return ZHTools.getCurrentTimeMillis() - AllSettings.updateCheck.getValue() > 5 * 60 * 1000
        }

        @Synchronized
        fun updateCheckerMainProgram(context: Context, ignore: Boolean) {
            if (ZHTools.getCurrentTimeMillis() - LAST_UPDATE_CHECK_TIME <= 5000) return
            LAST_UPDATE_CHECK_TIME = ZHTools.getCurrentTimeMillis()

            CallUtils(object : CallbackListener {
                override fun onFailure(call: Call?) {
                    showFailToast(context, context.getString(R.string.update_fail))
                }

                @Throws(IOException::class)
                override fun onResponse(call: Call?, response: Response?) {
                    if (!response!!.isSuccessful) {
                        showFailToast(context, context.getString(R.string.update_fail_code, response.code))
                        Logging.e("UpdateLauncher", "Unexpected code ${response.code}")
                        return
                    }

                    try {
                        val releases = JSONArray(response.body!!.string())
                        val launcherVersion = parseBestRelease(releases) ?: run {
                            if (!ignore) {
                                runInUIThread {
                                    Toast.makeText(
                                        context,
                                        StringUtils.insertSpace(
                                            context.getString(R.string.update_without),
                                            ZHTools.getVersionName()
                                        ),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                            return
                        }

                        if (ignore && launcherVersion.versionName == ignoreUpdate.getValue()) return

                        if (isRemoteVersionNewer(launcherVersion.versionName, ZHTools.getVersionName())) {
                            runInUIThread {
                                UpdateDialog(context, launcherVersion).show()
                            }
                        } else if (!ignore) {
                            runInUIThread {
                                Toast.makeText(
                                    context,
                                    StringUtils.insertSpace(
                                        context.getString(R.string.update_without),
                                        ZHTools.getVersionName()
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    } catch (e: Exception) {
                        Logging.e("Check Update", Tools.printToString(e))
                    }
                }
            }, UrlManager.URL_GITHUB_RELEASES, null).enqueue()
        }

        private fun parseBestRelease(releases: JSONArray): LauncherVersion? {
            val allowPreRelease = ZHTools.isPreRelease() || AllSettings.acceptPreReleaseUpdates.getValue()

            for (i in 0 until releases.length()) {
                val release = releases.optJSONObject(i) ?: continue
                if (release.optBoolean("draft", false)) continue

                val isPreRelease = release.optBoolean("prerelease", false)
                if (isPreRelease && !allowPreRelease) continue

                val asset = findBestAssetForArch(release.optJSONArray("assets")) ?: continue

                val tagName = release.optString("tag_name")
                val releaseName = release.optString("name").ifBlank { tagName }
                val releaseBody = release.optString("body")
                val publishedAt = release.optString("published_at")
                val assetSize = asset.optLong("size", 0L)
                val downloadUrl = asset.optString("browser_download_url")

                val versionName = normalizeVersionName(tagName)
                if (versionName.isBlank() || downloadUrl.isBlank()) continue

                return LauncherVersion(
                    parseFallbackVersionCode(versionName),
                    versionName,
                    LauncherVersion.WhatsNew(
                        releaseName,
                        "NONE",
                        "NONE"
                    ),
                    LauncherVersion.WhatsNew(
                        releaseBody,
                        "NONE",
                        "NONE"
                    ),
                    publishedAt,
                    FileSize(
                        assetSize,
                        assetSize,
                        assetSize,
                        assetSize,
                        assetSize
                    ),
                    isPreRelease,
                    downloadUrl
                )
            }

            return null
        }

        private fun findBestAssetForArch(assets: JSONArray?): JSONObject? {
            if (assets == null || assets.length() == 0) return null

            val archModel = getArchModel()?.lowercase(Locale.ROOT)

            if (archModel != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name").lowercase(Locale.ROOT)
                    if (name.endsWith(".apk") && name.contains(archModel)) {
                        return asset
                    }
                }
            }

            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name").lowercase(Locale.ROOT)
                if (name.endsWith(".apk")) {
                    return asset
                }
            }

            return null
        }

        private fun normalizeVersionName(tagName: String): String {
            return tagName.removePrefix("v").trim()
        }

        private fun parseFallbackVersionCode(versionName: String): Int {
            val numbers = Regex("""\d+""").findAll(versionName).map { it.value }.toList()
            if (numbers.size < 4) return 0

            val major = numbers.getOrNull(0)?.toIntOrNull() ?: 0
            val minor = numbers.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = numbers.getOrNull(2)?.toIntOrNull() ?: 0
            val build = numbers.getOrNull(3)?.toIntOrNull() ?: 0

            return major * 100000 + minor * 10000 + patch * 1000 + build
        }

        private fun isRemoteVersionNewer(remote: String, local: String): Boolean {
            val remoteParts = splitVersion(remote)
            val localParts = splitVersion(local)
            val max = maxOf(remoteParts.size, localParts.size)

            for (i in 0 until max) {
                val remotePart = remoteParts.getOrNull(i) ?: VersionPart.Number(0)
                val localPart = localParts.getOrNull(i) ?: VersionPart.Number(0)

                val compare = compareVersionPart(remotePart, localPart)
                if (compare > 0) return true
                if (compare < 0) return false
            }

            return false
        }

        private fun splitVersion(version: String): List<VersionPart> {
            return version.removePrefix("v")
                .split('.', '-', '_')
                .filter { it.isNotBlank() }
                .map {
                    it.toIntOrNull()?.let { num -> VersionPart.Number(num) }
                        ?: VersionPart.Text(it.lowercase(Locale.ROOT))
                }
        }

        private fun compareVersionPart(a: VersionPart, b: VersionPart): Int {
            return when {
                a is VersionPart.Number && b is VersionPart.Number -> a.value.compareTo(b.value)
                a is VersionPart.Text && b is VersionPart.Text -> a.value.compareTo(b.value)
                a is VersionPart.Number && b is VersionPart.Text -> 1
                a is VersionPart.Text && b is VersionPart.Number -> -1
                else -> 0
            }
        }

        private sealed class VersionPart {
            data class Number(val value: Int) : VersionPart()
            data class Text(val value: String) : VersionPart()
        }

        @JvmStatic
        fun showFailToast(context: Context, resString: String) {
            runInUIThread {
                Toast.makeText(context, resString, Toast.LENGTH_SHORT).show()
            }
        }

        @JvmStatic
        fun getArchModel(arch: Int = Tools.DEVICE_ARCHITECTURE): String? {
            if (arch == Architecture.ARCH_ARM64) return "arm64-v8a"
            if (arch == Architecture.ARCH_ARM) return "armeabi-v7a"
            if (arch == Architecture.ARCH_X86_64) return "x86_64"
            if (arch == Architecture.ARCH_X86) return "x86"
            return null
        }

        @JvmStatic
        fun getFileSize(fileSize: FileSize): Long {
            val arch = Tools.DEVICE_ARCHITECTURE
            if (arch == Architecture.ARCH_ARM64) return fileSize.arm64
            if (arch == Architecture.ARCH_ARM) return fileSize.arm
            if (arch == Architecture.ARCH_X86_64) return fileSize.x86_64
            if (arch == Architecture.ARCH_X86) return fileSize.x86
            return fileSize.all
        }

        @JvmStatic
        fun getDownloadUrl(launcherVersion: LauncherVersion): String {
            return launcherVersion.downloadUrl
        }

        @JvmStatic
        fun installApk(context: Context, outputFile: File) {
            runInUIThread {
                TipDialog.Builder(context)
                    .setTitle(R.string.update)
                    .setMessage(StringUtils.insertNewline(context.getString(R.string.update_success), outputFile.absolutePath))
                    .setCenterMessage(false)
                    .setCancelable(false)
                    .setConfirmClickListener {
                        val intent = Intent(Intent.ACTION_VIEW)
                        val apkUri = FileProvider.getUriForFile(context, context.packageName + ".provider", outputFile)
                        intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(intent)
                    }.showDialog()
            }
        }
    }
}