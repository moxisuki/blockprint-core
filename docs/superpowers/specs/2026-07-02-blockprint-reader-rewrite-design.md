# BlockPrint 1.0 — Blueprint Reader 重写设计

**日期**：2026-07-02
**分支**：`rewrite/blueprint-reader`（从 `master` @ `7247540` 开出）
**目标版本**：1.0.0（首次稳定版；API 破坏可接受）
**作者**：BlockPrint Core 维护者

---

## 0. 摘要

`blockprint-core` 当前 0.2.2，使用 `Litematic*` 命名（`LitematicReader` / `Litematic` / `LitematicRegion` / `LitematicToGlb` / `LitematicException`），与项目自身名称 `blockprint` 不一致；公共 API 与项目内部包路径也已经存在命名混用（`BlueprintConverter` 已用 `Blueprint` 前缀，但同目录下仍是 `Litematic*`）。0.x 阶段做这次统一重写最经济。

重写目标：

1. **命名贯通**：`Litematic*` → `BlockPrint*` 统一前缀
2. **包结构重组**：按职责分包（`api/` / `model/` / `nbt/` / `format/` / `glb/` / `exceptions/` / `internal/`）
3. **冷解析吞吐量优化**：消除全量 `readBytes()`、特化 BlockStatePacker.unpack 热路径、共享 VarInt codec
4. **Peek 功能**：新增 `BlockPrintReader.peek()`，只读文件头、不解析 Regions/BlockStates
5. **错误处理清晰**：公开 API 单一异常类型 `BlockPrintException`；NBT / GLB 内部异常用 `cause` 传递

GLB 子树功能**不优化**，仅做命名 + 结构归位（`glb/writer/` / `glb/mesh/` / `glb/model/` / `glb/texture/` / `glb/platform/` / `glb/internal/` / `glb/synthetic/`）。

---

## 1. 架构与包布局

```
io.github.moxisuki.blockprint.core/
│
├── api/                                    公开入口（thin façade）
│   ├── BlockPrintReader.kt                 (原 LitematicReader)
│   │   - read / readLenient / peek / detectFormat
│   ├── BlockPrintConverter.kt              (原 BlueprintConverter)
│   │   - convert (Litematic↔Sponge↔Structure↔BuildingHelper)
│   ├── BlockPrintToGlb.kt                  (原 LitematicToGlb)
│   │   - convert / convertToBytes
│   └── GlbExportOptions.kt                 (从 glb/ 提升, 因属 public surface)
│
├── model/                                  纯数据 + 不可变值对象
│   ├── BlockPrintDocument.kt               (原 Litematic)
│   ├── BlockPrintRegion.kt                 (原 LitematicRegion)
│   ├── BlockPrintSummary.kt                ★ 新增: Peek 返回类型
│   ├── BlockPalette.kt
│   ├── BlockState.kt
│   ├── Position.kt
│   ├── MaterialList.kt
│   ├── MinecraftVersions.kt
│   └── SchematicFormat.kt
│
├── nbt/                                    NBT 序列化层
│   ├── NbtReader.kt
│   ├── NbtWriter.kt
│   ├── NbtDocument.kt
│   ├── NbtTag.kt
│   └── NbtTagType.kt
│
├── format/                                 格式特定的 reader/writer
│   ├── litematica/
│   │   ├── LitematicaReader.kt             (拆自 LitematicParser)
│   │   └── LitematicaWriter.kt
│   ├── sponge/
│   │   ├── SpongeReader.kt                 (拆自 LitematicParser, 容纳 v2+v3)
│   │   └── SpongeWriter.kt
│   ├── structure/
│   │   ├── StructureReader.kt              (拆自 LitematicParser)
│   │   └── StructureWriter.kt
│   ├── buildinghelper/
│   │   ├── BuildingHelperReader.kt         (拆自 BuildingHelperParser)
│   │   └── BuildingHelperWriter.kt
│   └── FormatDetector.kt                   (原 SchematicFormat.fromNbtRoot 抽出)
│
├── glb/                                    GLB 3D 模型生成 (按职责再分)
│   ├── writer/                             GlbWriter, GlbOutput
│   ├── mesh/                               MeshBuilder, MeshSink, RawMesh
│   ├── model/                              ModelResolver, CreateModObjAdapter
│   ├── texture/                            TexturePacker
│   ├── platform/                           expect ImageBackend, FileAccessor, OffHeapBuf
│   ├── internal/                           JsonParser, ObjParser
│   └── synthetic/                          12 个合成方块模型
│
├── exceptions/
│   ├── BlockPrintException.kt              (原 LitematicException)
│   ├── NbtFormatException.kt               (新增, NBT 层错误细分)
│   └── GlbExportException.kt               (新增, GLB 层错误细分)
│
└── internal/                               跨层共享的低层工具
    ├── BlockStatePacker.kt                 (位打包, 重用)
    ├── VarInt.kt                           (抽自 Sponge 解析, 共享给读/写)
    ├── PackedBlocks.kt                     (新增: 流式解包缓冲)
    └── NbtAccessors.kt                     (新增: 共享 readInt3/readStringOrEmpty/readIntOrNull)
```

