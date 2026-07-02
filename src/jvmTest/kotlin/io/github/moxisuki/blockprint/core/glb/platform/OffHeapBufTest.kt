package io.github.moxisuki.blockprint.core.glb.platform

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

class OffHeapBufTest {

    @Test
    fun putFloat_then_toByteArray_round_trips() {
        val buf = OffHeapBuf(16)
        buf.putFloat(1.5f)
        buf.putFloat(-2.25f)
        buf.putFloat(Float.NaN)
        val bytes = buf.toByteArray()
        assertEquals(12, bytes.size)
        val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(1.5f, bb.getFloat(0), 0f)
        assertEquals(-2.25f, bb.getFloat(4), 0f)
        assertTrue(bb.getFloat(8).isNaN())
    }

    @Test
    fun putInt_then_toByteArray_round_trips() {
        val buf = OffHeapBuf(8)
        buf.putInt(0)
        buf.putInt(Int.MAX_VALUE)
        val bytes = buf.toByteArray()
        assertEquals(8, bytes.size)
        val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, bb.getInt(0))
        assertEquals(Int.MAX_VALUE, bb.getInt(4))
    }

    @Test
    fun ensure_grows_capacity_exponentially() {
        val buf = OffHeapBuf(4)
        val startCap = buf.capacityBytes()
        for (i in 0 until 100) buf.putInt(i)
        assertTrue("capacity should grow beyond initial 4 bytes, got ${buf.capacityBytes()}",
            buf.capacityBytes() >= 100 * 4)
        assertTrue("capacity should be at least 2x the initial (doubling growth)",
            buf.capacityBytes() >= startCap * 2)
    }

    @Test
    fun clear_resets_position_keeps_capacity() {
        val buf = OffHeapBuf(32)
        buf.putFloat(1f)
        buf.putFloat(2f)
        val capBefore = buf.capacityBytes()
        buf.clear()
        assertEquals(0, buf.sizeBytes())
        assertEquals(capBefore, buf.capacityBytes())
        buf.putFloat(99f)
        assertEquals(4, buf.sizeBytes())
    }

    @Test
    fun close_then_any_method_throws() {
        val buf = OffHeapBuf(8)
        buf.putInt(1)
        buf.close()
        try {
            buf.putInt(2)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // expected
        }
        try {
            buf.toByteArray()
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun copyToStream_writes_all_bytes_in_chunks() {
        val buf = OffHeapBuf(1024)
        for (i in 0 until 256) buf.putInt(i)
        val out = ByteArrayOutputStream()
        buf.copyToStream(out)
        val written = out.toByteArray()
        assertEquals(1024, written.size)
        for (i in 0 until 256) {
            val bb = java.nio.ByteBuffer.wrap(written).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            assertEquals(i, bb.getInt(i * 4))
        }
    }

    @Test
    fun copyToStream_with_small_chunkSize_writes_in_multiple_chunks() {
        val buf = OffHeapBuf(1024)
        for (i in 0 until 100) buf.putInt(i)
        val out = ByteArrayOutputStream()
        buf.copyToStream(out, chunkSize = 17)
        assertEquals(400, out.size())
        val bb = java.nio.ByteBuffer.wrap(out.toByteArray()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 100) assertEquals(i, bb.getInt(i * 4))
    }

    @Test
    fun direct_buffer_is_off_heap() {
        val buf = OffHeapBuf(8)
        try {
            val field = OffHeapBuf::class.java.getDeclaredField("buf")
            field.isAccessible = true
            val underlying = field.get(buf) as java.nio.ByteBuffer
            assertTrue("underlying ByteBuffer must be direct (off-heap)", underlying.isDirect)
        } catch (e: NoSuchFieldException) {
            // informational only
        }
        buf.putInt(42)
        assertEquals(4, buf.sizeBytes())
    }
}
