package io.github.moxisuki.blockprint.core.glb.writer

import io.github.moxisuki.blockprint.core.glb.mesh.FloorStats
import io.github.moxisuki.blockprint.core.glb.mesh.GlbAtlas
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class GlbWriterStreamingTest {

    private fun emptyStats() = FloorStats(
        floorCount = 0,
        perFloorVertices = IntArray(0),
        perFloorIndices = IntArray(0),
        totalPositions = 0, totalNormals = 0, totalUvs = 0, totalIndices = 0,
        minX = 0f, minY = 0f, minZ = 0f, maxX = 0f, maxY = 0f, maxZ = 0f,
    )

    private fun singleFloorStats(vertices: Int, indices: Int) = FloorStats(
        floorCount = 1,
        perFloorVertices = intArrayOf(vertices),
        perFloorIndices = intArrayOf(indices),
        totalPositions = vertices * 3,
        totalNormals = vertices * 3,
        totalUvs = vertices * 2,
        totalIndices = indices,
        minX = 0f, minY = 0f, minZ = 0f, maxX = 1f, maxY = 1f, maxZ = 1f,
    )

    private fun singleFloorAtlas(): GlbAtlas =
        GlbAtlas(pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), width = 1, height = 1)

    @Test
    fun buildHeader_starts_with_glb_magic() {
        val writer = GlbWriter()
        val bytes = writer.buildHeader(
            atlas = singleFloorAtlas(),
            stats = singleFloorStats(4, 6),
            options = GlbExportOptions(),
        )
        assertEquals(0x67, bytes[0].toInt() and 0xFF)
        assertEquals(0x6C, bytes[1].toInt() and 0xFF)
        assertEquals(0x54, bytes[2].toInt() and 0xFF)
        assertEquals(0x46, bytes[3].toInt() and 0xFF)
        // GLB version 2 (LE int). bytes[4] is the low byte of the int; should be 0x02.
        assertEquals(0x02, bytes[4].toInt() and 0xFF)
    }

    @Test
    fun buildHeader_bin_length_equals_atlas_size_when_no_floors() {
        val writer = GlbWriter()
        val atlas = singleFloorAtlas() // 4 bytes: 0x89 0x50 0x4E 0x47
        val bytes = writer.buildHeader(atlas, emptyStats(), GlbExportOptions())
        // For empty stats, the BIN chunk is just the padded atlas (4 bytes here,
        // since 4 is already 4-byte aligned). The BIN chunk header is the last
        // 8 bytes of buildHeader's output: 4 bytes length (LE int) + 4 bytes 'BIN\0'.
        val binLen = java.nio.ByteBuffer.wrap(bytes, bytes.size - 8, 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        assertEquals(atlas.pngBytes.size, binLen)
    }

    @Test
    fun writeFloor_writes_positions_uvs_normals_indices_in_order() {
        val writer = GlbWriter()
        val out = ByteArrayOutputStream()
        val positions = floatArrayOf(
            0f, 0f, 0f,    1f, 0f, 0f,    1f, 1f, 0f,    0f, 1f, 0f,
        )
        val uvs = floatArrayOf(
            0f, 0f,   1f, 0f,   1f, 1f,   0f, 1f,
        )
        val normals = floatArrayOf(
            0f, 0f, 1f,   0f, 0f, 1f,   0f, 0f, 1f,   0f, 0f, 1f,
        )
        val indices = intArrayOf(0, 1, 2,   0, 2, 3)
        writer.writeFloor(
            stream = out, floorIdx = 0, yMin = 0, yMax = 0,
            positions = positions, uvs = uvs, normals = normals, indices = indices,
            vertexOffset = 100, // arbitrary non-zero to verify offset applied to indices
        )
        val bytes = out.toByteArray()
        // Expected layout: positions (48 bytes) | normals (48 bytes) | uvs (32 bytes) | indices+offset (24 bytes)
        // = 48 + 48 + 32 + 24 = 152 bytes
        assertEquals(152, bytes.size)
        // Spot-check the first 12 bytes: positions[0] = 0f LE = 0x00000000
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), bytes.copyOfRange(0, 4))
        // Index 0 with vertexOffset=100 → 100 → LE 0x64000000
        // Indices start at position 48+48+32 = 128.
        assertEquals(100, java.nio.ByteBuffer.wrap(bytes, 128, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
    }

    @Test
    fun writeStreaming_invokes_sink_and_streams_atlas() {
        val writer = GlbWriter()
        val out = ByteArrayOutputStream()
        var sinkCalled = false
        val stats = FloorStats(
            floorCount = 1,
            perFloorVertices = intArrayOf(4),
            perFloorIndices = intArrayOf(6),
            totalPositions = 12, totalNormals = 12, totalUvs = 8, totalIndices = 6,
            minX = 0f, minY = 0f, minZ = 0f, maxX = 1f, maxY = 1f, maxZ = 1f,
        )
        writer.writeStreaming(
            stream = out,
            atlas = singleFloorAtlas(),
            stats = stats,
            options = GlbExportOptions(),
            sink = {
                sinkCalled = true
                val positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f)
                val uvs = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
                val normals = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f)
                val indices = intArrayOf(0, 1, 2, 0, 2, 3)
                writer.writeFloor(out, 0, 0, 0, positions, uvs, normals, indices, 0)
            },
        )
        assertTrue("sink was not invoked", sinkCalled)
        assertTrue("output too small", out.size() > 100)
        // Atlas must be the last 4 bytes (or last 4 bytes of padded region).
        val tail = out.toByteArray().copyOfRange(out.size() - 4, out.size())
        assertArrayEquals(singleFloorAtlas().pngBytes, tail)
    }
}
