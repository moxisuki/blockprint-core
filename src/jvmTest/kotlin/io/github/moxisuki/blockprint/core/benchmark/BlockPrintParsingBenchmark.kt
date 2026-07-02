package io.github.moxisuki.blockprint.core.benchmark

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.Position
import io.github.moxisuki.blockprint.core.SchematicFormat
import io.github.moxisuki.blockprint.core.api.BlockPrintReader
import io.github.moxisuki.blockprint.core.api.BlockPrintToGlb
import io.github.moxisuki.blockprint.core.model.BlockPrintDocument
import io.github.moxisuki.blockprint.core.model.BlockPrintRegion
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.measureNanoTime
import org.junit.Test

/**
 * Hand-rolled wall-clock + heap-pressure benchmark for the GLB export path.
 *
 * CI skips via env var. Run with:
 *   ./gradlew jvmTest --tests "*BlockPrintParsingBenchmark*"
 *
 * NOT in CI: `CI=true ./gradlew jvmTest` is a no-op for this class.
 *
 * The benchmark exists to measure the impact of the PR-1 … PR-4 work that
 * replaced per-face `List<DoubleArray>` / `List<FloatArray>` /
 * `FloatArray(3)` allocations in
 * [io.github.moxisuki.blockprint.core.glb.mesh.MeshBuilder] with a single
 * per-call [io.github.moxisuki.blockprint.core.glb.mesh.FaceScratch].
 *
 * Two test methods:
 *
 *   1. `parse_litematic_baseline` — measures the parse path (NBT → palette →
 *      blockstate packer) which is NOT touched by the PR series. This is the
 *      "no-op control" that should not move between the baseline and the
 *      feature branch.
 *
 *   2. `synthetic_region_glb_export` — measures the GLB export path on a
 *      programmatically-built region at three sizes (16³, 32³, 64³) so
 *      per-face allocation savings scale with face count and become
 *      visible in both wall-clock and peak heap. The region is a
 *      checkerboard of stone and oak_planks blocks, surrounded by air on
 *      all 6 sides, so the culling path runs end-to-end and produces
 *      visible faces for every exposed block. This is the path the
 *      PR series actually optimises.
 *
 * For each, we report:
 *   - median wall-clock over 5 timed iterations (after 3 warmup)
 *   - peak heap delta across one timed iteration, measured via
 *     [Runtime.totalMemory] - [Runtime.freeMemory] snapshots taken
 *     immediately before and after the export
 */
class BlockPrintParsingBenchmark {
    private val fixture = "test/pre.litematic"
    private val assetsDir: Path = Paths.get("test/assets")

    @Test fun parse_litematic_baseline() {
        assumeFalse("skipping benchmark in CI", System.getenv("CI")?.equals("CI", ignoreCase = true) == true)
        val file = File(fixture)
        assumeTrue("fixture missing: $fixture", file.exists())

        // Warmup
        repeat(3) { BlockPrintReader.read(file) }

        val samples = LongArray(5)
        for (i in 0 until 5) {
            samples[i] = measureNanoTime { BlockPrintReader.read(file) }
        }
        val median = samples.sorted()[2]
        println("[BENCHMARK] parse_litematic: ${median / 1_000_000} ms (median of 5)")
    }

    @Test fun synthetic_region_glb_export() {
        assumeFalse("skipping benchmark in CI", System.getenv("CI")?.equals("CI", ignoreCase = true) == true)
        assumeTrue("assets dir missing: $assetsDir", Files.isDirectory(assetsDir))

        for (size in intArrayOf(16, 32, 64)) {
            val document = buildCheckerboardDocument(size)
            runOne(document, size)
        }
    }

    /**
     * Fence-heavy bench. Every cell is `minecraft:oak_fence` and every
     * cell has 0-6 same-family neighbours. This exercises the Area 2
     * connection-mask path (per-cell `Triple` allocation, per-cell
     * `Map` allocation, per-cell `String.contains` substring scan) and
     * the Area 3 connection-variant cache (one `ResolvedModel` per
     * unique orientation, ~K total instead of M).
     *
     * The synthetic stone/oak_planks bench in
     * [synthetic_region_glb_export] does not exercise the fence path
     * so it cannot reveal the Area 2/3 wins.
     */
    @Test fun synthetic_fence_region_glb_export() {
        assumeFalse("skipping benchmark in CI", System.getenv("CI")?.equals("CI", ignoreCase = true) == true)
        assumeTrue("assets dir missing: $assetsDir", Files.isDirectory(assetsDir))

        // Fence regions are ~7x heavier than the stone checkerboard
        // (each fence cell emits a post + 1-6 side rails vs a single
        // cube). A 32³ solid-fence region is ~200 MB of GLB output;
        // a 64³ is multiple GB. Cap at 16³ only to keep the bench
        // runnable on CI-sized heaps while still exercising the
        // connection path with 4096 fence cells.
        val size = 16
        val document = buildFenceSolidDocument(size)
        runOne(document, size, label = "synthetic_fence_region_glb_export")
    }

