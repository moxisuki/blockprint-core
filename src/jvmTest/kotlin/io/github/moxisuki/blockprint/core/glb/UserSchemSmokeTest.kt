package io.github.moxisuki.blockprint.core.glb

import io.github.moxisuki.blockprint.core.api.BlockPrintReader
import io.github.moxisuki.blockprint.core.api.BlockPrintToGlb
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * One-off smoke test: read a real-world `.schem` file from
 * `test/fixtures/user-sample.schem` and produce a GLB for the user to
 * inspect. Asserts the output is non-empty; the actual model fidelity is
 * the user's visual call.
 *
 * Run with:
 *   ./gradlew jvmTest --tests "*UserSchemSmokeTest*"
 *
 * Output is written to `build/user-sample.glb`.
 */
class UserSchemSmokeTest {

    @Test
    fun convert_user_sample_schem_to_glb() {
        val schemPath = Paths.get("test/fixtures/user-sample.schem")
        assumeTrue("fixture missing: $schemPath", Files.isRegularFile(schemPath))

        val document = BlockPrintReader.read(schemPath.toFile())

        println("[USER-SCHEM] regions = ${document.regions.size}")
        for ((i, r) in document.regions.withIndex()) {
            println(
                "[USER-SCHEM] region[$i] ${r.name} size=${r.width}x${r.height}x${r.depth} " +
                    "blocks=${r.rawBlocks.size} pos=${r.position}",
            )
        }

        val assetsDir = Paths.get("test/assets")

        // Warmup (the first call also pays modelCache + texturePacker
        // init costs; subsequent calls are pure GLB export).
        repeat(3) { BlockPrintToGlb.convertToBytes(document, listOf(assetsDir)) }

        // Median of 5 timed conversions.
        val samples = LongArray(5)
        repeat(5) { i ->
            samples[i] = kotlin.system.measureNanoTime {
                BlockPrintToGlb.convertToBytes(document, listOf(assetsDir))
            }
        }
        val medianNs = samples.sorted()[2]
        val medianMs = medianNs / 1_000_000
        println("[USER-SCHEM] convert median: $medianMs ms (median of 5)")

        val bytes = BlockPrintToGlb.convertToBytes(document, listOf(assetsDir))
        val out = Paths.get("build/user-sample.glb").toFile()
        out.parentFile.mkdirs()
        out.writeBytes(bytes)
        println(
            "[USER-SCHEM] wrote ${bytes.size} bytes (${bytes.size / 1024} KiB) to ${out.absolutePath}",
        )
        assertTrue("GLB output must be non-empty", bytes.isNotEmpty())
    }
}
