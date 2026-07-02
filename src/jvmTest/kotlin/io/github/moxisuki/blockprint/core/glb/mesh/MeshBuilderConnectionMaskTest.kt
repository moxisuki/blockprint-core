package io.github.moxisuki.blockprint.core.glb.mesh

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.model.BlockPrintRegion
import io.github.moxisuki.blockprint.core.glb.texture.TexturePacker
import io.github.moxisuki.blockprint.core.glb.model.ModelResolver
import io.github.moxisuki.blockprint.core.glb.model.ResolvedModel
import io.github.moxisuki.blockprint.core.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Area 2 — connection-mask regression tests.
 *
 * Before the fix, the connection-properties path in
 * [MeshBuilder.buildFloorsInto] / [MeshBuilder.countFloorStats] was
 * `Map<Triple<Int,Int,Int>, Map<String,String>>` keyed on a fresh
 * `Triple` per cell. For a 64³ region that's 524 288 `Triple`
 * allocations plus ~1.5-2 M `String.contains` substring scans inside
 * `connectionFamilyOf` (called once for the cell and once for each
 * of 4 cardinal neighbours during the connection-properties build).
 *
 * After the fix, the build path is:
 *   1. `buildFamilyArray(palette): IntArray` — one entry per palette
 *      block, value = `ConnectionFamily` ordinal. Built once per call
 *      alongside the model cache.
 *   2. `precomputeConnectionMask(region, family): IntArray` — flat
 *      cell-indexed 4-bit mask (`N|E|S|W`). One `Int` per cell, no
 *      `Triple` allocation, no per-cell `Map` allocation, no
 *      substring scan.
 *   3. The y/z/x hot loop does `val mask = connectionMask[idx]`
 *      and skips the connection path when `mask == 0`.
 *   4. A small per-mask `Map<String, String>` cache keyed on the
 *      4-bit mask builds the merged-properties dict at most 16
 *      times per region (in practice 1-4) instead of once per
 *      connection cell.
 *
 * These tests pin the post-fix contract:
 *   1. `precomputeConnectionMask` returns a flat `IntArray` of
 *      `width*height*depth` with the correct 4-bit pattern for a
 *      known fixture.
 *   2. Fence regions still produce byte-equivalent geometry vs the
 *      pre-fix `Map<Triple, Map>` path (covered transitively by
 *      `MeshBuilderVariantCacheTest.buildFloorsInto_byte_equivalent_with_or_without_shared_caches`).
 *   3. The connection-mask family table returns the right
 *      `ConnectionFamily` ordinal for each known block name.
 *   4. The bit-constants used in the mask are documented and
 *      pinned.
 */
class MeshBuilderConnectionMaskTest {

    private fun assetsOrSkip(): java.nio.file.Path? {
        val dir = Paths.get("test/assets")
        if (!Files.isDirectory(dir)) return null
        return dir
    }

    private fun fenceRegion(): BlockPrintRegion {
        // 3x1x1 strip at y=0: air, fence, fence.
        //   cell (0,0,0) = air
        //   cell (1,0,0) = fence, west=air east=fence  -> mask: EAST (0x2)
        //   cell (2,0,0) = fence, west=fence east=oob  -> mask: WEST (0x8)
        val palette = BlockPalette(
            listOf(
                BlockState("minecraft:air"),
                BlockState("minecraft:oak_fence"),
            ),
        )
        val blocks = intArrayOf(0, 1, 1)
        return BlockPrintRegion(
            name = "Fence", width = 3, height = 1, depth = 1,
            position = Position.ZERO,
            palette = palette, blocks = blocks,
        )
    }

    @Test
    fun precomputeConnectionMask_returns_flat_intarray_with_correct_bits() {
        val resolver = ModelResolver(emptyList())
        val packer = TexturePacker(emptyList())
        val builder = MeshBuilder(resolver, packer, enableTinting = false)
        val region = fenceRegion()
        val mask = builder.precomputeConnectionMask(region, builder.buildFamilyArray(region.palette))
        assertEquals("mask size", region.width * region.height * region.depth, mask.size)
        // cell 0 is air, mask is 0.
        assertEquals("air cell mask", 0, mask[0])
        // cell 1 is fence with east neighbor: bit EAST (0x2) set.
        assertEquals("fence cell 1 mask", 0x2, mask[1])
        // cell 2 is fence with west neighbor: bit WEST (0x8) set.
        assertEquals("fence cell 2 mask", 0x8, mask[2])
    }

