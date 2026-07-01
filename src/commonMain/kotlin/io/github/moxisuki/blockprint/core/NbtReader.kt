package io.github.moxisuki.blockprint.core

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.GZIPInputStream

/**
 * Public NBT (Named Binary Tag) parser. Reads a single root tag —
 * typically a named [NbtTag.CompoundTag] — from a byte payload or
 * stream.
 *
 * Supports all 13 NBT tag types. Auto-detects gzip wrapping via the
 * 1F 8B magic at the head of the input, so the same code reads both
 * compressed (`.litematic`, vanilla NBT) and uncompressed NBT files.
 *
 * For most use cases, prefer [NbtDocument.read] which returns a higher-
 * level wrapper. This class is exposed for callers that need direct
 * access to the [NbtTag] tree (e.g. custom NBT dialects, partial reads).
 */
object NbtReader {

    /** Magic bytes for gzip: 0x1F 0x8B. */
    private val GZIP_MAGIC = byteArrayOf(0x1F.toByte(), 0x8B.toByte())

    /**
     * Parse the root compound from a raw NBT byte payload. The root tag
     * must be a [NbtTag.CompoundTag] (id 10); the name is ignored (the
     * spec says it should be empty for vanilla NBT files).
     *
     * @throws IllegalArgumentException if the payload is malformed
     *   (unknown tag id, truncated, non-compound root, etc.)
     */
    fun readRoot(bytes: ByteArray): NbtTag.CompoundTag {
        DataInputStream(openStream(bytes)).use { dis ->
            val tagId = dis.readByte()
            check(tagId == NbtTagType.Compound.id) {
                "Expected root NBT tag to be COMPOUND (10), got ${NbtTagType.fromId(tagId)}"
            }
            // Root name is always "" for vanilla NBT files; consume + ignore.
            dis.readUTF()
            return readCompound(dis)
        }
    }

    /**
     * Same as [readRoot] but streams from an [InputStream] (which is
     * closed by this method).
     */
    fun readRoot(input: InputStream): NbtTag.CompoundTag = input.use { stream ->
        DataInputStream(openStreamFromInput(stream)).use { dis ->
            val tagId = dis.readByte()
            check(tagId == NbtTagType.Compound.id) {
                "Expected root NBT tag to be COMPOUND (10), got ${NbtTagType.fromId(tagId)}"
            }
            dis.readUTF()  // root name
            readCompound(dis)
        }
    }

