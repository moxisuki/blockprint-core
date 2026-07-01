package io.github.moxisuki.blockprint.core.format.structure

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.NbtTag
import io.github.moxisuki.blockprint.core.NbtTagType
import io.github.moxisuki.blockprint.core.Position
import io.github.moxisuki.blockprint.core.SchematicFormat
import io.github.moxisuki.blockprint.core.exceptions.BlockPrintException
import io.github.moxisuki.blockprint.core.internal.NbtAccessors
import io.github.moxisuki.blockprint.core.model.BlockPrintDocument
import io.github.moxisuki.blockprint.core.model.BlockPrintRegion
import io.github.moxisuki.blockprint.core.model.BlockPrintSummary

internal object StructureReader {
    fun parse(root: NbtTag.CompoundTag): BlockPrintDocument {
        val (width, height, depth) = readSizeLenient(root)
        val rawPalette = (root.get("palette") as? NbtTag.ListTag)
            ?: throw BlockPrintException("Structure file: 'palette' must be a ListTag")
        require(rawPalette.elementType == NbtTagType.Compound) {
            "Structure palette element type must be Compound, got ${rawPalette.elementType}"
        }
        val rawEntries = rawPalette.value.map { parseBlockState(it as NbtTag.CompoundTag) }
        val palette = BlockPalette(listOf(BlockState("minecraft:air", null)) + rawEntries)

        val blocksList = (root.get("blocks") as? NbtTag.ListTag) ?: (root.get("Blocks") as? NbtTag.ListTag)
            ?: throw BlockPrintException("Structure file: 'blocks' must be a ListTag")
        require(blocksList.elementType == NbtTagType.Compound) {
            "Structure blocks element type must be Compound, got ${blocksList.elementType}"
        }

        val dense = IntArray(width * height * depth)
        for (element in blocksList.value) {
            val entry = element as NbtTag.CompoundTag
            val posList = entry.get("pos") as? NbtTag.ListTag
                ?: throw BlockPrintException("Structure block entry missing 'pos' list")
            require(posList.value.size == 3) { "Structure block pos must have 3 elements, got ${posList.value.size}" }
            val x = (posList.value[0] as NbtTag.IntTag).value
            val y = (posList.value[1] as NbtTag.IntTag).value
            val z = (posList.value[2] as NbtTag.IntTag).value
            val state = (entry.get("state") as? NbtTag.IntTag)?.value
                ?: throw BlockPrintException("Structure block entry missing 'state'")
            val idx = y * (width * depth) + z * width + x
            require(idx in dense.indices) { "Structure block pos [$x,$y,$z] out of bounds ${width}x${height}x${depth}" }
            dense[idx] = state + 1
        }

        val region = BlockPrintRegion("Structure", width, height, depth, Position.ZERO, palette, dense)
        return BlockPrintDocument(
            minecraftDataVersion = NbtAccessors.readIntOrNull(root, "DataVersion"),
            version = null,
            name = NbtAccessors.readStringOrEmpty(root, "name").ifEmpty { NbtAccessors.readStringOrEmpty(root, "Name") },
            author = NbtAccessors.readStringOrEmpty(root, "author").ifEmpty { NbtAccessors.readStringOrEmpty(root, "Author") },
            description = NbtAccessors.readStringOrEmpty(root, "Description"),
            regions = listOf(region),
            format = SchematicFormat.Structure,
        )
    }

    fun readHeader(root: NbtTag.CompoundTag): BlockPrintSummary =
        BlockPrintSummary(
            format = SchematicFormat.Structure,
            name = NbtAccessors.readStringOrEmpty(root, "name").ifEmpty { NbtAccessors.readStringOrEmpty(root, "Name") },
            author = NbtAccessors.readStringOrEmpty(root, "author").ifEmpty { NbtAccessors.readStringOrEmpty(root, "Author") },
            description = NbtAccessors.readStringOrEmpty(root, "Description"),
            version = null,
            minecraftDataVersion = NbtAccessors.readIntOrNull(root, "DataVersion"),
        )

    private fun readSizeLenient(root: NbtTag.CompoundTag): IntArray {
        (root.get("Size") as? NbtTag.CompoundTag)?.let { return NbtAccessors.readInt3(it, "Size") }
        (root.get("size") as? NbtTag.ListTag)?.let { sizeList ->
            require(sizeList.elementType == NbtTagType.Int && sizeList.value.size == 3) {
                "Root 'size' list must be 3 ints, got ${sizeList.elementType} of ${sizeList.value.size}"
            }
            val ints = sizeList.value.map { (it as NbtTag.IntTag).value }
            return intArrayOf(ints[0], ints[1], ints[2])
        }
        throw BlockPrintException("Structure file: missing 'size'")
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
