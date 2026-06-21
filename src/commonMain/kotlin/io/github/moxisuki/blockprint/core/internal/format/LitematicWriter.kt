package io.github.moxisuki.blockprint.core.internal.format

import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.Litematic
import io.github.moxisuki.blockprint.core.LitematicRegion
import io.github.moxisuki.blockprint.core.NbtTag
import io.github.moxisuki.blockprint.core.NbtWriter
import io.github.moxisuki.blockprint.core.internal.BlockStatePacker

/**
 * Encode a [Litematic] as a `.litematic` (gzipped NBT) byte payload.
 *
 * Output schema mirrors what [io.github.moxisuki.blockprint.core.internal.LitematicParser]
 * reads, so a `write → read` round-trip is structurally stable.
 */
internal object LitematicWriter {

    /** Default `MinecraftDataVersion` to write when the input is null. 3465 ≈ 1.21. */
    private const val DEFAULT_DATA_VERSION = 3465

    /** Default Litematica file format version. */
    private const val DEFAULT_FORMAT_VERSION = 6

    fun write(source: Litematic): ByteArray {
        val root = buildRoot(source)
        return NbtWriter.writeRootToGzipBytes(root)
    }

    private fun buildRoot(source: Litematic): NbtTag.CompoundTag {
        val regions = source.regions.map { region -> region.name to buildRegion(region) }
        val entries = buildList<Pair<String, NbtTag>> {
            add("MinecraftDataVersion" to NbtTag.IntTag(source.minecraftDataVersion ?: DEFAULT_DATA_VERSION))
            add("Version" to NbtTag.IntTag(source.version ?: DEFAULT_FORMAT_VERSION))
            add("Name" to NbtTag.StringTag(source.name))
            add("Author" to NbtTag.StringTag(source.author))
            add("Description" to NbtTag.StringTag(source.description))
            add("Regions" to NbtTag.CompoundTag(regions))
        }
        return NbtTag.CompoundTag(entries)
    }

    private fun buildRegion(region: LitematicRegion): NbtTag.CompoundTag {
        val positionCompound = NbtTag.CompoundTag(
            listOf(
                "x" to NbtTag.IntTag(region.position.x),
                "y" to NbtTag.IntTag(region.position.y),
                "z" to NbtTag.IntTag(region.position.z),
            ),
        )
        val sizeCompound = NbtTag.CompoundTag(
            listOf(
                "x" to NbtTag.IntTag(region.width),
                "y" to NbtTag.IntTag(region.height),
                "z" to NbtTag.IntTag(region.depth),
            ),
        )
        val paletteList = NbtTag.ListTag(
            elementType = io.github.moxisuki.blockprint.core.NbtTagType.Compound,
            value = region.palette.entries.map { blockStateToCompound(it) },
        )
        val nbits = region.palette.bitsPerBlock
        val packed = BlockStatePacker.pack(region.rawBlocks, nbits, region.width, region.height, region.depth)
        val blockStates = NbtTag.LongArrayTag(packed)
        return NbtTag.CompoundTag(
            listOf(
                "Position" to positionCompound,
                "Size" to sizeCompound,
                "BlockStatePalette" to paletteList,
                "BlockStates" to blockStates,
            ),
        )
    }

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
