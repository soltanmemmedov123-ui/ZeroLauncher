package com.movtery.zalithlauncher.feature.mod.models

/**
 * POJO for mmc-pack.json used by MultiMC and Prism Launcher exports.
 *
 * Example structure:
 * {
 *   "components": [
 *     { "uid": "net.minecraft",                    "version": "1.20.1" },
 *     { "uid": "net.fabricmc.fabric-loader",       "version": "0.14.24" },
 *     { "uid": "net.fabricmc.intermediary",        "version": "1.20.1" }
 *   ],
 *   "formatVersion": 1
 * }
 */
data class MmcPackMeta(
    val components: List<MmcComponent> = emptyList(),
    val formatVersion: Int = 0
) {
    data class MmcComponent(
        /** Component UID, e.g. "net.minecraft", "net.fabricmc.fabric-loader" */
        val uid: String = "",
        /** Pinned version string; may be null if the component is not pinned */
        val version: String? = null
    )
}
