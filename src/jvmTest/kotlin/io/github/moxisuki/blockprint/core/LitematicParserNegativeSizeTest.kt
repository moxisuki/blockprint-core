package io.github.moxisuki.blockprint.core

import io.github.moxisuki.blockprint.core.internal.LitematicParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression: some mods emit Litematica `Size` with signed values (e.g. a
 * mod for Y-down coordinates). The parser must take abs() of the size so
 * these files still load instead of throwing on `height <= 0`.
 *
 * We craft 1x1x1 region fixtures with abs(sx)*abs(sy)*abs(sz) == 1 cell
 * so BlockStatePacker doesn't complain about under-filled BlockStates.
 */
class LitematicParserNegativeSizeTest {

    private fun buildRegion(sx: Int, sy: Int, sz: Int): NbtTag.CompoundTag {
        require(kotlin.math.abs(sx) * kotlin.math.abs(sy) * kotlin.math.abs(sz) == 1) {
            "fixture must be 1 cell total so the 1-long BlockStates matches"
        }
        val region = NbtTag.CompoundTag(
            listOf(
                "Position" to NbtTag.CompoundTag(
                    listOf(
                        "x" to NbtTag.IntTag(0),
                        "y" to NbtTag.IntTag(0),
                        "z" to NbtTag.IntTag(0),
                    ),
                ),
                "Size" to NbtTag.CompoundTag(
                    listOf(
                        "x" to NbtTag.IntTag(sx),
                        "y" to NbtTag.IntTag(sy),
                        "z" to NbtTag.IntTag(sz),
                    ),
                ),
                "BlockStatePalette" to NbtTag.ListTag(
                    elementType = NbtTagType.Compound,
                    value = listOf(
                        NbtTag.CompoundTag(listOf("Name" to NbtTag.StringTag("minecraft:air"))),
                    ),
                ),
                "BlockStates" to NbtTag.LongArrayTag(LongArray(1)),
            ),
        )
        return NbtTag.CompoundTag(
            listOf(
                "MinecraftDataVersion" to NbtTag.IntTag(3465),
                "Version" to NbtTag.IntTag(6),
                "Name" to NbtTag.StringTag(""),
                "Author" to NbtTag.StringTag(""),
                "Description" to NbtTag.StringTag(""),
                "Regions" to NbtTag.CompoundTag(listOf("Main" to region)),
            ),
        )
    }

    @Test
    fun negative_y_size_is_treated_as_positive_height() {
        val root = buildRegion(sx = 1, sy = -1, sz = 1)
        val lit = LitematicParser.parse(root)
        val r = lit.regions.single()
        assertEquals(1, r.width)
        assertEquals(1, r.height) // abs(-1)
        assertEquals(1, r.depth)
    }

    @Test
    fun negative_x_and_z_size_is_treated_as_positive() {
        val root = buildRegion(sx = -1, sy = 1, sz = -1)
        val lit = LitematicParser.parse(root)
        val r = lit.regions.single()
        assertEquals(1, r.width) // abs(-1)
        assertEquals(1, r.height)
        assertEquals(1, r.depth) // abs(-1)
    }

    @Test
    fun all_dimensions_negative_still_parse() {
        val root = buildRegion(sx = -1, sy = -1, sz = -1)
        val lit = LitematicParser.parse(root)
        val r = lit.regions.single()
        assertEquals(1, r.width)
        assertEquals(1, r.height)
        assertEquals(1, r.depth)
    }
}
