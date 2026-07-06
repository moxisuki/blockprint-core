package io.github.moxisuki.blockprint.core.api

import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.Position
import io.github.moxisuki.blockprint.core.SchematicFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BlueprintBuilderTest {

    // ---------- BlockState.parse ----------

    @Test fun parse_stateless_block() {
        val bs = BlockState.parse("minecraft:stone")
        assertEquals("minecraft:stone", bs.name)
        assertEquals(null, bs.properties)
    }

    @Test fun parse_block_with_properties() {
        val bs = BlockState.parse("minecraft:oak_log[axis=y]")
        assertEquals("minecraft:oak_log", bs.name)
        assertEquals(mapOf("axis" to "y"), bs.properties)
    }

    @Test fun parse_block_with_multiple_properties() {
        val bs = BlockState.parse("minecraft:oak_fence[east=true,north=false,west=false,south=false,waterlogged=false]")
        assertEquals("minecraft:oak_fence", bs.name)
        assertEquals(5, bs.properties?.size)
        assertEquals("true", bs.properties?.get("east"))
    }

    @Test fun parse_block_without_colon_throws() {
        try {
            BlockState.parse("stone")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("colon"))
        }
    }

    @Test fun parse_empty_brackets() {
        val bs = BlockState.parse("minecraft:stone[]")
        assertEquals("minecraft:stone", bs.name)
        assertEquals(null, bs.properties)
    }

    @Test fun parse_roundtrip_to_string() {
        val original = BlockState("minecraft:oak_log", mapOf("axis" to "y"))
        val reparsed = BlockState.parse(original.toString())
        assertEquals(original, reparsed)
    }

    // ---------- BlueprintBuilder ----------

    @Test fun builder_creates_document_with_metadata() {
        val doc = BlueprintBuilder()
            .name("Test House")
            .author("Alice")
            .description("A simple house")
            .dataVersion(3953)
            .version(6)
            .format(SchematicFormat.Litematica)
            .region("main", 5, 3, 5) {}
            .build()

        assertEquals("Test House", doc.name)
        assertEquals("Alice", doc.author)
        assertEquals("A simple house", doc.description)
        assertEquals(3953, doc.minecraftDataVersion)
        assertEquals(6, doc.version)
        assertEquals(SchematicFormat.Litematica, doc.format)
        assertEquals(1, doc.regions.size)
    }

    @Test fun builder_default_metadata() {
        val doc = BlueprintBuilder()
            .region("r", 2, 2, 2) {}
            .build()

        assertEquals("", doc.name)
        assertEquals("", doc.author)
        assertEquals(1, doc.regions.size)
    }

    @Test fun region_has_correct_dimensions() {
        val doc = BlueprintBuilder()
            .region("test", 3, 4, 5) {}
            .build()

        val region = doc.regions[0]
        assertEquals("test", region.name)
        assertEquals(3, region.width)
        assertEquals(4, region.height)
        assertEquals(5, region.depth)
        assertEquals(Position.ZERO, region.position)
    }

    @Test fun region_with_custom_position() {
        val doc = BlueprintBuilder()
            .region("offset", 1, 1, 1) {
                position(10, 20, 30)
            }
            .build()

        val region = doc.regions[0]
        assertEquals(Position(10, 20, 30), region.position)
    }

    @Test fun set_single_block() {
        val doc = BlueprintBuilder()
            .region("r", 2, 1, 2) {
                set(0, 0, 0, "minecraft:stone")
                set(1, 0, 1, "minecraft:dirt")
            }
            .build()

        val region = doc.regions[0]
        assertEquals(3, region.palette.size) // air + stone + dirt
        assertEquals("minecraft:stone", region.blockAt(0, 0, 0).name)
        assertEquals("minecraft:dirt", region.blockAt(1, 0, 1).name)
        assertTrue(region.isAir(0, 0, 1))
    }

    @Test fun set_block_with_block_state_object() {
        val doc = BlueprintBuilder()
            .region("r", 1, 1, 1) {
                set(0, 0, 0, BlockState("minecraft:birch_planks", null))
            }
            .build()

        val region = doc.regions[0]
        assertEquals("minecraft:birch_planks", region.blockAt(0, 0, 0).name)
    }

    @Test fun set_block_with_properties() {
        val doc = BlueprintBuilder()
            .region("r", 1, 1, 1) {
                set(0, 0, 0, "minecraft:oak_slab[type=top,waterlogged=false]")
            }
            .build()

        val region = doc.regions[0]
        val slab = region.blockAt(0, 0, 0)
        assertEquals("minecraft:oak_slab", slab.name)
        assertEquals("top", slab.properties?.get("type"))
        assertEquals("false", slab.properties?.get("waterlogged"))
    }

    @Test fun air_clears_block() {
        val doc = BlueprintBuilder()
            .region("r", 1, 1, 1) {
                set(0, 0, 0, "minecraft:stone")
                air(0, 0, 0)
            }
            .build()

        val region = doc.regions[0]
        assertTrue(region.isAir(0, 0, 0))
    }

    @Test fun fill_cuboid() {
        val doc = BlueprintBuilder()
            .region("r", 4, 4, 4) {
                fill(0, 0, 0, 3, 3, 3, "minecraft:stone")
            }
            .build()

        val region = doc.regions[0]
        assertEquals(64, doc.blockCount())
        for (y in 0 until 4) {
            for (z in 0 until 4) {
                for (x in 0 until 4) {
                    assertEquals("minecraft:stone", region.blockAt(x, y, z).name)
                }
            }
        }
    }

    @Test fun fill_partial_region() {
        val doc = BlueprintBuilder()
            .region("r", 4, 4, 4) {
                fill(0, 0, 0, 1, 1, 1, "minecraft:stone")
            }
            .build()

        val region = doc.regions[0]
        assertEquals("minecraft:stone", region.blockAt(0, 0, 0).name)
        assertEquals("minecraft:stone", region.blockAt(1, 1, 1).name)
        assertTrue(region.isAir(2, 2, 2))
    }

    @Test fun fill_with_position_objects() {
        val doc = BlueprintBuilder()
            .region("r", 2, 2, 2) {
                fill(Position(0, 0, 0), Position(1, 1, 1), "minecraft:dirt")
            }
            .build()

        val region = doc.regions[0]
        for (y in 0 until 2) {
            for (z in 0 until 2) {
                for (x in 0 until 2) {
                    assertEquals("minecraft:dirt", region.blockAt(x, y, z).name)
                }
            }
        }
    }

    @Test fun fill_reversed_coordinates() {
        val doc = BlueprintBuilder()
            .region("r", 3, 1, 3) {
                fill(2, 0, 2, 0, 0, 0, "minecraft:stone")
            }
            .build()

        val region = doc.regions[0]
        assertEquals("minecraft:stone", region.blockAt(0, 0, 0).name)
        assertEquals("minecraft:stone", region.blockAt(2, 0, 2).name)
    }

    @Test fun fill_clamped_out_of_bounds() {
        val doc = BlueprintBuilder()
            .region("r", 2, 2, 2) {
                fill(-1, -1, -1, 5, 5, 5, "minecraft:stone")
            }
            .build()

        val region = doc.regions[0]
        assertEquals(8, doc.blockCount())
    }

    @Test fun fill_air() {
        val doc = BlueprintBuilder()
            .region("r", 2, 1, 2) {
                fill(0, 0, 0, 1, 0, 1, "minecraft:stone")
                fillAir(0, 0, 0, 0, 0, 0)
            }
            .build()

        val region = doc.regions[0]
        assertTrue(region.isAir(0, 0, 0))
        assertFalse(region.isAir(1, 0, 1))
    }

    @Test fun get_block_index() {
        val builder = RegionBuilder("r", 2, 1, 2)
        builder.set(0, 0, 0, "minecraft:stone")
        assertEquals(1, builder.getBlockIndex(0, 0, 0))
        assertEquals(0, builder.getBlockIndex(1, 0, 0))
    }

    @Test fun get_block_state_from_builder() {
        val builder = RegionBuilder("r", 2, 1, 2)
        builder.set(0, 0, 0, "minecraft:stone")
        assertEquals("minecraft:stone", builder.getBlockState(0, 0, 0).name)
    }

    @Test fun palette_auto_register() {
        val builder = RegionBuilder("r", 1, 1, 1)
        assertEquals(1, builder.paletteSize()) // air only

        builder.set(0, 0, 0, "minecraft:stone")
        assertEquals(2, builder.paletteSize())

        builder.set(0, 0, 0, "minecraft:dirt")
        assertEquals(3, builder.paletteSize())

        builder.set(0, 0, 0, "minecraft:stone") // re-use existing
        assertEquals(3, builder.paletteSize())
    }

    @Test fun non_air_count() {
        val builder = RegionBuilder("r", 2, 2, 2)
        assertEquals(0, builder.nonAirCount())

        builder.set(0, 0, 0, "minecraft:stone")
        assertEquals(1, builder.nonAirCount())

        builder.fill(0, 0, 0, 1, 0, 1, "minecraft:dirt")
        assertEquals(4, builder.nonAirCount())

        builder.air(0, 0, 0)
        assertEquals(3, builder.nonAirCount())
    }

    @Test fun multiple_regions() {
        val doc = BlueprintBuilder()
            .region("regionA", 2, 1, 2) {
                set(0, 0, 0, "minecraft:stone")
            }
            .region("regionB", 3, 1, 3) {
                set(1, 0, 1, "minecraft:dirt")
            }
            .build()

        assertEquals(2, doc.regions.size)
        assertEquals("regionA", doc.regions[0].name)
        assertEquals("regionB", doc.regions[1].name)
        assertEquals("minecraft:stone", doc.regions[0].blockAt(0, 0, 0).name)
        assertEquals("minecraft:dirt", doc.regions[1].blockAt(1, 0, 1).name)
    }

    @Test fun out_of_bounds_throws() {
        try {
            val builder = RegionBuilder("r", 2, 1, 2)
            builder.set(5, 0, 0, "minecraft:stone")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("out of bounds"))
        }
    }

    @Test fun zero_dimensions_throws() {
        try {
            BlueprintBuilder().region("r", 0, 1, 1) {}
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("positive"))
        }
    }

    @Test fun built_region_has_correct_raw_blocks() {
        val doc = BlueprintBuilder()
            .region("r", 2, 2, 2) {
                set(0, 0, 0, "minecraft:stone")   // index 1
                set(1, 1, 1, "minecraft:dirt")     // index 2
            }
            .build()

        val region = doc.regions[0]
        assertEquals(3, region.palette.size) // air, stone, dirt
        assertEquals(1, region.getBlock(0, 0, 0))
        assertEquals(2, region.getBlock(1, 1, 1))
        assertEquals(0, region.getBlock(0, 0, 1))
    }

    @Test fun builder_is_fluent() {
        val doc = BlueprintBuilder()
            .name("Test")
            .author("Bob")
            .region("r", 1, 1, 1) {
                position(1, 2, 3)
                set(0, 0, 0, "minecraft:stone")
            }
            .build()

        assertNotNull(doc)
        assertEquals(1, doc.regions.size)
    }

    @Test fun empty_region_all_air() {
        val doc = BlueprintBuilder()
            .region("empty", 3, 3, 3) {}
            .build()

        val region = doc.regions[0]
        assertEquals(0, doc.blockCount())
        for (y in 0 until 3) {
            for (z in 0 until 3) {
                for (x in 0 until 3) {
                    assertTrue(region.isAir(x, y, z))
                }
            }
        }
    }
}
