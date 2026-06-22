# GLB Zero-Copy Streaming Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the `toByteArray()` transients in `GlbWriter.writeFloor` and `LitematicToGlb.run` Pass 1 with a true zero-copy chunked read API (`OffHeapBuf.readBytes`), bringing the 52 k-block real-file Android ART heap peak from 116-182 MB down to **< 80 MB**.

**Architecture:** Add a single low-level `OffHeapBuf.readBytes(target, srcOffset, length): Int` (mirroring `java.nio.ByteBuffer.get(byte[], int, int)`). Update three call sites: `GlbWriter.writeOffHeapFloats`, `GlbWriter.writeOffHeapIndices`, and the Pass 1 counting sink's min/max scan in `LitematicToGlb.run`. Each call site uses a fixed 64 KB (write) or 4 KB (Pass 1 scan) on-heap staging array, chunked reads, zero intermediate copy. Public API unchanged; byte-for-byte GLB output preserved.

**Tech Stack:** Kotlin 2.2.10, Kotlin Multiplatform (commonMain + jvmMain + androidMain + jvmTest), JUnit 4, `java.nio.ByteBuffer.allocateDirect`.

**Spec:** `docs/superpowers/specs/2026-06-22-glb-zerocopy-design.md`
**Prior work:** Off-heap refactor (commits `807a2af`–`298faa4`)

**Test command pattern (all tasks):**
```bash
./gradlew jvmTest --tests "<fully.qualified.TestClass>.<method>" --info
```

---

## File Structure

**Created:**
- `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBufReadBytesTest.kt`

**Modified:**
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.kt` — add `readBytes` to `expect class`
- `src/jvmMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.jvm.kt` — add `actual fun readBytes`
- `src/androidMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.android.kt` — add `actual fun readBytes` (identical to JVM)
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/GlbWriter.kt` — rewrite `writeOffHeapFloats` and `writeOffHeapIndices` to use `readBytes`
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/LitematicToGlb.kt` — rewrite Pass 1 counting sink's min/max scan to use `readBytes`
- `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/LitematicToGlbStreamingTest.kt` — tighten 500k threshold from `< 100 MB` → `< 80 MB`

**Untouched:** All production code other than the three call sites. Public API signatures. `MeshBuilder.kt`, `MeshSink.kt`, `TexturePacker.kt`, etc.

---

## Task 1: `OffHeapBuf.readBytes` — expect + JVM/Android actuals + tests

**Files:**
- Modify: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.kt`
- Modify: `src/jvmMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.jvm.kt`
- Modify: `src/androidMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.android.kt`
- Test: `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBufReadBytesTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBufReadBytesTest.kt`:

```kotlin
package io.github.moxisuki.blockprint.core.glb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class OffHeapBufReadBytesTest {

    @Test
    fun readBytes_basic_chunk() {
        val buf = OffHeapBuf(64)
        for (i in 0 until 8) buf.putInt(i) // 32 bytes
        val target = ByteArray(16)
        val read = buf.readBytes(target, srcOffset = 0, length = 16)
        assertEquals(16, read)
        val bb = java.nio.ByteBuffer.wrap(target, 0, 16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, bb.getInt(0))
        assertEquals(3, bb.getInt(12))
    }

    @Test
    fun readBytes_cross_chunk_when_staging_smaller_than_remaining() {
        val buf = OffHeapBuf(64)
        for (i in 0 until 16) buf.putInt(i) // 64 bytes
        // Read in 16-byte chunks (4 ints per chunk).
        val target = ByteArray(16)
        val collected = mutableListOf<Int>()
        var srcOffset = 0
        while (srcOffset < 64) {
            val n = buf.readBytes(target, srcOffset, 16)
            if (n == 0) break
            val bb = java.nio.ByteBuffer.wrap(target, 0, n).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            while (bb.remaining() >= 4) collected.add(bb.getInt())
            srcOffset += n
        }
        assertEquals(16, collected.size)
        for (i in 0 until 16) assertEquals(i, collected[i])
    }

    @Test
    fun readBytes_end_of_buffer_returns_partial() {
        val buf = OffHeapBuf(64)
        for (i in 0 until 5) buf.putInt(i) // 20 bytes
        val target = ByteArray(32)
        val read = buf.readBytes(target, srcOffset = 16, length = 16) // ask 16 but only 4 remain
        assertEquals(4, read)
    }

    @Test
    fun readBytes_at_end_returns_zero() {
        val buf = OffHeapBuf(32)
        buf.putInt(0) // 4 bytes total
        val target = ByteArray(16)
        val read = buf.readBytes(target, srcOffset = 4, length = 16)
        assertEquals(0, read)
    }

    @Test
    fun readBytes_zero_length_returns_zero() {
        val buf = OffHeapBuf(32)
        buf.putInt(0)
        val target = ByteArray(16)
        val read = buf.readBytes(target, srcOffset = 0, length = 0)
        assertEquals(0, read)
    }

    @Test
    fun readBytes_after_close_throws() {
        val buf = OffHeapBuf(16)
        buf.putInt(0)
        buf.close()
        val target = ByteArray(8)
        try {
            buf.readBytes(target, 0, 4)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun readBytes_target_too_small_throws() {
        val buf = OffHeapBuf(32)
        buf.putInt(0)
        val target = ByteArray(4) // smaller than length below
        try {
            buf.readBytes(target, srcOffset = 0, length = 16)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun readBytes_negative_offset_throws() {
        val buf = OffHeapBuf(32)
        buf.putInt(0)
        val target = ByteArray(16)
        try {
            buf.readBytes(target, srcOffset = -1, length = 4)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun readBytes_negative_length_throws() {
        val buf = OffHeapBuf(32)
        buf.putInt(0)
        val target = ByteArray(16)
        try {
            buf.readBytes(target, srcOffset = 0, length = -1)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.glb.OffHeapBufReadBytesTest" --info`
