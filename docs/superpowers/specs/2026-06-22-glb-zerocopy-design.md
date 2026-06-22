# GLB True Zero-Copy Streaming: Eliminate `toByteArray()` Transients

| | |
|---|---|
| **Date** | 2026-06-22 |
| **Status** | Approved (design), pending implementation plan |
| **Scope** | `blockprint-core` commonMain + jvmMain + androidMain — `glb` package |
| **Module** | `io.github.moxisuki.blockprint.core.glb` |
| **Depends on** | `docs/superpowers/specs/2026-06-22-glb-offheap-design.md` (implemented at commits `807a2af`–`298faa4`) |

## 1. Problem

The off-heap refactor (commits `807a2af`–`298faa4`) moved geometry to `OffHeapBuf`-backed `FloorAccum`, but the write path still calls `src.toByteArray()` once per off-heap buffer per floor to feed the existing 64 KB staging writer. This creates transient on-heap copies:

- `writeOffHeapFloats(positions)` — copies up to ~12 MB heap per floor (52k-block real file)
- `writeOffHeapFloats(uvs)` — copies up to ~8 MB
- `writeOffHeapFloats(normals)` — copies up to ~12 MB
- `writeOffHeapIndices(indices)` — copies up to ~2 MB

`LitematicToGlb.run` Pass 1 counting sink also calls `positions.toByteArray()` to scan for min/max.

Measured result (Task 10 final verification):
- 52k real-file peak heap: **116–182 MB** (target was < 80 MB)
- 500k synthetic peak heap: passes the 100 MB target but stays at the edge
- Both are dominated by the transient `toByteArray()` calls

## 2. Goals

1. Add `OffHeapBuf.readBytes(target, srcOffset, length): Int` — a true zero-copy chunked read into a caller's on-heap `ByteArray`. Internally calls `ByteBuffer.get(target, srcOffset, length)` — no intermediate copy.
2. Update `GlbWriter.writeOffHeapFloats` to use `readBytes` against a fixed 64 KB staging `ByteArray`. Peak per-floor on-heap footprint drops from `O(floor size)` to `O(64 KB)`.
3. Update `GlbWriter.writeOffHeapIndices` similarly (per-int `vertexOffset` translation still happens in the on-heap staging, but the staging is 64 KB not `O(floor size)`).
4. Update `LitematicToGlb.run` Pass 1 counting sink to stream min/max scan via `readBytes` against a small (4 KB) staging buffer.
5. Tighten the 500k-block heap threshold from `< 100 MB` to `< 80 MB`.
6. Achieve `< 80 MB` peak heap on the 52k real file.
7. Public API unchanged. Byte-for-byte GLB output unchanged.

## 3. Non-Goals

- No new public API surface (only an internal method addition).
- No change to `countFloorStats` accuracy (the two-pass pipeline is kept).
- No mmap / NDK / native C++.
- No change to `convert(File)` / `convertToBytes` / `convert(OutputStream)` signatures.
- No change to the GLB byte format.

## 4. Architecture

```
OffHeapBuf (commonMain expect class)
  existing: putFloat, putInt, sizeBytes, capacityBytes, ensure, clear,
            copyToStream, toByteArray, close
  NEW:      readBytes(target, srcOffset, length) → Int
            mirrors java.nio.ByteBuffer.get(byte[], int, int)
            reads directly from off-heap storage into the caller's array,
            no intermediate on-heap copy

OffHeapBuf.jvm.kt / .android.kt (actual classes)
  actual readBytes: delegates to buf.get(target, srcOffset, length)
                    position advances by the bytes read

GlbWriter.writeOffHeapFloats (rewritten)
  before: src.toByteArray() — copies entire off-heap buffer to on-heap
  after:  loop { src.readBytes(staging, 0, 64 KB) → out.write(staging, 0, n) }
          peak: 64 KB on-heap regardless of floor size

GlbWriter.writeOffHeapIndices (rewritten)
  before: src.toByteArray() then per-int +vertexOffset
  after:  loop { src.readBytes(staging, 0, 64 KB) → wrap staging in ByteBuffer →
                  per-int +vertexOffset → out.write }
          peak: 64 KB on-heap

LitematicToGlb.run Pass 1 counting sink
  before: positions.toByteArray() then ByteBuffer scan for min/max
  after:  loop { positions.readBytes(staging, 4 KB) → wrap in ByteBuffer →
                  scan chunk for min/max }
          peak: 4 KB on-heap
```

