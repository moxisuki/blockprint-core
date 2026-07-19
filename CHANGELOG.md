# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/lang/zh-CN/).

## [1.6.0] - 2026-07-20

### Added

- Bundle Create baked model manifests under core resources so `blockprint-core` can resolve captured Create geometry without requiring an external baker output directory.
- Add classpath fallback loading for split baked manifests while preserving external `assetsDirs` as the override path for local debugging.
- Add static Create assemblies for renderer/Flywheel-only parts including steam engine linkages, mechanical pump cogs, package frogports, brass/andesite tunnel flaps, chain conveyor shaft/wheel parts, fluid pipe attachments, encased shafts/cogwheels, fan internals, belts, funnels, deployers, mixers, and water wheels.

### Changed

- Improve Create model selection so stale baked captures do not erase richer semantic adapter output, while blockstate-authoritative models such as `crushing_wheel` keep their standard OBJ geometry.
- Improve vanilla sign synthetic geometry with separate standing, wall, hanging, and wall-hanging shapes.

### Fixed

- Fix missing dynamic Create parts in exported GLBs, including pump gears, steam engine pistons/linkages, tunnel curtains, packager/frogport pieces, and chain conveyor winch parts.
- Fix chain conveyor underside casing UVs so the bottom no longer samples the dark diagnostic region of the texture.
- Fix wall sign and wall hanging sign facing semantics so signs attach to the correct block face.
- Fix sign texture paths so synthetic sign faces resolve into the texture atlas instead of being dropped.

## [1.5.0] - 2026-07-06

### Added

- `BlockState.parse()` — factory method to parse block state strings (`"minecraft:stone"`, `"minecraft:oak_log[axis=y]"`) into `BlockState` objects.
- `BlueprintBuilder` — programmatic DSL for building `BlockPrintDocument` from scratch without parsing a file.
- `RegionBuilder` — fluent builder with auto-managed palette for constructing `BlockPrintRegion`:
  - `.set(x, y, z, blockState)` — place a single block (auto-registers in palette)
  - `.fill(fromX, fromY, fromZ, toX, toY, toZ, blockState)` — fill a cuboid region
  - `.fill(from, to, blockState)` — fill using `Position` objects
  - `.air(x, y, z)` / `.fillAir(...)` — clear blocks to air
  - `.getBlockIndex()`, `.getBlockState()`, `.isAir()`, `.paletteSize()`, `.nonAirCount()` — query methods
  - `.position(x, y, z)` — set region world origin

## [1.3.0] - 2026-07-03

### Fixed

- `ModelResolver.resolveMultipart` no longer returns the non-existent `minecraft:block/<name>` fallback when a multipart blockstate is resolved in isolation (e.g. `BlockIconSynthesizer` rendering walls, fences, chains, panes with no neighbour-block properties). It now picks the **first `apply`'s model** from the multipart definition, which always points to a real model on disk (e.g. `minecraft:block/andesite_wall_post` for `andesite_wall`). Previously, the GLB came out with `meshes: []` and the icon rendered as a transparent black square.

### Known limitations

- `*_hanging_sign` blocks still render black. Their blockstate uses `variants` (not multipart) and the model chain leads to `minecraft:item/generated` which `ModelResolver` cannot resolve as a block. This is a separate issue and will be fixed in a follow-up.

## [1.2.0] - 2026-07-03

### Added

- `BlockIconSynthesizer.synthesizeFromBlockstate(blockstateJson, namespace, name)` — reads a vanilla-style blockstate JSON and returns the right shape of `BlockPrintDocument`:
  - `_door` blocks (e.g. `acacia_door`) produce a 1×2×1 region with the lower half at (0, 0, 0) and the upper half at (0, 1, 0); palette indexes 0 = air, 1 = lower, 2 = upper.
  - `_button` and `*pressure_plate` blocks prefer the `face=floor,facing=north,powered=false` variant when available (the naive first-key ordering picked `face=ceiling,facing=east,powered=false`, which renders as a rotated plank in iso view).
  - Other blocks fall back to the first variant in JSON order, mirroring the previous single-block behaviour.
- `BlockIconSynthesizer.pickBestVariant` — small internal helper that owns the per-block-type default rules.
- `synthesizeFromBlockstate` walks the blockstate JSON via the existing internal `JsonParser` from `core/glb/internal/` (no new runtime dep needed).

### Fixed

- `_door` icons previously rendered as only the lower half (1×1×1 region) because the upper half was never placed.
- `_button` and `*pressure_plate` icons previously picked the first JSON variant (alphabetically `face=ceiling,facing=east,powered=false`) which rendered as a rotated plank in iso view.

## [1.1.0] - 2026-07-03

### Added

