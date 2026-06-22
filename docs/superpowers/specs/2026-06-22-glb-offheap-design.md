# GLB Off-Heap Streaming: Memory-Tight Pipeline for Android

| | |
|---|---|
| **Date** | 2026-06-22 |
| **Status** | Approved (design), pending implementation plan |
| **Scope** | `blockprint-core` commonMain + jvmMain + androidMain — `glb` package |
| **Module** | `io.github.moxisuki.blockprint.core.glb` |
| **Depends on** | `docs/superpowers/specs/2026-06-22-glb-streaming-design.md` (completed) |

## 1. Problem

The current GLB streaming pipeline (Plan completed at commit `721dca8`) puts all generated geometry on the **ART (JVM) heap**. For 500 k-block blueprints on Android:

- **ART heap peak**: ~205-223 MB measured
- Android default per-process heap: 256 MB on most devices, often less on low-end
- The geometry buffers (positions/uvs/normals/indices) and the final output ByteArray dominate the heap usage

Games that exceed 256 MB on Android (Minecraft Bedrock, Genshin, Roblox) do so by **moving data off the ART heap into native (C/C++) memory** via:
1. NDK / native heap — bypasses ART entirely
2. `mmap` of asset files — kernel page cache, no Java heap
3. Direct `ByteBuffer.allocateDirect()` — JVM-side but not ART-GC-managed

This spec adopts approach 3 (Direct ByteBuffer), which is JVM-native, KMP-friendly, and doesn't require NDK. It puts the geometry buffers, the atlas PNG, and the in-flight output buffer **off-ART-heap**, leaving only the unchangeable `region.rawBlocks` (17.7 MB on 4.6 M cells) and the shared model caches on the ART heap.

### 1.1 Measured baseline (current implementation, 52 k-block real file, JVM heap)

From the smoke test (`RealLitematicSmokeTest`, region `中世纪生存小屋`, 205x155x146 = 4,639,150 cells, 52,519 solid blocks):

| Metric | Value |
|---|---|
| Output GLB size | 20.6 MB |
| `convertToBytes` peak heap (sampled) | 205 MB |
| `convert(File)` peak heap (sampled) | 223 MB |
| `convertToBytes` heap after | 75 MB |
| `convert(File)` heap after | 152 MB |

### 1.2 Target

For the same 52 k-block real file on Android:
- ART heap peak: **< 80 MB** (down from 205-223 MB)
- `convertToBytes` final ByteArray (one-shot on-heap copy): unavoidable, ~20 MB
- Total process RSS: ~150-200 MB (off-heap is still RAM but not subject to ART OOM)

For 500 k-block synthetic: ART heap peak < 100 MB.

## 2. Goals

1. ART heap peak < 80 MB on a real 52 k-block blueprint with populated assets (Android target).
2. Byte-for-byte equivalent GLB output to the current implementation.
3. Public API unchanged: `convert(File)`, `convertToBytes()`, `convert(OutputStream)` keep their signatures.
4. KMP-compatible: works on jvm + android; non-JVM platforms (if added later) get an on-heap fallback via `expect`/`actual`.

## 3. Non-Goals

- Reducing `region.rawBlocks` heap usage (17.7 MB on 4.6 M cells). This requires changing `LitematicRegion.rawBlocks: IntArray`, which is a public API break. Out of scope.
- NDK / native C++ code. The library stays in Kotlin Multiplatform.
- `mmap` / `FileChannel.transferFrom` integration. Direct buffer + 64 KB streaming chunk is sufficient.
- Texture streaming. Atlas is still packed once before Pass 1.
- Changing the byte-level GLB format.
- CPU optimization. Off-heap allocation is slower than on-heap on some JVMs (especially ART); we accept the regression in exchange for memory.

## 4. Architecture

```
                LitematicToGlb (facade, public)
                  │
                  ▼
        ┌─────────────────────┐
        │ private run(...)    │  (orchestrator)
        └──────────┬──────────┘
                   │
   ┌───────────────┼─────────────────┐
   ▼               ▼                 ▼
  atlas           GLB header       geometry streaming
  (off-heap)      (off-heap buf)   (off-heap per-floor)
   │                                │
   │                                ▼
   │                GlbWriter.writeFloor(stream, OffHeapBuf*)
   │                                │
   │                                ▼
   │                            OutputStream (File / BAOS / etc.)
   ▼
  (PackedAtlas.pngBytes — wrapped in OffHeapBuf → flush to stream)
```

