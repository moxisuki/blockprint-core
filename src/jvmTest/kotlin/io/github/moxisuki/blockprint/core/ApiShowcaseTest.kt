package io.github.moxisuki.blockprint.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.zip.GZIPOutputStream

/**
 * Tests that demonstrate the public API of [LitematicReader] and related types.
 * Use this as a usage reference — every public operation is shown here.
 */
class ApiShowcaseTest {

    // ---------------------------------------------------------------------------
    // 1. Read from a File
    // ---------------------------------------------------------------------------
    @Test
    fun `read from File`() {
        // val lit = LitematicReader.read(File("test.litematic"))
        // println(lit.name)          // schematic name
        // println(lit.author)        // author
        // println(lit.regions.size)  // number of regions
        assertNotNull(LitematicReader::class.java) // just verify the API is accessible
    }

    // ---------------------------------------------------------------------------
    // 2. Read from an InputStream (network, zip entry, etc.)
    // ---------------------------------------------------------------------------
    @Test
    fun `read from InputStream`() {
        val palette = listOf(
            "minecraft:air" to null,
            "minecraft:stone" to null,
        )
        val blocks = intArrayOf(1, 0, 0, 1) // 2×1×2: stone at corners, air elsewhere
        val raw = TestNbt.buildLitematic(2, 1, 2, palette, blocks)

        // LitematicReader reads and closes the stream.
        val lit = LitematicReader.read(ByteArrayInputStream(raw))

        assertEquals(1, lit.regions.size)
        assertEquals("TestRegion", lit.regions[0].name)
    }

    // ---------------------------------------------------------------------------
    // 3. Read from a ByteArray (raw or gzipped — auto-detected)
    // ---------------------------------------------------------------------------
    @Test
    fun `read gzipped byte array`() {
        val palette = listOf(
            "minecraft:air" to null,
            "minecraft:oak_planks" to null,
        )
        val blocks = intArrayOf(1, 1, 1, 1) // 2×1×2: all planks
        val raw = TestNbt.buildLitematic(2, 1, 2, palette, blocks)

        // Gzip-wrap it (many litematic files are stored gzipped)
        val gzipped = java.io.ByteArrayOutputStream().use { baos ->
            GZIPOutputStream(baos).use { gz -> gz.write(raw) }
            baos.toByteArray()
        }
        // Magic check: 0x1F 0x8B
        assertEquals(0x1F.toByte(), gzipped[0])
        assertEquals(0x8B.toByte(), gzipped[1])

        // LitematicReader auto-detects gzip and decompresses.
        val lit = LitematicReader.read(gzipped)
        assertEquals(4, lit.regions[0].rawBlocks.size)
    }

    // ---------------------------------------------------------------------------
    // 4. Inspect schematic metadata
    // ---------------------------------------------------------------------------
    @Test
    fun `inspect metadata`() {
        val palette = listOf("minecraft:air" to null, "minecraft:stone" to null)
        val lit = LitematicReader.read(TestNbt.buildLitematic(
            width = 1, height = 1, depth = 2,
            palette = palette,
            blockIndices = intArrayOf(1, 1),
            name = "My House",
            author = "PlayerOne",
            version = 6,
            mcDataVersion = 3953,
        ))

        assertEquals("My House",   lit.name)
        assertEquals("PlayerOne",  lit.author)
        assertEquals(6,            lit.version)
        assertEquals(3953,         lit.minecraftDataVersion)
    }

    // ---------------------------------------------------------------------------
    // 5. Inspect a region's dimensions and palette
    // ---------------------------------------------------------------------------
    @Test
    fun `inspect region dimensions`() {
        val palette = listOf(
            "minecraft:air" to null,
            "minecraft:deepslate" to null,
            "minecraft:oak_log" to mapOf("axis" to "y"),
        )
        val blocks = intArrayOf(1, 2, 1, 2, 0, 0) // 2×1×3 = 6 blocks
        val lit = LitematicReader.read(TestNbt.buildLitematic(
            width = 2, height = 1, depth = 3,
            palette = palette,
            blockIndices = blocks,
        ))

        val region = lit.primaryRegion!!
        assertEquals(2, region.width)
        assertEquals(1, region.height)
        assertEquals(3, region.depth)

        // Palette: index → BlockState
        assertEquals("minecraft:air",       region.palette[0].name)
        assertEquals("minecraft:deepslate", region.palette[1].name)
        assertEquals("minecraft:oak_log",    region.palette[2].name)

        // Block with properties
        assertEquals("y", region.palette[2].properties?.get("axis"))
    }

    // ---------------------------------------------------------------------------
    // 6. Access blocks by (x, y, z) coordinate
    // ---------------------------------------------------------------------------
    @Test
    fun `access blocks by coordinates`() {
        val palette = listOf(
            "minecraft:air" to null,
            "minecraft:glass" to null,
        )
        // 2×2×1 slab: y=0: glass at x=0,1; y=1: all air
        val blocks = intArrayOf(1, 1, 0, 0)
        val lit = LitematicReader.read(TestNbt.buildLitematic(
            width = 2, height = 2, depth = 1,
            palette = palette,
            blockIndices = blocks,
        ))

        val region = lit.primaryRegion!!

        // getBlock returns the raw palette index
        assertEquals(1, region.getBlock(0, 0, 0)) // glass
        assertEquals(0, region.getBlock(0, 1, 0)) // air

        // blockAt resolves to a BlockState in one step
        assertEquals("minecraft:glass", region.blockAt(0, 0, 0).name)
        assertEquals("minecraft:air",   region.blockAt(0, 1, 0).name)

        // isAir convenience
        assertFalse(region.isAir(0, 0, 0))
        assertTrue( region.isAir(0, 1, 0))
    }

