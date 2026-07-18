package io.github.moxisuki.blockprint.core.api

import io.github.moxisuki.blockprint.core.SchematicFormat
import io.github.moxisuki.blockprint.core.NbtTag
import io.github.moxisuki.blockprint.core.NbtTagType
import io.github.moxisuki.blockprint.core.NbtWriter
import io.github.moxisuki.blockprint.core.model.BlockPrintDocument
import io.github.moxisuki.blockprint.core.testutil.TestBlueprintFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.File

class BlockPrintReaderTest {
    @Test
    fun read_from_file_returns_BlockPrintDocument() {
        val doc: BlockPrintDocument = BlockPrintReader.read(TestBlueprintFixtures.minimalLitematicFile())
        assertNotNull(doc.primaryRegion)
    }

    @Test
    fun read_from_InputStream_matches_read_from_file() {
        val file = TestBlueprintFixtures.minimalLitematicFile()
        val fromFile = BlockPrintReader.read(file)
        val fromStream = file.inputStream().use { BlockPrintReader.read(it) }
        assertEquals(fromFile.name, fromStream.name)
        assertEquals(fromFile.regions.size, fromStream.regions.size)
    }

    @Test
    fun read_from_bytes_matches_read_from_file() {
        val bytes = TestBlueprintFixtures.minimalLitematicBytes()
        val fromFile = BlockPrintReader.read(TestBlueprintFixtures.minimalLitematicFile())
        val fromBytes = BlockPrintReader.read(bytes)
        assertEquals(fromFile.regions.size, fromBytes.regions.size)
    }

    @Test
    fun detectFormat_from_bytes_returns_Litematica() {
        val bytes = TestBlueprintFixtures.minimalLitematicBytes()
        assertEquals(SchematicFormat.Litematica, BlockPrintReader.detectFormat(bytes))
    }

    @Test
    fun readLenient_on_file_still_returns_document() {
        val fromFile = BlockPrintReader.readLenient(TestBlueprintFixtures.minimalLitematicFile())
        assertNotNull(fromFile)
    }

    @Test
    fun litematica_with_enclosing_size_metadata_remains_litematica() {
        val region = NbtTag.CompoundTag(listOf(
            "Size" to int3(2, 1, 1),
            "Position" to int3(0, 0, 0),
            "BlockStatePalette" to NbtTag.ListTag(
                NbtTagType.Compound,
                listOf(NbtTag.CompoundTag(listOf("Name" to NbtTag.StringTag("minecraft:air")))),
            ),
            "BlockStates" to NbtTag.LongArrayTag(longArrayOf(0L)),
        ))
        val root = NbtTag.CompoundTag(listOf(
            "Version" to NbtTag.IntTag(7),
            "Metadata" to NbtTag.CompoundTag(listOf("EnclosingSize" to int3(99, 99, 99))),
            "Regions" to NbtTag.CompoundTag(listOf("main" to region)),
        ))

        val document = BlockPrintReader.read(NbtWriter.writeRootToGzipBytes(root))

        assertEquals(SchematicFormat.Litematica, document.format)
        assertEquals(2, document.primaryRegion?.width)
        assertEquals(1, document.primaryRegion?.height)
        assertEquals(1, document.primaryRegion?.depth)
    }

    @Test
    fun canonical_sponge_v2_is_detected_and_read() {
        val root = NbtTag.CompoundTag(listOf(
            "Version" to NbtTag.IntTag(2),
            "DataVersion" to NbtTag.IntTag(3700),
            "Width" to NbtTag.ShortTag(2),
            "Height" to NbtTag.ShortTag(1),
            "Length" to NbtTag.ShortTag(1),
            "Offset" to NbtTag.IntArrayTag(intArrayOf(4, 5, 6)),
            "Palette" to NbtTag.CompoundTag(listOf(
                "minecraft:air" to NbtTag.IntTag(0),
                "minecraft:stone" to NbtTag.IntTag(1),
            )),
            "BlockData" to NbtTag.ByteArrayTag(byteArrayOf(0, 1)),
        ))
        val bytes = NbtWriter.writeRootToGzipBytes(root)

        assertEquals(SchematicFormat.Sponge, BlockPrintReader.detectFormat(bytes))
        val document = BlockPrintReader.read(bytes)
        assertEquals(SchematicFormat.Sponge, document.format)
        assertArrayEquals(intArrayOf(0, 1), document.primaryRegion?.rawBlocks)
        assertEquals(4, document.primaryRegion?.position?.x)
    }

    @Test
    fun strict_file_and_stream_reads_support_building_helper_json() {
        val json = """{"name":"sample","statePosArrayList":"{blockstatelist:[{Name:\"minecraft:air\"}],startpos:{X:0,Y:0,Z:0},endpos:{X:0,Y:0,Z:0},statelist:[I;0]}"}"""
        val file = kotlin.io.path.createTempFile("building-helper-", ".json").toFile()
        try {
            file.writeText("\uFEFF  \n$json")
            val fromFile = BlockPrintReader.read(file)
            val fromStream = file.inputStream().let { BlockPrintReader.read(it) }
            assertEquals(SchematicFormat.BuildingHelper, fromFile.format)
            assertEquals(SchematicFormat.BuildingHelper, fromStream.format)
            assertEquals("sample", fromFile.name)
        } finally {
            file.delete()
        }
    }

    private fun int3(x: Int, y: Int, z: Int) = NbtTag.CompoundTag(listOf(
        "x" to NbtTag.IntTag(x),
        "y" to NbtTag.IntTag(y),
        "z" to NbtTag.IntTag(z),
    ))
}
