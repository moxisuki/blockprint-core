# 蓝图解析 API

BlockPrint Core 的全部公开 API 参考。

> [English](BLUEPRINT_API_EN.md)

## 入口：LitematicReader

```kotlin
object LitematicReader {
    fun read(file: File): Litematic                 // 从文件
    fun read(inputStream: InputStream): Litematic   // 从输入流（自动关闭）
    fun read(bytes: ByteArray): Litematic           // 从字节数组（自动检测 gzip / JSON / NBT 结构）

    fun readLenient(file: File): Litematic          // 宽松模式（残缺文件不抛异常）
    fun readLenient(inputStream: InputStream): Litematic
    fun readLenient(bytes: ByteArray): Litematic

    fun detectFormat(file: File): SchematicFormat   // 预检，不抛异常
}
```

`read(bytes)` 与 `readLenient(bytes)` 的格式识别走**内容嗅探**（不依赖后缀）：

1. 首字节 `{`（0x7B）→ BuildingHelper JSON
2. 首字节 `0x1F 0x8B` → gzip 包裹的 NBT（自动解压）
3. 否则按 NBT 根结构区分 Litematica / Sponge v2 / Sponge v3 / vanilla Structure / PartialNbt / Unknown

### 严格 vs 宽松

| 模式 | 方法 | 无 Regions 时 | 适用场景 |
|------|------|:--:|------|
| 严格 | `read()` | 抛 `LitematicException` | 完整正规文件 |
| 宽松 | `readLenient()` | 生成全空气占位 Region | 残缺/调试/非标准 |

宽松模式下占位 Region 的尺寸按以下顺序自动识别：Litematica `Size` 复合 → Litematica `size` 列表 → Sponge v2 `Metadata/EnclosingSize` → Sponge v3 `Width+Height+Length`（Short）。调色板仅含 `minecraft:air`。

## 入口：BlueprintConverter

在四种已支持格式之间互转：以内存中的 `Litematic` 为中间表示。

```kotlin
object BlueprintConverter {
    fun convert(source: Litematic, target: SchematicFormat): ByteArray
    fun convert(source: ByteArray, target: SchematicFormat): ByteArray
    fun convert(source: InputStream, target: SchematicFormat): ByteArray
    fun convert(source: Litematic, target: SchematicFormat, out: OutputStream)   // 流式：不堆 output 字节
    fun convert(source: File, outFile: File, target: SchematicFormat = ...)
}
```

输出大的转换（≥ ~50 MB）推荐用 `OutputStream` 重载：写端不构造完整 NBT 树（Litematica target 还会消除 5 MB+ 的 packed `LongArray` 中间产物）。调用方负责关闭 `out`。

### 转换矩阵

| 源 \ 目标 | Litematica | Sponge | Structure | BuildingHelper |
|---|:---:|:---:|:---:|:---:|
| **Litematica**   | ✓ | ✓ | ✓ | ✓ |
| **Sponge**       | ✓ | ✓ | ✓ | ✓ |
| **Structure**    | ✓ | ✓ | ✓ | ✓ |
| **BuildingHelper** | ✓ | ✓ | ✓ | ✓ |

### 多 region 限制

除 `Litematica` 外，所有目标格式都不支持多 region；传入多 region 的 `Litematic` 会抛 `LitematicException`。要绕过：自己 `lit.copy(regions = listOf(lit.primaryRegion!!))`。

### 文件级便捷调用

```kotlin
// 按扩展名推断：in.litematic → out.schem（.schematic 也可）
BlueprintConverter.convert(File("in.litematic"), File("out.schem"))
```

不支持的扩展名抛 `LitematicException`。

### 支持的格式枚举

