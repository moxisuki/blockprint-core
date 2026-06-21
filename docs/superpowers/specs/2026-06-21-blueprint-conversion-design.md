# Blueprint Format Conversion API

| | |
|---|---|
| **Date** | 2026-06-21 |
| **Status** | Approved (design), pending implementation plan |
| **Scope** | `blockprint-core` commonMain — write path |
| **Module** | `io.github.moxisuki.blockprint.core` |

## 1. Problem

`LitematicReader` can decode four Minecraft blueprint formats (Litematica, Sponge, vanilla Structure, BuildingHelper) into the in-memory `Litematic` model, but there is no corresponding **write** path. Users who want to migrate a `.schematic` to a `.litematic`, or share a Building Helper blueprint in vanilla-Structure form, must hand-translate or use external tools.

This spec adds a symmetric write API: `BlueprintConverter`.

## 2. Goals

1. Convert any of the 4 supported formats to any other, with `Litematic` as the in-memory canonical model.
2. Round-trip fidelity: `read → write → read` of the same format yields an equivalent `Litematic` (same regions, same palette, same block array).
3. Cross-format fidelity: block state identity and dimensions are preserved; only format-specific metadata that `Litematic` does not model is dropped.
4. Match existing library style: zero new dependencies, commonMain, JvmStatic + JvmOverloads, throws `LitematicException`.
5. Multi-region safety: any target format that does not support multiple regions must reject multi-region input explicitly, never silently truncate.

## 3. Non-Goals

- Reordering or compressing the palette to lower `bitsPerBlock`.
- Sponge `TileEntities` / `Entities` (Litematic does not model entities, output lists are empty).
- Sponge Schematic v1 (only v2 is targeted).
- McEdit `.schematic` (pre-Anvil) format.
- Streaming writes for giant multi-region blueprints (existing in-memory model is the bound; this spec does not change it).
- A custom progress callback in v1 (deferred — internal hook point exists but not exposed publicly).

## 4. Architecture

```
                BlueprintConverter  (public facade)
                         │
                         │ dispatch by target SchematicFormat
        ┌────────────────┼────────────────┬────────────────────┐
        ▼                ▼                ▼                    ▼
 LitematicWriter   SpongeWriter   StructureWriter   BuildingHelperWriter
   (NBT, gzip)     (NBT, raw)     (NBT, gzip)          (JSON text)
        └────────────────┴────────────────┴────────────────────┘
                         │
                         ▼
                    NbtWriter    (NbtTag → bytes; mirrors NbtReader)
```

Read path (`LitematicReader` + `NbtReader` + `LitematicParser` + `BuildingHelperParser`) is unchanged.

## 5. Public API

```kotlin
package io.github.moxisuki.blockprint.core

object BlueprintConverter {

    /** Litematic (in memory) → target format bytes. */
    @JvmStatic
    @JvmOverloads
    fun convert(source: Litematic, target: SchematicFormat): ByteArray

    /** Raw bytes → target format bytes. Source format is auto-detected. */
    @JvmStatic
    fun convert(source: ByteArray, target: SchematicFormat): ByteArray

    /** InputStream → target format bytes. */
    @JvmStatic
    fun convert(source: InputStream, target: SchematicFormat): ByteArray

    /**
     * File → File. Source format is inferred from the source file's
     * extension. `outFile` is overwritten. The output extension is
     * informational only — `target` is what the bytes are encoded as.
     *
     * @throws LitematicException if the source extension is not one of
     *   `.litematic`, `.schematic`, `.nbt`, `.json`, or if the source
     *   cannot be detected.
     */
    @JvmStatic
    fun convert(source: File, outFile: File, target: SchematicFormat = inferFromExtension(outFile))
}
```

`SchematicFormat` gains a new helper:

```kotlin
enum class SchematicFormat(...) {
    companion object {
        /** @throws LitematicException if extension is not one of the four supported ones. */
        @JvmStatic
        fun fromExtension(ext: String): SchematicFormat
    }
}
```

`SchematicFormat.displayName` values stay; the new `fromExtension` mirrors the existing `fromNbtRoot` symmetry.

## 6. Internal Components

### 6.1 `NbtWriter` (commonMain, public)

