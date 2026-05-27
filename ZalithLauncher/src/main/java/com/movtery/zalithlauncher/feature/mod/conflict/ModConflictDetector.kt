package com.movtery.zalithlauncher.feature.mod.conflict

import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.feature.mod.parser.ModInfo

/**
 * Analyses a list of parsed mods and produces a [ConflictReport].
 *
 * All rules are pure-Kotlin data — no network calls, no heavy I/O.
 * The engine runs quickly enough to be called synchronously before launch.
 */
object ModConflictDetector {

    // -----------------------------------------------------------------------
    // Rule tables
    // -----------------------------------------------------------------------

    /**
     * Pairs of mod IDs that are incompatible with each other.
     * Format: Triple(modA, modB, severity)
     */
    private val INCOMPATIBLE_PAIRS: List<Triple<String, String, ConflictSeverity>> = listOf(
        // Performance mods that replace the same rendering pipeline
        Triple("sodium",            "rubidium",           ConflictSeverity.CRASH),
        Triple("sodium",            "embeddium",          ConflictSeverity.CRASH),
        Triple("rubidium",          "embeddium",          ConflictSeverity.CRASH),
        Triple("optifine",          "sodium",             ConflictSeverity.CRASH),
        Triple("optifine",          "embeddium",          ConflictSeverity.CRASH),
        Triple("optifine",          "rubidium",           ConflictSeverity.CRASH),
        Triple("optifabric",        "sodium",             ConflictSeverity.CRASH),
        // Shader pipeline conflicts
        Triple("iris",              "oculus",             ConflictSeverity.CRASH),
        Triple("optifine",          "iris",               ConflictSeverity.CRASH),
        // Duplicate chunk/world engine
        Triple("starlight",         "phosphor",           ConflictSeverity.CRASH),
        Triple("starlight",         "betterbiomeblend",   ConflictSeverity.MINOR),
        // Memory / GC conflicts
        Triple("memoryleakfix",     "memoryleakfix-forge",ConflictSeverity.CRASH),
        // Physics / entity
        Triple("physicsmod",        "valkyrienskies",     ConflictSeverity.MAJOR),
        // Server-side only in single player
        Triple("fabricproxy-lite",  "viafabric",          ConflictSeverity.MAJOR),
        // Duplicate voice chat
        Triple("plasmovoice",       "simplevoicechat",    ConflictSeverity.MAJOR),
        // Duplicate inventory sorting
        Triple("inventorysorter",   "notenoughcrashes",   ConflictSeverity.MINOR),
        // Duplicate HUD overlays
        Triple("minimap",           "journeymap",         ConflictSeverity.MINOR),
        Triple("xaerominimap",      "journeymap",         ConflictSeverity.MINOR),
        Triple("xaeroworldmap",     "journeymap",         ConflictSeverity.MINOR),
        // Known Android-broken combos
        Triple("replaymod",         "sodium",             ConflictSeverity.MAJOR),
        Triple("replaymod",         "iris",               ConflictSeverity.MAJOR),
        // Duplicate waypoints
        Triple("voxelmap",          "journeymap",         ConflictSeverity.MINOR),
        Triple("voxelmap",          "xaerominimap",       ConflictSeverity.MINOR)
    )

    /**
     * Mods that require another mod to be present.
     * Format: Triple(modId, requiredDepId, severity)
     */
    private val REQUIRED_DEPENDENCIES: List<Triple<String, String, ConflictSeverity>> = listOf(
        Triple("iris",              "sodium",             ConflictSeverity.CRASH),
        Triple("oculus",            "rubidium",           ConflictSeverity.CRASH),
        Triple("oculus",            "embeddium",          ConflictSeverity.CRASH),
        Triple("indium",            "sodium",             ConflictSeverity.CRASH),
        Triple("reesessodiumoptions","sodium",            ConflictSeverity.CRASH),
        Triple("sodiumextra",       "sodium",             ConflictSeverity.CRASH),
        Triple("morecullingleaves", "sodium",             ConflictSeverity.MAJOR),
        Triple("iceberg",           "prism",              ConflictSeverity.MAJOR),
        Triple("polymorph",         "fabric-api",         ConflictSeverity.CRASH),
        Triple("create",            "flywheel",           ConflictSeverity.CRASH),
        Triple("origins",           "pehkui",             ConflictSeverity.MAJOR),
        Triple("botania",           "patchouli",          ConflictSeverity.MAJOR),
        Triple("twilightforest",    "patchouli",          ConflictSeverity.MAJOR),
        Triple("appleskin",         "fabric-api",         ConflictSeverity.CRASH),
        Triple("waystones",         "balm",               ConflictSeverity.CRASH),
        Triple("ftb-chunks",        "ftb-library",        ConflictSeverity.CRASH),
        Triple("ftb-quests",        "ftb-library",        ConflictSeverity.CRASH),
        Triple("ftb-ranks",         "ftb-library",        ConflictSeverity.CRASH),
        Triple("architectury",      "cloth-config",       ConflictSeverity.MINOR)
    )

