package io.github.moxisuki.blockprint.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import io.github.moxisuki.blockprint.core.exceptions.LitematicException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * End-to-end tests built on top of [TestNbt]. We construct a real litematic
 * byte stream in memory, feed it through [LitematicReader.read], and check
 * the resulting domain model.
 */
class LitematicReaderTest {

    @Test
    fun `round trips a 2x2x2 region`() {
        // Palette: 0=air, 1=stone, 2=oak_planks
        val palette = listOf(
            "minecraft:air" to null,
            "minecraft:stone" to null,
            "minecraft:oak_planks" to null,
        )
        // y-major: index = y*4 + z*2 + x. Build a checker pattern in y=0, fill y=1 with stone.
        val blocks = intArrayOf(
            // y=0, z=0: stone, oak ; z=1: oak, stone
            1, 2, 2, 1,
            // y=1: all stone
            1, 1, 1, 1,
        )
        val bytes = TestNbt.buildLitematic(2, 2, 2, palette, blocks)

        val lit = LitematicReader.read(bytes)
        assertEquals("Test", lit.name)
        assertEquals("litematic-lib", lit.author)
        assertEquals(6, lit.version)
        assertEquals(3953, lit.minecraftDataVersion)
        assertEquals(1, lit.regions.size)

        val r = lit.regions.single()
        assertEquals("TestRegion", r.name)
        assertEquals(2, r.width)
        assertEquals(2, r.height)
        assertEquals(2, r.depth)
        assertEquals(Position.ZERO, r.position)
        assertEquals(3, r.palette.size)

        for (i in blocks.indices) {
            assertEquals(blocks[i], r.rawBlocks[i], "block index $i")
        }
        // Resolved block names
        assertEquals("minecraft:air", lit.regions[0].palette[0].name)
        assertEquals("minecraft:stone", lit.regions[0].palette[1].name)
        assertEquals("minecraft:oak_planks", lit.regions[0].palette[2].name)
    }

    @Test
    fun `material list counts non-air blocks`() {
        val palette = listOf(
            "minecraft:air" to null,
            "minecraft:stone" to null,
            "minecraft:oak_planks" to null,
        )
        // 8 blocks: 5 stone, 2 planks, 1 air
        val blocks = intArrayOf(1, 1, 1, 1, 1, 2, 2, 0)
        val bytes = TestNbt.buildLitematic(2, 1, 4, palette, blocks)
        val lit = LitematicReader.read(bytes)
        val mats = MaterialList.from(lit)
        assertEquals(5, mats["minecraft:stone"])
        assertEquals(2, mats["minecraft:oak_planks"])
        assertNull(mats["minecraft:air"])
        assertEquals(7, lit.blockCount())
    }

    @Test
    fun `preserves block-state properties`() {
        val palette = listOf(
            "minecraft:air" to null,
            "minecraft:oak_slab" to mapOf("type" to "top", "waterlogged" to "false"),
        )
        val blocks = intArrayOf(1, 1, 1, 1)
        val bytes = TestNbt.buildLitematic(2, 1, 2, palette, blocks)
        val lit = LitematicReader.read(bytes)
        val slab = lit.regions[0].palette[1]
        assertEquals("minecraft:oak_slab", slab.name)
        assertEquals("top", slab.properties?.get("type"))
        assertEquals("false", slab.properties?.get("waterlogged"))
    }

    @Test
    fun `reads gzipped input`() {
        val palette = listOf("minecraft:air" to null, "minecraft:stone" to null)
        val blocks = intArrayOf(1, 0, 0, 1)
        val raw = TestNbt.buildLitematic(2, 1, 2, palette, blocks)

        val gzipped = ByteArrayOutputStream().use { baos ->
            GZIPOutputStream(baos).use { gz -> gz.write(raw) }
            baos.toByteArray()
        }
        // Magic check
        assertEquals(0x1F.toByte(), gzipped[0])
        assertEquals(0x8B.toByte(), gzipped[1])

        val lit = LitematicReader.read(ByteArrayInputStream(gzipped))
        assertEquals(1, lit.regions[0].rawBlocks[0])
        assertEquals(1, lit.regions[0].rawBlocks[3])
    }

    @Test
    fun `region position is honoured`() {
        val palette = listOf("minecraft:air" to null, "minecraft:stone" to null)
        val blocks = intArrayOf(1)
        val bytes = TestNbt.buildLitematic(
            1, 1, 1, palette, blocks, origin = Triple(7, 64, -3),
        )
        val lit = LitematicReader.read(bytes)
        assertEquals(Position(7, 64, -3), lit.regions[0].position)
    }

    @Test
    fun `missing Regions field throws`() {
        val baos = ByteArrayOutputStream()
        java.io.DataOutputStream(baos).use { out ->
            out.writeByte(10); out.writeUTF("")
            out.writeByte(8); out.writeUTF("Name"); out.writeUTF("empty")
            out.writeByte(0)
        }
        assertThrows<LitematicException> { LitematicReader.read(baos.toByteArray()) }
    }

    @Test
    fun `multi-region file preserves all regions`() {
        val palette = listOf("minecraft:air" to null, "minecraft:stone" to null)
        val combined = multiRegionBytes(
            palette,
            listOf("RegionA" to intArrayOf(1), "RegionB" to intArrayOf(1)),
        )
        val lit = LitematicReader.read(combined)
        assertEquals(2, lit.regions.size)
        assertEquals(listOf("RegionA", "RegionB"), lit.regions.map { it.name })
        assertTrue(lit.regions.all { it.rawBlocks.single() == 1 })
    }

    private fun multiRegionBytes(
        palette: List<Pair<String, Map<String, String>?>>,
        regions: List<Pair<String, IntArray>>,
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        java.io.DataOutputStream(baos).use { out ->
            out.writeByte(10); out.writeUTF("") // root compound
            out.writeByte(8); out.writeUTF("Name"); out.writeUTF("multi")
            out.writeByte(3); out.writeUTF("Version"); out.writeInt(6)
            out.writeByte(10); out.writeUTF("Regions")
            for ((rname, blocks) in regions) {
                out.writeByte(10); out.writeUTF(rname)
                out.writeByte(10); out.writeUTF("Size")
                out.writeByte(3); out.writeUTF("x"); out.writeInt(1)
                out.writeByte(3); out.writeUTF("y"); out.writeInt(1)
                out.writeByte(3); out.writeUTF("z"); out.writeInt(1)
                out.writeByte(0) // end Size
                out.writeByte(9); out.writeUTF("BlockStatePalette")
                out.writeByte(10); out.writeInt(palette.size)
                for ((name, _) in palette) {
                    // Compound elements inside a list start with their first
                    // child entry directly — no outer tag id / name.
                    out.writeByte(8); out.writeUTF("Name"); out.writeUTF(name)
                    out.writeByte(0)
                }
                out.writeByte(12); out.writeUTF("BlockStates")
                val packed = TestNbt.pack(blocks, TestNbt.nbitsFor(palette.size))
                out.writeInt(packed.size)
                for (l in packed) out.writeLong(l)
                out.writeByte(0) // end region
            }
            out.writeByte(0) // end Regions
            out.writeByte(0) // end root
        }
        return baos.toByteArray()
    }
}
