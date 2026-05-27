package com.movtery.zalithlauncher.setting

import androidx.annotation.CheckResult
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.movtery.zalithlauncher.event.single.SettingsChangeEvent
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.setting.unit.AbstractSettingUnit
import com.movtery.zalithlauncher.utils.path.PathManager
import net.kdt.pojavlaunch.Tools
import org.apache.commons.io.FileUtils
import org.greenrobot.eventbus.EventBus
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class Settings {
    companion object {
        private val GSON: Gson = GsonBuilder().disableHtmlEscaping().create()

        private val settingsLock = Any()
        private var settingsMap = ConcurrentHashMap<String, SettingAttribute>()

        // Single-thread executor keeps disk writes sequential without blocking the UI thread
        private val saveExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "settings-io").apply { isDaemon = true }
        }

        private fun refreshSettingsMap(): Map<String, SettingAttribute> {
            return PathManager.FILE_SETTINGS.takeIf { it.exists() }?.let { file ->
                try {
                    val jsonString = Tools.read(file)
                    val listType: Type = object : TypeToken<List<SettingAttribute>>() {}.type
                    GSON.fromJson<List<SettingAttribute>>(jsonString, listType)
                        .associateBy { it.key }
                } catch (e: Exception) {
                    Logging.e("Settings", "Failed to refresh settings: ${Tools.printToString(e)}")
                    emptyMap()
                }
            } ?: emptyMap()
        }

        /**
         * 刷新启动器的所有设置项
         */
        @Synchronized
        fun refreshSettings() {
            settingsMap = ConcurrentHashMap(refreshSettingsMap())
        }
    }

    class Manager private constructor() {
        companion object {
            /**
             * 在启动器设置中获取键对应的值
             */
            fun <T> getValue(key: String, defaultValue: T, parser: (String) -> T?): T {
                return settingsMap[key]?.value?.let { parser(it) } ?: defaultValue
            }

            /**
             * 检查启动器设置中，是否存在某个键
             */
            @JvmStatic
            fun contains(key: String): Boolean {
                return settingsMap.containsKey(key)
            }

            /**
             * 在启动器设置中存入键值
             */
            @JvmStatic
            @CheckResult
            fun put(key: String, value: Any) = SettingBuilder().put(key, value)
        }

        class SettingBuilder {
            private val valueMap = ConcurrentHashMap<String, Any>()

            /**
             * 在启动器设置中存入键值
             */
            @CheckResult
            fun put(key: String, value: Any): SettingBuilder {
                valueMap[key] = value
                return this
            }

            /**
             * 在启动器设置中存入键值
             * @param unit 设置单元
             */
            @CheckResult
            fun put(unit: AbstractSettingUnit<*>, value: Any): SettingBuilder {
                return put(unit.key, value)
            }

            fun save() {
                val settingsFile = PathManager.FILE_SETTINGS

                // 1. Apply the new values to the in-memory map immediately so all
                //    readers on any thread see the update without waiting for disk I/O.
                val snapshot = ConcurrentHashMap(settingsMap)
                valueMap.forEach { (key, value) ->
                    snapshot[key] = SettingAttribute(key, value.toString())
                }
                synchronized(settingsLock) {
                    settingsMap = snapshot
                }
                // Notify listeners right away – they work off the in-memory map.
                EventBus.getDefault().post(SettingsChangeEvent())

                // 2. Persist to disk on a background thread – never blocks the UI.
                saveExecutor.execute {
                    synchronized(settingsLock) {
                        runCatching {
                            if (!settingsFile.exists() && !settingsFile.createNewFile()) {
                                throw IllegalStateException("Failed to create settings file")
                            }
                            val settingsList = settingsMap.values.toList()
                            val json = GSON.toJson(settingsList)
                            FileUtils.write(settingsFile, json, Charsets.UTF_8)
                        }.onFailure { e ->
                            Logging.e("SettingBuilder", "Save failed!", e)
                        }
                    }
                }
            }
        }
    }

    private class SettingAttribute(
        var key: String = "",
        var value: String? = null
    )
}