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

class MeshBuilderBuildFloorsIntoTest {

    private fun solidStoneRegion() = BlockPrintRegion(
        name = "Solid",
        width = 2, height = 1, depth = 2,
        position = Position.ZERO,
        palette = BlockPalette(listOf(BlockState("minecraft:air"), BlockState("minecraft:stone"))),
        blocks = intArrayOf(1, 1, 1, 1),
    )

    @Test
    fun buildFloorsInto_sink_not_called_when_modelCache_is_empty() {
        // Empty ModelResolver produces no model elements, so no geometry is
        // generated and the sink is never invoked.
        val builder = MeshBuilder(
            modelResolver = ModelResolver(emptyList()),
            texturePacker = TexturePacker(emptyList()),
            enableTinting = false,
        )
        val floors = mutableListOf<Int>()
        builder.buildFloorsInto(
            region = solidStoneRegion(),
            originX = 0, originY = 0, originZ = 0,
            options = GlbExportOptions(),
            sink = FloorSink { floorIdx, _, _, _, _, _, _ -> floors.add(floorIdx) },
        )
        assertEquals(0, floors.size)
    }

    @Test
    fun buildFloorsInto_does_not_throw_with_floor_split() {
        val region = BlockPrintRegion(
            name = "Tall",
            width = 1, height = 4, depth = 1,
            position = Position.ZERO,
            palette = BlockPalette(listOf(BlockState("minecraft:air"))),
            blocks = intArrayOf(0, 0, 0, 0),
        )
        val builder = MeshBuilder(
            modelResolver = ModelResolver(emptyList()),
            texturePacker = TexturePacker(emptyList()),
            enableTinting = false,
        )
        builder.buildFloorsInto(
            region = region,
            originX = 0, originY = 0, originZ = 0,
            options = GlbExportOptions(floorHeight = 2),
            sink = FloorSink { _, _, _, _, _, _, _ -> },
        )
        assertTrue(true) // did not throw
    }
}