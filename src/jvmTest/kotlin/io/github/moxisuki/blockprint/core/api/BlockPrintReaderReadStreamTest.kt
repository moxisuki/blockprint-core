package io.github.moxisuki.blockprint.core.api

import io.github.moxisuki.blockprint.core.model.BlockPrintDocument
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class BlockPrintReaderReadStreamTest {
    @Test fun read_from_stream_matches_read_from_bytes() {
        val file = File("test/pre.litematic")
        val fromBytes: BlockPrintDocument = BlockPrintReader.read(file.readBytes())
        val fromStream: BlockPrintDocument = file.inputStream().use { BlockPrintReader.read(it) }
        assertEquals(fromBytes.regions.size, fromStream.regions.size)
        assertEquals(fromBytes.name, fromStream.name)
    }
}