package io.github.moxisuki.blockprint.core.api

import io.github.moxisuki.blockprint.core.SchematicFormat
import io.github.moxisuki.blockprint.core.exceptions.BlockPrintException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class BlockPrintReaderPeekTest {
    private val litematicFixture = "test/pre.litematic"

    @Test
    fun peek_Litematica_from_file_matches_read_metadata() {
        val peeked = BlockPrintReader.peek(File(litematicFixture))
        val read = BlockPrintReader.read(File(litematicFixture))
        assertEquals(SchematicFormat.Litematica, peeked.format)
        assertEquals(read.name, peeked.name)
        assertEquals(read.author, peeked.author)
        assertEquals(read.minecraftDataVersion, peeked.minecraftDataVersion)
    }

    @Test
    fun peek_Litematica_from_InputStream_matches_file_peek() {
        val fromFile = BlockPrintReader.peek(File(litematicFixture))
        val fromStream = File(litematicFixture).inputStream().use { BlockPrintReader.peek(it) }
        assertEquals(fromFile.format, fromStream.format)
        assertEquals(fromFile.name, fromStream.name)
    }

    @Test
    fun peek_Litematica_from_bytes_matches_file_peek() {
        val fromFile = BlockPrintReader.peek(File(litematicFixture))
        val fromBytes = BlockPrintReader.peek(File(litematicFixture).readBytes())
        assertEquals(fromFile.name, fromBytes.name)
    }

    @Test
    fun peek_non_schematic_bytes_throws_BlockPrintException() {
        val garbage = "this is not an nbt file".toByteArray()
        try {
            BlockPrintReader.peek(garbage)
            fail("Expected BlockPrintException")
        } catch (e: BlockPrintException) {
            assertNotNull(e)
        }
    }

    @Test
    fun peek_empty_bytes_throws() {
        try {
            BlockPrintReader.peek(ByteArray(0))
            fail("Expected BlockPrintException")
        } catch (e: BlockPrintException) {
            assertNotNull(e)
        }
    }
}