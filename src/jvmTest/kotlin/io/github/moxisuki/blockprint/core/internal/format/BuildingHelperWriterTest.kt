package io.github.moxisuki.blockprint.core.internal.format

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.Litematic
import io.github.moxisuki.blockprint.core.LitematicReader
import io.github.moxisuki.blockprint.core.LitematicRegion
import io.github.moxisuki.blockprint.core.Position
import io.github.moxisuki.blockprint.core.SchematicFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildingHelperWriterTest {

    private fun sampleLitematic(): Litematic {
        // 2x1x1 region: stone, dirt.
        val palette = BlockPalette(
            listOf(
                BlockState("minecraft:air"),
                BlockState("minecraft:stone"),
                BlockState("minecraft:dirt"),
            ),
        )
        val region = LitematicRegion(
            name = "BH",
            width = 2, height = 1, depth = 1,
            position = Position(5, 10, -3),
            palette = palette,
            blocks = intArrayOf(1, 2),
        )
        return Litematic(
            minecraftDataVersion = null,
            version = null,
            name = "BH Build",
            author = "Builder",
            description = "",
            regions = listOf(region),
            format = SchematicFormat.BuildingHelper,
        )
    }

    @Test
    fun write_then_readLenient_round_trips() {
        val lit = sampleLitematic()
        val bytes = BuildingHelperWriter.write(lit)
        val read = LitematicReader.readLenient(bytes)
        assertEquals(1, read.regions.size)
        val r = read.regions.single()
        assertEquals(2, r.width); assertEquals(1, r.height); assertEquals(1, r.depth)
        assertEquals(Position(5, 10, -3), r.position)
        assertEquals(3, r.palette.size)
        assertEquals(intArrayOf(1, 2).toList(), r.rawBlocks.toList())
        assertEquals("BH Build", read.name)
        assertEquals("Builder", read.author)
    }

    @Test
    fun write_emits_valid_json() {
        val bytes = BuildingHelperWriter.write(sampleLitematic())
        val s = bytes.decodeToString()
        // JSON must parse and have the three top-level keys.
        assertTrue(s.startsWith("{"))
        assertTrue(s.contains("\"name\""))
        assertTrue(s.contains("\"author\""))
        assertTrue(s.contains("\"statePosArrayList\""))
    }

    @Test
    fun statelist_length_matches_region_volume() {
        val bytes = BuildingHelperWriter.write(sampleLitematic())
        val s = bytes.decodeToString()
        // statelist should be "statelist:[I;<n0>,<n1>,...]"
        val marker = "statelist:[I;"
        val start = s.indexOf(marker)
        assertTrue(start >= 0)
        val end = s.indexOf(']', start)
        val list = s.substring(start + marker.length, end)
        val nums = list.split(",").map { it.trim() }
        assertEquals(2, nums.size) // 2*1*1
    }
}
