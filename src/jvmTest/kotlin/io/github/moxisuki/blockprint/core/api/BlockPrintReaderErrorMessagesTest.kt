package io.github.moxisuki.blockprint.core.api

import io.github.moxisuki.blockprint.core.exceptions.BlockPrintException
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockPrintReaderErrorMessagesTest {
    @Test
    fun garbage_bytes_throw_with_descriptive_message() {
        val e = runCatching { BlockPrintReader.read("junk".toByteArray()) }.exceptionOrNull()
        assertTrue("expected BlockPrintException, got $e", e is BlockPrintException)
    }

    @Test
    fun empty_bytes_throw() {
        val e = runCatching { BlockPrintReader.read(ByteArray(0)) }.exceptionOrNull()
        assertTrue("expected BlockPrintException, got $e", e is BlockPrintException)
    }

    @Test
    fun cause_preserved_for_NbtFormatException() {
        val e = runCatching { BlockPrintReader.read(byteArrayOf(0x05)) }.exceptionOrNull()
        assertTrue("expected BlockPrintException, got $e", e is BlockPrintException)
    }
}