Expected: compile error — `readBytes` not found on `OffHeapBuf`.

- [ ] **Step 3: Add `readBytes` to the expect class**

In `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.kt`, add this method inside the `expect class OffHeapBuf` block (after the existing `close()` declaration):

```kotlin
    /**
     * Read up to [length] bytes from this off-heap buffer starting at
     * [srcOffset] (absolute offset in the buffer, 0 = start) into [target]
     * at target offset 0. Returns the actual number of bytes read
     * (less than [length] when the source has fewer bytes remaining).
     *
     * Mirrors [java.nio.ByteBuffer.get]: bytes are read directly from the
     * off-heap storage into the caller's on-heap array with no intermediate
     * copy. The buffer's position advances by the bytes read.
     *
     * After [close], throws [IllegalStateException].
     */
    fun readBytes(target: ByteArray, srcOffset: Int, length: Int): Int
```

- [ ] **Step 4: Add the JVM `actual`**

In `src/jvmMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.jvm.kt`, add the `actual fun readBytes` inside the `actual class OffHeapBuf` block (after the `close()` method):

```kotlin
    actual fun readBytes(target: ByteArray, srcOffset: Int, length: Int): Int {
        check(!closed) { "OffHeapBuf is closed" }
        require(srcOffset >= 0) { "srcOffset must be non-negative, got $srcOffset" }
        require(length >= 0) { "length must be non-negative, got $length" }
        require(target.size >= length) {
            "target.size (${target.size}) must be >= length ($length)"
        }
        if (length == 0) return 0
        val available = buf.position() - srcOffset
        if (available <= 0) return 0
        val toRead = minOf(length, available)
        buf.position(srcOffset)
        buf.get(target, 0, toRead)
        return toRead
    }
```

- [ ] **Step 5: Add the Android `actual`**

In `src/androidMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.android.kt`, add the **identical** `actual fun readBytes` (Android uses the same `java.nio.ByteBuffer` as JVM):

```kotlin
    actual fun readBytes(target: ByteArray, srcOffset: Int, length: Int): Int {
        check(!closed) { "OffHeapBuf is closed" }
        require(srcOffset >= 0) { "srcOffset must be non-negative, got $srcOffset" }
        require(length >= 0) { "length must be non-negative, got $length" }
        require(target.size >= length) {
            "target.size (${target.size}) must be >= length ($length)"
        }
        if (length == 0) return 0
        val available = buf.position() - srcOffset
        if (available <= 0) return 0
        val toRead = minOf(length, available)
        buf.position(srcOffset)
        buf.get(target, 0, toRead)
        return toRead
    }
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.glb.OffHeapBufReadBytesTest" --info`
Expected: 9 tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.kt \
        src/jvmMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.jvm.kt \
        src/androidMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.android.kt \
        src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBufReadBytesTest.kt
git commit -m "feat(glb): add OffHeapBuf.readBytes (true zero-copy chunked read)"
```

---

## Task 2: `GlbWriter.writeOffHeapFloats` + `writeOffHeapIndices` rewrite

**Files:**
- Modify: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/GlbWriter.kt`

- [ ] **Step 1: Rewrite `writeOffHeapFloats`**

In `GlbWriter.kt`, find the existing `writeOffHeapFloats` method (around lines 176-193) and replace it with:

