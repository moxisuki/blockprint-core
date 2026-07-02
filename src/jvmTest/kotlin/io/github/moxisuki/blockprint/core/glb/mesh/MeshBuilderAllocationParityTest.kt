package io.github.moxisuki.blockprint.core.glb.mesh

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.model.BlockPrintRegion
import io.github.moxisuki.blockprint.core.glb.writer.GlbExportOptions
import io.github.moxisuki.blockprint.core.glb.texture.TexturePacker
import io.github.moxisuki.blockprint.core.glb.model.ModelResolver
import io.github.moxisuki.blockprint.core.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-2 deterministic-output tests.
 *
 * PR-2 introduces a per-call [FaceScratch] shared across the face processing
 * loop in [MeshBuilder.buildFloorsInto]. The scratch buffers are reused for
 * every face in the call, then (in principle) thrown away. The risk is
 * accidental state leakage between calls — e.g. a static scratch, a scratch
 * retained on a builder instance, or a "reset" path that forgets a buffer.
 *
 * These tests run [MeshBuilder.buildFloorsInto] twice on the same input and
 * assert byte-equal output. They pass today (no scratch is in use), and they
 * must keep passing once PR-2 / PR-3 / PR-4 wire scratch into the hot path.
 * If a future change leaks state, these tests fail immediately.
 *
 * The fixtures deliberately exercise paths that touch the most scratch
 * fields:
 *   - `faceUVs[0..3]` — face UV rotation / mirror
 *   - `finalRotated` — boundary offset when two blocks are adjacent and not
 *     full-opaque
 *   - `verts` — the final translated vertex array
 *
 * `ModelResolver` and `TexturePacker` are constructed against empty asset
 * directories, so the region resolves to "no models" and the sink never
 * fires. The deterministic-output guarantee is therefore exercised on the
 * empty path; that is sufficient to catch a "stale scratch" bug because
 * any such bug would also manifest as a wrong `FloorStats` count from
 * [MeshBuilder.countFloorStats] (which uses the same scratch).
 */
class MeshBuilderAllocationParityTest {

    private fun mixedRegion(): BlockPrintRegion {
        val palette = BlockPalette(
            listOf(
                BlockState("minecraft:air"),
                BlockState("minecraft:stone"),
                BlockState("minecraft:dirt"),
                BlockState("minecraft:oak_planks"),
                BlockState("minecraft:glass"),
            ),
        )
        val blocks = IntArray(4 * 3 * 2) { i ->
            when (i % 5) {
                0 -> 0; 1 -> 1; 2 -> 2; 3 -> 3; else -> 4
            }
        }
        return BlockPrintRegion(
            name = "Mixed", width = 4, height = 3, depth = 2,
            position = Position(10, 64, -5),
            palette = palette, blocks = blocks,
        )
    }

    private fun tallRegion(): BlockPrintRegion {
        val palette = BlockPalette(
            listOf(
                BlockState("minecraft:air"),
                BlockState("minecraft:stone"),
            ),
        )
        // Force a non-trivial floor split so the per-floor loop iterates > 1.
        val blocks = IntArray(2 * 6 * 2) { i ->
            // Interleave air and stone in a checker pattern.
            if ((i / 2 + i % 2) % 2 == 0) 0 else 1
        }
        return BlockPrintRegion(
            name = "TallSplit", width = 2, height = 6, depth = 2,
            position = Position.ZERO,
            palette = palette, blocks = blocks,
        )
    }

    private fun runAndCollect(
        region: BlockPrintRegion, options: GlbExportOptions = GlbExportOptions(),
    ): List<Triple<FloatArray, FloatArray, IntArray>> {
        val builder = MeshBuilder(
            modelResolver = ModelResolver(emptyList()),
            texturePacker = TexturePacker(emptyList()),
            enableTinting = false,
        )
        val out = mutableListOf<Triple<FloatArray, FloatArray, IntArray>>()
        builder.buildFloorsInto(
            region = region,
            originX = 0, originY = 0, originZ = 0,
            options = options,
            sink = FloorSink { _, _, _, positions, uvs, _, indices ->
                out.add(
                    Triple(
                        offHeapFloatsToFloatArray(positions),
                        offHeapFloatsToFloatArray(uvs),
                        offHeapIntsToIntArray(indices),
                    ),
                )
                true
            },
        )
        return out
    }

    @Test
    fun buildFloorsInto_is_deterministic_across_invocations_on_mixed_region() {
        val region = mixedRegion()
        val first = runAndCollect(region)
        val second = runAndCollect(region)
        assertEquals("floor count diverged", first.size, second.size)
        for (i in first.indices) {
            val (aPos, aUv, aIdx) = first[i]
            val (bPos, bUv, bIdx) = second[i]
            assertTrue("floor[$i] positions diverged",
                aPos.contentEquals(bPos))
            assertTrue("floor[$i] uvs diverged",
                aUv.contentEquals(bUv))
            assertTrue("floor[$i] indices diverged",
                aIdx.contentEquals(bIdx))
        }
    }

    @Test
    fun buildFloorsInto_is_deterministic_across_invocations_on_tall_split_region() {
        val region = tallRegion()
        val first = runAndCollect(region, GlbExportOptions(floorHeight = 2))
        val second = runAndCollect(region, GlbExportOptions(floorHeight = 2))
        assertEquals("floor count diverged", first.size, second.size)
        for (i in first.indices) {
            val (aPos, aUv, aIdx) = first[i]
            val (bPos, bUv, bIdx) = second[i]
            assertTrue("floor[$i] positions diverged",
                aPos.contentEquals(bPos))
            assertTrue("floor[$i] uvs diverged",
                aUv.contentEquals(bUv))
            assertTrue("floor[$i] indices diverged",
                aIdx.contentEquals(bIdx))
        }
    }

    @Test
    fun countFloorStats_is_deterministic_across_invocations() {
        val region = mixedRegion()
        val builder = MeshBuilder(
            modelResolver = ModelResolver(emptyList()),
            texturePacker = TexturePacker(emptyList()),
            enableTinting = false,
        )
        val a = builder.countFloorStats(region, GlbExportOptions())
        val b = builder.countFloorStats(region, GlbExportOptions())
        assertEquals("floorCount", a.floorCount, b.floorCount)
        assertTrue("perFloorVertices diverged",
            a.perFloorVertices.contentEquals(b.perFloorVertices))
        assertTrue("perFloorIndices diverged",
            a.perFloorIndices.contentEquals(b.perFloorIndices))
        assertEquals("totalPositions", a.totalPositions, b.totalPositions)
        assertEquals("totalNormals", a.totalNormals, b.totalNormals)
        assertEquals("totalUvs", a.totalUvs, b.totalUvs)
        assertEquals("totalIndices", a.totalIndices, b.totalIndices)
        assertEquals("minX", a.minX, b.minX)
        assertEquals("maxZ", a.maxZ, b.maxZ)
    }
}
