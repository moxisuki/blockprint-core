package io.github.moxisuki.blockprint.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

/**
 * Public NBT (Named Binary Tag) serializer. Writes a single root tag
 * — a [NbtTag.CompoundTag] — to a stream or byte payload.
 *
 * Two API styles:
 * - **In-memory**: pass a fully-built [NbtTag.CompoundTag] to [writeRoot] /
 *   [writeRootToBytes] / [writeRootToGzipBytes].
 * - **Streaming**: use [writeRootHeader], [writeNamed], [writeCompoundEnd],
 *   [writeListHeader], [writeListElement] to emit tag-by-tag without
 *   materialising the full tree. Used by `LitematicWriter`, `SpongeWriter`,
 *   `StructureWriter` to avoid building a complete `NbtTag.CompoundTag`
 *   in memory before serialising.
 *
 * Mirrors [NbtReader]: same tag-id order, same string encoding
 * (modified UTF-8, via [DataOutputStream.writeUTF]), same big-endian
 * numerics. The streaming entry point auto-wraps with `GZIPOutputStream`
 * via the [writeRootToGzipBytes] convenience method only — tag-by-tag
 * primitives emit raw NBT, gzip wrapping (if any) is the caller's job.
 *
 * **Why no `DataOutputStream` wrappers in the streaming primitives:**
 * wrapping [out] in a fresh `DataOutputStream` per call and then
 * `close()`-ing it via `.use {}` is unsafe when the caller has wrapped
 * [out] in a chain like `GZIPOutputStream(BufferedOutputStream(...))`.
 * Some JVM `DataOutputStream.close()` implementations propagate the
 * close to the underlying stream, which would finalise the gzip stream
 * mid-write. The streaming primitives here operate on raw bytes and
 * rely on the caller to flush / close the underlying stream at the end.
 */
object NbtWriter {

    // ───────────────────────────────────────────────────────────────
    // Convenience: in-memory → byte[] / stream
    // ───────────────────────────────────────────────────────────────

