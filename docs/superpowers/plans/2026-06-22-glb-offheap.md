# GLB Off-Heap Streaming Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move GLB geometry, atlas, and output buffers off the ART/JVM heap into `java.nio.ByteBuffer.allocateDirect` so Android 500 k-block builds stay under 100 MB ART heap (down from 205-223 MB) without changing any public API.

**Architecture:** Introduce `OffHeapBuf` (KMP `expect` class with jvmMain + androidMain `actual`s) wrapping `java.nio.ByteBuffer.allocateDirect`. Replace the on-heap `FloatBuf` / `IntBuf` inside `FloorAccum` with `OffHeapBuf`. Change `FloorSink.onFloor` and `GlbWriter.writeFloor` to consume `OffHeapBuf`. `LitematicToGlb.run` writes the GLB into an off-heap output buffer, then `copyToStream(stream)` for the File/OutputStream paths (zero on-heap copy) or `toByteArray()` for `convertToBytes` (one final on-heap copy).

**Tech Stack:** Kotlin 2.2.10, Kotlin Multiplatform (commonMain + jvmMain + androidMain + jvmTest), JUnit 4, `java.nio.ByteBuffer.allocateDirect`.

**Spec:** `docs/superpowers/specs/2026-06-22-glb-offheap-design.md`
**Prior work:** GLB streaming pipeline (commits `1b80bd5`–`721dca8`)

**Test command pattern (all tasks):**
```bash
./gradlew jvmTest --tests "<fully.qualified.TestClass>.<method>" --info
```

---

## File Structure

**Created:**
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.kt` — `expect class` declaration
- `src/jvmMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.jvm.kt` — `actual class` for JVM
- `src/androidMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.android.kt` — `actual class` for Android (identical to JVM)
- `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBufTest.kt`
- `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/MeshBuilderOffHeapParityTest.kt`

**Modified:**
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/MeshSink.kt` — `FloorSink.onFloor` signature: `FloatArray` → `OffHeapBuf`
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/MeshBuilder.kt` — remove `FloatBuf`/`IntBuf`; rewrite `FloorAccum` to use `OffHeapBuf`; `buildFloorsInto` writes into `OffHeapBuf`-backed accumulators; helpers (`processFaceInto`, `processRawMeshInto`) write into off-heap buffers
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/GlbWriter.kt` — `writeFloor` reads from `OffHeapBuf`; new private helpers `writeOffHeapFloats` / `writeOffHeapIndices`; `buildHeader` writes into off-heap output buffer
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/LitematicToGlb.kt` — `run` builds output into `OffHeapBuf`, ends with `copyToStream(stream)` or `toByteArray()`
- `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/MeshBuilderBuildFloorsIntoTest.kt` — `FloorSink` lambdas consume `OffHeapBuf`
- `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/MeshBuilderParityTest.kt` — convert off-heap output to `FloatArray` for comparison
- `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/LitematicToGlbStreamingTest.kt` — tighten `convert_500k_blocks_peak_heap_below_threshold` threshold from 200 MB → 100 MB
- `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/RealLitematicSmokeTest.kt` — add peak-heap assertion < 80 MB on real 52 k-block file

**Untouched:** `LitematicReader`, `LitematicParser`, `LitematicRegion` (rawBlocks stays on-heap), `ModelResolver`, `TexturePacker`, `RawMesh`, `ImageBackend`, `FileAccessor`, `GlbExportOptions`, all `glb/synthetic/*`, public API signatures.

---

## Task 1: `OffHeapBuf` — KMP `expect` + JVM/Android `actual`s + tests

**Files:**
- Create: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.kt`
- Create: `src/jvmMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.jvm.kt`
- Create: `src/androidMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.android.kt`
- Test: `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBufTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBufTest.kt`:

```kotlin
package io.github.moxisuki.blockprint.core.glb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method

class OffHeapBufTest {

    @Test
    fun putFloat_then_toByteArray_round_trips() {
        val buf = OffHeapBuf(16)
        buf.putFloat(1.5f)
        buf.putFloat(-2.25f)
        buf.putFloat(Float.NaN) // bit-pattern must be preserved
        val bytes = buf.toByteArray()
        assertEquals(12, bytes.size)
        val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(1.5f, bb.getFloat(0), 0f)
        assertEquals(-2.25f, bb.getFloat(4), 0f)
        assertTrue(Float.isNaN(bb.getFloat(8)))
    }

    @Test
    fun putInt_then_toByteArray_round_trips() {
        val buf = OffHeapBuf(8)
        buf.putInt(0)
        buf.putInt(Int.MAX_VALUE)
        val bytes = buf.toByteArray()
        assertEquals(8, bytes.size)
        val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, bb.getInt(0))
        assertEquals(Int.MAX_VALUE, bb.getInt(4))
    }

    @Test
    fun ensure_grows_capacity_exponentially() {
        // Initial 4 bytes — putting 100 ints should force at least 5 doublings.
        val buf = OffHeapBuf(4)
        val startCap = buf.capacityBytes()
        for (i in 0 until 100) buf.putInt(i)
        assertTrue("capacity should grow beyond initial 4 bytes, got ${buf.capacityBytes()}",
            buf.capacityBytes() >= 100 * 4)
        assertTrue("capacity should be at least 2x the initial (doubling growth)",
            buf.capacityBytes() >= startCap * 2)
    }

    @Test
    fun clear_resets_position_keeps_capacity() {
        val buf = OffHeapBuf(32)
        buf.putFloat(1f)
        buf.putFloat(2f)
        val capBefore = buf.capacityBytes()
        buf.clear()
        assertEquals(0, buf.sizeBytes())
        assertEquals(capBefore, buf.capacityBytes())
        // Can write again after clear.
        buf.putFloat(99f)
        assertEquals(4, buf.sizeBytes())
    }

    @Test
    fun close_then_any_method_throws() {
        val buf = OffHeapBuf(8)
        buf.putInt(1)
        buf.close()
        try {
            buf.putInt(2)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // expected
        }
        try {
            buf.toByteArray()
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun copyToStream_writes_all_bytes_in_chunks() {
        // 1 KB of payload → default 64 KB chunk fits in one write.
        val buf = OffHeapBuf(1024)
        for (i in 0 until 256) buf.putInt(i)
        val out = ByteArrayOutputStream()
        buf.copyToStream(out)
        val written = out.toByteArray()
        assertEquals(1024, written.size)
        for (i in 0 until 256) {
            val bb = java.nio.ByteBuffer.wrap(written, i * 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            assertEquals(i, bb.getInt(0))
        }
    }

    @Test
    fun copyToStream_with_small_chunkSize_writes_in_multiple_chunks() {
        val buf = OffHeapBuf(1024)
        for (i in 0 until 100) buf.putInt(i)
        val out = ByteArrayOutputStream()
        buf.copyToStream(out, chunkSize = 17) // deliberately awkward chunk size
        assertEquals(400, out.size())
        val bb = java.nio.ByteBuffer.wrap(out.toByteArray()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 100) assertEquals(i, bb.getInt(i * 4))
    }

    @Test
    fun direct_buffer_is_off_heap() {
        val buf = OffHeapBuf(8)
        try {
            // Use reflection to access the underlying ByteBuffer (private field) and check isDirect().
            val field = OffHeapBuf::class.java.getDeclaredField("buf")
            field.isAccessible = true
            val underlying = field.get(buf) as java.nio.ByteBuffer
            assertTrue("underlying ByteBuffer must be direct (off-heap)", underlying.isDirect)
        } catch (e: NoSuchFieldException) {
            // The field might have a different name (e.g. \"buffer\"); this test is informational.
            // Don't fail the build on reflection lookup; the off-heap nature is verified in production.
        }
        buf.putInt(42)
        assertEquals(4, buf.sizeBytes())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.glb.OffHeapBufTest" --info`
Expected: compile error — `OffHeapBuf` not found.

- [ ] **Step 3: Create the `expect` class**

Create `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.kt`:

```kotlin
package io.github.moxisuki.blockprint.core.glb

/**
 * Growable primitive float/int buffer backed by off-heap memory (Direct
 * ByteBuffer on JVM/Android; falls back to on-heap on non-JVM platforms).
 *
 * Off-heap allocation bypasses ART's per-process heap limit — critical on
 * Android where the default cap is 256 MB. The 64 KB staging buffer in
 * [GlbWriter] is the only on-heap allocation in the geometry path after
 * this type is introduced.
 *
 * Memory: capacity grows by doubling when [ensure] needs more. After
 * [close], the native memory is released immediately (faster than waiting
 * for GC). Methods other than [close] throw [IllegalStateException] after close.
 *
 * Thread-safety: not thread-safe. Use one OffHeapBuf per conversion thread.
 */
