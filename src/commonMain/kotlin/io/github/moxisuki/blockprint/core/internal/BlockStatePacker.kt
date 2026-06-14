package io.github.moxisuki.blockprint.core.internal

import io.github.moxisuki.blockprint.core.exceptions.LitematicException

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
     * matching [io.github.moxisuki.litematicCore.LitematicRegion.rawIndex].
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
