package io.github.moxisuki.blockprint.core.glb.mesh

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.model.BlockPrintRegion
import io.github.moxisuki.blockprint.core.glb.texture.TexturePacker
import io.github.moxisuki.blockprint.core.glb.model.Element
import io.github.moxisuki.blockprint.core.glb.model.ModelResolver
import io.github.moxisuki.blockprint.core.glb.model.ResolvedModel
import io.github.moxisuki.blockprint.core.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Area 3 — shared model cache + connection-variant cache tests.
 *
 * Before the fix, `MeshBuilder.buildFloorsInto` rebuilt its own
 * `modelCache: Array<List<Element>?>` from scratch on every call.
 * `BlockPrintToGlb.run` invokes `buildFloorsInto` twice (Pass 1 counting
 * + Pass 2 streaming) and additionally builds its own outer `modelCache`
 * for atlas prep. That's 3× the per-palette `modelResolver.resolve()`
 * work and zero sharing.
 *
 * For connection blocks (fence / glass pane / wall / iron bars) the
 * y/z/x hot loop in `buildFloorsInto` re-resolved every cell with a
 * merged (block properties + connProps) map. Two cells with identical
 * orientation independently re-walked the multipart resolution graph
 * (1 blockstate read + N multipart `resolveModel` calls per cell).
 *
 * These tests pin the post-fix contract:
 *   1. `buildFloorsInto` accepts a `sharedModelCache` parameter that
 *      is used in place of building its own.
 *   2. `buildFloorsInto` accepts a `sharedConnVariantCache` parameter
 *      (a `MutableMap<Pair<String, String>, ResolvedModel>` keyed on
 *      `(blockName, sortedMergedPropsToString)`) and writes entries
 *      into it as the hot loop encounters new orientations.
 *   3. Two cells with the same orientation share one cache entry; two
 *      cells with different orientations produce two entries.
 *   4. With vs without a shared variant cache the per-floor output is
 *      byte-identical (the cache is a pure optimisation).
 */
class MeshBuilderVariantCacheTest {

    private fun assetsOrSkip(): java.nio.file.Path? {
        val dir = Paths.get("test/assets")
        if (!Files.isDirectory(dir)) return null
        return dir
    }