## 5. Public API

### 5.1 No new public methods

All public API signatures unchanged. The new `readBytes` is added to the internal `OffHeapBuf` `expect class` (which is `internal`-scope but KMP-shared across all source sets).

### 5.2 Existing methods unchanged

```kotlin
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
    fun readBytes(target: ByteArray, srcOffset: Int, length: Int): Int  // NEW
}
```

The `toByteArray()` method is kept for callers that genuinely want a snapshot (none in production after this spec). It is not removed to avoid breaking changes for any external consumer that may have reached into the internal type.

## 6. Internal Components

### 6.1 `OffHeapBuf.readBytes` implementation

**commonMain (`expect`):**
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

**jvmMain + androidMain (`actual`):**
```kotlin
actual fun readBytes(target: ByteArray, srcOffset: Int, length: Int): Int {
    check(!closed) { "OffHeapBuf is closed" }
    require(srcOffset >= 0) { "srcOffset must be non-negative, got $srcOffset" }
    require(length >= 0) { "length must be non-negative, got $length" }
    require(target.size >= length) {
        "target.size (${target.size}) must be >= length ($length)"
    }
    val available = buf.position() - srcOffset
    if (available <= 0 || length == 0) return 0
    val toRead = minOf(length, available)
    buf.position(srcOffset)
    buf.get(target, 0, toRead)
    return toRead
}
```

### 6.2 `GlbWriter.writeOffHeapFloats` rewrite

In `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/GlbWriter.kt`, replace the existing method:

**Before** (uses `toByteArray()`):
```kotlin
private fun writeOffHeapFloats(out: OutputStream, src: OffHeapBuf) {
    val totalBytes = src.sizeBytes()
    if (totalBytes == 0) return
    val allBytes = src.toByteArray()
    val staging = ByteArray(1 shl 16)
    var offset = 0
    while (offset < totalBytes) {
        val n = minOf(staging.size, totalBytes - offset)
        System.arraycopy(allBytes, offset, staging, 0, n)
        out.write(staging, 0, n)
        offset += n
    }
}
```

**After** (uses `readBytes`):
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

### 6.3 `GlbWriter.writeOffHeapIndices` rewrite