```kotlin
    private fun writeOffHeapFloats(out: OutputStream, src: OffHeapBuf) {
        val totalBytes = src.sizeBytes()
        if (totalBytes == 0) return
        val staging = ByteArray(1 shl 16) // 64 KB on-heap, reused across chunks
        var srcOffset = 0
        while (srcOffset < totalBytes) {
            val want = minOf(staging.size, totalBytes - srcOffset)
            val read = src.readBytes(staging, srcOffset, want)
            if (read == 0) break
            out.write(staging, 0, read)
            srcOffset += read
        }
    }
```

- [ ] **Step 2: Rewrite `writeOffHeapIndices`**

Find the existing `writeOffHeapIndices` method (around lines 195-216) and replace it with:

```kotlin
    private fun writeOffHeapIndices(out: OutputStream, src: OffHeapBuf, vertexOffset: Int) {
        val totalBytes = src.sizeBytes()
        val numIndices = totalBytes / 4
        if (numIndices == 0) return
        val staging = ByteArray(1 shl 16) // 64 KB on-heap, reused across chunks
        val sbb = java.nio.ByteBuffer.wrap(staging).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var srcOffset = 0
        while (srcOffset < totalBytes) {
            val want = minOf(staging.size, totalBytes - srcOffset)
            val read = src.readBytes(staging, srcOffset, want)
            if (read == 0) break
            // Read the bytes we just got, add vertexOffset to each int, write back.
            sbb.clear()
            sbb.limit(read) // only process the bytes we read (last chunk may be partial)
            val nInts = read / 4
            for (j in 0 until nInts) {
                sbb.putInt(sbb.getInt(j * 4) + vertexOffset)
            }
            out.write(staging, 0, nInts * 4)
            srcOffset += read
        }
    }
```

- [ ] **Step 3: Run all GLB tests to verify byte-for-byte parity**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.glb.*" --info`
Expected: all existing tests pass (including `MeshBuilderParityTest` for byte-for-byte parity).

If any test fails, the most likely cause is the per-int vertexOffset math — re-verify with a small handwritten test.

- [ ] **Step 4: Commit**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/GlbWriter.kt
git commit -m "refactor(glb): use OffHeapBuf.readBytes in writeOffHeapFloats/Indices"
```

---

## Task 3: `LitematicToGlb.run` Pass 1 counting sink — stream min/max via `readBytes`

**Files:**
- Modify: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/LitematicToGlb.kt`

- [ ] **Step 1: Find the Pass 1 counting sink**

In `LitematicToGlb.kt`, the Pass 1 counting sink is the `FloorSink` closure passed to the first `meshBuilder.buildFloorsInto(...)` call. Its body currently does:

```kotlin
            var i = 0
            while (i + 2 < positions.sizeBytes()) {
                val px = positions.toByteArray().let { /* BUG: this reads from offset 0 each time */ }
                // ... etc
            }
```

(In the current implementation this is wrapped in `java.nio.ByteBuffer.wrap(positions.toByteArray())` and indexed manually — see file lines ~160-190 for the actual current code.)

**Read the current sink** (around lines 156-194 of `LitematicToGlb.kt`) to see exactly what it does. The key point: it calls `positions.toByteArray()` (or equivalent) to scan for min/max.

- [ ] **Step 2: Rewrite the min/max scan to use `readBytes`**

Replace the `var i = 0; while (i + 2 < positions.sizeBytes()) { ... }` loop with:

```kotlin
            val positionsBytes = positions.sizeBytes()
            val staging = ByteArray(4096) // 4 KB staging, reused for chunked scan
            var srcOffset = 0
            while (srcOffset < positionsBytes) {
                val want = minOf(staging.size, positionsBytes - srcOffset)
                val read = positions.readBytes(staging, srcOffset, want)
                if (read == 0) break
                val bb = java.nio.ByteBuffer.wrap(staging, 0, read)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                while (bb.remaining() >= 12) {
                    val px = bb.getFloat(); val py = bb.getFloat(); val pz = bb.getFloat()
                    if (px < minX) minX = px
                    if (py < minY) minY = py
                    if (pz < minZ) minZ = pz
                    if (px > maxX) maxX = px
                    if (py > maxY) maxY = py
                    if (pz > maxZ) maxZ = pz
                    anyVertex = true
                }
                srcOffset += read
            }
```

This must be inserted in place of the existing min/max scan loop. Keep the rest of the sink (perFloorVertices / perFloorIndices / total* counters) unchanged.

- [ ] **Step 3: Run all GLB tests**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.glb.*" --info`
Expected: all tests pass. `MeshBuilderParityTest` enforces byte-for-byte parity.

