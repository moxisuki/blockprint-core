package io.github.moxisuki.blockprint.core.model

import io.github.moxisuki.blockprint.core.BlockPalette
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
    private val volume = checkedVolume(width, height, depth, "Region '$name'")
    private val blocks: IntArray = blocks?.also {
        require(it.size == volume) {
            "Region '$name' block array has ${it.size} entries, expected $volume for ${width}x${height}x${depth}"
        }
    } ?: IntArray(volume)
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
}

internal fun checkedVolume(width: Int, height: Int, depth: Int, context: String): Int {
    require(width >= 0 && height >= 0 && depth >= 0) {
        "$context dimensions must be non-negative, got ${width}x${height}x${depth}"
    }
    val volume = width.toLong() * height.toLong() * depth.toLong()
    require(volume <= Int.MAX_VALUE) {
        "$context volume $volume exceeds the supported IntArray limit"
    }
    return volume.toInt()
}
