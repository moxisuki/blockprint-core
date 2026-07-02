package io.github.moxisuki.blockprint.core.format.sponge

import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.NbtTag
import io.github.moxisuki.blockprint.core.NbtTagType
import io.github.moxisuki.blockprint.core.NbtWriter
import io.github.moxisuki.blockprint.core.SchematicFormat
import io.github.moxisuki.blockprint.core.exceptions.BlockPrintException
import io.github.moxisuki.blockprint.core.model.BlockPrintDocument
import io.github.moxisuki.blockprint.core.model.BlockPrintRegion
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

/**
 * Encode a [BlockPrintDocument] as a Sponge Schematic **v3** (WorldEdit 7.3+)
 * NBT file. Per the Sponge schematic spec:
 *
 *   - Root named compound `"Schematic"`.
 *   - `Version` = 3.
 *   - `Width` / `Height` / `Length` are **Short**s (note: "Length", not
 *     "Depth"; the in-memory domain model uses `depth` but the wire
 *     field is still `Length`).
 *   - `Offset` is an **IntArray(3)** of (x, y, z).
 *   - Block data lives under a single `Blocks` sub-compound:
 *       - `Palette`      : Compound of paletteId → BlockStateCompound
 *       - `Data`         : ByteArray of varint palette indices (x→y→z)
 *       - `BlockEntities`: List<Compound> (always empty for us)
 *   - `Metadata` carries `Date` (Long) and a `WorldEdit` sub-compound
 *     (`Version`, `EditingPlatform`, `Origin` IntArray(3), `Platforms`).
 *
 * The write side only emits v3 going forward.
 */
internal object SpongeWriter {

    private const val DEFAULT_DATA_VERSION = 3465
    private const val SPONGE_VERSION = 3
    private const val WORLD_EDIT_VERSION = "blockprint-core"
    private const val EDITING_PLATFORM = "blockprint:blockprint-core"

    fun write(source: BlockPrintDocument): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gz -> write(source, gz) }
        return baos.toByteArray()
    }

    /**
     * Stream the Sponge v3 payload to [out]. The [BlockPrintConverter]
     * façade owns the GZIP layer and wraps [out] in a [GZIPOutputStream]
     * before calling this (mirroring the Litematica/Structure write paths),
     * so [out] here is already gzip-wrapped. WorldEdit 7.x exports gzipped
     * `.schem` files; wrapping here ensures our output is compatible.
     */
    fun write(source: BlockPrintDocument, out: OutputStream) {
        if (source.regions.size > 1) {
            throw BlockPrintException(
                "Format ${SchematicFormat.Sponge.displayName} does not support " +
                    "multiple regions; source has ${source.regions.size}. " +
                    "Pick one with primaryRegion or split first.",
            )
        }
        val region = source.regions.single()
        val root = buildRoot(source, region)
        NbtWriter.writeRoot(root, out)
    }

    /**
     * Per the Sponge v3 spec the root compound (name `""`) has exactly one
     * child key `"Schematic"` whose value is the actual v3 payload
     * CompoundTag. WorldEdit reads back files of this shape, so we must
     * emit it.
     */
    private fun buildRoot(source: BlockPrintDocument, region: BlockPrintRegion): NbtTag.CompoundTag {
        val inner = buildInner(source, region)
        return NbtTag.CompoundTag(listOf("Schematic" to inner))
    }

    private fun buildInner(source: BlockPrintDocument, region: BlockPrintRegion): NbtTag.CompoundTag {
        val w = region.width; val h = region.height; val d = region.depth
        val total = w * h * d
        require(w in 0..Short.MAX_VALUE.toInt() && h in 0..Short.MAX_VALUE.toInt() && d in 0..Short.MAX_VALUE.toInt()) {
            "Sponge v3: dimension must fit in a Short (got ${w}x${h}x${d})"
        }

        // v3 palette: blockStateName (with [k=v,k=v] properties) →
        // IntTag(paletteId). The Sponge v3 spec uses the canonical
        // resource location with comma-separated property pairs in
        // square brackets as the palette key (e.g. "minecraft:grass_block[snowy=false]"),
        // not a BlockStateCompound — so we must dedupe by the full
        // canonical string (BlockState.toString), then remap in-memory
        // palette indices to the compact 0..N-1 range so the varint
        // stream stays consistent with the written palette size.
        val oldToNew = IntArray(region.palette.size) { -1 }
        val newPalette = mutableListOf<String>()
        val keyToNewIndex = mutableMapOf<String, Int>()
        for ((i, state) in region.palette.entries.withIndex()) {
            val key = state.toString()
            val existing = keyToNewIndex[key]
            if (existing != null) {
                oldToNew[i] = existing
            } else {
                val newIndex = newPalette.size
                keyToNewIndex[key] = newIndex
                newPalette += key
                oldToNew[i] = newIndex
            }
        }

        // Block data: x → y → z traversal, varint-encoded palette indices.
        val blockData = java.io.ByteArrayOutputStream(total)
        for (y in 0 until h) for (z in 0 until d) for (x in 0 until w) {
            val oldIdx = region.rawBlocks[region.rawIndex(x, y, z)]
            writeVarInt(blockData, oldToNew[oldIdx])
        }

        // v3 palette: blockStateName → IntTag(paletteId) (compact 0..N-1).
        val paletteEntries = newPalette.map { key ->
            key to NbtTag.IntTag(keyToNewIndex[key]!!)
        }

        val blocksCompound = NbtTag.CompoundTag(
            listOf(
                "Palette" to NbtTag.CompoundTag(paletteEntries),
                "Data" to NbtTag.ByteArrayTag(blockData.toByteArray()),
                "BlockEntities" to NbtTag.ListTag(NbtTagType.Compound, emptyList()),
            ),
        )

        val offsetArray = NbtTag.IntArrayTag(
            intArrayOf(region.position.x, region.position.y, region.position.z),
        )

        val worldEditCompound = NbtTag.CompoundTag(
            listOf(
                "Version" to NbtTag.StringTag(WORLD_EDIT_VERSION),
                "EditingPlatform" to NbtTag.StringTag(EDITING_PLATFORM),
                "Origin" to NbtTag.IntArrayTag(intArrayOf(0, 0, 0)),
                "Platforms" to NbtTag.CompoundTag(
                    listOf(
                        EDITING_PLATFORM to NbtTag.CompoundTag(
                            listOf("Name" to NbtTag.StringTag(WORLD_EDIT_VERSION)),
                        ),
                    ),
                ),
            ),
        )

        val metadata = NbtTag.CompoundTag(
            listOf(
                "Date" to NbtTag.LongTag(0L),
                "WorldEdit" to worldEditCompound,
            ),
        )

        return NbtTag.CompoundTag(
            listOf(
                "Version" to NbtTag.IntTag(SPONGE_VERSION),
                "DataVersion" to NbtTag.IntTag(source.minecraftDataVersion ?: DEFAULT_DATA_VERSION),
                "Width" to NbtTag.ShortTag(w.toShort()),
                "Height" to NbtTag.ShortTag(h.toShort()),
                "Length" to NbtTag.ShortTag(d.toShort()),
                "Offset" to offsetArray,
                "Metadata" to metadata,
                "Blocks" to blocksCompound,
            ),
        )
    }

    private fun blockStateCompound(state: BlockState): NbtTag.CompoundTag {
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