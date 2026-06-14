package io.github.moxisuki.blockprint.core

import io.github.moxisuki.blockprint.core.exceptions.LitematicException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Tests for the public NBT file API. Verifies we can read partial
 * litematics (e.g. `test2.nbt` / `test3.nbt` / `test4.nbt`) that
 * the strict `LitematicReader` would reject, as well as synthetic
 * NBT files constructed in-test.
 */
class NbtDocumentTest {

    // ----------------------------------------------------------------
    // Real test files (partial litematic structures)
    // ----------------------------------------------------------------

    @Test
    fun `read test2_nbt — partial litematic with size and entities`() {
        val file = File("C:\\Users\\Administrator\\Documents\\CCO\\LitematicMobile\\test2.nbt")
        if (!file.isFile) return  // skip if not present (CI portability)

        val doc = NbtDocument.read(file)

        // Root must be a compound
        assertTrue(doc.root.contains("size"))
        assertTrue(doc.root.contains("entities"))

        // size is a list of 3 ints = [27, 16, 27]  (a stripped litematic size triple)
        val sizeList = doc.root.get("size") as NbtTag.ListTag
        assertEquals(NbtTagType.Int, sizeList.elementType)
        assertEquals(3, sizeList.value.size)
        assertEquals(27, (sizeList.value[0] as NbtTag.IntTag).value)
        assertEquals(16, (sizeList.value[1] as NbtTag.IntTag).value)
        assertEquals(27, (sizeList.value[2] as NbtTag.IntTag).value)
    }

    @Test
    fun `read test3_nbt — second copy parses successfully`() {
        // test2 and test3 are byte-identical (same MD5); this test just
        // verifies the second copy is parseable. Equality of the parsed
        // trees follows trivially from byte equality, so we don't
        // assert structural equality here (that would just be testing
        // Kotlin's data class equals).
        val b = File("C:\\Users\\Administrator\\Documents\\CCO\\LitematicMobile\\test3.nbt")
        if (!b.isFile) return

        val doc = NbtDocument.read(b)
        assertTrue(doc.root.contains("size"))
        assertTrue(doc.root.contains("entities"))
    }

    @Test
    fun `read test4_nbt — smaller partial litematic with non-empty entities`() {
        val file = File("C:\\Users\\Administrator\\Documents\\CCO\\LitematicMobile\\test4.nbt")
        if (!file.isFile) return

        val doc = NbtDocument.read(file)

        // size: [17, 7, 11]
        val sizeList = doc.root.get("size") as NbtTag.ListTag
        assertEquals(3, sizeList.value.size)
        assertEquals(17, (sizeList.value[0] as NbtTag.IntTag).value)

        // entities: list of 2 compounds
        val entitiesList = doc.root.get("entities") as NbtTag.ListTag
        assertEquals(NbtTagType.Compound, entitiesList.elementType)
        assertEquals(2, entitiesList.value.size)

        // First entity has blockPos + nbt
        val first = entitiesList.value[0] as NbtTag.CompoundTag
        assertTrue(first.contains("blockPos"))
        assertTrue(first.contains("nbt"))

        val blockPos = first.get("blockPos") as NbtTag.ListTag
        assertEquals(3, blockPos.value.size)
        assertEquals(8, (blockPos.value[0] as NbtTag.IntTag).value)

        val nbt = first.get("nbt") as NbtTag.CompoundTag
        assertTrue(nbt.contains("Motion"))
    }

    // ----------------------------------------------------------------
    // Synthetic NBT (built in-test, no real file dependency)
    // ----------------------------------------------------------------

    @Test
    fun `read raw nbt bytes — small compound with int string and list`() {
        val raw = buildRawNbt(
            name = "demo",
            entries = listOf(
                Triple("answer", NbtTagType.Int, intBytes(42)),
                Triple("greeting", NbtTagType.String, stringBytes("hello")),
                Triple("nums", NbtTagType.List, listBytes(NbtTagType.Int, listOf(intBytes(1), intBytes(2), intBytes(3)))),
            ),
        )
        val doc = NbtDocument.read(raw)

        assertEquals(3, doc.root.entries().size)
        assertEquals(42, (doc.root.get("answer") as NbtTag.IntTag).value)
        assertEquals("hello", (doc.root.get("greeting") as NbtTag.StringTag).value)
        val nums = doc.root.get("nums") as NbtTag.ListTag
        assertEquals(3, nums.value.size)
        assertEquals(listOf(1, 2, 3), nums.value.map { (it as NbtTag.IntTag).value })
    }