Public class paralleling `NbtReader`. Exposes the byte-level NBT serialization primitive so future code (and tests) can write any `NbtTag` tree.

```kotlin
object NbtWriter {
    fun writeRoot(root: NbtTag.CompoundTag, out: DataOutputStream)
    fun writeRootToBytes(root: NbtTag.CompoundTag): ByteArray
    fun writeRootToGzipBytes(root: NbtTag.CompoundTag): ByteArray   // GZIPOutputStream-wrapped
}
```

Implementation: recursive switch on `NbtTagType`, mirroring `NbtReader.readTagPayload`. `End` is a no-op (never written, terminator implicit).

### 6.2 `internal/format/LitematicWriter`

Builds a `NbtTag.CompoundTag` matching the Litematica spec, then `NbtWriter.writeRootToGzipBytes`.

- Root: `MinecraftDataVersion` (int, default `3465`), `Version` (int, default `6`), `Name`, `Author`, `Description` (strings, empty string if absent), `Regions` (compound).
- Each region: `Position` (compound x/y/z; `(0,0,0)` if absent), `Size` (compound), `BlockStatePalette` (list of compounds), `BlockStates` (long array) — see `LitematicParser.parseRegion` for the exact schema; this writer is the mirror image.
- `BlockStatePalette` invariant: index 0 = `minecraft:air` (already enforced by `Litematic`).
- `BlockStates` packed via existing `BlockStatePacker.pack(...)` (new function — see §9).
- gzip wrapping: yes.

### 6.3 `internal/format/SpongeWriter`

Builds a Sponge Schematic v2 root compound.

- Root fields:
  - `Version` = 2 (int)
  - `DataVersion` (int; passthrough from `Litematic.minecraftDataVersion` or default 3465)
  - `Width`, `Height`, `Length` (int; region dims)
  - `Offset` (int triple compound; region `Position`)
  - `Palette` (compound: maps palette index as stringified int → `BlockState` compound)
  - `PaletteMax` (int; `palette.size`)
  - `BlockData` (byte array; varint-packed palette indices, x→y→z order, **not** y-major)
  - `TileEntities` (list; empty — we cannot recover them)
  - `Entities` (list; empty)
  - `Metadata` (compound):
    - `Name`, `Author`, `Description` (strings)
    - `EnclosingSize` (compound x/y/z; region dims)
- gzip wrapping: **no** (Sponge spec is raw NBT).

### 6.4 `internal/format/StructureWriter`

Builds a vanilla `/structure save` style NBT.

- Root fields:
  - `DataVersion` (int)
  - `size` (int list of 3: `width, height, depth`)
  - `palette` (list of compounds; **does not include air** — index 0 is the first non-air block, matching the read-side convention used by `LitematicParser.parseStructure`)
  - `blocks` (list of compounds; sparse — one entry per non-air cell: `{ pos: [x,y,z], state: int }`; `state = paletteIndex - 1` to skip the air we prepended in memory)
- gzip wrapping: yes (matches the default `/structure save` output).

### 6.5 `internal/format/BuildingHelperWriter`

Outputs the JSON text format, **not NBT**.

- Top-level JSON object with fields:
  - `name` (string; `Litematic.name`)
  - `author` (string; `Litematic.author`)
  - `statePosArrayList` (string, escaped; contains `startpos`, `endpos`, `statelist`, embedded palette)
- The inner format mirrors what `BuildingHelperParser` reads:
  - `startpos`: `X:<x>,Y:<y>,Z:<z>` (from `region.position`)
  - `endpos`: `X:<x+W-1>,Y:<y+H-1>,Z:<z+D-1>`
  - `statelist`: `[I;<v0>,<v1>,...]` where each `v_i` is the region's raw palette index at `(x,y,z)`. Because `Litematic`'s internal palette always has `minecraft:air` at index 0, and BuildingHelper's palette is also written with `air` at index 0, the `statelist` numbers are used as-is. (`statelist.size == width*height*depth`; trailing air-only cells are written explicitly with `0`.)
  - Embedded palette: each entry as `{Name:"<id>",Properties:{k:"v",...}}` inside `statePosArrayList`.

### 6.6 `BlueprintConverter` dispatch

