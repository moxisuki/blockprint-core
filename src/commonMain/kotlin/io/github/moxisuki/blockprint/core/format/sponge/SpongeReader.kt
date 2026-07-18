package io.github.moxisuki.blockprint.core.format.sponge

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.NbtTag
import io.github.moxisuki.blockprint.core.Position
import io.github.moxisuki.blockprint.core.SchematicFormat
import io.github.moxisuki.blockprint.core.exceptions.BlockPrintException
import io.github.moxisuki.blockprint.core.internal.NbtAccessors
import io.github.moxisuki.blockprint.core.internal.VarInt
import io.github.moxisuki.blockprint.core.model.BlockPrintDocument
import io.github.moxisuki.blockprint.core.model.BlockPrintRegion
import io.github.moxisuki.blockprint.core.model.BlockPrintSummary
import io.github.moxisuki.blockprint.core.model.checkedVolume

internal object SpongeReader {
    fun parse(root: NbtTag.CompoundTag): BlockPrintDocument {
        val inner = root.get("Schematic") as? NbtTag.CompoundTag
        if (inner != null && (inner.get("Version") as? NbtTag.IntTag)?.value == 3 && inner.contains("Blocks")) {
            return parseV3(inner)
        }
        return parseV2(root)
    }

    fun readHeader(root: NbtTag.CompoundTag): BlockPrintSummary {
        val inner = root.get("Schematic") as? NbtTag.CompoundTag
        return if (inner != null && (inner.get("Version") as? NbtTag.IntTag)?.value == 3) {
            val meta = inner.get("Metadata") as? NbtTag.CompoundTag
            val worldEdit = meta?.get("WorldEdit") as? NbtTag.CompoundTag
            BlockPrintSummary(
                format = SchematicFormat.Sponge,
                name = NbtAccessors.readStringOrEmpty(worldEdit, "Name")
                    .ifEmpty { NbtAccessors.readStringOrEmpty(meta, "Name") }
                    .ifEmpty { NbtAccessors.readStringOrEmpty(inner, "Name") },
                author = NbtAccessors.readStringOrEmpty(worldEdit, "Author")
                    .ifEmpty { NbtAccessors.readStringOrEmpty(meta, "Author") }
                    .ifEmpty { NbtAccessors.readStringOrEmpty(inner, "Author") },
                description = NbtAccessors.readStringOrEmpty(meta, "Description")
                    .ifEmpty { NbtAccessors.readStringOrEmpty(inner, "Description") },
                version = NbtAccessors.readIntOrNull(inner, "Version"),
                minecraftDataVersion = NbtAccessors.readIntOrNull(inner, "DataVersion"),
            )
        } else {
            val meta = root.get("Metadata") as? NbtTag.CompoundTag
            BlockPrintSummary(
                format = SchematicFormat.Sponge,
                name = NbtAccessors.readStringOrEmpty(meta, "Name")
                    .ifEmpty { NbtAccessors.readStringOrEmpty(root, "Name") },
                author = NbtAccessors.readStringOrEmpty(meta, "Author")
                    .ifEmpty { NbtAccessors.readStringOrEmpty(root, "Author") },
                description = NbtAccessors.readStringOrEmpty(root, "Description"),
                version = NbtAccessors.readIntOrNull(root, "Version"),
                minecraftDataVersion = NbtAccessors.readIntOrNull(root, "DataVersion"),
            )
        }
    }

    private fun parseV2(root: NbtTag.CompoundTag): BlockPrintDocument {
        val width = readDimension(root, "Width")
        val height = readDimension(root, "Height")
        val depth = readDimension(root, "Length")
        require(width > 0 && height > 0 && depth > 0) { "Sponge: invalid dimension ${width}x${height}x${depth}" }

        val paletteTag = root.get("Palette") as? NbtTag.CompoundTag
            ?: throw BlockPrintException("Sponge: 'Palette' must be a compound")
        val paletteEntries = paletteTag.entries().map { (key, value) ->
            when (value) {
                // Canonical Sponge v1/v2: block-state string -> palette id.
                is NbtTag.IntTag -> value.value to NbtAccessors.parseSpongeV3Key(key)
                // Backward compatibility with the library's historical shape:
                // numeric id string -> vanilla-style block-state compound.
                is NbtTag.CompoundTag -> {
                    val id = key.toIntOrNull()
                        ?: throw BlockPrintException("Sponge: legacy palette key '$key' is not an int string")
                    id to parseBlockState(value)
                }
                else -> throw BlockPrintException("Sponge: palette value for '$key' must be IntTag or CompoundTag")
            }
        }.toMutableList()
        paletteEntries.sortBy { it.first }
        require(paletteEntries.map { it.first } == paletteEntries.indices.toList()) {
            "Sponge: palette ids must be contiguous starting at 0"
        }
        val palette = BlockPalette(paletteEntries.map { it.second })

        val blockData = (root.get("BlockData") as? NbtTag.ByteArrayTag)
            ?: throw BlockPrintException("Sponge: missing BlockData byte array")
        val total = checkedVolume(width, height, depth, "Sponge schematic")
        val flat = IntArray(total)
        val src = blockData.value
        var pos = 0
        var k = 0
        for (y in 0 until height) for (z in 0 until depth) for (x in 0 until width) {
            if (k >= total) throw BlockPrintException("Sponge: BlockData underrun (need $total varints, got fewer)")
            val v = VarInt.decode(src, pos)
            pos = v.nextPos
            flat[y * (width * depth) + z * width + x] = v.value
            k++
        }
        if (pos != src.size) throw BlockPrintException("Sponge: BlockData has ${src.size - pos} trailing bytes")

        val position = when (val offset = root.get("Offset")) {
            is NbtTag.IntArrayTag -> {
                require(offset.value.size >= 3) { "Sponge: Offset must have at least 3 ints" }
                Position(offset.value[0], offset.value[1], offset.value[2])
            }
            is NbtTag.CompoundTag -> {
                val (x, y, z) = NbtAccessors.readInt3(offset, "Offset")
                Position(x, y, z)
            }
            else -> Position.ZERO
        }

        val meta = root.get("Metadata") as? NbtTag.CompoundTag
        val region = BlockPrintRegion("Sponge", width, height, depth, position, palette, flat)
        return BlockPrintDocument(
            minecraftDataVersion = NbtAccessors.readIntOrNull(root, "DataVersion"),
            version = NbtAccessors.readIntOrNull(root, "Version"),
            name = NbtAccessors.readStringOrEmpty(meta, "Name").ifEmpty { NbtAccessors.readStringOrEmpty(root, "Name") },
            author = NbtAccessors.readStringOrEmpty(meta, "Author").ifEmpty { NbtAccessors.readStringOrEmpty(root, "Author") },
            description = NbtAccessors.readStringOrEmpty(meta, "Description")
                .ifEmpty { NbtAccessors.readStringOrEmpty(root, "Description") },
            regions = listOf(region),
            format = SchematicFormat.Sponge,
        )
    }

