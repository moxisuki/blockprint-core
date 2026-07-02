package io.github.moxisuki.blockprint.core.glb

import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.LitematicReader
import io.github.moxisuki.blockprint.core.model.BlockPrintDocument
import java.io.File

import io.github.moxisuki.blockprint.core.glb.writer.GlbExportOptions
import java.nio.file.Path
import kotlin.math.roundToLong
import org.junit.Ignore
import org.junit.Test

/**
 * Stress test against a real 500k+ litematic. Not in the regular suite —
 * file is local to one machine. Run manually when iterating on OOM/perf.
 */
class StressLitematicTest {
    private val projectRoot: File
        get() = File("").absoluteFile

    private val litematicFile = File(
        "D:\\mc\\.minecraft\\versions\\1.21.1-NeoForge_21.1.233\\schematics\\" +
        "1.21简单分整流全物品 By Cupperum29,装饰 By Asuna_soryu-1781585801323.litematic"
    )

    private fun assetsDirs(): List<Path> = listOf(
        Path.of(projectRoot.path, "test", "assets"),
        Path.of(projectRoot.path, "test", "create", "assets"),
    )

    // Run manually: ./gradlew jvmTest --tests "*StressLitematicTest*" -Dorg.gradle.jvmargs="-Xmx3g"
    @Test @org.junit.Ignore
    fun stressConvertFullLitematic() {
        if (!litematicFile.exists()) return  // skip on other machines

        // 1. Parse
        val tParse = System.nanoTime()
        val document = BlockPrintDocument.fromLegacy(LitematicReader.read(litematicFile))
        val parseMs = (System.nanoTime() - tParse) / 1_000_000

        val region = document.regions.first()
        val totalCells = region.width.toLong() * region.height * region.depth
        val solidCells = region.rawBlocks.count { it != 0 }
        val paletteSize = region.palette.entries.size

        println(
            "LITEMATIC: ${document.name}, " +
            "region=${region.width}x${region.height}x${region.depth} " +
            "cells=$totalCells solid=$solidCells palette=$paletteSize parse=${parseMs}ms"
        )

        // 2. Convert to GLB
        val outDir = File(projectRoot, "test").apply { mkdirs() }
        val outputFile = File(outDir, "stress_test_output.glb")

        val progress = mutableListOf<Float>()
        val procCount = Runtime.getRuntime().availableProcessors()
        println("CPU cores: $procCount")

        // Track GC count before/after
        val gcBean = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()
        val gcBefore = gcBean.map { it.collectionCount to it.collectionTime }.toList()

        val tConv = System.nanoTime()
        LitematicToGlb.convert(
            document = document,
            assetsDirs = assetsDirs(),
            outputFile = outputFile,
            regionIndex = 0,
            onProgress = { p -> progress.add(p) },
        )
        val convMs = (System.nanoTime() - tConv) / 1_000_000

        val gcAfter = gcBean.map { it.collectionCount to it.collectionTime }.toList()
        val gcDeltas = gcAfter.zip(gcBefore).mapIndexed { i, (after, before) ->
            val name = gcBean[i].name
            val cDelta = after.first - before.first
            val tDelta = after.second - before.second
            "$name: ${cDelta}collections/${tDelta}ms"
        }

        val outputSize = if (outputFile.exists()) outputFile.length() else 0L

        println(
            "CONVERT: ${convMs}ms, output=${outputSize}bytes, " +
            "progressPoints=${progress.size} " +
            "firstP=${progress.firstOrNull()} lastP=${progress.lastOrNull()}"
        )
        println("GC: ${gcDeltas.joinToString(" | ")}")

        // Sanity assertions
        require(outputFile.exists()) { "Output GLB must exist" }
        require(outputSize > 10000) { "Output should be >10KB for a real model, got $outputSize" }
        require(progress.isNotEmpty()) { "Progress must have been reported" }
        require(progress.lastOrNull() ?: 0f >= 0.94f) { "Progress should end near completion, got ${progress.lastOrNull()}" }
        require(progress.zipWithNext().all { (a, b) -> b >= a - 1e-6f }) { "Progress must be monotonic" }

        println("STRESS PASS: ${convMs}ms, $solidCells solid / $totalCells cells, ${outputSize/1024}KB GLB")
    }
}
