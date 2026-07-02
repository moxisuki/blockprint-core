package io.github.moxisuki.blockprint.core.glb.texture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Area A — `PackedAtlas.fallback` test.
 *
 * Before the fix, every face whose texture wasn't a direct key in
 * `atlas.mappings` (e.g. multipart sub-model references that didn't
 * get pre-collected by the atlas prep) triggered
 * `atlas.mappings.values.firstOrNull()` — an O(N) traversal of the
 * map per face. For a 16³ solid-fence region (~75-100k visible
 * faces, ~50-200 atlas entries) that's millions of unnecessary
 * map traversals per region.
 *
 * `PackedAtlas.fallback` caches the first non-null entry of the
 * `mappings` map at construction time so the hot path becomes an
 * O(1) property read.
 */
class PackedAtlasFallbackTest {

    @Test
    fun fallback_returns_first_entry_for_non_empty_atlas() {
        val entryA = AtlasEntry(u1 = 0.0f, v1 = 0.0f, u2 = 0.5f, v2 = 0.5f)
        val entryB = AtlasEntry(u1 = 0.5f, v1 = 0.0f, u2 = 1.0f, v2 = 0.5f)
        val atlas = PackedAtlas(
            pngBytes = byteArrayOf(),
            width = 16, height = 16,
            mappings = linkedMapOf("a" to entryA, "b" to entryB),
        )
        assertNotNull(atlas.fallback)
        // The fallback must be the first non-null entry in iteration
        // order. LinkedHashMap preserves insertion order, so the
        // first value is the value of the first key.
        assertSame(entryA, atlas.fallback)
    }

    @Test
    fun fallback_is_null_for_empty_atlas() {
        val atlas = PackedAtlas(
            pngBytes = byteArrayOf(),
            width = 16, height = 16,
            mappings = emptyMap(),
        )
        assertNull(atlas.fallback)
    }

    @Test
    fun fallback_matches_firstOrNull_of_mappings() {
        val entry = AtlasEntry(u1 = 0.25f, v1 = 0.25f, u2 = 0.75f, v2 = 0.75f)
        val atlas = PackedAtlas(
            pngBytes = byteArrayOf(),
            width = 16, height = 16,
            mappings = linkedMapOf("only" to entry),
        )
        assertEquals(atlas.mappings.values.firstOrNull(), atlas.fallback)
    }
}
