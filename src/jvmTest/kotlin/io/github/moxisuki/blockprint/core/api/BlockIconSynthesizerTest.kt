package io.github.moxisuki.blockprint.core.api

import io.github.moxisuki.blockprint.core.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BlockIconSynthesizerTest {

    @Test fun stateless_block_produces_valid_document() {
        val doc = BlockIconSynthesizer.synthesize("minecraft", "stone")
        assertEquals(1, doc.regions.size)
        val region = doc.regions[0]
        assertEquals(1, region.width)
        assertEquals(1, region.height)
        assertEquals(1, region.depth)
        assertEquals(Position.ZERO, region.position)
        assertEquals(2, region.palette.size)
        assertEquals("minecraft:air", region.palette[0].name)
        assertEquals("minecraft:stone", region.palette[1].name)
        assertEquals(null, region.palette[1].properties)
        assertEquals(1, region.rawBlocks[0])
    }

    @Test fun block_with_properties_propagates_them() {
        val doc = BlockIconSynthesizer.synthesize(
            "minecraft",
            "oak_slab",
            mapOf("type" to "top", "waterlogged" to "false"),
        )
        val region = doc.regions[0]
        val slab = region.palette[1]
        assertEquals("minecraft:oak_slab", slab.name)
        assertEquals(mapOf("type" to "top", "waterlogged" to "false"), slab.properties)
    }

    @Test fun custom_namespace_is_preserved() {
        val doc = BlockIconSynthesizer.synthesize("create", "mechanical_cogwheel")
        assertEquals("create:mechanical_cogwheel", doc.regions[0].palette[1].name)
    }

    @Test fun document_metadata_fields_are_populated() {
        val doc = BlockIconSynthesizer.synthesize("minecraft", "dirt")
        assertEquals("BlockIcon", doc.name)
        assertEquals("blockprint-icons", doc.author)
        assertNotNull(doc.regions.firstOrNull())
    }

    @Test fun blank_namespace_rejected() {
        try {
            BlockIconSynthesizer.synthesize("", "stone")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("namespace"))
        }
    }

    @Test fun blank_name_rejected() {
        try {
            BlockIconSynthesizer.synthesize("minecraft", "")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("name"))
        }
    }

    @Test fun end_to_end_produces_glb() {
        // Smoke: feed the synthesized document to BlockPrintToGlb against the
        // bundled test/assets and confirm we get a valid GLB byte stream.
        // This guards against future drift in BlockPrintRegion/BlockPalette
        // shape that could silently break the synthesizer.
        val doc = BlockIconSynthesizer.synthesize("minecraft", "stone")
        val assetsDir = java.nio.file.Paths.get("test/assets").toAbsolutePath()
        if (!java.nio.file.Files.isDirectory(assetsDir)) {
            // Skip when running outside a checkout with test assets.
            return
        }
        val glb = BlockPrintToGlb.convertToBytes(doc, listOf(assetsDir))
        // GLB magic: 'glTF' little-endian = 0x46546C67.
        // ByteBuffer.wrap defaults to BIG_ENDIAN, so we must switch it explicitly.
        val magic = java.nio.ByteBuffer.wrap(glb)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .int
        assertEquals(0x46546C67.toInt(), magic)
        assertTrue("GLB should be at least 12 bytes (header)", glb.size >= 12)
    }

    @Test fun door_produces_1x2x1_region_with_both_halves() {
        val doc = BlockIconSynthesizer.synthesizeFromBlockstate(
            DOOR_BLOCKSTATE_JSON, "minecraft", "acacia_door",
        )
        assertEquals(1, doc.regions.size)
        val region = doc.regions[0]
        assertEquals(1, region.width)
        assertEquals(2, region.height)
        assertEquals(1, region.depth)
        assertEquals(Position.ZERO, region.position)
        // 0 = air, 1 = lower half, 2 = upper half
        assertEquals(3, region.palette.size)
        assertEquals("minecraft:air", region.palette[0].name)
        assertEquals("minecraft:acacia_door", region.palette[1].name)
        assertEquals("minecraft:acacia_door", region.palette[2].name)
    }

    @Test fun door_cells_have_correct_properties() {
        val doc = BlockIconSynthesizer.synthesizeFromBlockstate(
            DOOR_BLOCKSTATE_JSON, "minecraft", "acacia_door",
        )
        val region = doc.regions[0]
        val lower = region.blockAt(0, 0, 0)
        val upper = region.blockAt(0, 1, 0)
        assertEquals("lower", lower.properties?.get("half"))
        assertEquals("upper", upper.properties?.get("half"))
        assertEquals("east", lower.properties?.get("facing"))
        assertEquals("east", upper.properties?.get("facing"))
        assertEquals("left", lower.properties?.get("hinge"))
        assertEquals("left", upper.properties?.get("hinge"))
        assertEquals("false", lower.properties?.get("open"))
        assertEquals("false", upper.properties?.get("open"))
        // Non-door cells remain air.
        // (No off-grid cells in 1x2x1; just sanity-check palette index 0.)
        assertEquals(0, region.palette[0].name.compareTo("minecraft:air"))
    }

    @Test fun button_picks_face_floor_variant_when_available() {
        // The acacia_button blockstate lists face=ceiling,facing=east,powered=false first
        // (alphabetical), which would render a rotated plank in iso view.
        // synthesizeFromBlockstate must instead pick face=floor,facing=north,powered=false.
        val doc = BlockIconSynthesizer.synthesizeFromBlockstate(
            BUTTON_BLOCKSTATE_JSON, "minecraft", "acacia_button",
        )
        val region = doc.regions[0]
        assertEquals(1, region.width)
        assertEquals(1, region.height)
        assertEquals(1, region.depth)
        val button = region.palette[1]
        assertEquals("minecraft:acacia_button", button.name)
        assertEquals("floor", button.properties?.get("face"))
        assertEquals("north", button.properties?.get("facing"))
        assertEquals("false", button.properties?.get("powered"))
    }

    @Test fun stone_still_produces_1x1x1() {
        val doc = BlockIconSynthesizer.synthesizeFromBlockstate(
            STATELESS_BLOCKSTATE_JSON, "minecraft", "stone",
        )
        val region = doc.regions[0]
        assertEquals(1, region.width)
        assertEquals(1, region.height)
        assertEquals(1, region.depth)
        assertEquals(2, region.palette.size)
        assertEquals("minecraft:stone", region.palette[1].name)
        // Stateless variant: empty key → null properties.
        assertEquals(null, region.palette[1].properties)
        assertEquals(1, region.rawBlocks[0])
    }

    private companion object {
        // Trimmed copy of minecraft:blockstates/acacia_door.json — only the keys
        // are relevant; the synthesizer only walks `variants` and inspects the
        // first cell's properties.
        val DOOR_BLOCKSTATE_JSON = """
            {
              "variants": {
                "facing=east,half=lower,hinge=left,open=false": {
                  "model": "minecraft:block/acacia_door_bottom_left"
                },
                "facing=east,half=upper,hinge=left,open=false": {
                  "model": "minecraft:block/acacia_door_top_left"
                }
              }
            }
        """.trimIndent()

        // Trimmed copy of minecraft:blockstates/acacia_button.json — the first
        // entry is intentionally the ceiling/east variant, so a naive "first
        // wins" picker would render a rotated plank. The synthesizer must
        // prefer face=floor,facing=north,powered=false.
        val BUTTON_BLOCKSTATE_JSON = """
            {
              "variants": {
                "face=ceiling,facing=east,powered=false": {
                  "model": "minecraft:block/acacia_button"
                },
                "face=floor,facing=north,powered=false": {
                  "model": "minecraft:block/acacia_button"
                }
              }
            }
        """.trimIndent()

        val STATELESS_BLOCKSTATE_JSON = """
            { "variants": { "": { "model": "minecraft:block/stone" } } }
        """.trimIndent()
    }
}
