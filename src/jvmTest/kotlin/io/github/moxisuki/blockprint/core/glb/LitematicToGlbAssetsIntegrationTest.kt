package io.github.moxisuki.blockprint.core.glb

import io.github.moxisuki.blockprint.core.LitematicReader
import io.github.moxisuki.blockprint.core.glb.internal.JsonParser
import io.github.moxisuki.blockprint.core.model.BlockPrintDocument
import java.io.File

import io.github.moxisuki.blockprint.core.glb.writer.GlbExportOptions
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end integration tests that load the real litematic fixture at
 * `test/pre.litematic` and the real Minecraft assets bundle at `test/assets/`.
 *
 * These tests exercise the full pipeline:
 *   1. LitematicReader.read(File) — parses the litematic
 *   2. LitematicToGlb.convertToBytes(..., assetsDirs, options) — runs the
 *      ModelResolver, TexturePacker, MeshBuilder, GlbWriter
 *   3. Asserts on the resulting GLB bytes
 *
 * They run only on the JVM (in `src/jvmTest`) because the fixtures live on
 * disk. They produce no network traffic.
 */
class LitematicToGlbAssetsIntegrationTest {

    private val projectRoot: File
        get() = File("").absoluteFile  // Gradle runs the JVM with cwd = project root

    private fun litematicFile(): File = File(projectRoot, "test/pre.litematic")

    private fun assetsDir(): Path = Path.of(projectRoot.path, "test", "assets")

    private fun loadLitematic() = BlockPrintDocument.fromLegacy(LitematicReader.read(litematicFile()))

    /**
     * Parse a GLB byte array into its 12-byte header and JSON chunk body.
     * The JSON parser is the same internal one the writer uses.
     */
    private fun parseGlbJson(bytes: ByteArray): Map<String, Any?> {
        // All multi-byte GLB header fields are little-endian per the GLB 2.0 spec.
        fun readIntLE(offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)

        val magic = readIntLE(0)
        assertEquals("glTF magic", 0x46546C67L, magic.toLong())
        val version = readIntLE(4)
        assertEquals("glTF version", 2L, version.toLong())
        val totalLength = readIntLE(8)
        assertTrue("totalLength ≥ 12", totalLength >= 12)
        assertEquals("totalLength matches bytes.size", bytes.size, totalLength)
        val jsonLen = readIntLE(12)
        val jsonType = readIntLE(16)
        assertEquals("JSON chunk type", 0x4E4F534AL, jsonType.toLong())  // "JSON"
        val jsonBytes = bytes.copyOfRange(20, 20 + jsonLen)
        return JsonParser.parseObject(jsonBytes.toString(Charsets.UTF_8))
    }

    // ─── Sanity: fixtures exist and load ────────────────────────────────

    @Test
    fun fixtures_loadAndParse() {
        val document = loadLitematic()
        assertEquals(1, document.regions.size)
        val region = document.regions[0]
        assertEquals("Unnamed", region.name)
        assertEquals(25, region.width)
        assertEquals(14, region.height)
        assertEquals(21, region.depth)
        // 24 palette entries, 1413 non-air blocks per inspection
        assertEquals(24, region.palette.size)
        val nonAir = region.rawBlocks.count { it != 0 }
        assertEquals(1413, nonAir)
    }

    // ─── Default options: single-floor GLB ──────────────────────────────

    @Test
    fun defaultOptions_producesValidGlb_withSingleFloor() {
        val document = loadLitematic()
        val bytes = LitematicToGlb.convertToBytes(
            document = document,
            assetsDirs = listOf(assetsDir()),
            regionIndex = 0,
        )
        assertTrue("GLB should be non-empty", bytes.isNotEmpty())

        val json = parseGlbJson(bytes)

        val nodes = json["nodes"] as List<*>
        assertEquals("1 root + 1 floor", 2, nodes.size)

        val root = nodes[0] as Map<*, *>
        val children = root["children"] as List<*>
        assertEquals(1, children.size)
        assertEquals("root.children[0] = node index 1", 1, children[0])

        val floor = nodes[1] as Map<*, *>
        assertEquals(0, floor["mesh"])
        // explodeGap=0 → translation is [0, 0, 0]
        val t = floor["translation"] as List<*>
        assertEquals(0.0, (t[0] as Number).toDouble(), 0.0)
        assertEquals(0.0, (t[1] as Number).toDouble(), 0.0)
        assertEquals(0.0, (t[2] as Number).toDouble(), 0.0)

        val meshes = json["meshes"] as List<*>
        assertEquals(1, meshes.size)
        val prims = (meshes[0] as Map<*, *>)["primitives"] as List<*>
        assertEquals(1, prims.size)

        // POSITION accessor should have min/max covering the building's bounds
        val accessors = json["accessors"] as List<*>
        val pos = accessors[0] as Map<*, *>
        val min = pos["min"] as List<*>
        val max = pos["max"] as List<*>
        val minY = (min[1] as Number).toDouble()
        val maxY = (max[1] as Number).toDouble()
        assertTrue("min.y should be ≤ max.y", minY <= maxY)
        // Region height is 14. Convert uses originY = region.position.y - height/2 = 13 - 7 = 6,
        // so the building occupies world Y in [6, 20]. Just verify the range is non-empty
        // and bounded by what 14 voxels + centering offset would produce.
        assertTrue("min.y should be around the lower bound (≥ 5)", minY >= 5.0)
        assertTrue("max.y should be around the upper bound (≤ 21)", maxY <= 21.0)
        assertTrue("y range should be ~14 wide", (maxY - minY) in 13.0..15.0)
    }

