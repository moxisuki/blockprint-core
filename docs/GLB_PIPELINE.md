# GLB 3D 模型生成管线

> 🔬 **实验性功能**。继续阅读前请先了解当前限制。计划路线：方块实体属性渲染 → 纹理动画。

蓝图 Region → glTF 2.0 Binary（GLB），可直接在 SceneView / Three.js / Web 等渲染引擎中加载。

> [English](GLB_PIPELINE_EN.md)

## 管线概览

```
BlockPrintRegion
    │
    ▼
BlockPrintToGlb.convert() / convertToBytes()   ← 入口
    │
    ├── ModelResolver(assetsDirs)              ← 方块状态 → JSON 模型（parent 链解析）
    ├── TexturePacker(assetsDirs)              ← 纹理图集打包（PNG → RGBA8 Atlas）
    ├── MeshBuilder                            ← 顶点/面/UV 构建
    │     ├── 方块面剔除（被遮挡的内部面自动移除）
    │     ├── 染色（草/树叶 biome tint）
    │     └── 合成方块（床/箱子/告示牌等）
    └── GlbWriter                              ← GLB 二进制序列化
          └── header + JSON + BIN header → 流式接收每个 floor 数据 → 末尾追加图集
```

## 基础用法

### 输出到文件

```kotlin
BlockPrintToGlb.convert(
    litematic = lit,
    assetsDirs = listOf(Path.of("path/to/assets")),
    outputFile = File("output.glb"),
    regionIndex = 0,          // 默认第 0 个 Region
    enableTinting = true,     // 草/树叶染色（默认开启）
)
```

### 输出到字节数组

```kotlin
val bytes: ByteArray = BlockPrintToGlb.convertToBytes(
    litematic = lit,
    assetsDirs = assetsDirs,
    regionIndex = 0,
    imageBackend = null,      // null = 使用平台默认实现
    onProgress = { p -> println("${(p * 100).toInt()}%") },
)
```

进度回调区间：

- `0.05` — 开始
- `0.20` — 模型解析完成，开始构建网格
- `0.70` — 网格构建完成，开始写入 GLB
- `1.0` — 完成

## 内存预算

500 k 块（100 × 100 × 50 全石块）峰值：

| 数据 | 大小 |
|---|---|
| `region.rawBlocks` | ~ 2 MB |
| `modelCache` + `rawMeshCache` + `rotCacheX/Y` | 10 – 50 MB |
| 图集 PNG | 2 – 16 MB |
| 单个 floor 累加器（positions/uvs/normals/indices） | ~ 25 MB |
| 64 KB staging buffer | 64 KB |
| GLB header / JSON | ~ 5 KB |

**峰值：~50–90 MB**，在 Android 256 MB 堆上稳定运行。

> `convertToBytes` 仍会把整个 GLB 作为 `ByteArray` 返回（~50 MB / 500 k 块）。调用方需保证有足够剩余堆内存；超大模型请用 `convert(BlockPrintDocument, File, ...)` 流到磁盘，或 `convert(BlockPrintDocument, OutputStream, ...)` 流到任意 `OutputStream`（MediaStore / SAF / 网络），输出端均不占堆。**Android 上务必用 streaming 路径**，详见下节实测对比。

## 内存实测

一个真实 .litematic（205 × 155 × 146，52 519 非空气方块，输出 19.7 MB GLB）在三条 API 路径下的内存占用：

| Path | Java 堆 Δ | DirectByteBuffer Δ |
|---|---:|---:|
| `convertToBytes()` | **+53 MB** | 0 |
| `convert(File)` | **+37 MB** | +25 MB |
| `convert(OutputStream)` | **+37 MB** | +25 MB |

`convertToBytes` 多出来的 ~16 MB 几乎就是 GLB 输出本身——它会先把整个 GLB 装进 `ByteArray` 再返回，Java 堆随输出线性增长。`convert(File)` / `convert(OutputStream)` 的输出直接走 OffHeapBuf（堆外）→ 64 KB staging → 调用方流，**Java 堆占用与 GLB 大小基本解耦**，无论 20 MB 还是 400 MB 输出都稳。25 MB 的 DirectByteBuffer 在堆外，Android 上不计入 ART 256 MB Java 堆上限。

## `convert(OutputStream)`

新增的流式入口，签名：

```kotlin
@JvmStatic @JvmOverloads
fun convert(
    litematic: BlockPrintDocument,
    assetsDirs: List<Path>,
    outputStream: OutputStream,   // ← 调用方提供
    regionIndex: Int = 0,
    options: GlbExportOptions = GlbExportOptions(),
    onProgress: ((Float) -> Unit)? = null,
)
```

与方法返回前会 `flush()` 流，但**不会 close**——调用方负责生命周期管理。这点对 Android 关键场景尤其重要：

- **MediaStore / SAF**：`context.contentResolver.openOutputStream(uri)` 返回的流由框架管理，本库不该 close
- **网络上传**：直接 pipe 到 HTTP body，避免先落本地再上传
- **追加写入**：调用方可在流上继续写后续内容

### Android 推荐用法

| 场景 | 推荐 API |
|---|---|
| 写到本地文件 | `convert(File)` |
| 写到 MediaStore / SAF / 网络 / 自定义流 | `convert(OutputStream)` |
| 必须以 `ByteArray` 交给下游，且 GLB < 10 MB | `convertToBytes()` |
| 50 MB+ 模型 | **不要**用 `convertToBytes()`，Android 必 OOM |

## 分层导出

