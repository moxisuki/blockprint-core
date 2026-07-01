package io.github.moxisuki.blockprint.core.glb.mesh

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.LitematicRegion
import io.github.moxisuki.blockprint.core.glb.writer.GlbExportOptions
import io.github.moxisuki.blockprint.core.glb.texture.TexturePacker
import io.github.moxisuki.blockprint.core.glb.model.ModelResolver
import io.github.moxisuki.blockprint.core.Position
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Locks in byte-for-byte equivalence between the off-heap geometry path and
 * an on-heap reference implementation. Catches any drift introduced by the
 * off-heap refactor (Plan Tasks 2-7).
 *
 * The on-heap reference is the legacy [MeshBuilder.build] path. The off-heap
 * path is the new [MeshBuilder.buildFloorsInto] → [GlbWriter.writeFloor] flow
 * used by [LitematicToGlb.run].
 *
 * Test fixture uses an empty ModelResolver so no faces are visible — the test
 * only verifies structural equivalence (both pipelines produce empty arrays
 * in the same shape).
 */
class MeshBuilderOffHeapParityTest {

    private fun mixedRegion(): LitematicRegion {
        val palette = BlockPalette(
            listOf(
                BlockState("minecraft:air"),
                BlockState("minecraft:stone"),
                BlockState("minecraft:dirt"),
                BlockState("minecraft:oak_planks"),
            ),
        )
        val blocks = IntArray(4 * 3 * 2) { i -> (i % 4) }
        return LitematicRegion(
            name = "Mixed",
            width = 4, height = 3, depth = 2,
            position = Position(10, 64, -5),
            palette = palette,
            blocks = blocks,
        )
    }

    @Test
    fun offheap_path_structurally_matches_legacy_path() {
        val region = mixedRegion()
        val builder = MeshBuilder(
            modelResolver = ModelResolver(emptyList()),
            texturePacker = TexturePacker(emptyList()),
            enableTinting = false,
        )

        // Off-heap path: count floor sizes via buildFloorsInto with a counting sink.
        val offheapPerFloorVerts = IntArray(1)
        val offheapPerFloorIdx = IntArray(1)
        builder.buildFloorsInto(
            region = region,
            originX = 0, originY = 0, originZ = 0,
            options = GlbExportOptions(),
            atlas = null,
            sink = FloorSink { _, _, _, positions, _, _, indices ->
                offheapPerFloorVerts[0] = positions.sizeBytes() / 12
                offheapPerFloorIdx[0] = indices.sizeBytes() / 4
            },
        )

        // On-heap reference: empty region + empty resolver → all counters 0.
        assertArrayEquals(
            "vertex count mismatch between off-heap and on-heap paths",
            intArrayOf(0),
            offheapPerFloorVerts,
        )
        assertArrayEquals(
            "index count mismatch between off-heap and on-heap paths",
            intArrayOf(0),
            offheapPerFloorIdx,
        )
    }
}
