package io.github.moxisuki.blockprint.core

import io.github.moxisuki.blockprint.core.exceptions.LitematicException

/**
 * A single litematic region.
 *
 * The decoded block array is laid out as a flat [IntArray] of size
 * `width * height * depth`, indexed in **y-major / z-middle / x-minor**
 * order to match litematic's BlockStates encoding:
 *
 * ```
 * index = y * (width * depth) + z * width + x
 * ```
 *
 * Use [getBlock] / [setBlock] for type-safe access; use [rawIndex] when
 * you need to iterate without per-access bounds checks.
 *
 * The values stored are **palette indices** (not block ids). Pass them to
 * [palette] to resolve into a [BlockState]. Index 0 is conventionally air
 * and is preserved verbatim — callers decide whether to skip it.
 */
class LitematicRegion(
    val name: String,
    val width: Int,
    val height: Int,
    val depth: Int,
    val position: Position,
    val palette: BlockPalette,
    blocks: IntArray? = null,
) {
    init {
        require(width >= 0 && height >= 0 && depth >= 0) {
            "Region size must be non-negative, got ${width}x${height}x${depth}"
        }
        val expectedSize = width.toLong() * height.toLong() * depth.toLong()
        blocks?.let {
            require(it.size.toLong() == expectedSize) {
                "Block array size ${it.size} does not match declared $width*$height*$depth = $expectedSize"
            }
        }
    }

    private val blocks: IntArray = blocks ?: IntArray(
        (width.toLong() * height.toLong() * depth.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
    )

    /**
     * Raw palette-index array. Indexed via [rawIndex]. **Do not mutate.**
     * Use [withBlocks] if you need a copy you own.
     */
    val rawBlocks: IntArray get() = blocks

    /** Compute the linear index for the given voxel. */
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
        if (paletteIndex < 0 || paletteIndex >= palette.size) {
            throw LitematicException("Palette index $paletteIndex out of range for palette of size ${palette.size}")
        }
        blocks[rawIndex(x, y, z)] = paletteIndex
    }

    fun blockAt(x: Int, y: Int, z: Int): BlockState = palette[getBlock(x, y, z)]

    fun isAir(x: Int, y: Int, z: Int): Boolean = getBlock(x, y, z) == 0

    /** Returns a defensive copy of the block index array. */
    fun toBlockArray(): IntArray = blocks.copyOf()

    private fun checkBounds(x: Int, y: Int, z: Int) {
        if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= depth) {
            throw IndexOutOfBoundsException(
                "($x, $y, $z) out of bounds for region $width x $height x $depth",
            )
        }
    }

    override fun equals(other: Any?): Boolean = other is LitematicRegion &&
        other.name == name && other.width == width && other.height == height &&
        other.depth == depth && other.position == position && other.palette == palette &&
        other.blocks.contentEquals(blocks)

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + depth
        result = 31 * result + position.hashCode()
        result = 31 * result + palette.hashCode()
        result = 31 * result + blocks.contentHashCode()
        return result
    }
}
