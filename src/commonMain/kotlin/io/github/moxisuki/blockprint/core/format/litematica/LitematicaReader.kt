package io.github.moxisuki.blockprint.core.format.litematica

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.NbtTag
import io.github.moxisuki.blockprint.core.NbtTagType
import io.github.moxisuki.blockprint.core.Position
import io.github.moxisuki.blockprint.core.SchematicFormat
import io.github.moxisuki.blockprint.core.exceptions.BlockPrintException
import io.github.moxisuki.blockprint.core.internal.BlockStatePacker
import io.github.moxisuki.blockprint.core.internal.NbtAccessors
import io.github.moxisuki.blockprint.core.model.BlockPrintDocument
import io.github.moxisuki.blockprint.core.model.BlockPrintRegion
import io.github.moxisuki.blockprint.core.model.BlockPrintSummary

/**
 * Parse a Litematica-format schematic root into [BlockPrintDocument].
 *
 * Also detects Litematica-with-Sponge-compat files (root has both
 * `Regions` and `Metadata/EnclosingSize`): these remain Litematica.
 */
internal object LitematicaReader {
    fun parse(root: NbtTag.CompoundTag): BlockPrintDocument {
        val regionsTag = root.get("Regions")
            ?: throw BlockPrintException("Litematic root is missing 'Regions' compound")
        if (regionsTag !is NbtTag.CompoundTag) {
            throw BlockPrintException("'Regions' must be a compound, got ${regionsTag::class.simpleName}")
        }
        val metadata = (root.get("Metadata") as? NbtTag.CompoundTag)
        val isSponge = metadata?.contains("EnclosingSize") == true
        val regions = regionsTag.entries().map { (regionName, regionTag) ->
            parseRegion(
                name = regionName,
                region = regionTag as? NbtTag.CompoundTag
                    ?: throw BlockPrintException("Region '$regionName' must be a compound"),
                isSponge = isSponge,
                metadata = metadata,
            )
        }
        return BlockPrintDocument(
            minecraftDataVersion = NbtAccessors.readIntOrNull(root, "MinecraftDataVersion"),
            version = NbtAccessors.readIntOrNull(root, "Version"),
            name = metadata?.let { NbtAccessors.readStringOrEmpty(it, "Name") }
                ?.ifEmpty { NbtAccessors.readStringOrEmpty(root, "Name") }
                ?: NbtAccessors.readStringOrEmpty(root, "Name"),
            author = metadata?.let { NbtAccessors.readStringOrEmpty(it, "Author") }
                ?.ifEmpty { NbtAccessors.readStringOrEmpty(root, "Author") }
                ?: NbtAccessors.readStringOrEmpty(root, "Author"),
            description = metadata?.let { NbtAccessors.readStringOrEmpty(it, "Description") }
                ?.ifEmpty { NbtAccessors.readStringOrEmpty(root, "Description") }
                ?: NbtAccessors.readStringOrEmpty(root, "Description"),
            regions = regions,
            format = if (isSponge) SchematicFormat.Sponge else SchematicFormat.Litematica,
        )
    }

    /** Peek: read only root metadata, skip Regions subtree entirely. */
    fun readHeader(root: NbtTag.CompoundTag): BlockPrintSummary =
        BlockPrintSummary(
            format = SchematicFormat.Litematica,
            name = NbtAccessors.readStringOrEmpty(root, "Name"),
            author = NbtAccessors.readStringOrEmpty(root, "Author"),
            description = NbtAccessors.readStringOrEmpty(root, "Description"),
            version = NbtAccessors.readIntOrNull(root, "Version"),
            minecraftDataVersion = NbtAccessors.readIntOrNull(root, "MinecraftDataVersion"),
        )

    private fun parseRegion(
        name: String, region: NbtTag.CompoundTag, isSponge: Boolean, metadata: NbtTag.CompoundTag?,
    ): BlockPrintRegion {
        val (rawWidth, rawHeight, rawDepth) = if (isSponge) {
            val enclosing = metadata?.get("EnclosingSize") as? NbtTag.CompoundTag
                ?: throw BlockPrintException("Sponge schematic: Metadata/EnclosingSize missing")
            NbtAccessors.readInt3(enclosing, "EnclosingSize")
        } else {
            val sizeTag = region.require("Size") as? NbtTag.CompoundTag
                ?: throw BlockPrintException("Region '$name' missing Size compound")
            NbtAccessors.readInt3(sizeTag, "Size")
        }
        val width = kotlin.math.abs(rawWidth)
        val height = kotlin.math.abs(rawHeight)
        val depth = kotlin.math.abs(rawDepth)
        require(width > 0 && height > 0 && depth > 0) {
            "Region '$name' has invalid dimension: ${width}x${height}x${depth}"
        }

        val position = region.get("Position")?.let {
            if (it is NbtTag.CompoundTag) {
                val (x, y, z) = NbtAccessors.readInt3(it, "Position")
                Position(x, y, z)
            } else null
        } ?: Position.ZERO

        val paletteTag = region.require("BlockStatePalette") as? NbtTag.ListTag
            ?: throw BlockPrintException("Region '$name' missing BlockStatePalette list")
        require(paletteTag.elementType == NbtTagType.Compound) {
            "Region '$name' BlockStatePalette element type must be COMPOUND, got ${paletteTag.elementType}"
        }
        val palette = BlockPalette(paletteTag.value.map { parseBlockState(it as NbtTag.CompoundTag) })

        val blockStatesTag = region.require("BlockStates") as? NbtTag.LongArrayTag
            ?: throw BlockPrintException("Region '$name' missing BlockStates long array")
        val nbits = palette.bitsPerBlock
        BlockStatePacker.validateLength(blockStatesTag.value, nbits, width, height, depth)
        val blocks = BlockStatePacker.unpack(blockStatesTag.value, nbits, width, height, depth)

        return BlockPrintRegion(name, width, height, depth, position, palette, blocks)
    }

    private fun parseBlockState(tag: NbtTag.CompoundTag): BlockState {
        val name = tag.get("Name") as? NbtTag.StringTag
            ?: throw BlockPrintException("Block state missing 'Name' string")
        val props = tag.get("Properties") as? NbtTag.CompoundTag
        val properties: Map<String, String>? = if (props == null) null else {
            if (props.value.isEmpty()) emptyMap() else props.value.associate { (k, v) ->
                val str = v as? NbtTag.StringTag
                    ?: throw BlockPrintException("Block state property '$k' must be a string")
                k to str.value
            }
        }
        return BlockState(name.value, properties)
    }
}