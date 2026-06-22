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
 * during growth (old + new buffers coexist during the copy).  Segments
 * avoid this: we only allocate one new segment at a time, and existing
 * segments stay in place.  The **first** segment is allocated at the
 * exact requested [initialCapacityBytes] (typically a few KB).  When it
 * fills up, it is promoted to a 2 MB segment, and all subsequent
 * segments are also 2 MB.
 *
 * Thread-safety: not thread-safe.  Use one OffHeapBuf per conversion
 * thread.  Methods other than [close] throw [IllegalStateException]
 * after close.
 */
actual class OffHeapBuf actual constructor(initialCapacityBytes: Int) {
    companion object {
        // 128 KB — small enough that a single allocation succeeds even
        // when the ART heap is near 256 MB and fragmented.
        private const val SEGMENT_BYTES = 128 * 1024
    }

    // All segments except possibly the first (before promotion) are
    // exactly SEGMENT_BYTES.  After the first growth, every segment
    // is full-size, which keeps the read/copy logic simple.
    private val segments = mutableListOf<ByteArray>()
    private var writePos = 0
    private var totalSize = 0
    private var closed = false

    init {
        require(initialCapacityBytes >= 0) { "initialCapacityBytes must be non-negative, got $initialCapacityBytes" }
        // Allocate exactly what was requested — do NOT round up to 2 MB.
        // Multi-floor mode creates one accumulator per floor, and most
        // floors are empty or tiny; pre-allocating 2 MB each would OOM.
        segments.add(ByteArray(maxOf(1, initialCapacityBytes)))
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

    actual fun capacityBytes(): Int {
        var sum = 0
        for (s in segments) sum += s.size
        return sum
    }

    actual fun ensure(extraBytes: Int) {
        check(!closed) { "OffHeapBuf is closed" }
        require(extraBytes >= 0) { "extraBytes must be non-negative, got $extraBytes" }
        val lastSeg = segments.last()
        val remaining = lastSeg.size - writePos
        if (extraBytes <= remaining) return

        if (segments.size == 1 && lastSeg.size < SEGMENT_BYTES) {
            // First growth: promote the initial small segment to 2 MB.
            val promoted = ByteArray(SEGMENT_BYTES)
            System.arraycopy(lastSeg, 0, promoted, 0, totalSize)
            segments[0] = promoted
            val newRemaining = SEGMENT_BYTES - writePos
            if (extraBytes <= newRemaining) return
            val shortfall = extraBytes - newRemaining
            val count = maxOf(1, (shortfall + SEGMENT_BYTES - 1) / SEGMENT_BYTES)
            repeat(count) { segments.add(ByteArray(SEGMENT_BYTES)) }
            writePos = 0
        } else {
            // Already in full-segment mode; tack on more 2 MB segments.
            val shortfall = extraBytes - remaining
            val count = maxOf(1, (shortfall + SEGMENT_BYTES - 1) / SEGMENT_BYTES)
            repeat(count) { segments.add(ByteArray(SEGMENT_BYTES)) }
            writePos = 0
        }
    }

    actual fun clear() {
        check(!closed) { "OffHeapBuf is closed" }
        // Drop any promoted segments and return to a minimal allocation.
        segments.clear()
        segments.add(ByteArray(4096))
        writePos = 0
        totalSize = 0
    }

    actual fun copyToStream(out: OutputStream, chunkSize: Int) {
        check(!closed) { "OffHeapBuf is closed" }
        require(chunkSize > 0) { "chunkSize must be positive, got $chunkSize" }
        val total = totalSize
        if (total == 0) return
        var remaining = total
        var segIdx = 0
        var segOff = 0
        while (remaining > 0) {
            val seg = segments[segIdx]
            val chunk = minOf(remaining, seg.size - segOff)
            out.write(seg, segOff, chunk)
            segOff += chunk; remaining -= chunk
            if (segOff >= seg.size) { segIdx++; segOff = 0 }
        }
    }

    actual fun toByteArray(): ByteArray {
        check(!closed) { "OffHeapBuf is closed" }
        val out = ByteArray(totalSize)
        var dst = 0
        var remaining = totalSize
        var segIdx = 0
        var segOff = 0
        while (remaining > 0) {
            val seg = segments[segIdx]
            val chunk = minOf(remaining, seg.size - segOff)
            System.arraycopy(seg, segOff, out, dst, chunk)
            dst += chunk; segOff += chunk; remaining -= chunk
            if (segOff >= seg.size) { segIdx++; segOff = 0 }
        }
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
        // Find starting segment.
        var si = 0
        var so = off
        while (si < segments.size && so >= segments[si].size) {
            so -= segments[si].size; si++
        }
        while (rem > 0 && si < segments.size) {
            val chunk = minOf(rem, segments[si].size - so)
            System.arraycopy(segments[si], so, target, dst, chunk)
            dst += chunk; rem -= chunk; so = 0; si++
        }
        return toRead
    }

    actual fun copyTo(dest: OffHeapBuf) {
        check(!closed) { "OffHeapBuf is closed" }
        val size = totalSize
        if (size == 0) return
        dest.ensure(size)
        var remaining = size
        var si = 0
        var so = 0
        while (remaining > 0) {
            val seg = segments[si]
            val chunk = minOf(remaining, seg.size - so)
            // Find or allocate space in dest's current segment.
            var dSeg = dest.segments.last()
            var dOff = dest.writePos
            if (dOff + chunk > dSeg.size) {
                dest.segments.add(ByteArray(SEGMENT_BYTES))
                dSeg = dest.segments.last()
                dOff = 0
                dest.writePos = 0
            }
            System.arraycopy(seg, so, dSeg, dOff, chunk)
            dest.writePos += chunk
            dest.totalSize += chunk
            so += chunk; remaining -= chunk
            if (so >= seg.size) { si++; so = 0 }
        }
    }

    actual fun close() {
        if (closed) return
        segments.clear()
        writePos = 0
        totalSize = 0
        closed = true
    }
}
