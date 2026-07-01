package io.github.moxisuki.blockprint.core.glb.synthetic

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.Litematic
import io.github.moxisuki.blockprint.core.LitematicRegion
import io.github.moxisuki.blockprint.core.Position
import io.github.moxisuki.blockprint.core.SchematicFormat
import io.github.moxisuki.blockprint.core.glb.LitematicToGlb
import io.github.moxisuki.blockprint.core.glb.model.CreateModObjAdapter
import io.github.moxisuki.blockprint.core.glb.model.ModelResolver
import io.github.moxisuki.blockprint.core.glb.mesh.MeshBuilder
import io.github.moxisuki.blockprint.core.glb.internal.JsonParser
import java.io.File
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end integration test that creates a synthetic Litematic containing
 * all of our synthetic blocks, converts it to a GLB file, and writes it to the
 * `test/` directory for manual and automated verification.
 */
class AllSyntheticBlocksGlbTest {

    private val projectRoot: File
        get() = File("").absoluteFile

    private fun assetsDir(): Path = Path.of(projectRoot.path, "test", "assets")

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

    private fun buildAllSyntheticBlocksLitematic(): Litematic {
        val statesList = mutableListOf(
            BlockState("minecraft:air"),
            BlockState("minecraft:obsidian"), // Floor base

            // 1. Bed (SyntheticBed) - idx 2 and 3
            BlockState("minecraft:red_bed", mapOf("part" to "foot", "facing" to "north")),
            BlockState("minecraft:red_bed", mapOf("part" to "head", "facing" to "north")),

            // 2. Chest (SyntheticChest) - idx 4 and 5
            BlockState("minecraft:chest", mapOf("facing" to "north")),
            BlockState("minecraft:ender_chest", mapOf("facing" to "south")),

            // 3. Banner / Wall Banner (SyntheticBanner) - idx 6 and 7
            BlockState("minecraft:red_banner", mapOf("rotation" to "4")),
            BlockState("minecraft:blue_wall_banner", mapOf("facing" to "south")),

            // 4. Conduit (SyntheticConduit) - idx 8
            BlockState("minecraft:conduit"),

            // 5. Decorated Pot (SyntheticDecoratedPot) - idx 9
            BlockState("minecraft:decorated_pot"),

            // 6. Ender Dragon Head (SyntheticEnderDragonHead) - idx 10 and 11
            BlockState("minecraft:dragon_head", mapOf("rotation" to "8")),
            BlockState("minecraft:dragon_wall_head", mapOf("facing" to "south")),

            // 7. Fluid (SyntheticFluid) - idx 12
            BlockState("minecraft:water", mapOf("level" to "0")),

            // 8. Lectern (SyntheticLectern) - idx 13
            BlockState("minecraft:lectern", mapOf("facing" to "north")),

            // 9. Shulker Box (SyntheticShulkerBox) - idx 14
            BlockState("minecraft:shulker_box", mapOf("facing" to "up")),

            // 10. Sign / Wall Sign (SyntheticSign) - idx 15 and 16
            BlockState("minecraft:oak_sign", mapOf("rotation" to "12")),
            BlockState("minecraft:oak_wall_sign", mapOf("facing" to "south")),

            // 11. Skull (SyntheticSkull) - idx 17 and 18
            BlockState("minecraft:skeleton_skull", mapOf("rotation" to "0")),
            BlockState("minecraft:zombie_wall_head", mapOf("facing" to "south"))
        )

        val palette = BlockPalette(statesList)
        val w = 10; val h = 3; val d = 12
        val blocks = IntArray(w * h * d)
        fun idx(x: Int, y: Int, z: Int) = y * (w * d) + z * w + x

        // Y = 0: obsidian floor
        for (x in 0 until w) {
            for (z in 0 until d) {
                blocks[idx(x, 0, z)] = 1 // obsidian
            }
        }

        // Y = 1: Obsidian wall at Z = 5 spanning X = 1..6
        for (x in 1..6) {
            blocks[idx(x, 1, 5)] = 1
            blocks[idx(x, 2, 5)] = 1 // 2-tall wall
        }

        // Place Bed: aligned along Z-axis (head at Z=2, foot at Z=3)
        blocks[idx(1, 1, 2)] = 3 // head
        blocks[idx(1, 1, 3)] = 2 // foot

        // Place Chests
        blocks[idx(3, 1, 2)] = 4 // chest
        blocks[idx(4, 1, 2)] = 5 // ender chest

        // Place Shulker Box & Lectern
        blocks[idx(5, 1, 2)] = 14 // shulker box
        blocks[idx(6, 1, 2)] = 13 // lectern

        // Place Wall-mounted blocks at Z = 6 (facing south, backing to the wall at Z = 5)
        blocks[idx(1, 1, 6)] = 7  // blue_wall_banner
        blocks[idx(2, 1, 6)] = 11 // dragon_wall_head
        blocks[idx(3, 1, 6)] = 16 // oak_wall_sign
        blocks[idx(4, 1, 6)] = 18 // zombie_wall_head

        // Place Ground standalone blocks at Z = 8
        blocks[idx(1, 1, 8)] = 6  // red_banner
        blocks[idx(2, 1, 8)] = 8  // conduit
        blocks[idx(3, 1, 8)] = 9  // decorated_pot
        blocks[idx(4, 1, 8)] = 10 // dragon_head
        blocks[idx(5, 1, 8)] = 15 // oak_sign
        blocks[idx(6, 1, 8)] = 17 // skeleton_skull
        blocks[idx(7, 1, 8)] = 12 // water

        val region = LitematicRegion(
            name = "AllSyntheticBlocks",
            width = w, height = h, depth = d,
            position = Position(0, 0, 0),
            palette = palette,
            blocks = blocks,
        )
        return Litematic(
            minecraftDataVersion = 3953,
            version = 6,
            name = "AllSyntheticBlocksTest",
            author = "blockprint-tests",
            description = "Synthetic litematic containing all synthetic block types",
            regions = listOf(region),
            format = SchematicFormat.Litematica,
        )
    }

