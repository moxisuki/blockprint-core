package io.github.moxisuki.blockprint.core.model

import io.github.moxisuki.blockprint.core.Litematic
import io.github.moxisuki.blockprint.core.LitematicReader
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BlockPrintDocumentAdapterTest {

    /**
     * Project convention: the bundled Litematica fixture lives at
     * `<projectRoot>/test/pre.litematic`. Gradle runs JVM tests with cwd =
     * project root, so a relative path resolves correctly.
     */
    private fun loadLegacy(): Litematic {
        val file = File("test/pre.litematic")
        require(file.exists()) {
            "Test fixture not found at ${file.absolutePath}"
        }
        return LitematicReader.read(file)
    }

    @Test
    fun fromLegacy_preserves_all_fields() {
        val legacy = loadLegacy()
        val doc = BlockPrintDocument.fromLegacy(legacy)

        assertEquals(legacy.minecraftDataVersion, doc.minecraftDataVersion)
        assertEquals(legacy.version, doc.version)
        assertEquals(legacy.name, doc.name)
        assertEquals(legacy.author, doc.author)
        assertEquals(legacy.description, doc.description)
        assertEquals(legacy.format, doc.format)
        assertEquals(legacy.regions.size, doc.regions.size)
        for ((a, b) in legacy.regions.zip(doc.regions)) {
            assertEquals(a.name, b.name)
            assertEquals(a.width, b.width)
            assertEquals(a.height, b.height)
            assertEquals(a.depth, b.depth)
            assertEquals(a.position, b.position)
            assertEquals(a.palette, b.palette)
            assertArrayEquals(a.toBlockArray(), b.toBlockArray())
        }
    }

    @Test
    fun blockCount_matches_legacy() {
        val legacy = loadLegacy()
        val doc = BlockPrintDocument.fromLegacy(legacy)
        assertEquals(legacy.blockCount(includeAir = false), doc.blockCount(includeAir = false))
        assertEquals(legacy.blockCount(includeAir = true), doc.blockCount(includeAir = true))
    }

    @Test
    fun primaryRegion_delegates() {
        val legacy = loadLegacy()
        val doc = BlockPrintDocument.fromLegacy(legacy)
        assertNotNull(doc.primaryRegion)
        assertEquals(legacy.primaryRegion!!.name, doc.primaryRegion!!.name)
    }
}