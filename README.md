# BlockPrint Core

[![Maven Central](https://badgen.net/maven/v/maven-central/io.github.moxisuki/blockprint-core)](https://central.sonatype.com/artifact/io.github.moxisuki/blockprint-core)
[![CI](https://github.com/moxisuki/blockprint-core/actions/workflows/ci.yml/badge.svg)](https://github.com/moxisuki/blockprint-core/actions/workflows/ci.yml)

零依赖 Kotlin Multiplatform 库，解析 Minecraft 蓝图并提供 **GLB 3D 模型实时生成**。

支持大部分模组**标准方块和标准 OBJ 模型**；Create(机械动力) 等复杂模组正在迁移到构建期 baked 模型提取流程。

**平台**：JVM 17+ / Android API 21+ ｜ **构建 JDK**：21 ｜ **语言**：Kotlin 2.2.10 ｜ **构建**：Gradle 9.5.1

> [English](README_EN.md)

## 集成

```kotlin
repositories { mavenCentral() }
dependencies {
    implementation("io.github.moxisuki:blockprint-core:1.5.1")
}
```

Android 推荐复合构建以获得 `BitmapImageBackend`：

```kotlin
// settings.gradle.kts
includeBuild("../blockprint-core")
```

或直接拉 Android 变体：`io.github.moxisuki:blockprint-core-android:1.5.1`

## 快速开始

```kotlin
import io.github.moxisuki.blockprint.core.*
import io.github.moxisuki.blockprint.core.api.*

// 解析蓝图（自动检测 .litematic / .schematic / .nbt / gzip）
val lit = BlockPrintReader.read(File("house.litematic"))

// 材料统计
MaterialList.from(lit).toSortedByCount().forEach { (name, count) ->
    println("$count × $name")
}

// 生成 GLB
val assetsDirs = listOf(Path.of("assets"))
BlockPrintToGlb.convert(lit, assetsDirs, File("output.glb"))

// 或输出字节数组（带进度）
val bytes = BlockPrintToGlb.convertToBytes(lit, assetsDirs, onProgress = { p ->
    println("${(p * 100).toInt()}%")
})
```

> 📖 详细 API：[BLUEPRINT_API.md](./docs/BLUEPRINT_API.md) · [GLB_PIPELINE.md](./docs/GLB_PIPELINE.md)

## 性能

v1.0.0 对 GLB 导出热路径进行了全面优化，消除了每面每回路的堆分配。短基准（median of 5，`test/assets` 模式下 JVM 21）：

| 场景                   | v0.2.x（基线） | v1.0.0 | 加速        |
| -------------------- | ---------- | ------ | --------- |
| 16³ 石/橡木板 checker    | 36 ms      | 20 ms  | -44%      |
| 32³ 石/橡木板 checker    | 131 ms     | 56 ms  | -57%      |
| 64³ 石/橡木板 checker    | 515 ms     | 368 ms | -29%      |
| 16³ 栅栏（实心，4096 栅栏块）  | 3 611 ms   | 207 ms | **17.4×** |
| 真实 `.schem` 35×25×30 | 159 ms     | 65 ms  | -59%      |

## Create 模组支持

Create 支持现在优先走“构建期提取、运行时读取静态 manifest”的新路径：

```text
NeoForge client + Create/mod jars
  → BakedModel / renderer / visual capture
  → blockprint/baked-models/*.json
  → blockprint-core / Android 读取静态 mesh
```



详细工具链、命令和路线图见：[tools/create-model-baker/README.md](./tools/create-model-baker/README.md)。

## TODO

- [ ] **纹理**：动画帧写入 GLB、biome 着色 Android 端、连接纹理(CTM)

## 编译

```bash
./gradlew compileKotlinJvm         # JVM
./gradlew build                     # 含 Android
./gradlew publishToMavenLocal       # 本地发布
```

要求 JDK 21 + Android SDK 34+。

## 许可

MIT License
