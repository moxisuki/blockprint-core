package io.github.moxisuki.blockprint.core.glb.mesh

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.model.BlockPrintRegion
import io.github.moxisuki.blockprint.core.glb.texture.AtlasEntry
import io.github.moxisuki.blockprint.core.glb.texture.PackedAtlas
import io.github.moxisuki.blockprint.core.glb.texture.TexturePacker
import io.github.moxisuki.blockprint.core.glb.model.ModelResolver
import io.github.moxisuki.blockprint.core.glb.writer.GlbExportOptions
import io.github.moxisuki.blockprint.core.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Area B — `countFloorStats` with atlas bbox test.
 *
 * Before the fix, `BlockPrintToGlb.run` ran `buildFloorsInto` twice
 * (Pass 1 counting, Pass 2 emitting). Pass 1's sink scanned the
 * position OffHeapBuf in 4 KiB chunks to compute min/max — a full
 * pass over the entire geometry just to recover the bbox, even
 * though `countFloorStats` already tracks the same bbox in its
 * inlined face-counting loop.
 *
 * The catch: `countFloorStats` did NOT model the atlas-lookup drop
 * (faces whose texture isn't in the atlas are skipped in
 * processFaceInto but counted in countFloorStats). So its bbox and
 * perFloorVertices were slightly higher than Pass 1's actual
 * emitted values.
 *
 * This test pins the post-fix contract: `countFloorStats` accepts
 * an optional `atlas` parameter; when supplied, the per-cell
 * face-counting loop applies the same atlas-lookup drop as
 * processFaceInto. The perFloorVertices/perFloorIndices/Indices
 * and the bbox returned by countFloorStats then match what
 * processFaceInto would emit.
 */
class CountFloorStatsAtlasTest {

    private fun assetsOrSkip(): java.nio.file.Path? {
        val dir = Paths.get("test/assets")
        if (!Files.isDirectory(dir)) return null
        return dir
    }

    /**
     * A simple 4x1x1 region with two fence cells. Without an atlas
     * the count is over-counted (face entries whose texture isn't
     * in the atlas are still counted). With the placeholder atlas
     * the count matches what processFaceInto would emit (face
     * entries whose texture isn't in the atlas are dropped).
     */
    private fun fenceRegion(): BlockPrintRegion {
        val palette = BlockPalette(
            listOf(
                BlockState("minecraft:air"),
                BlockState("minecraft:oak_fence"),
            ),
        )
        val blocks = intArrayOf(0, 1, 1)
        return BlockPrintRegion(
            name = "Fence", width = 3, height = 1, depth = 1,
            position = Position.ZERO,
            palette = palette, blocks = blocks,
        )
    }

    @Test
    fun countFloorStats_without_atlas_overcounts_vs_buildFloorsInto() {
        val assets = assetsOrSkip() ?: return
        val region = fenceRegion()
        val resolver = ModelResolver(listOf(assets))
        val packer = TexturePacker(listOf(assets))
        val builder = MeshBuilder(resolver, packer, enableTinting = false)
        val options = GlbExportOptions()

        // With a placeholder atlas (only one texture) most fence
        // faces' textures won't be in the atlas; they get dropped
        // by processFaceInto.
        val atlas = packer.pack(setOf("minecraft:block/oak_planks"), emptyMap(), emptyMap())

        // The "atlasless" count: countFloorStats sees every face
        // and counts it. This is the legacy over-counting behaviour.
        val statsNoAtlas = builder.countFloorStats(region, options, atlas = null)

        // The Pass 1 sink count via buildFloorsInto: counts only
        // what processFaceInto would emit (post-atlas-lookup-drop).
        var pass1TotalVertices = 0
        var pass1TotalIndices = 0
        builder.buildFloorsInto(
            region = region, options = options, atlas = atlas,
            sink = FloorSink { _, _, _, p, u, _, i ->
                pass1TotalVertices += p.sizeBytes() / 12
                pass1TotalIndices += i.sizeBytes() / 4
            },
        )

        // The over-counting happens because countFloorStats doesn't
        // know about the atlas. After the fix, countFloorStats(region,
        // options, atlas) should match Pass 1's counts exactly.
        val statsWithAtlas = builder.countFloorStats(region, options, atlas = atlas)

        assertTrue(
            "without atlas, countFloorStats over-counts vs Pass 1: " +
                "noAtlas=${statsNoAtlas.totalPositions / 3} pass1=$pass1TotalVertices",
            statsNoAtlas.totalPositions / 3 > pass1TotalVertices,
        )
        assertEquals(
            "with atlas, countFloorStats should match Pass 1 exactly",
            pass1TotalVertices, statsWithAtlas.totalPositions / 3,
        )
        assertEquals(
            "with atlas, totalIndices should match Pass 1",
            pass1TotalIndices, statsWithAtlas.totalIndices,
        )
    }
}
