package io.github.moxisuki.blockprint.core.glb

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.Litematic
import io.github.moxisuki.blockprint.core.LitematicRegion
import io.github.moxisuki.blockprint.core.Position
import io.github.moxisuki.blockprint.core.SchematicFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Integration tests for the streaming GLB pipeline.
 *
 * The new pipeline runs two passes (Pass 1 counts via buildFloorsInto +
 * counting sink; Pass 2 streams via writeFloor). These tests verify the
 * externally observable properties of that pipeline:
 *
 * - It never creates a temp file (memory-only pipeline).
 * - 500k blocks can be converted within a 100 MB peak-heap envelope.
 * - The onProgress callback fires at least 3 times (one for each phase
 *   boundary plus a final 1.0f).
 */
class LitematicToGlbStreamingTest {

    @Before
    fun setUp() {
        // No-op; placeholder if needed
    }

    @After
    fun tearDown() {
        // No-op
    }

    private fun solidStoneRegion(w: Int, h: Int, d: Int) = LitematicRegion(
        name = "Solid",
        width = w, height = h, depth = d,
        position = Position.ZERO,
        palette = BlockPalette(listOf(BlockState("minecraft:air"), BlockState("minecraft:stone"))),
        blocks = IntArray(w * h * d) { 1 },
    )

    @Test
    fun convertToBytes_does_not_create_temp_file() {
        val lit = Litematic(
            minecraftDataVersion = 3465, version = 6,
            name = "x", author = "", description = "",
            regions = listOf(solidStoneRegion(10, 10, 10)),
            format = SchematicFormat.Litematica,
        )
        val tmpDir = System.getProperty("java.io.tmpdir")
        val tmpBefore = File(tmpDir).listFiles()?.size ?: 0
        val bytes = LitematicToGlb.convertToBytes(
            litematic = lit,
            assetsDirs = emptyList(),
        )
        val tmpAfter = File(tmpDir).listFiles()?.size ?: 0
        assertTrue("convertToBytes produced empty output", bytes.isNotEmpty())
        assertEquals(
            "convertToBytes should not create temp files (was $tmpBefore, now $tmpAfter)",
            tmpBefore, tmpAfter,
        )
    }

    @Test
    fun convert_500k_blocks_peak_heap_below_threshold() {
        // 500 k blocks: 100 x 100 x 50.
        val lit = Litematic(
            minecraftDataVersion = 3465, version = 6,
            name = "Big", author = "", description = "",
            regions = listOf(solidStoneRegion(100, 100, 50)),
            format = SchematicFormat.Litematica,
        )
        // Warm up: first run may JIT-compile, slightly skewing the heap measurement.
        LitematicToGlb.convertToBytes(lit, emptyList())
        // Measure.
        Runtime.getRuntime().gc()
        val before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val bytes = LitematicToGlb.convertToBytes(lit, emptyList())
        val after = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val peakDelta = (after - before).coerceAtLeast(0L)
        val peakMB = peakDelta / 1024 / 1024
        assertTrue(
            "500 k-block peak heap ${peakMB} MB exceeds 80 MB threshold (output ${bytes.size / 1024 / 1024} MB)",
            peakMB < 80,
        )
    }

    @Test
    fun convert_progress_callback_fires_for_both_passes() {
        val lit = Litematic(
            minecraftDataVersion = 3465, version = 6,
            name = "x", author = "", description = "",
            regions = listOf(solidStoneRegion(8, 8, 8)),
            format = SchematicFormat.Litematica,
        )
        val progressValues = mutableListOf<Float>()
        LitematicToGlb.convertToBytes(
            litematic = lit,
            assetsDirs = emptyList(),
            onProgress = { p -> progressValues.add(p) },
        )
        // The new pipeline makes multiple passes (Pass 1 counting + Pass 2 streaming),
        // so we expect at least 3 distinct progress updates.
        assertTrue("progress callback should fire at least 3 times, got ${progressValues.size}",
            progressValues.size >= 3)
        assertEquals(1.0f, progressValues.last(), 0.001f)
    }
}