    private fun parseV3(inner: NbtTag.CompoundTag): BlockPrintDocument {
        val width = (inner.get("Width") as? NbtTag.ShortTag)?.value?.toInt()
            ?: throw BlockPrintException("Sponge v3: missing short 'Width'")
        val height = (inner.get("Height") as? NbtTag.ShortTag)?.value?.toInt()
            ?: throw BlockPrintException("Sponge v3: missing short 'Height'")
        val depth = (inner.get("Length") as? NbtTag.ShortTag)?.value?.toInt()
            ?: throw BlockPrintException("Sponge v3: missing short 'Length'")
        require(width > 0 && height > 0 && depth > 0) { "Sponge v3: invalid dimension ${width}x${height}x${depth}" }

        val blocksCompound = inner.get("Blocks") as? NbtTag.CompoundTag
            ?: throw BlockPrintException("Sponge v3: 'Blocks' must be a compound")
        val paletteTag = blocksCompound.get("Palette") as? NbtTag.CompoundTag
            ?: throw BlockPrintException("Sponge v3: Blocks/Palette must be a compound")
        val nameToId = mutableMapOf<String, Int>()
        for ((key, v) in paletteTag.entries()) {
            val id = (v as? NbtTag.IntTag)?.value
                ?: throw BlockPrintException("Sponge v3: Palette value for '$key' must be IntTag")
            nameToId[key] = id
        }
        val palette = BlockPalette(nameToId.entries.sortedBy { it.value }.map { NbtAccessors.parseSpongeV3Key(it.key) })

        val blockData = (blocksCompound.get("Data") as? NbtTag.ByteArrayTag)
            ?: throw BlockPrintException("Sponge v3: Blocks/Data missing or not a byte array")
        val total = checkedVolume(width, height, depth, "Sponge v3 schematic")
        val flat = IntArray(total)
        val src = blockData.value
        var pos = 0
        var k = 0
        for (y in 0 until height) for (z in 0 until depth) for (x in 0 until width) {
            if (k >= total) throw BlockPrintException("Sponge v3: BlockData underrun (need $total varints, got fewer)")
            val v = VarInt.decode(src, pos)
            pos = v.nextPos
            flat[y * (width * depth) + z * width + x] = v.value
            k++
        }
        if (k < total) throw BlockPrintException("Sponge v3: BlockData has trailing bytes (decoded $k of $total)")

        val position = (inner.get("Offset") as? NbtTag.IntArrayTag)?.let { arr ->
            require(arr.value.size >= 3) { "Sponge v3: Offset must have at least 3 ints, got ${arr.value.size}" }
            Position(arr.value[0], arr.value[1], arr.value[2])
        } ?: Position.ZERO

        val region = BlockPrintRegion("Sponge", width, height, depth, position, palette, flat)
        return BlockPrintDocument(
            minecraftDataVersion = NbtAccessors.readIntOrNull(inner, "DataVersion"),
            version = NbtAccessors.readIntOrNull(inner, "Version"),
            name = NbtAccessors.readStringOrEmpty(inner, "Name"),
            author = NbtAccessors.readStringOrEmpty(inner, "Author"),
            description = NbtAccessors.readStringOrEmpty(inner, "Description"),
            regions = listOf(region),
            format = SchematicFormat.Sponge,
        )
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

    private fun readDimension(root: NbtTag.CompoundTag, name: String): Int = when (val tag = root.get(name)) {
        is NbtTag.ShortTag -> tag.value.toInt()
        is NbtTag.IntTag -> tag.value
        else -> throw BlockPrintException("Sponge: missing numeric '$name'")
    }
}
