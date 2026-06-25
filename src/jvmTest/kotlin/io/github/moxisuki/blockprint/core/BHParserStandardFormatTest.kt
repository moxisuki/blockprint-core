package io.github.moxisuki.blockprint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BHParserStandardFormatTest {

    // This is the exact standard format from the user
    private val standardJson = """
{
  "name": "kkk",
  "statePosArrayList": "{blockstatemap:[{Name:\"minecraft:grass_block\",Properties:{snowy:\"false\"}}],endpos:{X:6,Y:0,Z:0},startpos:{X:0,Y:0,Z:-1},statelist:[I;0,0,0,0,0,0,0,0,0,0,0,0,0,0]}",
  "requiredItems": {
    "minecraft:Reference{ResourceKey[minecraft:item / minecraft:grass_block]=minecraft:grass_block}": 14
  }
}""".trimIndent()

    @Test
    fun parse_standard_format() {
        val bytes = standardJson.toByteArray(Charsets.UTF_8)
        val lit = LitematicReader.readLenient(bytes)
        assertEquals(1, lit.regions.size)
        val r = lit.regions.single()
        assertEquals(7, r.width)   // endpos.X(6) - startpos.X(0) + 1
        assertEquals(1, r.height)  // Y=0
        assertEquals(2, r.depth)   // endpos.Z(0) - startpos.Z(-1) + 1
        assertEquals(1, r.palette.size) // only grass_block
        assertEquals(14, r.rawBlocks.size)
        assertEquals(0, r.rawBlocks.count { it != 0 })
        // Check the block state has properties
        val bs = r.palette.entries.single()
        assertEquals("minecraft:grass_block", bs.name)
        assertNotNull(bs.properties)
        assertEquals("false", bs.properties!!["snowy"])
    }

    @Test
    fun our_writer_produces_parseable_output() {
        val palette = BlockPalette(listOf(
            BlockState("minecraft:grass_block", mapOf("snowy" to "false"))
        ))
        val region = LitematicRegion("Default", 7, 1, 2, Position(-1, 0, -1), palette, IntArray(14))
        val lit = Litematic(null, null, "", "", "", listOf(region), SchematicFormat.BuildingHelper)
        val bytes = BlueprintConverter.convert(lit, SchematicFormat.BuildingHelper)
        val text = bytes.decodeToString()
        System.err.println(">>> OUR BH:")
        System.err.println(text)
        // Read back
        val lit2 = LitematicReader.readLenient(bytes)
        val r2 = lit2.regions.single()
        System.err.println(">>> OUR round-trip: " + r2.width + "x" + r2.height + "x" + r2.depth + " pal=" + r2.palette.size)
        assertEquals(7, r2.width)
        assertEquals(1, r2.height)
        assertEquals(2, r2.depth)
        assertEquals(1, r2.palette.size)
        val bs = r2.palette.entries.single()
        assertEquals("minecraft:grass_block", bs.name)
        assertNotNull(bs.properties)
        assertEquals("false", bs.properties!!["snowy"])
    }
}
