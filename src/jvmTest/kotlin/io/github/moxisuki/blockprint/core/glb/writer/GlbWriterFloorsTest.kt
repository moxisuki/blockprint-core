package io.github.moxisuki.blockprint.core.glb.writer

import io.github.moxisuki.blockprint.core.glb.internal.JsonParser
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlbWriterFloorsTest {

    // The 1×1 white PNG bytes (smallest valid PNG) used as a stand-in atlas.
    // It is small enough to embed in the test and parseable by any PNG decoder.
    private val tinyAtlasPng: ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,  // signature
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,            // IHDR length + type
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,            // 1x1
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F.toByte(), 0x15, 0xC4.toByte(), 0x89.toByte(), // IHDR end
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41, 0x54,            // IDAT length + type
        0x78, 0x9C.toByte(), 0x62, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), // IDAT payload + crc
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,            // IEND length + type
        0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),                  // IEND crc
    )

    private fun writeAndExtractJson(floors: List<FloorSlice>, hasNormals: Boolean = true): Map<String, Any?> {
        val output = GlbOutput(
            floors = floors,
            atlasPng = tinyAtlasPng,
            atlasWidth = 1,
            atlasHeight = 1,
        )
        val bytes = ByteArrayOutputStream().use { stream ->
            GlbWriter().write(output, stream)
            stream.toByteArray()
        }
        // GLB layout: 12-byte header | 8-byte JSON chunk header | JSON bytes
        // Header fields are little-endian per the GLB 2.0 spec.
        val jsonLen = ((bytes[12].toInt() and 0xFF)) or
            ((bytes[13].toInt() and 0xFF) shl 8) or
            ((bytes[14].toInt() and 0xFF) shl 16) or
            ((bytes[15].toInt() and 0xFF) shl 24)
        val jsonBytes = bytes.copyOfRange(20, 20 + jsonLen)
        val json = jsonBytes.toString(Charsets.UTF_8)
        return JsonParser.parseObject(json)
    }

    private fun quadFloor(yMin: Int, yMax: Int, xOffset: Float): FloorSlice = FloorSlice(
        yMin = yMin, yMax = yMax,
        positions = floatArrayOf(
            xOffset, 0f, 0f,
            xOffset + 1f, 0f, 0f,
            xOffset + 1f, 1f, 0f,
            xOffset, 1f, 0f,
        ),
        uvs = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f),
        normals = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f),
        indices = intArrayOf(0, 1, 2, 0, 2, 3),
    )

    @Test
    fun singleFloor_emitsRootAndOneFloorNode() {
        val json = writeAndExtractJson(listOf(quadFloor(0, 9, 0f)))
        val nodes = json["nodes"] as List<*>
        assertEquals("1 root + 1 floor", 2, nodes.size)
        val root = nodes[0] as Map<*, *>
        val floor = nodes[1] as Map<*, *>
        val children = root["children"] as List<*>
        assertEquals(1, children.size)
        assertEquals("root.children[0] = node index 1", 1, children[0])
        assertEquals(0, floor["mesh"])
        val translation = floor["translation"] as List<*>
        assertEquals("explodeGap=0 → y=0", 0.0, (translation[1] as Number).toDouble(), 0.0)
    }

    @Test
    fun threeFloors_emitRootAndThreeFloorNodes_withZeroExplodeGap() {
        val json = writeAndExtractJson(
            listOf(
                quadFloor(0, 3, 0f),
                quadFloor(4, 7, 0f),
                quadFloor(8, 9, 0f),
            ),
        )
        val nodes = json["nodes"] as List<*>
        assertEquals("1 root + 3 floors", 4, nodes.size)
        for (i in 1..3) {
            val floor = nodes[i] as Map<*, *>
            val t = floor["translation"] as List<*>
            assertEquals(0.0, (t[0] as Number).toDouble(), 0.0)
            assertEquals(0.0, (t[1] as Number).toDouble(), 0.0)
            assertEquals(0.0, (t[2] as Number).toDouble(), 0.0)
        }
    }

    @Test
    fun bufferViews_are_independent_per_floor() {
        val json = writeAndExtractJson(listOf(quadFloor(0, 9, 0f), quadFloor(10, 19, 0f)))
        val bufferViews = json["bufferViews"] as List<*>
        // 2 × (positions, normals, uvs, indices) + atlas.
        assertEquals(9, bufferViews.size)
        assertNotNull((bufferViews[3] as Map<*, *>)["byteLength"])
        assertNotNull((bufferViews[7] as Map<*, *>)["byteLength"])
    }

    @Test
    fun meshCount_matchesFloorCount_andEachPrimitiveHasOneIndicesAccessor() {
        val json = writeAndExtractJson(listOf(quadFloor(0, 9, 0f), quadFloor(10, 19, 0f)))
        val meshes = json["meshes"] as List<*>
        assertEquals(2, meshes.size)
        for (m in meshes) {
            val mesh = m as Map<*, *>
            val primitives = mesh["primitives"] as List<*>
            assertEquals("1 primitive per floor mesh", 1, primitives.size)
            val prim = primitives[0] as Map<*, *>
            val attrs = prim["attributes"] as Map<*, *>
            assertTrue("POSITION" in attrs)
            assertTrue("TEXCOORD_0" in attrs)
            assertNotNull(prim["indices"])
            assertEquals(0, prim["material"])
        }
    }

    @Test
    fun eachPositionAccessorHasItsOwnMinMax() {
        val json = writeAndExtractJson(listOf(
            quadFloor(0, 3, 0f),    // x in [0, 1]
            quadFloor(4, 7, 10f),   // x in [10, 11]
        ))
        val accessors = json["accessors"] as List<*>
        val pos = accessors[0] as Map<*, *>
        val min = pos["min"] as List<*>
        val max = pos["max"] as List<*>
        assertEquals(0.0, (min[0] as Number).toDouble(), 0.0)
        assertEquals(1.0, (max[0] as Number).toDouble(), 0.0)
        val secondPos = accessors[4] as Map<*, *>
        val secondMin = secondPos["min"] as List<*>
        val secondMax = secondPos["max"] as List<*>
        assertEquals(10.0, (secondMin[0] as Number).toDouble(), 0.0)
        assertEquals(11.0, (secondMax[0] as Number).toDouble(), 0.0)
    }

    @Test
    fun multiFloor_binaryOffsetsPointAtEachFloorsOwnPositionsAndLocalIndices() {
        val floors = listOf(quadFloor(0, 3, 2f), quadFloor(4, 7, 10f))
        val bytes = ByteArrayOutputStream().use { stream ->
            GlbWriter().write(GlbOutput(floors, tinyAtlasPng, 1, 1), stream)
            stream.toByteArray()
        }
        val jsonLen = java.nio.ByteBuffer.wrap(bytes, 12, 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        val json = JsonParser.parseObject(bytes.copyOfRange(20, 20 + jsonLen).toString(Charsets.UTF_8))
        val binStart = 20 + jsonLen + 8
        val accessors = json["accessors"] as List<*>
        val views = json["bufferViews"] as List<*>
        val meshes = json["meshes"] as List<*>

        for ((floorIndex, expectedX) in listOf(2f, 10f).withIndex()) {
            val primitive = (((meshes[floorIndex] as Map<*, *>)["primitives"] as List<*>)[0] as Map<*, *>)
            val attributes = primitive["attributes"] as Map<*, *>
            val positionAccessor = accessors[(attributes["POSITION"] as Number).toInt()] as Map<*, *>
            val positionView = views[(positionAccessor["bufferView"] as Number).toInt()] as Map<*, *>
            val positionOffset = (positionView["byteOffset"] as Number).toInt()
            val actualX = java.nio.ByteBuffer.wrap(bytes, binStart + positionOffset, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).float
            assertEquals(expectedX.toDouble(), actualX.toDouble(), 0.0)

            val indexAccessor = accessors[(primitive["indices"] as Number).toInt()] as Map<*, *>
            val indexView = views[(indexAccessor["bufferView"] as Number).toInt()] as Map<*, *>
            val indexOffset = (indexView["byteOffset"] as Number).toInt()
            val firstIndex = java.nio.ByteBuffer.wrap(bytes, binStart + indexOffset, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            assertEquals("floor indices stay local", 0, firstIndex)
        }
    }

    @Test
    fun explodeGap_appearsInPerFloorTranslation() {
        val output = GlbOutput(
            floors = listOf(quadFloor(0, 3, 0f), quadFloor(4, 7, 0f), quadFloor(8, 9, 0f)),
            atlasPng = tinyAtlasPng,
            atlasWidth = 1,
            atlasHeight = 1,
        )
        val bytes = ByteArrayOutputStream().use { stream ->
            GlbWriter().write(output, stream, GlbExportOptions(explodeGap = 2.5f))
            stream.toByteArray()
        }
        val jsonLen = ((bytes[12].toInt() and 0xFF)) or
            ((bytes[13].toInt() and 0xFF) shl 8) or
            ((bytes[14].toInt() and 0xFF) shl 16) or
            ((bytes[15].toInt() and 0xFF) shl 24)
        val jsonBytes = bytes.copyOfRange(20, 20 + jsonLen)
        val json = JsonParser.parseObject(jsonBytes.toString(Charsets.UTF_8))
        val nodes = json["nodes"] as List<*>
        for (i in 1..3) {
            val t = (nodes[i] as Map<*, *>)["translation"] as List<*>
            val expected = (i - 1) * 2.5
            assertEquals("floor ${i - 1} y translation", expected, (t[1] as Number).toDouble(), 0.0)
        }
    }
}
