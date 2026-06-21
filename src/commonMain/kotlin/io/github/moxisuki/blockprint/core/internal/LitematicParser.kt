package io.github.moxisuki.blockprint.core.internal

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.Litematic
import io.github.moxisuki.blockprint.core.LitematicRegion
import io.github.moxisuki.blockprint.core.NbtTag
import io.github.moxisuki.blockprint.core.NbtTagType
import io.github.moxisuki.blockprint.core.Position
import io.github.moxisuki.blockprint.core.SchematicFormat
import io.github.moxisuki.blockprint.core.exceptions.LitematicException

/**
 * Convert the parsed NBT root into a [Litematic] domain model.
 *
 * Direct Kotlin analogue of `readLitematicFromNBTData` from the JS reference.
 * Multi-region litematics are supported here even though the original viewer
 * ignored them — we preserve every region and its declared origin.
 */
internal object LitematicParser {

    fun parse(root: NbtTag.CompoundTag): Litematic {
        // Sponge Schematic Format: palette + varint block data at root,
        // no "Regions" compound. Detect by presence of Palette + BlockData.
        if (root.contains("BlockData") && root.contains("Palette")) {
            return parseSponge(root)
        }

        val regionsTag = root.get("Regions")
            ?: throw LitematicException("Litematic root is missing 'Regions' compound")
        if (regionsTag !is NbtTag.CompoundTag) {
            throw LitematicException("'Regions' must be a compound, got ${regionsTag::class.simpleName}")
        }

        // Sponge Schematic Format puts the actual dimensions under Metadata/EnclosingSize.
        // Litematica puts the dimensions directly in each region under "Size".
        val isSponge = root.contains("Metadata") &&
            (root.get("Metadata") as? NbtTag.CompoundTag)?.contains("EnclosingSize") == true
        val metadata = if (isSponge) root.get("Metadata") as? NbtTag.CompoundTag else null
        val format = if (isSponge) SchematicFormat.Sponge else SchematicFormat.Litematica

        val regions = regionsTag.entries().map { (regionName, regionTag) ->
            parseRegion(
                name = regionName,
                region = regionTag as? NbtTag.CompoundTag
                    ?: throw LitematicException("Region '$regionName' must be a compound"),
                isSponge = isSponge,
                metadata = metadata,
            )
        }

        return Litematic(
            minecraftDataVersion = readIntOrNull(root, "MinecraftDataVersion"),
            version = readIntOrNull(root, "Version"),
            name = metadata?.let { readStringOrEmpty(it, "Name") }
                ?.ifEmpty { readStringOrEmpty(root, "Name") }
                ?: readStringOrEmpty(root, "Name"),
            author = metadata?.let { readStringOrEmpty(it, "Author") }
                ?.ifEmpty { readStringOrEmpty(root, "Author") }
                ?: readStringOrEmpty(root, "Author"),
            description = metadata?.let { readStringOrEmpty(it, "Description") }
                ?.ifEmpty { readStringOrEmpty(root, "Description") }
                ?: readStringOrEmpty(root, "Description"),
            regions = regions,
            format = format,
        )
    }

    private fun parseRegion(
        name: String,
        region: NbtTag.CompoundTag,
        isSponge: Boolean,
        metadata: NbtTag.CompoundTag?,
    ): LitematicRegion {
        // Size: from region (Litematica) or from Metadata/EnclosingSize (Sponge).
        val (width, height, depth) = if (isSponge) {
            val enclosing = metadata?.get("EnclosingSize") as? NbtTag.CompoundTag
                ?: throw LitematicException("Sponge schematic: Metadata/EnclosingSize missing")
            readInt3(enclosing, "EnclosingSize")
        } else {
            val sizeTag = region.require("Size") as? NbtTag.CompoundTag
                ?: throw LitematicException("Region '$name' missing Size compound")
            readInt3(sizeTag, "Size")
        }
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw LitematicException("Region '$name' has invalid dimension: ${width}x${height}x${depth}")
        }

        val position = region.get("Position")?.let {
            if (it is NbtTag.CompoundTag) readInt3(it, "Position").let { (x, y, z) -> Position(x, y, z) }
            else null
        } ?: Position.ZERO

        val paletteTag = region.require("BlockStatePalette") as? NbtTag.ListTag
            ?: throw LitematicException("Region '$name' missing BlockStatePalette list")
        if (paletteTag.elementType != NbtTagType.Compound) {
            throw LitematicException(
                "Region '$name' BlockStatePalette element type must be COMPOUND, " +
                    "got ${paletteTag.elementType}",
            )
        }
        val palette = BlockPalette(paletteTag.value.map { parseBlockState(it as NbtTag.CompoundTag) })

