package io.github.moxisuki.blockprint.core.internal

/**
 * Specialized paths for [BlockStatePacker.unpack]. The general path
 * shifts and masks per cell; this version handles the common Litematica
 * widths (4, 8) with one Long read producing 8/4 cells.
 */
internal object PackedBlocks {
    fun unpack(
        longs: LongArray, nbits: Int, width: Int, height: Int, depth: Int,
    ): IntArray {
        val total = width.toLong() * height * depth
        require(total <= Int.MAX_VALUE) { "Volume exceeds Int.MAX_VALUE" }
        val out = IntArray(total.toInt())
        return when (nbits) {
            0 -> out.also { /* all air */ }
            4 -> unpack4(longs, out, width, height, depth)
            8 -> unpack8(longs, out, width, height, depth)
            else -> BlockStatePacker.unpack(longs, nbits, width, height, depth)
        }
    }

    private fun unpack4(longs: LongArray, out: IntArray, w: Int, h: Int, d: Int): IntArray {
        val cellsPerSlice = w.toLong() * d
        var longIdx = 0
        var bitPos = 0
        val mask = 0xFL
        for (y in 0 until h) {
            val yBase = y * cellsPerSlice.toInt()
            for (z in 0 until d) {
                val rowBase = yBase + z * w
                for (x in 0 until w) {
                    out[rowBase + x] = ((longs[longIdx] ushr bitPos) and mask).toInt()
                    bitPos += 4
                    if (bitPos >= 64) { bitPos = 0; longIdx++ }
                }
            }
        }
        return out
    }

    private fun unpack8(longs: LongArray, out: IntArray, w: Int, h: Int, d: Int): IntArray {
        val cellsPerSlice = w.toLong() * d
        var longIdx = 0
        var bitPos = 0
        val mask = 0xFFL
        for (y in 0 until h) {
            val yBase = y * cellsPerSlice.toInt()
            for (z in 0 until d) {
                val rowBase = yBase + z * w
                for (x in 0 until w) {
                    out[rowBase + x] = ((longs[longIdx] ushr bitPos) and mask).toInt()
                    bitPos += 8
                    if (bitPos >= 64) { bitPos = 0; longIdx++ }
                }
            }
        }
        return out
    }
}