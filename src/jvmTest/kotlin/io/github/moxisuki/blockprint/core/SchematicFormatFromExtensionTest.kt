package io.github.moxisuki.blockprint.core

import io.github.moxisuki.blockprint.core.exceptions.LitematicException
import org.junit.Assert.assertEquals
import org.junit.Test

class SchematicFormatFromExtensionTest {

    @Test
    fun litematic_maps_to_Litematica() {
        assertEquals(SchematicFormat.Litematica, SchematicFormat.fromExtension("litematic"))
        assertEquals(SchematicFormat.Litematica, SchematicFormat.fromExtension(".litematic"))
        assertEquals(SchematicFormat.Litematica, SchematicFormat.fromExtension("LITEMATIC"))
    }

    @Test
    fun schematic_maps_to_Sponge() {
        assertEquals(SchematicFormat.Sponge, SchematicFormat.fromExtension("schematic"))
    }

    @Test
    fun nbt_maps_to_Structure() {
        assertEquals(SchematicFormat.Structure, SchematicFormat.fromExtension("nbt"))
    }

    @Test
    fun json_maps_to_BuildingHelper() {
        assertEquals(SchematicFormat.BuildingHelper, SchematicFormat.fromExtension("json"))
    }

    @Test(expected = LitematicException::class)
    fun unknown_extension_throws() {
        SchematicFormat.fromExtension("bin")
    }

    @Test(expected = LitematicException::class)
    fun empty_extension_throws() {
        SchematicFormat.fromExtension("")
    }
}