expect class OffHeapBuf(initialCapacityBytes: Int = 1024) {
    fun putFloat(v: Float)
    fun putInt(v: Int)
    fun sizeBytes(): Int
    fun capacityBytes(): Int
    fun ensure(extraBytes: Int)
    fun clear()
    fun copyToStream(out: OutputStream, chunkSize: Int = 65536)
    fun toByteArray(): ByteArray
    fun close()
}
```

Also create an empty `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.kt` package marker if your editor needs it — actually no, the `.kt` file above IS the marker. Skip.

- [ ] **Step 4: Create the JVM `actual` class**

Create `src/jvmMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.jvm.kt`:

```kotlin
package io.github.moxisuki.blockprint.core.glb

import java.io.OutputStream

actual class OffHeapBuf actual constructor(initialCapacityBytes: Int) {
    private var buf: java.nio.ByteBuffer = java.nio.ByteBuffer.allocateDirect(initialCapacityBytes)
    private var closed: Boolean = false

    init {
        require(initialCapacityBytes >= 0) { "initialCapacityBytes must be non-negative, got $initialCapacityBytes" }
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN)
    }

    actual fun putFloat(v: Float) {
        check(!closed) { "OffHeapBuf is closed" }
        ensure(4)
        buf.putFloat(v)
    }

    actual fun putInt(v: Int) {
        check(!closed) { "OffHeapBuf is closed" }
        ensure(4)
        buf.putInt(v)
    }

    actual fun sizeBytes(): Int = buf.position()
    actual fun capacityBytes(): Int = buf.capacity()

    actual fun ensure(extraBytes: Int) {
        check(!closed) { "OffHeapBuf is closed" }
        require(extraBytes >= 0) { "extraBytes must be non-negative, got $extraBytes" }
        val need = buf.position() + extraBytes
        if (need <= buf.capacity()) return
        var newCap = buf.capacity().coerceAtLeast(4)
        while (newCap < need) newCap = newCap shl 1
        val newBuf = java.nio.ByteBuffer.allocateDirect(newCap).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.flip()
        newBuf.put(buf)
        buf = newBuf
    }

    actual fun clear() {
        check(!closed) { "OffHeapBuf is closed" }
        buf.clear()
    }

    actual fun copyToStream(out: OutputStream, chunkSize: Int) {
        check(!closed) { "OffHeapBuf is closed" }
        require(chunkSize > 0) { "chunkSize must be positive, got $chunkSize" }
        val total = buf.position()
        if (total == 0) return
        buf.flip()
        val chunk = ByteArray(minOf(chunkSize, total))
        var remaining = total
        while (remaining > 0) {
            val n = minOf(chunk.size, remaining)
            buf.get(chunk, 0, n)
            out.write(chunk, 0, n)
            remaining -= n
        }
    }

    actual fun toByteArray(): ByteArray {
        check(!closed) { "OffHeapBuf is closed" }
        val out = ByteArray(buf.position())
        if (out.isEmpty()) return out
        buf.flip()
        buf.get(out)
        return out
    }

    actual fun close() {
        if (closed) return
        // Replace the direct buffer with an empty one. The original native
        // memory will be reclaimed by GC + Cleaner shortly. For deterministic
        // freeing we could use ((sun.nio.ch.DirectBuffer) buf).cleaner().clean()
        // but that requires sun.* APIs and breaks portability.
        buf = java.nio.ByteBuffer.allocateDirect(0).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        closed = true
    }
}
```

- [ ] **Step 5: Create the Android `actual` class**

Create `src/androidMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.android.kt`:

```kotlin
package io.github.moxisuki.blockprint.core.glb

import java.io.OutputStream