    /**
     * Serialize [root] as a named compound NBT root into [out]. The
     * root name is written as the empty string (matching vanilla NBT
     * files that [NbtReader.readRoot] accepts).
     */
    fun writeRoot(root: NbtTag.CompoundTag, out: OutputStream) {
        writeRootHeader(out)
        // writeCompoundBodyRaw writes the trailing End byte for the
        // root compound — no extra End needed here.
        writeCompoundBodyRaw(root, out)
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

    // ───────────────────────────────────────────────────────────────
    // Streaming primitives (used by internal format writers).
    //
    // These write raw bytes via [OutputStream.write].  They never wrap
    // the stream in DataOutputStream or .use {} it — see the class kdoc
    // for why.
    // ───────────────────────────────────────────────────────────────

    /**
     * Write the NBT root header: 1 byte Compound tag id + 2-byte empty
     * root name (modified UTF-8).  Callers that stream tag-by-tag must
     * emit this first so the reader picks up the same Compound header
     * it would have seen from a fully-built tree.
     */
    fun writeRootHeader(out: OutputStream) {
        out.write(NbtTagType.Compound.id.toInt())
        // UTF-8 encoded "" name: 2-byte length = 0 (big-endian).
        out.write(0); out.write(0)
    }

    /**
     * Write a single named tag: 1 byte tag id + 2-byte UTF name + payload.
     */
    fun writeNamed(name: String, tag: NbtTag, out: OutputStream) {
        writeNamedTagRaw(name, tag, out)
    }

    /**
     * Open a named compound tag: writes tag id (Compound) + UTF name.  The
     * caller writes the body via [writeNamed] calls, then closes with
     * [writeCompoundEnd].
     *
     * Use this when the body of the compound is built up over many calls
     * (e.g. one entry per region) and you can't materialise the full
     * [NbtTag.CompoundTag] up front.
     */
    fun writeCompoundOpen(name: String, out: OutputStream) {
        out.write(NbtTagType.Compound.id.toInt())
        writeUtf(name, out)
    }

    /**
     * Write the NBT compound terminator byte (id 0).  Use this to close
     * a compound tag body you opened with [writeRootHeader] (for the root),
     * [writeNamed] when the payload is itself a compound, or
     * [writeCompoundOpen] for streamed compound bodies.
     */
    fun writeCompoundEnd(out: OutputStream) {
        out.write(NbtTagType.End.id.toInt())
    }

    /**
     * Write a list tag header: 1 byte List id + 2-byte UTF name + 1 byte
     * element type id + 4-byte length.  After this, the caller writes
     * exactly [length] elements via [writeListElement].  NBT lists are
     * length-prefixed — there is no terminator to write after the
     * elements.
     */
    fun writeListHeader(name: String, elementType: NbtTagType, length: Int, out: OutputStream) {
        out.write(NbtTagType.List.id.toInt())
        writeUtf(name, out)
        out.write(elementType.id.toInt())
        writeInt(length, out)
    }

    /**
     * Write a single list element payload (no tag id, no name).  The
     * caller must already have emitted a [writeListHeader] that declares
     * this element's type.
     */
    fun writeListElement(tag: NbtTag, out: OutputStream) {
        writeListItemRaw(tag, out)
    }

    // ───────────────────────────────────────────────────────────────
    // Internals — same-module writers call these via `DataOutputStream`
    // when they own the stream's lifetime.  These still work fine; the
    // bug above only affected the public primitives that wrapped the
    // caller's stream in a fresh DataOutputStream and .use {}'d it.
    // ───────────────────────────────────────────────────────────────

    internal fun writeCompoundBody(c: NbtTag.CompoundTag, dos: DataOutputStream) {
        for ((name, tag) in c.value) {
            writeNamedTag(name, tag, dos)
        }
        dos.writeByte(NbtTagType.End.id.toInt())
    }

    internal fun writeNamedTag(name: String, tag: NbtTag, dos: DataOutputStream) {
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

    internal fun writeListItem(tag: NbtTag, dos: DataOutputStream) {
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

    // ───────────────────────────────────────────────────────────────
    // Raw byte-level helpers — used by the streaming primitives
    // above.  These exist because we can't wrap the caller's stream in
    // DataOutputStream safely (see class kdoc).
    // ───────────────────────────────────────────────────────────────

    /** UTF-8-modified: 2-byte big-endian length + UTF-8 bytes (or
     *  `DataOutputStream.writeUTF` semantics: same length encoding,
     *  same modified UTF-8). */
    private fun writeUtf(s: String, out: OutputStream) {
        // DataOutputStream.writeUTF uses modified UTF-8 (slightly different
        // from standard UTF-8).  Encode manually to match the reader
        // which uses DataInputStream.readUTF.
        val encoded = encodeModifiedUtf8(s)
        writeUnsignedShort(encoded.size, out)
        out.write(encoded, 0, encoded.size)
    }

    private fun writeUnsignedShort(v: Int, out: OutputStream) {
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeInt(v: Int, out: OutputStream) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeLong(v: Long, out: OutputStream) {
        out.write(((v ushr 56) and 0xFF).toInt())
        out.write(((v ushr 48) and 0xFF).toInt())
        out.write(((v ushr 40) and 0xFF).toInt())
        out.write(((v ushr 32) and 0xFF).toInt())
        out.write(((v ushr 24) and 0xFF).toInt())
        out.write(((v ushr 16) and 0xFF).toInt())
        out.write(((v ushr 8) and 0xFF).toInt())
        out.write((v and 0xFF).toInt())
    }

    private fun writeFloat(v: Float, out: OutputStream) =
        writeInt(java.lang.Float.floatToRawIntBits(v), out)

    private fun writeDouble(v: Double, out: OutputStream) =
        writeLong(java.lang.Double.doubleToRawLongBits(v), out)

    /** Per DataOutputStream.writeUTF: 2-byte unsigned length, then
     *  modified UTF-8 bytes (NUL encodes as 0xC0 0x80, surrogate halves
     *  are re-encoded). */
    private fun encodeModifiedUtf8(s: String): ByteArray {
        val len = s.length
        // Count output bytes.
        var utfLen = 0
        var i = 0
        while (i < len) {
            val c = s[i].code
            utfLen += when {
                c in 0x0001..0x007F -> 1
                c == 0x0000 -> 2
                c in 0x0080..0x07FF -> 2
                c in 0xD800..0xDBFF && i + 1 < len &&
                    s[i + 1].code in 0xDC00..0xDFFF -> 4
                c in 0x0800..0xFFFF -> 3
                else -> 1
            }
            i++
        }
        if (utfLen > 65535) throw RuntimeException("Modified UTF-8 length > 65535")
        val out = ByteArray(utfLen)
        var oi = 0
        i = 0
        while (i < len) {
            val c = s[i].code
            when {
                c in 0x0001..0x007F -> out[oi++] = c.toByte()
                c == 0x0000 -> { out[oi++] = 0xC0.toByte(); out[oi++] = 0x80.toByte() }
                c in 0x0080..0x07FF -> {
                    out[oi++] = (0xC0 or (c shr 6)).toByte()
                    out[oi++] = (0x80 or (c and 0x3F)).toByte()
                }
                c in 0xD800..0xDBFF && i + 1 < len &&
                    s[i + 1].code in 0xDC00..0xDFFF -> {
                    val c1 = c
                    val c2 = s[i + 1].code
                    i += 2
                    val cp = 0x010000 +
                        ((c1 and 0x03FF) shl 10) + (c2 and 0x03FF)
                    out[oi++] = (0xF0 or (cp shr 18)).toByte()
                    out[oi++] = (0x80 or ((cp shr 12) and 0x3F)).toByte()
                    out[oi++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                    out[oi++] = (0x80 or (cp and 0x3F)).toByte()
                    continue  // i was already advanced by 2
                }
                c in 0x0800..0xFFFF -> {
                    out[oi++] = (0xE0 or (c shr 12)).toByte()
                    out[oi++] = (0x80 or ((c shr 6) and 0x3F)).toByte()
                    out[oi++] = (0x80 or (c and 0x3F)).toByte()
                }
                else -> out[oi++] = c.toByte()
            }
            i++
        }
        return out
    }

    private fun writeCompoundBodyRaw(c: NbtTag.CompoundTag, out: OutputStream) {
        for ((name, tag) in c.value) writeNamedTagRaw(name, tag, out)
        out.write(NbtTagType.End.id.toInt()) // terminator
    }

    private fun writeNamedTagRaw(name: String, tag: NbtTag, out: OutputStream) {
        when (tag) {
            is NbtTag.EndTag -> out.write(NbtTagType.End.id.toInt())
            is NbtTag.ByteTag -> {
                out.write(NbtTagType.Byte.id.toInt())
                writeUtf(name, out); out.write(tag.value.toInt())
            }
            is NbtTag.ShortTag -> {
                out.write(NbtTagType.Short.id.toInt())
                writeUtf(name, out); writeShort(tag.value.toInt(), out)
            }
            is NbtTag.IntTag -> {
                out.write(NbtTagType.Int.id.toInt())
                writeUtf(name, out); writeInt(tag.value, out)
            }
            is NbtTag.LongTag -> {
                out.write(NbtTagType.Long.id.toInt())
                writeUtf(name, out); writeLong(tag.value, out)
            }
            is NbtTag.FloatTag -> {
                out.write(NbtTagType.Float.id.toInt())
                writeUtf(name, out); writeFloat(tag.value, out)
            }
            is NbtTag.DoubleTag -> {
                out.write(NbtTagType.Double.id.toInt())
                writeUtf(name, out); writeDouble(tag.value, out)
            }
            is NbtTag.ByteArrayTag -> {
                out.write(NbtTagType.ByteArray.id.toInt())
                writeUtf(name, out)
                writeInt(tag.value.size, out)
                out.write(tag.value)
            }
            is NbtTag.StringTag -> {
                out.write(NbtTagType.String.id.toInt())
                writeUtf(name, out); writeUtf(tag.value, out)
            }
            is NbtTag.ListTag -> {
                out.write(NbtTagType.List.id.toInt())
                writeUtf(name, out)
                out.write(tag.elementType.id.toInt())
                writeInt(tag.value.size, out)
                for (item in tag.value) writeListItemRaw(item, out)
            }
            is NbtTag.CompoundTag -> {
                out.write(NbtTagType.Compound.id.toInt())
                writeUtf(name, out)
                writeCompoundBodyRaw(tag, out) // writes End terminator
            }
            is NbtTag.IntArrayTag -> {
                out.write(NbtTagType.IntArray.id.toInt())
                writeUtf(name, out)
                writeInt(tag.value.size, out)
                for (v in tag.value) writeInt(v, out)
            }
            is NbtTag.LongArrayTag -> {
                out.write(NbtTagType.LongArray.id.toInt())
                writeUtf(name, out)
                writeInt(tag.value.size, out)
                for (v in tag.value) writeLong(v, out)
            }
        }
    }

    private fun writeListItemRaw(tag: NbtTag, out: OutputStream) {
        when (tag) {
            is NbtTag.EndTag -> {}
            is NbtTag.ByteTag -> out.write(tag.value.toInt())
            is NbtTag.ShortTag -> writeShort(tag.value.toInt(), out)
            is NbtTag.IntTag -> writeInt(tag.value, out)
            is NbtTag.LongTag -> writeLong(tag.value, out)
            is NbtTag.FloatTag -> writeFloat(tag.value, out)
            is NbtTag.DoubleTag -> writeDouble(tag.value, out)
            is NbtTag.ByteArrayTag -> {
                writeInt(tag.value.size, out); out.write(tag.value)
            }
            is NbtTag.StringTag -> writeUtf(tag.value, out)
            is NbtTag.ListTag -> {
                out.write(tag.elementType.id.toInt())
                writeInt(tag.value.size, out)
                for (item in tag.value) writeListItemRaw(item, out)
            }
            is NbtTag.CompoundTag -> writeCompoundBodyRaw(tag, out)
            is NbtTag.IntArrayTag -> {
                writeInt(tag.value.size, out)
                for (v in tag.value) writeInt(v, out)
            }
            is NbtTag.LongArrayTag -> {
                writeInt(tag.value.size, out)
                for (v in tag.value) writeLong(v, out)
            }
        }
    }

    private fun writeShort(v: Int, out: OutputStream) {
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }
}
