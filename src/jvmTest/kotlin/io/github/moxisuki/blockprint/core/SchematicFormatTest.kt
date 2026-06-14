package io.github.moxisuki.blockprint.core

import io.github.moxisuki.blockprint.core.SchematicFormat.Litematica
import io.github.moxisuki.blockprint.core.SchematicFormat.Sponge
import io.github.moxisuki.blockprint.core.SchematicFormat.Unknown
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Tests for [LitematicReader.readLenient] + [LitematicReader.detectFormat].
 * Verifies that partial / stripped NBT litematic files load
 * uniformly with the standard API, and that format detection
 * classifies them correctly.
 */
class SchematicFormatTest {

    // ----------------------------------------------------------------
    // readLenient — partial NBT files
    // ----------------------------------------------------------------

    @Test
    fun `readLenient loads test2_nbt as a vanilla Structure`() {
        val file = File("C:\\Users\\Administrator\\Documents\\CCO\\LitematicMobile\\test2.nbt")
        if (!file.isFile) return

        val lit = LitematicReader.readLenient(file)
        assertEquals(SchematicFormat.Structure, lit.format)
        assertEquals(1, lit.regions.size)
        val r = lit.primaryRegion!!
        assertEquals(27, r.width)
        assertEquals(16, r.height)
        assertEquals(27, r.depth)
    }

    @Test
    fun `readLenient loads test4_nbt as a vanilla Structure`() {
        val file = File("C:\\Users\\Administrator\\Documents\\CCO\\LitematicMobile\\test4.nbt")
        if (!file.isFile) return

        val lit = LitematicReader.readLenient(file)
        assertEquals(SchematicFormat.Structure, lit.format)
        val r = lit.primaryRegion!!
        assertEquals(17, r.width)
        assertEquals(7, r.height)
        assertEquals(11, r.depth)
    }

    @Test
    fun `MaterialList works on test2_nbt as a Structure`() {
        val file = File("C:\\Users\\Administrator\\Documents\\CCO\\LitematicMobile\\test2.nbt")
        if (!file.isFile) return

        val lit = LitematicReader.readLenient(file)
        val materials = MaterialList.from(lit)
        // test2 is a vanilla structure with sparse blocks → at least
        // some material types should be present (not empty).
        assertTrue(materials.isNotEmpty(), "expected materials in structure, got empty")
        assertNotNull(materials.toSortedByCount())
    }

    @Test
    fun `readLenient structure format produces palette with air at index 0`() {
        val file = File("C:\\Users\\Administrator\\Documents\\CCO\\LitematicMobile\\test2.nbt")
        if (!file.isFile) return

        val lit = LitematicReader.readLenient(file)
        val r = lit.primaryRegion!!
        // Air is always prepended at index 0 by parseStructure
        assertEquals("minecraft:air", r.palette[0].name)
        // Blocks array is dense (27x16x27)
        assertEquals(27 * 16 * 27, r.rawBlocks.size)
    }

    @Test
    fun `readLenient from InputStream overload works`() {
        val file = File("C:\\Users\\Administrator\\Documents\\CCO\\LitematicMobile\\test2.nbt")
        if (!file.isFile) return

        val lit = LitematicReader.readLenient(file.inputStream())
        assertEquals(27, lit.primaryRegion!!.width)
    }

    @Test
    fun `readLenient from byte array overload works`() {
        val file = File("C:\\Users\\Administrator\\Documents\\CCO\\LitematicMobile\\test2.nbt")
        if (!file.isFile) return

        val lit = LitematicReader.readLenient(file.readBytes())
        assertEquals(27, lit.primaryRegion!!.width)
    }

    @Test
    fun `Litematic format is exposed on the parsed object`() {
        val file = File("C:\\Users\\Administrator\\Documents\\CCO\\LitematicMobile\\test2.nbt")
        if (!file.isFile) return

        val lit = LitematicReader.readLenient(file)
        assertEquals(SchematicFormat.Structure, lit.format)
    }

    @Test
    fun `readLenient throws when there is no size metadata at all`() {
        // Build an NBT root with no Size, no size list, no EnclosingSize
        val raw = buildRawNbt(
            name = "no-size",
            entries = listOf(Triple("foo", NbtTagType.Int, intBytes(1))),
        )
        val ex = org.junit.jupiter.api.Assertions.assertThrows(
            Exception::class.java,
        ) { LitematicReader.readLenient(raw) }
        // Either the parser or the size-metadata error should surface
        val msg = ex.message ?: ex.cause?.message ?: ""
        assertTrue(
            msg.contains("Size") || msg.contains("size"),
            "expected size-metadata error, got: $msg",
        )
    }

    // ----------------------------------------------------------------
    // detectFormat
    // ----------------------------------------------------------------

