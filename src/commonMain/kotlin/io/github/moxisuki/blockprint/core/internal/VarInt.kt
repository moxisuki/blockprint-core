package io.github.moxisuki.blockprint.core.internal

import io.github.moxisuki.blockprint.core.exceptions.NbtFormatException

/**
 * Protobuf-style varint (7 bits/byte, MSB = continuation).
 * Used by Sponge v2 + v3 schematic block data.
 */
internal object VarInt {
    data class Result(val value: Int, val nextPos: Int)

    fun decode(src: ByteArray, startPos: Int): Result {
        var result = 0
        var shift = 0
        var pos = startPos
        while (true) {
            if (pos >= src.size) throw NbtFormatException(pos.toLong(), "Sponge: varint truncated at byte $pos")
            val b = src[pos].toInt() and 0xFF
            pos++
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) return Result(result, pos)
            shift += 7
            if (shift >= 35) throw NbtFormatException(pos.toLong(), "Sponge: varint too long (overflow)")
        }
    }

    fun encode(out: java.io.ByteArrayOutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv().toInt() != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v and 0x7F)
    }
}
