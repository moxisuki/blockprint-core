package io.github.moxisuki.blockprint.core.internal.format

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.Litematic
import io.github.moxisuki.blockprint.core.LitematicReader
import io.github.moxisuki.blockprint.core.LitematicRegion
import io.github.moxisuki.blockprint.core.Position
import io.github.moxisuki.blockprint.core.SchematicFormat
import io.github.moxisuki.blockprint.core.exceptions.LitematicException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SpongeWriterTest {

    private fun sampleLitematic(): Litematic {
        val palette = BlockPalette(
            listOf(
                BlockState("minecraft:air"),
                BlockState("minecraft:stone"),
                BlockState("minecraft:dirt"),
            ),
        )
        // 1x1x2 region: stone, dirt (x-major, y-major fallback with h=1).
        val blocks = intArrayOf(1, 2)
        val region = LitematicRegion(
            name = "SpongeSample",
            width = 1, height = 1, depth = 2,
            position = Position(0, 0, 0),
            palette = palette,
            blocks = blocks,
        )
        return Litematic(
            minecraftDataVersion = 3465,
            version = null,
            name = "Sponge Build",
            author = "Author",
            description = "test",
            regions = listOf(region),
            format = SchematicFormat.Sponge,
        )
    }

    @Test
    fun write_then_read_round_trips() {
        val lit = sampleLitematic()
        val bytes = SpongeWriter.write(lit)
        val read = LitematicReader.read(bytes)
        assertEquals(1, read.regions.size)
        val r = read.regions.single()
        assertEquals(1, r.width); assertEquals(1, r.height); assertEquals(2, r.depth)
        assertArrayEquals(intArrayOf(1, 2), r.rawBlocks)
        assertEquals(3, r.palette.size)
    }

    @Test
    fun write_does_not_gzip() {
        val bytes = SpongeWriter.write(sampleLitematic())
        // Sponge spec is raw NBT; first byte must be the compound tag id 0x0A.
        assertEquals(0x0A.toByte(), bytes[0])
        // Sanity: NOT a gzip header.
        assertNotEquals(0x1F.toByte(), bytes[0])
    }

    @Test
    fun write_rejects_multi_region_input() {
        val a = sampleLitematic().regions.single()
        val b = LitematicRegion(
            name = "Other", width = 1, height = 1, depth = 1,
            position = Position.ZERO,
            palette = BlockPalette(listOf(BlockState("minecraft:air"), BlockState("minecraft:bedrock"))),
            blocks = intArrayOf(1),
        )
        val multi = sampleLitematic().copy(regions = listOf(a, b))
        try {
            SpongeWriter.write(multi)
            assert(false) { "expected LitematicException" }
        } catch (e: LitematicException) {
            // expected
        }
    }

    @Test
    fun varint_encodes_small_palette_in_one_byte_each() {
        // All-zero (air) region with palette size 2 → 4 cells, all 0 → 4 varint bytes.
        val allAir = LitematicRegion(
            name = "Empty", width = 2, height = 1, depth = 2,
            position = Position.ZERO,
            palette = BlockPalette(listOf(BlockState("minecraft:air"), BlockState("minecraft:stone"))),
            blocks = IntArray(4),
        )
        val lit = sampleLitematic().copy(regions = listOf(allAir))
        val bytes = SpongeWriter.write(lit)
        val read = LitematicReader.read(bytes)
        assertArrayEquals(IntArray(4), read.regions.single().rawBlocks)
    }
}