```kotlin
when (target) {
    SchematicFormat.Litematica   -> LitematicWriter.write(source)
    SchematicFormat.Sponge       -> SpongeWriter.write(source)
    SchematicFormat.Structure    -> StructureWriter.write(source)
    SchematicFormat.BuildingHelper -> BuildingHelperWriter.write(source)
    SchematicFormat.PartialNbt, SchematicFormat.Unknown ->
        throw LitematicException("'$target' is a read-side category; cannot be used as a convert target")
}
```

Multi-region check (BEFORE dispatch):

```kotlin
private fun requireSingleRegion(source: Litematic, target: SchematicFormat) {
    if (target != SchematicFormat.Litematica && source.regions.size > 1) {
        throw LitematicException(
            "Format ${target.displayName} does not support multiple regions; " +
            "source has ${source.regions.size}. Pick one with primaryRegion or split first."
        )
    }
}
```

### 6.7 File extension routing

`SchematicFormat.fromExtension` mapping (case-insensitive):

| Extension | Format |
|---|---|
| `.litematic` | `Litematica` |
| `.schematic` | `Sponge` |
| `.nbt` | `Structure` (vanilla `/structure save`) |
| `.json` | `BuildingHelper` |

Ambiguity note: `.nbt` is officially `Structure` per `SchematicFormat.displayName`. This is the same convention the existing code uses (see `BLUEPRINT_API.md` line 38: "`.nbt` — 原版 /structure save"). Users who have raw Litematica NBT renamed to `.nbt` should pass the source `Litematic` directly rather than go through the file-extension overload.

## 7. Data Flow

### 7.1 `Litematic → Sponge` example

1. Caller: `BlueprintConverter.convert(lit, SchematicFormat.Sponge)`
2. `BlueprintConverter`: validates `lit.regions.size == 1` (Sponge target)
3. `SpongeWriter.write(lit)`:
   - extracts `region = lit.regions.single()`
   - iterates `region.rawBlocks` in x→y→z order (not y-major), varint-encodes each palette index → `ByteArray`
   - builds the Sponge root `CompoundTag`
4. `NbtWriter.writeRootToBytes(root)` → returns `ByteArray` (no gzip)
5. Returns bytes to caller.

### 7.2 File→File example

1. Caller: `BlueprintConverter.convert(File("a.schematic"), File("b.litematic"))`
2. `SchematicFormat.fromExtension("schematic")` → `Sponge`
3. `SchematicFormat.fromExtension("litematic")` → `Litematica`
4. Read `a.schematic` via `LitematicReader.read(file)` → `Litematic` (source format = `Sponge`)
5. Validate single region (Sponge is always single-region; pass)
6. `LitematicWriter.write(lit)` → bytes
7. `outFile.writeBytes(bytes)`

## 8. Error Handling

All errors throw `LitematicException` (existing type), with a clear message and a chained cause where applicable.

| Failure | Message template | Cause |
|---|---|---|
| Unknown source extension | `"Cannot infer source format from extension '.$ext'"` | none |
| Unknown target extension | `"Cannot infer target format from extension '.$ext'"` | none |
| Source auto-detect failed | `"Cannot detect source format from bytes (size=$n)"` | none |
| Multi-region to single-region target | `"Format $displayName does not support multiple regions; source has $n. Pick one with primaryRegion or split first."` | none |
| Target is read-only enum | `"$displayName is a read-side category; cannot be used as a convert target"` | none |
| Palette index out of range (defensive) | `"Palette index $i out of range (size=$n)"` | none |
| Varint overflow (Sponge) | `"Sponge block data exceeds 5-byte varint capacity"` | none |

No new exception types.

## 9. New Public Helpers on `BlockStatePacker`

`LitematicWriter` needs the inverse of `BlockStatePacker.unpack`:

```kotlin
object BlockStatePacker {
    /** Pack a dense block-index array back into the Litematica LongArray encoding. */
    fun pack(blocks: IntArray, nbits: Int, width: Int, height: Int, depth: Int): LongArray
}
```

Implementation: the same `y * (W*D) + z * W + x` traversal in reverse — for each linear index, write `nbits` little-endian bits into the output long array at the corresponding bit offset. Existing `BlockStatePacker.validateLength` covers the contract.