    @Test
    fun writesAllSyntheticBlocksGlbToTestDirectory() {
        val lit = buildAllSyntheticBlocksLitematic()
        val outDir = File(projectRoot, "test")
        outDir.mkdirs()

        val outputFile = File(outDir, "all_synthetic_blocks.glb")
        
        // Let's run convert and also capture the build output to extract the atlas
        val region = lit.regions[0]
        val modelResolver = io.github.moxisuki.blockprint.core.glb.model.ModelResolver(listOf(assetsDir()))
        val texturePacker = io.github.moxisuki.blockprint.core.glb.texture.TexturePacker(listOf(assetsDir()))
        val meshBuilder = io.github.moxisuki.blockprint.core.glb.mesh.MeshBuilder(modelResolver, texturePacker, enableTinting = true)
        val originX = region.position.x - region.width / 2
        val originY = region.position.y - region.height / 2
        val originZ = region.position.z - region.depth / 2
        val output = meshBuilder.build(region, originX, originY, originZ)
        
        val atlasFile = File(outDir, "all_synthetic_blocks_atlas.png")
        atlasFile.writeBytes(output.atlasPng)
        println("Saved atlas PNG to ${atlasFile.absolutePath}")

        LitematicToGlb.convert(
            litematic = lit,
            assetsDirs = listOf(assetsDir()),
            outputFile = outputFile,
            regionIndex = 0,
        )
        assertTrue("all_synthetic_blocks.glb exists", outputFile.exists())
        assertTrue("all_synthetic_blocks.glb non-empty", outputFile.length() > 0)

        val bytes = outputFile.readBytes()
        val json = parseGlbJson(bytes)
        assertNotNull("nodes parsed for all_synthetic_blocks.glb", json["nodes"])
    }

