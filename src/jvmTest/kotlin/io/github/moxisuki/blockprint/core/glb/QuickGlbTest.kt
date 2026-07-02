package io.github.moxisuki.blockprint.core.glb

import io.github.moxisuki.blockprint.core.api.BlockPrintReader
import io.github.moxisuki.blockprint.core.api.BlockPrintToGlb
import java.nio.file.Paths
import org.junit.Test

class QuickGlbTest {
    @Test fun gen() {
        val doc = BlockPrintReader.read(Paths.get("test/fixtures/user-sample.schem").toFile())
        val bytes = BlockPrintToGlb.convertToBytes(doc, listOf(Paths.get("test/assets")))
        Paths.get("build/user-sample.pr14.glb").toFile().writeBytes(bytes)
        println("PR14: wrote ${bytes.size} bytes")
    }
}
