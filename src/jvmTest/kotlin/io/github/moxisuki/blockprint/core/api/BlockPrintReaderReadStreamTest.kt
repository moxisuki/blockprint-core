package io.github.moxisuki.blockprint.core.api

import io.github.moxisuki.blockprint.core.model.BlockPrintDocument
import io.github.moxisuki.blockprint.core.testutil.TestBlueprintFixtures
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class BlockPrintReaderReadStreamTest {
    @Test fun read_from_stream_matches_read_from_bytes() {
        val bytes = TestBlueprintFixtures.minimalLitematicBytes()
        val fromBytes: BlockPrintDocument = BlockPrintReader.read(bytes)
        val fromStream: BlockPrintDocument = java.io.ByteArrayInputStream(bytes).use { BlockPrintReader.read(it) }
        assertEquals(fromBytes.regions.size, fromStream.regions.size)
        assertEquals(fromBytes.name, fromStream.name)
    }
}
