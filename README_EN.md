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
    implementation("io.github.moxisuki:blockprint-core:1.0.0")
}
```

For Android, composite build is recommended to get `BitmapImageBackend`:

```kotlin
// settings.gradle.kts
includeBuild("../blockprint-core")
```

Or pull the Android variant directly: `io.github.moxisuki:blockprint-core-android:1.0.0`

## Quick Start

```kotlin
import io.github.moxisuki.blockprint.core.*
import io.github.moxisuki.blockprint.core.glb.*

// Parse blueprint (auto-detects .litematic / .schematic / .nbt / gzip)
val lit = BlockPrintReader.read(File("house.litematic"))

// Material stats
MaterialList.from(doc).toSortedByCount().forEach { (name, count) ->
    println("$count × $name")
}

// Generate GLB
val assetsDirs = listOf(Path.of("assets"))
BlockPrintToGlb.convert(lit, assetsDirs, File("output.glb"))

// Or to byte array (with progress)
val bytes = BlockPrintToGlb.convertToBytes(lit, assetsDirs) { p ->
    println("${(p * 100).toInt()}%")
}
```

> 📖 Full API: [BLUEPRINT_API_EN.md](./docs/BLUEPRINT_API_EN.md) · [GLB_PIPELINE_EN.md](./docs/GLB_PIPELINE_EN.md)

## Performance

v1.0.0 overhauls the GLB export hot path to eliminate per-face heap allocations. Benchmarks (median of 5, JVM 21, `test/assets` mode):

| Scene | v0.2.x (baseline) | v1.0.0 | Speedup |
|---|---|---|---|
| 16³ stone/oak_planks checker | 36 ms | 20 ms | -44% |
| 32³ stone/oak_planks checker | 131 ms | 56 ms | -57% |
| 64³ stone/oak_planks checker | 515 ms | 368 ms | -29% |
| 16³ solid fence (4,096 fence blocks) | 3,611 ms | 207 ms | **17.4×** |
| Real `.schem` 35×25×30 | 159 ms | 65 ms | -59% |

Key optimisations:
- **FaceScratch**: per-face `List<DoubleArray>` / `FloatArray(3)` allocations replaced with a single per-call reuse buffer (PR-1..4).
- **Model resolution cache**: `ModelResolver` model + blockstate JSON reads and parses happen once (Area 1).
- **Connection variant cache**: two fence cells with identical orientation share one model resolution (Area 3).
- **IntArray connection-mask**: per-cell `Triple(x,y,z)` + 5× substring scans replaced with a flat 4-bit mask (Area 2).
- **Single-pass export**: counting and geometry emission merged into one `buildFloorsInto` call; sink takes buffer ownership, no copies (Area B/C/G).

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