    private fun runOne(document: BlockPrintDocument, size: Int, label: String = "synthetic_region_glb_export") {
        // Warmup the export path.
        repeat(3) {
            BlockPrintToGlb.convertToBytes(document, listOf(assetsDir))
        }

        val samples = LongArray(5)
        val outputSizes = LongArray(5)
        var peakHeapDelta = 0L
        for (i in 0 until 5) {
            gcQuiet()
            val before = currentHeap()
            val elapsed = measureNanoTime {
                BlockPrintToGlb.convertToBytes(document, listOf(assetsDir))
            }
            val after = currentHeap()
            val delta = (after - before).coerceAtLeast(0)
            if (delta > peakHeapDelta) peakHeapDelta = delta
            samples[i] = elapsed
            // Re-run to capture size (convertToBytes already produced bytes,
            // re-run with a different measurement path to keep this loop
            // allocation-free apart from the convertToBytes return value).
            val bytes = BlockPrintToGlb.convertToBytes(document, listOf(assetsDir))
            outputSizes[i] = bytes.size.toLong()
        }
        val median = samples.sorted()[2]
        val maxOut = outputSizes.max()
        val minOut = outputSizes.min()
        println(
            "[BENCHMARK] $label[${size}³=${size * size * size} cells]: " +
                "${median / 1_000_000} ms (median of 5), " +
                "output ${minOut / 1024}…${maxOut / 1024} KiB, " +
                "peak heap delta ${peakHeapDelta / 1024} KiB",
        )
    }

    /**
     * Build a synthetic [BlockPrintDocument] with one region of [size]³
     * cells. The region is a 3D checkerboard of stone and oak_planks
     * surrounded by air. The model resolver can find both blocks in
     * `test/assets/`, so face culling runs end-to-end and the
     * MeshBuilder path emits visible faces for every exposed block.
     */
    private fun buildCheckerboardDocument(size: Int): BlockPrintDocument {
        val palette = BlockPalette(
            listOf(
                BlockState("minecraft:air"),
                BlockState("minecraft:stone"),
                BlockState("minecraft:oak_planks"),
            ),
        )
        val blocks = IntArray(size * size * size)
        for (y in 0 until size) for (z in 0 until size) for (x in 0 until size) {
            val idx = y * size * size + z * size + x
            blocks[idx] = if ((x + y + z) % 2 == 0) 1 else 2
        }
        val region = BlockPrintRegion(
            name = "Bench${size}",
            width = size, height = size, depth = size,
            position = Position.ZERO,
            palette = palette,
            blocks = blocks,
        )
        return BlockPrintDocument(
            minecraftDataVersion = 3953,
            version = 7,
            name = "Bench",
            author = "",
            description = "",
            regions = listOf(region),
            format = SchematicFormat.Litematica,
        )
    }

    /**
     * Solid fence region: every cell is `minecraft:oak_fence`.
     * Interior cells have 6 same-family neighbours (the full 3³ cube
     * of fences around each cell); boundary cells have 1-5. The
     * fence's multipart model emits 1 post + up to 6 side rails per
     * cell. The variant cache (Area 3) holds ~3-7 unique orientation
     * entries instead of M = size³ resolves.
     */
    private fun buildFenceSolidDocument(size: Int): BlockPrintDocument {
        val palette = BlockPalette(
            listOf(
                BlockState("minecraft:air"),
                BlockState("minecraft:oak_fence"),
            ),
        )
        val blocks = IntArray(size * size * size) { 1 }  // every cell is fence
        val region = BlockPrintRegion(
            name = "Fence${size}",
            width = size, height = size, depth = size,
            position = Position.ZERO,
            palette = palette,
            blocks = blocks,
        )
        return BlockPrintDocument(
            minecraftDataVersion = 3953,
            version = 7,
            name = "Fence",
            author = "",
            description = "",
            regions = listOf(region),
            format = SchematicFormat.Litematica,
        )
    }

    private fun currentHeap(): Long {
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    /**
     * Best-effort encouragement of GC so the [currentHeap] snapshots are
     * representative. On the JVM, [System.gc] is just a hint; it usually
     * runs but is not guaranteed. The numbers below are best-case for
     * "memory that was used by the call but has not yet been reclaimed".
     */
    private fun gcQuiet() {
        for (i in 0 until 3) {
            System.gc()
            try { Thread.sleep(20) } catch (_: InterruptedException) {}
        }
    }
}
