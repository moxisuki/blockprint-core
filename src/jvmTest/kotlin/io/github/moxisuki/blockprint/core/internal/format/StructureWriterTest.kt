package io.github.moxisuki.blockprint.core.internal.format

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.Litematic
import io.github.moxisuki.blockprint.core.LitematicReader
import io.github.moxisuki.blockprint.core.LitematicRegion
import io.github.moxisuki.blockprint.core.NbtTag
import io.github.moxisuki.blockprint.core.NbtTagType
import io.github.moxisuki.blockprint.core.Position
import io.github.moxisuki.blockprint.core.SchematicFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StructureWriterTest {

    private fun sampleLitematic(): Litematic {
        // 1x2x1 region. y-major layout for blocks:
        //   index 0 = air
        //   index 1 = stone
        val palette = BlockPalette(
            listOf(
                BlockState("minecraft:air"),
                BlockState("minecraft:stone"),
            ),
        )
        val blocks = intArrayOf(0, 1) // y=0 air, y=1 stone
        val region = LitematicRegion(
            name = "Structure",
            width = 1, height = 2, depth = 1,
            position = Position.ZERO,
            palette = palette,
            blocks = blocks,
        )
        return Litematic(
            minecraftDataVersion = 3465,
            version = null,
            name = "x",
            author = "",
            description = "",
            regions = listOf(region),
            format = SchematicFormat.Structure,
        )
    }

    @Test
    fun write_then_read_via_lenient_round_trips() {
        val lit = sampleLitematic()
        val bytes = StructureWriter.write(lit)
        val read = LitematicReader.readLenient(bytes)
        assertEquals(1, read.regions.size)
        val r = read.regions.single()
        assertEquals(1, r.width); assertEquals(2, r.height); assertEquals(1, r.depth)
        // The lenient reader restores air at palette index 0 (mirroring the
        // writer's "drop air on write" rule), so the round-trip palette has
        // 2 entries: [air, stone]. The block array round-trips as [0, 1]
        // (y=0 air, y=1 stone), since the write skips air cells and the
        // reader only repopulates the non-air cell.
        assertEquals(2, r.palette.size)
        assertEquals("minecraft:air", r.palette[0].name)
        assertEquals("minecraft:stone", r.palette[1].name)
        assertEquals(0, r.rawBlocks[0])
        assertEquals(1, r.rawBlocks[1])
    }

    @Test
    fun write_produces_gzipped_output() {
        val bytes = StructureWriter.write(sampleLitematic())
        assertEquals(0x1F.toByte(), bytes[0])
        assertEquals(0x8B.toByte(), bytes[1])
    }

    @Test
    fun write_omits_air_cells_from_sparse_blocks() {
        // All-air region → empty blocks list (sparse).
        val allAir = LitematicRegion(
            name = "Empty",
            width = 2, height = 2, depth = 2,
            position = Position.ZERO,
            palette = BlockPalette(listOf(BlockState("minecraft:air"))),
            blocks = IntArray(8),
        )
        val lit = sampleLitematic().copy(regions = listOf(allAir))
        val bytes = StructureWriter.write(lit)
        // Re-parse to count blocks list size.
        val root = io.github.moxisuki.blockprint.core.NbtReader.readRoot(
            java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(bytes)).readBytes(),
        )
        val blocks = root.get("blocks") as NbtTag.ListTag
        assertEquals(0, blocks.value.size)
    }

    @Test
    fun write_root_has_required_keys() {
        val bytes = StructureWriter.write(sampleLitematic())
        val root = io.github.moxisuki.blockprint.core.NbtReader.readRoot(
            java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(bytes)).readBytes(),
        )
        val size = root.get("size") as NbtTag.ListTag
        assertEquals(NbtTagType.Int, size.elementType)
        assertEquals(3, size.value.size)
        val sizeInts = size.value.map { (it as NbtTag.IntTag).value }
        assertEquals(listOf(1, 2, 1), sizeInts)
        val palette = root.get("palette") as NbtTag.ListTag
        assertTrue(palette.value.isNotEmpty())
    }

    @Test
    fun write_streaming_matches_byteArray_output() {
        val lit = sampleLitematic()
        val legacy = StructureWriter.write(lit)
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(baos).use { gz -> StructureWriter.write(lit, gz) }
        assertArrayEquals(legacy, baos.toByteArray())
    }
}
