package io.github.moxisuki.blockprint.core.glb

import io.github.moxisuki.blockprint.core.LitematicReader
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.management.ManagementFactory

/**
 * Manual smoke test against a real .litematic file.
 *
 * Run via:
 *   ./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.glb.RealLitematicSmokeTest.*" --info
 *
 * Edit FILE_PATH to point at a different file.
 *
 * Not part of CI (the path is hardcoded); skip if the file is missing.
 */
class RealLitematicSmokeTest {

    private val filePath =
        "C:/Users/Administrator/Downloads/5c88d397-1a96-4f77-851b-c8cc93bc3148.litematic"
    private val assetsDir =
        "C:/Users/Administrator/Documents/CCO/LitematicMobile/blockprint-core/test/assets"

    @Test
    fun read_and_convert_smoke_test() {
        val file = File(filePath)
        if (!file.exists()) {
            println("[smoke] file not found: $filePath — skipping")
            return
        }
        println("[smoke] file size on disk: ${file.length()} bytes")

        // Read the file.
        val lit = LitematicReader.read(file)
        val assetsPath = java.nio.file.Path.of(assetsDir)
        println("[smoke] read: ${lit.regions.size} region(s)")
        println("[smoke] assets: $assetsDir")
        for ((i, region) in lit.regions.withIndex()) {
            val solidCount = region.rawBlocks.count { it != 0 }
            println(
                "[smoke]   region[$i] name=${region.name} size=${region.width}x${region.height}x${region.depth} " +
                    "pos=(${region.position.x}, ${region.position.y}, ${region.position.z}) " +
                    "palette=${region.palette.size} solid=$solidCount",
            )
        }

        // Warmup pass (JIT).
        println("[smoke] warmup pass…")
        LitematicToGlb.convertToBytes(lit, assetsDirs = listOf(assetsPath))

        // Measure: convertToBytes.
        println("[smoke] measured convertToBytes…")
        measureWithPeak("convertToBytes") {
            LitematicToGlb.convertToBytes(lit, assetsDirs = listOf(assetsPath))
        }.also { report ->
            println(report.summary("convertToBytes"))
        }

        // Measure: convert(File).
        val outFile = File.createTempFile("smoke_out_", ".glb")
        try {
            println("[smoke] measured convert(File) → ${outFile.absolutePath}…")
            measureWithPeak("convert(File)") {
                LitematicToGlb.convert(
                    litematic = lit,
                    assetsDirs = listOf(assetsPath),
                    outputFile = outFile,
                    regionIndex = 0,
                )
                outFile.readBytes()
            }.also { report ->
                println(report.summary("convert(File)"))
            }
        } finally {
            outFile.delete()
        }

        println("[smoke] DONE")
    }

    @Test
    fun real_litematic_peak_heap_below_80mb() {
        val file = File(filePath)
        if (!file.exists()) {
            println("[smoke] file not found: $filePath — skipping")
            return
        }
        val lit = LitematicReader.read(file)
        val assetsPath = java.nio.file.Path.of(assetsDir)
        // Warmup pass.
        LitematicToGlb.convertToBytes(lit, assetsDirs = listOf(assetsPath))
        // Measure peak heap via sampling thread.
        val sampler = HeapSampler()
        sampler.beginSampling()
        Runtime.getRuntime().gc()
        try {
            LitematicToGlb.convertToBytes(lit, assetsDirs = listOf(assetsPath))
        } finally {
            sampler.endSampling()
        }
        val peakMB = sampler.peakBytes / 1024 / 1024
        println("[smoke] real-file peak heap: ${peakMB} MB")
        assertTrue(
            "real-file peak heap $peakMB MB exceeds 80 MB target (Android ART heap cap is 256 MB; need headroom)",
            peakMB < 80,
        )
    }

