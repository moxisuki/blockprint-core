# Blueprint Parsing API

Complete public API reference for BlockPrint Core.

> [中文版本](BLUEPRINT_API.md)

## Entry Point: LitematicReader

```kotlin
object LitematicReader {
    fun read(file: File): Litematic                 // from file
    fun read(inputStream: InputStream): Litematic   // from stream (auto-closed)
    fun read(bytes: ByteArray): Litematic           // from bytes (auto-detects gzip / JSON / NBT structure)

    fun readLenient(file: File): Litematic          // lenient (no exception on partial)
    fun readLenient(inputStream: InputStream): Litematic
    fun readLenient(bytes: ByteArray): Litematic

    fun detectFormat(file: File): SchematicFormat   // preflight check, never throws
}
```

`read(bytes)` and `readLenient(bytes)` identify the format by **content sniffing** (the extension is not consulted):

1. Leading `{` (0x7B) → BuildingHelper JSON
2. Leading `0x1F 0x8B` → gzip-wrapped NBT (auto-decompressed)
3. Otherwise → NBT root structure, distinguished into Litematica / Sponge v2 / Sponge v3 / vanilla Structure / PartialNbt / Unknown

### Strict vs Lenient

| Mode | Method | Missing Regions | Use Case |
|------|--------|:--:|------|
| Strict | `read()` | throws `LitematicException` | complete, well-formed files |
| Lenient | `readLenient()` | fills an all-air placeholder Region | partial / debug / non-standard |

The placeholder Region is sized (in order) from: Litematica `Size` compound → Litematica `size` list → Sponge v2 `Metadata/EnclosingSize` → Sponge v3 `Width+Height+Length` (Shorts). Its palette contains only `minecraft:air`.

## Entry point: BlueprintConverter

Convert between the four supported formats via the in-memory `Litematic` model.

```kotlin
object BlueprintConverter {
    fun convert(source: Litematic, target: SchematicFormat): ByteArray
    fun convert(source: ByteArray, target: SchematicFormat): ByteArray
    fun convert(source: InputStream, target: SchematicFormat): ByteArray
    fun convert(source: Litematic, target: SchematicFormat, out: OutputStream)   // streaming: no output byte[] on the Java heap
    fun convert(source: File, outFile: File, target: SchematicFormat = ...)
}
```

For large outputs (≥ ~50 MB) prefer the `OutputStream` overload: the writer doesn't materialise the full NBT tree, and the Litematica target also skips the 5 MB+ packed `LongArray` intermediate. The caller owns the lifecycle of `out`.

### Conversion matrix

| Source \ Target | Litematica | Sponge | Structure | BuildingHelper |
|---|:---:|:---:|:---:|:---:|
| **Litematica**     | ✓ | ✓ | ✓ | ✓ |
| **Sponge**         | ✓ | ✓ | ✓ | ✓ |
| **Structure**      | ✓ | ✓ | ✓ | ✓ |
| **BuildingHelper** | ✓ | ✓ | ✓ | ✓ |

### Multi-region restriction

Every target format except `Litematica` rejects multi-region input with `LitematicException`. Workaround: `lit.copy(regions = listOf(lit.primaryRegion!!))`.

### File-level convenience

```kotlin
// Inferred by extension: in.litematic → out.schem (.schematic also accepted)
BlueprintConverter.convert(File("in.litematic"), File("out.schem"))
```

Unknown extensions throw `LitematicException`.

### Supported Format Enum