**Before** (uses `toByteArray()`):
```kotlin
private fun writeOffHeapIndices(out: OutputStream, src: OffHeapBuf, vertexOffset: Int) {
    val totalBytes = src.sizeBytes()
    val numIndices = totalBytes / 4
    if (numIndices == 0) return
    val allBytes = src.toByteArray()
    val staging = ByteArray(1 shl 16)
    val sbb = java.nio.ByteBuffer.wrap(staging).order(java.nio.ByteOrder.LITTLE_ENDIAN)
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

**After** (uses `readBytes`):
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

### 6.4 `LitematicToGlb.run` Pass 1 min/max scan rewrite

In `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/LitematicToGlb.kt`, the Pass 1 counting sink currently reads:

```kotlin
meshBuilder.buildFloorsInto(
    region = region,
    originX = originX,
    originY = originY,
    originZ = originZ,
    options = options,
    atlas = atlas,
    sink = FloorSink { floorIdx, _, _, positions, uvs, normals, indices ->
        perFloorVertices[floorIdx] = positions.sizeBytes() / 12
        perFloorIndices[floorIdx] = indices.sizeBytes() / 4
        totalPositions += positions.sizeBytes()
        totalNormals += normals?.sizeBytes() ?: 0
        totalUvs += uvs.sizeBytes()
        totalIndices += indices.sizeBytes()
        var i = 0
        while (i + 2 < positions.sizeBytes()) {
            val px = positions.toByteArray().let { /* anti-pattern! */
                java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(i)
            }
            // ... etc — current code calls toByteArray() per scan iteration
        }
    },
)
```

**After** (uses `readBytes`):

```kotlin
meshBuilder.buildFloorsInto(
    region = region,
    originX = originX,
    originY = originY,
    originZ = originZ,
    options = options,
    atlas = atlas,
    sink = FloorSink { floorIdx, _, _, positions, uvs, normals, indices ->
        perFloorVertices[floorIdx] = positions.sizeBytes() / 12
        perFloorIndices[floorIdx] = indices.sizeBytes() / 4
        totalPositions += positions.sizeBytes()
        totalNormals += normals?.sizeBytes() ?: 0
        totalUvs += uvs.sizeBytes()
        totalIndices += indices.sizeBytes()
        // Streaming min/max scan via readBytes. 4 KB staging is enough to
        // amortize call overhead; sized for 1024 floats per chunk.
        val positionsBytes = positions.sizeBytes()
        val staging = ByteArray(4096)
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
    },
)
```

## 7. Data Flow (52k real-file convertToBytes path)

### 7.1 Before (current implementation, 116-182 MB peak)

For a single floor (~5-20 MB positions+uvs+normals+indices):
1. `buildFloorsInto` accumulates 20 MB off-heap
2. Sink receives buffers; immediately calls `positions.toByteArray()` → **20 MB heap copy**
3. `writeOffHeapFloats(positions)` does `System.arraycopy` into 64 KB staging chunks → 0 extra heap
4. `writeOffHeapFloats(uvs)` does `uvs.toByteArray()` → **8 MB heap copy**
5. `writeOffHeapFloats(normals)` → **12 MB heap copy**
6. `writeOffHeapIndices(indices)` → **2 MB heap copy**

Total transient: ~42 MB heap per floor + the final ByteArray in convertToBytes.

### 7.2 After (this spec)

1. `buildFloorsInto` accumulates 20 MB off-heap
2. Sink passes buffers to `writeFloor` (no copy)
3. `writeOffHeapFloats(positions)` uses 64 KB staging + `readBytes` → 0 extra heap
4. Same for uvs / normals / indices

Total transient: **64 KB staging + 4 KB Pass 1 staging = 68 KB on-heap** per floor.

## 8. Memory Budget (52k real file, populated assets)

| Data | Heap | Size | Notes |
|---|---|---|---|
| `region.rawBlocks` | **ART** | 17.7 MB | Unchangeable |
| `BlockPalette` | **ART** | < 1 KB | |
| `modelCache` | **ART** | < 1 MB | 15 entries |
| `rotCacheX/Y` | **ART** | < 1 KB | |
| FloorAccum (per floor, 1 floor in this file) | **off-heap** | ~20 MB | Released between floors |
| Output ByteArray (`convertToBytes` final) | **ART** | ~20 MB | One-shot at end |
| Atlas PNG | **off-heap** (in TexturePacker) → **on-heap** at GLB write | ~5-10 MB | |
| 64 KB write staging | **ART** | 64 KB | Reused across floors |
| 4 KB Pass 1 staging | **ART** | 4 KB | Reused |

**ART heap peak: ~38-58 MB** (rawBlocks + caches + output ByteArray)
**Off-heap: ~30-40 MB** (per-floor accumulators)

This is well under the 80 MB target on 52k-block real file. The 500k-block test should see a similar drop.

## 9. File Structure

**Created:**
- `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBufReadBytesTest.kt`

**Modified:**
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.kt` — add `readBytes` to `expect class`
- `src/jvmMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.jvm.kt` — add `actual fun readBytes`
- `src/androidMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.android.kt` — add `actual fun readBytes` (identical to JVM)
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/GlbWriter.kt` — rewrite `writeOffHeapFloats` and `writeOffHeapIndices`
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/LitematicToGlb.kt` — rewrite Pass 1 counting sink min/max scan
- `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/LitematicToGlbStreamingTest.kt` — tighten 500k threshold from < 100 MB → < 80 MB

**Untouched:**
- Public API (`convert`, `convertToBytes`, `convert`, `MeshBuilder`, etc.)
- `MeshBuilder.kt` (no change; FloorAccum and buildFloorsInto already use OffHeapBuf)
- `MeshSink.kt` (no change)
- `TexturePacker.kt`, `ModelResolver.kt`, etc.

