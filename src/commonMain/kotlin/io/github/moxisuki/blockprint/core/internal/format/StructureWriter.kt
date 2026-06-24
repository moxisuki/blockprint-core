package io.github.moxisuki.blockprint.core.internal.format

import io.github.moxisuki.blockprint.core.Litematic
import io.github.moxisuki.blockprint.core.NbtTag
import io.github.moxisuki.blockprint.core.NbtTagType
import io.github.moxisuki.blockprint.core.NbtWriter
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

/**
 * Encode a [Litematic] as a vanilla Minecraft structure file
 * (`/structure save` style, gzipped NBT).
 *
 * Vanilla structure files store blocks **sparsely** (only non-air
 * cells with explicit positions) and use a palette that **does not
 * include air** (index 0 = first non-air block). The in-memory
 * `Litematic` model inverts both invariants (air at palette index 0,
 * dense block array), so this writer:
 *   1. Drops the air entry when building the output palette.
 *   2. Shifts every in-memory palette index down by 1 for the
 *      `state` field in the sparse `blocks` list.
 */
internal object StructureWriter {

    private const val DEFAULT_DATA_VERSION = 3465

    fun write(source: Litematic): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gz -> write(source, gz) }
        return baos.toByteArray()
    }

    /** Stream the Structure payload to [out].  Caller is responsible for
     *  wrapping in [java.util.zip.GZIPOutputStream] (Structure files are
     *  gzipped per Minecraft's structure-block spec). */
    fun write(source: Litematic, out: OutputStream) {
        val region = source.regions.firstOrNull()
            ?: throw IllegalArgumentException("StructureWriter: source has no regions")
        val root = buildRoot(source, region)
        NbtWriter.writeRoot(root, out)
    }

    private fun buildRoot(source: Litematic, region: io.github.moxisuki.blockprint.core.LitematicRegion): NbtTag.CompoundTag {
        // Output palette = in-memory palette minus index 0 (air).
        val outPalette = region.palette.entries.drop(1)

        // Iterate dense blocks in y-major order. For each non-air cell, emit
        // { pos: [x, y, z], state: inMemoryIndex - 1 }.
        val blocks = mutableListOf<NbtTag.CompoundTag>()
        val w = region.width; val h = region.height; val d = region.depth
        for (y in 0 until h) for (z in 0 until d) for (x in 0 until w) {
            val idx = region.rawIndex(x, y, z)
            val v = region.rawBlocks[idx]
            if (v != 0) {
                blocks.add(
                    NbtTag.CompoundTag(
                        listOf(
                            "pos" to NbtTag.ListTag(
                                elementType = NbtTagType.Int,
                                value = listOf(
                                    NbtTag.IntTag(x + region.position.x),
                                    NbtTag.IntTag(y + region.position.y),
                                    NbtTag.IntTag(z + region.position.z),
                                ),
                            ),
                            "state" to NbtTag.IntTag(v - 1),
                        ),
                    ),
                )
            }
        }

        val sizeList = NbtTag.ListTag(
            elementType = NbtTagType.Int,
            value = listOf(
                NbtTag.IntTag(w),
                NbtTag.IntTag(h),
                NbtTag.IntTag(d),
            ),
        )
        val paletteList = NbtTag.ListTag(
            elementType = NbtTagType.Compound,
            value = outPalette.map { state ->
                NbtTag.CompoundTag(
                    listOf(
                        "Name" to NbtTag.StringTag(state.name),
                        "Properties" to (
                            if (state.properties.isNullOrEmpty()) NbtTag.CompoundTag(emptyList())
                            else NbtTag.CompoundTag(state.properties.entries.map { (k, v) -> k to NbtTag.StringTag(v) })
                        ),
                    ),
                )
            },
        )
        val blocksList = NbtTag.ListTag(
            elementType = NbtTagType.Compound,
            value = blocks,
        )

        return NbtTag.CompoundTag(
            listOf(
                "DataVersion" to NbtTag.IntTag(source.minecraftDataVersion ?: DEFAULT_DATA_VERSION),
                "size" to sizeList,
                "palette" to paletteList,
                "blocks" to blocksList,
            ),
        )
    }
}