SpongeWriter and StructureWriter use their own traversal order and do not call `pack`.

## 10. Test Plan

All tests live in `jvmTest` next to the existing tests.

| Suite | Cases |
|---|---|
| `NbtWriterTest` | 1) write a hand-built `CompoundTag` and assert bytes match a hand-built expected NBT. 2) Round-trip with `NbtReader` on a small Litematica-shaped tree. 3) Gzip output: write then re-decompress and re-parse via `NbtReader`. |
| `LitematicWriterTest` | 1) Write a 1-region Litematic, read back, assert structural equality. 2) Multi-region write → read back → both regions present with original `Position` and blocks. 3) Default `MinecraftDataVersion` is applied when absent. |
| `SpongeWriterTest` | 1) Round-trip a single region. 2) Reject multi-region input. 3) Varint packing of all-zero blocks. 4) Empty `TileEntities` and `Entities` lists. |
| `StructureWriterTest` | 1) Round-trip. 2) Sparse `blocks` list — count equals non-air cell count. 3) Palette drops air. 4) Gzip magic present. |
| `BuildingHelperWriterTest` | 1) Round-trip via `readLenient` (which is the only path that knows about JSON). 2) `statePosArrayList` length matches `width*height*depth`. 3) `startpos` / `endpos` derived from `region.position + size`. |
| `BlueprintConverterTest` | 1) Cross-format matrix — all 12 meaningful ordered pairs (4 source × 3 distinct targets, skipping identity). For each: convert then re-read, assert blocks and palette match. 2) `convert(Litematic, Sponge)` with 2 regions throws. 3) `convert(bytes, Sponge)` with 2-region Litematica bytes throws. 4) `convert(file, file)` writes to disk and round-trips. 5) Unknown source extension throws. 6) `PartialNbt` / `Unknown` as target throws. |

## 11. Files Touched / Created

Created:
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/BlueprintConverter.kt`
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/NbtWriter.kt`
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/format/LitematicWriter.kt`
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/format/SpongeWriter.kt`
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/format/StructureWriter.kt`
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/format/BuildingHelperWriter.kt`
- `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/NbtWriterTest.kt`
- `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/internal/format/LitematicWriterTest.kt`
- `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/internal/format/SpongeWriterTest.kt`
- `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/internal/format/StructureWriterTest.kt`
- `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/internal/format/BuildingHelperWriterTest.kt`
- `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/BlueprintConverterTest.kt`

Modified:
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/SchematicFormat.kt` — add `fromExtension(String): SchematicFormat`
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/BlockStatePacker.kt` — add `pack(...)` function
- `docs/BLUEPRINT_API.md` and `docs/BLUEPRINT_API_EN.md` — document `BlueprintConverter`

Untouched: `LitematicReader`, `LitematicParser`, `BuildingHelperParser`, `NbtReader`, `NbtTag`, `NbtDocument`.

## 12. Open Risks

| Risk | Mitigation |
|---|---|
| Sponge's varint block encoding differs from vanilla Litematica's. Spec ambiguity if reader's Sponge support is later extended. | Pin the spec to Sponge Schematic v2 only; add a comment in `SpongeWriter` citing the format version. |
| BuildingHelper `statePosArrayList` schema is proprietary and undocumented; reader uses regex heuristics. | Writer outputs the simplest valid shape that the existing reader can re-parse. If the user reports a regression, we can extend the reader. |
| `BlockStatePacker.pack` is new code; risk of subtle bit-ordering bug. | Round-trip property test in `LitematicWriterTest` — generate random dense arrays, pack, unpack, assert equality. |
| Multi-region rejection may be too strict for some users (e.g. a 2-region Litematica where the caller is fine losing one). | Document the workaround (`lit.copy(regions = listOf(lit.primaryRegion!!))`) in `BLUEPRINT_API.md`. |

## 13. Out of Scope (Follow-up Specs)

- **Palette optimization** (reorder to minimize `bitsPerBlock` on write).
- **Lossy structure → multi-region Litematica with custom merging policy**.
- **Streaming write API** for very large schematics.
- **Round-trip Sponge `TileEntities` / `Entities`** — would require adding entity modeling to `Litematic`.
