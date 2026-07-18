package io.github.moxisuki.blockprint.core.glb

import io.github.moxisuki.blockprint.core.api.BlockPrintReader
import io.github.moxisuki.blockprint.core.api.BlockPrintToGlb
import io.github.moxisuki.blockprint.core.glb.writer.GlbExportOptions
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class UserBlueprintRegressionTest {

    @Test
    fun convert_lazy_iron_farm_floor1_glb() {
        convertBlueprintFloor1(
            inputPath = "test/6.0懒人刷铁机 (1).nbt",
            outputPath = "test/6.0懒人刷铁机.floor1.glb",
        )
    }

    @Test
    fun convert_black_goat_six_cylinder_steam_engine_floor1_glb() {
        convertBlueprintFloor1(
            inputPath = "C:/Users/Administrator/Downloads/Black_山羊_无黄铜省材料版本六缸蒸汽引擎4×3×7_11776应力输出.nbt",
            outputPath = "test/Black_山羊_无黄铜省材料版本六缸蒸汽引擎4×3×7_11776应力输出.floor1.glb",
        )
    }

    @Test
    fun convert_eightfold_level9_lava_steam_engine_floor1_glb() {
        convertBlueprintFloor1(
            inputPath = "C:/Users/Administrator/Downloads/八联9级岩浆蒸汽引擎0.1[cym].nbt",
            outputPath = "test/八联9级岩浆蒸汽引擎0.1[cym].floor1.glb",
        )
    }

    private fun convertBlueprintFloor1(inputPath: String, outputPath: String) {
        val input = Paths.get(inputPath)
        assumeTrue("fixture missing: $input", Files.isRegularFile(input))

        val document = BlockPrintReader.read(input.toFile())
        println("[USER-BLUEPRINT] source=$input regions=${document.regions.size}")
        for ((index, region) in document.regions.withIndex()) {
            println(
                "[USER-BLUEPRINT] region[$index] name=${region.name} " +
                    "size=${region.width}x${region.height}x${region.depth} " +
                    "palette=${region.palette.entries.size} blocks=${region.rawBlocks.size}",
            )
            val createCounts = mutableMapOf<String, Int>()
            for (paletteIndex in region.rawBlocks) {
                val block = region.palette.entries.getOrNull(paletteIndex) ?: continue
                if (block.name.startsWith("create:")) {
                    createCounts[block.name] = (createCounts[block.name] ?: 0) + 1
                }
            }
            createCounts.entries
                .sortedByDescending { it.value }
                .take(40)
                .forEach { (name, count) ->
                    println("[USER-BLUEPRINT] create-count $name=$count")
                }
        }

        val generatedBakedModels = Paths.get(
            "C:/Users/Administrator/.codex/repo-cache/model-adapter-research/Create/build/blockprint-model-baker/generated",
        )
        val assetsDirs = listOfNotNull(
            generatedBakedModels.takeIf { Files.isDirectory(it) },
            Paths.get("test/assets"),
            Paths.get("test/create/assets"),
        )
        val output = Paths.get(outputPath).toFile()
        output.parentFile.mkdirs()

        BlockPrintToGlb.convert(
            document = document,
            assetsDirs = assetsDirs,
            outputFile = output,
            options = GlbExportOptions(floorHeight = 1),
        )

        println("[USER-BLUEPRINT] wrote ${output.length()} bytes to ${output.absolutePath}")
        assertTrue("GLB output must be non-empty", output.length() > 0L)
    }
}