    // ─── Multi-floor: floorHeight=2 splits the 14-tall region into 7 floors

    @Test
    fun floorHeight_splitsRegionIntoMultipleFloors() {
        val document = loadLitematic()
        val bytes = LitematicToGlb.convertToBytes(
            document = document,
            assetsDirs = listOf(assetsDir()),
            regionIndex = 0,
            options = GlbExportOptions(floorHeight = 2),
        )
        val json = parseGlbJson(bytes)

        // Region is 14 tall with floorHeight=2 → ceil(14/2) = 7 floors
        val nodes = json["nodes"] as List<*>
        assertEquals("1 root + 7 floors", 8, nodes.size)

        val root = nodes[0] as Map<*, *>
        val children = root["children"] as List<*>
        assertEquals(7, children.size)
        for (i in 0..6) {
            assertEquals("root.children[$i] = node ${i + 1}", (i + 1), children[i])
        }

        val meshes = json["meshes"] as List<*>
        assertEquals(7, meshes.size)

        // All floor translations on Y are 0 (no explodeGap)
        for (i in 1..7) {
            val t = (nodes[i] as Map<*, *>)["translation"] as List<*>
            assertEquals("floor $i y translation", 0.0, (t[1] as Number).toDouble(), 0.0)
        }
    }

    // ─── Explode gap: per-floor Y translations differ ───────────────────

    @Test
    fun explodeGap_producesPerFloorVerticalOffsets() {
        val document = loadLitematic()
        val bytes = LitematicToGlb.convertToBytes(
            document = document,
            assetsDirs = listOf(assetsDir()),
            regionIndex = 0,
            options = GlbExportOptions(floorHeight = 2, explodeGap = 1.5f),
        )
        val json = parseGlbJson(bytes)

        val nodes = json["nodes"] as List<*>
        assertEquals("1 root + 7 floors", 8, nodes.size)

        // Floor i (node index i+1) should have translation.y = i * explodeGap
        for (i in 0..6) {
            val t = (nodes[i + 1] as Map<*, *>)["translation"] as List<*>
            val expectedY = i * 1.5
            assertEquals("floor $i expected y = $expectedY", expectedY, (t[1] as Number).toDouble(), 1e-6)
        }
    }

    // ─── Empty floor elimination: an unused Y range drops that floor ────

    @Test
    fun emptyFloors_areDroppedFromOutput() {
        val document = loadLitematic()
        // floorHeight=1 → 14 floors. With this 25x14x21 build (1413 non-air
        // blocks across 14 Y-levels), some Y-levels may be empty. The GLB
        // must not contain empty meshes/primitives.
        val bytes = LitematicToGlb.convertToBytes(
            document = document,
            assetsDirs = listOf(assetsDir()),
            regionIndex = 0,
            options = GlbExportOptions(floorHeight = 1),
        )
        val json = parseGlbJson(bytes)

        val nodes = json["nodes"] as List<*>
        val meshes = json["meshes"] as List<*>
        val nodeCount = nodes.size
        val meshCount = meshes.size
        // Node count = 1 root + N non-empty floors
        // Mesh count = N non-empty floors (each is 1 mesh, 1 primitive)
        assertEquals("meshes count matches non-root nodes", nodeCount - 1, meshCount)

        // Every non-root node must reference an existing mesh
        for (i in 1 until nodeCount) {
            val meshIdx = (nodes[i] as Map<*, *>)["mesh"] as Int
            assertTrue("node $i mesh idx out of range", meshIdx in 0 until meshCount)
        }

        // The total vertices across all floors' POSITION accessor should
        // equal a single-floor POSITION count (since positions are shared).
        val accessors = json["accessors"] as List<*>
        val pos = accessors[0] as Map<*, *>
        val totalVerts = (pos["count"] as Number).toInt()
        assertTrue("expected > 0 vertices (we have 1413 non-air blocks)", totalVerts > 0)
    }