    @Test
    fun memory_breakdown_estimation() {
        val file = File(filePath)
        if (!file.exists()) {
            println("[smoke] file not found: $filePath — skipping")
            return
        }
        val lit = LitematicReader.read(file)
        val assetsPath = java.nio.file.Path.of(assetsDir)
        val region = lit.regions.firstOrNull() ?: return
        val totalCells = region.width.toLong() * region.height * region.depth
        val solidCount = region.rawBlocks.count { it != 0 }
        val paletteSize = region.palette.size
        println("[mem] region ${region.width}x${region.height}x${region.depth} = $totalCells cells")
        println("[mem]   solid blocks: $solidCount")
        println("[mem]   palette entries: $paletteSize")
        println()

        // Estimated sizes of each component, in MB.
        val rawBlocksMB = totalCells * 4.0 / 1024 / 1024          // IntArray: 4 bytes per cell
        val paletteMB = paletteSize * 200.0 / 1024 / 1024       // ~200 bytes per BlockState (over-estimate, generous)
        val modelCacheMB = paletteSize * 100.0 / 1024 / 1024     // ~100 bytes per model element (over-estimate)
        println("[mem] --- Estimated per-component memory (empty ModelResolver) ---")
        println("[mem]   rawBlocks (IntArray $totalCells): ${"%.2f".format(rawBlocksMB)} MB")
        println("[mem]   palette ($paletteSize entries): ${"%.3f".format(paletteMB)} MB")
        println("[mem]   modelCache ($paletteSize entries): ${"%.3f".format(modelCacheMB)} MB")
        println("[mem]   atlas (placeholder): < 1 KB")
        println("[mem]   per-floor accumulator (theoretical max for $solidCount blocks): N/A (empty geometry)")
        println()

        // What it WOULD be without streaming (legacy single-allocation path):
        // - Per solid block: 24 vertices * (3 pos + 2 uv + 3 normal = 8 floats = 32 bytes) = 768 bytes/vertex positions/uvs/normals
        //   Actually per face: 4 vertices, 6 indices, 32+16+24=72 bytes per face (positions+uvs+normals+indices)
        // - Plus indices: 6 indices per face * 4 bytes = 24 bytes per face
        // - So ~96 bytes per face * 5 visible faces avg * solidCount blocks = ~25 MB for 52519 blocks
        // - But legacy code accumulates ALL floors in one FloatBuf before flush → peak ~2x that = ~50 MB
        val perFaceFloatsBytes = 4 * (3 + 2 + 3) * 4  // 4 vertices * 8 floats * 4 bytes = 128 bytes for pos+uv+normal
        val perFaceIndicesBytes = 6 * 4               // 6 indices * 4 bytes = 24 bytes
        val perFaceBytes = perFaceFloatsBytes + perFaceIndicesBytes   // 152 bytes
        val avgVisibleFaces = 5.0
        val perBlockBytes = perFaceBytes * avgVisibleFaces
        val noStreamingPeakMB = perBlockBytes * solidCount / 1024 / 1024
        println("[mem] --- Hypothetical 'no streaming' (legacy) peak ---")
        println("[mem]   per face: $perFaceBytes bytes (positions + uvs + normals + indices)")
        println("[mem]   per block (avg $avgVisibleFaces visible faces): $perBlockBytes bytes")
        println("[mem]   for $solidCount solid blocks: ${"%.2f".format(noStreamingPeakMB)} MB peak")
        println()

        // Actual measured peak:
        println("[mem] --- ACTUAL measured peak ---")
        Runtime.getRuntime().gc()
        val heapBefore = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
        LitematicToGlb.convertToBytes(lit, assetsDirs = listOf(assetsPath))
        val peakNow = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
        println("[mem]   heap before: ${heapBefore / 1024 / 1024} MB")
        println("[mem]   heap after convertToBytes (populated geometry): ${peakNow / 1024 / 1024} MB")
        println("[mem]   delta: ${(peakNow - heapBefore) / 1024} KB")
    }

    private data class MemReport(
        val name: String,
        val durationNanos: Long,
        val heapBeforeBytes: Long,
        val heapAfterBytes: Long,
        val peakBytes: Long,
        val outputBytes: Long,
    ) {
        fun summary(label: String): String {
            val ms = durationNanos / 1_000_000
            val heapDeltaKB = (heapAfterBytes - heapBeforeBytes).coerceAtLeast(0L) / 1024
            val peakMB = peakBytes / 1024 / 1024
            val heapAfterMB = heapAfterBytes / 1024 / 1024
            return "[smoke] $label: output=$outputBytes B, time=$ms ms, " +
                "heapDelta=$heapDeltaKB KB, peakHeap=$peakMB MB, heapAfter=$heapAfterMB MB"
        }
    }

    private fun measureWithPeak(name: String, block: () -> ByteArray): MemReport {
        // Sample heap on a background thread every 1 ms to capture true peak.
        val sampler = HeapSampler()
        sampler.beginSampling()
        Runtime.getRuntime().gc()
        val before = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
        val t0 = System.nanoTime()
        val bytes = try {
            block()
        } finally {
            sampler.endSampling()
        }
        val t1 = System.nanoTime()
        val after = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
        return MemReport(
            name = name,
            durationNanos = t1 - t0,
            heapBeforeBytes = before,
            heapAfterBytes = after,
            peakBytes = sampler.peakBytes.coerceAtLeast(after),
            outputBytes = bytes.size.toLong(),
        )
    }

    private class HeapSampler : Thread("mem-sampler") {
        @Volatile var peakBytes: Long = 0L
        @Volatile private var running = false
        fun beginSampling() { running = true; start() }
        fun endSampling() { running = false; interrupt() }
        override fun run() {
            while (running && !isInterrupted) {
                val used = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
                if (used > peakBytes) peakBytes = used
                try { sleep(1) } catch (e: InterruptedException) { break }
            }
        }
    }
}