通过 `GlbExportOptions.floorHeight` 把建筑切成 N 个 Y 轴楼层，每层一个 node，方便消费端显隐/动画/分组：

```kotlin
BlockPrintToGlb.convert(
    litematic = lit, assetsDirs = listOf(Path.of("path/to/assets")),
    outputFile = File("out.glb"), regionIndex = 0,
    options = GlbExportOptions(floorHeight = 4, explodeGap = 0f),
)
```

GLB 结构：`scene 0` → `node 0`（根，无 mesh） → `node 1..N`（每层 `translation.y = i × explodeGap`，共享同一张纹理图集）。消费端可直接控制每层 `visible`。

边界：`floorHeight = 0`（默认）= 不分层；`floorHeight > region.height` 自动收敛为 1 层；不整除时最末层吸收余数；空 Y 段自动剔除。

## ⚠️ 实验性限制

### 方块实体属性

箱子、熔炉、附魔台、讲台等**方块本体可正常渲染**，但其方块实体（BlockEntity / TileEntity）的附加属性无法体现：

| 方块            | 可渲染 | 不显示       |
| ------------- |:---:| --------- |
| 箱子 / 末影箱      | ✅   | 是否打开、双箱连接 |
| 熔炉 / 高炉 / 烟熏炉 | ✅   | 燃烧状态、火焰动画 |
| 附魔台           | ✅   | 书架判定、书本悬浮 |
| 讲台            | ✅   | 放置的书籍     |
| 告示牌           | ✅   | 文字内容      |
| 潜影盒           | ✅   | 染色        |
| 潮涌核心          | ✅   | 激活状态、旋转眼球 |
| 信标            | ✅   | 光束        |

### OBJ 模型

部分模组使用 `.obj` 格式的自定义方块模型（如 Create 模组的机械部件）。管线已内建 OBJ 解析器（`ObjParser`）并适配了基础几何体，但以下场景**仍不完善**：

- 复杂 OBJ 面材质组（`usemtl`）的纹理映射可能偏移
- 多模型组合的 OBJ 方块（如 Create 的传动轴、齿轮箱）可能缺面或错位
- 非标准的 OBJ 顶点格式（如带切空间的）解析不完整

欢迎针对具体模组报告渲染结果以帮助改进。

### 纹理动画

原版纹理动画（如流动的水、熔岩、火焰、海晶灯等）的帧序列数据在解析阶段已提取，但 GLB 管线当前不写入动画通道（`KHR_texture_transform` 或自定义 sampler），输出为静态纹理图集。

### 实体

物品展示框、画、盔甲架、拴绳结等实体的 NBT 数据已解析，但未参与 GLB 网格构建。

## 文件访问抽象

GLB 管线通过 `FileAccessor` 读取模型和纹理文件，解耦了具体存储方式：

```kotlin
// commonMain 接口
interface FileAccessor {
    fun readBytes(relPath: String): ByteArray?
    fun exists(relPath: String): Boolean
}

// JVM 实现
class PathFileAccessor(dirs: List<Path>) : FileAccessor
class PathFileAccessor(dir: Path) : FileAccessor
```

Android 端实现见 BlockPrint Cat 的 `FileSystemFileAccessor` 和 `AssetFileAccessor`。

## 图片后端

跨平台图片编解码，纹理图集打包依赖此抽象：

```kotlin
// commonMain expect 接口
expect interface ImageBackend {
    fun loadPng(path: Path): ImageData?
    fun encodePng(data: ImageData): ByteArray
}

// JVM actual — java.awt.BufferedImage + javax.imageio.ImageIO
// Android actual — android.graphics.Bitmap + BitmapFactory

data class ImageData(
    val width: Int,
    val height: Int,
    val argb: IntArray,  // ARGB_8888, 行主序排列
)
```

## 合成方块

对于需要动态模型的特化方块，管线内置了合成器（`synthetic/` 子包）：

| 合成器                   | 方块     | 说明           |
| --------------------- | ------ | ------------ |
| SyntheticBed          | 床      | 4 朝向 + 双半组合  |
| SyntheticChest        | 箱子、末影箱 | 三部分（底/盖/锁）   |
| SyntheticSign         | 告示牌    | 立式/墙挂 + 8 朝向 |
| SyntheticBanner       | 旗帜     | 8朝向          |
| SyntheticSkull        | 头颅     | 含龙首          |
| SyntheticShulkerBox   | 潜影盒    | 4 朝向         |
| SyntheticConduit      | 潮涌核心   | 笼状结构         |
| SyntheticDecoratedPot | 饰纹陶罐   | 四面纹理         |
| SyntheticLectern      | 讲台     | 底座 + 书       |
| SyntheticFluid        | 水/熔岩   | 染色           |

## GLB 输出结构

```kotlin
data class GlbOutput(
    val positions: FloatArray,   // 顶点坐标 (x, y, z) × N
    val uvs: FloatArray,         // 纹理坐标 (u, v) × N
    val normals: FloatArray?,    // 法线 (nx, ny, nz) × N，可选
    val indices: IntArray,       // 三角形索引
    val atlasPng: ByteArray,     // PNG 编码的纹理图集
    val atlasWidth: Int,         // 图集宽度
    val atlasHeight: Int,        // 图集高度
)
```

输出的 GLB 文件：

- `scene 0` → `node 0` → `mesh 0`
- 单张纹理图集（`baseColorTexture`）
- `alphaMode = MASK` + `alphaCutoff = 0.05` + `doubleSided = true`
- 半透明方块（水、染色玻璃等）走独立 primitive
