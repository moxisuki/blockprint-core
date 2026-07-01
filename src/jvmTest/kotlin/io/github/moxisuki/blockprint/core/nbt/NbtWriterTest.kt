package io.github.moxisuki.blockprint.core.nbt

import io.github.moxisuki.blockprint.core.NbtTag
import io.github.moxisuki.blockprint.core.NbtTagType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class NbtWriterTest {

    @Test
    fun writeRootToBytes_produces_byte_equivalent_to_hand_crafted_payload() {
        // Hand-crafted minimal NBT: root compound named "" containing a single
        // IntTag("answer") = 42. Then a trailing End tag.
        // Spec: 1B tag id (0x0A compound), 2B name length (0x0000 ""), then the
        // single entry's payload: 1B id (0x03 int), 2B name "answer" (0x0006 0x61 0x6E 0x73 0x77 0x65 0x72),
        // 4B big-endian int 42, then 1B end tag (0x00).
        val expected = byteArrayOf(
            0x0A, 0x00, 0x00,
            0x03, 0x00, 0x06, 0x61, 0x6E, 0x73, 0x77, 0x65, 0x72,
            0x00, 0x00, 0x00, 0x2A,
            0x00,
        )
        val root = NbtTag.CompoundTag(
            listOf("answer" to NbtTag.IntTag(42)),
        )
        assertArrayEquals(expected, NbtWriter.writeRootToBytes(root))
    }

    @Test
    fun roundtrip_through_NbtReader_recovers_tree() {
        val tree = NbtTag.CompoundTag(
            listOf(
                "str" to NbtTag.StringTag("hi"),
                "i" to NbtTag.IntTag(-7),
                "l" to NbtTag.LongTag(1L shl 40),
                "f" to NbtTag.FloatTag(1.5f),
                "d" to NbtTag.DoubleTag(3.25),
                "ba" to NbtTag.ByteArrayTag(byteArrayOf(1, 2, 3)),
                "ia" to NbtTag.IntArrayTag(intArrayOf(10, 20)),
                "la" to NbtTag.LongArrayTag(longArrayOf(100L, 200L)),
                "emptyList" to NbtTag.ListTag(NbtTagType.End, emptyList()),
                "nested" to NbtTag.CompoundTag(
                    listOf("inside" to NbtTag.ByteTag(0x7F)),
                ),
            ),
        )
        val bytes = NbtWriter.writeRootToBytes(tree)
        val parsed = NbtReader.readRoot(bytes)
        // Compare by re-serializing; structural equals is what matters.
        assertArrayEquals(bytes, NbtWriter.writeRootToBytes(parsed))
    }

    @Test
    fun writeRootToGzipBytes_starts_with_gzip_magic() {
        val root = NbtTag.CompoundTag(listOf("x" to NbtTag.IntTag(1)))
        val bytes = NbtWriter.writeRootToGzipBytes(root)
        assertEquals(0x1F.toByte(), bytes[0])
        assertEquals(0x8B.toByte(), bytes[1])
        // The bytes must be a valid NBT root when decompressed.
        val parsed = NbtReader.readRoot(bytes)
        assertEquals(NbtTag.IntTag(1), parsed.get("x"))
    }

    @Test
    fun write_to_DataOutputStream_appends_to_existing_stream() {
        val baos = ByteArrayOutputStream()
        baos.write(byteArrayOf(0x00, 0x00, 0xFF.toByte()))
        val root = NbtTag.CompoundTag(listOf("k" to NbtTag.IntTag(7)))
        NbtWriter.writeRoot(root, baos)
        val full = baos.toByteArray()
        // First three bytes are the prefix we wrote.
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0xFF.toByte()), full.copyOfRange(0, 3))
        // Remaining bytes must round-trip through the reader as a fresh root.
        val rest = full.copyOfRange(3, full.size)
        val parsed = NbtReader.readRoot(rest)
        assertEquals(NbtTag.IntTag(7), parsed.get("k"))
    }
}
