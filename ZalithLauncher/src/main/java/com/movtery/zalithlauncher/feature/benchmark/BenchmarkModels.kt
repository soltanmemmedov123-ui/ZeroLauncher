package com.movtery.zalithlauncher.feature.benchmark

/** Phases reported during a benchmark run. */
enum class BenchmarkPhase {
    CPU_SINGLE,
    CPU_MULTI,
    MEMORY,
    STORAGE,
    RECOMMENDATIONS,
    DONE
}

/** Overall device performance tier. */
enum class BenchmarkTier(val label: String) {
    VERY_LOW("Entry"),
    LOW("Low"),
    MEDIUM("Mid-Range"),
    HIGH("High-End")
}

/** Category a recommendation belongs to. */
enum class RecommendationCategory(val label: String) {
    RAM("Memory"),
    RENDERER("Renderer"),
    RESOLUTION("Resolution"),
    PERFORMANCE("Performance"),
    JAVA("Java / GC"),
    STORAGE("Storage")
}

/** How much applying this recommendation is expected to help. */
enum class RecommendationImpact(val label: String) {
    LOW("Low impact"),
    MEDIUM("Medium impact"),
    HIGH("High impact")
}

/**
 * A single actionable suggestion produced by the benchmark.
 *
 * @param settingKey   The preference key to write when the user taps "Apply",
 *                     or null if no automatic apply is possible.
 * @param settingValue The value to write, or null.
 */
data class BenchmarkRecommendation(
    val category    : RecommendationCategory,
    val title       : String,
    val description : String,
    val settingKey  : String?,
    val settingValue: String?,
    val impact      : RecommendationImpact
)

/**
 * Full result produced by [BenchmarkEngine.run].
 *
 * Scores are 0–1000 (higher = better).
 */
data class BenchmarkResult(
    val overallScore    : Int,
    val cpuSingleScore  : Int,
    val cpuMultiScore   : Int,
    val memoryScore     : Int,
    val storageScore    : Int,
    val totalMemoryMb   : Int,
    val freeMemoryMb    : Int,
    val coreCount       : Int,
    val deviceModel     : String,
    val recommendations : List<BenchmarkRecommendation>,
    val tier            : BenchmarkTier
)