    @Test
    fun `detectFormat test2_nbt is Structure`() {
        val file = File("C:\\Users\\Administrator\\Documents\\CCO\\LitematicMobile\\test2.nbt")
        if (!file.isFile) return
        assertEquals(SchematicFormat.Structure, LitematicReader.detectFormat(file))
    }

    @Test
    fun `detectFormat test4_nbt is Structure`() {
        val file = File("C:\\Users\\Administrator\\Documents\\CCO\\LitematicMobile\\test4.nbt")
        if (!file.isFile) return
        assertEquals(SchematicFormat.Structure, LitematicReader.detectFormat(file))
    }

    @Test
    fun `detectFormat synthetic Litematica returns Litematica`() {
        val raw = buildLitematicNbt(
            includeRegions = true,
            includeMetadata = false,
        )
        assertEquals(Litematica, LitematicReader.detectFormat(raw))
    }

    @Test
    fun `detectFormat synthetic Sponge returns Sponge`() {
        val raw = buildLitematicNbt(
            includeRegions = false,
            includeMetadata = true,
        )
        assertEquals(Sponge, LitematicReader.detectFormat(raw))
    }

    @Test
    fun `readLenient loads vanilla structure nbt as Structure format`() {
        val file = File("C:\\Users\\Administrator\\Documents\\xwechat_files\\wxid_eg2h84sb8fy722_fb05\\msg\\file\\2026-06\\双钟楼火车站(坐西朝东).nbt")
        if (!file.isFile) return

        val lit = LitematicReader.readLenient(file)
        assertEquals(SchematicFormat.Structure, lit.format)
        val r = lit.primaryRegion!!
        assertEquals(32, r.width)
        assertEquals(34, r.height)
        assertEquals(47, r.depth)
        // Structure palette: air at 0 + actual blocks at 1+
        assertTrue(r.palette.size >= 2, "expected at least air + 1 block, got ${r.palette.size}")
        assertEquals("minecraft:air", r.palette[0].name)
        // MaterialList works
        val materials = MaterialList.from(lit)
        assertTrue(materials.size >= 1, "expected at least 1 material type in structure")
    }

    @Test
    fun `detectFormat vanilla structure returns Structure`() {
        val file = File("C:\\Users\\Administrator\\Documents\\xwechat_files\\wxid_eg2h84sb8fy722_fb05\\msg\\file\\2026-06\\双钟楼火车站(坐西朝东).nbt")
        if (!file.isFile) return
        assertEquals(SchematicFormat.Structure, LitematicReader.detectFormat(file))
    }

    @Test
    fun `detectFormat unknown root returns Unknown`() {
        val raw = buildRawNbt(
            name = "weird",
            entries = listOf(Triple("foo", NbtTagType.Int, intBytes(42))),
        )
        assertEquals(Unknown, LitematicReader.detectFormat(raw))
    }

    @Test
    fun `detectFormat prefers Litematica over Sponge when both markers present`() {
        // Edge case: file has both Regions and Metadata/EnclosingSize
        val raw = buildLitematicNbt(
            includeRegions = true,
            includeMetadata = true,
        )
        assertEquals(Litematica, LitematicReader.detectFormat(raw))
    }

    // ----------------------------------------------------------------
    // Helpers — synthetic NBT
    // ----------------------------------------------------------------

    private fun buildRawNbt(
        name: String,
        entries: List<Triple<String, NbtTagType, ByteArray>>,
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.writeByte(10)
            out.writeUTF(name)
            for ((key, type, valueBytes) in entries) {
                out.writeByte(type.id.toInt())
                out.writeUTF(key)
                out.write(valueBytes)
            }
            out.writeByte(0)
        }
        return baos.toByteArray()
    }

    /**
     * Build a synthetic Litematic-style NBT for format detection.
     * Either includes `Regions` (for Litematica) or `Metadata/EnclosingSize`
     * (for Sponge) — or both.
     */
    private fun buildLitematicNbt(includeRegions: Boolean, includeMetadata: Boolean): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.writeByte(10) // root compound
            out.writeUTF("")

            if (includeRegions) {
                // empty Regions compound
                out.writeByte(10); out.writeUTF("Regions")
                out.writeByte(0) // end Regions
            }
            if (includeMetadata) {
                // Metadata compound with EnclosingSize
                out.writeByte(10); out.writeUTF("Metadata")
                out.writeByte(10); out.writeUTF("EnclosingSize")
                out.writeByte(3); out.writeUTF("x"); out.writeInt(1)
                out.writeByte(3); out.writeUTF("y"); out.writeInt(1)
                out.writeByte(3); out.writeUTF("z"); out.writeInt(1)
                out.writeByte(0) // end EnclosingSize
                out.writeByte(0) // end Metadata
            }
            out.writeByte(0) // end root
        }
        return baos.toByteArray()
    }

    private fun intBytes(v: Int): ByteArray = byteArrayOf(
        (v shr 24).toByte(),
        (v shr 16).toByte(),
        (v shr 8).toByte(),
        v.toByte(),
    )
}
