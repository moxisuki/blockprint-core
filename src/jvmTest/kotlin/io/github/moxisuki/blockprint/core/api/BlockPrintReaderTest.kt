package io.github.moxisuki.blockprint.core.api

import io.github.moxisuki.blockprint.core.SchematicFormat
import io.github.moxisuki.blockprint.core.model.BlockPrintDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class BlockPrintReaderTest {
    private val fixture = "test/pre.litematic"

    @Test
    fun read_from_file_returns_BlockPrintDocument() {
        val doc: BlockPrintDocument = BlockPrintReader.read(File(fixture))
        assertNotNull(doc.primaryRegion)
    }

    @Test
    fun read_from_InputStream_matches_read_from_file() {
        val fromFile = BlockPrintReader.read(File(fixture))
        val fromStream = File(fixture).inputStream().use { BlockPrintReader.read(it) }
        assertEquals(fromFile.name, fromStream.name)
        assertEquals(fromFile.regions.size, fromStream.regions.size)
    }

    @Test
    fun read_from_bytes_matches_read_from_file() {
        val fromFile = BlockPrintReader.read(File(fixture))
        val fromBytes = BlockPrintReader.read(File(fixture).readBytes())
        assertEquals(fromFile.regions.size, fromBytes.regions.size)
    }

    @Test
    fun detectFormat_from_bytes_returns_Litematica() {
        val bytes = File(fixture).readBytes()
        assertEquals(SchematicFormat.Litematica, BlockPrintReader.detectFormat(bytes))
    }

    @Test
    fun readLenient_on_file_still_returns_document() {
        val fromFile = BlockPrintReader.readLenient(File(fixture))
        assertNotNull(fromFile)
    }
}