    private fun openStreamFromInput(stream: InputStream): InputStream {
        val pb = PushbackInputStream(stream, 2)
        val head = ByteArray(2)
        val n = pb.read(head, 0, 2)
        pb.unread(head, 0, n)
        return if (n == 2 && head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte()) {
            GZIPInputStream(pb)
        } else {
            pb
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun openStream(bytes: ByteArray): InputStream =
        if (isGzip(bytes)) {
            GZIPInputStream(ByteArrayInputStream(bytes))
        } else {
            ByteArrayInputStream(bytes)
        }

    /**
     * Same as [readRoot] but skips the bodies of sub-compounds/lists with
     * names that match [skipSubtreeNames]. Useful for Peek: read the root
     * metadata but skip e.g. the `Regions` compound without decoding it.
     */
    fun readRootHeader(
        bytes: ByteArray,
        skipSubtreeNames: Set<String>,
    ): NbtTag.CompoundTag = DataInputStream(
        if (isGzip(bytes)) GZIPInputStream(ByteArrayInputStream(bytes))
        else ByteArrayInputStream(bytes)
    ).use { dis ->
        val tagId = dis.readByte()
        check(tagId == NbtTagType.Compound.id) {
            "Expected root NBT tag to be COMPOUND (10), got ${NbtTagType.fromId(tagId)}"
        }
        dis.readUTF()
        readCompoundHeader(dis, skipSubtreeNames)
    }

    private fun isGzip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == GZIP_MAGIC[0] && bytes[1] == GZIP_MAGIC[1]

    private fun readCompoundHeader(dis: DataInputStream, skipSubtreeNames: Set<String>): NbtTag.CompoundTag {
        val entries = mutableListOf<Pair<String, NbtTag>>()
        while (true) {
            val rawId = dis.readByte()
            val id = NbtTagType.fromId(rawId)
            if (id == NbtTagType.End) break
            val name = dis.readUTF()
            if (name in skipSubtreeNames) {
                skipPayload(dis, id)
                entries += name to NbtTag.EndTag
            } else {
                entries += name to readTagPayload(dis, id)
            }
        }
        return NbtTag.CompoundTag(entries)
    }

    private fun skipPayload(dis: DataInputStream, id: NbtTagType) {
        when (id) {
            NbtTagType.End -> {}
            NbtTagType.Byte -> dis.readByte()
            NbtTagType.Short -> dis.readShort()
            NbtTagType.Int -> dis.readInt()
            NbtTagType.Long -> dis.readLong()
            NbtTagType.Float -> dis.readFloat()
            NbtTagType.Double -> dis.readDouble()
            NbtTagType.ByteArray -> dis.skipNBytes(dis.readInt().toLong())
            NbtTagType.String -> { val n = dis.readUnsignedShort(); dis.skipNBytes(n.toLong()) }
            NbtTagType.List -> {
                val elementType = NbtTagType.fromId(dis.readByte())
                val length = dis.readInt()
                repeat(length) { skipPayload(dis, elementType) }
            }
            NbtTagType.Compound -> {
                while (true) {
                    val next = NbtTagType.fromId(dis.readByte())
                    if (next == NbtTagType.End) break
                    dis.readUTF()
                    skipPayload(dis, next)
                }
            }
            NbtTagType.IntArray -> { val n = dis.readInt(); dis.skipNBytes(n * 4L) }
            NbtTagType.LongArray -> { val n = dis.readInt(); dis.skipNBytes(n * 8L) }
        }
    }

    private fun readCompound(dis: DataInputStream): NbtTag.CompoundTag {
        val entries = mutableListOf<Pair<String, NbtTag>>()
        while (true) {
            val rawId = dis.readByte()
            val id = NbtTagType.fromId(rawId)
            if (id == NbtTagType.End) break
            val name = dis.readUTF()
            entries += name to readTagPayload(dis, id)
        }
        return NbtTag.CompoundTag(entries)
    }

    private fun readTagPayload(dis: DataInputStream, id: NbtTagType): NbtTag = when (id) {
        NbtTagType.End -> NbtTag.EndTag
        NbtTagType.Byte -> NbtTag.ByteTag(dis.readByte())
        NbtTagType.Short -> NbtTag.ShortTag(dis.readShort())
        NbtTagType.Int -> NbtTag.IntTag(dis.readInt())
        NbtTagType.Long -> NbtTag.LongTag(dis.readLong())
        NbtTagType.Float -> NbtTag.FloatTag(dis.readFloat())
        NbtTagType.Double -> NbtTag.DoubleTag(dis.readDouble())
        NbtTagType.ByteArray -> NbtTag.ByteArrayTag(readByteArray(dis))
        NbtTagType.String -> NbtTag.StringTag(dis.readUTF())
        NbtTagType.List -> readList(dis)
        NbtTagType.Compound -> readCompound(dis)
        NbtTagType.IntArray -> NbtTag.IntArrayTag(readIntArray(dis))
        NbtTagType.LongArray -> NbtTag.LongArrayTag(readLongArray(dis))
    }

    private fun readList(dis: DataInputStream): NbtTag.ListTag {
        val rawId = dis.readByte()
        val elementType = NbtTagType.fromId(rawId)
        val length = dis.readInt()
        require(length >= 0) { "Negative NBT list length: $length" }
        if (elementType == NbtTagType.End) {
            // Vanilla allows an empty list to be declared as End. Consume nothing.
            return NbtTag.ListTag(NbtTagType.End, emptyList())
        }
        val items = ArrayList<NbtTag>(length)
        repeat(length) {
            items += readTagPayload(dis, elementType)
        }
        return NbtTag.ListTag(elementType, items)
    }

    private fun readByteArray(dis: DataInputStream): ByteArray {
        val length = dis.readInt()
        require(length >= 0) { "Negative NBT byte array length: $length" }
        val out = ByteArray(length)
        dis.readFully(out)
        return out
    }

    private fun readIntArray(dis: DataInputStream): IntArray {
        val length = dis.readInt()
        require(length >= 0) { "Negative NBT int array length: $length" }
        val out = IntArray(length)
        for (i in 0 until length) out[i] = dis.readInt()
        return out
    }

    private fun readLongArray(dis: DataInputStream): LongArray {
        val length = dis.readInt()
        require(length >= 0) { "Negative NBT long array length: $length" }
        val out = LongArray(length)
        // NBT long arrays are big-endian per spec, matching DataInputStream.readLong().
        for (i in 0 until length) out[i] = dis.readLong()
        return out
    }
}