Key changes vs. current pipeline:

1. **`FloatBuf` / `IntBuf` are replaced with `OffHeapBuf`** (commonMain `expect` class; jvmMain + androidMain `actual` via `java.nio.ByteBuffer.allocateDirect`).
2. **`FloorAccum` holds `OffHeapBuf` instances** instead of growable on-heap FloatArrays.
3. **`FloorSink.onFloor` signature changes** to accept `OffHeapBuf` instances.
4. **`GlbWriter.writeFloor` reads from `OffHeapBuf`** and writes through the existing 64 KB staging buffer.
5. **`LitematicToGlb.run` builds the GLB into an off-heap buffer**, then either `flushTo(stream)` (File path, zero on-heap copy of output) or `toByteArray()` (convertToBytes path, one final on-heap copy).

## 5. Public API

### 5.1 No breaking changes

```kotlin
object LitematicToGlb {
    fun convert(litematic, assetsDirs, outputFile, regionIndex, options, onProgress)   // unchanged
    fun convert(litematic, assetsDirs, outputStream, regionIndex, options, onProgress)   // unchanged
    fun convertToBytes(litematic, assetsDirs, regionIndex, imageBackend, onProgress, options): ByteArray  // unchanged
}
```

`convertToBytes` continues to return `ByteArray`. Internally it now allocates off-heap during conversion and copies to `ByteArray` at the very end (one allocation of ~20-50 MB depending on output size). KDoc explicitly notes this.

### 5.2 No new public methods

Adding `convertToByteBuffer` or `convertToFileChannel` is considered — they would let Android callers write directly to an mmap'd file with zero on-heap copy of the GLB body. Deferred as a follow-up; this spec keeps scope tight.

## 6. Internal Components

### 6.1 `OffHeapBuf` (KMP `expect` class)

**File:** `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.kt`

```kotlin
/**
 * Growable primitive float/int buffer backed by off-heap (Direct ByteBuffer on JVM/Android).
 *
 * On non-JVM platforms (iOS, JS, Wasm), the actual implementation falls back to
 * on-heap `FloatArray` / `IntArray` — off-heap is only an optimization, not a
 * correctness requirement.
 *
 * Memory: capacity grows by doubling; old buffer is GC'd (JVM) or `free()`d
 * (Native) when replaced. The buffer is **explicitly closeable** to release
 * native memory immediately rather than waiting for GC.
 */
expect class OffHeapBuf(initialCapacityBytes: Int = 1024) {
    fun putFloat(v: Float)
    fun putInt(v: Int)
    fun sizeBytes(): Int
    fun capacityBytes(): Int
    /** Grow if needed to hold [extraBytes] more. */
    fun ensure(extraBytes: Int)
    /** Reset position to 0; keep capacity. */
    fun clear()
    /** Copy all bytes to [out] in [chunkSize]-byte chunks. Does not flush. */
    fun copyToStream(out: OutputStream, chunkSize: Int = 65536)
    /** Snapshot to a new ByteArray. Caller owns the returned array. */
    fun toByteArray(): ByteArray
    /** Release native memory immediately. Idempotent. After close(), all other methods throw. */
    fun close()
}
```

### 6.2 `OffHeapBuf.jvm.kt` and `OffHeapBuf.android.kt` (actuals)

**Files:**
- `src/jvmMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.jvm.kt`
- `src/androidMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.android.kt`

Both actuals are identical (Android uses the same `java.nio.ByteBuffer` API as JVM):

