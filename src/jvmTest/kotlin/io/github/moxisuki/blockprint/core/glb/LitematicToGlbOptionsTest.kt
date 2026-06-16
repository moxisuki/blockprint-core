package io.github.moxisuki.blockprint.core.glb

import org.junit.Assert.assertEquals
import org.junit.Test

class LitematicToGlbOptionsTest {
    @Test
    fun convert_optionsParameter_defaultsToUnsplit() {
        val opts: GlbExportOptions = GlbExportOptions()
        assertEquals(0, opts.floorHeight)
    }

    @Test
    fun convertToBytes_optionsParameter_defaultsToUnsplit() {
        val opts = GlbExportOptions(floorHeight = 4, explodeGap = 0.5f)
        assertEquals(4, opts.floorHeight)
        assertEquals(0.5f, opts.explodeGap)
    }
}
