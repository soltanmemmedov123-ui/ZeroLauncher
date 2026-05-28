package com.movtery.zalithlauncher.feature.benchmark

import android.content.Context
import android.os.Build
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.utils.platform.MemoryUtils
import net.kdt.pojavlaunch.Architecture
import net.kdt.pojavlaunch.Tools
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Performs a multi-phase hardware benchmark and produces a [BenchmarkResult] with
 * per-category scores and recommended launcher settings.
 *
 * All heavy work runs on background threads; [onProgress] is called on the same
 * background thread – callers must marshal to UI if needed.
 */
object BenchmarkEngine {

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    fun run(
        context: Context,
        onProgress: (phase: BenchmarkPhase, progressPercent: Int) -> Unit
    ): BenchmarkResult {

        val totalMemoryMb = (MemoryUtils.getTotalDeviceMemory(context) / (1024L * 1024L)).toInt()
        val freeMemoryMb  = (MemoryUtils.getFreeDeviceMemory(context) / (1024L * 1024L)).toInt()

        // ── Phase 1: CPU single-thread ────────────────────────────────────
        onProgress(BenchmarkPhase.CPU_SINGLE, 0)
        val cpuSingleScore = benchmarkCpuSingleThread { p -> onProgress(BenchmarkPhase.CPU_SINGLE, p) }

        // ── Phase 2: CPU multi-thread ─────────────────────────────────────
        onProgress(BenchmarkPhase.CPU_MULTI, 0)
        val cpuMultiScore = benchmarkCpuMultiThread { p -> onProgress(BenchmarkPhase.CPU_MULTI, p) }

        // ── Phase 3: Memory bandwidth ─────────────────────────────────────
        onProgress(BenchmarkPhase.MEMORY, 0)
        val memoryScore = benchmarkMemoryBandwidth { p -> onProgress(BenchmarkPhase.MEMORY, p) }

        // ── Phase 4: Storage I/O ──────────────────────────────────────────
        onProgress(BenchmarkPhase.STORAGE, 0)
        val storageScore = benchmarkStorageIO(context) { p -> onProgress(BenchmarkPhase.STORAGE, p) }

        // ── Phase 5: Compute recommendations ─────────────────────────────
        onProgress(BenchmarkPhase.RECOMMENDATIONS, 95)

        val overall = computeOverallScore(cpuSingleScore, cpuMultiScore, memoryScore, storageScore)
        val recommendations = buildRecommendations(
            context, overall, cpuSingleScore, cpuMultiScore,
            memoryScore, storageScore, totalMemoryMb, freeMemoryMb
        )

        onProgress(BenchmarkPhase.DONE, 100)

        return BenchmarkResult(
            overallScore      = overall,
            cpuSingleScore    = cpuSingleScore,
            cpuMultiScore     = cpuMultiScore,
            memoryScore       = memoryScore,
            storageScore      = storageScore,
            totalMemoryMb     = totalMemoryMb,
            freeMemoryMb      = freeMemoryMb,
            coreCount         = Runtime.getRuntime().availableProcessors(),
            deviceModel       = "${Build.MANUFACTURER} ${Build.MODEL}",
            recommendations   = recommendations,
            tier              = tierFromScore(overall)
        )
    }

    // -----------------------------------------------------------------------
    // Phase implementations
    // -----------------------------------------------------------------------

    /** Floating-point & integer compute stress on one thread. Returns 0..1000. */
    private fun benchmarkCpuSingleThread(progress: (Int) -> Unit): Int {
        val iterations = 4_000_000L
        val step       = iterations / 10

        var result = 1.0
        val t0 = System.nanoTime()
        var i  = 0L
        while (i < iterations) {
            // Mix of fp, integer and branch work
            result = result * 1.0000001 + (i % 97).toDouble() / (i % 13 + 1)
            if (i % step == 0L) progress(((i.toDouble() / iterations) * 50).toInt())
            i++
        }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
        Logging.i("Benchmark", "CPU single dummy=$result elapsed=${elapsedMs}ms")

        // Baseline: ~400 ms on a mid-range device → score 500
        val score = (200_000.0 / elapsedMs.coerceAtLeast(1)).coerceIn(50.0, 1000.0).roundToInt()
        progress(50)

        // Additional: log-computation pass
        val t1 = System.nanoTime()
        var acc = 0.0
        for (k in 1..500_000) acc += ln(k.toDouble())
        val logMs = (System.nanoTime() - t1) / 1_000_000L
        Logging.i("Benchmark", "CPU single log acc=$acc logMs=${logMs}ms")
        val logScore = (50_000.0 / logMs.coerceAtLeast(1)).coerceIn(50.0, 1000.0).roundToInt()
        progress(100)

        return ((score * 0.7) + (logScore * 0.3)).roundToInt().coerceIn(50, 1000)
    }

