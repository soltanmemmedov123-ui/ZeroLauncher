package com.movtery.zalithlauncher.feature.mod.conflict

/**
 * Severity of a detected conflict or issue.
 */
enum class ConflictSeverity(val label: String) {
    /** Game will crash or be completely unplayable. */
    CRASH("Will Crash"),
    /** Major features broken or severe performance issues. */
    MAJOR("Major Issue"),
    /** Minor incompatibility or suboptimal behaviour. */
    MINOR("Minor Issue"),
    /** Informational – user may want to know, but nothing breaks. */
    INFO("Info")
}

/**
 * Type of rule being evaluated.
 */
enum class ConflictRuleType {
    /** Both mods present simultaneously causes a problem. */
    INCOMPATIBLE_PAIR,
    /** Having mod A requires mod B to also be present. */
    MISSING_DEPENDENCY,
    /** Mod A requires a specific other mod to be absent. */
    REQUIRES_ABSENT,
    /** Duplicate mod — same mod ID installed twice. */
    DUPLICATE_ID,
    /** Mod is known to be broken on Android / this launcher. */
    ANDROID_INCOMPATIBLE
}

/**
 * A resolved conflict issue to present to the user.
 */
data class ConflictIssue(
    val type        : ConflictRuleType,
    val severity    : ConflictSeverity,
    val title       : String,
    val description : String,
    /** Names/IDs of involved mod files (for display). */
    val involvedMods: List<String>
)
