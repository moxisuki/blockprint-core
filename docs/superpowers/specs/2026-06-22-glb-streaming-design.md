# GLB Streaming: OOM-Safe Mesh → GLB Pipeline

| | |
|---|---|
| **Date** | 2026-06-22 |
| **Status** | Approved (design), pending implementation plan |
| **Scope** | `blockprint-core` commonMain — `glb` package |
| **Module** | `io.github.moxisuki.blockprint.core.glb` |

## 1. Problem

`LitematicToGlb.convert()` and `convertToBytes()` accumulate all generated vertices in memory before writing the GLB. For blueprints with ≥ 40 k blocks this triggers `OutOfMemoryError` on Android (heap limit ~ 128–256 MB).

The current behavior:
- `MeshBuilder.build(region, ...)` accumulates every floor's positions / uvs / normals / indices into growable `FloatBuf` / `IntBuf` arrays held until the function returns.
- `LitematicToGlb.convert()` then hands the resulting `GlbOutput` to `GlbWriter.write()`.
- `LitematicToGlb.convertToBytes()` writes to a temp file and then calls `tmpFile.readBytes()`, which allocates a second full copy of the output.

Peak memory at 500 k blocks: ~ 320 MB for vertex data alone, plus model caches, atlas, and the temp-file reread — guaranteed OOM on Android.

The README already calls this out (TODO list): "*MeshBuilder 先攒所有顶点再写出，500k 方块模型峰值 >1 GB，Android 必 OOM. 双趟流式可降到 ~100 MB*".

## 2. Goals

1. Run a 500 k-block blueprint through `LitematicToGlb` on a 256 MB Android heap without `OutOfMemoryError`.
2. Produce a GLB byte-for-byte identical to the current implementation for any input (parity test enforces this).
3. Keep all existing public API signatures working unchanged: existing callers get the fix for free.
4. **No new public methods.** The fix is delivered entirely through internal rewriting. Callers do not change their code.
5. Match existing library style: no new dependencies, commonMain, `LitematicException` for errors, `@JvmStatic` / `@JvmOverloads` where applicable.

## 3. Non-Goals

- Reordering or compressing the palette.
- Changing `GlbWriter`'s byte-level encoding logic (already streaming with a 64 KB staging buffer).
- Changing the texture packer or OBJ parser.
- Parallelizing the per-floor build (adds complexity without solving the memory problem).
- Returning a streaming `InputStream` from `convertToBytes` (breaks the `ByteArray` return type contract).
- Changing public API signatures of any existing method.

## 4. Architecture

```
                       LitematicToGlb  (facade, public)
              ┌────────────────────┬─────────────────────┐
              ▼                    ▼                     ▼
  convert(Litematic, File)   convertToBytes(...)    (no other public entry)
              │                    │                     │
              └────────────────────┴─────────────────────┘
                                   │
                                   ▼
                          ┌─────────────────┐
                          │ shared pipeline │  (single implementation)
                          └─────────────────┘
                                   │
                ┌──────────────────┼──────────────────┐
                ▼                  ▼                  ▼
  MeshBuilder.countFloorStats  GlbWriter.writeStreaming  MeshBuilder.buildFloorsInto
           (Pass 1)               (header + BIN)            (Pass 2)
                │                  ▲                          │
                └─► FloorStats ────┘                          │
                                       ◄─────── FloorSink ◄───┘
```

Pass 1 (`countFloorStats`) walks the region once and counts visible vertices / indices per floor — no vertex allocation, only small `IntArray` counters.

The consumer (typically `GlbWriter`) uses the stats to emit the GLB header, JSON chunk, and BIN chunk header.

Pass 2 (`buildFloorsInto`) walks the region a second time, generating each floor's vertex data and pushing it to the `FloorSink` as soon as that floor completes. The sink flushes the data to the output stream and may discard the array before the next floor begins.

## 5. Public API

### 5.1 No new public methods

The existing public API is sufficient. Two public methods remain, both with **unchanged signatures**:

- `convert(litematic, assetsDirs, outputFile: File, regionIndex, options, onProgress)` — `File` overload. Implementation rewritten to delegate to the streaming pipeline internally.
- `convertToBytes(litematic, assetsDirs, regionIndex, imageBackend, onProgress, options): ByteArray` — same signature. Implementation rewritten to use the streaming pipeline into a `ByteArrayOutputStream`, eliminating the previous temp-file reread.

The fix is delivered through implementation changes only — no caller needs to update their code.

**Rationale for not adding a new `OutputStream` overload:**