    // ---------------------------------------------------------------------------
    // 7. Iterate all blocks via raw array
    // ---------------------------------------------------------------------------
    @Test
    fun `iterate all blocks via raw array`() {
        val palette = listOf(
            "minecraft:air" to null,
            "minecraft:bricks" to null,
        )
        val blocks = intArrayOf(1, 0, 0, 1) // 2×1×2 checker
        val lit = LitematicReader.read(TestNbt.buildLitematic(
            width = 2, height = 1, depth = 2,
            palette = palette,
            blockIndices = blocks,
        ))

        val region = lit.primaryRegion!!
        val raw = region.rawBlocks // IntArray, y-major order: index = y*W*D + z*W + x

        assertEquals(4, raw.size)
        assertEquals(1, raw[0]) // (0,0,0) → brick
        assertEquals(0, raw[1]) // (1,0,0) → air
        assertEquals(0, raw[2]) // (0,0,1) → air
        assertEquals(1, raw[3]) // (1,0,1) → brick

        // rawIndex mirrors the y-major formula
        assertEquals(0, region.rawIndex(0, 0, 0))
        assertEquals(1, region.rawIndex(1, 0, 0))
        assertEquals(2, region.rawIndex(0, 0, 1))
        assertEquals(3, region.rawIndex(1, 0, 1))
    }

    // ---------------------------------------------------------------------------
    // 8. Region position (origin offset in world coordinates)
    // ---------------------------------------------------------------------------
    @Test
    fun `region has world position`() {
        val palette = listOf("minecraft:air" to null, "minecraft:stone" to null)
        val lit = LitematicReader.read(TestNbt.buildLitematic(
            width = 1, height = 1, depth = 1,
            palette = palette,
            blockIndices = intArrayOf(1),
            origin = Triple(100, 64, -30),
        ))

        val pos = lit.primaryRegion!!.position
        assertEquals(100,  pos.x)
        assertEquals(64,   pos.y)
        assertEquals(-30,  pos.z)
    }

    // ---------------------------------------------------------------------------
    // 9. Material list — count each block type
    // ---------------------------------------------------------------------------
    @Test
    fun `material list`() {
        val palette = listOf(
            "minecraft:air" to null,
            "minecraft:cobblestone" to null,
            "minecraft:mossy_cobblestone" to null,
            "minecraft:oak_door" to mapOf("half" to "lower"),
        )
        val blocks = intArrayOf(1, 2, 0) // cobble, mossy, air
        val lit = LitematicReader.read(TestNbt.buildLitematic(
            width = 1, height = 1, depth = 3,
            palette = palette,
            blockIndices = blocks,
        ))

        val mats = MaterialList.from(lit) // air is skipped by default

        // Sorted by count descending
        val sorted = mats.toSortedByCount()
        assertEquals("minecraft:cobblestone",      sorted[0].first)
        assertEquals(1, sorted[0].second)
        assertEquals("minecraft:mossy_cobblestone", sorted[1].first)
        assertEquals(1, sorted[1].second)
        assertEquals(2, sorted.size) // air is excluded

        // Include air blocks
        val withAir = MaterialList.from(lit, includeAir = true)
        assertEquals(3, withAir.size)
        assertEquals(1, withAir["minecraft:air"])
    }

    // ---------------------------------------------------------------------------
    // 10. Multi-region files (all regions preserved)
    // ---------------------------------------------------------------------------
    @Test
    fun `multi-region`() {
        val palette = listOf("minecraft:air" to null, "minecraft:stone" to null)
        val bytes = multiRegionBytes(palette, listOf(
            "Foundation" to intArrayOf(1),
            "Walls"      to intArrayOf(1),
        ))
        val lit = LitematicReader.read(bytes)

        assertEquals(2, lit.regions.size)
        assertEquals(listOf("Foundation", "Walls"), lit.regions.map { it.name })
    }

    // ---------------------------------------------------------------------------
    // 11. Total block count (with/without air)
    // ---------------------------------------------------------------------------
    @Test
    fun `block count`() {
        val palette = listOf("minecraft:air" to null, "minecraft:stone" to null)
        // 2×1×2 = 4 slots: (0,0,0)=stone, (1,0,0)=air, (0,0,1)=air, (1,0,1)=stone
        // blockCount(true) = 4 (all); blockCount() = 2 (non-air only)
        val lit = LitematicReader.read(TestNbt.buildLitematic(
            width = 2, height = 1, depth = 2,
            palette = palette,
            blockIndices = intArrayOf(1, 0, 0, 1),
        ))

        assertEquals(4, lit.blockCount(includeAir = true))  // all 4 slots (including air)
        assertEquals(2, lit.blockCount())                   // 2 non-air (stone) blocks
    }

    // ---------------------------------------------------------------------------
    // Helper: build a multi-region litematic byte array
    // ---------------------------------------------------------------------------
    private fun multiRegionBytes(
        palette: List<Pair<String, Map<String, String>?>>,
        regions: List<Pair<String, IntArray>>,
    ): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(baos).use { out ->
            out.writeByte(10); out.writeUTF("") // root compound
            out.writeByte(8);  out.writeUTF("Name"); out.writeUTF("multi-region")
            out.writeByte(3);  out.writeUTF("Version"); out.writeInt(6)
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
                    out.writeByte(8); out.writeUTF("Name"); out.writeUTF(name)
                    out.writeByte(0)
                }
                out.writeByte(12); out.writeUTF("BlockStates")
                val packed = TestNbt.pack(blocks, TestNbt.nbitsFor(palette.size))
                out.writeInt(packed.size)
                for (l in packed) out.writeLong(l)
                out.writeByte(0) // end region
            }
            out.writeByte(0); out.writeByte(0) // end Regions + root
        }
        return baos.toByteArray()
    }
}