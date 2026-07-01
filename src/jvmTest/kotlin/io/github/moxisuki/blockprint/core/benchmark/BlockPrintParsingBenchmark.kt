package io.github.moxisuki.blockprint.core.benchmark

import io.github.moxisuki.blockprint.core.LitematicReader
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.system.measureNanoTime
import org.junit.Test

/**
 * Hand-rolled wall-clock benchmark. CI skips via env var. Run with:
 *   ./gradlew jvmTest --tests "*BlockPrintParsingBenchmark*"
 *
 * NOT in CI: `CI=true ./gradlew jvmTest` is a no-op for this class.
 */
class BlockPrintParsingBenchmark {
    private val fixture5MB = "test/benchmark/5mb.litematic"
    private val fixture50MB = "test/benchmark/50mb.litematic"
    private val fixture200MB = "test/benchmark/200mb.litematic"

    @Test fun parse_50MB_Litematica_baseline() {
        assumeFalse("skipping benchmark in CI", System.getenv("CI")?.equals("CI", ignoreCase = true) == true)
        val file = File(fixture50MB)
        assumeTrue("fixture missing: $fixture50MB", file.exists())

        // Warmup
        repeat(3) { LitematicReader.read(file) }

        val samples = LongArray(5)
        repeat(5) { i ->
            samples[i] = measureNanoTime { LitematicReader.read(file) }
        }
        val median = samples.sorted()[2]
        println("[BENCHMARK] Litematica 50MB: ${median / 1_000_000} ms (median of 5)")
        // No assertion: this is a measurement, not a test.
    }
}
