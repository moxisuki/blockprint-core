package io.github.moxisuki.blockprint.core.glb.synthetic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticSignTest {

    @Test
    fun standingSign_hasBoardAndPost() {
        val model = SyntheticSign.buildStanding("minecraft:entity/signs/oak")

        assertEquals(2, model.elements.size)
        assertTrue(
            "Expected centered sign board",
            model.elements.any {
                it.from == listOf(2.0, 7.0, 7.0) &&
                    it.to == listOf(14.0, 15.0, 9.0)
            },
        )
        assertTrue(
            "Expected narrow center post down to ground",
            model.elements.any {
                it.from == listOf(7.0, 0.0, 7.25) &&
                    it.to == listOf(9.0, 7.0, 8.75)
            },
        )
    }

    @Test
    fun wallSign_isAttachedToNorthFace() {
        val model = SyntheticSign.buildWall("minecraft:entity/signs/oak")

        assertEquals(1, model.elements.size)
        assertEquals(listOf(2.0, 4.0, 14.0), model.elements.single().from)
        assertEquals(listOf(14.0, 12.0, 16.0), model.elements.single().to)
    }

    @Test
    fun hangingSign_hasBoardAndTwoCeilingChains() {
        val model = SyntheticSign.buildHanging("minecraft:entity/signs/hanging/oak")

        assertEquals(3, model.elements.size)
        assertTrue(model.elements.any { it.from == listOf(2.0, 4.0, 7.0) && it.to == listOf(14.0, 12.0, 9.0) })
        assertEquals(2, model.elements.count { it.from[1] == 12.0 && it.to[1] == 16.0 })
    }

    @Test
    fun wallHangingSign_hasBoardAndWallBrackets() {
        val model = SyntheticSign.buildWallHanging("minecraft:entity/signs/hanging/oak")

        assertEquals(5, model.elements.size)
        assertTrue(model.elements.any { it.from == listOf(2.0, 4.0, 14.0) && it.to == listOf(14.0, 12.0, 16.0) })
        assertEquals(2, model.elements.count { it.from[2] == 11.0 && it.to[2] == 16.0 })
    }

    @Test
    fun signTexturesUseFullTexturePath() {
        val model = SyntheticSign.buildStanding("minecraft:entity/signs/oak")

        assertTrue(
            model.elements.all { element ->
                element.faces.values.all { face -> face.texture == "minecraft:textures/entity/signs/oak" }
            },
        )
    }
}
