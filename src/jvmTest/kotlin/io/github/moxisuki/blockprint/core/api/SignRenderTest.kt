package io.github.moxisuki.blockprint.core.api

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * End-to-end regression test for the bug where signs and hanging signs
 * rendered as black icons: the synthetic model returned a non-empty
 * ResolvedModel, but its face textures had a path that missed the
 * `textures/` segment, so TexturePacker.readPng silently dropped the
 * texture and every face with it. This test asserts that the full
 * BlockPrintToGlb pipeline emits a non-empty mesh for these blocks
 * against the vanilla 1.20.4 asset tree.
 */
class SignRenderTest {
    private fun assetsOrSkip(): java.nio.file.Path? {
        val p = Paths.get("src/jvmTest/resources/test/assets")
        if (!Files.isDirectory(p)) return null
        return p
    }

    private fun assertHasMeshes(blockName: String, ns: String = "minecraft") {
        val assets = assetsOrSkip() ?: return
        assumeTrue("test assets present", Files.isDirectory(assets))
        val doc = BlockIconSynthesizer.synthesize(ns, blockName)
        val glb = BlockPrintToGlb.convertToBytes(doc, listOf(assets))
        // Walk the JSON chunk: GLB header is [magic 4B][version 4B LE][length 4B LE]
        // [chunk0-length 4B LE][chunk0-type 4B][chunk0-data N bytes]
        val jsonLen = java.nio.ByteBuffer.wrap(glb)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .position(12)
            .int
        val json = String(glb, 20, jsonLen, Charsets.UTF_8)
        val match = Regex("\"meshes\"\\s*:\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
            .find(json) ?: error("GLB JSON has no `meshes` array:\n$json")
        val inside = match.groupValues[1].trim()
        // An empty meshes array is just whitespace/comma. A populated
        // array contains at least one '{' from a primitive.
        assertTrue(
            "$blockName GLB has empty meshes array:\n$json",
            inside.isNotEmpty() && inside.contains("{"),
        )
    }

    @Test fun `bamboo_hanging_sign produces non-empty GLB`() {
        assertHasMeshes("bamboo_hanging_sign")
    }

    @Test fun `bamboo_sign produces non-empty GLB`() {
        assertHasMeshes("bamboo_sign")
    }

    @Test fun `oak_wall_sign produces non-empty GLB`() {
        assertHasMeshes("oak_wall_sign")
    }

    @Test fun `acacia_hanging_sign produces non-empty GLB`() {
        assertHasMeshes("acacia_hanging_sign")
    }

    @Test fun `acacia_wall_hanging_sign produces non-empty GLB`() {
        assertHasMeshes("acacia_wall_hanging_sign")
    }
}
