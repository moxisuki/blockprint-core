package io.github.moxisuki.blockprint.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Minimal NBT writer used by tests. We only need to emit litematic-shaped
 * data, so we hard-code the tag types we touch:
 *
 *   - root compound (id 10) with name "" and entries
 *   - nested compound (id 10)
 *   - list of compound (id 9, element id 10)
 *   - string (id 8)
 *   - int (id 3)
 *   - long array (id 12)
 */
internal object TestNbt {

    fun buildLitematic(
        width: Int,
        height: Int,
        depth: Int,
        palette: List<Pair<String, Map<String, String>?>>,
        blockIndices: IntArray, // length must equal width*height*depth
        origin: Triple<Int, Int, Int>? = null,
        name: String = "Test",
        author: String = "litematic-lib",
        description: String = "",
        version: Int = 6,
        mcDataVersion: Int = 3953, // 1.21
    ): ByteArray {
        val nbits = nbitsFor(palette.size)
        val packed = pack(blockIndices, nbits)

        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            // Root compound
            out.writeByte(10)
            out.writeUTF("")

            // ---- File-level metadata ----
            out.writeByte(8); out.writeUTF("Name"); out.writeUTF(name)
            out.writeByte(8); out.writeUTF("Author"); out.writeUTF(author)
            out.writeByte(8); out.writeUTF("Description"); out.writeUTF(description)
            out.writeByte(3); out.writeUTF("Version"); out.writeInt(version)
            out.writeByte(3); out.writeUTF("MinecraftDataVersion"); out.writeInt(mcDataVersion)

            // ---- Regions compound ----
            out.writeByte(10); out.writeUTF("Regions")
            out.writeByte(10); out.writeUTF("TestRegion")

            // Size compound
            out.writeByte(10); out.writeUTF("Size")
            out.writeByte(3); out.writeUTF("x"); out.writeInt(width)
            out.writeByte(3); out.writeUTF("y"); out.writeInt(height)
            out.writeByte(3); out.writeUTF("z"); out.writeInt(depth)
            out.writeByte(0) // end Size

            // Position compound (optional)
            if (origin != null) {
                out.writeByte(10); out.writeUTF("Position")
                out.writeByte(3); out.writeUTF("x"); out.writeInt(origin.first)
                out.writeByte(3); out.writeUTF("y"); out.writeInt(origin.second)
                out.writeByte(3); out.writeUTF("z"); out.writeInt(origin.third)
                out.writeByte(0) // end Position
            }

            // BlockStatePalette (list of compound, element id 10)
            out.writeByte(9); out.writeUTF("BlockStatePalette")
            out.writeByte(10)              // element type (Compound)
            out.writeInt(palette.size)
            for ((name, props) in palette) {
                // Compound elements in a list have no outer tag id or name;
                // their contents are written straight in.
                out.writeByte(8); out.writeUTF("Name"); out.writeUTF(name)
                if (!props.isNullOrEmpty()) {
                    out.writeByte(10); out.writeUTF("Properties")
                    for ((k, v) in props) {
                        out.writeByte(8); out.writeUTF(k); out.writeUTF(v)
                    }
                    out.writeByte(0) // end Properties compound
                }
                // end block-state compound
                out.writeByte(0)
            }

            // BlockStates (long array, id 12)
            out.writeByte(12); out.writeUTF("BlockStates")
            out.writeInt(packed.size)
            for (l in packed) out.writeLong(l)

            // Close region compound
            out.writeByte(0)
            // Close Regions compound
            out.writeByte(0)
            // Close root compound
            out.writeByte(0)
        }

        return baos.toByteArray()
    }

    /** ceil(log2(n)) for n >= 1; matches BlockPalette.bitsPerBlock. */
    fun nbitsFor(paletteSize: Int): Int {
        require(paletteSize >= 1) { "Palette must have at least one entry" }
        if (paletteSize == 1) return 0
        var n = paletteSize - 1
        var bits = 0
        while (n > 0) { bits++; n = n ushr 1 }
        return bits
    }

    /**
     * Pack [indices] into a LongArray using [nbits] per index, LSB-first,
     * y-major / z-middle / x-minor. Total cell count must equal indices.size.
     */
    fun pack(indices: IntArray, nbits: Int): LongArray {
        val requiredBits = indices.size.toLong() * nbits
        val longCount = ((requiredBits + 63) / 64).toInt()
        val out = LongArray(longCount)
        if (nbits == 0) return out
        for ((i, v) in indices.withIndex()) {
            val bit = i.toLong() * nbits
            val longIdx = (bit ushr 6).toInt()
            val intra = (bit and 0x3F).toInt()
            val lv = v.toLong() and ((1L shl nbits) - 1L)
            out[longIdx] = out[longIdx] or (lv shl intra)
            if (intra + nbits > 64) {
                val overflow = intra + nbits - 64
                out[longIdx + 1] = out[longIdx + 1] or (lv ushr (nbits - overflow))
            }
        }
        return out
    }
}
