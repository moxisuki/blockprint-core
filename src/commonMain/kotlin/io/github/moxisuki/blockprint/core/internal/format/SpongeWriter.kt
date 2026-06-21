package io.github.moxisuki.blockprint.core.internal.format

import io.github.moxisuki.blockprint.core.Litematic
import io.github.moxisuki.blockprint.core.NbtTag
import io.github.moxisuki.blockprint.core.NbtTagType
import io.github.moxisuki.blockprint.core.NbtWriter
import io.github.moxisuki.blockprint.core.SchematicFormat
import io.github.moxisuki.blockprint.core.exceptions.LitematicException

/**
 * Encode a [Litematic] as a Sponge Schematic v2 NBT file (no gzip).
 *
 * Sponge stores block data as a varint-packed byte array in
 * x → y → z order (not y-major). It supports a single region per
 * file — this writer rejects multi-region input.
 */
internal object SpongeWriter {

    private const val DEFAULT_DATA_VERSION = 3465
    private const val SPONGE_VERSION = 2

    fun write(source: Litematic): ByteArray {
        if (source.regions.size > 1) {
            throw LitematicException(
                "Format ${SchematicFormat.Sponge.displayName} does not support " +
                    "multiple regions; source has ${source.regions.size}. " +
                    "Pick one with primaryRegion or split first.",
            )
        }
        val region = source.regions.single()
        val root = buildRoot(source, region)
        return NbtWriter.writeRootToBytes(root) // Sponge is raw NBT, no gzip
    }

    private fun buildRoot(source: Litematic, region: io.github.moxisuki.blockprint.core.LitematicRegion): NbtTag.CompoundTag {
        val w = region.width; val h = region.height; val d = region.depth
        val total = w * h * d

        // Block data: x → y → z traversal, varint-encoded palette indices.
        val blockData = java.io.ByteArrayOutputStream(total)
        for (y in 0 until h) for (z in 0 until d) for (x in 0 until w) {
            val v = region.rawBlocks[region.rawIndex(x, y, z)]
            writeVarInt(blockData, v)
        }

        // Palette: stringified int key → BlockState compound. Sponge's
        // palette is conceptually a map; we serialize as a compound.
        val paletteEntries = region.palette.entries.mapIndexed { i, state ->
            i.toString() to blockStateCompound(state)
        }

        val enclosingSize = NbtTag.CompoundTag(
            listOf(
                "x" to NbtTag.IntTag(w),
                "y" to NbtTag.IntTag(h),
                "z" to NbtTag.IntTag(d),
            ),
        )
        val offset = NbtTag.CompoundTag(
            listOf(
                "x" to NbtTag.IntTag(region.position.x),
                "y" to NbtTag.IntTag(region.position.y),
                "z" to NbtTag.IntTag(region.position.z),
            ),
        )
        val metadata = NbtTag.CompoundTag(
            listOf(
                "Name" to NbtTag.StringTag(source.name),
                "Author" to NbtTag.StringTag(source.author),
                "Description" to NbtTag.StringTag(source.description),
                "EnclosingSize" to enclosingSize,
            ),
        )

        return NbtTag.CompoundTag(
            listOf(
                "Version" to NbtTag.IntTag(SPONGE_VERSION),
                "DataVersion" to NbtTag.IntTag(source.minecraftDataVersion ?: DEFAULT_DATA_VERSION),
                "Width" to NbtTag.IntTag(w),
                "Height" to NbtTag.IntTag(h),
                "Length" to NbtTag.IntTag(d),
                "Offset" to offset,
                "Palette" to NbtTag.CompoundTag(paletteEntries),
                "PaletteMax" to NbtTag.IntTag(region.palette.size),
                "BlockData" to NbtTag.ByteArrayTag(blockData.toByteArray()),
                "TileEntities" to NbtTag.ListTag(NbtTagType.Compound, emptyList()),
                "Entities" to NbtTag.ListTag(NbtTagType.Compound, emptyList()),
                "Metadata" to metadata,
            ),
        )
    }

    private fun blockStateCompound(state: io.github.moxisuki.blockprint.core.BlockState): NbtTag.CompoundTag {
        val props = state.properties
        val propsCompound: NbtTag.CompoundTag = if (props.isNullOrEmpty()) NbtTag.CompoundTag(emptyList())
        else NbtTag.CompoundTag(props.entries.map { (k, v) -> k to NbtTag.StringTag(v) })
        return NbtTag.CompoundTag(
            listOf("Name" to NbtTag.StringTag(state.name), "Properties" to propsCompound),
        )
    }

    /**
     * Standard protobuf-style varint: 7 bits per byte, MSB set means
     * more bytes follow. Used by Sponge for the BlockData stream.
     */
    private fun writeVarInt(out: java.io.OutputStream, value: Int) {
        var v = value
        while (v < 0 || v >= 0x80) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v and 0x7F)
    }
}
