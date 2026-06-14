package io.github.moxisuki.blockprint.core

/**
 * NBT (Named Binary Tag) tag type identifiers — the 13 types defined
 * by the NBT spec at <https://minecraft.wiki/w/NBT_format>.
 *
 * Used by [NbtTag.ListTag.elementType] to identify the declared element
 * type of a list (all elements of an NBT list share the same type).
 */
enum class NbtTagType(val id: Byte) {
    End(0),
    Byte(1),
    Short(2),
    Int(3),
    Long(4),
    Float(5),
    Double(6),
    ByteArray(7),
    String(8),
    List(9),
    Compound(10),
    IntArray(11),
    LongArray(12);

    companion object {
        fun fromId(id: Byte): NbtTagType =
            entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Unknown NBT tag id: $id")
    }
}

/**
 * Discriminated union for the NBT values this library can read.
 *
 * Mirrors the 13 NBT tag types. Use a `when` block (the type is sealed)
 * to walk the tree:
 *
 * ```kotlin
 * val doc = NbtDocument.read(file)
 * fun describe(tag: NbtTag): String = when (tag) {
 *     is NbtTag.CompoundTag -> "compound(${tag.entries().size} entries)"
 *     is NbtTag.ListTag     -> "list[${tag.elementType}] of ${tag.value.size}"
 *     is NbtTag.StringTag   -> tag.value
 *     is NbtTag.IntTag      -> tag.value.toString()
 *     // ... 9 more
 * }
 * ```
 *
 * Tag IDs follow the NBT spec; byte ordering is big-endian everywhere.
 */
sealed class NbtTag {
    data object EndTag : NbtTag()
    data class ByteTag(val value: Byte) : NbtTag()
    data class ShortTag(val value: Short) : NbtTag()
    data class IntTag(val value: Int) : NbtTag()
    data class LongTag(val value: Long) : NbtTag()
    data class FloatTag(val value: Float) : NbtTag()
    data class DoubleTag(val value: Double) : NbtTag()
    data class ByteArrayTag(val value: ByteArray) : NbtTag()
    data class StringTag(val value: String) : NbtTag()

    /**
     * List payload. [elementType] is the declared element id at the head
     * of the list; [value] is the contained tags, all of that type.
     */
    data class ListTag(
        val elementType: NbtTagType,
        val value: List<NbtTag>,
    ) : NbtTag()

    /** Compound payload: ordered list of name→tag pairs (preserves insertion order). */
    data class CompoundTag(val value: List<Pair<String, NbtTag>>) : NbtTag() {
        private val map: Map<String, NbtTag> = value.toMap()

        fun get(name: String): NbtTag? = map[name]

        fun require(name: String): NbtTag =
            map[name] ?: throw IllegalArgumentException("Missing required NBT field: $name")

        fun contains(name: String): Boolean = map.containsKey(name)

        fun entries(): List<Pair<String, NbtTag>> = value

        fun names(): Set<String> = map.keys
    }

    data class IntArrayTag(val value: IntArray) : NbtTag()
    data class LongArrayTag(val value: LongArray) : NbtTag()
}
