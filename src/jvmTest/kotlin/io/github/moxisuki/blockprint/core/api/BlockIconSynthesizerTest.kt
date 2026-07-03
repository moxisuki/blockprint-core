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
}
