# BlockPrint Core

[![Maven Central](https://badgen.net/maven/v/maven-central/io.github.moxisuki/blockprint-core)](https://central.sonatype.com/artifact/io.github.moxisuki/blockprint-core)
[![Publish](https://github.com/moxisuki/blockprint-core/actions/workflows/publish.yml/badge.svg)](https://github.com/moxisuki/blockprint-core/actions/workflows/publish.yml)
[![CI](https://github.com/moxisuki/blockprint-core/actions/workflows/ci.yml/badge.svg)](https://github.com/moxisuki/blockprint-core/actions/workflows/ci.yml)

A zero-dependency Kotlin Multiplatform library for parsing Minecraft blueprint files and generating **real-time GLB 3D models**.

**Platforms**: JVM 21+ / Android API 21+ ｜ **Language**: Kotlin 2.2.10 ｜ **Build**: Gradle 9.5.1

> [中文版本](README.md)

## Highlights

### Multi-Format Support

One API for **5** blueprint sources, with automatic format detection:

```
.litematic  (Litematica mod)       ─┐
.schematic  (Sponge / WorldEdit)    ─┤
.nbt        (vanilla /structure)    ─┼──► LitematicReader ──► unified Litematic model
.nbt        (Building Helper)       ─┤
any NBT     (debug / non-standard)  ─┘     NbtDocument raw access
```

- **Strict mode** `read()` — full validation
- **Lenient mode** `readLenient()` — auto-fills placeholder Region for partial files
- **Preflight** `detectFormat()` — check format before parsing

### Real-time GLB Generation (Experimental)

> ⚠️ **Experimental** — known limitations:
> 
> - **Block entity properties**: chests, furnaces, enchantment tables etc. render as blocks, but TE data (orientation, burning state, placed book) is not displayed
> - **OBJ models**: basic geometry is supported, but complex material groups and multi-model assemblies are still imperfect
> - **Texture animation**: water/lava/fire animation frames are parsed but not written to GLB
> - **Entities**: item frames, paintings, armor stands are not mapped to GLB nodes

See [docs/GLB_PIPELINE.md](./docs/GLB_PIPELINE.md) for details.

## Build

```bash
cd blockprint-core

# JVM only
./gradlew compileKotlinJvm

# Full build (including Android target)
./gradlew build

# Publish to local Maven
./gradlew publishToMavenLocal
```

Requires JDK 21 + Android SDK 34+.

## Integration

### JVM Projects

```kotlin
repositories { mavenCentral() }
dependencies {
    implementation("io.github.moxisuki:blockprint-core:0.1.13")
}
```

### Android Projects

Composite build (recommended — gets platform-specific code like `BitmapImageBackend`):

```kotlin
// settings.gradle.kts
includeBuild("../blockprint-core")

// app/build.gradle.kts
dependencies {
    implementation("io.github.moxisuki:blockprint-core:0.1.13")
}
```

Or pull the Android variant directly from Maven Central:

```kotlin
dependencies {
    implementation("io.github.moxisuki:blockprint-core-android:0.1.13")
}
```

### Minecraft Mods

```kotlin
dependencies {
    implementation("io.github.moxisuki:blockprint-core:0.1.13")
}
```

JVM targets use `java.nio.file.Path` as the file access backend.

## Zero runtime dependencies. Android API 21+. No ProGuard rules needed.

## Quick Start

### Parse a Blueprint

```kotlin
import io.github.moxisuki.blockprint.core.*

// From File / InputStream / ByteArray (auto-detects gzip)
val lit = LitematicReader.read(File("house.litematic"))

// Basic info
println("Name: ${lit.name}")
println("Author: ${lit.author}")
println("Format: ${lit.format}")              // Litematica / Sponge / Structure / ...
println("MC version: ${MinecraftVersions[lit.minecraftDataVersion]}")
println("Regions: ${lit.regions.size}")
println("Blocks: ${lit.blockCount()}")        // excluding air
```

### Traverse Region Blocks

```kotlin
val region = lit.primaryRegion!!

// Random access by coordinate
val block = region.blockAt(x, y, z)
println(block.name)        // "minecraft:oak_planks"
println(block.properties)  // { "variant": "oak" }

// Check air
if (!region.isAir(x, y, z)) { ... }

// Efficient bulk traversal (operate on IntArray directly)
val raw = region.rawBlocks
val palette = region.palette
for (i in raw.indices) {
    val idx = raw[i]
    if (idx != 0) {
        val bs = palette[idx]  // BlockState(name, properties)
    }
}
```

> 📖 [BLUEPRINT_API_EN.md](./docs/BLUEPRINT_API_EN.md) — full API: Region traversal, BlockPalette, NBT, coordinates

### Generate GLB

```kotlin
import io.github.moxisuki.blockprint.core.glb.*

val assetsDirs = listOf(Path.of("assets"))

// Output to file
LitematicToGlb.convert(lit, assetsDirs, File("output.glb"))

// Output to byte array (with progress callback)
val bytes = LitematicToGlb.convertToBytes(lit, assetsDirs) { progress ->
    println("${(progress * 100).toInt()}%")
}
```

> 📖 [GLB_PIPELINE_EN.md](./docs/GLB_PIPELINE_EN.md) — experimental limits, synthetic blocks, cross-platform abstractions

### Material Stats

```kotlin
MaterialList.from(lit).toSortedByCount().forEach { (name, count) ->
    println("$count × ${name.removePrefix("minecraft:")}")
}
```

## License

MIT License