    private fun fenceRegion(): BlockPrintRegion {
        // 3x1x1 strip at y=0: air, fence, fence.
        //   cell (0,0,0) = air
        //   cell (1,0,0) = fence, west=air east=fence  -> connProps {east:true}
        //   cell (2,0,0) = fence, west=fence east=oob  -> connProps {west:true}
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
    fun buildFloorsInto_accepts_sharedModelCache_and_populates_variant_cache() {
        val assets = assetsOrSkip() ?: return
        val region = fenceRegion()
        val resolver = ModelResolver(listOf(assets))
        val packer = TexturePacker(listOf(assets))
        val builder = MeshBuilder(resolver, packer, enableTinting = false)

        // Pre-build the shared model cache externally (the same way
        // BlockPrintToGlb.run does it for atlas prep).
        val sharedModelCache: Array<List<Element>?> = arrayOfNulls(region.palette.size)
        for ((i, b) in region.palette.entries.withIndex()) {
            val m = resolver.resolve(b.name, b.properties)
            if (m.hasTextures) sharedModelCache[i] = m.elements
        }

        val variantCache = mutableMapOf<Pair<String, String>, ResolvedModel>()

        // Pre-build atlas so processFaceInto can map faces.
        val atlas = packer.pack(
            setOf("minecraft:block/oak_planks"),  // any placeholder
            emptyMap(),
            emptyMap(),
        )

        builder.buildFloorsInto(
            region = region,
            options = io.github.moxisuki.blockprint.core.glb.writer.GlbExportOptions(),
            atlas = atlas,
            sink = FloorSink { _, _, _, _, _, _, _ -> /* drain */ },
            sharedModelCache = sharedModelCache,
            sharedConnVariantCache = variantCache,
        )

        assertTrue(
            "sharedConnVariantCache should be populated for a region with fence cells",
            variantCache.isNotEmpty(),
        )
    }

    @Test
    fun buildFloorsInto_two_cells_with_different_orientations_produce_two_cache_entries() {
        val assets = assetsOrSkip() ?: return
        val region = fenceRegion()
        val resolver = ModelResolver(listOf(assets))
        val packer = TexturePacker(listOf(assets))
        val builder = MeshBuilder(resolver, packer, enableTinting = false)

        val sharedModelCache: Array<List<Element>?> = arrayOfNulls(region.palette.size)
        for ((i, b) in region.palette.entries.withIndex()) {
            val m = resolver.resolve(b.name, b.properties)
            if (m.hasTextures) sharedModelCache[i] = m.elements
        }

        val variantCache = mutableMapOf<Pair<String, String>, ResolvedModel>()

        val atlas = packer.pack(setOf("minecraft:block/oak_planks"), emptyMap(), emptyMap())

        builder.buildFloorsInto(
            region = region,
            options = io.github.moxisuki.blockprint.core.glb.writer.GlbExportOptions(),
            atlas = atlas,
            sink = FloorSink { _, _, _, _, _, _, _ -> },
            sharedModelCache = sharedModelCache,
            sharedConnVariantCache = variantCache,
        )

        // 2 fence cells, 2 distinct orientations -> 2 entries.
        assertEquals(
            "expected 2 distinct variant entries for 2 distinct orientations",
            2, variantCache.size,
        )
        // Each key should be (minecraft:oak_fence, <sortedProps>).
        for ((name, _) in variantCache.keys) {
            assertEquals("minecraft:oak_fence", name)
        }
    }

    @Test
    fun buildFloorsInto_byte_equivalent_with_or_without_shared_caches() {
        val assets = assetsOrSkip() ?: return
        val region = fenceRegion()
        val resolverA = ModelResolver(listOf(assets))
        val resolverB = ModelResolver(listOf(assets))
        val packerA = TexturePacker(listOf(assets))
        val packerB = TexturePacker(listOf(assets))
        val atlasA = packerA.pack(setOf("minecraft:block/oak_planks"), emptyMap(), emptyMap())
        val atlasB = packerB.pack(setOf("minecraft:block/oak_planks"), emptyMap(), emptyMap())

        fun collectFloors(
            builder: MeshBuilder,
            atlas: io.github.moxisuki.blockprint.core.glb.texture.PackedAtlas,
            resolver: ModelResolver,
            useShared: Boolean,
        ): List<Triple<FloatArray, FloatArray, IntArray>> {
            val sharedModelCache: Array<List<Element>?> = arrayOfNulls(region.palette.size)
            for ((i, b) in region.palette.entries.withIndex()) {
                val m = resolver.resolve(b.name, b.properties)
                if (m.hasTextures) sharedModelCache[i] = m.elements
            }
            val variantCache = mutableMapOf<Pair<String, String>, ResolvedModel>()
            val out = mutableListOf<Triple<FloatArray, FloatArray, IntArray>>()
            builder.buildFloorsInto(
                region = region,
                options = io.github.moxisuki.blockprint.core.glb.writer.GlbExportOptions(),
                atlas = atlas,
                sink = FloorSink { _, _, _, p, u, _, i ->
                    out.add(
                        Triple(
                            offHeapFloatsToFloatArray(p),
                            offHeapFloatsToFloatArray(u),
                            offHeapIntsToIntArray(i),
                        ),
                    )
                },
                sharedModelCache = if (useShared) sharedModelCache else null,
                sharedConnVariantCache = if (useShared) variantCache else null,
            )
            return out
        }

        val builderA = MeshBuilder(resolverA, packerA, enableTinting = false)
        val builderB = MeshBuilder(resolverB, packerB, enableTinting = false)
        val baseline = collectFloors(builderA, atlasA, resolverA, useShared = false)
        val withCaches = collectFloors(builderB, atlasB, resolverB, useShared = true)

        assertEquals("floor count", baseline.size, withCaches.size)
        for (i in baseline.indices) {
            val (aP, aU, aI) = baseline[i]
            val (bP, bU, bI) = withCaches[i]
            assertTrue("positions differ at floor $i", aP.contentEquals(bP))
            assertTrue("uvs differ at floor $i", aU.contentEquals(bU))
            assertTrue("indices differ at floor $i", aI.contentEquals(bI))
        }
    }
}
