package io.github.moxisuki.blockprint.core.glb.platform

import java.io.OutputStream

actual class OffHeapBuf actual constructor(initialCapacityBytes: Int) {
    private var buf: java.nio.ByteBuffer = java.nio.ByteBuffer.allocateDirect(initialCapacityBytes)
    private var closed: Boolean = false

    init {
        require(initialCapacityBytes >= 0) { "initialCapacityBytes must be non-negative, got $initialCapacityBytes" }
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN)
    }

    actual fun putFloat(v: Float) {
        check(!closed) { "OffHeapBuf is closed" }
        ensure(4)
        buf.putFloat(v)
    }

    actual fun putInt(v: Int) {
        check(!closed) { "OffHeapBuf is closed" }
        ensure(4)
        buf.putInt(v)
    }

    actual fun sizeBytes(): Int = buf.position()
    actual fun capacityBytes(): Int = buf.capacity()

    actual fun ensure(extraBytes: Int) {
        check(!closed) { "OffHeapBuf is closed" }
        require(extraBytes >= 0) { "extraBytes must be non-negative, got $extraBytes" }
        val need = buf.position() + extraBytes
        if (need <= buf.capacity()) return
        var newCap = buf.capacity().coerceAtLeast(4)
        while (newCap < need) newCap = newCap shl 1
        val newBuf = java.nio.ByteBuffer.allocateDirect(newCap).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.flip()
        newBuf.put(buf)
        buf = newBuf
    }

    actual fun clear() {
        check(!closed) { "OffHeapBuf is closed" }
        buf.clear()
    }

    actual fun copyToStream(out: OutputStream, chunkSize: Int) {
        check(!closed) { "OffHeapBuf is closed" }
        require(chunkSize > 0) { "chunkSize must be positive, got $chunkSize" }
        val total = buf.position()
        if (total == 0) return
        buf.flip()
        val chunk = ByteArray(minOf(chunkSize, total))
        var remaining = total
        while (remaining > 0) {
            val n = minOf(chunk.size, remaining)
            buf.get(chunk, 0, n)
            out.write(chunk, 0, n)
            remaining -= n
        }
    }

    actual fun toByteArray(): ByteArray {
        check(!closed) { "OffHeapBuf is closed" }
        val out = ByteArray(buf.position())
        if (out.isEmpty()) return out
        buf.flip()
        buf.get(out)
        return out
    }

    actual fun readBytes(target: ByteArray, srcOffset: Int, length: Int): Int {
        check(!closed) { "OffHeapBuf is closed" }
        require(srcOffset >= 0) { "srcOffset must be non-negative, got $srcOffset" }
        require(length >= 0) { "length must be non-negative, got $length" }
        require(target.size >= length) {
            "target.size (${target.size}) must be >= length ($length)"
        }
        if (length == 0) return 0
        val available = buf.position() - srcOffset
        if (available <= 0) return 0
        val toRead = minOf(length, available)
        // Use absolute indexed get so the buffer's position (which represents
        // the end-of-data mark) is not disturbed across repeated calls.
        buf.get(srcOffset, target, 0, toRead)
        return toRead
    }

    actual fun copyTo(dest: OffHeapBuf) {
        check(!closed) { "OffHeapBuf is closed" }
        val size = buf.position()
        if (size == 0) return
        dest.ensure(size)
        val savedPos = buf.position()
        val savedLim = buf.limit()
        buf.position(0)
        buf.limit(size)
        dest.buf.put(buf)
        buf.limit(savedLim)
        buf.position(savedPos)
    }

    actual fun close() {
        if (closed) return
        closed = true
        // Drop our reference to the DirectByteBuffer and explicitly run its
        // Cleaner so the underlying native memory is reclaimed IMMEDIATELY
        // rather than waiting for the GC to finalize the old buffer.
        // Otherwise the old buffer leaks until GC runs, and on Android
        // that native allocation counts against the 256 MB ART heap cap.
        val old = buf
        buf = java.nio.ByteBuffer.allocate(0).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        runDirectCleaner(old)
    }

    private fun runDirectCleaner(buffer: java.nio.ByteBuffer) {
        try {
            // sun.misc.Cleaner is private but the cleanest portable way
            // to release native direct-buffer memory without waiting for
            // GC. On Android 8+ this also invokes the framework's
            // direct-buffer tracking, so the bytes come off the heap
            // budget immediately.
            val cleanerMethod = Class.forName("sun.misc.Unsafe")
                .getMethod("invokeCleaner", java.nio.ByteBuffer::class.java)
            val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null)
            cleanerMethod.invoke(unsafe, buffer)
        } catch (_: Throwable) {
            // sun.misc.Unsafe not available — fall back to letting the
            // GC reclaim the buffer eventually.
        }
    }
}
