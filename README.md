# BlockPrint Core

[![Maven Central](https://badgen.net/maven/v/maven-central/io.github.moxisuki/blockprint-core)](https://central.sonatype.com/artifact/io.github.moxisuki/blockprint-core)
[![CI](https://github.com/moxisuki/blockprint-core/actions/workflows/ci.yml/badge.svg)](https://github.com/moxisuki/blockprint-core/actions/workflows/ci.yml)

零依赖 Kotlin Multiplatform 库，解析 Minecraft 蓝图并提供 **GLB 3D 模型实时生成**。

支持大部分模组**标准方块和标准obj模型**，但对**Create(机械动力)**单独适配支持中

**平台**：JVM 21+ / Android API 21+ ｜ **语言**：Kotlin 2.2.10 ｜ **构建**：Gradle 9.5.1

> [English](README_EN.md)

## 集成

```kotlin
repositories { mavenCentral() }
dependencies {
    implementation("io.github.moxisuki:blockprint-core:0.2.1")
}
```

Android 推荐复合构建以获得 `BitmapImageBackend`：

```kotlin
// settings.gradle.kts
includeBuild("../blockprint-core")
```

或直接拉 Android 变体：`io.github.moxisuki:blockprint-core-android:0.2.1`

## 快速开始

```kotlin
import io.github.moxisuki.blockprint.core.*
import io.github.moxisuki.blockprint.core.glb.*

// 解析蓝图（自动检测 .litematic / .schematic / .nbt / gzip）
val lit = BlockPrintReader.read(File("house.litematic"))

// 材料统计
MaterialList.from(doc).toSortedByCount().forEach { (name, count) ->
    println("$count × $name")
}

// 生成 GLB
val assetsDirs = listOf(Path.of("assets"))
BlockPrintToGlb.convert(lit, assetsDirs, File("output.glb"))

// 或输出字节数组（带进度）
val bytes = LitematicToGlb.convertToBytes(lit, assetsDirs) { p ->
    println("${(p * 100).toInt()}%")
}
```

> 📖 详细 API：[BLUEPRINT_API.md](./docs/BLUEPRINT_API.md) · [GLB_PIPELINE.md](./docs/GLB_PIPELINE.md)

## Create 模组支持

适配逻辑在 `CreateModObjAdapter.kt`。已适配 24 种方块的多部件合成几何：

| 方块                                                                              | 适配内容                                                             |
| ------------------------------------------------------------------------------- | ---------------------------------------------------------------- |
| `mechanical_drill`                                                              | 外壳 + 钻头(head, 6 朝向)                                              |
| `mechanical_press`                                                              | 外壳 + 压杆(head) + 水平传动轴(沿 facing)                                  |
| `mechanical_mixer`                                                              | 外壳 + 齿轮(cogwheel) + 顶杆(pole) + 搅拌头(whisk)                        |
| `belt`                                                                          | 皮带环(top+bottom, horiz/diag, cased/uncased, start/mid/end/pulley) |
| `gearbox`                                                                       | 双正交轴(根据 active axis)                                             |
| `andesite_encased_shaft` / `brass_encased_shaft` / `metal_girder_encased_shaft` | 套管 + 轴                                                           |
| `clutch` / `gearshift` / `sequenced_gearshift` / `encased_chain_drive`          | 变速箱 + 轴                                                          |
| `andesite_encased_cogwheel` / `brass_encased_cogwheel`                          | 套管 + 小齿轮(含 top/bottom shaft)                                     |
| `andesite_encased_large_cogwheel` / `brass_encased_large_cogwheel`              | 同上(大齿轮)                                                          |
| `andesite_funnel` / `brass_funnel`                                              | 漏斗 + 帘子(水平朝向)                                                    |
| `andesite_belt_funnel` / `brass_belt_funnel`                                    | 传送带漏斗 + 帘子                                                       |
| `water_wheel`                                                                   | 水车基座 + 轮片                                                        |

其他 Create 方块走默认 blockstate 解析——外壳可渲染，多部件方块实体暂缺部件合成。

## TODO

- [ ] **边建边写**：`MeshBuilder` 先攒所有顶点再写出，500k 方块模型峰值 >1 GB，Android 必 OOM。双趟流式可降到 ~100 MB（详见 [GLB_PIPELINE.md](./docs/GLB_PIPELINE.md)）
- [ ] **Create 模组**：``millstone`、`basin`、`mechanical_saw`、`deployer`、`steam_engine`、`fluid_tank`、`chute`` 等方块实体适配

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

