# BlockPrint Core

[![Maven Central](https://badgen.net/maven/v/maven-central/io.github.moxisuki/blockprint-core)](https://central.sonatype.com/artifact/io.github.moxisuki/blockprint-core)
[![CI](https://github.com/moxisuki/blockprint-core/actions/workflows/ci.yml/badge.svg)](https://github.com/moxisuki/blockprint-core/actions/workflows/ci.yml)

A zero-dependency Kotlin Multiplatform library for parsing Minecraft blueprints and generating **real-time GLB 3D models**.

Supports most vanilla blocks and standard OBJ models. Complex mods such as **Create** are being moved to a build-time baked-model extraction workflow.

**Platforms**: JVM 17+ / Android API 21+ ｜ **Build JDK**: 21 ｜ **Language**: Kotlin 2.2.10 ｜ **Build**: Gradle 9.5.1

> [中文](README.md)

## Integration

```kotlin
repositories { mavenCentral() }
dependencies {
    implementation("io.github.moxisuki:blockprint-core:1.5.1")
}
```

For Android, composite build is recommended to get `BitmapImageBackend`:

```kotlin
// settings.gradle.kts
includeBuild("../blockprint-core")
```

Or pull the Android variant directly: `io.github.moxisuki:blockprint-core-android:1.5.1`

## Quick Start

```kotlin
import io.github.moxisuki.blockprint.core.*
import io.github.moxisuki.blockprint.core.api.*

// Parse blueprint (auto-detects .litematic / .schematic / .nbt / gzip)
val lit = BlockPrintReader.read(File("house.litematic"))

// Material stats
MaterialList.from(lit).toSortedByCount().forEach { (name, count) ->
    println("$count × $name")
}

// Generate GLB
val assetsDirs = listOf(Path.of("assets"))
BlockPrintToGlb.convert(lit, assetsDirs, File("output.glb"))

// Or to byte array (with progress)
val bytes = BlockPrintToGlb.convertToBytes(lit, assetsDirs, onProgress = { p ->
    println("${(p * 100).toInt()}%")
})
```

> 📖 Full API: [BLUEPRINT_API_EN.md](./docs/BLUEPRINT_API_EN.md) · [GLB_PIPELINE_EN.md](./docs/GLB_PIPELINE_EN.md)

## Performance

v1.0.0 overhauls the GLB export hot path to eliminate per-face heap allocations. Benchmarks (median of 5, JVM 21, `test/assets` mode):

| Scene                                | v0.2.x (baseline) | v1.0.0 | Speedup   |
| ------------------------------------ | ----------------- | ------ | --------- |
| 16³ stone/oak_planks checker         | 36 ms             | 20 ms  | -44%      |
| 32³ stone/oak_planks checker         | 131 ms            | 56 ms  | -57%      |
| 64³ stone/oak_planks checker         | 515 ms            | 368 ms | -29%      |
| 16³ solid fence (4,096 fence blocks) | 3,611 ms          | 207 ms | **17.4×** |
| Real `.schem` 35×25×30               | 159 ms            | 65 ms  | -59%      |

## Create Mod Support

Create support now prefers the new "extract at build time, read static manifests at runtime" path:

```text
NeoForge client + Create/mod jars
  → BakedModel / renderer / visual capture
  → blockprint/baked-models/*.json
  → blockprint-core / Android reads static meshes
```

See the detailed toolchain, commands, and roadmap: [tools/create-model-baker/README.md](./tools/create-model-baker/README.md).

## TODO

- [ ] **Textures**: animation frames in GLB, biome tinting on Android, connected textures (CTM)

## Build

```bash
./gradlew compileKotlinJvm         # JVM
./gradlew build                    # incl. Android
./gradlew publishToMavenLocal      # local publish
```

Requires JDK 21 + Android SDK 34+.

## License

MIT License
