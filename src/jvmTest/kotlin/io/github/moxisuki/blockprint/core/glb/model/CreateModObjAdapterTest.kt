package io.github.moxisuki.blockprint.core.glb.model

import java.io.File
import java.nio.file.Path
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateModObjAdapterTest {

    private val projectRoot: File
        get() = File("").absoluteFile

    private fun assetsDirs(): List<Path> =
        generateSequence(projectRoot) { it.parentFile }
            .flatMap { root ->
                sequenceOf(
                    File(root, "test/assets"),
                    File(root, "test/create/assets"),
                )
            }
            .filter { it.isDirectory }
            .map { it.toPath() }
            .distinct()
            .toList()

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

    @Test
    fun mechanicalDrill_rotatesAsOneBlockAndExtendsInFacingDirection() {
        // The drill is a single block: body + head form one assembly
        // whose overall orientation is the block's facing. The head
        // defaults to pointing in the +Z direction, so the whole
        // assembly must extend outside the base cube along the facing
        // direction, with the same total element count for every facing.
        // After rotateModel aligns the head with the facing:
        //   - up / down    -> head sticks out along Y
        //   - north/south  -> head sticks out along Z
        //   - east / west   -> head sticks out along X
        val expectedExtension = mapOf<String, (Bounds) -> Boolean>(
            "up" to { it.maxY > 16.0 + 1e-6 },
            "down" to { it.minY < 0.0 - 1e-6 },
            "north" to { it.minZ < 0.0 - 1e-6 },
            "south" to { it.maxZ > 16.0 + 1e-6 },
            "east" to { it.maxX > 16.0 + 1e-6 },
            "west" to { it.minX < 0.0 - 1e-6 },
        )

        val expectedElementCount = resolver().resolve(
            "create:mechanical_drill",
            mapOf("facing" to "south", "waterlogged" to "false"),
        ).elements.size

        for ((facing, extendsTowardFacing) in expectedExtension) {
            val model = resolver().resolve(
                "create:mechanical_drill",
                mapOf(
                    "facing" to facing,
                    "waterlogged" to "false",
                )
            )

            // Same total element count regardless of facing — body and
            // head merge into a single block-shaped assembly.
            assertEquals(
                "Drill should have the same element count for facing=$facing",
                expectedElementCount,
                model.elements.size,
            )

            // The whole assembly extends outside the base cube in the
            // facing direction.
            val bounds = resolvedBounds(model)
            assertTrue(
                "Expected whole drill assembly to extend outside the base cube for facing=$facing but bounds were $bounds",
                bounds.any(extendsTowardFacing),
            )
        }
    }

    @Test
    fun mechanicalDrillBodyAxlePlateAlignsWithFacing() {
        // Regression: the body used to be rotated with the *head's* rotate-to-face
        // table, leaving the casing stuck in its facing=up default while the head
        // pointed the right way. The body must instead use its blockstate rotation
        // so the whole assembly agrees on one axis.
        //
        // The body's "axle plate" ([2,0.95,2]..[14,9.05,14]) is thin along the
        // drill's open axis. After the correct rotation, that thin axis must point
        // along `facing`.
        val facingToAxis = mapOf(
            "up" to "y",
            "down" to "y",
            "north" to "z",
            "south" to "z",
            "east" to "x",
            "west" to "x",
        )

        for ((facing, expectedAxis) in facingToAxis) {
            val model = resolver().resolve(
                "create:mechanical_drill",
                mapOf("facing" to facing, "waterlogged" to "false"),
            )

            val plate = model.elements.first { e ->
                abs(e.from[0] - 2.0) < 1e-6 && abs(e.from[1] - 0.95) < 1e-6 && abs(e.from[2] - 2.0) < 1e-6 &&
                    abs(e.to[0] - 14.0) < 1e-6 && abs(e.to[1] - 9.05) < 1e-6 && abs(e.to[2] - 14.0) < 1e-6
            }

            val rotX = if (plate.modelRotX != 0 || plate.modelRotY != 0) plate.modelRotX else model.rotX
            val rotY = if (plate.modelRotX != 0 || plate.modelRotY != 0) plate.modelRotY else model.rotY
            val bounds = rotateBounds(Bounds.from(plate.from, plate.to), rotX, rotY)

            val thinAxis = listOf(
                "x" to bounds.sizeX,
                "y" to bounds.sizeY,
                "z" to bounds.sizeZ,
            ).minByOrNull { it.second }!!.first

            assertEquals(
                "Drill body axle plate should be thin along the facing axis for facing=$facing",
                expectedAxis,
                thinAxis,
            )
        }
    }

    @Test
    fun mechanicalPress_includesPressHeadAndTallRamForHorizontalFacings() {
        for (facing in listOf("north", "south", "east", "west")) {
            val model = resolver().resolve(
                "create:mechanical_press",
                mapOf("facing" to facing)
            )
            val bounds = resolvedBounds(model)

            assertTrue(
                "Expected press geometry below y=4 for facing=$facing but bounds were $bounds",
                bounds.any { it.minY < 4.0 - 1e-6 },
            )
            assertTrue(
                "Expected press ram geometry above y=20 for facing=$facing but bounds were $bounds",
                bounds.any { it.maxY > 20.0 + 1e-6 },
            )
            assertTrue(
                "Expected a wide press head below y=4, not just a shaft placeholder, for facing=$facing but bounds were $bounds",
                bounds.any {
                    it.minY < 4.0 - 1e-6 &&
                        it.sizeX > 8.0 &&
                        it.sizeZ > 8.0
                },
            )
            // Transmission rod = the kinetic input shaft. It runs HORIZONTALLY
            // along the facing/gearbox axis (north/south -> Z, east/west -> X) at
            // gearbox height, poking out the side socket — NOT a vertical rod
            // buried inside the casing.
            val longAxisIsZ = facing == "north" || facing == "south"
            assertTrue(
                "Expected a horizontal 4x4 transmission shaft along the ${if (longAxisIsZ) "Z" else "X"} (facing) axis at gearbox height for facing=$facing but bounds were $bounds",
                bounds.any { b ->
                    val longSpan = if (longAxisIsZ) b.sizeZ else b.sizeX
                    val cross1 = b.sizeY
                    val cross2 = if (longAxisIsZ) b.sizeX else b.sizeZ
                    longSpan > 14.0 &&
                        kotlin.math.abs(cross1 - 4.0) < 1e-6 &&
                        kotlin.math.abs(cross2 - 4.0) < 1e-6 &&
                        b.minY > 3.0 &&
                        b.maxY < 14.0
                },
            )
        }
    }

    @Test
    fun mechanicalMixer_includesCogwheelPoleAndWhisk() {
        // The mixer is non-directional (single "" variant) and is a multi-part
        // block-entity assembly merged in world orientation:
        //   - casing (block)
        //   - cogwheel_shaftless: a horizontal gear (vertical axis) at y6.5..9.5,
        //     teeth poking past the cube sides, sitting in the casing's side gap
        //   - pole: central rod sticking UP above the cube (top kinetic input)
        //   - whisk head: hangs DOWN below the cube into the basin
        val model = resolver().resolve("create:mechanical_mixer", emptyMap())
        val bounds = resolvedBounds(model)

        // Casing: near-full-width slab in the upper half of the cube.
        assertTrue(
            "Expected mixer casing but bounds were $bounds",
            bounds.any { it.sizeX > 14.0 && it.sizeZ > 14.0 && it.maxY > 9.0 },
        )
        // Cogwheel: a flat gear around y6.5..9.5 whose teeth extend past the cube.
        assertTrue(
            "Expected horizontal cogwheel at gear height with teeth past the cube but bounds were $bounds",
            bounds.any {
                it.minY > 5.0 && it.maxY < 11.0 &&
                    it.sizeY < 4.0 &&
                    (it.minX < 0.0 - 1e-6 || it.maxX > 16.0 + 1e-6 || it.minZ < 0.0 - 1e-6 || it.maxZ > 16.0 + 1e-6)
            },
        )
        // Central pole sticking up above the block (pole reaches ~y32).
        assertTrue(
            "Expected mixer pole sticking up above y16 but bounds were $bounds",
            bounds.any { it.maxY > 16.0 + 1e-6 },
        )
        // Whisk head hanging below the block (reaches ~y-4).
        assertTrue(
            "Expected mixer whisk hanging below y0 but bounds were $bounds",
            bounds.any { it.minY < 0.0 - 1e-6 },
        )
    }

    private fun resolvedBounds(model: ResolvedModel): List<Bounds> =
        model.elements.map { element ->
            val rotX = if (element.modelRotX != 0 || element.modelRotY != 0) element.modelRotX else model.rotX
            val rotY = if (element.modelRotX != 0 || element.modelRotY != 0) element.modelRotY else model.rotY
            rotateBounds(Bounds.from(element.from, element.to), rotX, rotY)
        }

    private fun rotateBounds(bounds: Bounds, rotX: Int, rotY: Int): Bounds {
        if (rotX == 0 && rotY == 0) return bounds

        val corners = listOf(
            listOf(bounds.minX, bounds.minY, bounds.minZ),
            listOf(bounds.maxX, bounds.minY, bounds.minZ),
            listOf(bounds.minX, bounds.maxY, bounds.minZ),
            listOf(bounds.maxX, bounds.maxY, bounds.minZ),
            listOf(bounds.minX, bounds.minY, bounds.maxZ),
            listOf(bounds.maxX, bounds.minY, bounds.maxZ),
            listOf(bounds.minX, bounds.maxY, bounds.maxZ),
            listOf(bounds.maxX, bounds.maxY, bounds.maxZ),
        ).map { rotatePoint(it, rotX, rotY) }

        return Bounds(
            minX = corners.minOf { it[0] },
            minY = corners.minOf { it[1] },
            minZ = corners.minOf { it[2] },
            maxX = corners.maxOf { it[0] },
            maxY = corners.maxOf { it[1] },
            maxZ = corners.maxOf { it[2] },
        )
    }

    private fun rotatePoint(point: List<Double>, rotX: Int, rotY: Int): List<Double> {
        if (rotX == 0 && rotY == 0) return point

        var x = point[0]
        var y = point[1]
        var z = point[2]
        val radiansX = -kotlin.math.PI * rotX / 180.0
        val radiansY = kotlin.math.PI * rotY / 180.0
        val centerX = 8.0
        val centerY = 8.0
        val centerZ = 8.0

        if (rotX != 0) {
            val dy = y - centerY
            val dz = z - centerZ
            y = centerY + dy * kotlin.math.cos(radiansX) - dz * kotlin.math.sin(radiansX)
            z = centerZ + dy * kotlin.math.sin(radiansX) + dz * kotlin.math.cos(radiansX)
        }
        if (rotY != 0) {
            val dx = x - centerX
            val dz = z - centerZ
            x = centerX + dx * kotlin.math.cos(radiansY) - dz * kotlin.math.sin(radiansY)
            z = centerZ + dx * kotlin.math.sin(radiansY) + dz * kotlin.math.cos(radiansY)
        }

        return listOf(x, y, z)
    }

    private data class Bounds(
        val minX: Double,
        val minY: Double,
        val minZ: Double,
        val maxX: Double,
        val maxY: Double,
        val maxZ: Double,
    ) {
        val sizeX: Double get() = maxX - minX
        val sizeY: Double get() = maxY - minY
        val sizeZ: Double get() = maxZ - minZ

        fun isInsideBaseCube(): Boolean =
            minX >= -1e-6 &&
                minY >= -1e-6 &&
                minZ >= -1e-6 &&
                maxX <= 16.0 + 1e-6 &&
                maxY <= 16.0 + 1e-6 &&
                maxZ <= 16.0 + 1e-6

        fun matchesDrillBody(): Boolean =
            abs(minX - 2.0) < 1e-6 &&
                abs(minY - 0.95) < 1e-6 &&
                abs(minZ - 2.0) < 1e-6 &&
                abs(maxX - 14.0) < 1e-6 &&
                abs(maxY - 9.05) < 1e-6 &&
                abs(maxZ - 14.0) < 1e-6

        companion object {
            fun from(from: List<Double>, to: List<Double>): Bounds = Bounds(
                minX = from[0],
                minY = from[1],
                minZ = from[2],
                maxX = to[0],
                maxY = to[1],
                maxZ = to[2],
            )
        }
    }
}