```kotlin
actual class OffHeapBuf actual constructor(initialCapacityBytes: Int) {
    private var buf: java.nio.ByteBuffer = java.nio.ByteBuffer.allocateDirect(initialCapacityBytes)
    private var closed = false

    init {
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
        val need = buf.position() + extraBytes
        if (need <= buf.capacity()) return
        var newCap = buf.capacity()
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
        val total = buf.position()
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
        buf.flip()
        buf.get(out)
        return out
    }

    actual fun close() {
        if (!closed) {
            // Direct ByteBuffers are tracked by a Cleaner; setting to null lets GC
            // reclaim native memory. For more aggressive cleanup we could use
            // ((sun.nio.ch.DirectBuffer) buf).cleaner().clean() but that requires
            // sun.* APIs. Leave the GC to do its job.
            buf = java.nio.ByteBuffer.allocateDirect(0).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            closed = true
        }
    }
}
```

### 6.3 `FloorAccum` rewrite

The current `FloorAccum` uses `FloatBuf` / `IntBuf`. After this spec:

```kotlin
internal class FloorAccum(initialCapacityFloats: Int = 1024, initialCapacityInts: Int = 1024) {
    val positions = OffHeapBuf(initialCapacityFloats * 4)
    val uvs = OffHeapBuf(initialCapacityFloats * 8 / 3 * 4)   // 2/3 of position floats (uvs have 2 per vertex, positions have 3)
    val normals = OffHeapBuf(initialCapacityFloats * 4)
    val indices = OffHeapBuf(initialCapacityInts * 4)
    val vertexCount: Int get() = positions.sizeBytes() / 12  // 12 bytes per float (3 floats per vertex)

    fun appendQuad(verts: FloatArray, uvs: FloatArray, normal: FloatArray) {
        // ... unchanged, just calls positions.putFloat(v) etc.
    }

    fun toFloats(): Pair<FloatArray, FloatArray> { /* for parity test only */ }
    fun toIndices(): IntArray { /* for parity test only */ }

    fun reset() {
        positions.clear()
        uvs.clear()
        normals.clear()
        indices.clear()
    }

    fun close() {
        positions.close()
        uvs.close()
        normals.close()
        indices.close()
    }
}
```

For the parity test (`MeshBuilderParityTest`), we need to extract FloatArrays back from the OffHeapBuf. Add `toFloats()` / `toIndices()` helpers that materialize the data for comparison. In production code (LitematicToGlb.run), these are NOT called — the OffHeapBuf is consumed by `GlbWriter.writeFloor` directly, then closed.

### 6.4 `FloorSink.onFloor` signature change

```kotlin
fun interface FloorSink {
    fun onFloor(
        floorIdx: Int,
        yMin: Int,
        yMax: Int,
        positions: OffHeapBuf,   // was: FloatArray
        uvs: OffHeapBuf,         // was: FloatArray
        normals: OffHeapBuf?,    // was: FloatArray?
        indices: OffHeapBuf,      // was: IntArray
    )
}
```

The `FloorSink.onFloor` callbacks receive **borrowed** OffHeapBuf references. They MUST NOT retain the references after the call returns. (The producer — `buildFloorsInto` — reuses the underlying buffers for the next floor.)

### 6.5 `GlbWriter.writeFloor` reads from OffHeapBuf

```kotlin
fun writeFloor(
    stream: OutputStream,
    floorIdx: Int, yMin: Int, yMax: Int,
    positions: OffHeapBuf,
    uvs: OffHeapBuf,
    normals: OffHeapBuf?,
    indices: OffHeapBuf,
    vertexOffset: Int,
) {
    // ...
    // Reuse the existing writeFloats/writeIndices staging-buffer helpers, but read
    // from the OffHeapBuf via a fixed-size staging array. The staging buffer is
    // on-heap but bounded (64 KB), so its heap contribution is negligible.
    writeOffHeapFloats(out, positions, staging, sbb)
    normals?.let { writeOffHeapFloats(out, it, staging, sbb) }
    writeOffHeapFloats(out, uvs, staging, sbb)
    writeOffHeapIndices(out, indices, staging, sbb, vertexOffset)
    out.flush()
}

private fun writeOffHeapFloats(out: OutputStream, src: OffHeapBuf, staging: ByteArray, sbb: ByteBuffer) {
    // Read from src (off-heap) into staging (64 KB on-heap), write to out.
    val totalFloats = src.sizeBytes() / 4
    val cap = staging.size / 4
    var offset = 0
    while (offset < totalFloats) {
        val chunk = minOf(cap, totalFloats - offset)
        // Reset and read `chunk` floats from the off-heap buf into staging.
        sbb.clear()
        // (Implementation note: ByteBuffer.duplicate() and bulk get into staging)
        // ...
        out.write(staging, 0, chunk * 4)
        offset += chunk
    }
}
```

