package io.github.moxisuki.blockprint.core.glb

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.model.BlockPrintDocument

import io.github.moxisuki.blockprint.core.glb.writer.GlbExportOptions
import io.github.moxisuki.blockprint.core.model.BlockPrintRegion
import io.github.moxisuki.blockprint.core.Position
import io.github.moxisuki.blockprint.core.SchematicFormat
import io.github.moxisuki.blockprint.core.glb.internal.JsonParser
import java.io.File
import java.nio.file.Path
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateCompositeFixtureGlbTest {

    private val projectRoot: File
        get() = File("").absoluteFile

    private fun assetsDirs(): List<Path> = listOf(
        Path.of(projectRoot.path, "test", "assets"),
        Path.of(projectRoot.path, "test", "create", "assets"),
    )

    private fun parseGlbJson(bytes: ByteArray): Map<String, Any?> {
        fun readIntLE(offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
        val jsonLen = readIntLE(12)
        val jsonBytes = bytes.copyOfRange(20, 20 + jsonLen)
        return JsonParser.parseObject(jsonBytes.toString(Charsets.UTF_8))
    }

    private fun buildFixture(): BlockPrintDocument {
        val states = listOf(
            BlockState("minecraft:air"),
            BlockState("minecraft:stone"),
            BlockState("create:mechanical_drill", mapOf("facing" to "north", "waterlogged" to "false")),
            BlockState("create:mechanical_drill", mapOf("facing" to "south", "waterlogged" to "false")),
            BlockState("create:mechanical_drill", mapOf("facing" to "east", "waterlogged" to "false")),
            BlockState("create:mechanical_drill", mapOf("facing" to "west", "waterlogged" to "false")),
            BlockState("create:mechanical_drill", mapOf("facing" to "up", "waterlogged" to "false")),
            BlockState("create:mechanical_drill", mapOf("facing" to "down", "waterlogged" to "false")),
            BlockState("create:mechanical_press", mapOf("facing" to "north")),
            BlockState("create:mechanical_press", mapOf("facing" to "south")),
            BlockState("create:mechanical_press", mapOf("facing" to "east")),
            BlockState("create:mechanical_press", mapOf("facing" to "west")),
            BlockState("create:mechanical_mixer"),
        )
        val palette = BlockPalette(states)
        val w = 15
        val h = 6
        val d = 11
        val blocks = IntArray(w * h * d)
        fun idx(x: Int, y: Int, z: Int) = y * (w * d) + z * w + x

        for (x in 0 until w) {
            for (z in 0 until d) {
                blocks[idx(x, 0, z)] = 1
            }
        }

        blocks[idx(2, 1, 2)] = 2
        blocks[idx(5, 1, 2)] = 3
        blocks[idx(8, 1, 2)] = 4
        blocks[idx(11, 1, 2)] = 5
        blocks[idx(3, 1, 6)] = 6
        blocks[idx(9, 1, 6)] = 7

        blocks[idx(2, 1, 9)] = 8
        blocks[idx(5, 1, 9)] = 9
        blocks[idx(8, 1, 9)] = 10
        blocks[idx(11, 1, 9)] = 11

        // Mechanical mixers (non-directional): casing + cogwheel + pole + whisk.
        // Floor below carved out so the downward whisk is visible.
        blocks[idx(4, 1, 5)] = 12
        blocks[idx(10, 1, 5)] = 12
        blocks[idx(4, 0, 5)] = 0
        blocks[idx(10, 0, 5)] = 0

        val region = BlockPrintRegion(
            name = "CreateDrillPressFixture",
            width = w,
            height = h,
            depth = d,
            position = Position(0, 0, 0),
            palette = palette,
            blocks = blocks,
        )
        return BlockPrintDocument(
            minecraftDataVersion = 3953,
            version = 6,
            name = "CreateDrillPressFixture",
            author = "blockprint-tests",
            description = "Focused Create drill and press rendering fixture",
            regions = listOf(region),
            format = SchematicFormat.Litematica,
        )
    }

    @Test
    fun writesFocusedCreateCompositeGlb() {
        val document = buildFixture()
        val outDir = File(projectRoot, "test")
        outDir.mkdirs()
        val outputFile = File(outDir, "create_composites.glb")

        LitematicToGlb.convert(
            document = document,
            assetsDirs = assetsDirs(),
            outputFile = outputFile,
            regionIndex = 0,
        )

        assertTrue(outputFile.exists())
        assertTrue(
            "Focused Create GLB should be non-trivial in size",
            outputFile.length() > 100,
        )
    }

    @Test
    fun convertReportsMonotonicBoundedProgress() {
        val document = buildFixture()
        val outputFile = File(File(projectRoot, "test").apply { mkdirs() }, "create_composites_progress.glb")

        val reported = mutableListOf<Float>()
        LitematicToGlb.convert(
            document = document,
            assetsDirs = assetsDirs(),
            outputFile = outputFile,
            regionIndex = 0,
            onProgress = { reported.add(it) },
        )

        assertTrue("Expected progress callbacks to fire", reported.isNotEmpty())
        assertTrue(
            "Progress values must stay within [0,1] but were $reported",
            reported.all { it in 0.0f..1.0f },
        )
        // Non-decreasing: the mesh-loop band maps cleanly into the overall ramp,
        // so the sequence the caller sees must never go backwards.
        var prev = -1.0f
        for (p in reported) {
            assertTrue("Progress must be non-decreasing but $reported", p >= prev - 1e-6f)
            prev = p
        }
        assertTrue("Final reported progress should be near completion", reported.last() >= 0.9f)
        outputFile.delete()
    }
}
