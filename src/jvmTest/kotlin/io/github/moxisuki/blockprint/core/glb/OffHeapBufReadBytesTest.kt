package io.github.moxisuki.blockprint.core.glb

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class OffHeapBufReadBytesTest {

    @Test
    fun readBytes_basic_chunk() {
        val buf = OffHeapBuf(64)
        for (i in 0 until 8) buf.putInt(i) // 32 bytes
        val target = ByteArray(16)
        val read = buf.readBytes(target, srcOffset = 0, length = 16)
        assertEquals(16, read)
        val bb = java.nio.ByteBuffer.wrap(target, 0, 16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, bb.getInt(0))
        assertEquals(3, bb.getInt(12))
    }

    @Test
    fun readBytes_cross_chunk_when_staging_smaller_than_remaining() {
        val buf = OffHeapBuf(64)
        for (i in 0 until 16) buf.putInt(i) // 64 bytes
        val target = ByteArray(16)
        val collected = mutableListOf<Int>()
        var srcOffset = 0
        while (srcOffset < 64) {
            val n = buf.readBytes(target, srcOffset, 16)
            if (n == 0) break
            val bb = java.nio.ByteBuffer.wrap(target, 0, n).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            while (bb.remaining() >= 4) collected.add(bb.getInt())
            srcOffset += n
        }
        assertEquals(16, collected.size)
        for (i in 0 until 16) assertEquals(i, collected[i])
    }

    @Test
    fun readBytes_end_of_buffer_returns_partial() {
        val buf = OffHeapBuf(64)
        for (i in 0 until 5) buf.putInt(i) // 20 bytes
        val target = ByteArray(32)
        val read = buf.readBytes(target, srcOffset = 16, length = 16) // ask 16 but only 4 remain
        assertEquals(4, read)
    }

    @Test
    fun readBytes_at_end_returns_zero() {
        val buf = OffHeapBuf(32)
        buf.putInt(0) // 4 bytes total
        val target = ByteArray(16)
        val read = buf.readBytes(target, srcOffset = 4, length = 16)
        assertEquals(0, read)
    }

    @Test
    fun readBytes_zero_length_returns_zero() {
        val buf = OffHeapBuf(32)
        buf.putInt(0)
        val target = ByteArray(16)
        val read = buf.readBytes(target, srcOffset = 0, length = 0)
        assertEquals(0, read)
    }

    @Test
    fun readBytes_after_close_throws() {
        val buf = OffHeapBuf(16)
        buf.putInt(0)
        buf.close()
        val target = ByteArray(8)
        try {
            buf.readBytes(target, 0, 4)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun readBytes_target_too_small_throws() {
        val buf = OffHeapBuf(32)
        buf.putInt(0)
        val target = ByteArray(4) // smaller than length below
        try {
            buf.readBytes(target, srcOffset = 0, length = 16)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun readBytes_negative_offset_throws() {
        val buf = OffHeapBuf(32)
        buf.putInt(0)
        val target = ByteArray(16)
        try {
            buf.readBytes(target, srcOffset = -1, length = 4)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun readBytes_negative_length_throws() {
        val buf = OffHeapBuf(32)
        buf.putInt(0)
        val target = ByteArray(16)
        try {
            buf.readBytes(target, srcOffset = 0, length = -1)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}