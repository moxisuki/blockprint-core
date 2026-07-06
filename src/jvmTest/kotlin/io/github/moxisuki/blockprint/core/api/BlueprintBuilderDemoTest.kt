@file:Suppress("JAVA_MODULE_DOES_NOT_EXPORT_PACKAGE")

package io.github.moxisuki.blockprint.core.api

import io.github.moxisuki.blockprint.core.BlockPrintConverter
import io.github.moxisuki.blockprint.core.SchematicFormat
import org.junit.Test

class BlueprintBuilderDemoTest {

    @Test
    fun build_house_and_export_glb() {
        val assetsDir = java.nio.file.Paths.get("test/assets").toAbsolutePath()
        if (!java.nio.file.Files.isDirectory(assetsDir)) return

        val doc = BlueprintBuilder()
            .name("Demo House")
            .author("blockprint")
            .description("A small house with a door")
            .dataVersion(3953)
            .version(6)
            .region("house", 7, 5, 7) {
                position(0, 0, 0)

                // Floor (stone bricks)
                fill(0, 0, 0, 6, 0, 6, "minecraft:stone_bricks")

                // Walls (oak planks) — 4 walls, 3 blocks high
                // Front wall (z=0), with door gap
                fill(0, 1, 0, 6, 3, 0, "minecraft:oak_planks")          // back wall
                fill(0, 1, 0, 0, 3, 6, "minecraft:oak_planks")           // left wall
                fill(6, 1, 0, 6, 3, 6, "minecraft:oak_planks")           // right wall
                fill(0, 1, 6, 2, 3, 6, "minecraft:oak_planks")           // front wall left
                fill(4, 1, 6, 6, 3, 6, "minecraft:oak_planks")           // front wall right
                // Door gap at (3,1,6) to (3,2,6)
                fill(3, 3, 6, 3, 3, 6, "minecraft:oak_planks")           // above door

                // Door (acacia door)
                set(3, 1, 6, "minecraft:acacia_door[facing=north,half=lower,hinge=left,open=false]")
                set(3, 2, 6, "minecraft:acacia_door[facing=north,half=upper,hinge=left,open=false]")

                // Windows — glass panes on left and right walls
                set(0, 2, 2, "minecraft:glass_pane[east=true,north=false,south=false,west=true,waterlogged=false]")
                set(0, 2, 4, "minecraft:glass_pane[east=true,north=false,south=false,west=true,waterlogged=false]")
                set(6, 2, 2, "minecraft:glass_pane[east=true,north=false,south=false,west=true,waterlogged=false]")
                set(6, 2, 4, "minecraft:glass_pane[east=true,north=false,south=false,west=true,waterlogged=false]")

                // Roof — oak stairs (pyramid peak)
                set(0, 4, 0, "minecraft:oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]")
                set(6, 4, 0, "minecraft:oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]")
                set(0, 4, 6, "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]")
                set(6, 4, 6, "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]")
                // Fill roof edges with oak planks
                fill(0, 4, 1, 0, 4, 5, "minecraft:oak_planks")
                fill(6, 4, 1, 6, 4, 5, "minecraft:oak_planks")
                fill(1, 4, 0, 5, 4, 0, "minecraft:oak_planks")
                fill(1, 4, 6, 5, 4, 6, "minecraft:oak_planks")
                // Roof top flat fill
                fill(1, 4, 1, 5, 4, 5, "minecraft:dark_oak_planks")
            }
            .build()

        // Save as litematic
        val litematicBytes = BlockPrintConverter.convert(doc, SchematicFormat.Litematica)
        val litematicFile = java.io.File("test/build/demo_house.litematic")
        litematicFile.parentFile.mkdirs()
        litematicFile.writeBytes(litematicBytes)
        println("Litematic saved to: ${litematicFile.absolutePath}")
        println("Block count: ${doc.blockCount()}")

        // Export as GLB
        val glbFile = java.io.File("test/build/demo_house.glb")
        BlockPrintToGlb.convert(doc, listOf(assetsDir), glbFile)
        println("GLB saved to: ${glbFile.absolutePath}")
        println("GLB size: ${glbFile.length()} bytes")
    }
}
