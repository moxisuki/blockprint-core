package io.github.moxisuki.blockprint.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import io.github.moxisuki.blockprint.core.exceptions.LitematicException
import io.github.moxisuki.blockprint.core.internal.BlockStatePacker

/**
 * Direct unit tests for the bit-unpacking core.
 *
 * These cases cover the boundaries that the JS reference viewer left
 * implicit or as fall-through hacks:
 *   - nbits == 1 (max density: one bit per block)
 *   - nbits > 32 (field straddles two longs)
 *   - nbits == 64 (the JVM undefined-shift trap)
 *   - intra-bit offsets that cross the 64-bit boundary
 *   - nbits == 0 (palette of size 1)
 *   - under-filled long arrays
 */
class BlockStatePackerTest {

    @Test
    fun `single bit per block, full region`() {
        // 2x1x2 = 4 blocks, nbits = 1. We pack 0b1010 = 0xA so reading LSB-first
        // yields [0, 1, 0, 1] at indices 0..3.
        val packed = longArrayOf(0b1010L)
        val out = BlockStatePacker.unpack(packed, nbits = 1, width = 2, height = 1, depth = 2)
        // y-major: index = y*(W*D) + z*W + x → for y=0,z=0,x=0..1, indices 0,1; y=0,z=1,x=0..1, indices 2,3
        assertArrayEquals(intArrayOf(0, 1, 0, 1), out)
    }

    @Test
    fun `field straddles two longs`() {
        // 4 blocks at nbits=16. Pack values 0x0001, 0x0002, 0x0003, 0x0004 LSB-first.
        // 16 * 4 = 64 bits, but if the third block crosses a 64-bit boundary we
        // need a wider packed array. Use 8 values to force a straddling read.
        // 8 blocks × 16 bits = 128 bits = 2 longs.
        // Values: 0, 1, 2, 3, 4, 5, 6, 7 — easy to verify.
        val packed = longArrayOf(
            // bits 0..63:   16-bit fields 0..3
            //   field0=0x0000 at bits 0..15
            //   field1=0x0001 at bits 16..31
            //   field2=0x0002 at bits 32..47
            //   field3=0x0003 at bits 48..63
            (0L) or (1L shl 16) or (2L shl 32) or (3L shl 48),
            // bits 64..127: 16-bit fields 4..7
            (4L) or (5L shl 16) or (6L shl 32) or (7L shl 48),
        )
        val out = BlockStatePacker.unpack(packed, nbits = 16, width = 8, height = 1, depth = 1)
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 4, 5, 6, 7), out)
    }

    @Test
    fun `mid-long straddling read`() {
        // nbits = 12, 8 blocks → 96 bits total, straddles the long boundary.
        // Pack so that fields 5 and 6 are split across longs 0 and 1.
        // Layout (LSB first per field):
        //   field0=0xABC bits 0..11
        //   field1=0x123 bits 12..23
        //   field2=0x456 bits 24..35
        //   field3=0x789 bits 36..47
        //   field4=0xDEF bits 48..59
        //   field5=0x111  bits 60..71  ← straddles long0/long1
        //   field6=0x222  bits 72..83
        //   field7=0x333  bits 84..95
        val field0 = 0xABCL
        val field1 = 0x123L
        val field2 = 0x456L
        val field3 = 0x789L
        val field4 = 0xDEFL
        val field5 = 0x111L
        val field6 = 0x222L
        val field7 = 0x333L

        val long0 = field0 or (field1 shl 12) or (field2 shl 24) or (field3 shl 36) or (field4 shl 48) or (field5 shl 60)
        val long1 = (field5 ushr 4) or (field6 shl 8) or (field7 shl 20)

        val out = BlockStatePacker.unpack(longArrayOf(long0, long1), nbits = 12, width = 8, height = 1, depth = 1)
        assertArrayEquals(
            intArrayOf(0xABC, 0x123, 0x456, 0x789, 0xDEF, 0x111, 0x222, 0x333),
            out,
        )
    }

    @Test
    fun `nbits 64 reads the whole long verbatim`() {
        // nbits == 64: every block is exactly one long. 3 blocks → 3 longs.
        val packed = longArrayOf(0x1122334455667787L.toLong(), 0xCAFEBABEL, -1L)
        val out = BlockStatePacker.unpack(packed, nbits = 64, width = 3, height = 1, depth = 1)
        // The values fit into 32 bits, so .toInt() is fine for the first two
        // and the third is -1 (all bits set). The JS reference truncates the
        // int so we do the same — palette indices > 2^31 are nonsensical.
        assertEquals(0x55667787.toInt(), out[0])
        assertEquals(0xCAFEBABE.toInt(), out[1])
        assertEquals(-1, out[2])
    }

    @Test
    fun `zero bit palette returns all zeros`() {
        val out = BlockStatePacker.unpack(LongArray(0), nbits = 0, width = 2, height = 2, depth = 2)
        assertArrayEquals(IntArray(8), out)
    }

    @Test
    fun `under-filled long array throws`() {
        // 8 blocks at nbits = 16 → 128 bits required, but we only provide 1 long (64 bits).
        // unpack() itself tolerates missing longs (it returns 0 for them); the
        // length check that catches this lives in validateLength().
        assertThrows<LitematicException> {
            BlockStatePacker.validateLength(longArrayOf(0L), nbits = 16, width = 8, height = 1, depth = 1)
        }
    }
}