- New public API `io.github.moxisuki.blockprint.core.api.BlockIconSynthesizer`:
  constructs a minimal 1×1×1 `BlockPrintDocument` containing a single block at
  (0, 0, 0), suitable for downstream `BlockPrintToGlb` use (e.g. icon generators).
  Handles arbitrary blocks — multipart, custom OBJ, doors, beds, tinted blocks —
  by delegating geometry resolution to the existing model pipeline.

## [1.0.0] - 2026-07-03

### Added

- `BlockPrintReader.peek(...)` — 仅读元数据的快速预览接口，跳过 `Regions`/`Schematic` 子树（`NbtReader.readRootHeader` + `skipPayload`）
- `PackedAtlas.fallback` — O(1) 访问首个 atlas 条目，替代 per-face `Map.values` 遍历
- `FaceScratch` — per-call 复用的固定缓冲区，消除 per-face `List<DoubleArray>`/`FloatArray(3)` 分配
- 8 个 `*Into` companion helpers（`facePlaneCornersInto`、`rotateElementPointInto` 等），零分配写入 caller buffer
- `ConnectionVariantKey` 变体缓存（`Pair<String, String>` key），相同朝向的 fence/pane/wall 共享一次模型解析
- 合成 region benchmark（16³–64³ stone checker + 16³ solid fence）
- `UserSchemSmokeTest` — 用户 `.schem` 端到端 wall-clock 测量
- `PackedAtlasFallbackTest`、`ModelResolverCacheTest`、`MeshBuilderConnectionMaskTest`、`CountFloorStatsAtlasTest`、`FloorSinkConsumeTest`、`MeshBuilderVariantCacheTest`、`MeshBuilderAllocationParityTest`、`MeshBuilderMinMaxParityTest`、`MeshBuilderHelpersParityTest`

### Changed

- **Breaking:** `FloorSink.onFloor(...): Unit` → `: Boolean`。consuming sink 必须 return `true`（skip `FloorAccum.close()`）；drain-only sink 返回 `false`
- **Breaking:** `FloorAccum.appendQuad` 入参从 `List<FloatArray>` 改为扁平 `FloatArray` / `Array<FloatArray>`
- **Breaking:** `MeshBuilder.build()` 移除。改为 `BlockPrintToGlb.convert(…)/convertToBytes(…)` 或直接 `buildFloorsInto(…)`
- **Breaking:** package 重命名：`LitematicReader` → `BlockPrintReader`，`Litematic` → `BlockPrintDocument`，`LitematicRegion` → `BlockPrintRegion`，`LitematicException` → `BlockPrintException`，`LitematicToGlb` → `BlockPrintToGlb`，`BlueprintConverter` → `BlockPrintConverter`；`internal/format/` → `format/<formatName>/`
- `BlockPrintToGlb.run` 改为单次 `buildFloorsInto` pass（计数 + 几何写入合并，sink 直接持有 `OffHeapBuf` 不拷贝）
- `countFloorStats` 仅提供 bbox（通过 origin offset 的世界坐标）；per-floor size 由 sink 从实际 `FloorAccum` buffer 读取
- `ModelResolver.modelCache` 从 dead code 改为实际缓存（`resolveModel` + `resolveBlockstate`）
- `ConnectionFamily` 枚举扩展为 `NONE, GLASS_PANE, FENCE, WALL, IRON_BARS`；`buildFamilyArray` + `precomputeConnectionMask` 替换 `Map<Triple, Map>` 为 flat `IntArray`
- `MeshBuilder.buildFloorsInto` 新增可选参数 `sharedModelCache`、`sharedConnVariantCache` 以跨 pass 共享
- `countFloorStats` 新增可选参数 `originX/Y/Z`（默认 0）以输出世界坐标 bbox
- `countFloorElements` inline 到 `countFloorStats` 内（消除 per-cell `IntArray(4)`/`FloatArray(6)`/`BooleanArray(1)`/lambda 分配）
- 8 个 legacy helper 从 instance `private` 迁至 `companion object`（PR-1），最终删除（Area F）
- `processFaceInto` / `processRawMeshInto` 改用 `FaceScratch` + `*Into` helpers

### Removed

- `MeshBuilder.build()` + 3 个 `collect*` helpers（~258 行 dead code）；`MeshBuilderParityTest`
- `precomputeConnectionProperties`（`Map<Triple, Map>`）替换为 `precomputeConnectionMask`（`IntArray(4-bit mask)`）
- `connectionFamilyOf` 的 per-cell `String.contains` 扫描被 `buildFamilyArray` 的 per-palette-entry 扫描取代
- 8 个 instance-level legacy helper（`facePlaneCorners`、`rotateElementPoint` 等），全部由 companion `*Into` 重载替代
- `FloatArray(3)` per-face normal 分配（改为 `dirToNormalArrayInto`）
- Pass 2 per-floor `OffHeapBuf(N) + copyTo` 分配（改为 sink 持有原始引用）
- `BlockPrintToGlb.run` 内的 per-floor bbox 4 KiB 分块扫描（改用 `countFloorStats` world-space bbox）

### Fixed

