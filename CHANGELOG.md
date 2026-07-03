# Changelog

## 1.0.0 (2026-07-03)

### Breaking changes
- `LitematicReader` → `BlockPrintReader` (now in `io.github.moxisuki.blockprint.core.api`)
- `Litematic` → `BlockPrintDocument` (now in `io.github.moxisuki.blockprint.core.model`)
- `LitematicRegion` → `BlockPrintRegion` (now in `io.github.moxisuki.blockprint.core.model`)
- `LitematicException` → `BlockPrintException` (now in `io.github.moxisuki.blockprint.core.exceptions`)
- `LitematicToGlb` → `BlockPrintToGlb` (now in `io.github.moxisuki.blockprint.core.api`)
- `BlueprintConverter` → `BlockPrintConverter`
- Package layout: `internal/format/` → `format/<formatName>/`; `internal/LitematicParser.kt` split into per-format readers
- `FloorSink.onFloor(...)` returns `Boolean` (was `Unit`). Sinks that consume the buffers (copy or take ownership) must return `true`; drain-only sinks return `false`.
- `MeshBuilder.build()` removed. Use `BlockPrintToGlb.convert(...)` or `buildFloorsInto(...)` instead.
- `FloorAccum.close()` behavior changed: called per-floor inline instead of post-loop batch; sinks returning `true` suppress the close.

### New features
- `BlockPrintReader.peek(...)` returns `BlockPrintSummary` (header-only read, skips block data)
- `PackedAtlas.fallback` — O(1) access to the first atlas entry, used as fallback for faces whose texture isn't a direct key
- `BlockPrintToGlb` is fully wired: `convert(File/OutputStream, ...)` and `convertToBytes(...)` work end-to-end via the single-pass streaming pipeline

### Performance (GLB export hot path)
- **Face-geometry allocation storm eliminated** (PR-1..4). Per-face `List<DoubleArray>(4)` / `List<FloatArray>(4)` / `FloatArray(3)` allocations replaced with a single per-call `FaceScratch` reused across all faces. 16³ solid-fence region: 3 611 ms → 207 ms **(17.4× speedup)**.
- **Model resolution cache wired** (was dead code). `ModelResolver.resolveModel` now hits `modelCache`; `resolveBlockstate` caches parsed JSON roots in `blockstateRootCache`. ~600-1 500 disk-read + JSON-parse ops per region eliminated.
- **Connection-variant cache** (Area 3). Two fence cells with identical neighbour configuration share one `ResolvedModel`. Connection-heavy regions: K unique orientations of model resolution instead of M per-cell calls.
- **IntArray connection-mask** (Area 2). Per-cell `Triple(x, y, z)` allocation + 5× `String.contains` substring scans replaced with flat `IntArray` (4-bit mask per cell) + palette-indexed family table. ~524k `Triple` allocations + ~2.6M substring scans per region eliminated.
- **Atlas fallback O(1)** (Area A). `atlas.mappings.values.firstOrNull()` per-face LinkedHashMap walk replaced with precomputed `PackedAtlas.fallback`.
- **FloorSink ownership pattern** (Area G). Pass 2 per-floor `OffHeapBuf(N) + copyTo(...)` allocations eliminated. Sink takes direct OffHeapBuf references from FloorAccum.
- **NBT parser streaming**: `NbtReader.readRoot(InputStream)` no longer materializes the full byte array
- `PackedBlocks` specialized 4/8-bit unpacking (Litematica hot path)
- `NbtReader.readRootHeader` + `skipPayload` for Peek short-circuit (skips `Regions` / `Schematic` subtrees)

### Benchmarks (median of 5, `test/assets` mode)

| Fixture | Baseline (0.2.x) | v1.0.0 | Speedup |
|---|---|---|---|
| 16³ stone/oak_planks (4 096 cells) | 36 ms | 20 ms | -44% |
| 32³ stone/oak_planks (32 768 cells) | 131 ms | 56 ms | -57% |
| 64³ stone/oak_planks (262 144 cells) | 515 ms | 368 ms | -29% |
| 16³ solid fence (4 096 fence cells) | 3 611 ms | 207 ms | **-94% (17.4×)** |
| user-sample.schem (35×25×30 Sponge) | 159 ms | 65 ms | -59% |

### Bug fixes
- GLB header `bufferView.byteLength` and `accessors.count` no longer under-declared vs actual geometry data (Area B+C regression; fixed by restoring sink-based stats).
- GLB bounding box (`min`/`max`) now in world-space coordinates (was local-space after Area B+C; fixed by passing origin offsets to `countFloorStats`).
- `PackedAtlas.fallback` computed at construction, not per-face via `Map.values` iterator walk.
- `FloorAccum` no longer closes OffHeapBufs taken by an ownership-returning `FloorSink` (would cause `IllegalStateException` on subsequent `writeOffHeapFloats`).
- Legacy `build()` parity test retired (served its purpose over PR-1..4 + Areas 1-3).

### Removed
- `MeshBuilder.build()` legacy entry point and its three `collect*` helpers (~258 lines dead code)
- `MeshBuilderParityTest` (byte-equality lock-in test; invariant now covered by 28 other jvmTest suites)
- Per-cell `IntArray(4)` / `FloatArray(6)` / `BooleanArray(1)` / `(IntArray, FloatArray, Boolean) → Unit` closure allocations from `countFloorElements` (inlined into `countFloorStats`)
- Instance-level legacy helpers (`facePlaneCorners`, `rotateElementPoint`, etc.) — replaced by companion `*Into` overloads