- The `File` overload is enough for typical use cases. JVM's `FileOutputStream` is itself an `OutputStream`; if a caller needs to stream to network/pipe, they can write to a temp file then copy.
- Adding an overload increases API surface for marginal benefit (the `File` path covers all real destinations on JVM).
- Keeping the API small matches the project's style (only `LitematicReader.read(File)` and `LitematicReader.read(InputStream)` — but for the GLB write side, `File` alone covers everything).

**Caveat documented in KDoc for `convertToBytes`:**

`convertToBytes` still allocates the entire GLB as a `ByteArray`. For very large outputs that won't fit in available heap, callers should use `convert(Litematic, File, ...)` to stream to disk instead. (A `convertToStream: InputStream` would require a return-type change and is out of scope for this spec.)

### 5.2 Internal: `MeshSink.kt` (new file)

```kotlin
package io.github.moxisuki.blockprint.core.glb

/** Pre-computed size counts for a region's floors, used to lay out the GLB BIN chunk. */
internal data class FloorStats(
    val floorCount: Int,
    val perFloorVertices: IntArray,   // size = floorCount
    val perFloorIndices: IntArray,     // size = floorCount
    val totalPositions: Int,
    val totalNormals: Int,
    val totalUvs: Int,
    val totalIndices: Int,
) {
    override fun equals(other: Any?): Boolean { ... }
    override fun hashCode(): Int { ... }
}

/** Consumes one floor's worth of mesh data at a time. */
fun interface FloorSink {
    fun onFloor(
        floorIdx: Int,
        yMin: Int,
        yMax: Int,
        positions: FloatArray,  // size = vertices * 3
        uvs: FloatArray,        // size = vertices * 2
        normals: FloatArray?,   // size = vertices * 3 or null
        indices: IntArray,      // size = triangles * 3
    )
}
```

## 6. Internal Components

### 6.1 `MeshBuilder.countFloorStats(region, options): FloorStats`

Pass 1. Walks the region once. Reuses:
- `modelCache` / `rawMeshCache` / `rotCacheX` / `rotCacheY` — built once per palette entry, identical to the existing palette loop in `build()`.
- `precomputeConnectionProperties(region)` — built once, identical to existing.

The visible-face-culling logic is reimplemented as a lightweight count-only walker. For each cell:
1. Look up the block's model elements from the cache (or skip if absent).
2. For each face that passes the `isFaceOnBoundary` and `sameFloor` culling checks, count 4 vertices and 6 indices.
3. Increment `perFloorVertices[floorIdx]` and `perFloorIndices[floorIdx]`.

**No float arrays, no vertex math.** Only Int counters.

Outputs `FloorStats` with the totals summed across floors.

### 6.2 `MeshBuilder.buildFloorsInto(region, originX, originY, originZ, options, sink, onProgress)`

