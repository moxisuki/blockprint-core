package io.github.moxisuki.blockprint.core.internal

import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.NbtTag
import io.github.moxisuki.blockprint.core.exceptions.BlockPrintException

/**
 * Shared helpers for reading common NBT patterns. Used by all format
 * readers.
 */
internal object NbtAccessors {
    fun readInt3(compound: NbtTag.CompoundTag, label: String): IntArray {
        val x = (compound.get("x") as? NbtTag.IntTag)?.value
            ?: throw BlockPrintException("$label missing int field 'x'")
        val y = (compound.get("y") as? NbtTag.IntTag)?.value
            ?: throw BlockPrintException("$label missing int field 'y'")
        val z = (compound.get("z") as? NbtTag.IntTag)?.value
            ?: throw BlockPrintException("$label missing int field 'z'")
        return intArrayOf(x, y, z)
    }

    fun readStringOrEmpty(c: NbtTag.CompoundTag?, key: String): String {
        val t = c?.get(key) as? NbtTag.StringTag ?: return ""
        return t.value
    }

    fun readIntOrNull(c: NbtTag.CompoundTag, key: String): Int? =
        (c.get(key) as? NbtTag.IntTag)?.value

    /**
     * Read a Sponge v3 palette key of the form `minecraft:foo[k=v,k2=v2]`
     * (or just `minecraft:foo` when no properties).
     */
    fun parseSpongeV3Key(key: String): BlockState {
        val bracket = key.indexOf('[')
        if (bracket < 0) return BlockState(key, null)
        val name = key.substring(0, bracket)
        val body = key.substring(bracket + 1, key.length - 1)
        if (body.isEmpty()) return BlockState(name, null)
        val props = LinkedHashMap<String, String>()
        for (pair in body.split(',')) {
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            val pk = pair.substring(0, eq).trim()
            var pv = pair.substring(eq + 1).trim()
            if (pv.length >= 2 && pv.first() == '"' && pv.last() == '"') {
                pv = pv.substring(1, pv.length - 1)
            }
            if (pk.isNotEmpty()) props[pk] = pv
        }
        return BlockState(name, props)
    }
}