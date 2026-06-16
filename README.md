# BlockPrint Core

[![Release](https://img.shields.io/github/release/moxisuki/blockprint-core)](https://github.com/moxisuki/blockprint-core/releases)
[![Publish](https://github.com/moxisuki/blockprint-core/actions/workflows/publish.yml/badge.svg)](https://github.com/moxisuki/blockprint-core/actions/workflows/publish.yml)
[![CI](https://github.com/moxisuki/blockprint-core/actions/workflows/ci.yml/badge.svg)](https://github.com/moxisuki/blockprint-core/actions/workflows/ci.yml)

零依赖 Kotlin Multiplatform 库，解析 Minecraft 蓝图文件并提供 **GLB 3D 模型实时生成**。

**平台**：JVM 21+ / Android API 21+ ｜ **语言**：Kotlin 2.2.10 ｜ **构建**：Gradle 9.5.1

> [English](README_EN.md)

## 亮点

### 多格式协议支持

一套 API 覆盖 **5 种** 蓝图数据源，自动检测格式：

```
.litematic  (Litematica 模组)     ─┐
.schematic  (Sponge / WorldEdit)   ─┤
.nbt        (原版 /structure save)  ─┼──► LitematicReader ──► 统一的 Litematic 模型
.nbt        (Building Helper)      ─┤
任意 NBT    (调试 / 非标准)         ─┘     NbtDocument 底层直读
```

- **严格模式** `read()` — 完整校验
- **宽松模式** `readLenient()` — 残缺文件自动填充占位 Region
- **预检** `detectFormat()` — 先看格式再选策略

### 实时 GLB 生成（实验性）

蓝图 Region → glTF 2.0 Binary 一步到位，手机端 3D 预览即开即用。

> ⚠️ **实验性阶段**——以下场景尚不完善：
> 
> - **方块实体属性**：箱子/熔炉/附魔台等方块本体可渲染，但方块实体数据（如箱子的朝向、熔炉燃烧状态、讲台上的书）不显示
> - **OBJ 模型**：已适配基础几何体，但复杂面材质组、多模型组合等场景仍不完善
> - **纹理动画**：水/熔岩/火焰的动画帧数据已解析但不写入 GLB
> - **实体**：物品展示框、画、盔甲架等未映射为 GLB 节点

详见 [docs/GLB_PIPELINE.md](./docs/GLB_PIPELINE.md)。

## 编译

```bash
cd blockprint-core

# JVM 编译
./gradlew compileKotlinJvm

# 完整构建（含 Android 目标）
./gradlew build

# 发布到本地 Maven（供其他项目使用）
./gradlew publishToMavenLocal
```

要求 JDK 21 + Android SDK 34+。

## 集成

### JVM 项目

```kotlin
repositories { mavenCentral() }
dependencies {
    implementation("io.github.moxisuki:blockprint-core:0.1.13")
}
```

### Android 项目

复合构建（推荐，自动获得平台相关代码如 `BitmapImageBackend`）：

```kotlin
// settings.gradle.kts
includeBuild("../blockprint-core")

// app/build.gradle.kts
dependencies {
    implementation("io.github.moxisuki:blockprint-core:0.1.13")
}
```

或直接从 Maven Central 拉取 Android 变体：

```kotlin
dependencies {
    implementation("io.github.moxisuki:blockprint-core-android:0.1.13")
}
```

### Minecraft 模组

```kotlin
dependencies {
    implementation("io.github.moxisuki:blockprint-core:0.1.13")
}
```

JVM 端使用 `java.nio.file.Path` 作为文件访问后端。

## 零运行时依赖。Android API 21+，无需额外 ProGuard 规则。

## 快速开始

### 解析蓝图

```kotlin
import io.github.moxisuki.blockprint.core.*

// 从文件 / 输入流 / 字节数组（自动检测 gzip）
val lit = LitematicReader.read(File("house.litematic"))

// 基本信息
println("名称: ${lit.name}")
println("作者: ${lit.author}")
println("格式: ${lit.format}")              // Litematica / Sponge / Structure / ...
println("MC 版本: ${MinecraftVersions[lit.minecraftDataVersion]}")
println("区域数: ${lit.regions.size}")
println("总方块: ${lit.blockCount()}")     // 不含空气
```

### 遍历 Region 方块

```kotlin
val region = lit.primaryRegion!!

// 按坐标访问
val block = region.blockAt(x, y, z)
println(block.name)        // "minecraft:oak_planks"
println(block.properties)  // { "variant": "oak" }

// 判断空气
if (!region.isAir(x, y, z)) { ... }

// 高效批量遍历（直接操作 IntArray）
val raw = region.rawBlocks
val palette = region.palette
for (i in raw.indices) {
    val idx = raw[i]
    if (idx != 0) {
        val bs = palette[idx]  // BlockState(name, properties)
    }
}
```

> 📖 [BLUEPRINT_API.md](./docs/BLUEPRINT_API.md) — Region 遍历、BlockPalette、NBT 底层、坐标系统

### 生成 GLB

```kotlin
import io.github.moxisuki.blockprint.core.glb.*

val assetsDirs = listOf(Path.of("assets"))

// 输出文件
LitematicToGlb.convert(lit, assetsDirs, File("output.glb"))

// 输出字节数组（带进度回调）
val bytes = LitematicToGlb.convertToBytes(lit, assetsDirs) { progress ->
    println("${(progress * 100).toInt()}%")
}
```

> 📖 [GLB_PIPELINE.md](./docs/GLB_PIPELINE.md) — 实验性限制、合成方块、跨平台抽象

### 材料统计

```kotlin
MaterialList.from(lit).toSortedByCount().forEach { (name, count) ->
    println("$count × ${name.removePrefix("minecraft:")}")
}
```

## 许可

MIT License
