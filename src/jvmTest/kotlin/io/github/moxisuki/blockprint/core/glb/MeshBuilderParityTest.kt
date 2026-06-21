package io.github.moxisuki.blockprint.core.glb

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.LitematicRegion
import io.github.moxisuki.blockprint.core.Position
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Arrays

class MeshBuilderParityTest {

    /**
     * Parity test: the OLD `build()` and the NEW `countFloorStats() + buildFloorsInto()`
     * pipeline must produce byte-identical [GlbOutput] for any input region.
     *
     * Uses a synthetic region with diverse blocks to exercise culling, rotation,
     * and connection blocks. The test exists before Task 6 rewrites `build()` to
     * use the new pipeline, to lock in the invariant. If the rewrite introduces
     * any drift, this test fails immediately.
     */
    @Test
    fun new_two_pass_pipeline_matches_legacy_build_byte_for_byte() {
        val palette = BlockPalette(
            listOf(
                BlockState("minecraft:air"),
                BlockState("minecraft:stone"),
                BlockState("minecraft:dirt"),
                BlockState("minecraft:oak_planks"),
                BlockState("minecraft:glass"),
            ),
        )
        // 4x3x2 region with mixed blocks.
        // y-major index = y * (W*D) + z * W + x = y * 8 + z * 4 + x
        val blocks = IntArray(4 * 3 * 2) { i ->
            when (i % 5) {
                0 -> 0 // air
                1 -> 1 // stone
                2 -> 2 // dirt
                3 -> 3 // oak_planks
                else -> 4 // glass
            }
        }
        val region = LitematicRegion(
            name = "Mixed",
            width = 4, height = 3, depth = 2,
            position = Position(10, 64, -5),
            palette = palette,
            blocks = blocks,
        )

        val builder = MeshBuilder(
            modelResolver = ModelResolver(emptyList()),
            texturePacker = TexturePacker(emptyList()),
            enableTinting = false,
        )

        // LEGACY: collect via build().
        val legacy = builder.build(
            region = region,
            originX = 0, originY = 0, originZ = 0,
            options = GlbExportOptions(),
        )

        // NEW: collect via countFloorStats + buildFloorsInto.
        val stats = builder.countFloorStats(region, GlbExportOptions())
        val collected = mutableListOf<GlbOutput>()
        val placeholderAtlas = GlbAtlas(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), 1, 1)
        builder.buildFloorsInto(
            region = region,
            originX = 0, originY = 0, originZ = 0,
            options = GlbExportOptions(),
            sink = FloorSink { floorIdx, yMin, yMax, positions, uvs, normals, indices ->
                collected.add(
                    GlbOutput(
                        floors = listOf(
                            FloorSlice(
                                yMin = yMin, yMax = yMax,
                                positions = positions, uvs = uvs,
                                normals = normals, indices = indices,
                            ),
                        ),
                        atlasPng = placeholderAtlas.pngBytes,
                        atlasWidth = placeholderAtlas.width,
                        atlasHeight = placeholderAtlas.height,
                    ),
                )
            },
        )
        val newFloors = collected.flatMap { it.floors }
        val newOutput = GlbOutput(
            floors = newFloors,
            atlasPng = placeholderAtlas.pngBytes,
            atlasWidth = placeholderAtlas.width,
            atlasHeight = placeholderAtlas.height,
        )

        // Compare counts.
        val legacyVerts = legacy.floors.sumOf { it.positions.size / 3 }
        val newVerts = newOutput.floors.sumOf { it.positions.size / 3 }
        assertEquals("vertex count mismatch", legacyVerts, newVerts)
        assertEquals("floor count mismatch", legacy.floors.size, newOutput.floors.size)
        assertEquals(
            "total indices mismatch",
            legacy.floors.sumOf { it.indices.size },
            newOutput.floors.sumOf { it.indices.size },
        )

        // Compare positions byte-for-byte (per floor, in order).
        for ((legacyFloor, newFloor) in legacy.floors.zip(newOutput.floors)) {
            assertEquals("yMin mismatch", legacyFloor.yMin, newFloor.yMin)
            assertEquals("yMax mismatch", legacyFloor.yMax, newFloor.yMax)
            assertTrue(
                "positions mismatch: ${Arrays.toString(legacyFloor.positions)} vs ${Arrays.toString(newFloor.positions)}",
                legacyFloor.positions.contentEquals(newFloor.positions),
            )
            assertTrue(
                "uvs mismatch: ${Arrays.toString(legacyFloor.uvs)} vs ${Arrays.toString(newFloor.uvs)}",
                legacyFloor.uvs.contentEquals(newFloor.uvs),
            )
            assertTrue(
                "normals mismatch",
                (legacyFloor.normals ?: FloatArray(0)).contentEquals(newFloor.normals ?: FloatArray(0)),
            )
            assertArrayEquals("indices mismatch", legacyFloor.indices, newFloor.indices)
        }
    }
}
