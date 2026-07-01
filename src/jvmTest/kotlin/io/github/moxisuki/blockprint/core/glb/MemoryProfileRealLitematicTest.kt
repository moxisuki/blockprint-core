package io.github.moxisuki.blockprint.core.glb

import io.github.moxisuki.blockprint.core.LitematicReader
import java.io.File
import java.io.OutputStream

import io.github.moxisuki.blockprint.core.glb.writer.GlbExportOptions
import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Path
import org.junit.Test

/**
 * Side-by-side memory profile of the three public conversion paths, run
 * against a real litematic file (not a synthetic region).
 *
 * Compares:
 *   1. convertToBytes()        — the legacy in-memory path
 *   2. convert(File)           — FileOutputStream wrapper (equivalent to
 *                                convert(OutputStream) on a non-buffered sink)
 *   3. convert(OutputStream)   — caller-provided stream (we supply a
 *                                byte-counting sink that does not allocate)
 *
 * Metrics captured per path:
 *   - Output GLB size (bytes)
 *   - Wall-clock time
 *   - Java heap delta (Runtime.totalMemory − freeMemory, before vs. after
 *     full GC; this is the on-heap portion)
 *   - Direct (off-heap) memory delta via BufferPoolMXBean — captures the
 *     DirectByteBuffers that back [OffHeapBuf]
 *
 * The .litematic path is read from system property `lit.path` if set,
 * otherwise falls back to `test/pre.litematic`. To run on the user's
 * download:
 *
 *   ./gradlew jvmTest --tests MemoryProfileRealLitematicTest \
 *     -Dlit.path="C:/Users/Administrator/Downloads/5c88d397-1a96-4f77-851b-c8cc93bc3148.litematic"
 *
 * This test always passes — it is a measurement harness, not a validator.
 */
class MemoryProfileRealLitematicTest {

    @Test
    fun memory_profile_three_paths() {
        // Resolve the .litematic in this order:
        //   1. -Dlit.path=...  on the test JVM (forwarded via Gradle property)
        //   2. Hard-coded fallback to the user's Downloads fixture (the
        //      "5c88d397..." file mentioned in the original request).
        //   3. Project's bundled test/pre.litematic (small, for sanity).
        val litFile = resolveLitematic()
        require(litFile.isFile) {
            "litematic not found: ${litFile.absolutePath}"
        }
        println("\n========== Memory profile ==========")
        println("litematic : ${litFile.absolutePath}")
        println("file size : ${litFile.length()} bytes (compressed NBT)")

        val lit = LitematicReader.read(litFile)
        val region = lit.regions.firstOrNull()
            ?: error("litematic has no regions")
        val nonAir = region.rawBlocks.count { it != 0 }
        println("region    : ${region.width} x ${region.height} x ${region.depth} " +
            "(${region.width.toLong() * region.height * region.depth} cells, " +
            "$nonAir non-air)")
        println("palette   : ${region.palette.entries.size} entries")

        // Use bundled Minecraft assets so blocks resolve to real models
        // (without assets, most faces are dropped → tiny placeholder GLB).
        val assetsDirs: List<Path> = listOf(
            File("").absoluteFile.let { File(it, "test/assets").toPath() }
        )
        println("assets    : $assetsDirs")

        // Warm up JIT so the first measured run isn't polluted by code-gen.
        warmUp(litFile, assetsDirs)

        // ── 1. convertToBytes ──────────────────────────────────────────
        val a = runOnce("convertToBytes") {
            LitematicToGlb.convertToBytes(lit, assetsDirs)
        }
        val bytesSize = (a.result as ByteArray).size.toLong()

        // ── 2. convert(File) ───────────────────────────────────────────
        val outFile = File.createTempFile("memprof-", ".glb")
        outFile.deleteOnExit()
        val b = runOnce("convert(File)") {
            LitematicToGlb.convert(lit, assetsDirs, outFile)
        }
        val fileSize = outFile.length()

        // ── 3. convert(OutputStream) — counting sink, no on-heap accumulation
        val c = runOnce("convert(OutputStream)") {
            val counter = CountingOutputStream()
            LitematicToGlb.convert(lit, assetsDirs, counter)
            counter.count
        }
        val osBytes = (c.result as Long)

        println("\n---------- Results ----------")
        println(String.format("%-26s %12s %12s %14s %12s",
            "path", "output(B)", "time(ms)", "heap Δ(MB)", "direct Δ(KB)"))
        println(String.format("%-26s %12d %12d %14.2f %12.2f",
            "convertToBytes", bytesSize, a.elapsedMs, a.heapDeltaMB, a.directDeltaKB))
        println(String.format("%-26s %12d %12d %14.2f %12.2f",
            "convert(File)", fileSize, b.elapsedMs, b.heapDeltaMB, b.directDeltaKB))
        println(String.format("%-26s %12d %12d %14.2f %12.2f",
            "convert(OutputStream)", osBytes, c.elapsedMs, c.heapDeltaMB, c.directDeltaKB))

        println("\nNotes:")
        println("  • convertToBytes holds the entire GLB as a Java ByteArray")
        println("    → heap Δ ≈ output size (peak in-heap retention).")
        println("  • convert(File) and convert(OutputStream) stream through")
        println("    OffHeapBuf → 64 KB on-heap staging → sink, so heap Δ")
        println("    stays ~constant regardless of output size.")
        println("  • direct Δ measures DirectByteBuffer allocation; this is")
        println("    the off-heap pool that bypasses ART's 256 MB Java heap cap.")

        // Sanity: all three should produce identical-sized output.
        check(bytesSize == fileSize && fileSize == osBytes) {
            "Output sizes differ: bytes=$bytesSize file=$fileSize os=$osBytes"
        }
    }

