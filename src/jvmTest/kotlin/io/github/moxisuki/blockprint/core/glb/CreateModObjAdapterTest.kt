package io.github.moxisuki.blockprint.core.glb

import java.io.File
import java.nio.file.Path
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateModObjAdapterTest {

    private val projectRoot: File
        get() = File("").absoluteFile

    private fun assetsDirs(): List<Path> = listOf(
        Path.of(projectRoot.path, "test", "assets"),
        Path.of(projectRoot.path, "test", "create", "assets"),
    )

    private fun resolver() = ModelResolver(assetsDirs())

    @Test
    fun uncasedHorizontalMiddleBelt_matchesCreateWidthAndHasNoFrontBackCaps() {
        val model = resolver().resolve(
            "create:belt",
            mapOf(
                "casing" to "false",
                "facing" to "south",
                "part" to "middle",
                "slope" to "horizontal",
                "waterlogged" to "false",
            )
        )

        assertEquals(4, model.elements.size)
        assertTrue(model.rawMeshes.isEmpty())

        val outerTop = model.elements.first { it.from == listOf(1.0, 11.0, 0.0) && it.to == listOf(15.0, 13.0, 16.0) }
        val innerTop = model.elements.first { it.from == listOf(3.0, 10.0, 0.0) && it.to == listOf(13.0, 11.0, 16.0) }
        val outerBottom = model.elements.first { it.from == listOf(1.0, 3.0, 0.0) && it.to == listOf(15.0, 5.0, 16.0) }
        val innerBottom = model.elements.first { it.from == listOf(3.0, 5.0, 0.0) && it.to == listOf(13.0, 6.0, 16.0) }

        assertEquals(setOf("east", "west", "up", "down"), outerTop.faces.keys)
        assertEquals(setOf("east", "west", "down"), innerTop.faces.keys)
        assertEquals(setOf("east", "west", "up", "down"), outerBottom.faces.keys)
        assertEquals(setOf("east", "west", "up"), innerBottom.faces.keys)
    }

    @Test
    fun uncasedStartAndEndBelts_addOnlyOneClosedEndCap() {
        val start = resolver().resolve(
            "create:belt",
            mapOf(
                "casing" to "false",
                "facing" to "south",
                "part" to "start",
                "slope" to "horizontal",
                "waterlogged" to "false",
            )
        )
        val startCap = start.elements.first {
            abs(it.from[1] - 4.0) < 1e-6 &&
                abs(it.to[1] - 12.0) < 1e-6 &&
                it.from[2] < 0.5 &&
                it.to[2] < 2.5
        }
        assertEquals(listOf(1.1, 4.0, -0.05), startCap.from)
        assertEquals(listOf(14.9, 12.0, 1.95), startCap.to)

        val end = resolver().resolve(
            "create:belt",
            mapOf(
                "casing" to "false",
                "facing" to "south",
                "part" to "end",
                "slope" to "horizontal",
                "waterlogged" to "false",
            )
        )
        val endCap = end.elements.first {
            abs(it.from[1] - 4.0) < 1e-6 &&
                abs(it.to[1] - 12.0) < 1e-6 &&
                it.from[2] > 13.5 &&
                it.to[2] > 15.5
        }
        assertEquals(listOf(1.1, 4.0, 14.05), endCap.from)
        assertEquals(listOf(14.9, 12.0, 16.05), endCap.to)
    }

    @Test
    fun diagonalBelts_useDiagonalTextureAndStillStayHollow() {
        val model = resolver().resolve(
            "create:belt",
            mapOf(
                "casing" to "false",
                "facing" to "south",
                "part" to "middle",
                "slope" to "upward",
                "waterlogged" to "false",
            )
        )

        assertTrue(model.elements.isNotEmpty())
        assertTrue(model.elements.all { element ->
            element.faces.values.all { face -> face.texture == "create:textures/block/belt_diagonal" }
        })
        assertTrue(model.elements.any { it.from[0] < 1.5 && it.to[0] > 14.5 })
    }

    @Test
    fun casedPulleyBelt_keepsCasingAndAddsPulleyGeometry() {
        val model = resolver().resolve(
            "create:belt",
            mapOf(
                "casing" to "true",
                "facing" to "south",
                "part" to "pulley",
                "slope" to "horizontal",
                "waterlogged" to "false",
            )
        )

        assertTrue(model.elements.size > 3)
        assertTrue(model.elements.any { it.from == listOf(0.0, 0.0, 0.0) && it.to == listOf(16.0, 11.0, 16.0) })
        assertTrue(model.elements.any { it.from == listOf(1.0, 11.0, 0.0) && it.to == listOf(15.0, 13.0, 16.0) })
        assertTrue(model.elements.any { element ->
            abs(element.from[0] - 0.0) < 1e-6 &&
                abs(element.to[0] - 16.0) < 1e-6 &&
                abs(element.from[1] - 6.0) < 1e-6 &&
                abs(element.to[1] - 10.0) < 1e-6
        })
    }
}
