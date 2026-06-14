package io.github.moxisuki.blockprint.core

import io.github.moxisuki.blockprint.core.exceptions.LitematicException
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Smoke test against a real .litematic file on disk.
 *
 * The file path is hardcoded — just drop your schematic at the location below
 * and run:
 *
 * ```
 * ./gradlew test --tests "io.github.moxisuki.litematicCore.ReadFileTest"
 * ```
 *
 * If the file is missing, the test fails loudly instead of being skipped, so
 * you know immediately that you forgot to drop it in.
 */
class ReadFileTest {

    companion object {
        /** Hardcoded path — change here if you move the file. */
        private val LITEMATIC_FILE: File =
            File("C:\\Users\\Administrator\\Documents\\CCO\\LitematicMobile\\test.litematic")
    }

    @Test
    fun `read hardcoded litematic file`() {
        check(LITEMATIC_FILE.isFile) {
            "Litematic file not found at: ${LITEMATIC_FILE.absolutePath}\n" +
                "Drop your .litematic there and rerun, or update the path in " +
                "ReadFileTest.kt#LITEMATIC_FILE."
        }

        val lit = LitematicReader.read(LITEMATIC_FILE)
        printSummary(lit)
    }

    private fun printSummary(lit: Litematic) {
        println()
        println("=".repeat(70))
        println("LITEMATIC SUMMARY")
        println("=".repeat(70))
        println("File:        ${LITEMATIC_FILE.absolutePath}")
        println("Name:        ${lit.name}")
        println("Author:      ${lit.author}")
        println("Description: ${lit.description}")
        println("Version:     ${lit.version}")
        println("Data ver:    ${lit.minecraftDataVersion}")
        println("Regions:     ${lit.regions.size}")
        println("Block count: ${lit.blockCount(includeAir = true)} total, " +
                "${lit.blockCount()} non-air")
        println()

        lit.regions.forEachIndexed { i, region ->
            println("-".repeat(70))
            println("Region #${i + 1}: ${region.name}")
            println("  Size:     ${region.width} × ${region.height} × ${region.depth}")
            println("  Position: ${region.position}")
            println("  Palette:  ${region.palette.size} block-state(s)")
            region.palette.entries.take(5).forEachIndexed { idx, bs ->
                println("    [$idx] $bs")
            }
            if (region.palette.size > 5) {
                println("    …and ${region.palette.size - 5} more")
            }
            println()
        }

        val materials = MaterialList.from(lit)
        println("-".repeat(70))
        println("MATERIAL LIST (sorted by count)")
        println("-".repeat(70))
        materials.toSortedByCount().forEach { (name, count) ->
            val short = name.removePrefix("minecraft:")
            println("  ${count.toString().padStart(6)}  $short")
        }

        // Show a small slice of the first region
        lit.regions.firstOrNull()?.let { region ->
            println()
            println("-".repeat(70))
            println("FIRST 20 NON-AIR BLOCKS (region: ${region.name})")
            println("-".repeat(70))
            var shown = 0
            for (y in 0 until region.height) {
                for (z in 0 until region.depth) {
                    for (x in 0 until region.width) {
                        if (shown >= 20) return
                        val idx = region.getBlock(x, y, z)
                        if (idx != 0) {
                            val name = region.palette[idx].name.removePrefix("minecraft:")
                            println("  (x=$x, y=$y, z=$z) → $name")
                            shown++
                        }
                    }
                }
            }
        }
        println()
        println("=".repeat(70))
    }
}