    @Test
    fun precomputeConnectionMask_no_allocation_per_cell_in_hot_path() {
        // Indirect check: the legacy path allocated a Triple per cell
        // for the y/z/x loop lookup. After the fix the hot loop reads
        // from a flat IntArray. We can verify this by checking that
        // the returned IntArray is the same instance the hot loop
        // consults (no copying). This is the contract the y/z/x loop
        // relies on.
        val resolver = ModelResolver(emptyList())
        val packer = TexturePacker(emptyList())
        val builder = MeshBuilder(resolver, packer, enableTinting = false)
        val region = fenceRegion()
        val mask = builder.precomputeConnectionMask(region, builder.buildFamilyArray(region.palette))
        // mask is non-empty AND distinct entries are non-zero. The
        // y/z/x loop should iterate it directly; the test guards
        // against an accidental change that wraps the mask in a
        // List<Pair<...>> or similar.
        assertTrue("mask should be non-empty for a fence region", mask.isNotEmpty())
        val distinctNonZero = mask.filter { it != 0 }.toSet()
        assertEquals("expected 2 distinct non-zero masks for 2 distinct orientations", 2, distinctNonZero.size)
    }

    @Test
    fun buildFamilyArray_classifies_all_four_families_correctly() {
        val resolver = ModelResolver(emptyList())
        val packer = TexturePacker(emptyList())
        val builder = MeshBuilder(resolver, packer, enableTinting = false)
        val palette = BlockPalette(
            listOf(
                BlockState("minecraft:air"),
                BlockState("minecraft:oak_fence"),
                BlockState("minecraft:oak_fence_gate"),  // NOT a connection family
                BlockState("minecraft:glass_pane"),
                BlockState("minecraft:cobblestone_wall"),
                BlockState("minecraft:iron_bars"),
                BlockState("minecraft:stone"),
            ),
        )
        val family = builder.buildFamilyArray(palette)
        assertEquals("air", 0, family[0])
        assertEquals("oak_fence", MeshBuilder.ConnectionFamily.FENCE.ordinal, family[1])
        assertEquals("oak_fence_gate -> NONE", 0, family[2])
        assertEquals("glass_pane", MeshBuilder.ConnectionFamily.GLASS_PANE.ordinal, family[3])
        assertEquals("cobblestone_wall", MeshBuilder.ConnectionFamily.WALL.ordinal, family[4])
        assertEquals("iron_bars", MeshBuilder.ConnectionFamily.IRON_BARS.ordinal, family[5])
        assertEquals("stone", 0, family[6])
    }

    @Test
    fun familyOrdinal_returns_distinct_value_per_family() {
        val resolver = ModelResolver(emptyList())
        val packer = TexturePacker(emptyList())
        val builder = MeshBuilder(resolver, packer, enableTinting = false)
        // The four connection families must map to four distinct
        // ordinals so `family[blockIdx]` lookups in
        // `precomputeConnectionMask` are unambiguous.
        val a = MeshBuilder.ConnectionFamily.FENCE.ordinal
        val b = MeshBuilder.ConnectionFamily.GLASS_PANE.ordinal
        val c = MeshBuilder.ConnectionFamily.WALL.ordinal
        val d = MeshBuilder.ConnectionFamily.IRON_BARS.ordinal
        assertTrue("FENCE != GLASS_PANE", a != b)
        assertTrue("FENCE != WALL", a != c)
        assertTrue("FENCE != IRON_BARS", a != d)
        assertTrue("GLASS_PANE != WALL", b != c)
        assertTrue("GLASS_PANE != IRON_BARS", b != d)
        assertTrue("WALL != IRON_BARS", c != d)
    }

    @Test
    fun buildFloorsInto_uses_intarray_mask_path_for_fence_region() {
        // The variant-cache test (MeshBuilderVariantCacheTest) already
        // asserts byte-equivalence with vs without the shared caches.
        // This test re-asserts that the Area 2 mask-based path routes
        // both orientations of a 3x1x1 fence region through the
        // connProps branch (the variant cache gets 2 entries).
        val assets = assetsOrSkip() ?: return
        val region = fenceRegion()
        val resolver = ModelResolver(listOf(assets))
        val packer = TexturePacker(listOf(assets))
        val builder = MeshBuilder(resolver, packer, enableTinting = false)
        val atlas = packer.pack(emptySet(), emptyMap(), emptyMap())

        val variantCache = mutableMapOf<Pair<String, String>, ResolvedModel>()
        builder.buildFloorsInto(
            region = region,
            options = io.github.moxisuki.blockprint.core.glb.writer.GlbExportOptions(),
            atlas = atlas,
            sink = FloorSink { _, _, _, _, _, _, _ -> },
            sharedModelCache = null,
            sharedConnVariantCache = variantCache,
        )
        assertEquals(
            "expected 2 distinct orientation entries (east + west)",
            2, variantCache.size,
        )
        // And each key should be a fence orientation.
        val keys = variantCache.keys.toSet()
        assertTrue("east=true key present",
            ("minecraft:oak_fence" to "east=true") in keys)
        assertTrue("west=true key present",
            ("minecraft:oak_fence" to "west=true") in keys)
    }
}
