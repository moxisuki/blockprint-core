package io.github.moxisuki.blockprint.core.nbt

import io.github.moxisuki.blockprint.core.NbtReader
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NbtReaderStreamReadTest {
    @Test fun stream_and_bytes_paths_produce_equal_root() {
        val bytes = generateSimpleRoot()
        val fromBytes = NbtReader.readRoot(bytes)
        val fromStream = NbtReader.readRoot(ByteArrayInputStream(bytes))
        assertEquals(fromBytes, fromStream)
    }

    @Test fun gzipped_stream_and_gzipped_bytes_produce_equal_root() {
        val payload = generateSimpleRoot()
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(baos).use { it.write(payload) }
        val gz = baos.toByteArray()
        val fromBytes = NbtReader.readRoot(gz)
        val fromStream = NbtReader.readRoot(ByteArrayInputStream(gz))
        assertEquals(fromBytes, fromStream)
    }

    @Test fun declared_array_larger_than_safety_limit_is_rejected_before_allocation() {
        val baos = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(baos).use { dos ->
            dos.writeByte(10); dos.writeUTF("")
            dos.writeByte(7); dos.writeUTF("bomb"); dos.writeInt(Int.MAX_VALUE)
        }
        val error = assertThrows(IllegalArgumentException::class.java) {
            NbtReader.readRoot(baos.toByteArray())
        }
        check(error.message.orEmpty().contains("safety limit"))
    }

    private fun generateSimpleRoot(): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(baos)
        dos.writeByte(10)  // compound
        dos.writeUTF("")   // name
        dos.writeByte(3); dos.writeUTF("answer"); dos.writeInt(42)  // int tag
        dos.writeByte(0)   // end
        dos.flush()
        return baos.toByteArray()
    }
}