```kotlin
enum class SchematicFormat {
    Litematica,     // .litematic — standard Litematica file
    Sponge,         // .schematic / .schem — WorldEdit Schematic (writer outputs v3; reader accepts v2 + v3)
    Structure,      // .nbt — vanilla / generic NBT (`/structure save` or similar palette+blocks layout)
    BuildingHelper, // .json — 建筑小帮手 (Building Helper) — identified by content sniff (leading `{` + `statePosArrayList` field), NOT by extension
    PartialNbt,     // generic NBT — has Size but no Regions
    Unknown,        // unrecognised
}
```

> `SchematicFormat.fromExtension` is only used by **file-level** APIs (e.g. `convert(File, File)` target inference). `detectFormat` / `read` / `readLenient` all use **content sniffing** and never look at the extension. BuildingHelper is recognised even if the file is renamed to `.bin`.

## Litematic

Top-level document model.

```kotlin
data class Litematic(
    val name: String,                   // blueprint name
    val author: String,                 // author
    val description: String,            // description
    val version: Int?,                  // file format version (5 or 6 for Litematica; 2 or 3 for Sponge)
    val minecraftDataVersion: Int?,     // MC data version, e.g. 3953 = 1.21
    val regions: List<LitematicRegion>, // regions (NBT insertion order)
    val format: SchematicFormat,        // source format
) {
    val primaryRegion: LitematicRegion?  // = regions.firstOrNull()
    fun blockCount(includeAir: Boolean = false): Int
}
```

### Sponge Schematic v2 vs v3 schema

The writer only emits v3; the reader accepts both specs. `Litematic.version` preserves the original `Version` (v2 → 2, v3 → 3).

| Field | v2 (Sponge / older WorldEdit) | v3 (WorldEdit 7.3+) |
|---|---|---|
| Root name | `""` | `""` but root has a `Schematic` child compound |
| `Version` | int = 2 | int = 3 (inside `Schematic`) |
| `Width` / `Height` / `Length` | int | **short** (note: "Length", not "Depth") |
| `Offset` | compound `{x, y, z}` | **int[3]** |
| `Palette` | compound `{intString: BlockState}` | compound `{blockStateName: IntTag(paletteId)}` |
| `BlockData` / `Data` | byte[] of varints at root | `Blocks.Data` byte[] of varints |
| `BlockEntities` | root-level `TileEntities` list | `Blocks.BlockEntities` list |
| `Metadata/EnclosingSize` | compound `{x, y, z}` | (**dropped** — dims come from `Width/Height/Length`) |
| `Metadata/WorldEdit` | (absent) | compound `{Version, EditingPlatform, Origin, Platforms}` |
| Wrapping | direct at root | `"Schematic": { ... }` child compound |

## LitematicRegion

A single region, holding dense block data.

```kotlin
class LitematicRegion(
    val name: String,           // region name
    val width: Int,             // X size
    val height: Int,            // Y size (height)
    val depth: Int,             // Z size
    val position: Position,     // origin in world coordinates
    val palette: BlockPalette,  // block state palette
    blocks: IntArray? = null,   // dense block array (palette indices, 0 = air)
)
```

### Block Access

```kotlin
// Raw palette index by coordinate
fun getBlock(x: Int, y: Int, z: Int): Int

// Resolved BlockState by coordinate
fun blockAt(x: Int, y: Int, z: Int): BlockState

// Air check
fun isAir(x: Int, y: Int, z: Int): Boolean

// Dense array (y-major: index = y * W * D + z * W + x)
val rawBlocks: IntArray

// 3D coordinate → 1D index
fun rawIndex(x: Int, y: Int, z: Int): Int
```

### Traversal Examples

```kotlin
// Coordinate-wise
for (y in 0 until region.height) {
    for (z in 0 until region.depth) {
        for (x in 0 until region.width) {
            if (!region.isAir(x, y, z)) {
                val block = region.blockAt(x, y, z)
            }
        }
    }
}

// Efficient bulk traversal
val raw = region.rawBlocks
val palette = region.palette
for (i in raw.indices) {
    val idx = raw[i]
    if (idx != 0) {
        val bs = palette[idx]
        // process block...
    }
}
```

## BlockPalette

Region palette — index ↔ block state.

```kotlin
data class BlockPalette(val entries: List<BlockState>) {
    val size: Int                      // entry count
    val bitsPerBlock: Int              // ceil(log2(size)), bit-packing width
    operator fun get(index: Int): BlockState
}
```

## BlockState

A single block state.

```kotlin
data class BlockState(
    val name: String,                       // "minecraft:oak_planks"
    val properties: Map<String, String>?,   // { "axis": "y" }, null if absent
) {
    // toString() → "minecraft:oak_planks"
    //          or "minecraft:oak_log[axis=y]"
}
```

## Position

Integer 3D coordinate.

```kotlin
data class Position(val x: Int, val y: Int, val z: Int) {
    companion object {
        val ZERO = Position(0, 0, 0)
    }
}
```

## MaterialList

Block material counter.

```kotlin
class MaterialList : LinkedHashMap<String, Int>() {
    companion object {
        fun from(litematic: Litematic, includeAir: Boolean = false): MaterialList
    }
    fun toSortedByCount(): List<Pair<String, Int>>   // descending by count
    fun toSortedList(): List<Pair<String, Int>>      // alphabetical by name
}
```

## MinecraftVersions

Data version → version string lookup.

```kotlin
MinecraftVersions[3578]  // "1.20.2"
MinecraftVersions[3953]  // "1.21"
MinecraftVersions.size   // 811 (1.8–present)

// Table is baked in at compile time — zero runtime cost
// Update script: python scripts/update_versions.py
```

## Coordinate System

Minecraft native coordinates:

```
x → horizontal (east/west)
y → vertical (up/down)
z → horizontal (south/north)
```

Block memory layout is y-major / z-middle / x-minor (consistent with Minecraft NBT):

```
rawIndex(x, y, z) = y × (width × depth) + z × width + x
```

## Low-Level NBT Access

```kotlin
val doc = NbtDocument.read(File("data.nbt"))

// 13 tag subtypes
// EndTag, ByteTag, ShortTag, IntTag, LongTag, FloatTag, DoubleTag,
// ByteArrayTag, StringTag, ListTag, CompoundTag, IntArrayTag, LongArrayTag

val size = doc.root.get("size") as? NbtTag.ListTag
val palette = doc.root.get("palette") as? NbtTag.ListTag
```

## Exceptions

```kotlin
class LitematicException(message: String, cause: Throwable? = null)
    : RuntimeException
```

Thrown on: missing required NBT fields, invalid dimensions, mismatched BlockStates array length, etc.

## Architecture

```
LitematicReader                      ← public entry point
    ├── content sniff: leading `{` → JSON → BuildingHelperParser
    │                 0x1F 0x8B  → gzip → NBT
    │                 otherwise   → NBT
    ├── strict parse()
    │     └── LitematicParser        ← NBT → Litematic / Region / Palette
    │           ├── Sponge v2 (Palette + BlockData at root, int dims)
    │           ├── Sponge v3 ("Schematic" wrapper compound, short dims, name→Int palette)
    │           ├── Structure sparse → dense conversion
    │           └── Building Helper JSON
    │
    ├── lenient parseLenient()       ← partial files → placeholder Region
    │     └── readSizeLenient(): Size / size / Metadata.EnclosingSize / Width+Height+Length
    │
    └── NbtReader + NbtTag           ← low-level NBT (zero-dep, gzip auto-detect)

GLB Pipeline (glb/ subpackage)
    ├── LitematicToGlb               ← public entry
    ├── GlbWriter                    ← GLB serialization
    ├── MeshBuilder                  ← vertices / UVs / normals
    ├── ModelResolver                ← model resolution
    ├── TexturePacker                ← texture atlas packing
    ├── ImageBackend (expect)        ← cross-platform image codec
    ├── FileAccessor                 ← cross-platform file abstraction
    └── synthetic/                   ← synthetic blocks
```
