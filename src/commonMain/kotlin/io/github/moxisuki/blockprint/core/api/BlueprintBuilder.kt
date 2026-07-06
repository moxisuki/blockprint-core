package io.github.moxisuki.blockprint.core.api

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.Position
import io.github.moxisuki.blockprint.core.SchematicFormat
import io.github.moxisuki.blockprint.core.model.BlockPrintDocument
import io.github.moxisuki.blockprint.core.model.BlockPrintRegion

class BlueprintBuilder {
    private var documentName: String = ""
    private var documentAuthor: String = ""
    private var documentDescription: String = ""
    private var documentDataVersion: Int? = null
    private var documentVersion: Int? = null
    private var documentFormat: SchematicFormat = SchematicFormat.Litematica
    private val regionBuilders = mutableListOf<RegionBuilder>()

    fun name(name: String): BlueprintBuilder {
        documentName = name
        return this
    }

    fun author(author: String): BlueprintBuilder {
        documentAuthor = author
        return this
    }

    fun description(description: String): BlueprintBuilder {
        documentDescription = description
        return this
    }

    fun dataVersion(version: Int): BlueprintBuilder {
        documentDataVersion = version
        return this
    }

    fun version(version: Int): BlueprintBuilder {
        documentVersion = version
        return this
    }

    fun format(format: SchematicFormat): BlueprintBuilder {
        documentFormat = format
        return this
    }

    fun region(
        name: String,
        width: Int,
        height: Int,
        depth: Int,
        init: RegionBuilder.() -> Unit,
    ): BlueprintBuilder {
        require(width > 0 && height > 0 && depth > 0) {
            "Region dimensions must be positive: $width x $height x $depth"
        }
        val builder = RegionBuilder(name, width, height, depth)
        builder.init()
        regionBuilders.add(builder)
        return this
    }

    fun build(): BlockPrintDocument {
        val regions = regionBuilders.map { it.build() }
        return BlockPrintDocument(
            minecraftDataVersion = documentDataVersion,
            version = documentVersion,
            name = documentName,
            author = documentAuthor,
            description = documentDescription,
            regions = regions,
            format = documentFormat,
        )
    }
}

class RegionBuilder @PublishedApi internal constructor(
    val name: String,
    val width: Int,
    val height: Int,
    val depth: Int,
) {
    private var regionPosition: Position = Position.ZERO
    private val paletteKeys = mutableListOf("minecraft:air")
    private val paletteMap = mutableMapOf("minecraft:air" to 0)
    private val blocks: IntArray = IntArray(width * height * depth)

    private fun rawIndex(x: Int, y: Int, z: Int): Int {
        require(x in 0 until width && y in 0 until height && z in 0 until depth) {
            "($x, $y, $z) out of bounds for region $width x $height x $depth"
        }
        return y * (width * depth) + z * width + x
    }

    private fun resolvePaletteIndex(raw: String): Int {
        val key = raw.trim()
        paletteMap[key]?.let { return it }
        val idx = paletteKeys.size
        paletteKeys.add(key)
        paletteMap[key] = idx
        return idx
    }

    private fun resolvePaletteIndex(blockState: BlockState): Int {
        return resolvePaletteIndex(blockState.toString())
    }

    fun position(x: Int, y: Int, z: Int): RegionBuilder {
        regionPosition = Position(x, y, z)
        return this
    }

    fun position(position: Position): RegionBuilder {
        regionPosition = position
        return this
    }

    fun set(x: Int, y: Int, z: Int, blockState: String): RegionBuilder {
        blocks[rawIndex(x, y, z)] = resolvePaletteIndex(blockState)
        return this
    }

    fun set(x: Int, y: Int, z: Int, blockState: BlockState): RegionBuilder {
        blocks[rawIndex(x, y, z)] = resolvePaletteIndex(blockState)
        return this
    }

    fun fill(
        fromX: Int, fromY: Int, fromZ: Int,
        toX: Int, toY: Int, toZ: Int,
        blockState: String,
    ): RegionBuilder {
        val idx = resolvePaletteIndex(blockState)
        val x0 = minOf(fromX, toX).coerceIn(0, width - 1)
        val x1 = maxOf(fromX, toX).coerceIn(0, width - 1)
        val y0 = minOf(fromY, toY).coerceIn(0, height - 1)
        val y1 = maxOf(fromY, toY).coerceIn(0, height - 1)
        val z0 = minOf(fromZ, toZ).coerceIn(0, depth - 1)
        val z1 = maxOf(fromZ, toZ).coerceIn(0, depth - 1)
        for (y in y0..y1) {
            for (z in z0..z1) {
                for (x in x0..x1) {
                    blocks[y * (width * depth) + z * width + x] = idx
                }
            }
        }
        return this
    }

    fun fill(from: Position, to: Position, blockState: String): RegionBuilder {
        return fill(from.x, from.y, from.z, to.x, to.y, to.z, blockState)
    }

    fun air(x: Int, y: Int, z: Int): RegionBuilder {
        blocks[rawIndex(x, y, z)] = 0
        return this
    }

    fun fillAir(
        fromX: Int, fromY: Int, fromZ: Int,
        toX: Int, toY: Int, toZ: Int,
    ): RegionBuilder {
        val x0 = minOf(fromX, toX).coerceIn(0, width - 1)
        val x1 = maxOf(fromX, toX).coerceIn(0, width - 1)
        val y0 = minOf(fromY, toY).coerceIn(0, height - 1)
        val y1 = maxOf(fromY, toY).coerceIn(0, height - 1)
        val z0 = minOf(fromZ, toZ).coerceIn(0, depth - 1)
        val z1 = maxOf(fromZ, toZ).coerceIn(0, depth - 1)
        for (y in y0..y1) {
            for (z in z0..z1) {
                for (x in x0..x1) {
                    blocks[y * (width * depth) + z * width + x] = 0
                }
            }
        }
        return this
    }

    fun getBlockIndex(x: Int, y: Int, z: Int): Int = blocks[rawIndex(x, y, z)]

    fun getBlockState(x: Int, y: Int, z: Int): BlockState {
        val key = paletteKeys[blocks[rawIndex(x, y, z)]]
        return BlockState.parse(key)
    }

    fun isAir(x: Int, y: Int, z: Int): Boolean = getBlockIndex(x, y, z) == 0

    fun paletteSize(): Int = paletteKeys.size

    fun nonAirCount(): Int = blocks.count { it != 0 }

    @PublishedApi
    internal fun build(): BlockPrintRegion {
        val entries = paletteKeys.map { BlockState.parse(it) }
        return BlockPrintRegion(
            name = name,
            width = width,
            height = height,
            depth = depth,
            position = regionPosition,
            palette = BlockPalette(entries),
            blocks = blocks,
        )
    }
}