    /** Quick smoke: generate GLB via convert(OutputStream) to a known path
     *  for manual inspection.  Output lands in test/stream_output.glb. */
    @Test
    fun generate_glb_via_outputstream() {
        val litFile = resolveLitematic()
        require(litFile.isFile) { "litematic not found: ${litFile.absolutePath}" }
        val lit = LitematicReader.read(litFile)
        val assetsDirs: List<Path> = listOf(
            File("").absoluteFile.let { File(it, "test/assets").toPath() }
        )
        val outFile = File("test/stream_output.glb")
        val options = GlbExportOptions(floorHeight = 2)  // multi-floor to validate the fix
        java.io.FileOutputStream(outFile).use { fos ->
            LitematicToGlb.convert(lit, assetsDirs, fos, options = options)
        }
        println("Wrote: ${outFile.absolutePath} (${outFile.length()} bytes)")
    }

    private fun resolveLitematic(): File {
        System.getProperty("lit.path")?.let { return File(it) }
        val download = File(
            "C:/Users/Administrator/Downloads/" +
                "5c88d397-1a96-4f77-851b-c8cc93bc3148.litematic"
        )
        if (download.isFile) return download
        return File("").absoluteFile.let { File(it, "test/pre.litematic") }
    }

    /** Warm up everything once so JIT / class-loading doesn't pollute deltas. */
    private fun warmUp(litFile: File, assetsDirs: List<Path>) {
        val lit = LitematicReader.read(litFile)
        LitematicToGlb.convertToBytes(lit, assetsDirs)
        val tmp = File.createTempFile("memprof-warm-", ".glb")
        tmp.deleteOnExit()
        LitematicToGlb.convert(lit, assetsDirs, tmp)
        LitematicToGlb.convert(lit, assetsDirs, CountingOutputStream())
        tmp.delete()
    }

    private data class Measurement(
        val pathName: String,
        val elapsedMs: Long,
        val heapDeltaMB: Double,
        val directDeltaKB: Double,
        val result: Any,
    )

    private inline fun <T : Any> runOnce(name: String, block: () -> T): Measurement {
        // Best-effort GC + settle. Two cycles because some GCs are generational.
        Runtime.getRuntime().gc()
        Thread.sleep(50)
        Runtime.getRuntime().gc()
        Thread.sleep(50)

        val directBefore = directBufferUsed()
        val heapBefore = currentHeapUsed()

        val t0 = System.nanoTime()
        val result = block()
        val t1 = System.nanoTime()

        val directAfter = directBufferUsed()
        val heapAfter = currentHeapUsed()

        return Measurement(
            pathName = name,
            elapsedMs = (t1 - t0) / 1_000_000L,
            heapDeltaMB = (heapAfter - heapBefore) / (1024.0 * 1024.0),
            directDeltaKB = (directAfter - directBefore) / 1024.0,
            result = result,
        )
    }

    private fun currentHeapUsed(): Long {
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    /** Sum of all BufferPoolMXBean entries named "direct" — i.e. DirectByteBuffers. */
    private fun directBufferUsed(): Long {
        var total = 0L
        for (bean: BufferPoolMXBean in ManagementFactory.getPlatformMXBeans(BufferPoolMXBean::class.java)) {
            if (bean.name == "direct") total += bean.memoryUsed
        }
        return total
    }

    /** OutputStream that just counts bytes — exercises convert(OutputStream)
     *  without forcing the data into a Java heap byte[] (which would defeat
     *  the test). */
    private class CountingOutputStream : OutputStream() {
        var count: Long = 0
        override fun write(b: Int) { count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { count += len }
        override fun flush() {}
        override fun close() {}
    }
}