Pass 2. Same iteration order and same culling rules as the existing `build()`, but:
- A single `FloorAccum` is reused per floor slot. When a floor completes (the iteration crosses into the next floor's Y range), the current `FloorAccum` is converted to arrays via `toFloatArray()` / `toIntArray()` and pushed to the sink.
- The sink receives a strong reference. After the sink method returns, the arrays are eligible for GC. The next floor's `FloorAccum` is reset and reused.
- `connectionProps` is reused from a pass-1 cache, computed once and shared.

Memory budget per floor: ~ 25 MB for a 100 k-block floor (positions + uvs + normals + indices as raw arrays). The total peak is ~ 25 MB + model caches + atlas.

### 6.3 `GlbWriter.writeStreaming(stream, atlas, stats, options, sink: () -> Unit)`

New streaming entry point.

```kotlin
fun writeStreaming(
    stream: OutputStream,
    atlas: GlbAtlas,
    stats: FloorStats,
    options: GlbExportOptions,
    sink: () -> Unit,
)
```

Steps:
1. Wrap `stream` in a `BufferedOutputStream(64 KB)` if not already.
2. Compute layout (per-floor byte offsets, total BIN size, min/max bounding box would normally need pass 1 too — see §6.4).
3. Write GLB header (12 bytes), JSON header chunk header (8 bytes), JSON string, padding.
4. Write BIN chunk header (8 bytes).
5. Invoke `sink()`. The sink is expected to call `FloorSink.onFloor(...)` per non-empty floor. `GlbWriter` writes the per-floor data through the existing 64 KB staging buffer (`writeFloats` / `writeIndices` are reused unchanged).
6. Write the atlas PNG (raw byte stream — already small relative to vertex data).
7. Flush.

### 6.4 Bounding-box (min/max) computation

The current `build()` computes a bounding box over `floors.map { it.positions }` to emit `accessors[0].min` / `.max` in the JSON.

The streaming path doesn't have all positions in memory at once. Two options:

- **Option A (preferred):** Compute min/max during Pass 1 (`countFloorStats`) by accumulating min/max along with the count — zero extra cost.
- **Option B:** Compute min/max during Pass 2 inside `buildFloorsInto`, accumulate it on the `FloorSink` side, and pass it back to `GlbWriter` after the sink returns. Cleaner separation, slight extra cost.

Pick **Option A** — the min/max computation is 3 float compares per visible face, and Pass 1 already iterates the visible-face set. Add `var minX, minY, minZ, maxX, maxY, maxZ` accumulators in `countFloorStats`.

### 6.5 `GlbOutput` & `GlbAtlas`

`GlbOutput` (existing) remains the input shape for the legacy `GlbWriter.write(output, stream)` overload. Internally, that overload is rewritten to call `writeStreaming` after collecting the new streaming output into a `GlbOutput`.

Add a small data carrier `GlbAtlas(pngBytes, width, height)` so `writeStreaming` doesn't have to take three separate parameters.

### 6.6 `LitematicToGlb` rewrite

The two public methods (`convert(File)` and `convertToBytes`) both delegate to one private implementation:

```kotlin
private fun run(
    litematic: Litematic,
    assetsDirs: List<Path>,
    regionIndex: Int,
    options: GlbExportOptions,
    onProgress: ((Float) -> Unit)?,
    outputStream: OutputStream,
)
```

`run` performs:
1. Build `ModelResolver`, `TexturePacker`, `MeshBuilder`.
2. Pre-load the palette caches (modelCache / rawMeshCache / rotCacheX/Y / usedTextures / tintedTextures / specialTints) once. This step is shared between Pass 1 and Pass 2.
3. Call `meshBuilder.countFloorStats(region, options)` → `stats`. (`onProgress = 0.30`).
4. Pack atlas from the cached `usedTextures` / `tintedTextures` / `specialTints` → `atlas`. (`onProgress = 0.35`).
5. Wrap `outputStream` in a `BufferedOutputStream(64 KB)` if it isn't already.
6. Call `glbWriter.writeStreaming(stream, atlas, stats, options, sink = { floorSink ->
        meshBuilder.buildFloorsInto(region, ..., floorSink, onProgress = 0.35..0.95)
   })`. `writeStreaming` invokes `floorSink.onFloor(...)` per non-empty floor; `buildFloorsInto` produces the data.
7. Flush.

The two public methods wrap `run` by opening their own stream:
- `convert(..., File)` — opens `outputFile.outputStream()`, calls `run` inside a `.use` block.
- `convertToBytes(...)` — opens a `ByteArrayOutputStream()`, calls `run`, returns `baos.toByteArray()`.

This eliminates the previous `convertToBytes` temp-file + `readBytes` pattern (which allocated a second full copy of the output).

### 6.7 Two-pass determinism contract

The two passes MUST produce identical visible-face sets, vertex positions, UVs, normals, and indices for any input. The existing code's iteration order is deterministic:

- Palette iteration uses `palette.entries.withIndex()` — a `List`, insertion-ordered.
- Cell iteration: `for y in 0 until h) for z in 0 until d) for x in 0 until w)` — fully ordered.
- `modelCache` is `arrayOfNulls(paletteSize)` indexed by palette id.
- `connectionProps` is a `Map<Triple, Map<dir, Boolean>>` — accessed by `Triple(x, y, z)` lookup, never iterated. Order is irrelevant.

A parity test (see §10) will hard-enforce this for a randomized 1000-block fixture.

## 7. Data Flow

### 7.1 `convert(litematic, outputFile)` — backward-compatible path

1. Caller invokes `LitematicToGlb.convert(litematic, ..., outputFile, ...)`.
2. `run` opens `outputFile.outputStream()`, wrapped in a 64 KB `BufferedOutputStream`.
3. `ModelResolver` + `TexturePacker` initialized (`onProgress = 0.20`).
4. `meshBuilder.countFloorStats(region, options)` → `FloorStats` (`onProgress = 0.25`).
5. `texturePacker.pack(...)` → `atlas` (already done before count, but can move; either order works).
6. `glbWriter.writeStreaming(stream, atlas, stats, options, sink = { meshBuilder.buildFloorsInto(region, ..., floorSink, onProgress = 0.25..0.95) })`.
7. Inside `writeStreaming`: write GLB header → write JSON (knowing totals) → write BIN header → invoke `sink` → for each `onFloor(...)` call from the sink, stream the per-floor data via the existing `writeFloats` / `writeIndices` 64 KB chunks → write atlas PNG → flush.
8. `onProgress = 1.0`.

### 7.2 `convertToBytes(litematic, ...)` — no temp-file reread

1. Allocate a `ByteArrayOutputStream` (size hint = 64 KB).
2. `run` streams into that `ByteArrayOutputStream`.
3. Return `baos.toByteArray()`.

For 500 k blocks, the resulting `ByteArray` is ~ 50 MB. On a 256 MB Android heap this still fits, but **the caller's free heap must accommodate it**. Document this constraint in the KDoc.

For truly gigantic outputs that won't fit even as bytes, callers must use `convert(Litematic, File, ...)` to stream to disk instead (which uses 0 heap for the output, since `FileOutputStream` writes via the kernel page cache).

## 8. Error Handling

Reuse `LitematicException` for domain errors. New error paths:

| Failure | Exception type | Message template |
|---|---|---|
| `FloorStats` empty (no non-air blocks in region) | existing behavior — empty floors list, no error | n/a |
| Pass 2 produces floor count ≠ stats.floorCount | `IllegalStateException` | `"Floor count mismatch: stats=${stats.floorCount} actual=$actual"` |
| Pass 2 emits floor idx ≠ expected | `IllegalStateException` | `"Floor index out of order: expected $expected got $actual"` |
| `connectionProps` empty but modelCache says a connection block exists | existing behavior — `connectionProps` is built once in Pass 1 and reused | n/a |
| Output stream throws | `IOException` propagated as-is | from the underlying stream |

The parity test (§10) catches nondeterminism: if Pass 1 and Pass 2 disagree on which faces are visible, the resulting GLB has indices out of range and the test fails with a clear diagnostic.

## 9. File Structure

**Created:**
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/MeshSink.kt` — `FloorStats` data class + `FloorSink` fun interface

**Modified:**
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/MeshBuilder.kt` — add `countFloorStats()`, `buildFloorsInto()`; rewrite `build()` to delegate to them
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/GlbWriter.kt` — add `writeStreaming()` + `GlbAtlas` data class; rewrite `write(output, stream)` as a wrapper around `writeStreaming()`
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb/LitematicToGlb.kt` — refactor both public methods (`convert(File)`, `convertToBytes`) to delegate to a private `run(OutputStream)` that uses the streaming pipeline. **No new public overload.**
- `docs/GLB_PIPELINE.md` — document the new memory budget and the `convertToBytes` heap caveat
- `docs/GLB_PIPELINE_EN.md` — same in English

**Created tests:**
- `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/MeshBuilderStreamingTest.kt`
- `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/LitematicToGlbStreamingTest.kt`

**Untouched:**
- `TexturePacker`, `ModelResolver`, `RawMesh`, `ImageBackend`, `FileAccessor`, `GlbExportOptions`, all `glb/synthetic/*`
- All existing GLB tests (must pass unchanged, proving byte-for-byte parity)

## 10. Test Plan

### 10.1 New tests (`MeshBuilderStreamingTest`)

| Test | What it asserts |
|---|---|
| `countFloorStats_matches_existing_build_counts` | Run a known small region through both `build()` and `countFloorStats()` + `buildFloorsInto()` (collected back to `GlbOutput`); assert per-floor vertex and index counts match. |
| `buildFloorsInto_byte_for_byte_matches_build` | Random 1000-block fixture. Run `build()` once (collect), run the new two-pass pipeline once (collect), assert `GlbOutput.positions` / `uvs` / `normals` / `indices` / `atlasPng` are byte-equal. |
| `countFloorStats_does_not_allocate_per_cell` | Run on a 10 k-block region; assert peak heap (via `Runtime.totalMemory() - freeMemory()`) before / after `countFloorStats()` differs by < 1 MB. |
| `buildFloorsInto_releases_per_floor_arrays` | Multi-floor region; instrument the sink to call `System.gc()` + record heap after each `onFloor` call; assert heap drops back to baseline between floors. |
| `buildFloorsInto_empty_floors_skipped` | Region with only air; assert sink is never called. |
| `buildFloorsInto_floor_index_monotonic` | `floorIdx` argument increases by exactly 1 per call. |

### 10.2 New tests (`LitematicToGlbStreamingTest`)

| Test | What it asserts |
|---|---|
| `convert_to_File_still_works` | Existing path; assert file is non-empty and starts with the GLB magic `0x46546C67`. |
| `convertToBytes_returns_byteArray` | Existing path; assert non-empty and starts with the GLB magic. |
| `convertToBytes_no_longer_uses_temp_file` | Inspect / verify that `convertToBytes` does not create or read a temp file (regression guard against reintroducing the old pattern). One way: count files in `System.getProperty("java.io.tmpdir")` before and after; another: spy on `File.createTempFile` calls via a small wrapper. |
| `convert_byte_for_byte_identical_to_old_path` | Generate a GLB from a fixed fixture region via `convert(File)`; save the bytes as a golden file in test resources. Subsequent runs compare against the golden. (First run generates and writes the golden; subsequent runs read and compare. Or: check in a known-good golden file generated pre-rewrite as the reference.) |
| `convert_500k_blocks_completes` | Generate a 500 k-block region (e.g. 100 × 100 × 50 solid stone); run `convertToBytes`; assert completes within 60 s on a reference machine. |
| `convert_500k_blocks_peak_heap_below_threshold` | Same 500 k-block fixture; instrument `Runtime.totalMemory() - freeMemory()` around the `convertToBytes` call; assert peak heap < 200 MB. (Today: > 1 GB.) |
| `convert_progress_callback_fires_for_both_passes` | Hook the progress callback; assert it fires at least twice (once during model-cache build / Pass 1, once during Pass 2) and reaches 1.0. |

### 10.3 Existing tests (must pass unchanged)

All existing tests under `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/glb/` — including:

- `GlbOutputTest.kt`, `GlbWriterFloorsTest.kt`, `CreateCompositeFixtureGlbTest.kt`, `DragonHeadGlbGenerationTest.kt`, `GlbExportOptionsTest.kt`, `CreateModObjAdapterTest.kt`, `LitematicToGlbAssetsIntegrationTest.kt`

Their passing after the refactor is the byte-for-byte parity guarantee.

## 11. Memory Budget at 500 k Blocks

| Data | Size | Lifetime |
|---|---|---|
| `region.rawBlocks` | ~ 2 MB | whole call |
| `modelCache` + `rawMeshCache` + `rotCacheX/Y` | 10 – 50 MB | whole call |
| `atlas.pngBytes` | 2 – 16 MB | whole call |
| `FloorStats` (IntArrays) | ~ 32 B × floor count | Pass 1 |
| Single `FloorAccum` (positions / uvs / normals / indices) | ~ 25 MB | per floor in Pass 2; reused & GC'd between floors |
| 64 KB staging buffer | 64 KB | whole call |
| GLB header / JSON | ~ 5 KB | one-shot |

**Peak: ~ 50 – 90 MB** (vs. ~ 320 MB today). Fits comfortably on a 256 MB Android heap with margin for `convertToBytes`'s output `ByteArray` (~ 50 MB).

## 12. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Two-pass nondeterminism produces inconsistent vertex data → corrupted GLB | §10.1 parity test byte-compares the new pipeline against the legacy `build()` for a random 1000-block fixture. |
| Pass 2 still allocates one full floor at a time; on a single-floor 500 k region, peak is still 25 MB+ | Acceptable; this is the irreducible per-floor cost. Document in `GLB_PIPELINE.md`. |
| Existing `convertToBytes` allocates the whole output as `ByteArray`; for outputs > free heap, still OOM | Document the constraint in KDoc; recommend `convert(Litematic, File, ...)` (streams directly to disk, 0 heap for the output) for outputs > 100 MB. |
| `connectionProps` Map is keyed by `Triple<Int, Int, Int>` — boxed allocations | Existing code already incurs this cost. Not introducing new boxed allocations. |
| CPU × 2 (two passes over the region) | README TODO explicitly accepts this trade-off for the OOM fix. Document expected wall-clock impact in `GLB_PIPELINE.md` (~ 2× slower for huge models, ~ 1.1× for typical ≤ 50 k-block models because model-cache build dominates). |
| Atlas is currently packed after `countFloorStats`; need to ensure `tintedTextures` / `specialTints` are populated before atlas | These are populated by the same palette loop that builds `modelCache`. Run that loop ONCE before Pass 1 (it currently runs at the top of `build()`), and use the same cache in both passes. |

## 13. Out of Scope (Follow-up Specs)

- **Streaming `InputStream` return from `convertToBytes`** — would change the return type contract.
- **Two-pass parallelism** — run Pass 1 and Pass 2 on different threads reading from a shared iterator. Memory benefit unclear; risk of cache-line ping-pong.
- **Compressed GLB output** — GLB spec supports `KHR_mesh_compression`; would shrink output 2-5× but is a separate feature.
- **Per-region streaming** — when `litematic.regions.size > 1`, write one GLB per region without re-packing the atlas. Currently not supported by the facade; left for future.