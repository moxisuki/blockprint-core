package io.github.moxisuki.blockprint.core.format.sponge

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.LitematicReader
import io.github.moxisuki.blockprint.core.NbtTag
import io.github.moxisuki.blockprint.core.NbtTagType
import io.github.moxisuki.blockprint.core.Position
import io.github.moxisuki.blockprint.core.SchematicFormat
import io.github.moxisuki.blockprint.core.exceptions.LitematicException
import io.github.moxisuki.blockprint.core.model.BlockPrintDocument
import io.github.moxisuki.blockprint.core.model.BlockPrintRegion
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SpongeWriterTest {

    private fun sampleLitematic(): BlockPrintDocument {
        val palette = BlockPalette(
            listOf(
                BlockState("minecraft:air"),
                BlockState("minecraft:stone"),
                BlockState("minecraft:dirt"),
            ),
        )
        // 1x1x2 region: stone, dirt (x-major, y-major fallback with h=1).
        val blocks = intArrayOf(1, 2)
        val region = BlockPrintRegion(
            name = "SpongeSample",
            width = 1, height = 1, depth = 2,
            position = Position(0, 0, 0),
            palette = palette,
            blocks = blocks,
        )
        return BlockPrintDocument(
            minecraftDataVersion = 3465,
            version = null,
            name = "Sponge Build",
            author = "Author",
            description = "test",
            regions = listOf(region),
            format = SchematicFormat.Sponge,
        )
    }

    @Test
    fun write_then_read_round_trips() {
        val lit = sampleLitematic()
        val bytes = SpongeWriter.write(lit)
        val read = LitematicReader.read(bytes)
        assertEquals(1, read.regions.size)
        val r = read.regions.single()
        assertEquals(1, r.width); assertEquals(1, r.height); assertEquals(2, r.depth)
        assertArrayEquals(intArrayOf(1, 2), r.rawBlocks)
        assertEquals(3, r.palette.size)
    }

    @Test
    fun write_is_gzipped() {
        // WorldEdit 7.x exports gzipped `.schem` files; our writer now
        // wraps in GZIPOutputStream so the output is compatible.
        val bytes = SpongeWriter.write(sampleLitematic())
        assertEquals(0x1F.toByte(), bytes[0]) // gzip header byte 1
        assertEquals(0x8B.toByte(), bytes[1]) // gzip header byte 2
    }

    @Test
    fun write_rejects_multi_region_input() {
        val a = sampleLitematic().regions.single()
        val b = BlockPrintRegion(
            name = "Other", width = 1, height = 1, depth = 1,
            position = Position.ZERO,
            palette = BlockPalette(listOf(BlockState("minecraft:air"), BlockState("minecraft:bedrock"))),
            blocks = intArrayOf(1),
        )
        val multi = sampleLitematic().copy(regions = listOf(a, b))
        try {
            SpongeWriter.write(multi)
            assert(false) { "expected LitematicException" }
        } catch (e: LitematicException) {
            // expected
        }
    }

    @Test
    fun varint_encodes_small_palette_in_one_byte_each() {
        // All-zero (air) region with palette size 2 → 4 cells, all 0 → 4 varint bytes.
        val allAir = BlockPrintRegion(
            name = "Empty", width = 2, height = 1, depth = 2,
            position = Position.ZERO,
            palette = BlockPalette(listOf(BlockState("minecraft:air"), BlockState("minecraft:stone"))),
            blocks = IntArray(4),
        )
        val lit = sampleLitematic().copy(regions = listOf(allAir))
        val bytes = SpongeWriter.write(lit)
        val read = LitematicReader.read(bytes)
        assertArrayEquals(IntArray(4), read.regions.single().rawBlocks)
    }

    @Test
    fun write_streaming_matches_byteArray_output() {
        // The streaming write() overload expects the caller to own the
        // GZIP wrapper (same contract as BlueprintConverter). Wrap before
        // calling so the output matches the ByteArray convenience overload.
        val lit = sampleLitematic()
        val legacy = SpongeWriter.write(lit)
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(baos).use { gz ->
            SpongeWriter.write(lit, gz)
        }
        assertArrayEquals(legacy, baos.toByteArray())
    }

    @Test
    fun write_emits_v3_layout() {
        // Re-parse the bytes the writer produces and verify the v3 layout
        // markers: root is {"Schematic": {Version=3, Short dims, Offset
        // IntArray(3), Blocks compound with Palette+Data+BlockEntities,
        // Metadata with Date+WorldEdit}}.
        val lit = sampleLitematic()
        val bytes = SpongeWriter.write(lit)
        val root = io.github.moxisuki.blockprint.core.NbtReader.readRoot(bytes)
        val inner = root.get("Schematic") as NbtTag.CompoundTag
        assertEquals(3, (inner.get("Version") as NbtTag.IntTag).value)
        assertEquals(1, (inner.get("Width") as NbtTag.ShortTag).value.toInt())
        assertEquals(1, (inner.get("Height") as NbtTag.ShortTag).value.toInt())
        assertEquals(2, (inner.get("Length") as NbtTag.ShortTag).value.toInt())
        val offset = inner.get("Offset") as NbtTag.IntArrayTag
        assertArrayEquals(intArrayOf(0, 0, 0), offset.value)
        val blocks = inner.get("Blocks") as NbtTag.CompoundTag
        val palette = blocks.get("Palette") as NbtTag.CompoundTag
        assertEquals(3, palette.value.size)
        val data = blocks.get("Data") as NbtTag.ByteArrayTag
        assertEquals(2, data.value.size) // 2 cells × 1 varint byte each
        val blockEntities = blocks.get("BlockEntities") as NbtTag.ListTag
        assertEquals(0, blockEntities.value.size)
        val meta = inner.get("Metadata") as NbtTag.CompoundTag
        assertEquals(true, meta.contains("Date"))
        assertEquals(true, meta.contains("WorldEdit"))
        val worldEdit = meta.get("WorldEdit") as NbtTag.CompoundTag
        assertEquals("blockprint-core", (worldEdit.get("Version") as NbtTag.StringTag).value)
    }

    @Test
    fun read_v3_worldedit_fixture() {
        // Real-world v3 file saved by WorldEdit 7.4.3-beta-01 for fabric.
        // Lives outside the repo (Downloads); skip if absent.
        val f = File("C:/Users/Administrator/Downloads/1b1d1a6a-e202-4782-b9ed-fea0c869f282.schem")
        if (!f.exists()) return
        val lit = LitematicReader.read(f)
        assertEquals(1, lit.regions.size)
        val r = lit.regions.single()
        assertEquals(35, r.width)
        assertEquals(25, r.height)
        assertEquals(30, r.depth)
        assertEquals(117, r.palette.size)
        // Round-trip via the writer — blocks must be byte-equal.
        val rt = LitematicReader.read(SpongeWriter.write(BlockPrintDocument.fromLegacy(lit)))
        assertArrayEquals(r.rawBlocks, rt.regions.single().rawBlocks)
    }
}