### 平台子包

- `androidMain/.../glb/platform/ImageBackend.android.kt` 等不变
- `jvmMain/.../glb/platform/ImageBackend.jvm.kt` 等不变
- 业务代码（api/、model/、nbt/、format/）放 `commonMain`，无 expect/actual

### 跨包依赖规则

| 包 | 可依赖 |
|---|---|
| `api/` | `model/`, `nbt/`, `format/`, `glb/`, `exceptions/`, `internal/` |
| `format/` | `model/`, `nbt/`, `exceptions/`, `internal/` |
| `glb/` | `model/`, `nbt/`, `exceptions/`, `internal/` |
| `nbt/` | `exceptions/`, `internal/` |
| `model/` | （不依赖其他业务包） |
| `internal/` | （不依赖业务包） |
| `exceptions/` | （不依赖任何包） |

依赖单向、无环，IDE 静态可校验。

---

## 2. 重命名映射 + 公共 API 表面

### 类/文件重命名映射

| 旧（路径 = `io.github.moxisuki.blockprint.core`） | 新 | 备注 |
|---|---|---|
| `LitematicReader` (object) | `BlockPrintReader` | 移入 `api/` |
| `Litematic` (data class) | `BlockPrintDocument` | 移入 `model/` |
| `LitematicRegion` (class) | `BlockPrintRegion` | 移入 `model/` |
| `LitematicException` | `BlockPrintException` | 移入 `exceptions/` |
| `BlueprintConverter` (object) | `BlockPrintConverter` | 移入 `api/`（统一前缀） |
| `LitematicToGlb` (object) | `BlockPrintToGlb` | 移入 `api/` |
| `LitematicParser` (internal object) | 拆分为 4 个 format/*Reader | 详见下表 |
| `BuildingHelperParser` (internal) | `BuildingHelperReader` | 移入 `format/buildinghelper/` |
| `LitematicWriter` (internal) | `LitematicaWriter` | 移入 `format/litematica/`（复数 → 单数） |
| `SpongeWriter` (internal) | `SpongeWriter` | 移入 `format/sponge/` |
| `StructureWriter` (internal) | `StructureWriter` | 移入 `format/structure/` |
| `BuildingHelperWriter` (internal) | `BuildingHelperWriter` | 移入 `format/buildinghelper/` |

### `LitematicParser` 拆分

当前 `LitematicParser.kt`（648 行）一个文件做 4 件事。重写为 4 个独立 reader + 一个共享 detector：

| 旧逻辑 | 新位置 |
|---|---|
| `parse(root)` Litematica 分支 | `format/litematica/LitematicaReader` |
| `parseSponge(root)` v2 | `format/sponge/SpongeReader.parseV2` |
| `parseSpongeV3(inner)` | `format/sponge/SpongeReader.parseV3` |
| `parseStructure(root)` | `format/structure/StructureReader.parse` |
| `BuildingHelperParser.parse(bytes)` | `format/buildinghelper/BuildingHelperReader.parse` |
| `SchematicFormat.fromNbtRoot(root)` | `format/FormatDetector.detect(root)` |
| 共享的 `readInt3` / `readStringOrEmpty` / `readIntOrNull` | `internal/NbtAccessors.kt`（新增） |

### 不重命名的类型

| 名称 | 理由 |
|---|---|
| `BlockPalette`, `BlockState`, `Position` | 已是通用名 |
| `SchematicFormat`（enum） | 是 spec 名（Litematica/Sponge/Structure/BuildingHelper 4 种外部格式的并称） |
| `MaterialList` | 通用名 |
| `MinecraftVersions` | 通用名 |
| `NbtReader`, `NbtWriter`, `NbtDocument`, `NbtTag`, `NbtTagType` | 移入 `nbt/` 子包，类名不动 |
| `MaterialList.from` / `.toSortedByCount` | 签名不变 |
| `BlockPalette.bitsPerBlock` / `operator get` | 不变 |

### 公共 API 表面（最终）

```kotlin
// api/BlockPrintReader.kt
object BlockPrintReader {
    fun read(file: File): BlockPrintDocument
    fun read(input: InputStream): BlockPrintDocument
    fun read(bytes: ByteArray): BlockPrintDocument

    fun readLenient(file: File): BlockPrintDocument
    fun readLenient(input: InputStream): BlockPrintDocument
    fun readLenient(bytes: ByteArray): BlockPrintDocument

    fun peek(file: File): BlockPrintSummary                  ★ 新
    fun peek(input: InputStream): BlockPrintSummary           ★ 新
    fun peek(bytes: ByteArray): BlockPrintSummary             ★ 新

    fun detectFormat(file: File): SchematicFormat
    fun detectFormat(input: InputStream): SchematicFormat
    fun detectFormat(bytes: ByteArray): SchematicFormat
}

// api/BlockPrintConverter.kt
object BlockPrintConverter {
    fun convert(source: BlockPrintDocument, target: SchematicFormat): ByteArray
    fun convert(source: ByteArray, target: SchematicFormat): ByteArray
    fun convert(source: InputStream, target: SchematicFormat): ByteArray
    fun convert(source: BlockPrintDocument, target: SchematicFormat, out: OutputStream)
    fun convert(source: File, outFile: File, target: SchematicFormat = ...)
}

// api/BlockPrintToGlb.kt
object BlockPrintToGlb {
    fun convert(doc: BlockPrintDocument, assetsDirs: List<Path>, outFile: File,
                options: GlbExportOptions = GlbExportOptions.Default): File
    fun convertToBytes(doc: BlockPrintDocument, assetsDirs: List<Path>,
                       options: GlbExportOptions = ...,
                       progress: ((Float) -> Unit)? = null): ByteArray
    // ... 其它现有重载, 签名不变 (只换类型)
}

// model/BlockPrintDocument.kt
data class BlockPrintDocument(
    val minecraftDataVersion: Int?,
    val version: Int?,
    val name: String,
    val author: String,
    val description: String,
    val regions: List<BlockPrintRegion>,
    val format: SchematicFormat = SchematicFormat.Unknown,
) {
    val primaryRegion: BlockPrintRegion?
    fun blockCount(includeAir: Boolean = false): Int
}

// model/BlockPrintSummary.kt                                          ★ 新
data class BlockPrintSummary(
    val format: SchematicFormat,    // 内容嗅探结果
    val name: String,
    val author: String,
    val description: String,
    val version: Int?,
    val minecraftDataVersion: Int?,
)
```

### 不变项

- `MaterialList` API 不变
- `BlockPalette` / `BlockState` / `Position` / `MinecraftVersions` 类名 + 字段不变
- `SchematicFormat` enum 值不变
- 异常类型 `BlockPrintException(message, cause)` 构造器签名不变（`RuntimeException` 子类，message 兼容现有 i18n 字符串）
- GLB 子树的方法签名几乎不变（只换 `Litematic` → `BlockPrintDocument`）
- `NbtReader.readRoot` / `NbtWriter.writeRoot` 等 NBT API 签名不变（仅换包路径）

---

## 3. 冷解析吞吐量优化

### 优化目标 & 指标

- **目标**：从字节/流到 `BlockPrintDocument` 的总耗时下降
- **方法**：先在 `jvmTest` 加一组微基准（手写 wall-clock，无 jmh 依赖），覆盖 5MB / 50MB / 200MB 三个尺寸的 Litematica + Sponge v3 + Structure 样本，记录当前基线
- **接受门槛**：Litematica（LongArray 解包主导）≥ **1.4×** 加速；Sponge v3（varint 主导）≥ **1.6×** 加速；Structure 持平或更快
- 任何改动后跑同一基准，未达标不合并

### 优化点（按 ROI 排序）

#### 1. 流式入口：消灭 `readBytes()` — 高 ROI

**现状**：`BlockPrintReader.read(InputStream)` 调 `stream.readBytes()`，把整文件装进 `ByteArray` 再交给 `NbtReader.readRoot(ByteArray)`。一个 200 MB 文件 → 一份 200 MB 堆内存 + 一份 200 MB 解压后字节 = 400 MB 峰值。

**改动**：
- 新增 `NbtReader.readRoot(InputStream): CompoundTag`，内部沿用现有 `DataInputStream` 路径，仅去掉"先 readBytes"一步
- `BlockPrintReader.read(InputStream)` 直接 `NbtReader.readRoot(input)` → 分发到 `FormatDetector.detect(root)` → 对应 `*Reader.parse(root)`。gzip 检测从字节级下沉到 `PushbackInputStream` 嗅 2 字节
- `BlockPrintReader.read(File)` 改用 `FileInputStream` 路径而非 `inputStream().readBytes()`，触发 JIT 后小文件 IO 差异可忽略

**收益**：大文件堆峰值 -50%；冷启动少一次大数组拷贝。

#### 2. BlockStatePacker.unpack — 中高 ROI

**现状**：`BlockStatePacker.unpack(longs, nbits, w, h, d)` 逐 cell 移位 + mask，结果 `IntArray` 预分配。

**改动**：
- 抽出 `internal/PackedBlocks.kt`，把 `unpack` 改为对 nbits=4 / 8 / 16 的特化路径（这些是 Litematica 实际用到的常见宽度），用 Long 批量掩码 + 一次写入 4/8/16 cell
- nbits≤8 时一个 Long 直接产 8/4 cell，循环体从 `nCells` 次降到 `nCells/8` 次
- 通用 nbits 路径保留 fallback（用于 12/15/16 等罕见宽度）
- 单元测试：旧路径 vs 新路径对相同输入产出 byte-equal `IntArray`（已有 `BlockStatePackerPackTest` 验证行为不变）

**收益**：Litematica 解析的位解包段是唯一热点（其他都是 O(1) 元数据），预期 1.3-1.5×。

#### 3. 共享 VarInt 编解码 — 中 ROI

**现状**：`LitematicParser.readVarInt` 是 `private`，仅 Sponge 解析用；`SpongeWriter` 写时也内联了 varint encode。

**改动**：
- 抽到 `internal/VarInt.kt`：暴露 `decode(src: ByteArray, startPos: Int): Pair<Int, Int>` 和 `encode(out: ByteArrayOutputStream, value: Int)`
- 改 `SpongeReader` + `SpongeWriter` 共用。Reader 多分配：当前每次 Sponge 解析都新建一份完整 `IntArray(total)` 写入。改为单次 preallocate + 顺序填充（已是如此，但去掉临时 `Pair` boxing）
- 加 micro-bench: 100k varints 顺序读

**收益**：Sponge v3 解析主路径加速 + 一份代码维护。

#### 4. FormatDetector — 低 ROI 但必做

**现状**：`SchematicFormat.fromNbtRoot` 返回 enum 之前做了 4 个 `contains` + 几个 `as?` 检查。开销可忽略，但每次 `read` 都跑。

**改动**：
- 抽到 `format/FormatDetector.kt`：`fun detect(root: NbtTag.CompoundTag): SchematicFormat`
- 顺序按 NBT 文件实际比例优化：先 `Regions`（最常见）→ `Schematic.Version=3`（次常见）→ `palette+blocks` → `Metadata/EnclosingSize` → `Size/size` → `Unknown`
- 不分配中间对象，全部走 `as?` 模式匹配
- 这条本身不显著，但抽出来使 NbtReader → FormatDetector → *Reader 三段各自可测

#### 5. Structure 稀疏→稠密 — 低 ROI

**现状**：`parseStructure` 一次 `IntArray(w*h*d)` 分配，然后 `O(n_blocks)` 随机写。

**改动**：当前实现已经是单次分配 + 顺序遍历 blocks 列表写 dense 数组。最优做法是**不变**——除非 sparse→dense 之后还要过一遍做位打包，那是写出端的事，不在 read 优化范围。仅在新加基准里加测试用例。

#### 6. BlockState 名去重 — **不做**

理由：String interning 在 JVM 已有 StringTable 自动处理；在 Android ART 上 intern 成本反而高（首次调用慢）。跳过。后续若 profile 显示 `parseBlockState` 仍是热点再考虑。

### 基准与门禁

- 在 `src/jvmTest/.../benchmark/` 新增 `BlockPrintParsingBenchmark.kt`（手写 wall-clock，5 轮取中位数）
- 3 个 fixture：5MB / 50MB / 200MB 的 Litematica（用现有 `test/create/assets/create/lang/` 已有大文件，或脚本生成）
- 每个 fixture 跑 Litematica / Sponge v3 / Structure 三个路径
- `gradle jvmTest` 跑测试；基准手动跑（不接 CI，避免噪音）
- 优化 PR 必须附带 before/after 数字，未达 1.4× / 1.6× 不合并

### 性能改动不影响正确性

- 现有所有 `src/jvmTest/.../*.kt` 测试在新代码下必须 byte-equal 通过
- 新增 `PackedBlocksUnpackParityTest`：旧 `BlockStatePacker.unpack` vs 新特化路径在 nbits ∈ {2,3,4,5,6,7,8,9,10,12,15,16}、不同 (w,h,d) 组合下产出相同 IntArray
- 新增 `VarIntCodecParityTest`：随机 varint 序列 encode → decode round-trip

---

## 4. Peek 功能设计

### API

```kotlin
// model/BlockPrintSummary.kt
data class BlockPrintSummary(
    val format: SchematicFormat,
    val name: String,
    val author: String,
    val description: String,
    val version: Int?,
    val minecraftDataVersion: Int?,
)

// api/BlockPrintReader.kt
fun peek(file: File): BlockPrintSummary
fun peek(input: InputStream): BlockPrintSummary
fun peek(bytes: ByteArray): BlockPrintSummary
```

三重重载与 `read` 行为一致（gzip 自动解压、BuildingHelper 嗅 `{`、流自动关闭）。

### Peek 行为契约

| 路径 | Peek 读取 | Peek 不读取 |
|---|---|---|
| Litematica | `MinecraftDataVersion` / `Version` / `Name` / `Author` / `Description` | `Regions.*` / `BlockStates` / `BlockStatePalette` |
| Sponge v2 | `Version` / `DataVersion` / `Metadata.{Name,Author,Description}` | `Palette` / `BlockData` / 实际 varint 字节 |
| Sponge v3 | `Schematic.{Version, DataVersion}` + `Metadata.{Name,Author,Description}` | `Schematic.Blocks.{Palette,Data,BlockEntities}` |
| Structure | `DataVersion` / `name` / `author` / `Description` / `size` | `palette` 条目 / `blocks` 条目 |
| BuildingHelper | JSON 顶层 `formatVersion` / `name` / `version`（如有） | `statePosArrayList` / `statelist` |
| PartialNbt | 根 `Size` / `size` / `MinecraftDataVersion` | （已无 body） |
| Unknown | 返回 `name=""` / `author=""` / `description=""` / `version=null` / `minecraftDataVersion=null` / `format=Unknown` | — |

**关键不变量**：Peek 路径下不调用任何 `unpack`、不分配 `IntArray(blocks)`、不解析 varint、不解析 BuildingHelper 的 `statePosArrayList`。

### 实现

```kotlin
// format/FormatDetector.kt  —— 已有 detect()，peek 复用
// format/litematica/LitematicaReader.kt
internal object LitematicaReader {
    fun parse(root: NbtTag.CompoundTag): BlockPrintDocument { ... }  // 已有
    fun readHeader(root: NbtTag.CompoundTag): BlockPrintSummary {
        return BlockPrintSummary(
            format = SchematicFormat.Litematica,
            name = readStringOrEmpty(root, "Name"),
            author = readStringOrEmpty(root, "Author"),
            description = readStringOrEmpty(root, "Description"),
            version = readIntOrNull(root, "Version"),
            minecraftDataVersion = readIntOrNull(root, "MinecraftDataVersion"),
        )
    }
}

// format/sponge/SpongeReader.kt  类似
// format/structure/StructureReader.kt  类似
// format/buildinghelper/BuildingHelperReader.kt  类似
```

`BlockPrintReader.peek(bytes)`：

```kotlin
fun peek(bytes: ByteArray): BlockPrintSummary {
    if (bytes.isNotEmpty() && bytes[0] == '{'.code.toByte()) {
        return BuildingHelperReader.readHeader(bytes)
    }
    val root = NbtReader.readRoot(bytes)   // 仍然全量 root, 但 root 不包含 BlockStates
    return when (FormatDetector.detect(root)) {
        SchematicFormat.Litematica -> LitematicaReader.readHeader(root)
        SchematicFormat.Sponge -> SpongeReader.readHeader(root)   // 内部判 v2/v3
        SchematicFormat.Structure -> StructureReader.readHeader(root)
        SchematicFormat.PartialNbt, SchematicFormat.Unknown ->
            BlockPrintSummary(format = FormatDetector.detect(root), ...)
        SchematicFormat.BuildingHelper -> error("BuildingHelper routed by byte sniff above")
    }
}
```

> **重要细节**：NbtReader 仍要把根 compound 全部读出（因为根里有 `Name` / `Author` / `MinecraftDataVersion` 等）。但根 compound 不包含 `Regions` / `BlockStates` / `Palette` 的内容（只包含它们作为 tag 引用）。**关键优化在 Litematica reader：进入 `readHeader` 路径时**完全跳过** `Regions` compound 子树读**。这要求 NbtReader 支持"惰性"或"短路"读。

### NbtReader 短路读取 — 性能关键

新增 `NbtReader.readRootHeader(bytes): NbtTag.CompoundTag`：

- 解析根 tag id + 根 name（标准 NBT 头）
- 进入根 compound 的 entry 循环时，**只读** 已知浅层字段（按 byte 模式匹配 tag id + 跳过未知长度的子 compound）
- 对根 compound 中不感兴趣的大型子树（如 `Regions` / `Schematic.Blocks`），**调用 `dis.skipNBytes(n)` 跳过**而非递归读

> 短路的精确字节数可以预先算：每个 NBT 字段都有显式长度（List 用 int 长度，Compound 用 End tag 终止，ByteArray/IntArray/LongArray/String 用前缀长度）。NbtReader 现有 `readTagPayload` 可以加一个 `skipPayload` 变体。

这条改了之后：`peek()` 对 200 MB 的 Litematica 只读几百字节头 + 跳过 `Regions` 整子树，毫秒级。

### BuildingHelper 短路

`BuildingHelperReader.readHeader(bytes)` 只解 JSON 顶层几个字段。BuildingHelper 的 JSON 顶层很小（`name` / `formatVersion` / `version`），即便不解出 `statePosArrayList`，JSON 仍要扫一遍找到顶层 key。**优化**：先用 `indexOf("\"statePosArrayList\"")` 找 marker，找到后用 `substring(0, marker)` 取前半段（顶层 key 都在前），JSON 解析这个子串即可。这条要测，如果 JSON 顶层小、`statePosArrayList` 在末尾，多数情况下不显著。**先实现不做这层优化**，测完再说。

### 错误处理

- `peek` 抛 `BlockPrintException`（同 `read` 行为），不返回 `null`
- 对完全无法解析的字节：抛 `BlockPrintException("Not a recognized schematic format")`
- 对 `Unknown` 格式：返回 `BlockPrintSummary(format=Unknown, ...empty...)`，不抛
- 对部分字段缺失：返回空串/null，不抛

### 测试

- `BlockPrintReaderPeekTest.kt`：覆盖每种格式（Litematica / Sponge v2 / Sponge v3 / Structure / BuildingHelper / PartialNbt / Unknown / gzip 包裹 / 流式输入 / 文件输入 三种入口）
- `BlockPrintReaderPeekNoAllocationTest.kt`：对 200 MB fixture，断言 peek 路径下 `IntArray(>1MB)` 分配次数为 0
- `NbtReaderSkipSubtreeTest.kt`：单元测 `skipPayload` 的字节数正确性（与 `readPayload` 产出位等）

---

## 5. 错误处理

### 异常层级

```
RuntimeException
├── BlockPrintException              ← 公开 API 唯一异常类型
│   ├── (cause: NbtFormatException)    用户传的字节根本不是 NBT
│   ├── (cause: GlbExportException)   GLB 转换内部失败
│   └── (cause: IllegalArgumentException 等)
│
├── NbtFormatException               ← nbt/ 层内部异常（可被包外 catch）
│   "tag id 14 not recognized"        不暴露给业务
│   "compound at offset 0x123 missing End tag"
│
└── GlbExportException               ← glb/ 层内部异常
    "texture pack overflow"
    "model resolver: missing asset for minecraft:stone"
```

**公开 API 异常统一为 `BlockPrintException` 一类**。`NbtFormatException` / `GlbExportException` 用作 `cause`，调用方需要细分时再 `unwrap`（但不应该需要）。这样下游 `catch (e: BlockPrintException)` 永远抓得到所有错误，类型不爆炸。

### `BlockPrintException` 形状

```kotlin
// exceptions/BlockPrintException.kt
class BlockPrintException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
```

签名与原 `LitematicException` 完全相同 —— 现有 i18n 字符串（"建筑小帮手解析失败: ${e.message}"、"Litematic root is missing 'Regions' compound" 等）原样保留，英文 README 用户无感。

### 触发场景（典型）

| 场景 | 抛出位置 | 类型 | 消息 |
|---|---|---|---|
| 字节根本不是 NBT | `NbtReader.readRoot` | `NbtFormatException` | `"Expected root NBT tag to be COMPOUND (10), got End"` |
| 空文件 / 1 字节 | `NbtReader.readRoot` | `NbtFormatException` | `"EOF at offset 0"` |
| varint 截断 | `SpongeReader` | `NbtFormatException` | `"Sponge: varint truncated at byte 12345"` |
| Regions 缺失 | `LitematicaReader` | `BlockPrintException` | `"Litematic root is missing 'Regions' compound"` |
| 区域尺寸无效 | `LitematicaReader` | `BlockPrintException` | `"Region 'foo' has invalid dimension: 0x10x10"` |
| BlockStates 长度不匹配 | `LitematicaReader` | `BlockPrintException` | `"BlockStates length 1234 does not match ..."` |
| 调色板索引越界 | `BlockPrintRegion.setBlock` | `BlockPrintException` | `"Palette index 999 out of range for palette of size 16"` |
| Peek 字节无法识别 | `BlockPrintReader.peek` | `BlockPrintException` | `"Not a recognized schematic format"` |
| 写入 GLB 时纹理溢出 | `TexturePacker` | `GlbExportException` | `"Texture atlas overflow: needed 4096x4096, max 2048x2048"` |
| 转换矩阵不支持 | `BlockPrintConverter` | `BlockPrintException` | `"Cannot convert multi-region document to Sponge format (single-region only)"` |

### 包装与传播

- `NbtFormatException` 抛出时附带 `offset` 信息（用 NbtReader 当前游标），便于排查坏文件
- `BlockPrintReader.read` / `peek` / `convert` 边界做最后一次 `try { ... } catch (e: NbtFormatException) { throw BlockPrintException("NBT parse failed at offset 0x${e.offset}", e) }` 包装，把 NBT 细节包到 message，cause 保留原始
- GLB 路径类似：`catch (e: GlbExportException) { throw BlockPrintException("GLB export failed: ${e.message}", e) }`
- 不在每层重复包装，**只在公开 API 边界统一包一次**

### 不做的事

- 不引入 sealed class 异常族（Kotlin 里无 checked exception，sealed 也无 catch 时分类型优势）
- 不引入 Result<T, E>（保持抛异常的 Kotlin 惯例）
- 不引入 retry / fallback 机制（解析是确定性的，要么对要么错）

### 测试

- `BlockPrintReaderErrorMessagesTest.kt`：覆盖每条触发场景的 message 文本快照（防止重构改坏 i18n / 调试信息）
- `BlockPrintExceptionUnwrappingTest.kt`：构造带 cause 的异常，断言 `e.cause` 是预期的内部类型
- 现有 `LitematicParserNegativeSizeTest` / `LitematicParserRegionsWithSpongeCompatTest` 改名 + 更新 import，行为不变

---

## 6. 测试策略

### 现有测试迁移

| 旧文件 | 新文件 | 改动 |
|---|---|---|
| `LitematicParserNegativeSizeTest.kt` | `format/litematica/LitematicaReaderTest.kt` | 重命名 + 改 import |
| `LitematicParserRegionsWithSpongeCompatTest.kt` | 拆入 `LitematicaReaderTest` + `format/sponge/SpongeReaderTest.kt` | 行为按 Sponge 兼容性断言分到对应 reader 测试 |
| `BHParserStandardFormatTest.kt` | `format/buildinghelper/BuildingHelperReaderTest.kt` | 改 import |
| `BlueprintConverterMatrixTest.kt` | `api/BlockPrintConverterTest.kt`（在 `api/` 下，因 convert 是 API 入口） | 类名 + 路径改 |
| `BlueprintConverterTest.kt` | 同上 | 同上 |
| `SchematicFormatFromExtensionTest.kt` | `format/FormatDetectorTest.kt`（fromNbtRoot 部分并入） | 拆 NBT root 探测 vs 扩展名路由两组测试 |
| `NbtWriterTest.kt` | `nbt/NbtWriterTest.kt` | 仅改包路径 |
| `internal/format/BuildingHelperWriterTest.kt` | `format/buildinghelper/BuildingHelperWriterTest.kt` | 改路径 |
| `internal/format/LitematicWriterTest.kt` | `format/litematica/LitematicaWriterTest.kt` | 改路径 |
| `internal/format/SpongeWriterTest.kt` | `format/sponge/SpongeWriterTest.kt` | 改路径 |
| `internal/format/StructureWriterTest.kt` | `format/structure/StructureWriterTest.kt` | 改路径 |
| `internal/BlockStatePackerPackTest.kt` | `internal/BlockStatePackerTest.kt` | 改路径 |
| `glb/*` (20+ 文件) | `glb/writer/` / `glb/mesh/` / 等 5 个子包 | 改 import + 子包归位；功能测试断言不变 |

GLB 测试**不重写**——只改 import 和包路径。GLB 行为是 1.0 承诺的一部分，测试断言是合同。

### 新增测试

#### Peek

- `BlockPrintReaderPeekTest.kt`：每种格式（Litematica / Sponge v2 / Sponge v3 / Structure / BuildingHelper / PartialNbt / Unknown）× 3 种入口（`File` / `InputStream` / `ByteArray`）× gzip 包裹 = 至少 24 case
- `BlockPrintReaderPeekNoAllocationTest.kt`：用 200 MB fixture 跑 peek，断言 `IntArray` 分配 ≤ 0、`LongArray` 分配 ≤ 1（仅 NbtReader 内部）
- `NbtReaderSkipSubtreeTest.kt`：构造手工 NBT 字节，验证 `skipPayload` 与 `readPayload` 跳过字节数一致；对含嵌套 List/Compound/数组的子树都覆盖

#### 性能（基准，非 CI）

- `src/jvmTest/.../benchmark/BlockPrintParsingBenchmark.kt`（手动跑，CI 跳过）：
  - 3 fixture: 5MB / 50MB / 200MB
  - 3 格式: Litematica / Sponge v3 / Structure
  - 5 轮取中位数 wall-clock
  - 优化 PR 必须附 before/after 数字，模板化输出便于 diff

#### 解析正确性

- `PackedBlocksUnpackParityTest.kt`：旧 `BlockStatePacker.unpack` 与新 `PackedBlocks.unpack` 在 nbits ∈ {2,3,4,5,6,7,8,9,10,12,15,16} × 多组 (w,h,d) 下 byte-equal
- `VarIntCodecParityTest.kt`：随机 varint 序列 round-trip；旧 path vs 新 path 字节位等

#### 流式入口

- `NbtReaderStreamReadTest.kt`：用 `InputStream` 直接传 1MB / 50MB NBT 字节，断言产出与 `readRoot(ByteArray)` 路径 byte-equal
- `BlockPrintReaderReadStreamTest.kt`：通过 `InputStream` 跑 read/peek 两种路径，结果与 `read(ByteArray)` byte-equal

#### Round-trip 一致性

- `FormatRoundTripTest.kt`：每种格式 parse → write → parse → diff 文档模型应等
- 已有测试覆盖大部分，新加的 peek 路径也加一条：`peek(bytes) → read(bytes) → name/author/desc 一致`

### 覆盖率目标

| 包 | 行覆盖目标 | 理由 |
|---|---|---|
| `api/` | **100%** | 公开合同，每个分支必测 |
| `format/` | **95%** | 格式 bug 是最常见投诉源 |
| `nbt/` | **90%** | 边界多（EOF、tag id 错误等）需细致 |
| `model/` | **80%** | 纯数据 + 简单 invariant，多数行自动覆盖 |
| `exceptions/` | **80%** | 包装路径 |
| `internal/` | **85%** | 共享工具，回归成本高 |
| `glb/` | 80%（沿用现状） | GLB 改动面小，不重测 |

测试覆盖率用 Kover 或 IntelliJ 自带 runner（看项目现配什么）。

### CI 行为

- `gradle jvmTest` 跑所有 `src/jvmTest/**` 下的测试
- benchmark 文件用 `assumeFalse(System.getenv("CI") == "true")` 或单独的 `benchmarkTest` task（不进 CI）
- 任何 PR 若 `gradle jvmTest` 红 = block merge

### 不做的事

- 不引入 kotest / mockk 等新依赖（沿用 kotlin.test + 项目当前栈）
- 不写 E2E（这是个解析库，没有 UI 流程）
- 不写 Android instrumentation 测试（保持现状，仅 JVM 单测）

---

## 7. 实施顺序（待 writing-plans 阶段细化）

1. 创建 `api/` + `model/` 壳（先放空的 BlockPrintReader / BlockPrintDocument，throw UnsupportedOperationException）
2. 把旧 LitematicReader / Litematic 转发到新类型（typealias + 委托），跑现有测试确认迁移路径可工作
3. 抽 `nbt/NbtReader`、建 `internal/NbtAccessors`、建 `format/FormatDetector`
4. 拆 LitematicParser → 4 个 format/*Reader + readHeader
5. 建 `model/BlockPrintSummary` + `api/BlockPrintReader.peek`
6. 抽 `internal/VarInt`、`internal/PackedBlocks`，替换热路径
7. 抽 `internal/NbtAccessors.skipPayload`，加 `NbtReader.readRoot(InputStream)`
8. 改 `BlockPrintReader.read(InputStream)` 用流式路径
9. GLB 子树仅做命名 + 包归位，不动逻辑
10. 跑全部测试 + benchmark 验证 1.4× / 1.6× 门槛
11. 删旧 Litematic* 类和旧包路径
12. 删旧 README/文档中的 Litematic* 引用，更新 BLUEPRINT_API.md / GLB_PIPELINE.md
13. 升版 0.2.2 → 1.0.0，发布

每步独立提交，方便回滚与 review。
