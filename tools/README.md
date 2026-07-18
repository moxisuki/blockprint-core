# BlockPrint 建模工具链

blockprint-core 对 Create 模组的支持通过**构建期模型烘焙**实现, 而非运行时解析 Minecraft/NeoForge/Mod Java 类。

## 架构边界

```text
┌─ 构建期 (仅在 developer 机器上执行) ────────────────────────────────────┐
│                                                                           │
│  NeoForge Client (headless)                                               │
│    + Create 6.0.11 / Flywheel 1.0.6                                       │
│    + blockprint_model_baker (temporary dev mod)                           │
│      │                                                                    │
│      ├─> BlockRenderDispatcher.renderBatched(...)                         │
│      │     └─> RecordingVertexConsumer → positions/uvs/normals/indices   │
│      │                                                                     │
│      ├─> BlockEntityRenderDispatcher → BER 录制                           │
│      │                                                                     │
│      ├─> Flywheel Visual 录制 (provenance-aware)                          │
│      │     └─> CapturingInstancerProvider → 捕获 Instance 网格+变换      │
│      │                                                                     │
│      └─> Atlas sprite attribution → 逻辑纹理 ID                          │
│                                                                           │
│  Output: by-block/<namespace>/<block>.json (pure static mesh data)       │
│                                                                           │
└───────────────────────────────────────────────────────────────────────────┘
                                     │
                                     ▼
┌─ 运行时 (JVM / Android, zero mod deps) ──────────────────────────────────┐
│                                                                           │
│  blockprint-core:                                                          │
│    → BakedModelMeshSource: 读取 .json manifest                            │
│    → BlockPrintToGlb: 组装 mesh + 外部纹理 → GLB                         │
│                                                                           │
│  No dependency on: Minecraft, NeoForge, Create, Flywheel.                │
│                                                                           │
└───────────────────────────────────────────────────────────────────────────┘
```

## 关键设计

### 1. 真实渲染录制, 不做源代码分析

- 不解析 Create/Flywheel 源码, 不在 core 中维护任何方块规则表
- 直接调用 Minecraft 官方的 BlockRenderDispatcher + BlockEntityRenderDispatcher
- Flywheel Visual 通过 Generic Instancer 录制 (provenance mixin 携带 texture 来源)

### 2. 默认 BlockEntity 状态

- 不读取蓝图 BlockEntity NBT
- BlockEntity 由注册类型构造后处于默认状态 (标准外观)
- 在离线 title screen 加载 NeoForge SERVER configs 保证 `getLevel()` 非空

### 3. Atlas 纹理归属

- Flywheel mesh 通过 BakedQuad sprite provenance mixin 恢复逻辑纹理 ID
- 支持 blocks/chest 等多个 atlas, 无法归属的 primitive 会明确跳过 (不输出占位纹理)

## 当前 Create 方块覆盖

已对 Create 6.0.11 **全部 643 个方块**完成烘焙, 包括:

**传动类**: shaft, cogwheel, large_cogwheel, gearbox, clutch, encased_chain_drive, adjustable_chain_gearshift, sequenced_gearshift, rotation_speed_controller, encased_cogwheel/large_cogwheel/shaft (andesite+brass)

**传送类**: belt, andesite_belt_funnel, brass_belt_funnel, funnel (andesite/brass), chute, smart_chute

**流体类**: fluid_pipe, fluid_valve, pump, hose_pulley, spout, item_drain, mechanical_arm

**动力/机械**: water_wheel, large_water_wheel, crushing_wheel, windmill_bearing, steam_engine, mechanical_drill, mechanical_press, mechanical_saw, mechanical_harvester, mechanical_plough, mechanical_mixer, deployer, nozzle, encased_fan

**储罐**: fluid_tank, item_vault, basin, creative_fluid_tank

**轨道**: track, controls, train_door, train_trapdoor, bogey 系列, railway_casing

**功能性**: blaze_burner, weighted_ejector, smart_observer, content_observer, stockpile_switch, threshold_switch, nixie_tube 系列, redstone_link, redstone_contact

**装饰**: 全部 window/door/bars/scaffolding/ladder/table_cloth/seat/sail/postbox/valve_handle 变体 (所有颜色)

## 构建使用方法

```powershell
# 1. 从蓝图提取 blockstate palette
.\gradlew.bat -PblueprintFile=C:\Users\Administrator\Downloads\machine.nbt writeBakerBlockstateFile --no-daemon

# 2. 运行模型烘焙 (需要在本地有 Create 源码 checkout)
.\gradlew.bat -Dblockprint.baker.blockstateFile=.\build\blockprint-model-baker\palette.blockstates.txt runCreateModelBakerClientExport --no-daemon

# 3. 生成 GLB
.\gradlew.bat jvmTest --tests "UserBlueprintRegressionTest.convert_lazy_iron_farm_floor1_glb" --no-daemon
```
