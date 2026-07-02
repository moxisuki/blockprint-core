package io.github.moxisuki.blockprint.core.glb.writer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlbOutputTest {
    @Test
    fun floors_alwaysList_evenForSingleFloor() {
        val output = GlbOutput(
            floors = listOf(
                FloorSlice(
                    yMin = 0, yMax = 9,
                    positions = floatArrayOf(0f, 0f, 0f),
                    uvs = floatArrayOf(0f, 0f),
                    normals = null,
                    indices = intArrayOf(0),
                ),
            ),
            atlasPng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
            atlasWidth = 16,
            atlasHeight = 16,
        )
        assertEquals(1, output.floors.size)
        assertEquals(0, output.floors[0].yMin)
        assertEquals(9, output.floors[0].yMax)
        assertNull(output.floors[0].normals)
        assertEquals(16, output.atlasWidth)
    }

    @Test
    fun floors_canBeEmpty() {
        val output = GlbOutput(
            floors = emptyList(),
            atlasPng = byteArrayOf(),
            atlasWidth = 0,
            atlasHeight = 0,
        )
        assertTrue(output.floors.isEmpty())
    }

    @Test
    fun floorSlice_carriesYRange_andLocalIndices() {
        val slice = FloorSlice(
            yMin = 4, yMax = 7,
            positions = floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f),
            uvs = floatArrayOf(0f, 0f, 1f, 1f),
            normals = floatArrayOf(0f, 1f, 0f, 0f, 1f, 0f),
            indices = intArrayOf(0, 1),
        )
        assertEquals("2 verts × 3 floats", 6, slice.positions.size)
        assertEquals(2, slice.indices.size)
    }
}
