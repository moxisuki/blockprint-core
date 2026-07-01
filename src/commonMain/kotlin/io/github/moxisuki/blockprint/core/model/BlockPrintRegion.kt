package io.github.moxisuki.blockprint.core.model

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.LitematicRegion as LegacyRegion
import io.github.moxisuki.blockprint.core.Position

/**
 * Single region. Stores the decoded block array as a flat [IntArray]
 * in y-major / z-middle / x-minor order. [rawBlocks] should not be mutated.
 */
class BlockPrintRegion(
    val name: String,
    val width: Int,
    val height: Int,
    val depth: Int,
    val position: Position,
    val palette: BlockPalette,
    blocks: IntArray? = null,
) {
    private val blocks: IntArray = blocks ?: IntArray(
        (width.toLong() * height.toLong() * depth.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
    )
    val rawBlocks: IntArray get() = blocks

    fun rawIndex(x: Int, y: Int, z: Int): Int {
        checkBounds(x, y, z)
        return y * (width * depth) + z * width + x
    }

    fun getBlock(x: Int, y: Int, z: Int): Int {
        checkBounds(x, y, z)
        return blocks[rawIndex(x, y, z)]
    }

    fun setBlock(x: Int, y: Int, z: Int, paletteIndex: Int) {
        checkBounds(x, y, z)
        require(paletteIndex >= 0 && paletteIndex < palette.size) {
            "Palette index $paletteIndex out of range for palette of size ${palette.size}"
        }
        blocks[rawIndex(x, y, z)] = paletteIndex
    }

    fun blockAt(x: Int, y: Int, z: Int) = palette[getBlock(x, y, z)]
    fun isAir(x: Int, y: Int, z: Int): Boolean = getBlock(x, y, z) == 0
    fun toBlockArray(): IntArray = blocks.copyOf()

    private fun checkBounds(x: Int, y: Int, z: Int) {
        require(x in 0 until width && y in 0 until height && z in 0 until depth) {
            "($x, $y, $z) out of bounds for region $width x $height x $depth"
        }
    }

    companion object {
        fun fromLegacy(r: LegacyRegion): BlockPrintRegion = BlockPrintRegion(
            name = r.name,
            width = r.width,
            height = r.height,
            depth = r.depth,
            position = r.position,
            palette = r.palette,
            blocks = r.toBlockArray(),
        )
    }
}