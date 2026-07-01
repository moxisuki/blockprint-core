package io.github.moxisuki.blockprint.core.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import kotlin.random.Random

class PackedBlocksUnpackParityTest {
    @Test fun four_bit_matches_BlockStatePacker() {
        val w = 16; val h = 16; val d = 16
        val blocks = IntArray(w * h * d) { Random(42).nextInt(0, 16) }
        val packed = packFromInts(blocks, 4, w, h, d)
        val legacy = BlockStatePacker.unpack(packed, 4, w, h, d)
        val specialized = PackedBlocks.unpack(packed, 4, w, h, d)
        assertArrayEquals(legacy, specialized)
    }

    @Test fun eight_bit_matches_BlockStatePacker() {
        val w = 32; val h = 16; val d = 16
        val blocks = IntArray(w * h * d) { Random(7).nextInt(0, 256) }
        val packed = packFromInts(blocks, 8, w, h, d)
        val legacy = BlockStatePacker.unpack(packed, 8, w, h, d)
        val specialized = PackedBlocks.unpack(packed, 8, w, h, d)
        assertArrayEquals(legacy, specialized)
    }

    @Test fun fallback_path_for_nbits_12_matches_legacy() {
        val w = 16; val h = 16; val d = 16
        val blocks = IntArray(w * h * d) { Random(1).nextInt(0, 1 shl 12) }
        val packed = packFromInts(blocks, 12, w, h, d)
        val legacy = BlockStatePacker.unpack(packed, 12, w, h, d)
        val specialized = PackedBlocks.unpack(packed, 12, w, h, d)
        assertArrayEquals(legacy, specialized)
    }

    private fun packFromInts(blocks: IntArray, nbits: Int, w: Int, h: Int, d: Int): LongArray {
        val mask = (1L shl nbits) - 1L
        val total = blocks.size
        val longCount = (total.toLong() * nbits + 63) / 64
        val longs = LongArray(longCount.toInt())
        var longIdx = 0; var bitPos = 0
        for (v in blocks) {
            longs[longIdx] = longs[longIdx] or ((v.toLong() and mask) shl bitPos)
            bitPos += nbits
            if (bitPos >= 64) { bitPos = 0; longIdx++ }
        }
        return longs
    }
}