    /** Same workload split across all available cores. Returns 0..1000. */
    private fun benchmarkCpuMultiThread(progress: (Int) -> Unit): Int {
        val cores    = Runtime.getRuntime().availableProcessors()
        val pool     = Executors.newFixedThreadPool(cores)
        val perThread = 1_000_000L

        progress(10)
        val futures = (0 until cores).map { tid ->
            pool.submit<Long> {
                val t0 = System.nanoTime()
                var r  = tid.toLong()
                for (k in 0 until perThread) r = r * 6364136223846793005L + 1442695040888963407L
                Logging.i("Benchmark", "CPU multi thread $tid dummy=$r")
                System.nanoTime() - t0
            }
        }

        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)
        progress(80)

        val maxElapsedMs = futures.mapNotNull {
            runCatching { it.get() / 1_000_000L }.getOrNull()
        }.maxOrNull() ?: 500L

        // Baseline: 4-core device finishes 4×1M iterations in ~80 ms → score 600
        val singleEquivalent = maxElapsedMs * cores
        val score = (240_000.0 / singleEquivalent.coerceAtLeast(1))
            .coerceIn(50.0, 1000.0).roundToInt()
        progress(100)
        return score
    }

    /** Allocates a large array and measures read/write throughput. Returns 0..1000. */
    private fun benchmarkMemoryBandwidth(progress: (Int) -> Unit): Int {
        val sizeMb   = 32
        val sizeInts = sizeMb * 1024 * 1024 / 4  // 32 MB as ints

        progress(10)
        return try {
            val buf = IntArray(sizeInts)

            // Write pass
            val t0 = System.nanoTime()
            for (i in 0 until sizeInts) buf[i] = i xor 0xDEADBEEF.toInt()
            val writeMs = (System.nanoTime() - t0) / 1_000_000L
            progress(50)

            // Read pass
            var sum = 0L
            val t1 = System.nanoTime()
            for (v in buf) sum += v
            val readMs = (System.nanoTime() - t1) / 1_000_000L
            Logging.i("Benchmark", "Memory sum=$sum writeMs=$writeMs readMs=$readMs")

            val writeMbps = sizeMb * 1000.0 / writeMs.coerceAtLeast(1)
            val readMbps  = sizeMb * 1000.0 / readMs.coerceAtLeast(1)
            val avgMbps   = (writeMbps + readMbps) / 2.0

            // Baseline: ~5000 MB/s → score 500
            val score = (avgMbps / 10.0).coerceIn(50.0, 1000.0).roundToInt()
            progress(100)
            score
        } catch (oom: OutOfMemoryError) {
            Logging.e("Benchmark", "OOM during memory benchmark", oom)
            progress(100)
            100
        }
    }

    /** Writes and reads a 4 MB temp file on the app's cache dir. Returns 0..1000. */
    private fun benchmarkStorageIO(context: Context, progress: (Int) -> Unit): Int {
        val file   = File(context.cacheDir, "zlbench_tmp.bin")
        val sizeKb = 4096
        val chunk  = ByteArray(4096) { it.toByte() }

        return try {
            // Write
            progress(10)
            val t0 = System.nanoTime()
            file.outputStream().buffered(65536).use { out ->
                repeat(sizeKb) { out.write(chunk) }
                out.flush()
            }
            val writeMs = (System.nanoTime() - t0) / 1_000_000L
            progress(55)

            // Read
            val t1  = System.nanoTime()
            var sum = 0L
            file.inputStream().buffered(65536).use { inp ->
                val buf = ByteArray(4096)
                var n: Int
                while (inp.read(buf).also { n = it } != -1) sum += n
            }
            val readMs = (System.nanoTime() - t1) / 1_000_000L
            Logging.i("Benchmark", "Storage sum=$sum writeMs=$writeMs readMs=$readMs")

            val writeMbps = (sizeKb.toDouble() / writeMs.coerceAtLeast(1))
            val readMbps  = (sizeKb.toDouble() / readMs.coerceAtLeast(1))
            val avgMbps   = (writeMbps + readMbps) / 2.0

            // Baseline: ~200 MB/s → score 500
            val score = (avgMbps * 2.5).coerceIn(50.0, 1000.0).roundToInt()
            progress(100)
            score
        } catch (e: Exception) {
            Logging.e("Benchmark", "Storage benchmark failed", e)
            progress(100)
            100
        } finally {
            file.delete()
        }
    }

    // -----------------------------------------------------------------------
    // Scoring helpers
    // -----------------------------------------------------------------------

    private fun computeOverallScore(
        cpuSingle: Int, cpuMulti: Int, memory: Int, storage: Int
    ): Int = ((cpuSingle * 0.30) + (cpuMulti * 0.30) + (memory * 0.25) + (storage * 0.15))
        .roundToInt().coerceIn(0, 1000)

    private fun tierFromScore(score: Int): BenchmarkTier = when {
        score >= 750 -> BenchmarkTier.HIGH
        score >= 450 -> BenchmarkTier.MEDIUM
        score >= 200 -> BenchmarkTier.LOW
        else         -> BenchmarkTier.VERY_LOW
    }

    // -----------------------------------------------------------------------
    // Recommendations
    // -----------------------------------------------------------------------

    private fun buildRecommendations(
        context: Context,
        overall: Int,
        cpuSingle: Int,
        cpuMulti: Int,
        memory: Int,
        storage: Int,
        totalMemMb: Int,
        freeMemMb: Int
    ): List<BenchmarkRecommendation> {
        val list = mutableListOf<BenchmarkRecommendation>()
        val is32 = Architecture.is32BitsDevice()

        // ── RAM allocation ────────────────────────────────────────────────
        val recommendedRam = when {
            is32 || totalMemMb < 2048 -> 512
            totalMemMb < 3072         -> 768
            totalMemMb < 4096         -> 1024
            totalMemMb < 6144         -> 1536
            totalMemMb < 8192         -> 2048
            else                      -> 2560
        }
        list += BenchmarkRecommendation(
            category    = RecommendationCategory.RAM,
            title       = "RAM Allocation",
            description = "Set game memory to ${recommendedRam} MB. " +
                "Your device has ${totalMemMb} MB total, ${freeMemMb} MB free.",
            settingKey  = "allocation",
            settingValue = recommendedRam.toString(),
            impact      = RecommendationImpact.HIGH
        )

        // ── Renderer ──────────────────────────────────────────────────────
        if (overall >= 600 && !is32) {
            list += BenchmarkRecommendation(
                category    = RecommendationCategory.RENDERER,
                title       = "Use Zink Renderer",
                description = "Your device is powerful enough for Zink (OpenGL over Vulkan), which " +
                    "provides better compatibility and performance on high-end hardware.",
                settingKey  = "renderer",
                settingValue = "opengles3_zink",
                impact      = RecommendationImpact.HIGH
            )
        } else {
            list += BenchmarkRecommendation(
                category    = RecommendationCategory.RENDERER,
                title       = "Use OpenGL ES 2 Renderer",
                description = "OpenGL ES 2 is recommended for your device tier. It offers " +
                    "the best stability and battery efficiency.",
                settingKey  = "renderer",
                settingValue = "opengles2",
                impact      = RecommendationImpact.MEDIUM
            )
        }

        // ── Resolution ratio ─────────────────────────────────────────────
        val resRatio = when {
            overall >= 750 -> 100
            overall >= 500 -> 85
            overall >= 300 -> 70
            else           -> 50
        }
        list += BenchmarkRecommendation(
            category    = RecommendationCategory.RESOLUTION,
            title       = "Resolution Ratio ${resRatio}%",
            description = "Rendering at ${resRatio}% of native resolution balances visual " +
                "quality and frame rate for your hardware tier.",
            settingKey  = "resolutionRatio",
            settingValue = resRatio.toString(),
            impact      = if (resRatio < 100) RecommendationImpact.HIGH else RecommendationImpact.LOW
        )

        // ── Sustained performance mode ────────────────────────────────────
        if (cpuMulti >= 600) {
            list += BenchmarkRecommendation(
                category    = RecommendationCategory.PERFORMANCE,
                title       = "Enable Sustained Performance",
                description = "Your multi-core score is strong. Sustained performance mode " +
                    "prevents thermal throttling during long play sessions.",
                settingKey  = "sustainedPerformance",
                settingValue = "true",
                impact      = RecommendationImpact.MEDIUM
            )
        }

        // ── Big-core affinity ─────────────────────────────────────────────
        if (cpuSingle >= 600 && !is32) {
            list += BenchmarkRecommendation(
                category    = RecommendationCategory.PERFORMANCE,
                title       = "Enable Big-Core Affinity",
                description = "Your single-core performance is strong. Binding the game to " +
                    "performance cores reduces latency and frame stuttering.",
                settingKey  = "bigCoreAffinity",
                settingValue = "true",
                impact      = RecommendationImpact.MEDIUM
            )
        }

        // ── Java args GC tuning ───────────────────────────────────────────
        val gcArgs = when {
            overall >= 700 -> "-XX:+UseG1GC -XX:MaxGCPauseMillis=50"
            overall >= 400 -> "-XX:+UseG1GC -XX:MaxGCPauseMillis=100"
            else           -> "-XX:+UseSerialGC"
        }
        list += BenchmarkRecommendation(
            category    = RecommendationCategory.JAVA,
            title       = "Optimised GC Arguments",
            description = "Recommended Java GC flags: $gcArgs — minimises garbage-collection " +
                "pauses for your performance tier.",
            settingKey  = "javaArgs",
            settingValue = gcArgs,
            impact      = RecommendationImpact.MEDIUM
        )

        // ── Storage warning ───────────────────────────────────────────────
        if (storage < 200) {
            list += BenchmarkRecommendation(
                category    = RecommendationCategory.STORAGE,
                title       = "Slow Storage Detected",
                description = "Storage I/O is slow. Avoid large modpacks or shader packs, and " +
                    "keep at least 1 GB free. Game load times will be long.",
                settingKey  = null,
                settingValue = null,
                impact      = RecommendationImpact.HIGH
            )
        }

        return list
    }
}
