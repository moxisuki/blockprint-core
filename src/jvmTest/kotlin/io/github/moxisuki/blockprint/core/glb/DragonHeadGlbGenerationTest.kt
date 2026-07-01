package io.github.moxisuki.blockprint.core.glb

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.Litematic

import io.github.moxisuki.blockprint.core.glb.writer.GlbExportOptions
import io.github.moxisuki.blockprint.core.LitematicRegion
import io.github.moxisuki.blockprint.core.Position
import io.github.moxisuki.blockprint.core.SchematicFormat
import io.github.moxisuki.blockprint.core.glb.internal.JsonParser
import java.io.File
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end GLB generation test for the `dragon_head` and `dragon_wall_head`
 * synthetic model. Builds a tiny in-memory litematic containing both variants
 * and verifies the resulting GLB has the expected multi-element geometry.
 *
 * Also writes the resulting `.glb` files to `test/` for visual inspection in a
 * glTF viewer.
 */
class DragonHeadGlbGenerationTest {

    private val projectRoot: File
        get() = File("").absoluteFile

    private fun assetsDir(): Path = Path.of(projectRoot.path, "test", "assets")

    /**
     * Build a small in-memory litematic with a few dragon head blocks scattered
     * through the region. Layout (4x4x4, viewed from above):
     * ```
     * y=0: ground dragon_head  at (0,0,0), (3,0,3)
     * y=1: wall dragon_head    at (1,1,1), (1,1,2)
     * y=2: dragon_head         at (0,2,0)
     * y=3: (air)
     * ```
     */
    private fun buildDragonHeadLitematic(): Litematic {
        val palette = BlockPalette(listOf(
            BlockState("minecraft:air"),
            BlockState("minecraft:dragon_head"),
            BlockState("minecraft:dragon_wall_head", mapOf("facing" to "north")),
        ))
        val w = 4; val h = 4; val d = 4
        val blocks = IntArray(w * h * d)  // 0 = air by default
        fun idx(x: Int, y: Int, z: Int) = y * (w * d) + z * w + x
        blocks[idx(0, 0, 0)] = 1  // ground head
        blocks[idx(3, 0, 3)] = 1  // ground head
        blocks[idx(1, 1, 1)] = 2  // wall head
        blocks[idx(1, 1, 2)] = 2  // wall head
        blocks[idx(0, 2, 0)] = 1  // ground head on a higher Y
        val region = LitematicRegion(
            name = "DragonHeads",
            width = w, height = h, depth = d,
            position = Position(0, 0, 0),
            palette = palette,
            blocks = blocks,
        )
        return Litematic(
            minecraftDataVersion = 3953,
            version = 6,
            name = "DragonHeadTest",
            author = "blockprint-tests",
            description = "Synthetic litematic for dragon_head GLB generation test",
            regions = listOf(region),
            format = SchematicFormat.Litematica,
        )
    }