The 64 KB on-heap staging buffer is the **only** on-heap allocation in the geometry path. Total heap from staging: 64 KB.

### 6.6 `LitematicToGlb.run` orchestration

The current `run` does two `buildFloorsInto` passes (one counting, one writing). With off-heap, the cost of those two passes is much smaller (the off-heap geometry is released as soon as `FloorSink.onFloor` returns). But we still want to eliminate the redundancy.

This spec adopts the same two-pass strategy as the current pipeline (counting via `buildFloorsInto` + counting sink → write via `buildFloorsInto` + writing sink) because **the counting pass must agree with the writing pass byte-for-byte**, and `countFloorStats` alone is not accurate (atlas-lookup-miss drops faces, model rotations change geoDir, etc.). 

The two-pass approach remains, but each pass's peak **off-heap** geometry is small (one floor at a time, immediately flushed to the counting sink which just reads `.sizeBytes()` and discards). The peak **ART heap** during the two passes is dominated by:
- `region.rawBlocks`: 17.7 MB (on-heap, unchangeable)
- modelCache + rotCacheX/Y: 10-50 MB (on-heap, shared, small per-block)
- 64 KB staging buffer × 2: 128 KB
- per-cell closures in countFloorStats: small, short-lived
- output ByteArray: only in convertToBytes path, ~20 MB one-shot

**ART heap peak: ~30-70 MB** on a real 52 k-block file.

### 6.7 `convert(File)` / `convert(OutputStream)` path

The output goes into an `OffHeapBuf`. At the end of Pass 2, `run` calls:

```kotlin
outputBuffer.copyToStream(outputStream)
outputBuffer.close()
```

Zero on-heap copy. The OS page cache absorbs the data; for FileOutputStream, the bytes never touch ART heap.

### 6.8 `convertToBytes` path

Same flow, but at the end:

```kotlin
val bytes = outputBuffer.toByteArray()  // ~20 MB one-shot on-heap allocation
outputBuffer.close()
return bytes
```

This is unavoidable — the caller wants a `ByteArray`. KDoc notes the constraint.

## 7. Data Flow

### 7.1 `convert(litematic, outputFile)` — Android-friendly path

1. Caller invokes `LitematicToGlb.convert(litematic, ..., outputFile, ...)`.
2. `run` opens `outputFile.outputStream()` (which becomes a FileChannel under the hood), wraps in `BufferedOutputStream(64 KB)`.
3. `run` builds the off-heap output `OffHeapBuf` (initial size: `estimateOutputSize(region) = w*h*d*32 bytes`).
4. `run` does Pass 1 (counting via `buildFloorsInto` + counting sink that reads `sizeBytes()` only).
5. `run` writes the GLB header to the off-heap output buf.
6. `run` does Pass 2 (writing via `buildFloorsInto` + writing sink that streams from off-heap per-floor bufs).
7. `run` writes atlas PNG to off-heap output buf.
8. `run` calls `outputBuf.copyToStream(fileStream)`, then closes the buf.
9. `run` flushes and closes the file stream via `.use`.

ART heap: ~30-70 MB. Off-heap: ~100-150 MB (geometry + output + atlas).

### 7.2 `convert(litematic, outputStream)` — same as §7.1 but the stream comes from the caller.

### 7.3 `convertToBytes(...)` — final on-heap copy

Same as §7.1 through step 7. Then:

8. `run` calls `outputBuf.toByteArray()` (one ~20-50 MB on-heap allocation).
9. `run` closes the off-heap buf.

ART heap: ~30-70 MB during conversion, +20-50 MB at the very end for the ByteArray return value.

For 500 k-block synthetic model: ART heap peak < 100 MB.

## 8. Error Handling