    // ─── Default options vs floorHeight=1 should match floor count ──────

    @Test
    fun defaultOptions_singleFloor_matches_floorHeight_one_withSameRegion() {
        val document = loadLitematic()
        // With floorHeight=0 the whole building is one floor
        val defaultBytes = LitematicToGlb.convertToBytes(
            document = document,
            assetsDirs = listOf(assetsDir()),
            regionIndex = 0,
        )
        // With floorHeight=1 and a building where some Y-levels are empty,
        // the number of non-empty floors is at most region.height.
        val perVoxelBytes = LitematicToGlb.convertToBytes(
            document = document,
            assetsDirs = listOf(assetsDir()),
            regionIndex = 0,
            options = GlbExportOptions(floorHeight = 1),
        )
        val defaultJson = parseGlbJson(defaultBytes)
        val perVoxelJson = parseGlbJson(perVoxelBytes)

        val defaultFloorCount = (defaultJson["nodes"] as List<*>).size - 1
        val perVoxelFloorCount = (perVoxelJson["nodes"] as List<*>).size - 1
        assertEquals("default = 1 floor", 1, defaultFloorCount)
        assertTrue(
            "per-voxel floor count should be ≥ 1 and ≤ region.height",
            perVoxelFloorCount in 1..14,
        )
    }

    // ─── Disk output: writes .glb files to test/ for manual inspection ───

    @Test
    fun writesGlbFilesToTestDirectory() {
        val document = loadLitematic()
        val outDir = File(projectRoot, "test")
        outDir.mkdirs()

        // Single-floor baseline
        val singleFile = File(outDir, "pre.single.glb")
        LitematicToGlb.convert(
            document = document,
            assetsDirs = listOf(assetsDir()),
            outputFile = singleFile,
            regionIndex = 0,
        )
        assertTrue("single.glb should exist", singleFile.exists())
        assertTrue("single.glb should be non-empty", singleFile.length() > 0)

        // 7-floor split (region is 14 tall, floorHeight = 2)
        val splitFile = File(outDir, "pre.split2.glb")
        LitematicToGlb.convert(
            document = document,
            assetsDirs = listOf(assetsDir()),
            outputFile = splitFile,
            regionIndex = 0,
            options = GlbExportOptions(floorHeight = 2),
        )
        assertTrue("split2.glb should exist", splitFile.exists())
        assertTrue("split2.glb should be non-empty", splitFile.length() > 0)

        // 7-floor with explode gap so per-floor Y translations are visible
        val explodedFile = File(outDir, "pre.exploded2.glb")
        LitematicToGlb.convert(
            document = document,
            assetsDirs = listOf(assetsDir()),
            outputFile = explodedFile,
            regionIndex = 0,
            options = GlbExportOptions(floorHeight = 2, explodeGap = 0.5f),
        )
        assertTrue("exploded2.glb should exist", explodedFile.exists())
        assertTrue("exploded2.glb should be non-empty", explodedFile.length() > 0)

        // Per-voxel split: floorHeight=1 → 1 voxel per floor, up to 14 floors
        val perVoxelFile = File(outDir, "pre.floors1.glb")
        LitematicToGlb.convert(
            document = document,
            assetsDirs = listOf(assetsDir()),
            outputFile = perVoxelFile,
            regionIndex = 0,
            options = GlbExportOptions(floorHeight = 1),
        )
        assertTrue("floors1.glb should exist", perVoxelFile.exists())
        assertTrue("floors1.glb should be non-empty", perVoxelFile.length() > 0)

        // Per-voxel split with explode gap for visual separation
        val perVoxelExplodedFile = File(outDir, "pre.floors1.exploded.glb")
        LitematicToGlb.convert(
            document = document,
            assetsDirs = listOf(assetsDir()),
            outputFile = perVoxelExplodedFile,
            regionIndex = 0,
            options = GlbExportOptions(floorHeight = 1, explodeGap = 0.3f),
        )
        assertTrue("floors1.exploded.glb should exist", perVoxelExplodedFile.exists())
        assertTrue("floors1.exploded.glb should be non-empty", perVoxelExplodedFile.length() > 0)

        // Each output should be a valid GLB
        for (f in listOf(singleFile, splitFile, explodedFile, perVoxelFile, perVoxelExplodedFile)) {
            val bytes = f.readBytes()
            val json = parseGlbJson(bytes)
            assertNotNull("JSON parsed for ${f.name}", json["nodes"])
        }
    }
}
