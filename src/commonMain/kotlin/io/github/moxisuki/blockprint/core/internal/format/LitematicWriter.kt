package io.github.moxisuki.blockprint.core.internal.format

import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.Litematic
import io.github.moxisuki.blockprint.core.LitematicRegion
import io.github.moxisuki.blockprint.core.NbtTag
import io.github.moxisuki.blockprint.core.NbtTagType
import io.github.moxisuki.blockprint.core.NbtWriter
import io.github.moxisuki.blockprint.core.internal.BlockStatePacker
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

/**
 * Encode a [Litematic] as a `.litematic` (gzipped NBT) byte payload.
 *
 * Output schema mirrors what [io.github.moxisuki.blockprint.core.internal.LitematicParser]
 * reads, so a `write → read` round-trip is structurally stable.
 *
 * `Litematic.format` is a read-side category (see [Litematic.format]) and
 * is not serialized — the reader re-derives it from the NBT structure.
 *
 * Two entry shapes:
 * - [write] returning `ByteArray` — kept for back-compat. Builds the
 *   full NBT tree in memory (via [NbtWriter.writeRootToGzipBytes]).
 *   Convenient for small regions.
 * - [write] into an [OutputStream] — streaming variant. Emits tags
 *   directly to the stream without materialising the full tree; the
 *   `BlockStates` long array is bit-packed straight to the underlying
 *   [DataOutputStream], avoiding the 5 MB+ `LongArray` intermediate
 *   that the in-memory path allocates for large regions.
 *
 * Both paths produce **byte-for-byte identical** output.
 */
internal object LitematicWriter {

    /** Default `MinecraftDataVersion` to write when the input is null. 3465 ≈ 1.21. */
    private const val DEFAULT_DATA_VERSION = 3465

    /** Default Litematica file format version. */
    private const val DEFAULT_FORMAT_VERSION = 6

    /** Convenience: [write] into a fresh `ByteArrayOutputStream` wrapped
     *  in [GZIPOutputStream].  This matches the original `writeRootToGzipBytes`
     *  behaviour: callers expect a `.litematic`-shaped byte payload
     *  (gzipped NBT) when they ask for a `ByteArray`.  The streaming
     *  [write] overload emits raw NBT and lets the caller own the
     *  gzip wrapping. */
    fun write(source: Litematic): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gz -> write(source, gz) }
        return baos.toByteArray()
    }

    /**
     * Stream the Litematica payload to [out].  Caller is responsible for
     * gzip-wrapping [out] if needed — this writer emits raw NBT, the
     * [BlueprintConverter] façade owns the GZIP layer (and so does the
     * legacy [write] returning ByteArray via
     * [NbtWriter.writeRootToGzipBytes], which this method deliberately
     * does NOT wrap so the two paths stay composable).
     *
     * Use [NbtWriter.writeRootToGzipBytes] semantics on the caller's side:
     * ```
     * val gz = GZIPOutputStream(BufferedOutputStream(out, 64 * 1024))
     * gz.use { writer.write(source, it) }
     * ```
     */
    fun write(source: Litematic, out: OutputStream) {
        NbtWriter.writeRootHeader(out)
        // Top-level metadata tags — order matches buildRoot.
        NbtWriter.writeNamed("MinecraftDataVersion",
            NbtTag.IntTag(source.minecraftDataVersion ?: DEFAULT_DATA_VERSION), out)
        NbtWriter.writeNamed("Version",
            NbtTag.IntTag(source.version ?: DEFAULT_FORMAT_VERSION), out)
        NbtWriter.writeNamed("Name", NbtTag.StringTag(source.name), out)
        NbtWriter.writeNamed("Author", NbtTag.StringTag(source.author), out)
        NbtWriter.writeNamed("Description", NbtTag.StringTag(source.description), out)

        // Open the Regions compound without committing to a body yet —
        // we use writeCompoundOpen so the region entries can be streamed
        // directly into the body.  Caller closes the compound with
        // writeCompoundEnd.
        NbtWriter.writeCompoundOpen("Regions", out)
        for (region in source.regions) {
            writeRegion(region, out)
        }
        NbtWriter.writeCompoundEnd(out)

        // Close the root compound.
        NbtWriter.writeCompoundEnd(out)
    }

    private fun writeRegion(region: LitematicRegion, out: OutputStream) {
        // Open this region's compound keyed by region.name (NOT by field
        // name — that would put Position/Size/etc. at root level).
        NbtWriter.writeCompoundOpen(region.name, out)
        NbtWriter.writeNamed("Position", positionCompound(region), out)
        NbtWriter.writeNamed("Size", sizeCompound(region), out)
        // BlockStatePalette: small list of compound tags.  Building the
        // nested NbtTag list is fine — palette sizes are typically < 1000.
        val paletteList = NbtTag.ListTag(
            elementType = NbtTagType.Compound,
            value = region.palette.entries.map { blockStateToCompound(it) },
        )
        NbtWriter.writeNamed("BlockStatePalette", paletteList, out)

        // BlockStates: long-array tag.  Pre-compute longCount, hand-write
        // the tag header (id + name + int32 length), then stream-pack
        // exactly longCount big-endian longs via BlockStatePacker.  This
        // avoids allocating the 5 MB+ LongArray intermediate that the
        // in-memory path requires.
        val nbits = region.palette.bitsPerBlock
        val longCount = if (nbits == 0) 1 else {
            val total = (region.width.toLong() * region.height.toLong() * region.depth.toLong())
            ((total * nbits + 63) / 64).toInt().coerceAtLeast(1)
        }
        // Hand-emit the LongArray header bytes (NBT spec): 0x0C tag id +
        // UTF-8 name + int32 length.  Body follows.
        out.write(NbtTagType.LongArray.id.toInt())
        // Use NbtWriter internals (which accept a DataOutputStream the
        // caller owns — we wrap the underlying stream but never .use {}
        // the wrapper, so the underlying GZIPOutputStream isn't closed).
        val dos = DataOutputStream(out)
        dos.writeUTF("BlockStates")
        dos.writeInt(longCount)
        // Stream-pack the longs directly.  No LongArray intermediate.
        BlockStatePacker.pack(
            region.rawBlocks, nbits, region.width, region.height, region.depth, dos,
        )
        dos.flush()
        // Close this region's compound.
        NbtWriter.writeCompoundEnd(out)
    }

    private fun positionCompound(region: LitematicRegion): NbtTag.CompoundTag =
        NbtTag.CompoundTag(
            listOf(
                "x" to NbtTag.IntTag(region.position.x),
                "y" to NbtTag.IntTag(region.position.y),
                "z" to NbtTag.IntTag(region.position.z),
            ),
        )

    private fun sizeCompound(region: LitematicRegion): NbtTag.CompoundTag =
        NbtTag.CompoundTag(
            listOf(
                "x" to NbtTag.IntTag(region.width),
                "y" to NbtTag.IntTag(region.height),
                "z" to NbtTag.IntTag(region.depth),
            ),
        )

    private fun blockStateToCompound(state: BlockState): NbtTag.CompoundTag {
        val props = state.properties
        val propsCompound: NbtTag.CompoundTag? = if (props.isNullOrEmpty()) null
        else NbtTag.CompoundTag(props.entries.map { (k, v) -> k to NbtTag.StringTag(v) })
        val entries = buildList<Pair<String, NbtTag>> {
            add("Name" to NbtTag.StringTag(state.name))
            if (propsCompound != null) add("Properties" to propsCompound)
        }
        return NbtTag.CompoundTag(entries)
    }
}