| Failure | Exception | Message template |
|---|---|---|
| OffHeapBuf grow fails (Direct buffer OOM) | `OutOfMemoryError` (thrown by `ByteBuffer.allocateDirect`) | from JVM/ART |
| `OffHeapBuf.close()` then any other method | `IllegalStateException` | `"OffHeapBuf is closed"` |
| `convert(File)` write fails | `IOException` | from `FileOutputStream` / `copyToStream` |
| `region.indexOutOfBounds` in Palette | `IllegalArgumentException` | from existing `LitematicParser` |

No new exception types.

## 9. File Structure

**Created:**
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.kt` (`expect class`)
- `src/jvmMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.jvm.kt` (`actual class`)
- `src/androidMain/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBuf.android.kt` (`actual class`, identical to JVM)
- `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/OffHeapBufTest.kt`
- `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/MeshBuilderOffHeapTest.kt` (extends ParityTest for off-heap path)

**Modified:**
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/MeshSink.kt` — `FloorSink.onFloor` signature uses `OffHeapBuf`
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/MeshBuilder.kt` — `FloorAccum` uses `OffHeapBuf`, removes `FloatBuf`/`IntBuf`
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/GlbWriter.kt` — `writeFloor` accepts `OffHeapBuf`, adds `writeOffHeapFloats` / `writeOffHeapIndices` helpers
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/LitematicToGlb.kt` — `run` uses `OffHeapBuf` for output, builds geometry off-heap
- `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/MeshBuilderBuildFloorsIntoTest.kt` — sink signature updates
- `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/MeshBuilderParityTest.kt` — uses new signature; off-heap version compared byte-for-byte against the on-heap path (still in test only)
- `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/LitematicToGlbStreamingTest.kt` — `convert_500k_blocks_peak_heap_below_threshold` threshold tightened from 200 MB → 100 MB (and ideally verify via a 50 k-block real-file test that ART heap < 80 MB)
- `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/RealLitematicSmokeTest.kt` — peak heap assertions tightened to < 80 MB

**Untouched:**
- `LitematicReader`, `LitematicParser`, `LitematicRegion.rawBlocks` (API constraint)
- `ModelResolver`, `TexturePacker`, `RawMesh`, `ImageBackend`, `FileAccessor`, `GlbExportOptions`
- All `glb/synthetic/*`
- All non-JVM source sets (the `expect`/`actual` design lets us add iOS / JS later without code changes here)
- Documentation files (the previous spec's memory budget table becomes inaccurate; update in a follow-up)

## 10. Test Plan

### 10.1 New tests (`OffHeapBufTest`)

| Test | What it asserts |
|---|---|
| `putFloat_then_toByteArray_round_trips` | Single float survives the off-heap round trip |
| `putInt_then_toByteArray_round_trips` | Single int survives |
| `ensure_grows_capacity_exponentially` | Adding more bytes than capacity triggers a 2× grow |
| `clear_resets_position_keeps_capacity` | `clear()` does not free the buffer |
| `close_then_any_method_throws` | IllegalStateException after close |
| `copyToStream_writes_all_bytes_in_chunks` | Stream receives every byte in order |
| `direct_buffer_is_off_heap` | Reflection check that the underlying `ByteBuffer.isDirect()` is true |

### 10.2 New tests (`MeshBuilderOffHeapTest`)

| Test | What it asserts |
|---|---|
| `parity_against_on_heap_path_byte_for_byte` | The new off-heap `build()` produces identical positions/uvs/normals/indices to the previous on-heap version |
| `floorAccum_close_releases_native_memory` | After `FloorAccum.close()`, a forced `System.gc()` reduces native memory usage |

### 10.3 Modified tests

| Test | Change |
|---|---|
| `MeshBuilderBuildFloorsIntoTest` | Update `FloorSink` implementations to consume `OffHeapBuf` instead of `FloatArray`/`IntArray` |
| `MeshBuilderParityTest` | Same; the new path is off-heap but the byte content matches the legacy path |
| `LitematicToGlbStreamingTest.convert_500k_blocks_peak_heap_below_threshold` | Lower threshold from 200 MB → 100 MB |
| `RealLitematicSmokeTest.read_and_convert_smoke_test` | Add a peak-heap assertion: `< 80 MB` for the 52 k-block real file |
| `RealLitematicSmokeTest.memory_breakdown_estimation` | Update estimates to reflect off-heap placement |

### 10.4 Existing tests (must pass unchanged)

All other GLB tests (`GlbOutputTest`, `GlbWriterFloorsTest`, `CreateCompositeFixtureGlbTest`, `DragonHeadGlbGenerationTest`, etc.) must pass byte-for-byte. They prove the GLB output is identical to the previous implementation.

## 11. Memory Budget at 52 k Blocks (Real File, Populated Assets)

| Data | Heap | Size | Notes |
|---|---|---|---|
| `region.rawBlocks` | **ART** | 17.7 MB | Unchangeable (public API) |
| `BlockPalette` | **ART** | < 1 KB | Small |
| `modelCache` (15 entries) | **ART** | < 1 MB | Element list per block |
| `rotCacheX/Y` (15 entries each) | **ART** | < 1 KB | Small IntArrays |
| `FloorAccum` (during Pass 1 / Pass 2) | **off-heap** | ~20 MB | Released between floors via `reset()` |
| Output GLB buffer | **off-heap** | ~20 MB | For File/Stream: zero on-heap copy. For `convertToBytes`: one final `toByteArray()` of 20 MB on-heap |
| Atlas PNG | **off-heap** | ~5-10 MB | Decoded ImageData stays off-heap |
| 64 KB staging buffer × 2 | **ART** | 128 KB | Streaming chunk |

**ART heap peak during conversion: ~18-22 MB** (just `region.rawBlocks` + caches)

For `convertToBytes`: final +20 MB one-shot for the return ByteArray. Total ART heap at end of call: ~40 MB. After `convertToBytes` returns and the caller drops the reference: GC reclaims it; ART heap drops back to ~18-22 MB.

## 12. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| `ByteBuffer.allocateDirect` fails on some Android devices (rare) | `OutOfMemoryError` propagates; caller can catch and fall back to a File path. KDoc notes this. |
| Cleaner / native memory fragmentation | Doubling growth + explicit `close()` in `FloorAccum` and `LitematicToGlb.run` keep allocation count low. |
| Byte-for-byte inconsistency between on-heap and off-heap paths | Parity test (`MeshBuilderOffHeapTest.parity_against_on_heap_path_byte_for_byte`) compares bytes. |
| Off-heap allocation slower than on-heap on ART (10-100x in some benchmarks) | Accepted per §3 Non-Goals. Android memory is the constraint. |
| KMP compilation fails (no actual for some future target) | `expect class` is forward-compatible; non-JVM targets can add an `actual class` later. |
| `convertToBytes` final ByteArray copy defeats the off-heap win for callers who hold the result | Documented in KDoc; Android callers should use `convert(File)` to avoid the on-heap copy. |
| `OffHeapBuf` accidentally used after `close()` | All methods check `closed` and throw `IllegalStateException`. |

## 13. Out of Scope (Follow-up Specs)

- **`convertToByteBuffer` / `convertToFileChannel`** — let callers obtain the off-heap output without going through `convertToBytes`'s on-heap copy. Required for Android streaming to network sockets / mmap'd files.
- **NDK / `mmap` integration** — bypass JVM entirely for the geometry path. Significantly more work, only worthwhile if off-heap Direct buffers prove insufficient on the lowest-end Android devices.
- **Atlas streaming** — pack textures on-demand during geometry pass to avoid holding the full decoded atlas at once.
- **`region.rawBlocks` off-heap** — would require changing `LitematicRegion.rawBlocks: IntArray` to `ByteBuffer` (public API break). Deferred.

## 14. Acceptance Criteria

This spec is "done" when:

1. All existing GLB tests pass byte-for-byte against the new implementation.
2. `OffHeapBuf` is implemented and tested on JVM and Android.
3. `convertToBytes` and `convert(File)` produce identical GLB bytes for the same input.
4. ART heap peak on `RealLitematicSmokeTest.read_and_convert_smoke_test` (52 k-block real file, populated assets) is **< 80 MB**.
5. ART heap peak on `LitematicToGlbStreamingTest.convert_500k_blocks_peak_heap_below_threshold` is **< 100 MB**.
6. `convertToBytes` final return value is still a `ByteArray` with identical content to the previous implementation.
7. No public API signatures changed.
8. KMP build still succeeds for jvm + android.