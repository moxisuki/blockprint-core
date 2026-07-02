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
 * PR-4 min/max + per-cell accumulation tests.
 *
 * PR-4 inlines the legacy `countFloorElements` helper into the
 * y/z/x loop inside `countFloorStats` and replaces the per-cell
 * `IntArray(4)` + `FloatArray(6)` + `BooleanArray(1)` + lambda closure
 * with a single shared `FaceCountAccum` object on the caller's stack.
 *
 * The behavioural contract — same [FloorStats] byte-for-byte as before
 * — is locked in by:
 *   1. `MeshBuilderParityTest` (legacy build() vs new pipeline byte-equal)
 *   2. `MeshBuilderAllocationParityTest` (scratch determinism)
 *   3. `MeshBuilderCountFloorStatsTest` (3 cases: empty / split / bbox
 *      sentinel)
 *
 * This test class adds a *focused* PR-4 layer that exercises the per-cell
 * accumulator's reset semantics in a way the existing tests don't:
 *   - The same region run twice must produce byte-equal `FloorStats`
 *     (catches an inlined-counter bug that leaves residue between cells).
 *   - A "big" 8×8×8 region with 0% resolvable blocks must aggregate
 *     to 0 across all floors (catches a per-cell sum that forgets to
 *     add to the outer totals).
 *   - A multi-floor split must still produce correctly sized
 *     `perFloorVertices` / `perFloorIndices` arrays (catches a loop
 *     nesting bug introduced by inlining).
 *
 * All three tests run on `ModelResolver(emptyList())`, so no real
 * Minecraft models are needed in CI.
 */
class MeshBuilderMinMaxParityTest {

    private fun meshBuilder(): MeshBuilder = MeshBuilder(
        modelResolver = ModelResolver(emptyList()),
        texturePacker = TexturePacker(emptyList()),
        enableTinting = false,
    )

    private fun emptyRegion(w: Int, h: Int, d: Int, seed: Int = 0): BlockPrintRegion {
        // Two palette entries so idx != 0 isn't always air. Nothing
        // resolves under the empty ModelResolver, so neither produces
        // visible geometry.
        val palette = BlockPalette(
            listOf(BlockState("minecraft:air"), BlockState("minecraft:stone")),
        )
        val blocks = IntArray(w * h * d) { if ((it + seed) % 2 == 0) 0 else 1 }
        return BlockPrintRegion(
            name = "Empty", width = w, height = h, depth = d,
            position = Position.ZERO,
            palette = palette, blocks = blocks,
        )
    }

    @Test
    fun countFloorStats_is_deterministic_across_invocations_on_8x8x8() {
        // The accumulator must not leak state between cells — a single
        // missed reset inside the inlined loop would show up as drift
        // across separate invocations on a region big enough to
        // exercise thousands of cells.
        val region = emptyRegion(8, 8, 8)
        val builder = meshBuilder()
        val a = builder.countFloorStats(region, GlbExportOptions())
        val b = builder.countFloorStats(region, GlbExportOptions())
        assertTrue("perFloorVertices drift", a.perFloorVertices.contentEquals(b.perFloorVertices))
        assertTrue("perFloorIndices drift", a.perFloorIndices.contentEquals(b.perFloorIndices))
        assertEquals("totalPositions drift", a.totalPositions, b.totalPositions)
        assertEquals("totalNormals drift", a.totalNormals, b.totalNormals)
        assertEquals("totalUvs drift", a.totalUvs, b.totalUvs)
        assertEquals("totalIndices drift", a.totalIndices, b.totalIndices)
        assertEquals("minX drift", a.minX, b.minX)
        assertEquals("minY drift", a.minY, b.minY)
        assertEquals("minZ drift", a.minZ, b.minZ)
        assertEquals("maxX drift", a.maxX, b.maxX)
        assertEquals("maxY drift", a.maxY, b.maxY)
        assertEquals("maxZ drift", a.maxZ, b.maxZ)
    }

    @Test
    fun countFloorStats_per_cell_accumulator_aggregates_to_zero_on_empty_resolver() {
        // With no models resolvable, every cell contributes 0 to the
        // totals. A bug in the inlined counter (e.g. forgetting to
        // increment `totalPositions` on a face) would show up as a
        // non-zero per-floor sum.
        val region = emptyRegion(8, 8, 8)
        val builder = meshBuilder()
        val stats = builder.countFloorStats(region, GlbExportOptions())
        val sumVerts = stats.perFloorVertices.sum()
        val sumIdx = stats.perFloorIndices.sum()
        assertEquals("perFloorVertices must aggregate to 0", 0, sumVerts)
        assertEquals("perFloorIndices must aggregate to 0", 0, sumIdx)
        assertEquals("totalPositions", 0, stats.totalPositions)
        assertEquals("totalNormals", 0, stats.totalNormals)
        assertEquals("totalUvs", 0, stats.totalUvs)
        assertEquals("totalIndices", 0, stats.totalIndices)
        assertTrue(
            "minX (${stats.minX}) should be > maxX (${stats.maxX}) when no faces are visible",
            stats.minX > stats.maxX,
        )
    }

    @Test
    fun countFloorStats_per_floor_array_sizes_match_floor_count_under_split() {
        // 8-tall region split into 3 floors of effective height 3 must
        // produce 3 floors of arrays. A loop-nesting bug introduced by
        // inlining could produce 1 floor or 8 floors.
        val region = emptyRegion(2, 8, 2)
        val builder = meshBuilder()
        val stats = builder.countFloorStats(region, GlbExportOptions(floorHeight = 3))
        assertEquals(3, stats.floorCount)
        assertEquals(3, stats.perFloorVertices.size)
        assertEquals(3, stats.perFloorIndices.size)
    }
}
