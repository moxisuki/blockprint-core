# Blueprint Parsing API

Complete public API reference for BlockPrint Core.

> [中文版本](BLUEPRINT_API.md)

## Entry Point: LitematicReader

```kotlin
object LitematicReader {
    fun read(file: File): Litematic                 // from file
    fun read(inputStream: InputStream): Litematic   // from stream (auto-closed)
    fun read(bytes: ByteArray): Litematic           // from bytes (auto-detects gzip)

    fun readLenient(file: File): Litematic          // lenient (no exception on partial)
    fun readLenient(inputStream: InputStream): Litematic
    fun readLenient(bytes: ByteArray): Litematic

    fun detectFormat(file: File): SchematicFormat   // preflight check, never throws
}
```

### Strict vs Lenient

| Mode | Method | Missing Regions | Use Case |
|------|--------|:--:|------|
| Strict | `read()` | throws `LitematicException` | complete, well-formed files |
| Lenient | `readLenient()` | fills an all-air placeholder Region | partial / debug / non-standard |

The placeholder Region is sized from `Size`, `size`, or `Metadata.EnclosingSize` in NBT; its palette contains only `minecraft:air`.

### Supported Format Enum

```kotlin
enum class SchematicFormat {
    Litematica,     // .litematic — standard Litematica file
    Sponge,         // .schematic — Sponge / WorldEdit
    Structure,      // .nbt — vanilla /structure save
    BuildingHelper, // .nbt — Building Helper mod
    PartialNbt,     // generic NBT — has Size but no Regions
    Unknown,        // unrecognised
}
```

## Litematic

Top-level document model.

```kotlin
data class Litematic(
    val name: String,                   // blueprint name
    val author: String,                 // author
    val description: String,            // description
    val version: Int?,                  // file format version (usually 5 or 6)
    val minecraftDataVersion: Int?,     // MC data version, e.g. 3953 = 1.21
    val regions: List<LitematicRegion>, // regions (NBT insertion order)
    val format: SchematicFormat,        // source format
) {
    val primaryRegion: LitematicRegion?  // = regions.firstOrNull()
    fun blockCount(includeAir: Boolean = false): Int
}
```

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
    ├── strict parse()
    │     └── LitematicParser        ← NBT → Litematic / Region / Palette
    │           ├── Sponge detection
    │           ├── Structure sparse → dense conversion
    │           └── Building Helper format
    │
    ├── lenient parseLenient()       ← partial files → placeholder Region
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
