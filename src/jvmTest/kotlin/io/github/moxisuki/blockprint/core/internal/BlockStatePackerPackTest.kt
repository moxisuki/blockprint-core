package io.github.moxisuki.blockprint.core.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class BlockStatePackerPackTest {

    @Test
    fun pack_then_unpack_is_identity() {
        val rng = Random(42)
        for (trial in 0 until 20) {
            val width = 1 + rng.nextInt(5)
            val height = 1 + rng.nextInt(4)
            val depth = 1 + rng.nextInt(5)
            val paletteSize = 1 + rng.nextInt(15)
            val nbits = BlockPaletteBits.bitsNeeded(paletteSize)
            val blocks = IntArray(width * height * depth) { rng.nextInt(paletteSize) }
            val packed = BlockStatePacker.pack(blocks, nbits, width, height, depth)
            val unpacked = BlockStatePacker.unpack(packed, nbits, width, height, depth)
            assertArrayEquals(blocks, unpacked)
        }
    }

    @Test
    fun pack_all_zeros_yields_zeros() {
        val blocks = IntArray(2 * 3 * 4)
        val packed = BlockStatePacker.pack(blocks, nbits = 1, width = 2, height = 3, depth = 4)
        // 1 bit per block * 24 blocks = 24 bits = 1 long (with padding zeros)
        assertEquals(1, packed.size)
        assertEquals(0L, packed[0])
    }

    @Test
    fun pack_little_endian_bit_order() {
        // 2 blocks, nbits=4, blocks=[0xA, 0x5]
        // bit 0..3 = 0xA = 0b1010
        // bit 4..7 = 0x5 = 0b0101
        // long LSB-first: 0x5A
        val blocks = intArrayOf(0xA, 0x5)
        val packed = BlockStatePacker.pack(blocks, nbits = 4, width = 2, height = 1, depth = 1)
        assertEquals(1, packed.size)
        assertEquals(0x5AL, packed[0])
    }

    @Test
    fun pack_straddle_two_longs() {
        // nbits=33, 3 cells: bit 0..32 = cell 0, bit 33..65 = cells 1 and 2 (straddles)
        val blocks = intArrayOf(0x1, 0x2, 0x3)
        val packed = BlockStatePacker.pack(blocks, nbits = 33, width = 3, height = 1, depth = 1)
        val unpacked = BlockStatePacker.unpack(packed, nbits = 33, width = 3, height = 1, depth = 1)
        assertArrayEquals(blocks, unpacked)
    }

    @Test
    fun pack_nbits_64_round_trip() {
        // nbits=64 — the masking edge case. Use a 2-cell region so the
        // value fits in a Long and the round-trip is well-defined.
        val blocks = intArrayOf(0x1L.toInt(), 0xFFFFFFFFL.toInt())
        val packed = BlockStatePacker.pack(blocks, nbits = 64, width = 2, height = 1, depth = 1)
        val unpacked = BlockStatePacker.unpack(packed, nbits = 64, width = 2, height = 1, depth = 1)
        assertArrayEquals(blocks, unpacked)
    }
}

private object BlockPaletteBits {
    fun bitsNeeded(paletteSize: Int): Int =
        if (paletteSize <= 1) 1 else (0..30).first { (1 shl it) >= paletteSize }
}