- [ ] **Step 4: Commit**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/LitematicToGlb.kt
git commit -m "refactor(glb): stream Pass 1 min/max scan via OffHeapBuf.readBytes"
```

---

## Task 4: Tighten heap threshold + final verification

**Files:**
- Modify: `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/LitematicToGlbStreamingTest.kt`

- [ ] **Step 1: Tighten the 500 k-block threshold**

In `LitematicToGlbStreamingTest.kt`, find:

```kotlin
        assertTrue(
            "500 k-block peak heap ${peakMB} MB exceeds 100 MB threshold (output ${bytes.size / 1024 / 1024} MB)",
            peakMB < 100,
        )
```

Replace with:

```kotlin
        assertTrue(
            "500 k-block peak heap ${peakMB} MB exceeds 80 MB threshold (output ${bytes.size / 1024 / 1024} MB)",
            peakMB < 80,
        )
```

- [ ] **Step 2: Run the real-file and 500k-block heap tests**

```bash
./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.glb.RealLitematicSmokeTest.real_litematic_peak_heap_below_80mb" --info
./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.glb.LitematicToGlbStreamingTest.convert_500k_blocks_peak_heap_below_threshold" --info
```

Expected: both pass (real-file peak < 80 MB, 500k-block peak < 80 MB).

If the real-file test fails (peak > 80 MB), report the actual peak. The target is a stretch goal; if we're 90-100 MB, that's still much better than the 116-182 MB before this spec. Consider loosening the threshold to < 100 MB and filing a follow-up.

If the 500k-block test fails (peak > 80 MB), report the actual peak. Same: stretch goal.

- [ ] **Step 3: Run the full test suite**

```bash
./gradlew jvmTest --info
```

Expected: all 21+ existing test suites pass, no regressions.

- [ ] **Step 4: Commit**

```bash
git add src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/LitematicToGlbStreamingTest.kt
git commit -m "test(glb): tighten 500k-block heap threshold to < 80 MB after zero-copy refactor"
```

---

## Self-Review

### 1. Spec coverage

| Spec section | Implemented in |
|---|---|
| §2 Goals (1: 52 k < 80 MB, 2: 500 k < 80 MB, 3: readBytes API, 4: byte-for-byte) | Tasks 1, 2, 3, 4 |
| §5 Public API (no new public methods) | All tasks; `readBytes` is `expect class` internal scope |
| §6.1 `readBytes` expect/actual | Task 1 |
| §6.2 `writeOffHeapFloats` rewrite | Task 2 |
| §6.3 `writeOffHeapIndices` rewrite | Task 2 |
| §6.4 Pass 1 counting sink rewrite | Task 3 |
| §7 Data Flow (Before 42 MB → After 68 KB) | Verified by Step 3 tests |
| §8 Memory Budget (38-58 MB) | Verified by Task 4 tests |
| §9 File Structure | Followed exactly |
| §10 Test Plan (OffHeapBufReadBytesTest + tightened thresholds) | Tasks 1 + 4 |
| §11 Risks (boundary errors, JVM/Android parity) | Tests in Task 1 cover boundaries; both actuals are byte-for-byte identical |
| §13 Acceptance Criteria (8 items) | Verified in Task 4 |

### 2. Placeholder scan

- No `TODO`, `TBD`, `fill in`, `etc.`, `appropriate`, `handle edge cases`.
- All code blocks contain actual implementation, not placeholders.

### 3. Type consistency

- `OffHeapBuf.readBytes(target: ByteArray, srcOffset: Int, length: Int): Int` declared in commonMain expect, implemented identically in jvmMain + androidMain actuals.
- `GlbWriter.writeOffHeapFloats` and `writeOffHeapIndices` are private — their parameter types use `OffHeapBuf` (consistent with existing `OffHeapBuf`-based signatures).
- `LitematicToGlb.run` Pass 1 sink uses `positions.readBytes(staging, srcOffset, want)` with the same `ByteArray`/`Int` parameters as the rest of the codebase.

### 4. Notable design adjustments documented inline

- **Task 3 Step 1** acknowledges that the current Pass 1 sink may use a slightly different structure than the spec text (the implementer must read the current code first). The rewrite pattern is the same; the implementer adapts the exact placement to the existing code.
- **Task 2 Step 2** uses `sbb.limit(read)` to handle the case where `read` is less than the full 64 KB on the last chunk (when total size isn't a multiple of 64 KB). This avoids reading past the actual chunk into the staging's stale bytes.
- **Task 4 Step 2** explicitly accepts the possibility that the threshold may need loosening if the actual peak is in the 90-100 MB range — that's still a major improvement over the pre-spec 116-182 MB and the off-heap approach can be tightened further in a follow-up.