    /**
     * Mods that are known to be incompatible with Android / this launcher.
     * Format: Pair(modId, description)
     */
    private val ANDROID_INCOMPATIBLE: List<Pair<String, String>> = listOf(
        "replaymod"         to "ReplayMod requires FFmpeg which is not bundled. Install the FFmpeg plugin.",
        "mcef"              to "MCEF (Chromium Embedded Framework) cannot run on Android.",
        "valkyrienskies"    to "Valkyrien Skies crashes in single-player on Android; run a server instead.",
        "borderlesswindow"  to "Borderless Window is not compatible with Android window management.",
        "imblocker"         to "IMBlocker is not compatible with Android and will crash on launch.",
        "ingameime"         to "In-GameIME is not compatible with Android and will crash on launch.",
        "computecraft"      to "ComputerCraft has JNI dependencies that do not work on Android.",
        "opencomputers"     to "OpenComputers relies on native libraries not available on Android."
    )

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Analyse [modList] and return a [ConflictReport].
     * This is intentionally synchronous and fast — call from a background thread.
     */
    fun detect(modList: List<ModInfo>): ConflictReport {
        if (modList.isEmpty()) {
            return ConflictReport(issues = emptyList(), scannedCount = 0)
        }

        Logging.i("ConflictDetector", "Scanning ${modList.size} mods for conflicts")

        val issues   = mutableListOf<ConflictIssue>()
        val idToMods = buildIdMap(modList)

        // 1. Duplicate IDs
        detectDuplicates(idToMods, issues)

        // 2. Incompatible pairs
        detectIncompatiblePairs(idToMods, issues)

        // 3. Missing dependencies
        detectMissingDependencies(idToMods, issues)

        // 4. Android-incompatible mods
        detectAndroidIncompatible(idToMods, issues)

        // Sort: crashes first, then severity, then title
        issues.sortWith(
            compareBy<ConflictIssue> { it.severity.ordinal }
                .thenBy { it.title }
        )

        Logging.i("ConflictDetector", "Found ${issues.size} issues in ${modList.size} mods")
        return ConflictReport(issues = issues, scannedCount = modList.size)
    }

    // -----------------------------------------------------------------------
    // Detection phases
    // -----------------------------------------------------------------------

    private fun buildIdMap(modList: List<ModInfo>): Map<String, List<ModInfo>> =
        modList.groupBy { it.id.lowercase().trim() }

    private fun detectDuplicates(
        idToMods : Map<String, List<ModInfo>>,
        issues   : MutableList<ConflictIssue>
    ) {
        idToMods.forEach { (id, mods) ->
            if (mods.size > 1) {
                issues += ConflictIssue(
                    type         = ConflictRuleType.DUPLICATE_ID,
                    severity     = ConflictSeverity.CRASH,
                    title        = "Duplicate mod: ${mods.first().name}",
                    description  = "${mods.size} copies of mod '$id' are installed. " +
                        "Remove all but one to prevent class-loading conflicts and crashes.",
                    involvedMods = mods.map { it.file?.name ?: id }
                )
            }
        }
    }

    private fun detectIncompatiblePairs(
        idToMods : Map<String, List<ModInfo>>,
        issues   : MutableList<ConflictIssue>
    ) {
        for ((modA, modB, severity) in INCOMPATIBLE_PAIRS) {
            val presentA = idToMods[modA] ?: continue
            val presentB = idToMods[modB] ?: continue

            val nameA = presentA.first().name.ifBlank { modA }
            val nameB = presentB.first().name.ifBlank { modB }

            issues += ConflictIssue(
                type         = ConflictRuleType.INCOMPATIBLE_PAIR,
                severity     = severity,
                title        = "Incompatible: $nameA ↔ $nameB",
                description  = "$nameA and $nameB cannot be used together. " +
                    "Remove one of them to resolve the conflict.",
                involvedMods = listOf(
                    presentA.first().file?.name ?: modA,
                    presentB.first().file?.name ?: modB
                )
            )
        }
    }

    private fun detectMissingDependencies(
        idToMods : Map<String, List<ModInfo>>,
        issues   : MutableList<ConflictIssue>
    ) {
        for ((modId, requiredId, severity) in REQUIRED_DEPENDENCIES) {
            val present = idToMods[modId] ?: continue
            if (idToMods.containsKey(requiredId)) continue  // dep is present

            val name = present.first().name.ifBlank { modId }
            issues += ConflictIssue(
                type         = ConflictRuleType.MISSING_DEPENDENCY,
                severity     = severity,
                title        = "Missing dependency for $name",
                description  = "$name requires '$requiredId' to be installed. " +
                    "Download and add the dependency to your mods folder.",
                involvedMods = listOf(present.first().file?.name ?: modId)
            )
        }
    }

    private fun detectAndroidIncompatible(
        idToMods : Map<String, List<ModInfo>>,
        issues   : MutableList<ConflictIssue>
    ) {
        for ((modId, description) in ANDROID_INCOMPATIBLE) {
            val present = idToMods[modId] ?: continue
            val name    = present.first().name.ifBlank { modId }
            issues += ConflictIssue(
                type         = ConflictRuleType.ANDROID_INCOMPATIBLE,
                severity     = ConflictSeverity.CRASH,
                title        = "Android incompatible: $name",
                description  = description,
                involvedMods = listOf(present.first().file?.name ?: modId)
            )
        }
    }
}

/**
 * Full result of a conflict detection pass.
 */
data class ConflictReport(
    val issues       : List<ConflictIssue>,
    val scannedCount : Int
) {
    val hasCritical : Boolean get() = issues.any { it.severity == ConflictSeverity.CRASH }
    val hasMajor    : Boolean get() = issues.any { it.severity == ConflictSeverity.MAJOR }
    val isEmpty     : Boolean get() = issues.isEmpty()
}
