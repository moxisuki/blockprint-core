package io.github.moxisuki.blockprint.core.glb

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.LitematicRegion
import io.github.moxisuki.blockprint.core.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshBuilderCountFloorStatsTest {

    private fun solidStoneCube(): LitematicRegion {
        val palette = BlockPalette(
            listOf(
                BlockState("minecraft:air"),
                BlockState("minecraft:stone"),
            ),
        )
        val blocks = intArrayOf(1, 1, 1, 1)
        return LitematicRegion(
            name = "Solid",
            width = 2, height = 1, depth = 2,
            position = Position.ZERO,
            palette = palette,
            blocks = blocks,
        )
    }

    @Test
    fun countFloorStats_returns_single_floor_for_no_floorSplit() {
        val builder = MeshBuilder(
            modelResolver = ModelResolver(emptyList()),
            texturePacker = TexturePacker(emptyList()),
            enableTinting = false,
        )
        val region = solidStoneCube()
        val stats = builder.countFloorStats(region, GlbExportOptions())
        assertEquals(1, stats.floorCount)
        assertEquals(0, stats.perFloorVertices[0])
        assertEquals(0, stats.perFloorIndices[0])
    }

    @Test
    fun countFloorStats_initializes_arrays_to_floorCount() {
        val builder = MeshBuilder(
            modelResolver = ModelResolver(emptyList()),
            texturePacker = TexturePacker(emptyList()),
            enableTinting = false,
        )
        val region = solidStoneCube()
        val stats = builder.countFloorStats(region, GlbExportOptions(floorHeight = 1))
        assertEquals(1, stats.floorCount)
        assertEquals(stats.floorCount, stats.perFloorVertices.size)
        assertEquals(stats.floorCount, stats.perFloorIndices.size)
    }

    @Test
    fun countFloorStats_bbox_initialized_to_extreme_values_when_no_blocks() {
        val builder = MeshBuilder(
            modelResolver = ModelResolver(emptyList()),
            texturePacker = TexturePacker(emptyList()),
            enableTinting = false,
        )
        val palette = BlockPalette(listOf(BlockState("minecraft:air")))
        val region = LitematicRegion(
            name = "Empty",
            width = 1, height = 1, depth = 1,
            position = Position.ZERO,
            palette = palette,
            blocks = intArrayOf(0),
        )
        val stats = builder.countFloorStats(region, GlbExportOptions())
        assertEquals(0, stats.totalPositions)
        assertEquals(0, stats.totalIndices)
        // When no faces are visible, min should be > max as a "no data" sentinel.
        assertTrue("minX=${stats.minX} should be > maxX=${stats.maxX} when no blocks",
            stats.minX > stats.maxX)
    }
}
