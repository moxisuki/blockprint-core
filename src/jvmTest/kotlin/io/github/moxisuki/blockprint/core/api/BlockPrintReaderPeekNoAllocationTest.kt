package io.github.moxisuki.blockprint.core.api

import io.github.moxisuki.blockprint.core.api.BlockPrintReader
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockPrintReaderPeekNoAllocationTest {
    @Test fun peek_on_50MB_fixture_completes_quickly() {
        val fixture = "test/benchmark/50mb.litematic"
        val file = File(fixture)
        assumeTrue("50MB fixture missing: $fixture", file.exists())

        val readNs = kotlin.system.measureNanoTime {
            runCatching { BlockPrintReader.read(file) }
        }
        val peekNs = kotlin.system.measureNanoTime {
            BlockPrintReader.peek(file)
        }
        println("[PEEK-ALLOC] read 50MB = ${readNs / 1_000_000} ms, peek = ${peekNs / 1_000_000} ms")
        // Soft assertion: peek should be at least 10x faster than read
        assertTrue("peek not 10x faster than read: read=$readNs peek=$peekNs", peekNs * 10 < readNs)
    }
}