    @Test
    fun `read gzip-compressed nbt bytes`() {
        val raw = buildRawNbt(
            name = "z",
            entries = listOf(Triple("x", NbtTagType.Int, intBytes(99))),
        )
        val gzipped = gzip(raw)

        val doc = NbtDocument.read(gzipped)
        assertEquals(99, (doc.root.get("x") as NbtTag.IntTag).value)
    }

    @Test
    fun `read from InputStream overload`() {
        val raw = buildRawNbt(
            name = "s",
            entries = listOf(Triple("v", NbtTagType.String, stringBytes("streamed"))),
        )
        val doc = NbtDocument.read(ByteArrayInputStream(raw))
        assertEquals("streamed", (doc.root.get("v") as NbtTag.StringTag).value)
    }

    @Test
    fun `read from File overload returns same as byte array overload`() {
        val raw = buildRawNbt(
            name = "f",
            entries = listOf(Triple("n", NbtTagType.Int, intBytes(7))),
        )
        val tmp = File.createTempFile("nbt-test", ".nbt")
        try {
            tmp.writeBytes(raw)
            val fromFile = NbtDocument.read(tmp)
            val fromBytes = NbtDocument.read(raw)
            assertEquals(fromBytes.root.entries(), fromFile.root.entries())
        } finally {
            tmp.delete()
        }
    }

    // ----------------------------------------------------------------
    // Error handling
    // ----------------------------------------------------------------

    @Test
    fun `non-compound root throws`() {
        // Build a valid NBT but with a non-compound root (an int instead).
        val raw = byteArrayOf(3) + intBytes(42)
        assertThrows<Exception> { NbtDocument.read(raw) }
    }

    @Test
    fun `truncated bytes throw`() {
        val truncated = byteArrayOf(0x0a, 0x00, 0x00, 0x09)  // root + partial name
        assertThrows<Exception> { NbtDocument.read(truncated) }
    }

    // ----------------------------------------------------------------
    // Tag navigation helpers
    // ----------------------------------------------------------------

    @Test
    fun `require throws when key is missing`() {
        val raw = buildRawNbt(
            name = "",
            entries = listOf(Triple("present", NbtTagType.Int, intBytes(1))),
        )
        val doc = NbtDocument.read(raw)
        assertNotNull(doc.root.get("present"))
        assertNull(doc.root.get("missing"))
        assertThrows<IllegalArgumentException> { doc.root.require("missing") }
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    /**
     * Build a minimal named-compound NBT to bytes.
     *
     * Each entry is `(name, tagType, valueBytes)`. The helper writes
     * the full tag header (tag_id + name UTF) followed by the value
     * bytes. Value bytes contain ONLY the tag-specific payload
     * (e.g. 4 bytes for an int, length-prefixed for a string), with
     * no leading tag_id or name.
     */
    private fun buildRawNbt(
        name: String,
        entries: List<Triple<String, NbtTagType, ByteArray>>,
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.writeByte(10) // root compound
            out.writeUTF(name)
            for ((key, type, valueBytes) in entries) {
                out.writeByte(type.id.toInt())  // tag id
                out.writeUTF(key)               // name
                out.write(valueBytes)           // tag-specific payload
            }
            out.writeByte(0) // TAG_End
        }
        return baos.toByteArray()
    }

    private fun intBytes(v: Int): ByteArray =
        byteArrayOf(
            (v shr 24).toByte(),
            (v shr 16).toByte(),
            (v shr 8).toByte(),
            v.toByte(),
        )

    private fun stringBytes(s: String): ByteArray {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val out = ByteArray(bytes.size + 2)
        out[0] = (bytes.size shr 8).toByte()
        out[1] = bytes.size.toByte()
        System.arraycopy(bytes, 0, out, 2, bytes.size)
        return out
    }

    private fun listBytes(elemType: NbtTagType, items: List<ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write(byteArrayOf(elemType.id))
        baos.write(intBytes(items.size))
        for (item in items) baos.write(item)
        return baos.toByteArray()
    }

    private fun gzip(input: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(input) }
        return baos.toByteArray()
    }
}