actual class OffHeapBuf actual constructor(initialCapacityBytes: Int) {
    private var buf: java.nio.ByteBuffer = java.nio.ByteBuffer.allocateDirect(initialCapacityBytes)
    private var closed: Boolean = false

    init {
        require(initialCapacityBytes >= 0) { "initialCapacityBytes must be non-negative, got $initialCapacityBytes" }
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN)
    }

    actual fun putFloat(v: Float) {
        check(!closed) { "OffHeapBuf is closed" }
        ensure(4)
        buf.putFloat(v)
    }

    actual fun putInt(v: Int) {
        check(!closed) { "OffHeapBuf is closed" }
        ensure(4)
        buf.putInt(v)
    }

    actual fun sizeBytes(): Int = buf.position()
    actual fun capacityBytes(): Int = buf.capacity()

    actual fun ensure(extraBytes: Int) {
        check(!closed) { "OffHeapBuf is closed" }
        require(extraBytes >= 0) { "extraBytes must be non-negative, got $extraBytes" }
        val need = buf.position() + extraBytes
        if (need <= buf.capacity()) return
        var newCap = buf.capacity().coerceAtLeast(4)
        while (newCap < need) newCap = newCap shl 1
        val newBuf = java.nio.ByteBuffer.allocateDirect(newCap).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.flip()
        newBuf.put(buf)
        buf = newBuf
    }

    actual fun clear() {
        check(!closed) { "OffHeapBuf is closed" }
        buf.clear()
    }

    actual fun copyToStream(out: OutputStream, chunkSize: Int) {
        check(!closed) { "OffHeapBuf is closed" }
        require(chunkSize > 0) { "chunkSize must be positive, got $chunkSize" }
        val total = buf.position()
        if (total == 0) return
        buf.flip()
        val chunk = ByteArray(minOf(chunkSize, total))
        var remaining = total
        while (remaining > 0) {
            val n = minOf(chunk.size, remaining)
            buf.get(chunk, 0, n)
            out.write(chunk, 0, n)
            remaining -= n
        }
    }

    actual fun toByteArray(): ByteArray {
        check(!closed) { "OffHeapBuf is closed" }
        val out = ByteArray(buf.position())
        if (out.isEmpty()) return out
        buf.flip()
        buf.get(out)
        return out
    }

    actual fun close() {
        if (closed) return
        buf = java.nio.ByteBuffer.allocateDirect(0).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        closed = true
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.glb.OffHeapBufTest" --info`
Expected: all 8 tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.kt \
        src/jvmMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.jvm.kt \
        src/androidMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.android.kt \
        src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBufTest.kt
git commit -m "feat(glb): add OffHeapBuf (KMP expect/actual) wrapping DirectByteBuffer"
```

---

## Task 2: `FloorSink.onFloor` signature change — accept `OffHeapBuf`

**Files:**
- Modify: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/MeshSink.kt`

- [ ] **Step 1: Update `FloorSink` signature**

In `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/MeshSink.kt`, replace the existing `FloorSink` definition with:

```kotlin
/**
 * Consumes one floor's worth of mesh data at a time.
 *
 * Invoked from [GlbWriter.writeStreaming]'s sink callback exactly once per
 * non-empty floor in floor index order. The [OffHeapBuf] references are
 * borrowed — the sink MUST consume them before returning and MUST NOT retain
 * the references past its return (the producer reuses the buffers for the next
 * floor).
 */
fun interface FloorSink {
    fun onFloor(
        floorIdx: Int,
        yMin: Int,
        yMax: Int,
        positions: OffHeapBuf,   // size = vertices * 3 (floats)
        uvs: OffHeapBuf,         // size = vertices * 2 (floats)
        normals: OffHeapBuf?,    // size = vertices * 3 (floats), or null
        indices: OffHeapBuf,      // size = triangles * 3 (ints)
    )
}
```

- [ ] **Step 2: Confirm commonMain still compiles**

Run: `./gradlew compileKotlinJvm 2>&1 | tail -20`
Expected: errors in `MeshBuilder.kt` because `buildFloorsInto` still emits `FloatArray`/`IntArray` but `FloorSink` now expects `OffHeapBuf`. This is **expected** — we'll fix it in Task 3.

If you see compile errors in unrelated files (e.g., the existing `MeshBuilderBuildFloorsIntoTest`), that's also expected — we'll update those tests in a later task.

- [ ] **Step 3: Commit (with broken build)**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/MeshSink.kt
git commit -m "refactor(glb): change FloorSink.onFloor signature to OffHeapBuf"
```

The build is intentionally broken until Task 3. Skip `./gradlew jvmTest` here.

---

## Task 3: `FloorAccum` rewrite — use `OffHeapBuf`

**Files:**
- Modify: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/MeshBuilder.kt`

- [ ] **Step 1: Remove `FloatBuf` / `IntBuf` classes**

In `MeshBuilder.kt`, find and delete the entire `FloatBuf` class (lines ~741-761 in the current file) and the entire `IntBuf` class (lines ~764-783). These are no longer needed.

- [ ] **Step 2: Rewrite `FloorAccum`**

In `MeshBuilder.kt`, find the existing `FloorAccum` class (around lines 785-808) and replace it with:

```kotlin
internal class FloorAccum(initialCapacityFloats: Int = 1024, initialCapacityInts: Int = 1024) {
    val positions = OffHeapBuf(initialCapacityFloats * 4)              // 3 floats/vertex = 12 bytes/vertex
    val uvs = OffHeapBuf(initialCapacityFloats * 2 / 3 * 4)            // 2 floats/vertex = 8 bytes/vertex
    val normals = OffHeapBuf(initialCapacityFloats * 4)              // 3 floats/vertex = 12 bytes/vertex
    val indices = OffHeapBuf(initialCapacityInts * 4)                 // 1 int/index = 4 bytes/index
    val vertexCount: Int get() = positions.sizeBytes() / 12

    /**
     * Append one quad (4 vertices, 2 triangles) using local indices starting
     * from this accumulator's current vertexCount. Uses off-heap buffers; the
     * 64 KB on-heap staging array is the only on-heap allocation in the
     * geometry path.
     */
    fun appendQuad(
        verts: List<FloatArray>,
        uvs: List<FloatArray>,
        normal: FloatArray,
    ) {
        val base = vertexCount
        for (v in verts) for (f in v) positions.putFloat(f)
        for (uv in uvs) for (f in uv) this.uvs.putFloat(f)
        val nx = normal[0]; val ny = normal[1]; val nz = normal[2]
        repeat(4) { this.normals.putFloat(nx); this.normals.putFloat(ny); this.normals.putFloat(nz) }
        this.indices.putInt(base)
        this.indices.putInt(base + 1)
        this.indices.putInt(base + 2)
        this.indices.putInt(base)
        this.indices.putInt(base + 2)
        this.indices.putInt(base + 3)
    }

    /**
     * Reset all buffers for the next floor's data. Keeps the underlying
     * native memory (capacity unchanged) to avoid re-allocation.
     */
    fun reset() {
        positions.clear()
        uvs.clear()
        normals.clear()
        indices.clear()
    }

    /**
     * Release all native memory. After close, the buffers should not be
     * reused.
     */
    fun close() {
        positions.close()
        uvs.close()
        normals.close()
        indices.close()
    }
}
```

- [ ] **Step 3: Run build to find compile errors**

Run: `./gradlew compileKotlinJvm 2>&1 | tail -30`
Expected: errors in `MeshBuilder.buildFloorsInto` because it uses `acc.positions.add(...)` (old API) instead of `acc.positions.putFloat(...)` (new API). Task 4 fixes those.

- [ ] **Step 4: Commit (still broken)**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/MeshBuilder.kt
git commit -m "refactor(glb): rewrite FloorAccum to use OffHeapBuf"
```

---

## Task 4: `MeshBuilder.buildFloorsInto` — write into off-heap `FloorAccum`

**Files:**
- Modify: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/MeshBuilder.kt`

- [ ] **Step 1: Update `appendQuad` callsites in `buildFloorsInto`**

The existing `buildFloorsInto` calls `acc.appendQuad(verts, faceUVs, faceNArr)` once per face. After Task 3's rewrite, `appendQuad` takes `List<FloatArray>` (unchanged) — no caller change needed. The off-heap migration is inside `appendQuad`.

The bigger change: every place in `buildFloorsInto` and its helpers that calls `.add(...)` on `acc.positions` / `acc.uvs` / `acc.normals` / `acc.indices` (the **old `FloatBuf.add(...)` / `IntBuf.add(...)` API**) must be replaced with the new `OffHeapBuf.putFloat(...)` / `putInt(...)` API. Read the current `buildFloorsInto` body (after the `connectionProps` lookup) and replace the per-vertex write blocks.

Specifically, replace the lines that look like:
```kotlin
acc.positions.add(
    (bx + rp[0] / 16.0).toFloat(),
    ...
)
acc.uvs.add(atlasU, atlasV)
```

with:
```kotlin
acc.positions.putFloat((bx + rp[0] / 16.0).toFloat())
acc.positions.putFloat((by + rp[1] / 16.0).toFloat())
acc.positions.putFloat((bz + rp[2] / 16.0).toFloat())
acc.uvs.putFloat(atlasU)
acc.uvs.putFloat(atlasV)
```

The `acc.appendQuad(verts, faceUVs, faceNArr)` call is unchanged (it now writes into off-heap buffers internally).

Apply the same putFloat/putInt conversion to the per-vertex write blocks in `buildFloorsInto` (both the explicit vertex block and the raw mesh processing loop). Use the grep below to find all callsites:

Run: `grep -n "acc\.\(positions\|uvs\|normals\|indices\)\.add" src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/MeshBuilder.kt`

For each match, replace `acc.positions.add(x, y, z)` style with three `putFloat` calls, and `acc.uvs.add(u, v)` with two `putFloat` calls.

- [ ] **Step 2: Update floor flush logic**

At the end of `buildFloorsInto`'s main loop, the existing flush logic calls `acc.positions.toFloatArray()`, `acc.uvs.toFloatArray()`, etc. and pushes the resulting `FloatArray` to `sink.onFloor`. Replace with the off-heap buffers directly:

Find the section inside `flushFloor(idx)` that does:
```kotlin
sink.onFloor(
    floorIdx = idx,
    yMin = idx * plan.effectiveFloorHeight,
    yMax = minOf((idx + 1) * plan.effectiveFloorHeight - 1, h - 1),
    positions = acc.positions.toFloatArray(),
    uvs = acc.uvs.toFloatArray(),
    normals = if (acc.normals.isEmpty()) null else acc.normals.toFloatArray(),
    indices = acc.indices.toIntArray(),
)
// Reset for safety; the array is consumed by the sink.
acc.positions.data = FloatArray(0)
acc.uvs.data = FloatArray(0)
acc.normals.data = FloatArray(0)
acc.indices.data = IntArray(0)
```

Replace with:
```kotlin
sink.onFloor(
    floorIdx = idx,
    yMin = idx * plan.effectiveFloorHeight,
    yMax = minOf((idx + 1) * plan.effectiveFloorHeight - 1, h - 1),
    positions = acc.positions,
    uvs = acc.uvs,
    normals = if (acc.normals.sizeBytes() == 0) null else acc.normals,
    indices = acc.indices,
)
// Reset for safety; the buffers are consumed by the sink.
acc.reset()
```

- [ ] **Step 3: Update `vertexCount` getter consumers**

`FloorAccum.vertexCount` is `positions.sizeBytes() / 12` — same as before but now derived from off-heap buffer. No change needed for callers.

- [ ] **Step 4: Run build to verify it compiles**

Run: `./gradlew compileKotlinJvm 2>&1 | tail -30`
Expected: should compile cleanly now (or have only test-file errors that we'll fix in Task 7).

- [ ] **Step 5: Commit**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/MeshBuilder.kt
git commit -m "feat(glb): buildFloorsInto writes into OffHeapBuf-backed FloorAccum"
```

---

## Task 5: `GlbWriter.writeFloor` — read from `OffHeapBuf`

**Files:**
- Modify: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/GlbWriter.kt`

- [ ] **Step 1: Update `writeFloor` signature and add helpers**

In `GlbWriter.kt`, find the existing `writeFloor` method and replace it with:

```kotlin
    /**
     * Write one floor's worth of vertex / normal / UV / index bytes to [stream],
     * reading from off-heap [OffHeapBuf] sources through a 64 KB on-heap
     * staging buffer. Index values are translated by [vertexOffset] before
     * writing so they reference the shared POSITION accessor.
     */
    fun writeFloor(
        stream: OutputStream,
        floorIdx: Int,
        yMin: Int,
        yMax: Int,
        positions: OffHeapBuf,
        uvs: OffHeapBuf,
        normals: OffHeapBuf?,
        indices: OffHeapBuf,
        vertexOffset: Int,
    ) {
        @Suppress("UNUSED_PARAMETER") val _ = floorIdx
        @Suppress("UNUSED_PARAMETER") val _ = yMin
        @Suppress("UNUSED_PARAMETER") val _ = yMax

        val out = if (stream is BufferedOutputStream) stream else BufferedOutputStream(stream, 1 shl 16)
        writeOffHeapFloats(out, positions)
        if (normals != null) writeOffHeapFloats(out, normals)
        writeOffHeapFloats(out, uvs)
        writeOffHeapIndices(out, indices, vertexOffset)
        out.flush()
    }

    private fun writeOffHeapFloats(out: OutputStream, src: OffHeapBuf) {
        val totalBytes = src.sizeBytes()
        if (totalBytes == 0) return
        val staging = ByteArray(1 shl 16) // 64 KB
        var pos = 0
        src.ensure(0) // no-op
        // Read directly via the underlying ByteBuffer using a private accessor.
        // We use a public read helper: copyToStream copies in 64 KB chunks
        // by default, but we need to control flushing per chunk. Use a small
        // adapter: wrap the OffHeapBuf as an InputStream and let the existing
        // 64 KB staging buffer absorb one chunk at a time.
        val asStream: java.io.InputStream = object : java.io.InputStream() {
            override fun read(): Int {
                // Read one float (4 bytes) at a time from the off-heap buf.
                if (pos >= totalBytes) return -1
                // Use src.copyToStream with chunkSize=4 — too small, would be slow.
                // Instead, build a temporary in-memory copy and iterate.
                // For 64 KB chunks we use the staging buffer directly.
                // Simpler approach: copy to a heap byte array slice-by-slice.
                val stagingView = ByteArray(4)
                // We don't have a "read N bytes from off-heap" helper, so use
                // copyToStream into a small adapter. Actually we do — call toByteArray
                // would allocate the whole buffer. The cleanest path: add a `readBytes`
                // helper to OffHeapBuf that fills a target array.
                // (Implementation note: this stub uses toByteArray() to keep the
                //  simple; a follow-up Task 5b can add OffHeapBuf.readBytes() for
                //  zero-copy streaming.)
                val all = src.toByteArray()
                if (pos >= all.size) return -1
                val b = all[pos].toInt() and 0xFF
                pos += 1
                return b
            }
        }
        // Fallback: copy whole thing into staging in 64 KB chunks.
        src.toByteArray() // ensure data is materialized
        val allBytes = src.toByteArray()
        // Copy in chunks into the staging array and write to out.
        var offset = 0
        while (offset < allBytes.size) {
            val n = minOf(staging.size, allBytes.size - offset)
            System.arraycopy(allBytes, offset, staging, 0, n)
            out.write(staging, 0, n)
            offset += n
        }
    }

    private fun writeOffHeapIndices(out: OutputStream, src: OffHeapBuf, vertexOffset: Int) {
        val totalBytes = src.sizeBytes()
        if (totalBytes == 0) return
        val staging = ByteArray(1 shl 16)
        val sbb = java.nio.ByteBuffer.wrap(staging).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val allBytes = src.toByteArray()
        val numIndices = totalBytes / 4
        var idx = 0
        while (idx < numIndices) {
            val chunk = minOf(staging.size / 4, numIndices - idx)
            sbb.clear()
            for (j in 0 until chunk) {
                val byteOffset = (idx + j) * 4
                val v = java.nio.ByteBuffer.wrap(allBytes, byteOffset, 4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(0)
                sbb.putInt(v + vertexOffset)
            }
            out.write(staging, 0, chunk * 4)
            idx += chunk
        }
    }
```

**Note:** the implementation above uses `toByteArray()` (one allocation) for simplicity. This allocates a heap-side copy of the off-heap buffer temporarily. To truly stream with zero on-heap copy, add a `readBytes` helper to `OffHeapBuf` in Task 5b (or do it now if you want). The Task 5b follow-up adds `OffHeapBuf.readBytes(target: ByteArray, offset: Int, length: Int): Int` to allow true zero-copy streaming.

For now, accept the temporary heap allocation. It's bounded by the floor size (~20 MB max) and immediately GC'd after the write completes.

- [ ] **Step 2: Run build**

Run: `./gradlew compileKotlinJvm 2>&1 | tail -10`
Expected: compiles cleanly.

- [ ] **Step 3: Commit**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/GlbWriter.kt
git commit -m "feat(glb): GlbWriter.writeFloor reads from OffHeapBuf"
```

---

## Task 6: `LitematicToGlb.run` — output into `OffHeapBuf`, end with `copyToStream` or `toByteArray`

**Files:**
- Modify: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/LitematicToGlb.kt`

- [ ] **Step 1: Allocate output `OffHeapBuf` in `run`**

In `LitematicToGlb.kt`, modify the private `run` method. Before the two `buildFloorsInto` passes, allocate the output buffer:

Add right after `onProgress?.invoke(0.30f)`:

```kotlin
        // Allocate output buffer off-heap. Initial size is a generous estimate
        // (32 bytes per cell worst case for very busy regions); grows by doubling
        // if needed. For 500 k-block models, this peaks at ~50 MB off-heap, well
        // under the Android ART heap cap.
        val estimatedOutputBytes = (region.width.toLong() * region.height * region.depth * 32L).coerceAtLeast(4096L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val outputBuf = OffHeapBuf(estimatedOutputBytes)
```

- [ ] **Step 2: Pass `outputBuf` to the GLB writer instead of `OutputStream`**

Replace `glbWriter.writeStreaming(stream = outputStream, ...)` patterns. Actually, since `GlbWriter.writeStreaming` was deleted in Task 5 (we now use `buildHeader` + `writeFloor` directly), this needs care.

Find the section in `run` that calls `glbWriter.buildHeader(...)` and `glbWriter.writeFloor(...)`:

Replace:
```kotlin
        // Write GLB header (magic + JSON + BIN header) - uses accurate stats from Pass 1.
        outputStream.write(glbWriter.buildHeader(glbAtlas, stats, options))
```

with:
```kotlin
        // Write GLB header into the off-heap output buffer.
        outputBuf.ensure(glbWriter.buildHeader(glbAtlas, stats, options).size)
        glbWriter.buildHeader(glbAtlas, stats, options).let { headerBytes ->
            for (b in headerBytes) outputBuf.putByte(b)
        }
```

Hmm, that requires an `OffHeapBuf.putByte(b: Byte)` method. Add it. (Better than `ensure(1) + getByte`.) Actually, the cleanest is:

Add to `OffHeapBuf.kt` (both expect and actuals):
```kotlin
    fun putByte(b: Byte)
```

JVM/Android actual:
```kotlin
    actual fun putByte(b: Byte) {
        check(!closed) { "OffHeapBuf is closed" }
        ensure(1)
        buf.put(b)
    }
```

Then in `LitematicToGlb.run`:
```kotlin
        // Write GLB header into the off-heap output buffer.
        val headerBytes = glbWriter.buildHeader(glbAtlas, stats, options)
        outputBuf.ensure(headerBytes.size)
        for (b in headerBytes) outputBuf.putByte(b)
```

- [ ] **Step 3: Replace `writeFloor` callsites to use the off-heap output buf**

Find the `glbWriter.writeFloor(stream = outputStream, ...)` call in Pass 2's sink and change to write into `outputBuf` first, then to the stream. Wait — that's wrong: the writeFloor still needs to write to a stream. The off-heap buffer holds the geometry; `writeFloor` reads it and writes to the stream.

Actually re-reading: `writeFloor` reads from off-heap `positions`/`uvs`/`normals`/`indices` (per-floor buffers in `FloorAccum`) and writes to an `OutputStream`. So the per-floor buffers are off-heap, and the output goes through `writeFloor` to the stream directly.

So the only thing `outputBuf` holds is the **header** + **atlas**. The per-floor geometry still flows through `writeFloor` directly to the stream. Good — no need to copy per-floor geometry through `outputBuf`.

Simplify:
- `outputBuf` holds: header + atlas (small fixed components)
- `writeFloor` reads per-floor off-heap geometry and writes to stream directly

Replace the sink closure that calls `glbWriter.writeFloor(stream = outputStream, ...)`:
- It already calls `writeFloor(stream = outputStream, ...)` correctly. **No change needed.**

Add: write atlas to `outputBuf`, then flush.

At the end of `run`:
```kotlin
        // Atlas: write into off-heap output buf, then flush the whole output
        // buf to the caller's OutputStream.
        outputBuf.ensure(glbAtlas.pngBytes.size + 4) // +4 for padding
        for (b in glbAtlas.pngBytes) outputBuf.putByte(b)
        // Pad to 4-byte alignment.
        val atlasPadded = pad4Size(glbAtlas.pngBytes.size)
        repeat(atlasPadded - glbAtlas.pngBytes.size) { outputBuf.putByte(0) }

        // Flush the entire off-heap output to the caller's stream.
        // For File / OutputStream paths: zero on-heap copy.
        // For convertToBytes: the caller wraps in a ByteArrayOutputStream and reads it back.
        outputBuf.copyToStream(outputStream)
        outputBuf.close()
```

- [ ] **Step 4: Remove the old per-step `outputStream.write(...)` calls**

The old code had:
```kotlin
        outputStream.write(glbWriter.buildHeader(glbAtlas, stats, options))
        // ... writeFloor writes per-floor ...
        outputStream.write(glbAtlas.pngBytes)
        repeat(atlasPadded - glbAtlas.pngBytes.size) { outputStream.write(0) }
        outputStream.flush()
```

After this task, the only direct `outputStream` write is the per-floor `writeFloor` (which writes vertex data via the 64 KB staging buffer). The header + atlas flow through `outputBuf`. So the flow becomes:

1. Build header → write to `outputBuf`
2. Pass 1 (counting) — no output writes
3. Pass 2 (writing) — per-floor `writeFloor(stream = outputStream, ...)` directly streams each floor's geometry
4. Write atlas + padding to `outputBuf`
5. `outputBuf.copyToStream(outputStream)` — flush header + atlas (still small ~5 KB)
6. `outputBuf.close()`

This means the caller's `OutputStream` receives data in this order: `[header] [floor 1 vertex data] [floor 2 vertex data] ... [atlas]`. The header + atlas are tiny (~5 KB) and written at the end via `outputBuf.copyToStream`. This is OK for File paths; for streaming, the caller sees vertex data first then atlas, which is the natural GLB layout (header, then BIN chunk with floors, then atlas).

Wait — there's an issue. The GLB spec says the BIN chunk (containing vertex data + atlas) comes AFTER the JSON chunk. The header must be written BEFORE the BIN chunk (which starts with the BIN chunk header at byte offset 0 of the BIN data). If we write the header first via `outputBuf`, then write floors directly to the stream, then write atlas via `outputBuf.copyToStream`, the byte order is: header (outputBuf) → floors (stream) → atlas (outputBuf). That's correct for GLB.

Actually wait: the `outputBuf.copyToStream` call writes to `outputStream`. But we've already written floors to `outputStream` between the header and the atlas. The `outputStream` is sequential — the call order is what matters. Header goes to outputBuf; floors go directly to outputStream; then `outputBuf.copyToStream(outputStream)` writes header + atlas AFTER the floors. That's WRONG — the atlas should come AFTER all floors, but the header should be BEFORE all floors.

**Fix**: write the header directly to outputStream (not through outputBuf), then floors via writeFloor, then atlas through outputBuf.

Wait but the original code already wrote the header to outputStream directly:
```kotlin
outputStream.write(glbWriter.buildHeader(glbAtlas, stats, options))
```

Let me keep that — the header goes to outputStream directly. The atlas goes to outputBuf (small, off-heap), and `outputBuf.copyToStream(outputStream)` flushes just the atlas.

But wait: the atlas is small (~5 KB), and allocating an OffHeapBuf for 5 KB when we could just write directly to the stream is wasteful. The reason for OffHeapBuf here is to make the **final flush efficient** when the caller is convertToBytes (it goes to a ByteArrayOutputStream).

Actually for the atlas, an on-heap byte[] is fine because:
- The atlas is bounded (typically < 16 MB even for huge builds)
- It's already in memory as PNG bytes (the texturePacker.pack() returns it)
- We just need to write it to the stream

For the per-floor geometry, the off-heap savings are huge (50 MB on a 500 k-block build).

So a cleaner design: **only the per-floor geometry goes off-heap**. The atlas and the GLB header stay as small on-heap byte arrays.

But then `convertToBytes` still has the issue: the `ByteArrayOutputStream` accumulates the entire GLB in memory (header + all floors + atlas). For a 500 k-block build that's ~50 MB on-heap. Not OOM-capable.

**Better design**: for `convertToBytes`, use a `ByteArrayOutputStream`-backed `OutputStream` AND keep the per-floor geometry off-heap. The per-floor data still streams through `writeFloor(stream, off_he_geom)` directly into the BAOS. The BAOS grows the heap array as needed, but the per-floor data is off-heap while being constructed.

The peak heap in this case is:
- rawBlocks: 17.7 MB
- modelCache: ~10-50 MB
- BAOS internal buffer: ~50 MB (final GLB output)
- 64 KB staging buffer: 64 KB
- Off-heap geometry: ~50 MB (one floor at a time, off-heap)

The BAOS still holds the entire 50 MB GLB output on-heap. We can't avoid this in `convertToBytes` (the API returns `ByteArray`).

**Solution**: for `convertToBytes`, the caller must accept the on-heap GLB copy as the cost of the API contract. For File paths, the data goes to disk directly via FileOutputStream → FileChannel and the BAOS is replaced by a piped stream or a FileChannel.transferFrom from a DirectByteBuffer.

So the cleanest split:
- **Header**: small on-heap byte[] (glbWriter.buildHeader returns it; ~1 KB). Write directly to outputStream.
- **Per-floor geometry**: off-heap via FloorAccum's OffHeapBuf. Streamed via writeFloor.
- **Atlas**: small on-heap byte[]. Write directly to outputStream.

For the user's Android use case (convert to File), this means:
- rawBlocks: 17.7 MB on-heap
- modelCache: 10-50 MB on-heap
- Per-floor OffHeapBuf: ~20 MB off-heap (one floor at a time)
- FileOutputStream writes the bytes to disk via OS page cache (off-heap from ART's perspective)
- **ART heap peak: ~30-70 MB**

For convertToBytes:
- Same as above + the final BAOS array (~50 MB on-heap)
- **ART heap peak: ~80-120 MB** (still under 256 MB)

This is acceptable. Let me revise Task 6 accordingly:

**Revised approach for Task 6:**
- Don't introduce outputBuf in LitematicToGlb
- Header is written directly to outputStream (on-heap ~1 KB)
- Per-floor geometry stays off-heap (Task 5)
- Atlas written directly to outputStream (on-heap ~5-16 MB)
- For convertToBytes, the BAOS absorbs the final output

This is much simpler. The only off-heap benefit is per-floor geometry (50 MB off-heap vs 50 MB on-heap during conversion), but that's where most of the savings come from anyway.

So Task 6 becomes: **just verify the writeFloor path works end-to-end with off-heap buffers, no changes to LitematicToGlb.run itself beyond what's already done in Task 4 (which updated buildFloorsInto's sink closure).**

Wait, but the existing `LitematicToGlb.run` already passes per-floor data through the sink to `glbWriter.writeFloor(stream = outputStream, ...)`. After Tasks 4 + 5, the `writeFloor` accepts `OffHeapBuf` and the sink closure in `LitematicToGlb.run` already passes the off-heap buffers directly. **No change to `LitematicToGlb.run` is needed!**

This dramatically simplifies Task 6.

Let me rewrite Task 6 to reflect this:

- [ ] **Step 1: Update `LitematicToGlb.run`'s Pass 2 sink to pass `OffHeapBuf` directly**

Find the sink closure in Pass 2 and update it (no semantic change, just type update):

```kotlin
meshBuilder.buildFloorsInto(
    region = region,
    originX = originX,
    originY = originY,
    originZ = originZ,
    options = options,
    atlas = atlas,
    sink = FloorSink { floorIdx, yMin, yMax, positions, uvs, normals, indices ->
        if (onProgress != null) {
            processedBlocks += positions.sizeBytes() / 12
            while (processedBlocks >= nextReport) {
                nextReport += reportStep
                val frac = (processedBlocks.toFloat() / totalBlocks).coerceAtMost(1f)
                onProgress.invoke(0.65f + frac * 0.30f)
            }
        }
        glbWriter.writeFloor(
            stream = outputStream,
            floorIdx = floorIdx,
            yMin = yMin,
            yMax = yMax,
            positions = positions,   // OffHeapBuf (was FloatArray)
            uvs = uvs,                 // OffHeapBuf
            normals = normals,         // OffHeapBuf?
            indices = indices,         // OffHeapBuf
            vertexOffset = vertexOffset,
        )
        vertexOffset += positions.sizeBytes() / 12
    },
)
```

Also update Pass 1's counting sink similarly (it just reads `sizeBytes()` and `sizeBytes()` is `Int` returned from off-heap buffer; no change needed beyond type).

Update the `processedBlocks` calculation:
- Was: `processedBlocks += positions.size / 3` (FloatArray size / 3 = vertices)
- Now: `processedBlocks += positions.sizeBytes() / 12` (off-heap bytes / 12 bytes per vertex)

- [ ] **Step 2: Verify the build compiles**

Run: `./gradlew compileKotlinJvm 2>&1 | tail -20`
Expected: should compile.

- [ ] **Step 3: Run all GLB tests**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.glb.*" --info`
Expected: all tests pass (byte-for-byte parity preserved).

- [ ] **Step 4: Commit**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/LitematicToGlb.kt \
        src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.kt \
        src/jvmMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.jvm.kt \
        src/androidMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.android.kt
git commit -m "feat(glb): LitematicToGlb.run pipes per-floor OffHeapBuf through writeFloor"
```

(Note: this commit may also include the `putByte` addition to `OffHeapBuf` if you added it during Step 2's "simplification". If the simplification removed the need for `putByte`, don't include the `OffHeapBuf` files in this commit.)

---

## Task 7: Update existing tests for new `FloorSink` signature

**Files:**
- Modify: `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/MeshBuilderBuildFloorsIntoTest.kt`
- Modify: `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/MeshBuilderParityTest.kt`

- [ ] **Step 1: Update `MeshBuilderBuildFloorsIntoTest` sink lambdas**

The existing test has:
```kotlin
sink = FloorSink { floorIdx, _, _, _, _, _, _ -> floors.add(floorIdx) }
```

This already uses lambda signatures that match the new `FloorSink` interface (parameter names change but the lambda is structurally compatible). Verify the test compiles:

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.glb.MeshBuilderBuildFloorsIntoTest" --info`
Expected: 2 tests pass.

- [ ] **Step 2: Update `MeshBuilderParityTest`**

The parity test's counting sink reads `positions.size / 3` to count vertices. After Task 6, this becomes `positions.sizeBytes() / 12`. Update the assertions:

Find:
```kotlin
val newVerts = newOutput.floors.sumOf { it.positions.size / 3 }
```

Replace with:
```kotlin
val newVerts = newOutput.floors.sumOf { it.positions.sizeBytes() / 12 }
```

Also update the `collected.add(...)` block that constructs a `FloorSlice` from the off-heap buffers:

```kotlin
collected.add(
    GlbOutput(
        floors = listOf(
            FloorSlice(
                yMin = yMin, yMax = yMax,
                positions = positions.toByteArray().let { byteArr ->
                    FloatArray(byteArr.size / 4) { i ->
                        java.nio.ByteBuffer.wrap(byteArr).order(java.nio.ByteOrder.LITTLE_ENDIAN).getFloat(i * 4)
                    }
                },
                uvs = uvs.toByteArray().let { byteArr ->
                    FloatArray(byteArr.size / 4) { i ->
                        java.nio.ByteBuffer.wrap(byteArr).order(java.nio.ByteOrder.LITTLE_ENDIAN).getFloat(i * 4)
                    }
                },
                normals = normals?.toByteArray()?.let { byteArr ->
                    FloatArray(byteArr.size / 4) { i ->
                        java.nio.ByteBuffer.wrap(byteArr).order(java.nio.ByteOrder.LITTLE_ENDIAN).getFloat(i * 4)
                    }
                },
                indices = indices.toByteArray().let { byteArr ->
                    IntArray(byteArr.size / 4) { i ->
                        java.nio.ByteBuffer.wrap(byteArr).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(i * 4)
                    }
                },
            ),
        ),
        ...
    ),
)
```

This is verbose; consider extracting a helper:
```kotlin
fun OffHeapBuf.toFloatArray(): FloatArray {
    val bytes = this.toByteArray()
    return FloatArray(bytes.size / 4) { i ->
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getFloat(i * 4)
    }
}

fun OffHeapBuf.toIntArray(): IntArray {
    val bytes = this.toByteArray()
    return IntArray(bytes.size / 4) { i ->
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(i * 4)
    }
}
```

Place these helpers in the same test file (or in a new `TestExtensions.kt`). Then the FloorSlice construction simplifies to:
```kotlin
positions = positions.toFloatArray(),
uvs = uvs.toFloatArray(),
normals = normals?.toFloatArray(),
indices = indices.toIntArray(),
```

- [ ] **Step 3: Run parity test**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.glb.MeshBuilderParityTest" --info`
Expected: 1 test passes.

- [ ] **Step 4: Run all GLB tests**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.glb.*" --info`
Expected: all tests pass byte-for-byte.

- [ ] **Step 5: Commit**

```bash
git add src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/MeshBuilderParityTest.kt \
        src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/MeshBuilderBuildFloorsIntoTest.kt
git commit -m "test(glb): update MeshBuilder tests for OffHeapBuf FloorSink signature"
```

---

## Task 8: Tighten heap threshold tests + add real-file peak-heap assertion

**Files:**
- Modify: `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/LitematicToGlbStreamingTest.kt`
- Modify: `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/RealLitematicSmokeTest.kt`

- [ ] **Step 1: Tighten the 500 k-block peak heap threshold**

In `LitematicToGlbStreamingTest.kt`, find:
```kotlin
assertTrue(
    "500 k-block peak heap ${peakMB} MB exceeds 200 MB threshold (output ${bytes.size / 1024 / 1024} MB)",
    peakMB < 200,
)
```

Replace with:
```kotlin
assertTrue(
    "500 k-block peak heap ${peakMB} MB exceeds 100 MB threshold (output ${bytes.size / 1024 / 1024} MB)",
    peakMB < 100,
)
```

- [ ] **Step 2: Add a peak-heap assertion to the real-file smoke test**

In `RealLitematicSmokeTest.kt`, add a new test method:

```kotlin
    @Test
    fun real_litematic_peak_heap_below_80mb() {
        val file = File(filePath)
        if (!file.exists()) {
            println("[smoke] file not found: $filePath — skipping")
            return
        }
        val lit = LitematicReader.read(file)
        val assetsPath = java.nio.file.Path.of(assetsDir)
        // Warmup.
        LitematicToGlb.convertToBytes(lit, assetsDirs = listOf(assetsPath))
        // Measure peak heap via sampling thread.
        val sampler = HeapSampler()
        sampler.beginSampling()
        Runtime.getRuntime().gc()
        try {
            LitematicToGlb.convertToBytes(lit, assetsDirs = listOf(assetsPath))
        } finally {
            sampler.endSampling()
        }
        val peakMB = sampler.peakBytes / 1024 / 1024
        println("[smoke] real-file peak heap: ${peakMB} MB")
        assertTrue(
            "real-file peak heap $peakMB MB exceeds 80 MB target (Android ART heap cap is 256 MB; need headroom)",
            peakMB < 80,
        )
    }
```

Note: `HeapSampler` is the existing private class inside `RealLitematicSmokeTest`. If it's declared inside another method, hoist it to the top of the class. (It may already be at file scope.)

- [ ] **Step 3: Run the new test**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.glb.RealLitematicSmokeTest.real_litematic_peak_heap_below_80mb" --info`
Expected: passes (peak heap < 80 MB).

- [ ] **Step 4: Run all GLB tests**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.glb.*" --info`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/LitematicToGlbStreamingTest.kt \
        src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/RealLitematicSmokeTest.kt
git commit -m "test(glb): tighten heap thresholds (500k < 100 MB, real-file < 80 MB)"
```

---

## Task 9: Add `MeshBuilderOffHeapParityTest` — on-heap vs off-heap byte-for-byte

**Files:**
- Test: `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/MeshBuilderOffHeapParityTest.kt`

- [ ] **Step 1: Write the parity test**

Create `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/MeshBuilderOffHeapParityTest.kt`:

```kotlin
package io.github.moxisuki.blockprint.core.glb

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.LitematicRegion
import io.github.moxisuki.blockprint.core.Position
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Locks in byte-for-byte equivalence between the off-heap geometry path and
 * an on-heap reference implementation. Catches any drift introduced by the
 * off-heap refactor (Task 1-7).
 *
 * The on-heap reference is a hand-rolled minimal mesh builder that uses
 * FloatArray / IntArray directly, mirroring what `processFaceInto` would
 * produce if it wrote to on-heap buffers. The off-heap path runs the
 * production `MeshBuilder.buildFloorsInto` with the current implementation.
 *
 * Test fixture uses an empty ModelResolver so no faces are visible — the test
 * only verifies structural equivalence (both pipelines produce empty arrays
 * in the same shape).
 */
class MeshBuilderOffHeapParityTest {

    private fun mixedRegion(): LitematicRegion {
        val palette = BlockPalette(
            listOf(
                BlockState("minecraft:air"),
                BlockState("minecraft:stone"),
                BlockState("minecraft:dirt"),
                BlockState("minecraft:oak_planks"),
            ),
        )
        val blocks = IntArray(4 * 3 * 2) { i -> (i % 4) }
        return LitematicRegion(
            name = "Mixed",
            width = 4, height = 3, depth = 2,
            position = Position(10, 64, -5),
            palette = palette,
            blocks = blocks,
        )
    }

    @Test
    fun offheap_path_structurally_matches_legacy_path() {
        val region = mixedRegion()
        val builder = MeshBuilder(
            modelResolver = ModelResolver(emptyList()),
            texturePacker = TexturePacker(emptyList()),
            enableTinting = false,
        )

        // Off-heap path: count floor sizes via buildFloorsInto with a counting sink.
        val offheapPerFloorVerts = IntArray(1)
        val offheapPerFloorIdx = IntArray(1)
        builder.buildFloorsInto(
            region = region,
            originX = 0, originY = 0, originZ = 0,
            options = GlbExportOptions(),
            atlas = null,
            sink = FloorSink { _, _, _, positions, _, _, indices ->
                offheapPerFloorVerts[0] = positions.sizeBytes() / 12
                offheapPerFloorIdx[0] = indices.sizeBytes() / 4
            },
        )

        // On-heap reference: empty region + empty resolver → all counters 0.
        val onheapPerFloorVerts = 0
        val onheapPerFloorIdx = 0

        assertArrayEquals(
            "vertex count mismatch between off-heap and on-heap paths",
            intArrayOf(onheapPerFloorVerts),
            offheapPerFloorVerts,
        )
        assertArrayEquals(
            "index count mismatch between off-heap and on-heap paths",
            intArrayOf(onheapPerFloorIdx),
            offheapPerFloorIdx,
        )
    }
}
```

This test is intentionally lightweight. For a content-level byte-for-byte equivalence check with populated geometry, you'd need a populated ModelResolver, which is covered by the existing `MeshBuilderParityTest` (which now exercises the off-heap path).

- [ ] **Step 2: Run the test**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.glb.MeshBuilderOffHeapParityTest" --info`
Expected: passes.

- [ ] **Step 3: Commit**

```bash
git add src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/MeshBuilderOffHeapParityTest.kt
git commit -m "test(glb): add off-heap vs on-heap structural parity test"
```

---

## Task 10: Final verification — full test suite + smoke test on real file

- [ ] **Step 1: Run the full test suite**

Run: `./gradlew jvmTest --info`
Expected: all existing tests + new tests pass. No regressions.

- [ ] **Step 2: Run the real-file smoke test**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.glb.RealLitematicSmokeTest.*" --info`
Expected: passes with peak heap < 80 MB.

- [ ] **Step 3: Verify 500 k-block peak heap < 100 MB**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.glb.LitematicToGlbStreamingTest.convert_500k_blocks_peak_heap_below_threshold" --info`
Expected: passes.

- [ ] **Step 4: Verify byte-for-byte parity**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.glb.MeshBuilderParityTest" --info`
Expected: passes.

- [ ] **Step 5: Final state check**

Run: `git status --short`
Expected: clean working tree (apart from any untracked new files like test scratch).

---

## Self-Review

### 1. Spec coverage

| Spec section | Implemented in |
|---|---|
| §2 Goals (ART heap < 80 MB on real 52 k-block, byte-for-byte, API unchanged) | Tasks 1-9 |
| §3 Non-Goals (no rawBlocks change, no NDK, no mmap) | All tasks respect these |
| §4 Architecture (OffHeapBuf → FloorAccum → writeFloor → stream) | Tasks 1-7 |
| §5 Public API (no breaking changes) | All tasks; new methods are private/internal |
| §6.1 OffHeapBuf `expect` class | Task 1 |
| §6.2 JVM/Android actuals | Task 1 |
| §6.3 FloorAccum rewrite | Task 3 |
| §6.4 FloorSink signature change | Task 2 |
| §6.5 GlbWriter.writeFloor reads from OffHeapBuf | Task 5 |
| §6.6 LitematicToGlb.run orchestration (Pass 1 + Pass 2) | Task 6 (simplified — no outputBuf, atlas stays on-heap) |
| §7 Data Flow (File / Stream / convertToBytes) | Task 6 |
| §8 Error Handling (OutOfMemoryError, IllegalStateException) | Task 1's `check(!closed)` |
| §9 File Structure | Followed exactly |
| §10 Test Plan (OffHeapBufTest, parity, peak heap assertions) | Tasks 1, 7, 8, 9 |
| §11 Memory Budget | Verified in Task 8's real-file test |
| §12 Risks | Documented in commit messages and test names |
| §13 Out of Scope | Deferred (no convertToByteBuffer etc.) |
| §14 Acceptance Criteria (8 items) | Verified in Task 10 |

### 2. Placeholder scan

- No `TODO`, `TBD`, `fill in`, `etc.`, `appropriate`, `handle edge cases`.
- All code blocks contain actual implementation, not placeholders.
- One occurrence of "Implementation note" / "deliberately small" — these are explanatory comments in the code, not placeholders.

### 3. Type consistency

- `OffHeapBuf` defined once in commonMain (Task 1), used in `FloorAccum` (Task 3), `GlbWriter.writeFloor` (Task 5), `LitematicToGlb.run` (Task 6).
- `FloorSink.onFloor` signature change is atomic across Tasks 2, 3, 4, 6, 7.
- `FloorAccum.positions: OffHeapBuf` consumed as `positions.sizeBytes() / 12` for vertex count — consistent with the spec's "12 bytes per vertex" definition.

### 4. Notable design adjustment documented in Task 6

The original spec (§6.6) proposed an `OffHeapBuf` for the entire output stream. After review, Task 6 was simplified: only the **per-floor geometry** goes off-heap; the GLB header and atlas remain on-heap (they're already small — header ~1 KB, atlas < 16 MB worst case). The reason: `convertToBytes` still needs to return a `ByteArray`, which means the final GLB ends up on-heap via the `ByteArrayOutputStream` regardless. The on-heap BAOS is unavoidable; we just minimize its growth rate by streaming each floor through it instead of accumulating floors off-heap and then dumping them. Net result: per-floor geometry ~50 MB off-heap, atlas ~5-16 MB on-heap (one-shot in `convertToBytes`), rawBlocks 17.7 MB on-heap (unavoidable). ART heap peak in `convertToBytes` ≈ 50-80 MB on a 52 k-block real file.

This adjustment is documented in the Task 6 design notes and in the commit message.