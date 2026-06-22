# BlockPrint Core

[![Maven Central](https://badgen.net/maven/v/maven-central/io.github.moxisuki/blockprint-core)](https://central.sonatype.com/artifact/io.github.moxisuki/blockprint-core)
[![CI](https://github.com/moxisuki/blockprint-core/actions/workflows/ci.yml/badge.svg)](https://github.com/moxisuki/blockprint-core/actions/workflows/ci.yml)

A zero-dependency Kotlin Multiplatform library for parsing Minecraft blueprints and generating **real-time GLB 3D models**.

Supports most vanilla blocks and standard OBJ models, with dedicated **Create mod** adapter work in progress.

**Platforms**: JVM 21+ / Android API 21+ ｜ **Language**: Kotlin 2.2.10 ｜ **Build**: Gradle 9.5.1

> [中文](README.md)

## Integration

```kotlin
repositories { mavenCentral() }
dependencies {
    implementation("io.github.moxisuki:blockprint-core:0.1.27")
}
```

For Android, composite build is recommended to get `BitmapImageBackend`:

```kotlin
// settings.gradle.kts
includeBuild("../blockprint-core")
```

Or pull the Android variant directly: `io.github.moxisuki:blockprint-core-android:0.1.27`

## Quick Start

```kotlin
import io.github.moxisuki.blockprint.core.*
import io.github.moxisuki.blockprint.core.glb.*

// Parse blueprint (auto-detects .litematic / .schematic / .nbt / gzip)
val lit = LitematicReader.read(File("house.litematic"))

// Material stats
MaterialList.from(lit).toSortedByCount().forEach { (name, count) ->
    println("$count × $name")
}

// Generate GLB
val assetsDirs = listOf(Path.of("assets"))
LitematicToGlb.convert(lit, assetsDirs, File("output.glb"))

// Or to byte array (with progress)
val bytes = LitematicToGlb.convertToBytes(lit, assetsDirs) { p ->
    println("${(p * 100).toInt()}%")
}
```

> 📖 Full API: [BLUEPRINT_API_EN.md](./docs/BLUEPRINT_API_EN.md) · [GLB_PIPELINE_EN.md](./docs/GLB_PIPELINE_EN.md)

## Create Mod Support

Adapter logic lives in `CreateModObjAdapter.kt`. 24 blocks with multi-part composite geometry:

| Block | Adapted |
|-------|---------|
| `mechanical_drill` | casing + drill head (6 facings) |
| `mechanical_press` | casing + press head + horizontal shaft (along facing) |
| `mechanical_mixer` | casing + cogwheel + pole + whisk |
| `belt` | belt loops (top+bottom, horiz/diag, cased/uncased, start/mid/end/pulley) |
| `gearbox` | dual orthogonal shafts (per active axis) |
| `andesite_encased_shaft` / `brass_encased_shaft` / `metal_girder_encased_shaft` | casing + shaft |
| `clutch` / `gearshift` / `sequenced_gearshift` / `encased_chain_drive` | gearbox + shaft |
| `andesite_encased_cogwheel` / `brass_encased_cogwheel` | casing + small cogwheel (top/bottom shaft) |
| `andesite_encased_large_cogwheel` / `brass_encased_large_cogwheel` | same (large cogwheel) |
| `andesite_funnel` / `brass_funnel` | funnel + curtains (horizontal facings) |
| `andesite_belt_funnel` / `brass_belt_funnel` | belt funnel + curtains |
| `water_wheel` | base + wheel blades |

Other Create blocks use default blockstate resolution — casing renders, multi-part block entities lack composite assembly.

## TODO

- [ ] **Streaming builder**: `MeshBuilder` accumulates all vertices in memory before writing — 500k block models peak >1 GB, OOM on Android. A two-pass streaming approach can reduce peak to ~100 MB (see [GLB_PIPELINE_EN.md](./docs/GLB_PIPELINE_EN.md))
- [ ] **Create mod**: `millstone`, `basin`, `mechanical_saw`, `deployer`, `steam_engine`, `fluid_tank`, `chute` block entity adaptation
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
