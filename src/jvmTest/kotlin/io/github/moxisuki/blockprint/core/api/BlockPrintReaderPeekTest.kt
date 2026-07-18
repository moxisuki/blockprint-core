package io.github.moxisuki.blockprint.core.api

import io.github.moxisuki.blockprint.core.SchematicFormat
import io.github.moxisuki.blockprint.core.exceptions.BlockPrintException
import io.github.moxisuki.blockprint.core.testutil.TestBlueprintFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class BlockPrintReaderPeekTest {
    @Test
    fun peek_Litematica_from_file_matches_read_metadata() {
        val file = TestBlueprintFixtures.minimalLitematicFile()
        val peeked = BlockPrintReader.peek(file)
        val read = BlockPrintReader.read(file)
        assertEquals(SchematicFormat.Litematica, peeked.format)
        assertEquals(read.name, peeked.name)
        assertEquals(read.author, peeked.author)
        assertEquals(read.minecraftDataVersion, peeked.minecraftDataVersion)
    }

    @Test
    fun peek_Litematica_from_InputStream_matches_file_peek() {
        val file = TestBlueprintFixtures.minimalLitematicFile()
        val fromFile = BlockPrintReader.peek(file)
        val fromStream = file.inputStream().use { BlockPrintReader.peek(it) }
        assertEquals(fromFile.format, fromStream.format)
        assertEquals(fromFile.name, fromStream.name)
    }

    @Test
    fun peek_Litematica_from_bytes_matches_file_peek() {
        val bytes = TestBlueprintFixtures.minimalLitematicBytes()
        val fromFile = BlockPrintReader.peek(TestBlueprintFixtures.minimalLitematicFile())
        val fromBytes = BlockPrintReader.peek(bytes)
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
