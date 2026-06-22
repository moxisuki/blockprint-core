package io.github.moxisuki.blockprint.core.glb

import java.io.OutputStream

/**
 * Android actual: segmented growable float/int buffer.
 *
 * **Why not DirectByteBuffer?** On Android, `ByteBuffer.allocateDirect()`
 * calls `VMRuntime.newNonMovableArray` which still counts against the
 * ART per-process heap limit (256 MB typical).  Worse, the buffer is
 * non-movable, so the GC cannot compact it — fragmentation causes OOM
 * even when total free bytes would be sufficient.
 *
 * **Why segmented?** A single monolithic buffer doubles its footprint
 * during growth (old + new buffers coexist during the copy).  For a
 * 128 MB buffer growing to 256 MB the peak is ~384 MB → guaranteed
 * OOM.  Segments avoid this: we only ever allocate one new 2 MB segment
 * at a time, and the existing segments stay in place.  Total memory =
 * sum of all segment capacities.
 *
 * Thread-safety: not thread-safe.  Use one OffHeapBuf per conversion
 * thread.  Methods other than [close] throw [IllegalStateException]
 * after close.
 */
actual class OffHeapBuf actual constructor(initialCapacityBytes: Int) {
    companion object {
        private const val SEGMENT_BYTES = 2 * 1024 * 1024  // 2 MB
    }

    /** Full 2 MB segments (except possibly the last). */
    private val segments = mutableListOf<ByteArray>()
    /** Write offset within `segments.last()`. */
    private var writePos = 0
    /** Total bytes written — this is the authoritative size. */
    private var totalSize = 0
    private var closed = false

    init {
        require(initialCapacityBytes >= 0) { "initialCapacityBytes must be non-negative, got $initialCapacityBytes" }
        val n = maxOf(1, (initialCapacityBytes + SEGMENT_BYTES - 1) / SEGMENT_BYTES)
        repeat(n) { segments.add(ByteArray(SEGMENT_BYTES)) }
    }

    actual fun putFloat(v: Float) {
        check(!closed) { "OffHeapBuf is closed" }
        ensure(4)
        val bits = java.lang.Float.floatToRawIntBits(v)
        val seg = segments.last()
        seg[writePos]     =  bits.toByte()
        seg[writePos + 1] = (bits shr 8).toByte()
        seg[writePos + 2] = (bits shr 16).toByte()
        seg[writePos + 3] = (bits shr 24).toByte()
        writePos += 4
        totalSize += 4
    }

    actual fun putInt(v: Int) {
        check(!closed) { "OffHeapBuf is closed" }
        ensure(4)
        val seg = segments.last()
        seg[writePos]     =  v.toByte()
        seg[writePos + 1] = (v shr 8).toByte()
        seg[writePos + 2] = (v shr 16).toByte()
        seg[writePos + 3] = (v shr 24).toByte()
        writePos += 4
        totalSize += 4
    }

    actual fun sizeBytes(): Int = totalSize

    actual fun capacityBytes(): Int = segments.size * SEGMENT_BYTES

    actual fun ensure(extraBytes: Int) {
        check(!closed) { "OffHeapBuf is closed" }
        require(extraBytes >= 0) { "extraBytes must be non-negative, got $extraBytes" }
        val remaining = SEGMENT_BYTES - writePos
        if (extraBytes <= remaining) return
        // Add one or more fresh segments so we have `extraBytes` space.
        val shortfall = extraBytes - remaining
        val count = maxOf(1, (shortfall + SEGMENT_BYTES - 1) / SEGMENT_BYTES)
        repeat(count) { segments.add(ByteArray(SEGMENT_BYTES)) }
        writePos = 0
    }

    actual fun clear() {
        check(!closed) { "OffHeapBuf is closed" }
        segments.clear()
        segments.add(ByteArray(SEGMENT_BYTES))
        writePos = 0
        totalSize = 0
    }

    actual fun copyToStream(out: OutputStream, chunkSize: Int) {
        check(!closed) { "OffHeapBuf is closed" }
        require(chunkSize > 0) { "chunkSize must be positive, got $chunkSize" }
        val total = totalSize
        if (total == 0) return
        val full = total / SEGMENT_BYTES
        val rem = total % SEGMENT_BYTES
        for (i in 0 until full) out.write(segments[i], 0, SEGMENT_BYTES)
        if (rem > 0) out.write(segments[full], 0, rem)
    }

    actual fun toByteArray(): ByteArray {
        check(!closed) { "OffHeapBuf is closed" }
        val out = ByteArray(totalSize)
        var dst = 0
        val full = totalSize / SEGMENT_BYTES
        val rem = totalSize % SEGMENT_BYTES
        for (i in 0 until full) {
            System.arraycopy(segments[i], 0, out, dst, SEGMENT_BYTES)
            dst += SEGMENT_BYTES
        }
        if (rem > 0) System.arraycopy(segments[full], 0, out, dst, rem)
        return out
    }

    actual fun readBytes(target: ByteArray, srcOffset: Int, length: Int): Int {
        check(!closed) { "OffHeapBuf is closed" }
        require(srcOffset >= 0) { "srcOffset must be non-negative, got $srcOffset" }
        require(length >= 0) { "length must be non-negative, got $length" }
        require(target.size >= length) { "target.size (${target.size}) must be >= length ($length)" }
        if (length == 0) return 0
        val available = totalSize - srcOffset
        if (available <= 0) return 0
        val toRead = minOf(length, available)
        var off = srcOffset
        var rem = toRead
        var dst = 0
        while (rem > 0) {
            val si = off / SEGMENT_BYTES
            val so = off % SEGMENT_BYTES
            val chunk = minOf(rem, SEGMENT_BYTES - so)
            System.arraycopy(segments[si], so, target, dst, chunk)
            off += chunk; dst += chunk; rem -= chunk
        }
        return toRead
    }

    actual fun copyTo(dest: OffHeapBuf) {
        check(!closed) { "OffHeapBuf is closed" }
        val size = totalSize
        if (size == 0) return
        dest.ensure(size)
        val full = size / SEGMENT_BYTES
        val rem = size % SEGMENT_BYTES
        // Copy segment-by-segment into dest's pre-allocated segments.
        // dest.segments already has enough capacity thanks to ensure().
        var dstIdx = dest.totalSize / SEGMENT_BYTES
        var dstOff = dest.totalSize % SEGMENT_BYTES
        for (i in 0 until full) {
            val space = SEGMENT_BYTES - dstOff
            if (space >= SEGMENT_BYTES) {
                System.arraycopy(segments[i], 0, dest.segments[dstIdx], dstOff, SEGMENT_BYTES)
                dstOff += SEGMENT_BYTES
            } else {
                System.arraycopy(segments[i], 0, dest.segments[dstIdx], dstOff, space)
                dstIdx++; dstOff = SEGMENT_BYTES - space
                System.arraycopy(segments[i], space, dest.segments[dstIdx], 0, dstOff)
            }
        }
        if (rem > 0) {
            val space = SEGMENT_BYTES - dstOff
            if (space >= rem) {
                System.arraycopy(segments[full], 0, dest.segments[dstIdx], dstOff, rem)
                dstOff += rem
            } else {
                System.arraycopy(segments[full], 0, dest.segments[dstIdx], dstOff, space)
                dstIdx++; dstOff = rem - space
                System.arraycopy(segments[full], space, dest.segments[dstIdx], 0, dstOff)
            }
        }
        dest.writePos = dstOff
        dest.totalSize += size
    }

    actual fun close() {
        if (closed) return
        segments.clear()
        writePos = 0
        totalSize = 0
        closed = true
    }
}