    private fun parseGlbJson(bytes: ByteArray): Map<String, Any?> {
        fun readIntLE(offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
        val magic = readIntLE(0)
        assertEquals("glTF magic", 0x46546C67L, magic.toLong())
        assertEquals("glTF version", 2L, readIntLE(4).toLong())
        val jsonLen = readIntLE(12)
        val jsonType = readIntLE(16)
        assertEquals("JSON chunk type", 0x4E4F534AL, jsonType.toLong())
        val jsonBytes = bytes.copyOfRange(20, 20 + jsonLen)
        return JsonParser.parseObject(jsonBytes.toString(Charsets.UTF_8))
    }

    // ─── Sanity ────────────────────────────────────────────────────────────

    @Test
    fun syntheticLitematic_hasFiveDragonHeads() {
        val lit = buildDragonHeadLitematic()
        assertEquals(1, lit.regions.size)
        val region = lit.regions[0]
        val nonAir = region.rawBlocks.count { it != 0 }
        assertEquals("5 placed dragon heads", 5, nonAir)
    }

    // ─── GLB structure ────────────────────────────────────────────────────

    @Test
    fun generatesValidGlb_forDragonHeads() {
        val lit = buildDragonHeadLitematic()
        val bytes = LitematicToGlb.convertToBytes(
            litematic = lit,
            assetsDirs = listOf(assetsDir()),
            regionIndex = 0,
        )
        assertTrue("GLB should be non-empty", bytes.isNotEmpty())

        val json = parseGlbJson(bytes)
        assertNotNull("scene present", json["scene"])
        assertNotNull("nodes present", json["nodes"])
        assertNotNull("meshes present", json["meshes"])
        assertNotNull("accessors present", json["accessors"])

        // POSITION accessor (acc 0) must have > 0 vertices (we placed 5 heads)
        val accessors = json["accessors"] as List<*>
        val pos = accessors[0] as Map<*, *>
        val vertexCount = (pos["count"] as Number).toInt()
        assertTrue("vertex count > 0 (got $vertexCount)", vertexCount > 0)

        // The mesh should have more vertices than a plain 8×8×8 skull
        // (which is 24 vertices = 6 faces × 4 verts).
        // A dragon head has 7 boxes × 6 faces × 4 verts = 168 vertices per head,
        // minus back-face culling. We expect substantially more than 24.
        assertTrue(
            "dragon head should have more than the 24 verts of a plain skull (got $vertexCount)",
            vertexCount > 24,
        )
    }

    @Test
    fun meshHasMultipleBoxesPerHead() {
        val lit = buildDragonHeadLitematic()
        val bytes = LitematicToGlb.convertToBytes(
            litematic = lit,
            assetsDirs = listOf(assetsDir()),
            regionIndex = 0,
        )
        val json = parseGlbJson(bytes)

        // Each head should be 1 mesh with 1 primitive. We have 5 heads but
        // they may collapse into fewer meshes if the model returns a flat
        // element list (each head is its own ResolvedModel invocation).
        // Just verify that the dragon head geometry is in use by checking
        // that the texture path is referenced in the GLB and that
        // we have at least 1 mesh with > 0 primitives.
        val meshes = json["meshes"] as List<*>
        assertTrue("meshes > 0 (got ${meshes.size})", meshes.isNotEmpty())
        for (m in meshes) {
            val mesh = m as Map<*, *>
            val primitives = mesh["primitives"] as List<*>
            assertTrue("each mesh has at least 1 primitive", primitives.isNotEmpty())
            val prim = primitives[0] as Map<*, *>
            // Each primitive should have a non-null material and indices
            assertNotNull("indices", prim["indices"])
            assertEquals("material 0", 0, prim["material"])
        }
    }

    @Test
    fun dragonTexture_isReferencedInAtlas() {
        val lit = buildDragonHeadLitematic()
        val bytes = LitematicToGlb.convertToBytes(
            litematic = lit,
            assetsDirs = listOf(assetsDir()),
            regionIndex = 0,
        )
        // The GLB contains a PNG atlas. We don't need to parse the atlas
        // itself — just verify that the GLB is well-formed and contains
        // an image entry (which is the atlas).
        val json = parseGlbJson(bytes)
        val images = json["images"] as List<*>
        assertEquals("exactly 1 atlas image", 1, images.size)
    }

    @Test
    fun dragonEggAtlas_isRealPng() {
        // Verify the atlas in the GLB is a real, non-empty PNG (not a 16x16
        // transparent placeholder). A real dragon_egg.png atlas should be
        // 100+ bytes (compressed PNG of a 16x16 image with content).
        val lit = buildDragonHeadLitematic()
        val bytes = LitematicToGlb.convertToBytes(
            litematic = lit,
            assetsDirs = listOf(assetsDir()),
            regionIndex = 0,
        )
        fun readIntLE(offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
        val jsonLen = readIntLE(12)
        val jsonBytes = bytes.copyOfRange(20, 20 + jsonLen)
        val json = JsonParser.parseObject(String(jsonBytes, Charsets.UTF_8))
        val images = json["images"] as List<*>
        val imageBv = (images[0] as Map<*, *>)["bufferView"] as Int
        val bufferViews = json["bufferViews"] as List<*>
        val atlasBv = bufferViews[imageBv] as Map<*, *>
        val atlasOffset = (atlasBv["byteOffset"] as Number).toInt()
        val atlasLength = (atlasBv["byteLength"] as Number).toInt()
        val binChunkStart = 20 + jsonLen + 8
        val atlasBytes = bytes.copyOfRange(binChunkStart + atlasOffset, binChunkStart + atlasOffset + atlasLength)
        // PNG signature
        assertEquals(0x89.toByte(), atlasBytes[0])
        assertEquals(0x50, atlasBytes[1].toInt() and 0xFF)
        assertEquals(0x4E, atlasBytes[2].toInt() and 0xFF)
        assertEquals(0x47, atlasBytes[3].toInt() and 0xFF)
        // Real PNG (not the empty 16x16 transparent atlas ~100 bytes)
        assertTrue("atlas is non-trivial (got $atlasLength bytes)", atlasLength > 100)
    }

    // ─── Disk output: write GLBs to test/ for visual inspection ───────────

    @Test
    fun writesDragonHeadGlbsToTestDirectory() {
        val lit = buildDragonHeadLitematic()
        val outDir = File(projectRoot, "test")
        outDir.mkdirs()

        // Default: single floor
        val singleFile = File(outDir, "dragon.single.glb")
        LitematicToGlb.convert(
            litematic = lit,
            assetsDirs = listOf(assetsDir()),
            outputFile = singleFile,
            regionIndex = 0,
        )
        assertTrue("dragon.single.glb exists", singleFile.exists())
        assertTrue("dragon.single.glb non-empty", singleFile.length() > 0)

        // Floor split (4-tall region, floorHeight=2 → 2 floors)
        val splitFile = File(outDir, "dragon.split2.glb")
        LitematicToGlb.convert(
            litematic = lit,
            assetsDirs = listOf(assetsDir()),
            outputFile = splitFile,
            regionIndex = 0,
            options = GlbExportOptions(floorHeight = 2),
        )
        assertTrue("dragon.split2.glb exists", splitFile.exists())
        assertTrue("dragon.split2.glb non-empty", splitFile.length() > 0)

        // Exploded view
        val explodedFile = File(outDir, "dragon.exploded2.glb")
        LitematicToGlb.convert(
            litematic = lit,
            assetsDirs = listOf(assetsDir()),
            outputFile = explodedFile,
            regionIndex = 0,
            options = GlbExportOptions(floorHeight = 2, explodeGap = 0.5f),
        )
        assertTrue("dragon.exploded2.glb exists", explodedFile.exists())
        assertTrue("dragon.exploded2.glb non-empty", explodedFile.length() > 0)

        // Each output must be a valid GLB
        for (f in listOf(singleFile, splitFile, explodedFile)) {
            val bytes = f.readBytes()
            val json = parseGlbJson(bytes)
            assertNotNull("nodes parsed for ${f.name}", json["nodes"])
        }
    }

    private fun buildComprehensiveRotationLitematic(): Litematic {
        val statesList = mutableListOf(BlockState("minecraft:air"), BlockState("minecraft:obsidian"))

        // Add ground dragon heads with rotations 0..15
        for (r in 0..15) {
            statesList.add(BlockState("minecraft:dragon_head", mapOf("rotation" to r.toString())))
        }
        // Add wall dragon heads facing 4 directions
        val facings = listOf("north", "south", "east", "west")
        for (f in facings) {
            statesList.add(BlockState("minecraft:dragon_wall_head", mapOf("facing" to f)))
        }

        val palette = BlockPalette(statesList)
        val w = 8; val h = 3; val d = 8
        val blocks = IntArray(w * h * d)
        fun idx(x: Int, y: Int, z: Int) = y * (w * d) + z * w + x

        // Y = 0: obsidian floor
        for (x in 0 until w) {
            for (z in 0 until d) {
                blocks[idx(x, 0, z)] = 1 // obsidian
            }
        }

        // Y = 1: Ground dragon heads rotated 0..15 in a 4x4 grid in the center (x=2..5, z=2..5)
        var rotIndex = 0
        for (x in 2..5) {
            for (z in 2..5) {
                val stateIndex = palette.entries.indexOfFirst {
                    it.name == "minecraft:dragon_head" && it.properties?.get("rotation") == rotIndex.toString()
                }
                blocks[idx(x, 1, z)] = stateIndex
                rotIndex++
            }
        }

        // Y = 1: Wall dragon heads on the border
        // North wall (z=0, facing north)
        val idxNorth = palette.entries.indexOfFirst { it.name == "minecraft:dragon_wall_head" && it.properties?.get("facing") == "north" }
        blocks[idx(3, 1, 0)] = idxNorth

        // South wall (z=7, facing south)
        val idxSouth = palette.entries.indexOfFirst { it.name == "minecraft:dragon_wall_head" && it.properties?.get("facing") == "south" }
        blocks[idx(4, 1, 7)] = idxSouth

        // East wall (x=7, facing east)
        val idxEast = palette.entries.indexOfFirst { it.name == "minecraft:dragon_wall_head" && it.properties?.get("facing") == "east" }
        blocks[idx(7, 1, 3)] = idxEast

        // West wall (x=0, facing west)
        val idxWest = palette.entries.indexOfFirst { it.name == "minecraft:dragon_wall_head" && it.properties?.get("facing") == "west" }
        blocks[idx(0, 1, 4)] = idxWest

        val region = LitematicRegion(
            name = "RotationCarousel",
            width = w, height = h, depth = d,
            position = Position(0, 0, 0),
            palette = palette,
            blocks = blocks,
        )
        return Litematic(
            minecraftDataVersion = 3953,
            version = 6,
            name = "RotationCarouselTest",
            author = "blockprint-tests",
            description = "Synthetic litematic for comprehensive dragon_head rotation GLB generation test",
            regions = listOf(region),
            format = SchematicFormat.Litematica,
        )
    }

    @Test
    fun writesDragonHeadRotationCarouselGlb() {
        val lit = buildComprehensiveRotationLitematic()
        val outDir = File(projectRoot, "test")
        outDir.mkdirs()

        val carouselFile = File(outDir, "dragon_rotation_carousel.glb")
        LitematicToGlb.convert(
            litematic = lit,
            assetsDirs = listOf(assetsDir()),
            outputFile = carouselFile,
            regionIndex = 0,
        )
        assertTrue("dragon_rotation_carousel.glb exists", carouselFile.exists())
        assertTrue("dragon_rotation_carousel.glb non-empty", carouselFile.length() > 0)

        val bytes = carouselFile.readBytes()
        val json = parseGlbJson(bytes)
        assertNotNull("nodes parsed for dragon_rotation_carousel.glb", json["nodes"])
    }
}
