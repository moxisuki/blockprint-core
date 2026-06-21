package io.github.moxisuki.blockprint.core

import io.github.moxisuki.blockprint.core.exceptions.LitematicException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BlueprintConverterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sampleLitematic(): Litematic {
        val palette = BlockPalette(
            listOf(
                BlockState("minecraft:air"),
                BlockState("minecraft:stone"),
                BlockState("minecraft:dirt"),
            ),
        )
        val region = LitematicRegion(
            name = "Main",
            width = 2, height = 1, depth = 1,
            position = Position.ZERO,
            palette = palette,
            blocks = intArrayOf(1, 2),
        )
        return Litematic(
            minecraftDataVersion = 3465,
            version = 6,
            name = "n", author = "a", description = "",
            regions = listOf(region),
            format = SchematicFormat.Litematica,
        )
    }

    @Test
    fun convert_litematic_to_all_targets() {
        val lit = sampleLitematic()
        for (target in listOf(
            SchematicFormat.Litematica,
            SchematicFormat.Sponge,
            SchematicFormat.Structure,
            SchematicFormat.BuildingHelper,
        )) {
            val bytes = BlueprintConverter.convert(lit, target)
            assertTrue("target $target produced empty output", bytes.isNotEmpty())
        }
    }

    @Test
    fun convert_litematic_to_litematic_then_read_is_identity() {
        val lit = sampleLitematic()
        val bytes = BlueprintConverter.convert(lit, SchematicFormat.Litematica)
        val read = LitematicReader.read(bytes)
        assertEquals(1, read.regions.size)
        assertArrayEquals(intArrayOf(1, 2), read.regions.single().rawBlocks)
        assertEquals(3, read.regions.single().palette.size)
    }

    @Test
    fun convert_litematic_to_sponge_then_read_recovers_blocks() {
        val lit = sampleLitematic()
        val bytes = BlueprintConverter.convert(lit, SchematicFormat.Sponge)
        val read = LitematicReader.read(bytes)
        assertArrayEquals(intArrayOf(1, 2), read.regions.single().rawBlocks)
    }

    @Test
    fun convert_litematic_to_structure_then_read_lenient_recovers_blocks() {
        val lit = sampleLitematic()
        val bytes = BlueprintConverter.convert(lit, SchematicFormat.Structure)
        val read = LitematicReader.readLenient(bytes)
        val r = read.regions.single()
        // Round-trip: writer drops air from palette & shifts state indices by -1;
        // reader prepends air & shifts back by +1. Original blocks are recovered.
        assertArrayEquals(intArrayOf(1, 2), r.rawBlocks)
    }

    @Test
    fun convert_litematic_to_buildingHelper_then_read_lenient_recovers_blocks() {
        val lit = sampleLitematic()
        val bytes = BlueprintConverter.convert(lit, SchematicFormat.BuildingHelper)
        val read = LitematicReader.readLenient(bytes)
        val r = read.regions.single()
        assertArrayEquals(intArrayOf(1, 2), r.rawBlocks)
    }

    @Test
    fun convert_bytes_to_litematica_uses_auto_detected_source() {
        val lit = sampleLitematic()
        val litematicBytes = BlueprintConverter.convert(lit, SchematicFormat.Litematica)
        // Round-trip via the ByteArray overload — this triggers the auto-detect
        // path on the source side.
        val out = BlueprintConverter.convert(litematicBytes, SchematicFormat.Litematica)
        val read = LitematicReader.read(out)
        assertArrayEquals(intArrayOf(1, 2), read.regions.single().rawBlocks)
    }

    @Test
    fun convert_multi_region_to_sponge_throws() {
        val a = sampleLitematic().regions.single()
        val b = LitematicRegion(
            name = "Other", width = 1, height = 1, depth = 1,
            position = Position.ZERO,
            palette = BlockPalette(listOf(BlockState("minecraft:air"), BlockState("minecraft:bedrock"))),
            blocks = intArrayOf(1),
        )
        val multi = sampleLitematic().copy(regions = listOf(a, b))
        try {
            BlueprintConverter.convert(multi, SchematicFormat.Sponge)
            assert(false) { "expected LitematicException" }
        } catch (e: LitematicException) {
            // expected
        }
    }

    @Test
    fun convert_multi_region_to_litematica_succeeds() {
        val a = sampleLitematic().regions.single()
        val b = LitematicRegion(
            name = "Other", width = 1, height = 1, depth = 1,
            position = Position.ZERO,
            palette = BlockPalette(listOf(BlockState("minecraft:air"), BlockState("minecraft:bedrock"))),
            blocks = intArrayOf(1),
        )
        val multi = sampleLitematic().copy(regions = listOf(a, b))
        val bytes = BlueprintConverter.convert(multi, SchematicFormat.Litematica)
        val read = LitematicReader.read(bytes)
        assertEquals(2, read.regions.size)
    }

    @Test
    fun convert_to_partialNbt_throws() {
        try {
            BlueprintConverter.convert(sampleLitematic(), SchematicFormat.PartialNbt)
            assert(false) { "expected LitematicException" }
        } catch (e: LitematicException) {
            // expected
        }
    }

    @Test
    fun convert_to_unknown_throws() {
        try {
            BlueprintConverter.convert(sampleLitematic(), SchematicFormat.Unknown)
            assert(false) { "expected LitematicException" }
        } catch (e: LitematicException) {
            // expected
        }
    }

    @Test
    fun convert_file_to_file_routes_by_extension() {
        val lit = sampleLitematic()
        val inFile = tmp.newFile("input.litematic")
        inFile.writeBytes(BlueprintConverter.convert(lit, SchematicFormat.Litematica))
        val outFile = tmp.newFile("output.schematic")
        BlueprintConverter.convert(inFile, outFile)
        assertTrue("output file empty", outFile.length() > 0)
        val read = LitematicReader.read(outFile)
        assertArrayEquals(intArrayOf(1, 2), read.regions.single().rawBlocks)
    }

    @Test
    fun convert_file_with_unknown_source_extension_throws() {
        val inFile = tmp.newFile("input.bin")
        inFile.writeBytes(byteArrayOf(0x00))
        val outFile = tmp.newFile("output.litematic")
        try {
            BlueprintConverter.convert(inFile, outFile)
            assert(false) { "expected LitematicException" }
        } catch (e: LitematicException) {
            // expected
        }
    }
}
