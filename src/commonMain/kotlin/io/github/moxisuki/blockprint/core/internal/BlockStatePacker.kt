package io.github.moxisuki.blockprint.core.internal

import io.github.moxisuki.blockprint.core.exceptions.LitematicException
import java.io.DataOutputStream

/**
 * Decodes the packed `BlockStates` long array from a litematic region.
 *
 * Mirrors the algorithm in
 * `RAW/litematic-viewer-main/src/litematic-utils.js#processNBTRegionData`:
 * the long array is a y-major / z-middle / x-minor bit-packed run of
 * `nbits`-wide palette indices.
 *
 * Differences vs the JS reference:
 * - Operates on JVM `Long` directly — no 64→32-bit split dance.
 * - The index→field read is implemented as a single two-long span read,
 *   which is correct for every nbits ∈ [1, 64] and avoids the JS
 *   "sometimes the index can extend past the end" hack.
 * - nbits == 64 is a real edge case: it has to be detected and the read
 *   is the whole long, not a shift.
 */
internal object BlockStatePacker {

    /**
     * Decode [packed] into a fresh `width * height * depth` IntArray.
     *
     * The returned array is indexed in y-major / z-middle / x-minor order,
     * matching [io.github.moxisuki.blockprint.core.LitematicRegion.rawIndex].
     */
    fun unpack(
        packed: LongArray,
        nbits: Int,
        width: Int,
        height: Int,
        depth: Int,
    ): IntArray {
        require(nbits in 0..64) { "nbits must be in [0, 64], got $nbits" }
        require(width >= 0 && height >= 0 && depth >= 0) {
            "Dimensions must be non-negative, got ${width}x${height}x${depth}"
        }

        val totalBlocks = (width.toLong() * height.toLong() * depth.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val out = IntArray(totalBlocks)

        // Degenerate case: 0-bit palette (only "air"). Every field reads as 0.
        // Matches the JS reference's behavior for single-entry palettes.
        if (nbits == 0) return out

        // Mask: for nbits < 64, lower-nbits of 0xFF…FF. For nbits == 64, mask
        // is -1L (all bits) so that the final AND is a no-op.
        val mask: Long = if (nbits == 64) -1L else (1L shl nbits) - 1L

        val yShift = width * depth
        val zShift = width

        for (y in 0 until height) {
            val yBase = y * yShift
            for (z in 0 until depth) {
                val zBase = yBase + z * zShift
                for (x in 0 until width) {
                    val index = zBase + x

                    // Number of bits we need to skip to reach the start of
                    // this field, measured from the LSB of packed[0].
                    val bitOffset = index.toLong() * nbits.toLong()

                    val longIndex = (bitOffset ushr 6).toInt()      // / 64
                    val intraBit = (bitOffset and 0x3F).toInt()      // % 64

                    val raw: Long = if (intraBit + nbits <= 64) {
                        // Field fits in a single long.
                        val word = packed.getOrElse(longIndex) { 0L }
                        if (nbits == 64) word else (word ushr intraBit) and mask
                    } else {
                        // Field straddles two longs.
                        val hi = packed.getOrElse(longIndex) { 0L }
                        val lo = packed.getOrElse(longIndex + 1) { 0L }
                        val lowPart = (hi ushr intraBit) and mask
                        val highPartBits = intraBit + nbits - 64
                        val highPartMask = if (highPartBits == 64) -1L else (1L shl highPartBits) - 1L
                        val highPart = (lo and highPartMask) shl (64 - intraBit)
                        lowPart or highPart
                    }

                    out[index] = raw.toInt()
                }
            }
        }

        return out
    }

    /**
     * Pack a dense y-major block-index array into the Litematica
     * `BlockStates` long-array encoding (inverse of [unpack]).
     *
     * Layout matches [unpack]: y-major, z-middle, x-minor. Each block
     * is `nbits` wide, packed LSB-first across the long array. Any
     * trailing bits in the last long are zero (the reader tolerates
     * this; see [validateLength]).
     */
    fun pack(
        blocks: IntArray,
        nbits: Int,
        width: Int,
        height: Int,
        depth: Int,
    ): LongArray {
        require(nbits in 0..64) { "nbits must be in [0, 64], got $nbits" }
        require(width >= 0 && height >= 0 && depth >= 0) {
            "Dimensions must be non-negative, got ${width}x${height}x${depth}"
        }
        if (nbits == 0) {
            // 0-bit palette: every field is 0, the long array is unused
            // by the reader. We still emit one long so validateLength passes
            // for at least one cell.
            return LongArray(1)
        }
        val total = (width.toLong() * height.toLong() * depth.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        require(blocks.size >= total) {
            "Block array size ${blocks.size} is smaller than declared $width*$height*$depth = $total"
        }
        val requiredBits = total.toLong() * nbits.toLong()
        val longCount = ((requiredBits + 63) / 64).toInt().coerceAtLeast(1)
        val out = LongArray(longCount)
        val yShift = width * depth
        val zShift = width
        for (y in 0 until height) {
            val yBase = y * yShift
            for (z in 0 until depth) {
                val zBase = yBase + z * zShift
                for (x in 0 until width) {
                    val index = zBase + x
                    val fieldMask: Long = if (nbits == 64) -1L else (1L shl nbits) - 1L
                    val value = blocks[index].toLong() and fieldMask
                    val bitOffset = index.toLong() * nbits.toLong()
                    val longIndex = (bitOffset ushr 6).toInt()
                    val intraBit = (bitOffset and 0x3F).toInt()
                    out[longIndex] = out[longIndex] or (value shl intraBit)
                    if (intraBit + nbits > 64) {
                        val overflowBits = intraBit + nbits - 64
                        out[longIndex + 1] = out[longIndex + 1] or (value ushr (nbits - overflowBits))
                    }
                }
            }
        }
        return out
    }

    /**
     * Streaming variant of [pack]: writes `longCount` big-endian longs
     * directly to [dos].
     *
     * Avoids allocating the `LongArray` intermediate. For a 4.6M-cell
     * region at nbits=9 that's ~5 MB of `Long`s kept off the Java heap.
     *
     * The caller is responsible for emitting the NBT LongArray tag header
     * (id + name + int32 length) before this — see
     * [NbtWriter.writeListHeader] and the [NbtWriter.writeListElement]
     * primitives, or use [NbtWriter.writeNamed] with a placeholder
     * [NbtTag.LongArrayTag] sized to `longCount` and then call this to
     * overwrite the body.  (Most callers today use the `writeNamed` +
     * placeholder approach because NBT requires the length before the
     * payload — pre-compute `longCount` from `(total * nbits + 63) / 64`
     * and write a zero-filled `LongArrayTag` of that size first, then
     * stream-pack the longs in place.)
     *
     * Output bytes are **byte-for-byte identical** to wrapping [pack]'s
     * result in a `LongArrayTag` and serialising via [NbtWriter.writeNamed].
     */
    fun pack(
        blocks: IntArray,
        nbits: Int,
        width: Int,
        height: Int,
        depth: Int,
        dos: DataOutputStream,
    ) {
        require(nbits in 0..64) { "nbits must be in [0, 64], got $nbits" }
        require(width >= 0 && height >= 0 && depth >= 0) {
            "Dimensions must be non-negative, got ${width}x${height}x${depth}"
        }
        if (nbits == 0) {
            // 0-bit palette: emit one zero long (matches the in-memory path).
            dos.writeLong(0L)
            return
        }
        val total = (width.toLong() * height.toLong() * depth.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        require(blocks.size >= total) {
            "Block array size ${blocks.size} is smaller than declared $width*$height*$depth = $total"
        }
        val requiredBits = total.toLong() * nbits.toLong()
        val longCount = ((requiredBits + 63) / 64).toInt().coerceAtLeast(1)
        val yShift = width * depth
        val zShift = width
        // Track the current long being assembled + the number of bits
        // already written into it.  Y-major traversal of cells means
        // `longIndex` is non-decreasing — each cell either continues the
        // current long or starts a new one (possibly skipping a few when
        // a field straddles two longs).
        var current = 0L
        var bitsInCurrent = 0
        var emittedLongs = 0
        for (y in 0 until height) {
            val yBase = y * yShift
            for (z in 0 until depth) {
                val zBase = yBase + z * zShift
                for (x in 0 until width) {
                    val index = zBase + x
                    val fieldMask: Long = if (nbits == 64) -1L else (1L shl nbits) - 1L
                    val value = blocks[index].toLong() and fieldMask
                    val bitOffset = index.toLong() * nbits.toLong()
                    val longIndex = (bitOffset ushr 6).toInt()
                    val intraBit = (bitOffset and 0x3F).toInt()
                    if (longIndex > emittedLongs) {
                        // We jumped past `longIndex` boundary. Flush the
                        // current long first, then pad any skipped longs
                        // with zero (y-major traversal means we shouldn't
                        // skip more than 1 in practice, since each cell
                        // is at most `nbits` wide and we move 1 cell at
                        // a time).
                        if (bitsInCurrent > 0) {
                            dos.writeLong(current)
                            bitsInCurrent = 0
                            current = 0L
                            emittedLongs++
                        }
                        while (emittedLongs < longIndex) {
                            dos.writeLong(0L)
                            emittedLongs++
                        }
                    }
                    current = current or (value shl intraBit)
                    if (intraBit + nbits > 64) {
                        val overflowBits = intraBit + nbits - 64
                        current = current or (value ushr (nbits - overflowBits))
                    }
                    bitsInCurrent += nbits
                    if (bitsInCurrent >= 64) {
                        dos.writeLong(current)
                        bitsInCurrent = 0
                        current = 0L
                        emittedLongs++
                    }
                }
            }
        }
        // Flush any trailing partial long.
        if (bitsInCurrent > 0) {
            dos.writeLong(current)
            bitsInCurrent = 0
            emittedLongs++
        }
        // Pad to longCount with zero (e.g. if the last cell ended exactly
        // on a long boundary we don't want a stray write above).
        while (emittedLongs < longCount) {
            dos.writeLong(0L)
            emittedLongs++
        }
    }

    /**
     * Quick sanity check used by the parser: when the long array is way
     * too short to hold the declared block count at the declared nbits,
     * fail loudly instead of producing a silently truncated region.
     */
    fun validateLength(
        packed: LongArray,
        nbits: Int,
        width: Int,
        height: Int,
        depth: Int,
    ) {
        if (nbits == 0) return
        val requiredBits = width.toLong() * height.toLong() * depth.toLong() * nbits.toLong()
        val providedBits = packed.size.toLong() * 64L
        if (requiredBits > providedBits) {
            throw LitematicException(
                "BlockStates under-filled: need $requiredBits bits, " +
                    "have $providedBits (region ${width}x${height}x${depth}, nbits=$nbits)",
            )
        }
    }
}
