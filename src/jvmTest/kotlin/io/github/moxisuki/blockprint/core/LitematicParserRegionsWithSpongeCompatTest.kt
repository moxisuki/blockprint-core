package io.github.moxisuki.blockprint.core

import io.github.moxisuki.blockprint.core.internal.LitematicParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests: a Litematica file with `Regions` and a Sponge-compat
 * `Metadata/EnclosingSize` (emitted by some mods for tool compatibility)
 * must be classified as Litematica, not Sponge.
 */
class LitematicParserRegionsWithSpongeCompatTest {

    private fun buildRegionsWithSpongeCompat(): NbtTag.CompoundTag {
        // 1x1x1 region with air (palette index 0).
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
                        "x" to NbtTag.IntTag(1),
                        "y" to NbtTag.IntTag(1),
                        "z" to NbtTag.IntTag(1),
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
        val enclosingSize = NbtTag.CompoundTag(
            listOf(
                "x" to NbtTag.IntTag(1),
                "y" to NbtTag.IntTag(1),
                "z" to NbtTag.IntTag(1),
            ),
        )
        val metadata = NbtTag.CompoundTag(
            listOf(
                "Name" to NbtTag.StringTag(""),
                "Author" to NbtTag.StringTag(""),
                "Description" to NbtTag.StringTag(""),
                "EnclosingSize" to enclosingSize,
            ),
        )
        return NbtTag.CompoundTag(
            listOf(
                "MinecraftDataVersion" to NbtTag.IntTag(3465),
                "Version" to NbtTag.IntTag(6),
                "Name" to NbtTag.StringTag(""),
                "Author" to NbtTag.StringTag(""),
                "Description" to NbtTag.StringTag(""),
                "Metadata" to metadata,
                "Regions" to NbtTag.CompoundTag(listOf("Main" to region)),
            ),
        )
    }

    @Test
    fun regions_with_sponge_compat_metadata_is_litematica() {
        val root = buildRegionsWithSpongeCompat()
        // Strict parse: must NOT throw, must classify as Litematica.
        val lit = LitematicParser.parse(root)
        assertEquals(SchematicFormat.Litematica, lit.format)
        assertEquals(1, lit.regions.size)
        assertEquals("Main", lit.regions.single().name)
    }
}