- GLB header `bufferView.byteLength` 和 `accessors.count` 声明偏小（`countFloorStats` 低估 vs `processFaceInto` 实际 emit；改为 sink-based stats 修复）
- GLB bounding box 为局部坐标 (0-16) 而非世界坐标（Area B+C regression；`countFloorStats` 加入 `originX/Y/Z` offset 修复）
- `FloorAccum` 对 ownership-returning `FloorSink` 调用 `close()` 导致后续 `writeOffHeapFloats` 抛出 `IllegalStateException`
- `ModelResolver.modelCache` 从未被写入（dead code — 所有 model resolve 均反复重读磁盘 + 重解析 JSON）

## [0.2.1] - 2026-06-20

### Added

- `BlockPrintDocument`、`BlockPrintRegion`（`model/` package + `fromLegacy` adapter）
- `FormatDetector` — content-based dispatch（Litematica / Sponge / Structure / BuildingHelper）
- 4 个 per-format reader（`LitematicaReader`、`SpongeReader`、`StructureReader`、`BuildingHelperReader`）
- `NbtFormatException`、`GlbExportException`、`BlockPrintException`

### Changed

- `LitematicParser` 拆分为 per-format reader
- package `internal/format/` → `format/<formatName>/`
- `BlockPrintReader.read(InputStream)` 改为 streaming `NbtReader`
- `NbtReader.readRootHeader` + 用于 Peek 的 skipSubtreeNames

### Fixed

- Sponge v3 保留 block state properties + 去重 palette
- `parseLenient` for Sponge v3, strict read for Structure, gzip Sponge output
- Litematica-with-Sponge-compat 错误分类为 Sponge
- `packer streaming pack(... dos)` multi-long field write bug

## [0.1.29] - 2026-06-17

### Added

- `BlueprintConverter` — 4 格式双向 facade
- `LitematicWriter`、`SpongeWriter`、`StructureWriter`、`BuildingHelperWriter`
- `SchematicFormat.fromExtension` 不区分大小写匹配
- `NbtWriter` 镜像 `NbtReader` + gzip helper
- `BlockStatePacker.pack()` — `unpack()` 的逆运算

### Fixed

- nbits=64 masking 边界情况 + straddle field read
- GLB attributes 按 concatenated 顺序写入（非 per-floor interleaved）
- JDK 13 `get(int,byte[],int,int)` 替换为 position save/restore（Android 兼容）

## [0.1.28] - 2026-06-12

### Added

- `OffHeapBuf`（KMP expect/actual，JVM 上包装 `DirectByteBuffer`）
- `FloorAccum` 改为 OffHeapBuf-backed（移除 `FloatBuf`/`IntBuf`）
- `FloorSink.onFloor` 签名改为 `OffHeapBuf`
- `MeshBuilder.buildFloorsInto`（Pass 2 streaming skeleton）
- `MeshBuilder.countFloorStats`（Pass 1，零顶点分配）
- `GlbWriter.writeStreaming` + `buildHeader` + `writeFloor`
- 零拷贝 streaming 的双 pass parity test

### Changed

- `LitematicToGlb.run` 重写为双 pass streaming pipeline
- `build()` / `write()` 改为 delegating over 双 pass pipeline
- Pass 1 min/max scan 改为 streaming via `OffHeapBuf.readBytes`

### Fixed

- Android `DirectByteBuffer` 限制 → segmented 2 MB heap `ByteArray` 替换（防止 OOM）
- segment size 降至 128 KB + GC between Pass 1/2（适配 ART heap）

[1.0.0]: https://github.com/moxisuki/blockprint-core/compare/v0.2.1...v1.0.0
[0.2.1]: https://github.com/moxisuki/blockprint-core/compare/v0.1.29...v0.2.1
[0.1.29]: https://github.com/moxisuki/blockprint-core/compare/v0.1.28...v0.1.29
[0.1.28]: https://github.com/moxisuki/blockprint-core/releases/tag/v0.1.28

## [2026-07-04] - 1.4.0

### Fixed

- Sign icons (*_sign, *_wall_sign, *_hanging_sign) now render with content.
  Previously the synthetic model used a face texture path missing the
  	extures/ segment (e.g. minecraft:entity/signs/hanging/bamboo), which
  caused TexturePacker.readPng to build a wrong file path, drop the texture,
  and silently produce GLBs with empty meshes. Added a regression test
  (SignRenderTest) and normalized the path inside SyntheticSign.box.


## [2026-07-04] - 1.4.1

### Fixed

- *_wall_hanging_sign icons now render correctly. Previously the
  _hanging_sign suffix check matched before the _wall_hanging_sign
  check, stripping only the shorter suffix and leaving the bogus wood
  name oak_wall, whose texture file does not exist in vanilla. Added a
  dedicated _wall_hanging_sign branch (checked first) and a regression
  test (acacia_wall_hanging_sign produces non-empty GLB).

