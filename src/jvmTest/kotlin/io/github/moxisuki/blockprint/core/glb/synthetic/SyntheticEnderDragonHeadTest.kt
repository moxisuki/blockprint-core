package io.github.moxisuki.blockprint.core.glb.synthetic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticEnderDragonHeadTest {

    @Test
    fun texNameFor_dragonVariants_returnEnderdragonDragon() {
        // dragon.png is the source texture for the ender dragon entity AND the
        // dragon head block (the head is sampled from a sub-region of dragon.png).
        assertEquals("entity/enderdragon/dragon", SyntheticEnderDragonHead.texNameFor("dragon_head"))
        assertEquals("entity/enderdragon/dragon", SyntheticEnderDragonHead.texNameFor("dragon_wall_head"))
    }

    @Test
    fun texNameFor_nonDragon_returnsNull() {
        assertNull(SyntheticEnderDragonHead.texNameFor("skeleton_skull"))
        assertNull(SyntheticEnderDragonHead.texNameFor("player_head"))
        assertNull(SyntheticEnderDragonHead.texNameFor("stone"))
    }

    @Test
    fun isWall_detectsWallVariant() {
        assertTrue(SyntheticEnderDragonHead.isWall("dragon_wall_head"))
        assertEquals(false, SyntheticEnderDragonHead.isWall("dragon_head"))
    }

    @Test
    fun build_ground_hasMultipleDetailedElements() {
        val model = SyntheticEnderDragonHead.build(
            "minecraft:textures/entity/enderdragon/dragon",
            isWall = false,
        )
        // Ground head has 7 elements: upper_head + upper_lip + jaw + 2× nostril + 2× scale
        assertEquals("7 detailed boxes", 7, model.elements.size)
    }

    @Test
    fun build_wall_alsoHas7Elements_butShiftedAlongZ() {
        val ground = SyntheticEnderDragonHead.build(
            "minecraft:textures/entity/enderdragon/dragon",
            isWall = false,
        )
        val wall = SyntheticEnderDragonHead.build(
            "minecraft:textures/entity/enderdragon/dragon",
            isWall = true,
        )
        assertEquals(7, wall.elements.size)
        // Wall variant should be shifted: every element's z range should be ≥ ground's
        for ((g, w) in ground.elements.zip(wall.elements)) {
            assertTrue(
                "wall z should be ≥ ground z for element (g.from=${g.from}, w.from=${w.from})",
                w.from[2] >= g.from[2],
            )
            assertTrue(
                "wall to.z should be ≥ ground to.z (g.to=${g.to}, w.to=${w.to})",
                w.to[2] >= g.to[2],
            )
        }
    }

    @Test
    fun build_usesCorrectTexture() {
        val model = SyntheticEnderDragonHead.build(
            "minecraft:textures/entity/enderdragon/dragon",
            isWall = false,
        )
        for (elem in model.elements) {
            for ((_, face) in elem.faces) {
                assertEquals(
                    "every face should use the dragon.png texture",
                    "minecraft:textures/entity/enderdragon/dragon",
                    face.texture,
                )
            }
        }
    }

    @Test
    fun build_acceptsShortTexturePath() {
        val model = SyntheticEnderDragonHead.build(
            "entity/enderdragon/dragon",
            isWall = false,
        )
        assertNotNull(model)
        val firstFace = model.elements[0].faces.values.first()
        assertEquals("minecraft:entity/enderdragon/dragon", firstFace.texture)
    }

    @Test
    fun build_uvsFocusOnDragonHeadRegion() {
        // Now verifying the precise MCP-based UV mapping of upper_head.
        // For upper_head: u=112.0, v=30.0, dx=16.0, dy=16.0, dz=16.0, mirror=false.
        // up face UV: [(112+16)/16.0, 30.0/16.0, (112+16+16)/16.0, (30+16)/16.0] = [8.0, 1.875, 9.0, 2.875]
        val model = SyntheticEnderDragonHead.build(
            "minecraft:textures/entity/enderdragon/dragon",
            isWall = false,
        )
        val upperHead = model.elements[0]
        val upFaceUV = upperHead.faces["up"]?.uv
        assertNotNull(upFaceUV)
        assertEquals(4, upFaceUV!!.size)
        assertEquals(8.0, upFaceUV[0], 1e-5)
        assertEquals(1.875, upFaceUV[1], 1e-5)
        assertEquals(9.0, upFaceUV[2], 1e-5)
        assertEquals(2.875, upFaceUV[3], 1e-5)

        // north face UV: [(112+16)/16.0, (30+16)/16.0, (112+16+16)/16.0, (30+16+16)/16.0] = [8.0, 2.875, 9.0, 3.875]
        val northFaceUV = upperHead.faces["north"]?.uv
        assertNotNull(northFaceUV)
        assertEquals(8.0, northFaceUV!![0], 1e-5)
        assertEquals(2.875, northFaceUV[1], 1e-5)
        assertEquals(9.0, northFaceUV[2], 1e-5)
        assertEquals(3.875, northFaceUV[3], 1e-5)
    }

    @Test
    fun build_elementsCoverExpectedYRange() {
        val model = SyntheticEnderDragonHead.build(
            "minecraft:textures/entity/enderdragon/dragon",
            isWall = false,
        )
        // Dragon head spans y=1.986666 (jaw bottom) to y=16.986666 (scale top)
        val minY = model.elements.minOf { it.from[1] }
        val maxY = model.elements.maxOf { it.to[1] }
        assertEquals("min y is 1.986666 (jaw bottom)", 1.986666, minY, 1e-5)
        assertEquals("max y is 16.986666 (scale top)", 16.986666, maxY, 1e-5)
    }
}
