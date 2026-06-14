package io.github.moxisuki.blockprint.core

/**
 * Region's block-state palette: an ordered list of [BlockState]s, indexed
 * positionally by the unsigned integers packed into BlockStates.
 *
 * Index 0 is conventionally `minecraft:air` (the JS reference viewer skips
 * index 0; we follow the same convention).
 */
data class BlockPalette(val entries: List<BlockState>) {
    val size: Int get() = entries.size

    /** Number of bits needed to address any index in this palette. */
    val bitsPerBlock: Int
        get() {
            if (entries.size <= 1) return 1
            // ceil(log2(size)) for size >= 2
            var n = entries.size - 1
            var bits = 0
            while (n > 0) { bits++; n = n ushr 1 }
            return bits
        }

    operator fun get(index: Int): BlockState {
        if (index < 0 || index >= entries.size) {
            throw IndexOutOfBoundsException("Palette index $index out of range [0, ${entries.size})")
        }
        return entries[index]
    }
}
