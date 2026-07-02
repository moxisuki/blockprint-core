package io.github.moxisuki.blockprint.core.glb.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

/**
 * Area 1 — `ModelResolver` cache wiring tests.
 *
 * Before the fix, the `modelCache` field on [ModelResolver] was dead
 * code: the existing `mutableMapOf<String, ResolvedModel>()` was never
 * written to and the only thing that touched it was `close()` calling
 * `clear()`. Every call to `ModelResolver.resolveModel` (and every
 * nested call from `resolveBlockstate` / `resolveWithParent`) re-read
 * the JSON file from disk and re-parsed it. For a 500k-block region
 * with ~100 unique palette entries that was 600-1 500 redundant
 * `Files.readAllBytes` + `JsonParser.parseObject` calls per region
 * conversion.
 *
 * These tests pin the post-fix contract:
 *   1. `resolveModel` populates `modelCache` (even for missing assets
 *      where it returns `emptyCube()`)
 *   2. Two `resolveModel` calls with the same input return the SAME
 *      cached instance (referential equality)
 *   3. `resolveWithoutAdapter` populates the blockstate-root cache
 *      for a blockstate file that actually exists on disk
 *   4. `close()` clears both caches (lifecycle)
 */
class ModelResolverCacheTest {

    @Test
    fun resolveModel_populates_cache_for_missing_assets() {
        val resolver = ModelResolver(emptyList())
        assertEquals(
            "cache must start empty (no previous calls)",
            0, resolver.modelCache.size,
        )
        val result = resolver.resolveModel("minecraft:does_not_exist_xyz")
        // After the fix the empty-cube fallback is itself cached so a
        // second call doesn't re-walk the asset-list lookups.
        assertEquals(1, resolver.modelCache.size)
        assertSame(
            "cached entry must be the same instance returned to the caller",
            result, resolver.modelCache["minecraft/models/does_not_exist_xyz.json"],
        )
    }

    @Test
    fun resolveModel_returns_same_instance_on_repeated_calls() {
        val resolver = ModelResolver(emptyList())
        val first = resolver.resolveModel("minecraft:another_missing_block")
        val second = resolver.resolveModel("minecraft:another_missing_block")
        assertSame(
            "second call must return the cached instance, not a fresh empty-cube",
            first, second,
        )
    }

    @Test
    fun resolveBlockstate_populates_root_cache_for_real_blockstate() {
        val assetsDir = Paths.get("test/assets")
        if (!java.nio.file.Files.isDirectory(assetsDir)) {
            // If assets are not present in this checkout (e.g. CI without
            // the test fixture) skip the test rather than fail.
            return
        }
        val resolver = ModelResolver(listOf(assetsDir))
        assertEquals(
            "blockstateRootCache must start empty",
            0, resolver.blockstateRootCache.size,
        )
        // `stone` exists at test/assets/minecraft/blockstates/stone.json.
        resolver.resolveWithoutAdapter("minecraft", "stone", null)
        assertNotNull(
            "blockstateRootCache should have stone after first resolve",
            resolver.blockstateRootCache["minecraft/blockstates/stone.json"],
        )
    }

    @Test
    fun close_clears_both_caches() {
        val resolver = ModelResolver(emptyList())
        resolver.resolveModel("minecraft:seed_for_close_test")
        assertTrue(
            "modelCache should be non-empty before close",
            resolver.modelCache.isNotEmpty(),
        )
        resolver.close()
        assertEquals("modelCache should be empty after close", 0, resolver.modelCache.size)
        assertEquals(
            "blockstateRootCache should be empty after close",
            0, resolver.blockstateRootCache.size,
        )
    }
}
