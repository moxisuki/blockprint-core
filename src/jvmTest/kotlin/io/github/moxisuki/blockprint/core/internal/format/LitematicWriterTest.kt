package io.github.moxisuki.blockprint.core.internal.format

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.Litematic
import io.github.moxisuki.blockprint.core.LitematicReader
import io.github.moxisuki.blockprint.core.LitematicRegion
import io.github.moxisuki.blockprint.core.Position
import io.github.moxisuki.blockprint.core.SchematicFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LitematicWriterTest {

    private fun buildSampleLitematic(): Litematic {
        // 2x1x2 region with 4 distinct block types + air.
        val palette = BlockPalette(
            listOf(
                BlockState("minecraft:air"),
                BlockState("minecraft:stone"),
                BlockState("minecraft:dirt"),
                BlockState("minecraft:grass_block", mapOf("snowy" to "false")),
            ),
        )
        // y-major: [stone, dirt, grass, stone]
        val blocks = intArrayOf(1, 2, 3, 1)
        val region = LitematicRegion(
            name = "Sample",
            width = 2, height = 1, depth = 2,
            position = Position(10, 64, -5),
            palette = palette,
            blocks = blocks,
        )
        return Litematic(
            minecraftDataVersion = 3465,
            version = 6,
            name = "Sample Build",
            author = "Tester",
            description = "unit test",
            regions = listOf(region),
            format = SchematicFormat.Litematica,
        )
    }

    @Test
    fun write_then_read_round_trips_region() {
        val original = buildSampleLitematic()
        val bytes = LitematicWriter.write(original)
        val read = LitematicReader.read(bytes)
        assertEquals(1, read.regions.size)
        val r = read.regions.single()
        assertEquals("Sample", r.name)
        assertEquals(2, r.width); assertEquals(1, r.height); assertEquals(2, r.depth)
        assertEquals(Position(10, 64, -5), r.position)
        assertEquals(4, r.palette.size)
        assertArrayEquals(intArrayOf(1, 2, 3, 1), r.rawBlocks)
        assertEquals(3465, read.minecraftDataVersion)
        assertEquals(6, read.version)
        assertEquals("Sample Build", read.name)
        assertEquals("Tester", read.author)
    }

    @Test
    fun write_produces_gzipped_output() {
        val bytes = LitematicWriter.write(buildSampleLitematic())
        assertEquals(0x1F.toByte(), bytes[0])
        assertEquals(0x8B.toByte(), bytes[1])
    }

    @Test
    fun write_multi_region_preserves_all() {
        val first = buildSampleLitematic().regions.single()
        val second = LitematicRegion(
            name = "Second",
            width = 1, height = 1, depth = 1,
            position = Position(0, 0, 0),
            palette = BlockPalette(listOf(BlockState("minecraft:air"), BlockState("minecraft:bedrock"))),
            blocks = intArrayOf(1),
        )
        val lit = buildSampleLitematic().copy(regions = listOf(first, second))
        val bytes = LitematicWriter.write(lit)
        val read = LitematicReader.read(bytes)
        assertEquals(2, read.regions.size)
        assertEquals(listOf("Sample", "Second"), read.regions.map { it.name })
    }
}
