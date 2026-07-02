package io.github.moxisuki.blockprint.core.nbt

import io.github.moxisuki.blockprint.core.NbtReader
import io.github.moxisuki.blockprint.core.NbtTag
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class NbtReaderSkipSubtreeTest {
    @Test fun skipping_Regions_produces_same_header_fields_as_full_read() {
        val bytes = buildLitematicaHeader(
            name = "test-schematic", author = "tester", mcDataVersion = 3953,
            regionsBody = ByteArray(1024) { 0 },
        )
        val full = NbtReader.readRoot(bytes)
        val header = NbtReader.readRootHeader(bytes, skipSubtreeNames = setOf("Regions"))
        assertEquals((full.get("Name") as NbtTag.StringTag).value,
                     (header.get("Name") as NbtTag.StringTag).value)
        assertEquals((full.get("Author") as NbtTag.StringTag).value,
                     (header.get("Author") as NbtTag.StringTag).value)
        assertEquals((full.get("MinecraftDataVersion") as NbtTag.IntTag).value,
                     (header.get("MinecraftDataVersion") as NbtTag.IntTag).value)
    }

    @Test fun skipping_unknown_name_is_no_op_full_read() {
        val bytes = buildLitematicaHeader("a", "b", 1, ByteArray(0))
        val full = NbtReader.readRoot(bytes)
        val header = NbtReader.readRootHeader(bytes, skipSubtreeNames = setOf("DoesNotExist"))
        assertEquals(full.entries().map { it.first }.toSet(),
                     header.entries().map { it.first }.toSet())
    }

    private fun buildLitematicaHeader(
        name: String, author: String, mcDataVersion: Int, regionsBody: ByteArray,
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeByte(10); dos.writeUTF("")
        dos.writeByte(3); dos.writeUTF("MinecraftDataVersion"); dos.writeInt(mcDataVersion)
        dos.writeByte(8); dos.writeUTF("Name"); dos.writeUTF(name)
        dos.writeByte(8); dos.writeUTF("Author"); dos.writeUTF(author)
        dos.writeByte(10); dos.writeUTF("Regions")
        baos.write(regionsBody)
        dos.writeByte(0)
        dos.writeByte(0)
        return baos.toByteArray()
    }
}