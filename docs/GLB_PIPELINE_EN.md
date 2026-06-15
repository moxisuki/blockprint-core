# GLB 3D Model Pipeline

> 🔬 **Experimental**. Please review the known limitations below before use. Roadmap: block entity property rendering → texture animation.

Blueprint Region → glTF 2.0 Binary (GLB), ready for SceneView / Three.js / Web.

> [中文版本](GLB_PIPELINE.md)

## Pipeline Overview

```
LitematicRegion
    │
    ▼
LitematicToGlb.convert() / convertToBytes()   ← entry point
    │
    ├── ModelResolver(assetsDirs)              ← block state → JSON model (parent chain)
    ├── TexturePacker(assetsDirs)              ← texture atlas packing (PNG → RGBA8)
    ├── MeshBuilder                            ← vertex / face / UV construction
    │     ├── face culling (occluded internal faces removed)
    │     ├── tinting (grass / foliage biome tint)
    │     └── synthetic blocks (beds, chests, signs, etc.)
    └── GlbWriter                              ← GLB binary serialization
          └── ByteArray / OutputStream
```

## Basic Usage

### Output to File

```kotlin
LitematicToGlb.convert(
    litematic = lit,
    assetsDirs = listOf(Path.of("path/to/assets")),
    outputFile = File("output.glb"),
    regionIndex = 0,          // default: first Region
    enableTinting = true,     // grass / foliage biome tint (default: on)
)
```

### Output to Byte Array

```kotlin
val bytes: ByteArray = LitematicToGlb.convertToBytes(
    litematic = lit,
    assetsDirs = assetsDirs,
    regionIndex = 0,
    imageBackend = null,      // null = platform default
    onProgress = { p -> println("${(p * 100).toInt()}%") },
)
```

Progress callback phases:

- `0.05` — started
- `0.20` — model resolution complete, building mesh
- `0.70` — mesh complete, writing GLB
- `1.0` — done

## ⚠️ Experimental Limitations

### Block Entity Properties

Chests, furnaces, enchantment tables etc. **render correctly as blocks**, but their block entity (TileEntity) data is not reflected:

| Block | Renders | Not Displayed |
|-------|:--:|------|
| Chest / Ender Chest | ✅ | open/close state, double chest connection |
| Furnace / Blast Furnace / Smoker | ✅ | burning state, flame animation |
| Enchanting Table | ✅ | bookshelf detection, floating book |
| Lectern | ✅ | placed book |
| Sign | ✅ | text content |
| Shulker Box | ✅ | dye color |
| Conduit | ✅ | activation state, rotating eye |
| Beacon | ✅ | beam |

### OBJ Models

Some mods use `.obj` format for custom block models (e.g. Create mod mechanical parts). The pipeline has a built-in OBJ parser (`ObjParser`) that handles basic geometry, but the following scenarios are **still imperfect**:

- Complex OBJ material groups (`usemtl`) — texture mapping may be offset
- Multi-model OBJ blocks (e.g. Create shafts, gearboxes) — faces may be missing or misplaced
- Non-standard OBJ vertex formats (e.g. with tangents) — parsing incomplete

Reports for specific mods are welcome to help improve accuracy.

### Texture Animation

Vanilla animated textures (flowing water, lava, fire, sea lanterns, etc.) have their frame sequences extracted during parsing, but the GLB pipeline does not currently write animation channels (`KHR_texture_transform` or custom samplers). Output is a static texture atlas.

### Entities

Item frames, paintings, armor stands, lead knots, etc. have their NBT data parsed but are not included in GLB mesh construction.

## File Access Abstraction

The GLB pipeline reads model and texture files through `FileAccessor`, decoupling storage:

```kotlin
// commonMain interface
interface FileAccessor {
    fun readBytes(relPath: String): ByteArray?
    fun exists(relPath: String): Boolean
}

// JVM implementation
class PathFileAccessor(dirs: List<Path>) : FileAccessor
class PathFileAccessor(dir: Path) : FileAccessor
```

Android implementations: see BlockPrint Cat's `FileSystemFileAccessor` and `AssetFileAccessor`.

## Image Backend

Cross-platform image codec; texture atlas packing depends on this abstraction:

```kotlin
// commonMain expect interface
expect interface ImageBackend {
    fun loadPng(path: Path): ImageData?
    fun encodePng(data: ImageData): ByteArray
}

// JVM actual — java.awt.BufferedImage + javax.imageio.ImageIO
// Android actual — android.graphics.Bitmap + BitmapFactory

data class ImageData(
    val width: Int,
    val height: Int,
    val argb: IntArray,  // ARGB_8888, row-major
)
```

## Synthetic Blocks

Specialized block synthesizers for dynamic models (`synthetic/` subpackage):

| Synthesizer | Block(s) | Notes |
|-------------|----------|-------|
| SyntheticBed | Bed | 4 directions + top/bottom halves |
| SyntheticChest | Chest, Ender Chest | three parts (base / lid / latch) |
| SyntheticSign | Sign | standing / wall + 8 directions |
| SyntheticBanner | Banner | 8 directions |
| SyntheticSkull | Skull | includes Dragon Head |
| SyntheticShulkerBox | Shulker Box | 4 directions |
| SyntheticConduit | Conduit | cage structure |
| SyntheticDecoratedPot | Decorated Pot | four-sided texture |
| SyntheticLectern | Lectern | base + book |
| SyntheticFluid | Water / Lava | tinted |

## GLB Output Structure

```kotlin
data class GlbOutput(
    val positions: FloatArray,   // vertex positions (x, y, z) × N
    val uvs: FloatArray,         // texture coordinates (u, v) × N
    val normals: FloatArray?,    // normals (nx, ny, nz) × N, optional
    val indices: IntArray,       // triangle indices
    val atlasPng: ByteArray,     // PNG-encoded texture atlas
    val atlasWidth: Int,         // atlas width
    val atlasHeight: Int,        // atlas height
)
```

Output GLB characteristics:

- `scene 0` → `node 0` → `mesh 0`
- Single texture atlas (`baseColorTexture`)
- `alphaMode = MASK` + `alphaCutoff = 0.05` + `doubleSided = true`
- Translucent blocks (water, stained glass, etc.) use a separate primitive