```kotlin
enum class SchematicFormat {
    Litematica,     // .litematic — 标准 Litematica 文件
    Sponge,         // .schematic / .schem — WorldEdit Schematic（写端输出 v3；读端支持 v2 + v3）
    Structure,      // .nbt — 原版 / 通用 NBT（`/structure save` 或类似 palette+blocks 布局）
    BuildingHelper, // .json — 建筑小帮手（识别按内容首字节 `{` + `statePosArrayList` 字段，不是后缀）
    PartialNbt,     // 通用 NBT — 有 Size 但无 Regions
    Unknown,        // 无法识别
}
```

> `SchematicFormat.fromExtension` 只用于**文件级** API（如 `convert(File, File)` 推断 target）的扩展名路由。`detectFormat` / `read` / `readLenient` 全部走**内容嗅探**，不依赖扩展名。BuildingHelper 即便被保存为 `.bin` 也能识别。

## Litematic

顶层文档模型。

```kotlin
data class Litematic(
    val name: String,                   // 蓝图名称
    val author: String,                 // 作者
    val description: String,            // 描述
    val version: Int?,                  // 文件格式版本（通常 5 或 6，Litematica；Sponge 文件=2/3）
    val minecraftDataVersion: Int?,     // MC 数据版本，如 3953 = 1.21
    val regions: List<LitematicRegion>, // 区域列表（按 NBT 插入顺序）
    val format: SchematicFormat,        // 来源格式
) {
    val primaryRegion: LitematicRegion?  // = regions.firstOrNull()
    fun blockCount(includeAir: Boolean = false): Int
}
```

### Sponge Schematic v2 vs v3 解析对照

写端只输出 v3，读端两种 spec 都能识别。`Litematic.version` 字段保留原 `Version`（v2 → 2，v3 → 3）。

| 字段 | v2（Sponge/老 WorldEdit） | v3（WorldEdit 7.3+） |
|---|---|---|
| Root 名 | `""` | `""` 但**根**下挂一个 `Schematic` 子 compound |
| `Version` | int = 2 | int = 3（位于 `Schematic` 内） |
| `Width` / `Height` / `Length` | int | **short**（注意 "Length" 不用 "Depth"） |
| `Offset` | compound `{x, y, z}` | **int[3]** |
| `Palette` | compound `{intString: BlockState}` | compound `{blockStateName: IntTag(paletteId)}` |
| `BlockData` / `Data` | 根下 byte[] varint | `Blocks` 下 `Data` byte[] varint |
| `BlockEntities` | 根下 `TileEntities` list | `Blocks` 下 `BlockEntities` list |
| `Metadata/EnclosingSize` | compound `{x, y, z}` | （**取消**）尺寸直接由 `Width/Height/Length` 提供 |
| `Metadata/WorldEdit` | （无） | compound `{Version, EditingPlatform, Origin, Platforms}` |
| 包装 | 直接顶层 | 根 compound 内嵌 `"Schematic": { ... }` |

## LitematicRegion

单个区域，持有稠密方块数据。

```kotlin
class LitematicRegion(
    val name: String,           // 区域名
    val width: Int,             // X 尺寸
    val height: Int,            // Y 尺寸（高度）
    val depth: Int,             // Z 尺寸
    val position: Position,     // 世界中的原点坐标
    val palette: BlockPalette,  // 方块状态调色板
    blocks: IntArray? = null,   // 稠密方块数组（调色板索引，0 = 空气）
)
```

### 方块访问

```kotlin
// 按坐标获取原始调色板索引
fun getBlock(x: Int, y: Int, z: Int): Int

// 按坐标获取解析后的 BlockState
fun blockAt(x: Int, y: Int, z: Int): BlockState

// 判断空气
fun isAir(x: Int, y: Int, z: Int): Boolean

// 稠密数组（y-major: index = y * W * D + z * W + x）
val rawBlocks: IntArray

// 三维坐标 → 一维索引
fun rawIndex(x: Int, y: Int, z: Int): Int
```

### 遍历示例

```kotlin
// 逐坐标遍历
for (y in 0 until region.height) {
    for (z in 0 until region.depth) {
        for (x in 0 until region.width) {
            if (!region.isAir(x, y, z)) {
                val block = region.blockAt(x, y, z)
            }
        }
    }
}

// 高效批量遍历
val raw = region.rawBlocks
val palette = region.palette
for (i in raw.indices) {
    val idx = raw[i]
    if (idx != 0) {
        val bs = palette[idx]
        // 处理方块...
    }
}
```

## BlockPalette

区域调色板，索引 ↔ 方块状态。

```kotlin
data class BlockPalette(val entries: List<BlockState>) {
    val size: Int                      // 条目数
    val bitsPerBlock: Int              // ceil(log2(size))，位打包宽度
    operator fun get(index: Int): BlockState
}
```

## BlockState

单一方块状态。

```kotlin
data class BlockState(
    val name: String,                       // "minecraft:oak_planks"
    val properties: Map<String, String>?,   // { "axis": "y" }，无属性时为 null
) {
    // toString() → "minecraft:oak_planks"
    //          或 "minecraft:oak_log[axis=y]"
}
```

## Position

整数三维坐标。

```kotlin
data class Position(val x: Int, val y: Int, val z: Int) {
    companion object {
        val ZERO = Position(0, 0, 0)
    }
}
```

## MaterialList

方块材料统计。

```kotlin
class MaterialList : LinkedHashMap<String, Int>() {
    companion object {
        fun from(litematic: Litematic, includeAir: Boolean = false): MaterialList
    }
    fun toSortedByCount(): List<Pair<String, Int>>   // 按数量降序
    fun toSortedList(): List<Pair<String, Int>>      // 按名称字母序
}
```

## MinecraftVersions

数据版本号 → 版本字符串。

```kotlin
MinecraftVersions[3578]  // "1.20.2"
MinecraftVersions[3953]  // "1.21"
MinecraftVersions.size   // 811（1.8 至今）

// 版本表编译期内嵌，零运行时开销
// 更新脚本：python scripts/update_versions.py
```

## 坐标系统

采用 Minecraft 原生坐标系：

```
x → 水平（东/西）
y → 垂直（上/下）
z → 水平（南/北）
```

方块内存布局为 y-major / z-middle / x-minor（与 Minecraft NBT 一致）：

```
rawIndex(x, y, z) = y × (width × depth) + z × width + x
```

## NBT 底层访问

```kotlin
val doc = NbtDocument.read(File("data.nbt"))

// 13 种子类型
// EndTag, ByteTag, ShortTag, IntTag, LongTag, FloatTag, DoubleTag,
// ByteArrayTag, StringTag, ListTag, CompoundTag, IntArrayTag, LongArrayTag

val size = doc.root.get("size") as? NbtTag.ListTag
val palette = doc.root.get("palette") as? NbtTag.ListTag
```

## 异常

```kotlin
class LitematicException(message: String, cause: Throwable? = null)
    : RuntimeException
```

触发场景：缺少必需 NBT 字段、尺寸无效、BlockStates 数组长度不匹配等。

## 架构

```
LitematicReader                      ← 公开入口
    ├── 内容嗅探：首字节 `{` → JSON 走 BuildingHelperParser
    │            0x1F 0x8B → gzip 解压 → NBT
    │            其它 → NBT
    ├── 严格解析 parse()
    │     └── LitematicParser        ← NBT → Litematic / Region / Palette
    │           ├── Sponge v2 解析（Palette + BlockData 在根，int 维度）
    │           ├── Sponge v3 解析（root 下的 "Schematic" compound，short 维度，name→Int palette）
    │           ├── Structure 稀疏 → 稠密转换
    │           └── Building Helper JSON 解析
    │
    ├── 宽松解析 parseLenient()      ← 残缺文件 → 占位 Region
    │     └── readSizeLenient()：Size / size / Metadata.EnclosingSize / Width+Height+Length
    │
    └── NbtReader + NbtTag           ← 底层 NBT（零依赖，gzip 自动检测）

GLB 管线（glb/ 子包）
    ├── LitematicToGlb               ← 公开入口
    ├── GlbWriter                    ← GLB 序列化
    ├── MeshBuilder                  ← 顶点/UV/法线
    ├── ModelResolver                ← 模型解析
    ├── TexturePacker                ← 纹理打包
    ├── ImageBackend (expect)        ← 跨平台图片
    ├── FileAccessor                 ← 跨平台文件抽象
    └── synthetic/                   ← 合成方块
```
