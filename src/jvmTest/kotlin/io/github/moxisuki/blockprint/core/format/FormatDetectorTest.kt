package io.github.moxisuki.blockprint.core.format

import io.github.moxisuki.blockprint.core.NbtTag
import io.github.moxisuki.blockprint.core.NbtTagType
import io.github.moxisuki.blockprint.core.SchematicFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatDetectorTest {
    @Test
    fun Litematica_with_Regions_compound() {
        val root = NbtTag.CompoundTag(listOf("Regions" to NbtTag.CompoundTag(emptyList())))
        assertEquals(SchematicFormat.Litematica, FormatDetector.detect(root))
    }

    @Test
    fun Sponge_v3_with_Schematic_wrapper() {
        val inner = NbtTag.CompoundTag(listOf(
            "Version" to NbtTag.IntTag(3),
            "Blocks" to NbtTag.CompoundTag(emptyList()),
        ))
        val root = NbtTag.CompoundTag(listOf("Schematic" to inner))
        assertEquals(SchematicFormat.Sponge, FormatDetector.detect(root))
    }

    @Test
    fun Structure_with_palette_and_blocks_lists() {
        val root = NbtTag.CompoundTag(listOf(
            "palette" to NbtTag.ListTag(NbtTagType.Compound, emptyList()),
            "blocks" to NbtTag.ListTag(NbtTagType.Compound, emptyList()),
        ))
        assertEquals(SchematicFormat.Structure, FormatDetector.detect(root))
    }

    @Test
    fun Sponge_v2_with_Metadata_EnclosingSize() {
        val root = NbtTag.CompoundTag(listOf(
            "Metadata" to NbtTag.CompoundTag(listOf(
                "EnclosingSize" to NbtTag.CompoundTag(emptyList()),
            )),
        ))
        assertEquals(SchematicFormat.Sponge, FormatDetector.detect(root))
    }

    @Test
    fun PartialNbt_with_Size_compound() {
        val root = NbtTag.CompoundTag(listOf("Size" to NbtTag.CompoundTag(emptyList())))
        assertEquals(SchematicFormat.PartialNbt, FormatDetector.detect(root))
    }

    @Test
    fun Unknown_for_empty_root() {
        val root = NbtTag.CompoundTag(emptyList())
        assertEquals(SchematicFormat.Unknown, FormatDetector.detect(root))
    }

    @Test
    fun Litematica_with_Sponge_compat_metadata_still_detected_as_Litematica() {
        val root = NbtTag.CompoundTag(listOf(
            "Regions" to NbtTag.CompoundTag(emptyList()),
            "Metadata" to NbtTag.CompoundTag(listOf("EnclosingSize" to NbtTag.CompoundTag(emptyList()))),
        ))
        assertEquals(SchematicFormat.Litematica, FormatDetector.detect(root))
    }
}