    @Test
    fun convertUserNbtToGlb() {
        val nbtFile = File(projectRoot, "test/6.0懒人刷铁机 (1).nbt")
        val glbFile = File(projectRoot, "test/6.0懒人刷铁机 (1).glb")
        assertTrue("NBT file should exist at ${nbtFile.absolutePath}", nbtFile.exists())
        
        val litematic = io.github.moxisuki.blockprint.core.LitematicReader.readLenient(nbtFile)
        val region = litematic.regions[0]
        println("BLOCK PALETTE:")
        region.palette.entries.forEach { block ->
            println(" - ${block.name} with props: ${block.properties}")
        }
        
        val assetsList = listOf(
            java.nio.file.Path.of(projectRoot.path, "test", "assets"),
            java.nio.file.Path.of(projectRoot.path, "test", "create", "assets")
        )
        val resolver = ModelResolver(assetsList)
        
        println("RESOLVING LARGE WATER WHEELS:")
        val baseBlock = io.github.moxisuki.blockprint.core.BlockState("create:large_water_wheel", mapOf("extension" to "false", "axis" to "x"))
        val baseModel = resolver.resolve(baseBlock.name, baseBlock.properties)
        println("Base Model (${baseBlock.properties}): has ${baseModel.rawMeshes.size} raw meshes:")
        baseModel.rawMeshes.forEach { mesh ->
            println("  - Mesh texture: ${mesh.texture}, verts: ${mesh.positions.size / 3}")
        }
        
        val extBlock = io.github.moxisuki.blockprint.core.BlockState("create:large_water_wheel", mapOf("extension" to "true", "axis" to "x"))
        val extModel = resolver.resolve(extBlock.name, extBlock.properties)
        println("Extension Model (${extBlock.properties}): has ${extModel.rawMeshes.size} raw meshes:")
        extModel.rawMeshes.forEach { mesh ->
            println("  - Mesh texture: ${mesh.texture}, verts: ${mesh.positions.size / 3}")
        }

        println("RESOLVING GEARBOX AND ENCASED SHAFT FOR DEBUG:")
        val gbBlock = io.github.moxisuki.blockprint.core.BlockState("create:gearbox", mapOf("axis" to "x"))
        val gbModel = resolver.resolve(gbBlock.name, gbBlock.properties)
        println("Gearbox (${gbBlock.properties}): rotX=${gbModel.rotX}, rotY=${gbModel.rotY}, elements=${gbModel.elements.size}")
        gbModel.elements.forEachIndexed { idx, elem ->
            println("  - Elem $idx: name=${elem.faces.keys.firstOrNull()}, modelRotX=${elem.modelRotX}, modelRotY=${elem.modelRotY}, from=${elem.from}, to=${elem.to}")
        }

        val esBlock = io.github.moxisuki.blockprint.core.BlockState("create:andesite_encased_shaft", mapOf("axis" to "z"))
        val esModel = resolver.resolve(esBlock.name, esBlock.properties)
        println("Encased Shaft (${esBlock.properties}): rotX=${esModel.rotX}, rotY=${esModel.rotY}, elements=${esModel.elements.size}")
        esModel.elements.forEachIndexed { idx, elem ->
            println("  - Elem $idx: name=${elem.faces.keys.firstOrNull()}, modelRotX=${elem.modelRotX}, modelRotY=${elem.modelRotY}, from=${elem.from}, to=${elem.to}")
        }

        println("RESOLVING ANDESITE FUNNEL (DOWN) FOR DEBUG:")
        val afBlock = io.github.moxisuki.blockprint.core.BlockState("create:andesite_funnel", mapOf("facing" to "down", "powered" to "false", "extracting" to "false"))
        val afModel = resolver.resolve(afBlock.name, afBlock.properties)
        println("Andesite Funnel (facing=down): rotX=${afModel.rotX}, rotY=${afModel.rotY}, elements=${afModel.elements.size}")
        afModel.elements.forEachIndexed { idx, elem ->
            println("  - Elem $idx: from=${elem.from}, to=${elem.to}, rot=${elem.rotation?.let { "${it.axis} ${it.angle} @ ${it.origin}" } ?: "null"}")
        }

        println("RESOLVING ANDESITE FUNNEL (NORTH) FOR DEBUG:")
        val afNorthBlock = io.github.moxisuki.blockprint.core.BlockState("create:andesite_funnel", mapOf("facing" to "north", "powered" to "false", "extracting" to "false"))
        val afNorthModel = resolver.resolve(afNorthBlock.name, afNorthBlock.properties)
        println("Andesite Funnel (facing=north): rotX=${afNorthModel.rotX}, rotY=${afNorthModel.rotY}, elements=${afNorthModel.elements.size}")
        afNorthModel.elements.forEachIndexed { idx, elem ->
            println("  - Elem $idx: from=${elem.from}, to=${elem.to}, rot=${elem.rotation?.let { "${it.axis} ${it.angle} @ ${it.origin}" } ?: "null"}")
        }

        println("RESOLVING BELT FUNNEL FOR DEBUG:")
        val abfBlock = io.github.moxisuki.blockprint.core.BlockState("create:andesite_belt_funnel", mapOf("facing" to "east", "powered" to "false", "shape" to "pulling"))
        val abfModel = resolver.resolve(abfBlock.name, abfBlock.properties)
        println("Belt Funnel (${abfBlock.properties}): rotX=${abfModel.rotX}, rotY=${abfModel.rotY}, elements=${abfModel.elements.size}")
        abfModel.elements.forEachIndexed { idx, elem ->
            println("  - Elem $idx: from=${elem.from}, to=${elem.to}, rot=${elem.rotation?.let { "${it.axis} ${it.angle} @ ${it.origin}" } ?: "null"}")
        }

        println("PRINTING ALL TRANSMISSION BLOCKS IN REGION:")
        for (y in 0 until region.height) {
            for (z in 0 until region.depth) {
                for (x in 0 until region.width) {
                    val block = region.blockAt(x, y, z) ?: continue
                    if (block.name.contains("gearbox") || block.name.contains("shaft") || block.name.contains("clutch") || block.name.contains("cogwheel") || block.name.contains("water_wheel")) {
                        println("Block ${block.name} at ($x, $y, $z) props=${block.properties}")
                    }
                }
            }
        }
        
        LitematicToGlb.convert(
            litematic = litematic,
            assetsDirs = assetsList,
            outputFile = glbFile,
            regionIndex = 0,
        )
        assertTrue("GLB file should be generated", glbFile.exists())
        assertTrue("GLB file should be non-empty", glbFile.length() > 0)
        println("SUCCESS: Generated GLB at ${glbFile.absolutePath}")
    }
}