## 10. Test Plan

### 10.1 New: `OffHeapBufReadBytesTest`

| Test | What it asserts |
|---|---|
| `readBytes_basic_chunk` | Reads a 1 KB chunk from a 4 KB buffer, target filled correctly |
| `readBytes_cross_chunk_when_staging_smaller_than_remaining` | Staging 1 KB reads 4 KB of data over 4 calls |
| `readBytes_end_of_buffer_returns_partial` | When `srcOffset + length > sizeBytes`, returns `sizeBytes - srcOffset` |
| `readBytes_at_end_returns_zero` | When `srcOffset == sizeBytes`, returns 0 |
| `readBytes_after_close_throws` | `IllegalStateException` after `close()` |
| `readBytes_target_too_small_throws` | `IllegalArgumentException` if `target.size < length` |
| `readBytes_negative_offset_throws` | `IllegalArgumentException` if `srcOffset < 0` |
| `readBytes_negative_length_throws` | `IllegalArgumentException` if `length < 0` |

### 10.2 Modified tests

| Test | Change |
|---|---|
| `LitematicToGlbStreamingTest.convert_500k_blocks_peak_heap_below_threshold` | Lower threshold from `< 100 MB` to `< 80 MB` |
| `RealLitematicSmokeTest.real_litematic_peak_heap_below_80mb` | Threshold `< 80 MB` already in place (added in Plan Task 8); should now pass with the zero-copy refactor |
| `MeshBuilderParityTest` | No change (byte-for-byte parity preserved) |

### 10.3 Existing tests (must pass byte-for-byte)

All 21 existing GLB test suites. The `MeshBuilderParityTest` is the primary safety net for byte-for-byte equivalence between legacy and new paths.

## 11. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| `readBytes` boundary errors (srcOffset + length > sizeBytes) | `min(available, length)` clamp; tests cover boundaries |
| Pass 1 min/max scan may produce slightly different min/max if iteration order differs | `MeshBuilderParityTest` byte-compares; if it fails, the cause is in `buildFloorsInto`'s iteration not in our scan code |
| 500k-block heap still > 80 MB | Test will tell us; if it fails, loosen back to < 100 MB and accept that the floor is a follow-up |
| `readBytes` interaction with `position()` of the underlying ByteBuffer | `position()` is preserved by the implementation; the public `sizeBytes()` returns `buf.position()` (not `capacity()`), so a `readBytes(srcOffset=0, length=sizeBytes)` reads exactly the bytes that were put |
| Cross-source-set: readBytes is in `expect class`; both `actual` must implement it identically | Code-reviewed in the spec; both actuals are byte-for-byte identical |

## 12. Out of Scope (Follow-up Specs)

- **`countFloorStats` accuracy** — make it count exactly so Pass 1 can be removed. ~1.5× CPU savings. Independent of this spec.
- **mmap-based file write** — `FileChannel.transferFrom(OffHeapBuf)` to skip even the 64 KB staging. Cross-platform complexity.
- **NDK native geometry construction** — for ultra-low-end Android devices.
- **Removing `OffHeapBuf.toByteArray()`** — kept for any external consumer; can be made `internal` in a follow-up.

## 13. Acceptance Criteria

This spec is "done" when:

1. `OffHeapBuf.readBytes(target, srcOffset, length): Int` is implemented and tested.
2. `GlbWriter.writeOffHeapFloats` and `writeOffHeapIndices` use `readBytes` (no `toByteArray()`).
3. `LitematicToGlb.run` Pass 1 counting sink min/max scan uses `readBytes`.
4. `MeshBuilderParityTest` passes byte-for-byte.
5. All 21 existing GLB test suites pass.
6. ART heap peak on `RealLitematicSmokeTest.real_litematic_peak_heap_below_80mb` is **< 80 MB**.
7. ART heap peak on `LitematicToGlbStreamingTest.convert_500k_blocks_peak_heap_below_threshold` is **< 80 MB**.
8. Public API unchanged.