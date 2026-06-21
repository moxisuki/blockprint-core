package io.github.moxisuki.blockprint.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

/**
 * Public NBT (Named Binary Tag) serializer. Writes a single root tag
 * — a [NbtTag.CompoundTag] — to a stream or byte payload.
 *
 * Mirrors [NbtReader]: same tag-id order, same string encoding
 * (modified UTF-8, via [DataOutputStream.writeUTF]), same big-endian
 * numerics. Auto-wraps with GZIP only when the caller asks for it
 * via [writeRootToGzipBytes]; the stream-based [writeRoot] and the
 * [writeRootToBytes] variants emit raw NBT.
 */
object NbtWriter {

    /**
     * Serialize [root] as a named compound NBT root into [out]. The
     * root name is written as the empty string (matching vanilla NBT
     * files that [NbtReader.readRoot] accepts).
     */
    fun writeRoot(root: NbtTag.CompoundTag, out: OutputStream) {
        DataOutputStream(out).use { dos ->
            dos.writeByte(NbtTagType.Compound.id.toInt())
            dos.writeUTF("") // root name
            writeCompoundBody(root, dos)
        }
    }

    /** Convenience: [writeRoot] into a fresh `ByteArrayOutputStream`. */
    fun writeRootToBytes(root: NbtTag.CompoundTag): ByteArray {
        val baos = ByteArrayOutputStream()
        writeRoot(root, baos)
        return baos.toByteArray()
    }

    /** Convenience: [writeRoot] wrapped in a [GZIPOutputStream]. */
    fun writeRootToGzipBytes(root: NbtTag.CompoundTag): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gz -> writeRoot(root, gz) }
        return baos.toByteArray()
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun writeCompoundBody(c: NbtTag.CompoundTag, dos: DataOutputStream) {
        for ((name, tag) in c.value) {
            writeNamedTag(name, tag, dos)
        }
        dos.writeByte(NbtTagType.End.id.toInt())
    }

    private fun writeNamedTag(name: String, tag: NbtTag, dos: DataOutputStream) {
        when (tag) {
            is NbtTag.EndTag -> dos.writeByte(NbtTagType.End.id.toInt())
            is NbtTag.ByteTag -> {
                dos.writeByte(NbtTagType.Byte.id.toInt())
                dos.writeUTF(name); dos.writeByte(tag.value.toInt())
            }
            is NbtTag.ShortTag -> {
                dos.writeByte(NbtTagType.Short.id.toInt())
                dos.writeUTF(name); dos.writeShort(tag.value.toInt())
            }
            is NbtTag.IntTag -> {
                dos.writeByte(NbtTagType.Int.id.toInt())
                dos.writeUTF(name); dos.writeInt(tag.value)
            }
            is NbtTag.LongTag -> {
                dos.writeByte(NbtTagType.Long.id.toInt())
                dos.writeUTF(name); dos.writeLong(tag.value)
            }
            is NbtTag.FloatTag -> {
                dos.writeByte(NbtTagType.Float.id.toInt())
                dos.writeUTF(name); dos.writeFloat(tag.value)
            }
            is NbtTag.DoubleTag -> {
                dos.writeByte(NbtTagType.Double.id.toInt())
                dos.writeUTF(name); dos.writeDouble(tag.value)
            }
            is NbtTag.ByteArrayTag -> {
                dos.writeByte(NbtTagType.ByteArray.id.toInt())
                dos.writeUTF(name)
                dos.writeInt(tag.value.size)
                dos.write(tag.value)
            }
            is NbtTag.StringTag -> {
                dos.writeByte(NbtTagType.String.id.toInt())
                dos.writeUTF(name); dos.writeUTF(tag.value)
            }
            is NbtTag.ListTag -> {
                dos.writeByte(NbtTagType.List.id.toInt())
                dos.writeUTF(name)
                dos.writeByte(tag.elementType.id.toInt())
                dos.writeInt(tag.value.size)
                for (item in tag.value) writeListItem(item, dos)
            }
            is NbtTag.CompoundTag -> {
                dos.writeByte(NbtTagType.Compound.id.toInt())
                dos.writeUTF(name)
                writeCompoundBody(tag, dos)
            }
            is NbtTag.IntArrayTag -> {
                dos.writeByte(NbtTagType.IntArray.id.toInt())
                dos.writeUTF(name)
                dos.writeInt(tag.value.size)
                for (v in tag.value) dos.writeInt(v)
            }
            is NbtTag.LongArrayTag -> {
                dos.writeByte(NbtTagType.LongArray.id.toInt())
                dos.writeUTF(name)
                dos.writeInt(tag.value.size)
                for (v in tag.value) dos.writeLong(v)
            }
        }
    }

    private fun writeListItem(tag: NbtTag, dos: DataOutputStream) {
        when (tag) {
            is NbtTag.EndTag -> {} // never written inside a list
            is NbtTag.ByteTag -> dos.writeByte(tag.value.toInt())
            is NbtTag.ShortTag -> dos.writeShort(tag.value.toInt())
            is NbtTag.IntTag -> dos.writeInt(tag.value)
            is NbtTag.LongTag -> dos.writeLong(tag.value)
            is NbtTag.FloatTag -> dos.writeFloat(tag.value)
            is NbtTag.DoubleTag -> dos.writeDouble(tag.value)
            is NbtTag.ByteArrayTag -> {
                dos.writeInt(tag.value.size); dos.write(tag.value)
            }
            is NbtTag.StringTag -> dos.writeUTF(tag.value)
            is NbtTag.ListTag -> {
                dos.writeByte(tag.elementType.id.toInt())
                dos.writeInt(tag.value.size)
                for (item in tag.value) writeListItem(item, dos)
            }
            is NbtTag.CompoundTag -> writeCompoundBody(tag, dos)
            is NbtTag.IntArrayTag -> {
                dos.writeInt(tag.value.size)
                for (v in tag.value) dos.writeInt(v)
            }
            is NbtTag.LongArrayTag -> {
                dos.writeInt(tag.value.size)
                for (v in tag.value) dos.writeLong(v)
            }
        }
    }
}
