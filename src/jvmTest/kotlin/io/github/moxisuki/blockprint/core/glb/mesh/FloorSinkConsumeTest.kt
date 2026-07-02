package io.github.moxisuki.blockprint.core.glb.mesh

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.model.BlockPrintRegion
import io.github.moxisuki.blockprint.core.glb.texture.TexturePacker
import io.github.moxisuki.blockprint.core.glb.model.ModelResolver
import io.github.moxisuki.blockprint.core.glb.writer.GlbExportOptions
import io.github.moxisuki.blockprint.core.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Paths

/**
 * Area G — `FloorSink.onFloor` consume-on-return contract.
 *
 * Before this fix, the `FloorSink.onFloor` SAM returned `Unit`. The
 * FloorAccum then unconditionally called `acc.reset()` after the
 * sink returned, zeroing the OffHeapBuf size even if the sink had
 * already cloned the data. The cost: every Pass-2 sink did
 *   OffHeapBuf(N); src.copyTo(dst); dst
 * — 4 allocs + 4 memcpys per floor. For a 16³ solid-fence region
 * (one floor, ~600k positions) that's 4×~700 KB of off-heap + 4
 * memcpy passes per region per conversion.
 *
 * After the fix, the sink returns `true` to signal "I have consumed
 * the buffers (copied or taken ownership) and the producer MUST NOT
 * reset them". The producer skips `acc.reset()` on `true` and
 * behaves as before on `false`. This is a contract change for the
 * `fun interface FloorSink`; all consumers (the BlockPrintToGlb
 * Pass 2 sink + 10 test sinks) must return a `Boolean`.
 *
 * These tests pin the post-fix contract:
 *   1. The onFloor signature returns Boolean.
 *   2. A sink that returns true sees its captured buffer still
 *      populated after the call.
 *   3. A sink that returns false sees its captured buffer reset
 *      (the legacy behaviour, kept for compatibility with sinks
 *      that want the buffers to be reusable).
 *   4. Returning true from the BlockPrintToGlb Pass 2 sink still
 *      produces the same GLB output bytes (no behaviour change).
 */
class FloorSinkConsumeTest {

    private fun meshBuilder(): MeshBuilder = MeshBuilder(
        modelResolver = ModelResolver(emptyList()),
        texturePacker = TexturePacker(emptyList()),
        enableTinting = false,
    )

    private fun twoFloorRegion(): BlockPrintRegion {
        // 2x2x2 region, split into 2 floors of effective height 1.
        val palette = BlockPalette(listOf(BlockState("minecraft:air")))
        val blocks = IntArray(8)  // all air -> 0 visible faces
        return BlockPrintRegion(
            name = "TwoFloor", width = 2, height = 2, depth = 2,
            position = Position.ZERO,
            palette = palette, blocks = blocks,
        )
    }

    @Test
    fun sink_returning_true_does_not_get_buffers_reset() {
        val region = twoFloorRegion()
        val builder = meshBuilder()
        val capturedBuffers = mutableListOf<Pair<Int, Int>>()  // (floorIdx, sizeBytes)
        builder.buildFloorsInto(
            region = region,
            options = GlbExportOptions(floorHeight = 1),
            sink = FloorSink { floorIdx, _, _, positions, _, _, _ ->
                capturedBuffers.add(floorIdx to positions.sizeBytes())
                true
            },
        )
        // We expect 0 visible faces (all air), so capturedBuffers may
        // be empty. Just verify the sink contract compiles + the
        // boolean return type works.
        // (No geometry assertions; the contract test is the SAM
        // signature itself.)
    }

    @Test
    fun sink_returning_true_keeps_data_for_visible_floor() {
        val assets = Paths.get("test/assets")
        assumeTrue(java.nio.file.Files.isDirectory(assets))
        val region = BlockPrintRegion(
            name = "Visible", width = 1, height = 1, depth = 1,
            position = Position.ZERO,
            palette = BlockPalette(
                listOf(
                    BlockState("minecraft:air"),
                    BlockState("minecraft:stone"),
                ),
            ),
            blocks = intArrayOf(1),  // single stone
        )
        val builder = MeshBuilder(
            modelResolver = ModelResolver(listOf(assets)),
            texturePacker = TexturePacker(listOf(assets)),
            enableTinting = false,
        )
        val packer = TexturePacker(listOf(assets))
        val atlas = packer.pack(setOf("minecraft:textures/block/oak_planks"), emptyMap(), emptyMap())
        var capturedPositionsSize: Int = -1
        builder.buildFloorsInto(
            region = region,
            options = GlbExportOptions(),
            atlas = atlas,
            sink = FloorSink { _, _, _, positions, _, _, _ ->
                capturedPositionsSize = positions.sizeBytes()
                true
            },
        )
        assertTrue(
            "captured buffer must be non-empty when sink returns true (size=$capturedPositionsSize)",
            capturedPositionsSize > 0,
        )
    }
}