        val blockStatesTag = region.require("BlockStates") as? NbtTag.LongArrayTag
            ?: throw LitematicException("Region '$name' missing BlockStates long array")
        val nbits = palette.bitsPerBlock
        BlockStatePacker.validateLength(blockStatesTag.value, nbits, width, height, depth)
        val blocks = BlockStatePacker.unpack(blockStatesTag.value, nbits, width, height, depth)

        return LitematicRegion(
            name = name,
            width = width,
            height = height,
            depth = depth,
            position = position,
            palette = palette,
            blocks = blocks,
        )
    }

    private fun parseBlockState(tag: NbtTag.CompoundTag): BlockState {
        val name = tag.get("Name") as? NbtTag.StringTag
            ?: throw LitematicException("Block state missing 'Name' string")
        val props = tag.get("Properties") as? NbtTag.CompoundTag
        val properties: Map<String, String>? = if (props == null) null else {
            if (props.value.isEmpty()) emptyMap()
            else props.value.associate { (k, v) ->
                val str = v as? NbtTag.StringTag
                    ?: throw LitematicException("Block state property '$k' must be a string")
                k to str.value
            }
        }
        return BlockState(name.value, properties)
    }

    private fun readInt3(compound: NbtTag.CompoundTag, label: String): IntArray {
        val x = (compound.get("x") as? NbtTag.IntTag)?.value
            ?: throw LitematicException("$label missing int field 'x'")
        val y = (compound.get("y") as? NbtTag.IntTag)?.value
            ?: throw LitematicException("$label missing int field 'y'")
        val z = (compound.get("z") as? NbtTag.IntTag)?.value
            ?: throw LitematicException("$label missing int field 'z'")
        return intArrayOf(x, y, z)
    }

    private fun readStringOrEmpty(c: NbtTag.CompoundTag?, key: String): String {
        val t = c?.get(key) as? NbtTag.StringTag ?: return ""
        return t.value
    }

    private fun readIntOrNull(c: NbtTag.CompoundTag, key: String): Int? {
        return when (val t = c.get(key)) {
            is NbtTag.IntTag -> t.value
            else -> null
        }
    }

    /**
     * Parse a Sponge Schematic v2 root (palette at root, varint-packed block
     * data, no `Regions` compound). Sponge stores block data in x → y → z
     * traversal order; we reorder into the library's canonical y-major /
     * z-middle / x-minor layout ([LitematicRegion.rawIndex]).
     *
     * Root layout:
     *   Version       : int (=2)
     *   DataVersion   : int
     *   Width, Height, Length : ints (note "Length", not "Depth")
     *   Offset        : compound x/y/z
     *   Palette       : compound { "0"→BlockState, "1"→BlockState, ... }
     *   PaletteMax    : int
     *   BlockData     : byte array of varint palette indices
     *   Metadata      : compound { Name, Author, Description, EnclosingSize }
     */
    private fun parseSponge(root: NbtTag.CompoundTag): Litematic {
        val width = (root.get("Width") as? NbtTag.IntTag)?.value
            ?: throw LitematicException("Sponge: missing int 'Width'")
        val height = (root.get("Height") as? NbtTag.IntTag)?.value
            ?: throw LitematicException("Sponge: missing int 'Height'")
        // Note: Sponge spec names the depth axis "Length", not "Depth".
        val depth = (root.get("Length") as? NbtTag.IntTag)?.value
            ?: throw LitematicException("Sponge: missing int 'Length'")
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw LitematicException("Sponge: invalid dimension ${width}x${height}x${depth}")
        }

        // Palette: compound of int-string key → BlockState compound.
        val paletteTag = root.get("Palette") as? NbtTag.CompoundTag
            ?: throw LitematicException("Sponge: 'Palette' must be a compound")
        val paletteEntries = mutableListOf<Pair<Int, BlockState>>()
        for ((k, v) in paletteTag.entries()) {
            val idx = k.toIntOrNull()
                ?: throw LitematicException("Sponge: palette key '$k' is not an int string")
            paletteEntries += idx to parseBlockState(v as NbtTag.CompoundTag)
        }
        paletteEntries.sortBy { it.first }
        val palette = BlockPalette(paletteEntries.map { it.second })

        // Block data: byte array of varint-encoded palette indices in x→y→z order.
        val blockData = (root.get("BlockData") as? NbtTag.ByteArrayTag)
            ?: throw LitematicException("Sponge: missing BlockData byte array")
        val total = width * height * depth
        val flat = IntArray(total)
        val src = blockData.value
        var pos = 0
        var k = 0
        for (y in 0 until height) for (z in 0 until depth) for (x in 0 until width) {
            if (k >= total) {
                throw LitematicException("Sponge: BlockData underrun (need $total varints, got fewer)")
            }
            val (v, nextPos) = readVarInt(src, pos)
            pos = nextPos
            flat[rawIndex(x, y, z, width, depth)] = v
            k++
        }
        if (k < total) {
            throw LitematicException("Sponge: BlockData has trailing bytes (decoded $k of $total)")
        }

        // Offset (optional; default zero).
        val position = (root.get("Offset") as? NbtTag.CompoundTag)?.let {
            val (x, y, z) = readInt3(it, "Offset")
            Position(x, y, z)
        } ?: Position.ZERO

        val meta = root.get("Metadata") as? NbtTag.CompoundTag
        val region = LitematicRegion(
            name = "Sponge",
            width = width,
            height = height,
            depth = depth,
            position = position,
            palette = palette,
            blocks = flat,
        )
        return Litematic(
            minecraftDataVersion = readIntOrNull(root, "DataVersion"),
            version = readIntOrNull(root, "Version"),
            name = readStringOrEmpty(meta, "Name").ifEmpty { readStringOrEmpty(root, "Name") },
            author = readStringOrEmpty(meta, "Author").ifEmpty { readStringOrEmpty(root, "Author") },
            description = readStringOrEmpty(meta, "Description")
                .ifEmpty { readStringOrEmpty(root, "Description") },
            regions = listOf(region),
            format = SchematicFormat.Sponge,
        )
    }

    /** y-major / z-middle / x-minor index, matching [LitematicRegion.rawIndex]. */
    private fun rawIndex(x: Int, y: Int, z: Int, width: Int, depth: Int): Int =
        y * (width * depth) + z * width + x

    /** Read a single varint (protobuf-style, 7 bits/byte, MSB = continuation). */
    private fun readVarInt(src: ByteArray, startPos: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var pos = startPos
        while (true) {
            if (pos >= src.size) {
                throw LitematicException("Sponge: varint truncated at byte $pos")
            }
            val b = src[pos].toInt() and 0xFF
            pos++
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) return result to pos
            shift += 7
            if (shift >= 35) {
                throw LitematicException("Sponge: varint too long (overflow)")
            }
        }
    }

    /**
     * Lenient variant: parse the NBT root as a [Litematic], accepting
     * partial files that lack the full `Regions` + `Palette` + `BlockStates`
     * structure. When a full litematic is present it's parsed normally;
     * otherwise we construct a single empty region sized to the file's
     * declared `Size` (or `Metadata/EnclosingSize` for Sponge), so the
     * returned [Litematic] is always usable — `MaterialList.from(it)` and
     * `it.primaryRegion` work even on stripped / debug files.
     */
    fun parseLenient(root: NbtTag.CompoundTag): Litematic {
        // If a full litematic structure is present, take the strict path.
        if (root.contains("Regions")) {
            return parse(root)
        }

        // Vanilla Minecraft structure file — sparse blocks, convert to dense.
        if (root.contains("palette") && (root.contains("blocks") || root.contains("Blocks"))) {
            return parseStructure(root)
        }

        // Partial litematic: pull whatever size metadata is present.
        val (width, height, depth) = readSizeLenient(root)

        val isSponge = root.contains("Metadata") &&
            (root.get("Metadata") as? NbtTag.CompoundTag)
                ?.contains("EnclosingSize") == true

        val regionName = if (isSponge) "Sponge" else "Default"
        val region = LitematicRegion(
            name = regionName,
            width = width,
            height = height,
            depth = depth,
            position = Position.ZERO,
            palette = BlockPalette(listOf(BlockState("minecraft:air", null))),
            blocks = IntArray(width * height * depth), // all air
        )

        val meta = root.get("Metadata") as? NbtTag.CompoundTag
        val format = if (isSponge) SchematicFormat.Sponge else SchematicFormat.PartialNbt
        return Litematic(
            minecraftDataVersion = readIntOrNull(root, "MinecraftDataVersion"),
            version = readIntOrNull(root, "Version"),
            name = if (isSponge) {
                readStringOrEmpty(meta, "Name").ifEmpty { readStringOrEmpty(root, "Name") }
            } else {
                readStringOrEmpty(root, "Name")
            },
            author = if (isSponge) {
                readStringOrEmpty(meta, "Author").ifEmpty { readStringOrEmpty(root, "Author") }
            } else {
                readStringOrEmpty(root, "Author")
            },
            description = readStringOrEmpty(root, "Description"),
            regions = listOf(region),
            format = format,
        )
    }

    /**
     * Parse a vanilla Minecraft structure file (`/structure save` output).
     *
     * Root layout:
     *   size     : list of 3 ints [width, height, depth]
     *   palette  : list of compounds, each { Name (str), Properties? (cmpd) }
     *   blocks   : list of compounds, each { pos (list[3 ints]), state (int) }
     *
     * Structure format is sparse — only non-air blocks are written.
     * We convert to the dense Litematica format:
     *   - prepend "minecraft:air" at palette index 0
     *   - shift all state indices by +1
     *   - fill unassigned cells with 0 (air)
     */
    private fun parseStructure(root: NbtTag.CompoundTag): Litematic {
        val (width, height, depth) = readSizeLenient(root)

        // Parse palette
        val rawPalette = (root.get("palette") as? NbtTag.ListTag)
            ?: throw LitematicException("Structure file: 'palette' must be a ListTag")
        require(rawPalette.elementType == NbtTagType.Compound) {
            "Structure palette element type must be Compound, got ${rawPalette.elementType}"
        }
        val rawEntries = rawPalette.value.map { parseBlockState(it as NbtTag.CompoundTag) }
        // Structure palette index 0 = first block (NOT air). Litematica
        // palette index 0 = air. Shift everything up by one.
        val palette = BlockPalette(
            listOf(BlockState("minecraft:air", null)) + rawEntries
        )

        // Parse blocks
        val blocksList = (root.get("blocks") as? NbtTag.ListTag)
            ?: (root.get("Blocks") as? NbtTag.ListTag)
            ?: throw LitematicException("Structure file: 'blocks' must be a ListTag")
        require(blocksList.elementType == NbtTagType.Compound) {
            "Structure blocks element type must be Compound, got ${blocksList.elementType}"
        }

        val dense = IntArray(width * height * depth) // all 0 = air
        for (element in blocksList.value) {
            val entry = element as NbtTag.CompoundTag
            val posList = entry.get("pos") as? NbtTag.ListTag
                ?: throw LitematicException("Structure block entry missing 'pos' list")
            require(posList.value.size == 3) {
                "Structure block pos must have 3 elements, got ${posList.value.size}"
            }
            val x = (posList.value[0] as NbtTag.IntTag).value
            val y = (posList.value[1] as NbtTag.IntTag).value
            val z = (posList.value[2] as NbtTag.IntTag).value
            val state = (entry.get("state") as? NbtTag.IntTag)?.value
                ?: throw LitematicException("Structure block entry missing 'state'")
            val idx = y * (width * depth) + z * width + x
            require(idx in dense.indices) {
                "Structure block pos [$x,$y,$z] out of bounds ${width}x${height}x${depth}"
            }
            dense[idx] = state + 1  // +1 跳过新加的 air
        }

        val region = LitematicRegion(
            name = "Structure",
            width = width,
            height = height,
            depth = depth,
            position = Position.ZERO,
            palette = palette,
            blocks = dense,
        )

        return Litematic(
            minecraftDataVersion = readIntOrNull(root, "DataVersion"),
            version = null,
            name = readStringOrEmpty(root, "name").ifEmpty { readStringOrEmpty(root, "Name") },
            author = readStringOrEmpty(root, "author").ifEmpty { readStringOrEmpty(root, "Author") },
            description = readStringOrEmpty(root, "Description"),
            regions = listOf(region),
            format = SchematicFormat.Structure,
        )
    }

    /**
     * Resolve a (width, height, depth) triple from a partial litematic
     * root. Supports three layouts:
     *
     *   1. `Size` compound at root with `x` / `y` / `z` ints
     *   2. `size` list of 3 ints at root (Litematica variant seen in
     *      stripped / debug files)
     *   3. `Metadata/EnclosingSize` compound with `x` / `y` / `z` ints (Sponge)
     *
     * @throws LitematicException if none of the above is present
     */
    private fun readSizeLenient(root: NbtTag.CompoundTag): IntArray {
        // Variant 1: Litematica-style Size compound at root
        (root.get("Size") as? NbtTag.CompoundTag)?.let {
            return readInt3(it, "Size")
        }
        // Variant 2: Litematica-style size list at root
        (root.get("size") as? NbtTag.ListTag)?.let { sizeList ->
            require(sizeList.elementType == NbtTagType.Int && sizeList.value.size == 3) {
                "Root 'size' list must be 3 ints, got ${sizeList.elementType} of ${sizeList.value.size}"
            }
            val ints = sizeList.value.map { (it as NbtTag.IntTag).value }
            return intArrayOf(ints[0], ints[1], ints[2])
        }
        // Variant 3: Sponge EnclosingSize
        (root.get("Metadata") as? NbtTag.CompoundTag)?.let { meta ->
            (meta.get("EnclosingSize") as? NbtTag.CompoundTag)?.let {
                return readInt3(it, "EnclosingSize")
            }
        }
        throw LitematicException(
            "Partial litematic: cannot determine size (no Size / size / Metadata.EnclosingSize)",
        )
    }
}
