package io.github.moxisuki.blockprint.core.glb

import java.io.OutputStream

/**
 * Growable primitive float/int buffer backed by off-heap memory (Direct
 * ByteBuffer on JVM/Android; falls back to on-heap on non-JVM platforms).
 *
 * Off-heap allocation bypasses ART's per-process heap limit — critical on
 * Android where the default cap is 256 MB. The 64 KB staging buffer in
 * [GlbWriter] is the only on-heap allocation in the geometry path after
 * this type is introduced.
 *
 * Memory: capacity grows by doubling when [ensure] needs more. After
 * [close], the native memory is released immediately (faster than waiting
 * for GC). Methods other than [close] throw [IllegalStateException] after close.
 *
 * Thread-safety: not thread-safe. Use one OffHeapBuf per conversion thread.
 */
expect class OffHeapBuf(initialCapacityBytes: Int = 1024) {
    fun putFloat(v: Float)
    fun putInt(v: Int)
    fun sizeBytes(): Int
    fun capacityBytes(): Int
    fun ensure(extraBytes: Int)
    fun clear()
    fun copyToStream(out: OutputStream, chunkSize: Int = 65536)
    fun toByteArray(): ByteArray

    /**
     * Read up to [length] bytes from this off-heap buffer starting at
     * [srcOffset] (absolute offset in the buffer, 0 = start) into [target]
     * at target offset 0. Returns the actual number of bytes read
     * (less than [length] when the source has fewer bytes remaining).
     *
     * Mirrors [java.nio.ByteBuffer.get]: bytes are read directly from the
     * off-heap storage into the caller's on-heap array with no intermediate
     * copy. The buffer's position advances by the bytes read.
     *
     * After [close], throws [IllegalStateException].
     */
    fun readBytes(target: ByteArray, srcOffset: Int, length: Int): Int

    /**
     * Clone this buffer's data (position bytes) into [dest].  The copy is
     * direct-buffer-to-direct-buffer — it never transits the Java heap.
     * [dest] is grown as needed via its own [ensure].
     */
    fun copyTo(dest: OffHeapBuf)

    fun close()
}
