package io.github.moxisuki.blockprint.core

import io.github.moxisuki.blockprint.core.exceptions.BlockPrintException
import io.github.moxisuki.blockprint.core.model.BlockPrintDocument
import io.github.moxisuki.blockprint.core.model.BlockPrintRegion
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FilterInputStream

class BlockPrintConverterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sampleLitematic(): BlockPrintDocument = BlockPrintDocument.fromLegacy(
        Litematic(
            minecraftDataVersion = 3465,
            version = 6,
            name = "n", author = "a", description = "",
            regions = listOf(
                LitematicRegion(
                    name = "Main",
                    width = 2, height = 1, depth = 1,
                    position = Position.ZERO,
                    palette = BlockPalette(
                        listOf(
                            BlockState("minecraft:air"),
                            BlockState("minecraft:stone"),
                            BlockState("minecraft:dirt"),
                        ),
                    ),
                    blocks = intArrayOf(1, 2),
                ),
            ),
            format = SchematicFormat.Litematica,
        ),
    )

    private fun sampleBedrockRegion(): BlockPrintRegion = BlockPrintRegion.fromLegacy(
        LitematicRegion(
            name = "Other", width = 1, height = 1, depth = 1,
            position = Position.ZERO,
            palette = BlockPalette(
                listOf(
                    BlockState("minecraft:air"),
                    BlockState("minecraft:bedrock"),
                ),
            ),
            blocks = intArrayOf(1),
        ),
    )

    @Test
    fun convert_litematic_to_all_targets() {
        val lit = sampleLitematic()
        for (target in listOf(
            SchematicFormat.Litematica,
            SchematicFormat.Sponge,
            SchematicFormat.Structure,
            SchematicFormat.BuildingHelper,
        )) {
            val bytes = BlockPrintConverter.convert(lit, target)
            assertTrue("target $target produced empty output", bytes.isNotEmpty())
        }
    }

    @Test
    fun convert_litematic_to_litematic_then_read_is_identity() {
        val lit = sampleLitematic()
        val bytes = BlockPrintConverter.convert(lit, SchematicFormat.Litematica)
        val read = LitematicReader.read(bytes)
        assertEquals(1, read.regions.size)
        assertArrayEquals(intArrayOf(1, 2), read.regions.single().rawBlocks)
        assertEquals(3, read.regions.single().palette.size)
    }

    @Test
    fun convert_litematic_to_sponge_then_read_recovers_blocks() {
        val lit = sampleLitematic()
        val bytes = BlockPrintConverter.convert(lit, SchematicFormat.Sponge)
        val read = LitematicReader.read(bytes)
        assertArrayEquals(intArrayOf(1, 2), read.regions.single().rawBlocks)
    }

    @Test
    fun convert_litematic_to_structure_then_read_lenient_recovers_blocks() {
        val lit = sampleLitematic()
        val bytes = BlockPrintConverter.convert(lit, SchematicFormat.Structure)
        val read = LitematicReader.readLenient(bytes)
        val r = read.regions.single()
        // Round-trip: writer drops air from palette & shifts state indices by -1;
        // reader prepends air & shifts back by +1. Original blocks are recovered.
        assertArrayEquals(intArrayOf(1, 2), r.rawBlocks)
    }

    @Test
    fun convert_litematic_to_buildingHelper_then_read_lenient_recovers_blocks() {
        val lit = sampleLitematic()
        val bytes = BlockPrintConverter.convert(lit, SchematicFormat.BuildingHelper)
        val read = LitematicReader.readLenient(bytes)
        val r = read.regions.single()
        assertArrayEquals(intArrayOf(1, 2), r.rawBlocks)
    }

    @Test
    fun convert_bytes_to_litematica_uses_auto_detected_source() {
        val lit = sampleLitematic()
        val litematicBytes = BlockPrintConverter.convert(lit, SchematicFormat.Litematica)
        // Round-trip via the ByteArray overload — this triggers the auto-detect
        // path on the source side.
        val out = BlockPrintConverter.convert(litematicBytes, SchematicFormat.Litematica)
        val read = LitematicReader.read(out)
        assertArrayEquals(intArrayOf(1, 2), read.regions.single().rawBlocks)
    }

    @Test
    fun convert_multi_region_to_sponge_throws() {
        val a = sampleLitematic().regions.single()
        val b = sampleBedrockRegion()
        val multi = sampleLitematic().copy(regions = listOf(a, b))
        try {
            BlockPrintConverter.convert(multi, SchematicFormat.Sponge)
            assert(false) { "expected BlockPrintException" }
        } catch (e: BlockPrintException) {
            // expected
        }
    }

    @Test
    fun convert_multi_region_to_litematica_succeeds() {
        val a = sampleLitematic().regions.single()
        val b = sampleBedrockRegion()
        val multi = sampleLitematic().copy(regions = listOf(a, b))
        val bytes = BlockPrintConverter.convert(multi, SchematicFormat.Litematica)
        val read = LitematicReader.read(bytes)
        assertEquals(2, read.regions.size)
    }

    @Test
    fun convert_to_partialNbt_throws() {
        try {
            BlockPrintConverter.convert(sampleLitematic(), SchematicFormat.PartialNbt)
            assert(false) { "expected BlockPrintException" }
        } catch (e: BlockPrintException) {
            // expected
        }
    }

    @Test
    fun convert_to_unknown_throws() {
        try {
            BlockPrintConverter.convert(sampleLitematic(), SchematicFormat.Unknown)
            assert(false) { "expected BlockPrintException" }
        } catch (e: BlockPrintException) {
            // expected
        }
    }

    @Test
    fun convert_file_to_file_routes_by_extension() {
        val lit = sampleLitematic()
        val inFile = tmp.newFile("input.litematic")
        inFile.writeBytes(BlockPrintConverter.convert(lit, SchematicFormat.Litematica))
        val outFile = tmp.newFile("output.schematic")
        BlockPrintConverter.convert(inFile, outFile)
        assertTrue("output file empty", outFile.length() > 0)
        val read = LitematicReader.read(outFile)
        assertArrayEquals(intArrayOf(1, 2), read.regions.single().rawBlocks)
    }

    @Test
    fun convert_file_to_file_accepts_schem_and_schematic_for_target() {
        // .schem is a common short extension used by some tools (MCreator,
        // Schematica export); .schematic is the WorldEdit/Sponge default.
        // Both must route to the Sponge writer.
        val lit = sampleLitematic()
        for (targetExt in listOf("schematic", "schem")) {
            val inFile = tmp.newFile("input-${System.nanoTime()}.litematic")
            inFile.writeBytes(BlockPrintConverter.convert(lit, SchematicFormat.Litematica))
            val outFile = tmp.newFile("output-${System.nanoTime()}.$targetExt")
            BlockPrintConverter.convert(inFile, outFile)
            assertTrue("output file empty for .$targetExt", outFile.length() > 0)
        }
    }

    @Test
    fun convert_file_with_unknown_source_extension_throws() {
        val inFile = tmp.newFile("input.bin")
        inFile.writeBytes(byteArrayOf(0x00))
        val outFile = tmp.newFile("output.litematic")
        try {
            BlockPrintConverter.convert(inFile, outFile)
            assert(false) { "expected BlockPrintException" }
        } catch (e: BlockPrintException) {
            // expected
        }
    }

    @Test
    fun convert_inputStream_closes_and_matches_bytes_overload() {
        val lit = sampleLitematic()
        val bytes = BlockPrintConverter.convert(lit, SchematicFormat.Litematica)
        // Wrap in an InputStream whose close() flips a flag we can assert on.
        val src = java.io.ByteArrayInputStream(bytes)
        var closed = false
        val trackingStream = object : java.io.FilterInputStream(src) {
            override fun close() {
                closed = true
                super.close()
            }
        }
        val out = BlockPrintConverter.convert(trackingStream, SchematicFormat.Litematica)
        assertTrue("stream not closed after convert", closed)
        assertArrayEquals(bytes, out)
    }

    @Test
    fun convert_streaming_matches_byteArray_for_all_targets() {
        val lit = sampleLitematic()
        for (target in listOf(
            SchematicFormat.Litematica,
            SchematicFormat.Sponge,
            SchematicFormat.Structure,
            SchematicFormat.BuildingHelper,
        )) {
            val ba = BlockPrintConverter.convert(lit, target)
            val baos = java.io.ByteArrayOutputStream()
            BlockPrintConverter.convert(lit, target, baos)
            val streamed = baos.toByteArray()
            assertArrayEquals(
                "streaming output must byte-match ByteArray output for target=$target",
                ba, streamed,
            )
        }
    }

    @Test
    fun convert_file_to_file_uses_streaming_path() {
        val lit = sampleLitematic()
        val sourceBytes = BlockPrintConverter.convert(lit, SchematicFormat.Litematica)
        val sourceFile = tmp.newFile("src.litematic")
        sourceFile.writeBytes(sourceBytes)
        val outFile = tmp.newFile("out.litematic")
        BlockPrintConverter.convert(sourceFile, outFile, SchematicFormat.Litematica)
        val read = LitematicReader.read(outFile)
        assertEquals(1, read.regions.size)
        assertEquals("Main", read.regions.single().name)
        assertArrayEquals(intArrayOf(1, 2), read.regions.single().rawBlocks)
    }
}
