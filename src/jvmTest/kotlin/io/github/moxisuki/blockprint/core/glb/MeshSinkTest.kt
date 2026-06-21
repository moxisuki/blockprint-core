package io.github.moxisuki.blockprint.core.glb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MeshSinkTest {

    @Test
    fun floorStats_dataClass_equality() {
        val a = FloorStats(
            floorCount = 2,
            perFloorVertices = intArrayOf(100, 200),
            perFloorIndices = intArrayOf(150, 300),
            totalPositions = 900,
            totalNormals = 900,
            totalUvs = 600,
            totalIndices = 450,
            minX = 0f, minY = 0f, minZ = 0f,
            maxX = 1f, maxY = 1f, maxZ = 1f,
        )
        val b = FloorStats(
            floorCount = 2,
            perFloorVertices = intArrayOf(100, 200),
            perFloorIndices = intArrayOf(150, 300),
            totalPositions = 900,
            totalNormals = 900,
            totalUvs = 600,
            totalIndices = 450,
            minX = 0f, minY = 0f, minZ = 0f,
            maxX = 1f, maxY = 1f, maxZ = 1f,
        )
        val c = a.copy(perFloorVertices = intArrayOf(101, 200))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun floorStats_perFloor_arrays_are_array_equals() {
        val a = FloorStats(1, intArrayOf(10), intArrayOf(20), 30, 30, 20, 30,
            0f, 0f, 0f, 1f, 1f, 1f)
        val b = FloorStats(1, intArrayOf(10), intArrayOf(20), 30, 30, 20, 30,
            0f, 0f, 0f, 1f, 1f, 1f)
        assertEquals(a, b)
        assertArrayEquals(a.perFloorVertices, b.perFloorVertices)
    }

    @Test
    fun floorSink_funInterface_is_sam_compatible() {
        val received = mutableListOf<Int>()
        val sink: FloorSink = FloorSink { floorIdx, _, _, _, _, _, _ ->
            received.add(floorIdx)
        }
        sink.onFloor(0, 0, 0, FloatArray(0), FloatArray(0), null, IntArray(0))
        sink.onFloor(1, 0, 0, FloatArray(0), FloatArray(0), null, IntArray(0))
        assertEquals(listOf(0, 1), received)
    }

    @Test
    fun glbAtlas_dataClass_equality() {
        val a = GlbAtlas(pngBytes = byteArrayOf(1, 2, 3), width = 64, height = 64)
        val b = GlbAtlas(pngBytes = byteArrayOf(1, 2, 3), width = 64, height = 64)
        val c = a.copy(width = 128)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }
}
