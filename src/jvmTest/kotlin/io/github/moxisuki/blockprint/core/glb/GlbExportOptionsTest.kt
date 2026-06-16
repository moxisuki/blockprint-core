package io.github.moxisuki.blockprint.core.glb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlbExportOptionsTest {
    @Test
    fun defaults_disableSplitting_andEnableTinting() {
        val opts = GlbExportOptions()
        assertEquals("floorHeight=0 means no split", 0, opts.floorHeight)
        assertEquals("explodeGap=0 means no explode", 0f, opts.explodeGap)
        assertTrue("biome tint enabled by default", opts.enableTinting)
    }

    @Test
    fun customValues_arePreserved() {
        val opts = GlbExportOptions(floorHeight = 4, explodeGap = 2.5f, enableTinting = false)
        assertEquals(4, opts.floorHeight)
        assertEquals(2.5f, opts.explodeGap)
        assertFalse(opts.enableTinting)
    }

    @Test
    fun dataClass_equality() {
        val a = GlbExportOptions(floorHeight = 4, explodeGap = 1f, enableTinting = false)
        val b = GlbExportOptions(floorHeight = 4, explodeGap = 1f, enableTinting = false)
        assertEquals(a, b)
    }
}
