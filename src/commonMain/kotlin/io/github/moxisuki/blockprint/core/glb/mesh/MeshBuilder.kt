package io.github.moxisuki.blockprint.core.glb.mesh

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.model.BlockPrintRegion
import io.github.moxisuki.blockprint.core.glb.model.Element
import io.github.moxisuki.blockprint.core.glb.model.ElementRotation
import io.github.moxisuki.blockprint.core.glb.model.Face
import io.github.moxisuki.blockprint.core.glb.model.ModelResolver
import io.github.moxisuki.blockprint.core.glb.model.ResolvedModel
import io.github.moxisuki.blockprint.core.glb.platform.OffHeapBuf
import io.github.moxisuki.blockprint.core.glb.texture.AtlasEntry
import io.github.moxisuki.blockprint.core.glb.texture.PackedAtlas
import io.github.moxisuki.blockprint.core.glb.texture.TexturePacker
import io.github.moxisuki.blockprint.core.glb.writer.GlbExportOptions
import io.github.moxisuki.blockprint.core.glb.writer.GlbOutput
import io.github.moxisuki.blockprint.core.glb.writer.FloorSlice
import kotlin.math.cos
import kotlin.math.sin

class MeshBuilder(
    private val modelResolver: ModelResolver,
    private val texturePacker: TexturePacker,
    private val enableTinting: Boolean = true,
) {
    /**
     * Vanilla Minecraft 中真正注册了 [net.minecraft.world.level.GrassColors]/[FoliageColors]
     * 采样逻辑的方块（也就是有 `BlockColor` 的方块）。只有这些方块的 `tintindex` 才会被
     * Minecraft 实际生效；其它方块（哪怕模型写了 `tintindex: 0`）的染色请求会被
     * vanilla 忽略——典型例子就是切石机锯片，模型里写了 `tintindex: 0` 但 stonecutter
     * block 没有 `BlockColor`，所以锯片实际保持原色。
     *
     * 维护此白名单而不是反过来维护"不染色"列表，因为：
     *  - 应染色的方块是 vanilla 明确列出的有限集合
     *  - 不染色的方块是"所有其它"，范围太大且会随版本膨胀
     *
     * 增删时请对齐 vanilla `BlockColors` 注册表（1.21）。
     */
    private val biomeTintedBlocks: Set<String> = setOf(
        // 方块本身
        "minecraft:grass_block",        // 仅顶面有 tintindex
        "minecraft:redstone_wire",
        "minecraft:sugar_cane",
        "minecraft:lily_pad",
        "minecraft:vine",
        "minecraft:cave_vines",
        "minecraft:cave_vines_plant",
        "minecraft:spore_blossom",
        "minecraft:big_dripleaf",
        "minecraft:small_dripleaf",
        "minecraft:melon_stem",
        "minecraft:attached_melon_stem",
        "minecraft:pumpkin_stem",
        "minecraft:attached_pumpkin_stem",
        "minecraft:tall_grass",
        "minecraft:large_fern",
        // 全部树叶（10 种）
        "minecraft:oak_leaves",
        "minecraft:spruce_leaves",
        "minecraft:birch_leaves",
        "minecraft:jungle_leaves",
        "minecraft:acacia_leaves",
        "minecraft:dark_oak_leaves",
        "minecraft:mangrove_leaves",
        "minecraft:cherry_leaves",
        "minecraft:azalea_leaves",
        "minecraft:flowering_azalea_leaves",
        // 小型花（单格）
        "minecraft:dandelion",
        "minecraft:poppy",
        "minecraft:blue_orchid",
        "minecraft:allium",
        "minecraft:azure_bluet",
        "minecraft:red_tulip",
        "minecraft:orange_tulip",
        "minecraft:white_tulip",
        "minecraft:pink_tulip",
        "minecraft:oxeye_daisy",
        "minecraft:cornflower",
        "minecraft:lily_of_the_valley",
        "minecraft:wither_rose",
        // 大型花（双格）
        "minecraft:sunflower",
        "minecraft:lilac",
        "minecraft:rose_bush",
        "minecraft:peony",
        "minecraft:pitcher_plant",
    )

    internal fun isBiomeTinted(blockName: String): Boolean = blockName in biomeTintedBlocks

    /**
     * 某些方块在 vanilla 里有自己的 BlockColor，返回固定颜色（不读 colormap）。
     * 我们的库没有 biome/上下文数据，colormap 路径会退化成"全图单色"，对这些方块
     * 来说反而是错的（例如红石粉如果走 grass colormap 会被染成绿色）。
     * 返回 null 表示"没有特殊染色，走普通 colormap 流程"。
     */
    internal fun specialTintColorOf(blockName: String): Int? = when (blockName) {
        "minecraft:redstone_wire" -> 0xFFB40000.toInt()
        "minecraft:water" -> 0xFF1E5AA8.toInt()
        "minecraft:flowing_water" -> 0xFF1E5AA8.toInt()
        "minecraft:lava" -> 0xFFD45E00.toInt()
        "minecraft:flowing_lava" -> 0xFFD45E00.toInt()
        else -> {
            val name = blockName.removePrefix("minecraft:")
            val color = when {
                name.endsWith("_wall_banner") -> name.removeSuffix("_wall_banner")
                name.endsWith("_banner") -> name.removeSuffix("_banner")
                else -> null
            }
            if (color != null) {
                when (color) {
                    "white" -> 0xFFF0F0F0.toInt()
                    "orange" -> 0xFFF9801D.toInt()
                    "magenta" -> 0xFFC74EBD.toInt()
                    "light_blue" -> 0xFF3AB3DA.toInt()
                    "yellow" -> 0xFFFED83D.toInt()
                    "lime" -> 0xFF80C71F.toInt()
                    "pink" -> 0xFFF38BAA.toInt()
                    "gray" -> 0xFF474F52.toInt()
                    "light_gray" -> 0xFF9D9D97.toInt()
                    "cyan" -> 0xFF169C9C.toInt()
                    "purple" -> 0xFF8932B8.toInt()
                    "blue" -> 0xFF3C44AA.toInt()
                    "brown" -> 0xFF835432.toInt()
                    "green" -> 0xFF5E7C16.toInt()
                    "red" -> 0xFFB02E26.toInt()
                    "black" -> 0xFF1D1D21.toInt()
                    else -> null
                }
            } else null
        }
    }

    /**
     * vanilla 1.13+ 把这些方块的模型文件清空，让特殊渲染器（FluidRenderer / ChestRenderer /
     * BedRenderer）接管。我们的模型驱动渲染器对此无能为力。
     *
     * **所有 ber 方块已迁移至 ModelResolver.syntheticModel**（包括水/岩浆/床/箱子/告示牌/潜影盒/头骨/conduit/旗帜/装饰罐）。
     * 此函数保留为兼容入口，**所有 case 都返回 null**（走普通 model 路径或 synthetic 路径）。
     */
    private fun customBlockGeometry(
        block: BlockState,
        x: Int, y: Int, z: Int,
        region: BlockPrintRegion,
    ): List<Element>? {
        // 所有特殊方块都已在 ModelResolver.syntheticModel 里处理
        return null
    }

    /**
     * 箱子：1×14×14 box（去掉上下边缘各 1px 模拟箱子的"凹陷"边缘）。
     * 纹理用 oak_planks（普通木板）做占位——`entity/chest/normal` 是 64×64 的箱子动画图，
     * 包含盖子/锁/底面等多区域，对一个简单 box 拉伸会非常诡异。
     * 双箱子的 left/right 这里用同一个 box（视觉重叠，能看见位置即可）。
     */
    private fun chestBox(blockName: String): List<Element> {
        val texture = "minecraft:textures/block/oak_planks"
        return listOf(Element(
            from = listOf(1.0, 0.0, 1.0),
            to = listOf(15.0, 14.0, 15.0),
            faces = mapOf(
                "down"  to Face(texture, listOf(1.0, 1.0, 15.0, 15.0), "down", 0),
                "up"    to Face(texture, listOf(1.0, 1.0, 15.0, 15.0), "up", 0),
                "north" to Face(texture, listOf(1.0, 1.0, 15.0, 15.0), "north", 0),
                "south" to Face(texture, listOf(1.0, 1.0, 15.0, 15.0), "south", 0),
                "west"  to Face(texture, listOf(1.0, 1.0, 15.0, 15.0), "west", 0),
                "east"  to Face(texture, listOf(1.0, 1.0, 15.0, 15.0), "east", 0),
            )
        ))
    }

    /**
     * 床：16×6×16 扁平 box，**但 vanilla 床是 2 格方块（head + foot）**。
     * 如果当前格能找到对面那一格（foot 找 head，反之亦然），就把 box 延伸到 32×6×16 覆盖整张床；
     * 找不到（边界/缺一块）就只画自己这一格。
     *
     * 简化点：head/foot 用同一纹理（没有枕头/床尾区分），不模拟被子的折角。
     *
     * 返回 null 表示"这一格不要画"——专门给 foot 用：head 已经在画整个 32 长的 box，
     * foot 不画避免重叠。
     */
    /**
     * vanilla 中的"连接方块"——north/east/south/west 4 个属性是渲染时按邻居动态算的，
     * 不在 NBT 里。按连接族分组，族内任意两种都互连（玻璃板↔染色玻璃板↔墙↔铁栏互通）。
     */
    internal enum class ConnectionFamily { NONE, GLASS_PANE, FENCE, WALL, IRON_BARS }

    /**
     * Map a [ConnectionFamily] enum to its ordinal value (0=NONE, 1=GLASS_PANE,
     * 2=FENCE, 3=WALL, 4=IRON_BARS). Stable across the JVM lifetime so the
     * family `IntArray` can be cached and re-used.
     */
    internal fun familyOrdinal(family: ConnectionFamily): Int = family.ordinal

    private fun connectionFamilyOf(blockName: String): ConnectionFamily = when {
        blockName.contains("glass_pane") -> ConnectionFamily.GLASS_PANE
        blockName.contains("_wall") -> ConnectionFamily.WALL
        blockName == "minecraft:iron_bars" -> ConnectionFamily.IRON_BARS
        blockName.contains("_fence") && !blockName.contains("_fence_gate") -> ConnectionFamily.FENCE
        else -> ConnectionFamily.NONE
    }

    /**
     * Build a flat palette-indexed family table: one `Int` per palette
     * entry, value = [ConnectionFamily.ordinal]. Replaces the per-cell
     * `connectionFamilyOf` substring scans the legacy path did; the
     * substring scan now happens at most N times (palette size) per
     * region instead of N_cells * 5 times.
     */
    internal fun buildFamilyArray(palette: BlockPalette): IntArray {
        val out = IntArray(palette.size)
        for ((i, block) in palette.entries.withIndex()) {
            out[i] = connectionFamilyOf(block.name).ordinal
        }
        return out
    }

    /**
     * Bit constants used in the per-cell connection mask. The bit
     * positions are stable (don't reorder) because they're part of
     * the storage format the y/z/x hot loop reads.
     */
    private val CONN_NORTH: Int = 0x1
    private val CONN_EAST: Int = 0x2
    private val CONN_SOUTH: Int = 0x4
    private val CONN_WEST: Int = 0x8

    /**
     * Pre-compute a flat 4-bit mask per cell describing which
     * cardinal neighbours belong to the same connection family
     * (fence / glass pane / wall / iron bars). Replaces the
     * `Map<Triple<Int,Int,Int>, Map<String,String>>` from the legacy
     * path: zero per-cell `Triple` allocation, zero per-cell `Map`
     * allocation, zero per-cell `String.contains` scan.
     *
     * Indexing: `mask[idx]` where `idx = y*w*d + z*w + x` (the same
     * ordering used by `region.rawBlocks`). `mask[idx] == 0` means
     * the cell is not a connection-block anchor. Otherwise the bit
     * pattern is the union of [CONN_NORTH] / [CONN_EAST] /
     * [CONN_SOUTH] / [CONN_WEST] for each connected neighbour.
     */
    internal fun precomputeConnectionMask(
        region: BlockPrintRegion,
        family: IntArray,
    ): IntArray {
        val w = region.width; val h = region.height; val d = region.depth
        val mask = IntArray(w * h * d)
        val wd = w * d
        val raw = region.rawBlocks
        val noneOrdinal = ConnectionFamily.NONE.ordinal
        for (y in 0 until h) for (z in 0 until d) for (x in 0 until w) {
            val idx = y * wd + z * w + x
            val blockIdx = raw[idx]
            val fam = family[blockIdx]
            if (fam == noneOrdinal) continue
            var m = 0
            // 注意方向语义：north 对应 z-1，south 对应 z+1，east 对应 x+1，west 对应 x-1
            if (z > 0 && family[raw[idx - w]] == fam) m = m or CONN_NORTH
            if (x < w - 1 && family[raw[idx + 1]] == fam) m = m or CONN_EAST
            if (z < d - 1 && family[raw[idx + w]] == fam) m = m or CONN_SOUTH
            if (x > 0 && family[raw[idx - 1]] == fam) m = m or CONN_WEST
            if (m != 0) mask[idx] = m
        }
        return mask
    }

    /**
     * Pass 1 of the two-pass streaming pipeline: walk the region once and count
     * the visible vertices and indices per floor.
     *
     * No vertex data is allocated — only Int counters and per-face culling logic
     * identical to Pass 2. This means [FloorStats] matches the eventual output
     * byte-for-byte (asserted by the parity test).
     */
    internal fun countFloorStats(
        region: BlockPrintRegion,
        options: GlbExportOptions = GlbExportOptions(),
        atlas: PackedAtlas? = null,
    ): FloorStats {
        val w = region.width; val h = region.height; val d = region.depth
        val raw = region.rawBlocks
        val palette = region.palette

        // Build palette caches once and share with Pass 2.
        val paletteSize = palette.entries.size
        val modelCache = arrayOfNulls<List<Element>>(paletteSize)
        for ((blockIdx, block) in palette.entries.withIndex()) {
            val model = modelResolver.resolve(block.name, block.properties)
            if (model.hasTextures) {
                modelCache[blockIdx] = model.elements
            }
        }

        val family = buildFamilyArray(palette)
        val connectionMask = precomputeConnectionMask(region, family)
        val hasConnections = connectionMask.any { it != 0 }
        // Per-call merged-properties cache: at most 16 distinct masks
        // (4 bits) and typically 1-4 per region. Caches the dict the
        // y/z/x hot loop would otherwise allocate per connection cell.
        val mergedPropsCache = mutableMapOf<Int, Map<String, String>>()
        val plan = computeFloorPlan(h, options.floorHeight)
        val wd = w * d
        val perFloorVertices = IntArray(plan.floorCount)
        val perFloorIndices = IntArray(plan.floorCount)
        var totalPositions = 0
        var totalNormals = 0
        var totalUvs = 0
        var totalIndices = 0

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE

        // PR-4: per-call scratch for the inlined per-cell face-counting
        // loop. Allocated once, reused for every cell, never reallocated.
        val scratchCorners = DoubleArray(12)
        val scratchElemRot = DoubleArray(12)

        for (y in 0 until h) for (z in 0 until d) for (x in 0 until w) {
            val cellIdx = y * wd + z * w + x
            val idx = raw[cellIdx]
            if (idx == 0) continue
            val elements = modelCache[idx] ?: continue
            val mask = if (hasConnections) connectionMask[cellIdx] else 0
            val block: BlockState
            val finalElements: List<Element>
            if (mask != 0) {
                block = region.palette.entries[idx]
                val connProps = mergedPropsCache.getOrPut(mask) {
                    val m = mutableMapOf<String, String>()
                    if (mask and CONN_NORTH != 0) m["north"] = "true"
                    if (mask and CONN_EAST != 0) m["east"] = "true"
                    if (mask and CONN_SOUTH != 0) m["south"] = "true"
                    if (mask and CONN_WEST != 0) m["west"] = "true"
                    m
                }
                val merged = (block.properties ?: emptyMap()) + connProps
                val model = modelResolver.resolve(block.name, merged)
                if (!model.hasTextures) continue
                finalElements = model.elements
            } else {
                block = region.palette.entries[idx]
                finalElements = elements
            }
            val floorIdx = floorIndexForY(y, plan)
            // === INLINE OF countFloorElements (PR-4) ===
            for (elem in finalElements) {
                for ((origDir, face) in elem.faces) {
                    if (face.texture.isEmpty()) continue
                    // Atlas-lookup drop mirrors processFaceInto. Without
                    // this, the perFloorVertices/Indices counts are
                    // higher than what the actual export emits,
                    // forcing BlockPrintToGlb.run to run a second
                    // counting pass to size the GLB header.
                    if (atlas != null) {
                        val atlasHit = atlas.mappings[face.texture] ?: atlas.fallback
                        if (atlasHit == null) continue
                    }
                    facePlaneCornersInto(scratchCorners, 0, origDir, elem.from, elem.to)
                    if (elem.rotation != null) {
                        val rot = elem.rotation
                        for (i in 0 until 4) {
                            val off = i * 3
                            rotateElementPointInto(
                                out = scratchElemRot, off = off,
                                x = scratchCorners[off+0],
                                y = scratchCorners[off+1],
                                z = scratchCorners[off+2],
                                rot = rot,
                            )
                        }
                    } else {
                        System.arraycopy(scratchCorners, 0, scratchElemRot, 0, 12)
                    }
                    val geoDir = faceNormalToDir(scratchElemRot, 0)
                    if (!isFaceOnBoundary(geoDir, scratchElemRot, 0)) continue
                    val nx = when (geoDir) { "east" -> x + 1; "west" -> x - 1; else -> x }
                    val ny = when (geoDir) { "up" -> y + 1; "down" -> y - 1; else -> y }
                    val nz = when (geoDir) { "south" -> z + 1; "north" -> z - 1; else -> z }
                    if (nx in 0 until w && ny in 0 until h && nz in 0 until d) {
                        val neighborIdx = raw[ny * wd + nz * w + nx]
                        val neighborBlock = region.palette.entries[neighborIdx]
                        val neighborElements = modelCache[neighborIdx]
                        val sameFloor = floorIndexForY(ny, plan) == floorIdx
                        if (sameFloor) {
                            if ((block.name.contains("glass") && neighborBlock.name == block.name) ||
                                (block.name.contains("leaves") && neighborBlock.name == block.name)) continue
                            if (isFullOpaqueCube(neighborElements, neighborBlock.name)) continue
                        }
                    }
                    // Face is visible — count it.
                    perFloorVertices[floorIdx] += 4
                    perFloorIndices[floorIdx] += 6
                    totalPositions += 12
                    totalNormals += 12
                    totalUvs += 8
                    totalIndices += 6
                    for (i in 0 until 4) {
                        val off = i * 3
                        val cx = scratchElemRot[off+0].toFloat()
                        val cy = scratchElemRot[off+1].toFloat()
                        val cz = scratchElemRot[off+2].toFloat()
                        if (cx < minX) minX = cx
                        if (cy < minY) minY = cy
                        if (cz < minZ) minZ = cz
                        if (cx > maxX) maxX = cx
                        if (cy > maxY) maxY = cy
                        if (cz > maxZ) maxZ = cz
                    }
                }
            }
            // === END INLINE ===
        }

        return FloorStats(
            floorCount = plan.floorCount,
            perFloorVertices = perFloorVertices,
            perFloorIndices = perFloorIndices,
            totalPositions = totalPositions,
            totalNormals = totalNormals,
            totalUvs = totalUvs,
            totalIndices = totalIndices,
            minX = minX, minY = minY, minZ = minZ,
            maxX = maxX, maxY = maxY, maxZ = maxZ,
        )
    }

    /**
     * PR-4: the legacy `countFloorElements` helper was inlined into
     * [countFloorStats] above to remove the per-cell `IntArray(4)` +
     * `FloatArray(6)` + `BooleanArray(1)` + `(IntArray, FloatArray, Boolean) -> Unit`
     * closure allocations. The inlined loop uses `scratchCorners` and
     * `scratchElemRot` allocated once per [countFloorStats] call, and
     * mutates the outer accumulator state directly.
     */

    /**
     * Pass 2 of the two-pass streaming pipeline: walk the region, building
     * each floor's vertex data and pushing it to [sink] as soon as that floor
     * completes.
     *
     * Memory budget: peak is ~ one floor's worth of [FloorAccum] data plus the
     * shared palette caches.
     *
     * @param sharedModelCache Optional pre-built palette-indexed
     *   `Array<List<Element>?>`. When supplied, [buildFloorsInto] skips the
     *   `modelResolver.resolve()` pass for the base (non-connection) block
     *   geometry and reuses the provided array. The caller is responsible
     *   for building it once per region. [BlockPrintToGlb.run] already does
     *   this for atlas prep, so this parameter lets the two `buildFloorsInto`
     *   passes share that same array instead of re-resolving every palette
     *   entry a second and third time.
     * @param sharedConnVariantCache Optional mutable map keyed on
     *   `Pair<String, String>` (`blockName`, `sortedMergedPropsToString`).
     *   When a y/z/x cell is a connection block (fence / glass pane /
     *   wall / iron bars) the hot loop uses this map to cache the merged-
     *   property `ResolvedModel` so two cells with identical orientation
     *   share one resolution pass. `null` creates a fresh local map that
     *   is discarded at end of call; non-null lets two `buildFloorsInto`
     *   passes share the cache.
     */
    fun buildFloorsInto(
        region: BlockPrintRegion,
        originX: Int = 0,
        originY: Int = 0,
        originZ: Int = 0,
        options: GlbExportOptions = GlbExportOptions(),
        atlas: PackedAtlas? = null,
        sink: FloorSink,
        onProgress: ((Float) -> Unit)? = null,
        sharedModelCache: Array<List<Element>?>? = null,
        sharedConnVariantCache: MutableMap<Pair<String, String>, ResolvedModel>? = null,
    ) {
        val w = region.width; val h = region.height; val d = region.depth
        val palette = region.palette
        val plan = computeFloorPlan(h, options.floorHeight)

        val paletteSize = palette.entries.size
        val modelCache: Array<List<Element>?>
        val rawMeshCache = arrayOfNulls<List<RawMesh>>(paletteSize)
        val rotCacheX = IntArray(paletteSize)
        val rotCacheY = IntArray(paletteSize)
        if (sharedModelCache != null) {
            // Caller-supplied cache: skip the per-palette resolve for
            // base geometry but still need to populate the auxiliary
            // caches (rawMeshes, rotX, rotY) for any palette entry that
            // has elements.
            modelCache = sharedModelCache
            for ((blockIdx, block) in palette.entries.withIndex()) {
                if (sharedModelCache[blockIdx] != null) {
                    val model = modelResolver.resolve(block.name, block.properties)
                    rawMeshCache[blockIdx] = model.rawMeshes
                    rotCacheX[blockIdx] = model.rotX
                    rotCacheY[blockIdx] = model.rotY
                }
            }
        } else {
            // No shared cache: build everything locally as before.
            modelCache = arrayOfNulls(paletteSize)
            for ((blockIdx, block) in palette.entries.withIndex()) {
                val model = modelResolver.resolve(block.name, block.properties)
                if (model.hasTextures) {
                    modelCache[blockIdx] = model.elements
                    rawMeshCache[blockIdx] = model.rawMeshes
                    rotCacheX[blockIdx] = model.rotX
                    rotCacheY[blockIdx] = model.rotY
                }
            }
        }
        val family = buildFamilyArray(palette)
        val connectionMask = precomputeConnectionMask(region, family)
        val hasConnections = connectionMask.any { it != 0 }
        // Per-call merged-properties cache: at most 16 distinct masks
        // (4 bits) and typically 1-4 per region.
        val mergedPropsCache = mutableMapOf<Int, Map<String, String>>()

        // Per-call (or shared) cache for the merged-property model
        // resolution on connection blocks.
        val connVariantCache: MutableMap<Pair<String, String>, ResolvedModel> =
            sharedConnVariantCache ?: mutableMapOf()

        val raw = region.rawBlocks
        val wd = w * d

        val accs = Array(plan.floorCount) { FloorAccum(1024, 1024) }
        // PR-2: per-call scratch buffer set. Owned by this [buildFloorsInto]
        // invocation; not shared with other calls. processFaceInto reads
        // and writes into its slots for every face, eliminating the per-face
        // `List<FloatArray>` / `FloatArray(3)` allocations the legacy path
        // required.
        val scratch = newFaceScratch()
        var currentFloor = -1

        fun flushFloor(idx: Int) {
            val acc = accs[idx]
            if (acc.indices.sizeBytes() == 0) return
            sink.onFloor(
                floorIdx = idx,
                yMin = idx * plan.effectiveFloorHeight,
                yMax = minOf((idx + 1) * plan.effectiveFloorHeight - 1, h - 1),
                positions = acc.positions,
                uvs = acc.uvs,
                normals = if (acc.normals.sizeBytes() == 0) null else acc.normals,
                indices = acc.indices,
            )
            // Reset for safety; the buffers are consumed by the sink.
            acc.reset()
        }

        for (y in 0 until h) for (z in 0 until d) for (x in 0 until w) {
            val cellIdx = y * wd + z * w + x
            val idx = raw[cellIdx]
            val mask = if (hasConnections) connectionMask[cellIdx] else 0
            val block: BlockState
            val elements: List<Element>
            val (rotX, rotY) = if (mask != 0) {
                block = palette.entries[idx]
                val connProps = mergedPropsCache.getOrPut(mask) {
                    val m = mutableMapOf<String, String>()
                    if (mask and CONN_NORTH != 0) m["north"] = "true"
                    if (mask and CONN_EAST != 0) m["east"] = "true"
                    if (mask and CONN_SOUTH != 0) m["south"] = "true"
                    if (mask and CONN_WEST != 0) m["west"] = "true"
                    m
                }
                val merged = (block.properties ?: emptyMap()) + connProps
                // Build a deterministic key from the block name + the
                // sorted merged-property map. Two cells with identical
                // (name, props) produce the same key and therefore share
                // one ResolvedModel. This eliminates the per-cell
                // modelResolver.resolve() cost for connection-heavy
                // regions (every fence cell used to re-walk the
                // multipart resolution graph even when the orientation
                // matched a neighbour's).
                val variantKey: Pair<String, String> = block.name to merged.entries
                    .sortedBy { it.key }
                    .joinToString(",") { "${it.key}=${it.value}" }
                val model = connVariantCache.getOrPut(variantKey) {
                    modelResolver.resolve(block.name, merged)
                }
                if (!model.hasTextures) continue
                elements = model.elements
                model.rotX to model.rotY
            } else {
                elements = modelCache[idx] ?: continue
                block = palette.entries[idx]
                rotCacheX[idx] to rotCacheY[idx]
            }
            val bx = originX + x; val by = originY + y; val bz = originZ + z
            val floorIdx = floorIndexForY(y, plan)
            if (currentFloor >= 0 && floorIdx != currentFloor) {
                flushFloor(currentFloor)
            }
            currentFloor = floorIdx
            val acc = accs[floorIdx]
            // Process faces + rawMeshes for this block. The per-element processing
            // is copied from the legacy build() method (Task 6 will consolidate them).
            for (elem in elements) {
                for ((origDir, face) in elem.faces) {
                    processFaceInto(
                        face = face,
                        origDir = origDir,
                        elem = elem,
                        block = block,
                        bx = bx, by = by, bz = bz,
                        x = x, y = y, z = z,
                        w = w, h = h, d = d, wd = wd,
                        raw = raw, palette = palette, modelCache = modelCache,
                        plan = plan, floorIdx = floorIdx,
                        rotX = rotX, rotY = rotY,
                        atlas = atlas,
                        acc = acc,
                        scratch = scratch,
                    )
                }
            }
            val rawMeshes = rawMeshCache[idx] ?: emptyList()
            for (mesh in rawMeshes) {
                processRawMeshInto(mesh, block, bx, by, bz, rotX, rotY, atlas, acc, scratch)
            }
        }
        if (currentFloor >= 0) flushFloor(currentFloor)
        // Release every accumulator's native memory immediately.
        // Without this we rely on the GC to reclaim hundreds of MB of
        // segmented OffHeapBuf storage — risky on Android where the
        // ART heap cap is 256 MB.
        for (acc in accs) acc.close()
    }

    /**
     * Per-face processing for [buildFloorsInto]. Body is copied verbatim from
     * the legacy `build()` method so Pass 2 produces byte-identical output.
     * Requires [atlas] to be supplied; without it the face is skipped (no UV
     * transform is possible).
     */
    private fun processFaceInto(
        face: Face,
        origDir: String,
        elem: Element,
        block: BlockState,
        bx: Int, by: Int, bz: Int,
        x: Int, y: Int, z: Int,
        w: Int, h: Int, d: Int, wd: Int,
        raw: IntArray, palette: BlockPalette,
        modelCache: Array<List<Element>?>,
        plan: FloorPlan, floorIdx: Int,
        rotX: Int, rotY: Int,
        atlas: PackedAtlas?,
        acc: FloorAccum,
        scratch: FaceScratch,
    ) {
        if (face.texture.isEmpty()) return
        val atlasEntry = atlas?.mappings?.get(face.texture) ?: atlas?.fallback
        if (atlasEntry == null) return
        // PR-3: corners / rotations / boundary-offset path writes into the
        // per-call [scratch] buffers. The three stage buffers are
        //   corners      — `facePlaneCornersInto` raw 4×3 corners
        //   elemRotated  — after per-element `elem.rotation`
        //   rotated      — after blockstate `rotX`/`rotY`
        // and `finalRotated` is the post-offset (or identity) copy of
        // `rotated` that the verts loop reads.
        facePlaneCornersInto(scratch.corners, 0, origDir, elem.from, elem.to)
        if (elem.rotation != null) {
            val rot = elem.rotation
            for (i in 0 until 4) {
                val off = i * 3
                rotateElementPointInto(
                    out = scratch.elemRotated, off = off,
                    x = scratch.corners[off+0],
                    y = scratch.corners[off+1],
                    z = scratch.corners[off+2],
                    rot = rot,
                )
            }
        } else {
            System.arraycopy(scratch.corners, 0, scratch.elemRotated, 0, 12)
        }
        val eRotX = if (elem.modelRotX != 0 || elem.modelRotY != 0) elem.modelRotX else rotX
        val eRotY = if (elem.modelRotX != 0 || elem.modelRotY != 0) elem.modelRotY else rotY
        if (eRotX == 0 && eRotY == 0) {
            System.arraycopy(scratch.elemRotated, 0, scratch.rotated, 0, 12)
        } else {
            for (i in 0 until 4) {
                val off = i * 3
                rotatePointInto(
                    out = scratch.rotated, off = off,
                    x = scratch.elemRotated[off+0],
                    y = scratch.elemRotated[off+1],
                    z = scratch.elemRotated[off+2],
                    rotX = eRotX, rotY = eRotY,
                )
            }
        }
        val geoDir = faceNormalToDir(scratch.rotated, 0)
        // Mirror the legacy `var finalRotated = rotated` semantics: the
        // buffer always starts as a copy of `rotated`, and is mutated
        // in-place to add the boundary offset when the face sits next to
        // a non-opaque neighbour on the same floor. The verts loop then
        // reads from `finalRotated` regardless.
        System.arraycopy(scratch.rotated, 0, scratch.finalRotated, 0, 12)
        if (isFaceOnBoundary(geoDir, scratch.rotated, 0)) {
            val nx = when (geoDir) { "east" -> x + 1; "west" -> x - 1; else -> x }
            val ny = when (geoDir) { "up" -> y + 1; "down" -> y - 1; else -> y }
            val nz = when (geoDir) { "south" -> z + 1; "north" -> z - 1; else -> z }
            if (nx in 0 until w && ny in 0 until h && nz in 0 until d) {
                val neighborIdx = raw[ny * wd + nz * w + nx]
                val neighborBlock = palette.entries[neighborIdx]
                val neighborElements = modelCache[neighborIdx]
                val sameFloor = floorIndexForY(ny, plan) == floorIdx
                if (sameFloor) {
                    if ((block.name.contains("glass") && neighborBlock.name == block.name) ||
                        (block.name.contains("leaves") && neighborBlock.name == block.name)) return
                    if (isFullOpaqueCube(neighborElements, neighborBlock.name)) return
                    val offsetAmount = 0.005
                    for (i in 0 until 4) {
                        val off = i * 3
                        when (geoDir) {
                            "east" -> scratch.finalRotated[off+0] -= offsetAmount
                            "west" -> scratch.finalRotated[off+0] += offsetAmount
                            "up" -> scratch.finalRotated[off+1] -= offsetAmount
                            "down" -> scratch.finalRotated[off+1] += offsetAmount
                            "south" -> scratch.finalRotated[off+2] -= offsetAmount
                            "north" -> scratch.finalRotated[off+2] += offsetAmount
                        }
                    }
                }
            }
        }
        // PR-2: switch UV / verts / normal emission to write into the
        // per-call [scratch] buffers instead of allocating fresh
        // `List<FloatArray>` and `FloatArray(3)` per face.
        getFaceUVInto(scratch.uv, 0, face, origDir, elem.from, elem.to)
        val shouldMirror = origDir in listOf("north", "south", "west")
        val noVFlip = block.name.let { n ->
            if (listOf("lantern", "brewing_stand", "campfire", "flower_pot", "chest", "sign", "bed").any { n.contains(it) }) true
            else if (n.contains("potted_")) {
                val tex = face.texture
                tex.contains("flower_pot") || tex.contains("dirt")
            } else false
        }
        computeUVsInto(origDir, scratch.uv, 0, atlasEntry, shouldMirror, noVFlip, scratch.baseUVs)
        val adjustedRot = if (origDir == "up" || origDir == "down") {
            face.rotation
        } else {
            if (block.name.contains("piston")) (face.rotation + 180) % 360 else face.rotation
        }
        applyFaceRotationInto(scratch.baseUVs, adjustedRot)
        for (i in 0 until 4) {
            val off = i * 3
            scratch.verts[off+0] = (bx + scratch.finalRotated[off+0] / 16.0).toFloat()
            scratch.verts[off+1] = (by + scratch.finalRotated[off+1] / 16.0).toFloat()
            scratch.verts[off+2] = (bz + scratch.finalRotated[off+2] / 16.0).toFloat()
        }
        dirToNormalArrayInto(scratch.normal, geoDir)
        acc.appendQuad(scratch.verts, scratch.baseUVs, scratch.normal)
    }

    /**
     * Per-RawMesh processing for [buildFloorsInto]. Body is copied verbatim
     * from the legacy `build()` method so Pass 2 produces byte-identical output.
     * Requires [atlas] to be supplied; without it the mesh is skipped.
     */
    private fun processRawMeshInto(
        mesh: RawMesh,
        block: BlockState,
        bx: Int, by: Int, bz: Int,
        rotX: Int, rotY: Int,
        atlas: PackedAtlas?,
        acc: FloorAccum,
        scratch: FaceScratch,
    ) {
        if (atlas == null) return
        val atlasEntry: AtlasEntry? = atlas.mappings[mesh.texture]
        if (atlasEntry == null) return
        val au = atlasEntry.u2 - atlasEntry.u1; val bu = atlasEntry.u1
        val av = atlasEntry.v2 - atlasEntry.v1; val bv = atlasEntry.v1

        val mRotX = if (mesh.modelRotX != 0 || mesh.modelRotY != 0) mesh.modelRotX else rotX
        val mRotY = if (mesh.modelRotX != 0 || mesh.modelRotY != 0) mesh.modelRotY else rotY

        val posList = mesh.positions
        val uvList = mesh.uvs
        val baseVi = acc.vertexCount
        val tmp = scratch.tmpVec3
        for (i in posList.indices step 3) {
            val px = posList[i]; val py = posList[i+1]; val pz = posList[i+2]
            val u = uvList[i/3*2+0]; val v = uvList[i/3*2+1]
            // PR-3: rotation writes into the shared scratch slot instead of
            // allocating a fresh `DoubleArray(3)` per vertex.
            rotatePointInto(tmp, 0, px.toDouble(), py.toDouble(), pz.toDouble(), mRotX, mRotY)
            // UV already in 0-1 from parser (divided by 16)
            val uWrap = ((u % 1f) + 1f) % 1f
            val vWrap = ((v % 1f) + 1f) % 1f
            val atlasU = (bu + uWrap * au).toFloat(); val atlasV = (bv + vWrap * av).toFloat()
            acc.positions.putFloat((bx + tmp[0] / 16.0).toFloat())
            acc.positions.putFloat((by + tmp[1] / 16.0).toFloat())
            acc.positions.putFloat((bz + tmp[2] / 16.0).toFloat())
            acc.uvs.putFloat(atlasU)
            acc.uvs.putFloat(atlasV)
        }
        // RawMesh normals
        if (mesh.normals.isNotEmpty()) {
            for (i in mesh.normals.indices step 3) {
                rotateNormalInto(
                    tmp, 0,
                    mesh.normals[i].toDouble(), mesh.normals[i+1].toDouble(), mesh.normals[i+2].toDouble(),
                    mRotX, mRotY,
                )
                acc.normals.putFloat(tmp[0].toFloat())
                acc.normals.putFloat(tmp[1].toFloat())
                acc.normals.putFloat(tmp[2].toFloat())
            }
        } else {
            // compute from positions for triangles without explicit normals
            val posList = mesh.positions
            for (i in posList.indices step 9) {
                val x0=posList[i].toDouble();val y0=posList[i+1].toDouble();val z0=posList[i+2].toDouble()
                val x1=posList[i+3].toDouble();val y1=posList[i+4].toDouble();val z1=posList[i+5].toDouble()
                val x2=posList[i+6].toDouble();val y2=posList[i+7].toDouble();val z2=posList[i+8].toDouble()
                val e1x=x1-x0;val e1y=y1-y0;val e1z=z1-z0
                val e2x=x2-x0;val e2y=y2-y0;val e2z=z2-z0
                val nx=e1y*e2z-e1z*e2y;val ny=e1z*e2x-e1x*e2z;val nz=e1x*e2y-e1y*e2x
                val len=Math.sqrt(nx*nx+ny*ny+nz*nz)
                // PR-3: write the (possibly degenerate) normal into the
                // scratch slot directly. The legacy code went through
                // `listOf((nx/len).f, …).toDouble()` which means each
                // component takes a Float round-trip before becoming a
                // Double. We reproduce that dance verbatim so the bit
                // pattern of the rotated normal matches the legacy output
                // exactly.
                val f0: Float; val f1: Float; val f2: Float
                if (len > 0) {
                    f0 = (nx / len).toFloat()
                    f1 = (ny / len).toFloat()
                    f2 = (nz / len).toFloat()
                } else {
                    f0 = 0f; f1 = 1f; f2 = 0f
                }
                rotateNormalInto(tmp, 0, f0.toDouble(), f1.toDouble(), f2.toDouble(), mRotX, mRotY)
                repeat(3){
                    acc.normals.putFloat(tmp[0].toFloat())
                    acc.normals.putFloat(tmp[1].toFloat())
                    acc.normals.putFloat(tmp[2].toFloat())
                }
            }
        }
        // triangle indices
        if (mesh.indices != null) {
            // indexed: use provided indices
            for (idx in mesh.indices) acc.indices.putInt(baseVi + idx)
        } else {
            // sequential: 3 vertices per triangle
            for (i in posList.indices step 9) {
                val triBase = baseVi + i / 3
                acc.indices.putInt(triBase)
                acc.indices.putInt(triBase + 1)
                acc.indices.putInt(triBase + 2)
            }
        }
    }

    fun build(
        region: BlockPrintRegion,
        originX: Int = 0,
        originY: Int = 0,
        originZ: Int = 0,
        options: GlbExportOptions = GlbExportOptions(),
        onProgress: ((Float) -> Unit)? = null,
    ): GlbOutput {
        // Cache palette state once (shared between Pass 1 and Pass 2).
        val paletteSize = region.palette.entries.size
        val modelCache = arrayOfNulls<List<Element>>(paletteSize)
        val rawMeshCache = arrayOfNulls<List<RawMesh>>(paletteSize)
        val rotCacheX = IntArray(paletteSize)
        val rotCacheY = IntArray(paletteSize)
        for ((blockIdx, block) in region.palette.entries.withIndex()) {
            val model = modelResolver.resolve(block.name, block.properties)
            if (model.hasTextures) {
                modelCache[blockIdx] = model.elements
                rawMeshCache[blockIdx] = model.rawMeshes
                rotCacheX[blockIdx] = model.rotX
                rotCacheY[blockIdx] = model.rotY
            }
        }

        // Pass 1: count face stats.
        val stats = countFloorStats(region, options)
        onProgress?.invoke(0.30f)

        // Pack atlas from the cached palette state.
        val atlas = texturePacker.pack(
            collectUsedTexturesFromCache(region, modelCache),
            collectTintedTexturesFromCache(region, modelCache, options.enableTinting),
            collectSpecialTintsFromCache(region, modelCache),
        )
        onProgress?.invoke(0.35f)

        // Pass 2: stream floors into a collected list.
        val collectedFloors = mutableListOf<FloorSlice>()
        buildFloorsInto(
            region = region,
            originX = originX,
            originY = originY,
            originZ = originZ,
            options = options,
            atlas = atlas,
            sink = FloorSink { floorIdx, yMin, yMax, positions, uvs, normals, indices ->
                // Convert off-heap buffers to on-heap arrays for the legacy
                // FloorSlice data class. This double-allocates the bytes,
                // which defeats the off-heap benefit; consumers should prefer
                // buildFloorsInto() with a streaming sink in production.
                val posFloats = offHeapFloatsToFloatArray(positions)
                val uvFloats = offHeapFloatsToFloatArray(uvs)
                val nrmFloats = normals?.let(::offHeapFloatsToFloatArray)
                val idxInts = offHeapIntsToIntArray(indices)
                collectedFloors.add(
                    FloorSlice(
                        yMin = yMin, yMax = yMax,
                        positions = posFloats,
                        uvs = uvFloats,
                        normals = nrmFloats,
                        indices = idxInts,
                    ),
                )
            },
        )
        return GlbOutput(
            floors = collectedFloors,
            atlasPng = atlas.pngBytes,
            atlasWidth = atlas.width,
            atlasHeight = atlas.height,
        )
    }

    private fun collectUsedTexturesFromCache(
        region: BlockPrintRegion,
        modelCache: Array<List<Element>?>,
    ): Set<String> {
        val used = mutableSetOf<String>()
        for (modelElements in modelCache) {
            if (modelElements == null) continue
            for (elem in modelElements) for (face in elem.faces.values)
                if (face.texture.isNotEmpty()) used.add(face.texture)
        }
        return used
    }

    private fun collectTintedTexturesFromCache(
        region: BlockPrintRegion,
        modelCache: Array<List<Element>?>,
        enableTinting: Boolean,
    ): Map<String, Int> {
        if (!enableTinting) return emptyMap()
        val tinted = mutableMapOf<String, Int>()
        for ((idx, modelElements) in modelCache.withIndex()) {
            val block = region.palette.entries[idx]
            if (!isBiomeTinted(block.name)) continue
            if (modelElements == null) continue
            for (elem in modelElements) for (face in elem.faces.values)
                if (face.texture.isNotEmpty() && face.tintindex != null)
                    tinted[face.texture] = face.tintindex
        }
        return tinted
    }

    private fun collectSpecialTintsFromCache(
        region: BlockPrintRegion,
        modelCache: Array<List<Element>?>,
    ): Map<String, Int> {
        val specials = mutableMapOf<String, Int>()
        for ((idx, modelElements) in modelCache.withIndex()) {
            val block = region.palette.entries[idx]
            val rgbOverride = specialTintColorOf(block.name) ?: continue
            if (modelElements == null) continue
            for (elem in modelElements) for (face in elem.faces.values)
                if (face.texture.isNotEmpty() && face.tintindex != null)
                    specials[face.texture] = rgbOverride
        }
        return specials
    }

    // 🌟【核心新增】：完备的 Minecraft 原生多视口三维投影 Auto-UV 自动补全函数
    // 关键：Minecraft 模型 JSON 里的 UV 始终是**纹理空间**（V=0 是图片顶部，V=16 是底部）。
    // 任何 16 立方体（from=0, to=16）结果与原版模型完全一致；对非立方体（如蛋糕 14×8×14）
    // 也能正确把每个面投影到纹理中对应的内容区域。`computeUVs` 不再对 V 做翻转（V-flip）——
    // 显式 UV 已经在纹理空间，翻 V 会导致切石机侧面被采到纹理的空半边而变透明。
    // The corresponding `getFaceUVInto` / `computeUVsInto` /
    // `applyFaceRotationInto` companion helpers implement the allocation-free
    // variants used by the hot path; the List/DoubleArray-returning canonical
    // forms are kept in the companion for the helper-equivalence tests.

    private fun isFaceOnBoundary(geoDir: String, rotatedCorners: List<DoubleArray>): Boolean {
        val eps = 0.01
        return when (geoDir) {
            "east"  -> rotatedCorners.all { it[0] >= 16.0 - eps }
            "west"  -> rotatedCorners.all { it[0] <= eps }
            "up"    -> rotatedCorners.all { it[1] >= 16.0 - eps }
            "down"  -> rotatedCorners.all { it[1] <= eps }
            "south" -> rotatedCorners.all { it[2] >= 16.0 - eps }
            "north" -> rotatedCorners.all { it[2] <= eps }
            else    -> false
        }
    }

    private fun isFullOpaqueCube(elements: List<Element>?, blockName: String): Boolean {
        if (elements == null || elements.isEmpty()) return false
        val hasFullCube = elements.any { elem ->
            elem.from[0] == 0.0 && elem.from[1] == 0.0 && elem.from[2] == 0.0 &&
            elem.to[0] == 16.0 && elem.to[1] == 16.0 && elem.to[2] == 16.0
        }
        if (!hasFullCube) return false
        val name = blockName.lowercase()
        val transparentFullBlocks = listOf(
            "glass", "leaves", "ice", "portal", "spawner", "barrier", "fluid", "water", "lava"
        )
        return transparentFullBlocks.none { name.contains(it) }
    }

    // ─────────────────────────────────────────────────────────────────────
    // PR-1: companion object housing the face-geometry helpers as
    // `internal` static-style functions. The 8 *Into overloads are the
    // allocation-free path used by PR-2 / PR-3 / PR-4; the 8 legacy helpers
    // remain here so the helper-equivalence tests in
    // `MeshBuilderHelpersParityTest` can assert byte-for-byte equality and
    // so existing call sites continue to resolve by bare name.
    // ─────────────────────────────────────────────────────────────────────
    companion object {

        // ── New *Into overloads (write into caller-supplied buffers) ──

        internal fun facePlaneCornersInto(
            out: DoubleArray, off: Int, dir: String, from: List<Double>, to: List<Double>,
        ) {
            val fx = from[0]; val fy = from[1]; val fz = from[2]
            val tx = to[0]; val ty = to[1]; val tz = to[2]
            when (dir) {
                "up" -> {
                    out[off+0]=fx;  out[off+1]=ty;  out[off+2]=tz
                    out[off+3]=tx;  out[off+4]=ty;  out[off+5]=tz
                    out[off+6]=tx;  out[off+7]=ty;  out[off+8]=fz
                    out[off+9]=fx;  out[off+10]=ty; out[off+11]=fz
                }
                "down" -> {
                    out[off+0]=fx;  out[off+1]=fy;  out[off+2]=fz
                    out[off+3]=tx;  out[off+4]=fy;  out[off+5]=fz
                    out[off+6]=tx;  out[off+7]=fy;  out[off+8]=tz
                    out[off+9]=fx;  out[off+10]=fy; out[off+11]=tz
                }
                "north" -> {
                    out[off+0]=fx;  out[off+1]=ty;  out[off+2]=fz
                    out[off+3]=tx;  out[off+4]=ty;  out[off+5]=fz
                    out[off+6]=tx;  out[off+7]=fy;  out[off+8]=fz
                    out[off+9]=fx;  out[off+10]=fy; out[off+11]=fz
                }
                "south" -> {
                    out[off+0]=tx;  out[off+1]=ty;  out[off+2]=tz
                    out[off+3]=fx;  out[off+4]=ty;  out[off+5]=tz
                    out[off+6]=fx;  out[off+7]=fy;  out[off+8]=tz
                    out[off+9]=tx;  out[off+10]=fy; out[off+11]=tz
                }
                "west" -> {
                    out[off+0]=fx;  out[off+1]=ty;  out[off+2]=tz
                    out[off+3]=fx;  out[off+4]=ty;  out[off+5]=fz
                    out[off+6]=fx;  out[off+7]=fy;  out[off+8]=fz
                    out[off+9]=fx;  out[off+10]=fy; out[off+11]=tz
                }
                "east" -> {
                    out[off+0]=tx;  out[off+1]=ty;  out[off+2]=fz
                    out[off+3]=tx;  out[off+4]=ty;  out[off+5]=tz
                    out[off+6]=tx;  out[off+7]=fy;  out[off+8]=tz
                    out[off+9]=tx;  out[off+10]=fy; out[off+11]=fz
                }
                else -> {
                    for (k in 0 until 4) {
                        out[off+k*3]=fx; out[off+k*3+1]=fy; out[off+k*3+2]=fz
                    }
                }
            }
        }

        internal fun rotateElementPointInto(
            out: DoubleArray, off: Int, x: Double, y: Double, z: Double, rot: ElementRotation,
        ) {
            val angle = Math.toRadians(rot.angle)
            val cx = rot.origin[0]; val cy = rot.origin[1]; val cz = rot.origin[2]
            val dx = x - cx; val dy = y - cy; val dz = z - cz
            var rx = dx; var ry = dy; var rz = dz
            val c = cos(angle); val s = sin(angle)
            when (rot.axis) {
                "x" -> { ry = dy * c - dz * s; rz = dy * s + dz * c }
                "y" -> { rx = dx * c - dz * s; rz = dx * s + dz * c }
                "z" -> { rx = dx * c - dy * s; ry = dx * s + dy * c }
            }
            if (rot.rescale) {
                val scale = 1.0 / kotlin.math.abs(c)
                val px = rx + cx; val py = ry + cy; val pz = rz + cz
                when (rot.axis) {
                    "x" -> { out[off]=px; out[off+1]=cy + (py - cy) * scale; out[off+2]=cz + (pz - cz) * scale }
                    "y" -> { out[off]=cx + (px - cx) * scale; out[off+1]=py; out[off+2]=cz + (pz - cz) * scale }
                    "z" -> { out[off]=cx + (px - cx) * scale; out[off+1]=cy + (py - cy) * scale; out[off+2]=pz }
                    else -> { out[off]=px; out[off+1]=py; out[off+2]=pz }
                }
            } else {
                out[off] = rx + cx; out[off+1] = ry + cy; out[off+2] = rz + cz
            }
        }

        internal fun rotatePointInto(
            out: DoubleArray, off: Int, x: Double, y: Double, z: Double, rotX: Int, rotY: Int,
        ) {
            if (rotX == 0 && rotY == 0) {
                out[off] = x; out[off+1] = y; out[off+2] = z
                return
            }
            var px = x; var py = y; var pz = z
            val rx = -Math.toRadians(rotX.toDouble())
            val ry = Math.toRadians(rotY.toDouble())
            val cx = 8.0; val cy = 8.0; val cz = 8.0
            if (rotX != 0) {
                val dy = py - cy; val dz = pz - cz
                py = cy + dy * cos(rx) - dz * sin(rx)
                pz = cz + dy * sin(rx) + dz * cos(rx)
            }
            if (rotY != 0) {
                val dx = px - cx; val dz = pz - cz
                px = cx + dx * cos(ry) - dz * sin(ry)
                pz = cz + dx * sin(ry) + dz * cos(ry)
            }
            out[off] = px; out[off+1] = py; out[off+2] = pz
        }

        internal fun faceNormalToDir(corners: DoubleArray, off: Int): String {
            val c0x = corners[off+0]; val c0y = corners[off+1]; val c0z = corners[off+2]
            val c1x = corners[off+3]; val c1y = corners[off+4]; val c1z = corners[off+5]
            val c3x = corners[off+9]; val c3y = corners[off+10]; val c3z = corners[off+11]
            val e1x = c1x - c0x; val e1y = c1y - c0y; val e1z = c1z - c0z
            val e2x = c3x - c0x; val e2y = c3y - c0y; val e2z = c3z - c0z
            val nx = e1y*e2z - e1z*e2y; val ny = e1z*e2x - e1x*e2z; val nz = e1x*e2y - e1y*e2x
            val ax = Math.abs(nx); val ay = Math.abs(ny); val az = Math.abs(nz)
            return when {
                ay >= ax && ay >= az -> if (ny > 0) "up" else "down"
                az >= ax && az >= ay -> if (nz > 0) "south" else "north"
                else -> if (nx > 0) "east" else "west"
            }
        }

        internal fun isFaceOnBoundary(geoDir: String, corners: DoubleArray, off: Int): Boolean {
            val eps = 0.01
            return when (geoDir) {
                "east"  -> { var ok = true; for (k in 0 until 4) if (corners[off+k*3]   < 16.0 - eps) { ok = false; break }; ok }
                "west"  -> { var ok = true; for (k in 0 until 4) if (corners[off+k*3]   > eps)        { ok = false; break }; ok }
                "up"    -> { var ok = true; for (k in 0 until 4) if (corners[off+k*3+1] < 16.0 - eps) { ok = false; break }; ok }
                "down"  -> { var ok = true; for (k in 0 until 4) if (corners[off+k*3+1] > eps)        { ok = false; break }; ok }
                "south" -> { var ok = true; for (k in 0 until 4) if (corners[off+k*3+2] < 16.0 - eps) { ok = false; break }; ok }
                "north" -> { var ok = true; for (k in 0 until 4) if (corners[off+k*3+2] > eps)        { ok = false; break }; ok }
                else    -> false
            }
        }

        internal fun getFaceUVInto(
            out: DoubleArray, off: Int, face: Face, origDir: String, from: List<Double>, to: List<Double>,
        ) {
            val src = face.uv
            if (src != null) {
                for (i in 0 until 4) out[off+i] = src[i]
                return
            }
            when (origDir) {
                "up"    -> { out[off+0]=from[0];        out[off+1]=from[2];         out[off+2]=to[0];         out[off+3]=to[2] }
                "down"  -> { out[off+0]=from[0];        out[off+1]=16.0 - to[2];    out[off+2]=to[0];         out[off+3]=16.0 - from[2] }
                "north" -> { out[off+0]=16.0 - to[0];   out[off+1]=16.0 - to[1];    out[off+2]=16.0 - from[0]; out[off+3]=16.0 - from[1] }
                "south" -> { out[off+0]=from[0];        out[off+1]=16.0 - to[1];    out[off+2]=to[0];         out[off+3]=16.0 - from[1] }
                "west"  -> { out[off+0]=from[2];        out[off+1]=16.0 - to[1];    out[off+2]=to[2];         out[off+3]=16.0 - from[1] }
                "east"  -> { out[off+0]=16.0 - to[2];   out[off+1]=16.0 - to[1];    out[off+2]=16.0 - from[2]; out[off+3]=16.0 - from[1] }
                else    -> { out[off+0]=0.0;            out[off+1]=0.0;             out[off+2]=16.0;           out[off+3]=16.0 }
            }
        }

        internal fun computeUVsInto(
            dir: String, uv: DoubleArray, uvOff: Int, entry: AtlasEntry,
            mirror: Boolean, noVFlip: Boolean = false, outUVs: Array<FloatArray>,
        ) {
            var u1r = uv[uvOff+0] / 16.0; var u2r = uv[uvOff+2] / 16.0
            if (mirror) { val t = u1r; u1r = u2r; u2r = t }
            val v1r = uv[uvOff+1] / 16.0; val v2r = uv[uvOff+3] / 16.0
            val au = entry.u2 - entry.u1; val av = entry.v2 - entry.v1
            val bu = entry.u1; val bv = entry.v1
            val tu1 = (bu + u1r * au).toFloat(); val tv1 = (bv + v1r * av).toFloat()
            val tu2 = (bu + u2r * au).toFloat(); val tv2 = (bv + v2r * av).toFloat()
            if (dir == "north" || dir == "south" || dir == "west" || dir == "east") {
                outUVs[0][0]=tu1; outUVs[0][1]=tv1
                outUVs[1][0]=tu2; outUVs[1][1]=tv1
                outUVs[2][0]=tu2; outUVs[2][1]=tv2
                outUVs[3][0]=tu1; outUVs[3][1]=tv2
            } else {
                outUVs[0][0]=tu1; outUVs[0][1]=tv2
                outUVs[1][0]=tu2; outUVs[1][1]=tv2
                outUVs[2][0]=tu2; outUVs[2][1]=tv1
                outUVs[3][0]=tu1; outUVs[3][1]=tv1
            }
        }

        internal fun applyFaceRotationInto(uvs: Array<FloatArray>, rotation: Int) {
            val steps = ((rotation % 360) + 360) % 360 / 90
            if (steps == 0) return
            // In-place equivalent of the legacy:
            //   repeat(steps) { val last = uvs.removeAt(size-1); uvs.add(0, last) }
            // A 4-step right-rotation on a 4-slot array can be done with a
            // 4-pointer swap chain and zero allocations.
            repeat(steps) {
                val last = uvs[3]
                uvs[3] = uvs[2]
                uvs[2] = uvs[1]
                uvs[1] = uvs[0]
                uvs[0] = last
            }
        }

        /** Writes the (nx, ny, nz) face normal for [dir] into [out] without allocating. */
        internal fun dirToNormalArrayInto(out: FloatArray, dir: String) {
            when (dir) {
                "up"    -> { out[0] = 0f;  out[1] = 1f;  out[2] = 0f  }
                "down"  -> { out[0] = 0f;  out[1] = -1f; out[2] = 0f  }
                "north" -> { out[0] = 0f;  out[1] = 0f;  out[2] = -1f }
                "south" -> { out[0] = 0f;  out[1] = 0f;  out[2] = 1f  }
                "east"  -> { out[0] = 1f;  out[1] = 0f;  out[2] = 0f  }
                else    -> { out[0] = -1f; out[1] = 0f;  out[2] = 0f  }
            }
        }

        /**
         * Rotates a 3D normal vector by blockstate `rotX`/`rotY` and writes
         * the result into [out] at [off]. Equivalent to the legacy
         * `rotateNormal(DoubleArray(3), rotX, rotY)` but without allocating
         * a fresh `DoubleArray(3)` per call.
         */
        internal fun rotateNormalInto(
            out: DoubleArray, off: Int, x: Double, y: Double, z: Double, rotX: Int, rotY: Int,
        ) {
            if (rotX == 0 && rotY == 0) {
                out[off] = x; out[off+1] = y; out[off+2] = z
                return
            }
            var nx = x; var ny = y; var nz = z
            val rx = -Math.toRadians(rotX.toDouble())
            val ry = Math.toRadians(rotY.toDouble())
            if (rotX != 0) { val dy = ny; val dz = nz; ny = dy * cos(rx) - dz * sin(rx); nz = dy * sin(rx) + dz * cos(rx) }
            if (rotY != 0) { val dx = nx; val dz = nz; nx = dx * cos(ry) - dz * sin(ry); nz = dx * sin(ry) + dz * cos(ry) }
            out[off] = nx; out[off+1] = ny; out[off+2] = nz
        }

        /**
         * Legacy helpers moved to the companion (PR-3) so the parity tests
         * can compare the new `dirToNormalArrayInto` / `rotateNormalInto`
         * helpers against the canonical allocation-returning form.
         */
        internal fun dirToNormalArray(dir: String): FloatArray = when (dir) {
            "up" -> floatArrayOf(0f, 1f, 0f); "down" -> floatArrayOf(0f, -1f, 0f)
            "north" -> floatArrayOf(0f, 0f, -1f); "south" -> floatArrayOf(0f, 0f, 1f)
            "east" -> floatArrayOf(1f, 0f, 0f); else -> floatArrayOf(-1f, 0f, 0f)
        }

        internal fun rotateNormal(n: DoubleArray, rotX: Int, rotY: Int): DoubleArray {
            if (rotX == 0 && rotY == 0) return n.copyOf()
            var x = n[0]; var y = n[1]; var z = n[2]
            val rx = -Math.toRadians(rotX.toDouble())
            val ry = Math.toRadians(rotY.toDouble())
            if (rotX != 0) { val dy = y; val dz = z; y = dy * cos(rx) - dz * sin(rx); z = dy * sin(rx) + dz * cos(rx) }
            if (rotY != 0) { val dx = x; val dz = z; x = dx * cos(ry) - dz * sin(ry); z = dx * sin(ry) + dz * cos(ry) }
            return doubleArrayOf(x, y, z)
        }

        // ── Legacy helpers (kept `internal` so PR-1 tests can compare
        //    new *Into versions byte-for-byte against the canonical output).
        //    Will be deleted in PR-3 / PR-4 once the *Into versions are
        //    fully wired in and the only callers become the tests.

        internal fun getFaceUV(face: Face, origDir: String, from: List<Double>, to: List<Double>): List<Double> {
            if (face.uv != null) return face.uv
            return when (origDir) {
                "up"    -> listOf(from[0], from[2], to[0], to[2])
                "down"  -> listOf(from[0], 16.0 - to[2], to[0], 16.0 - from[2])
                "north" -> listOf(16.0 - to[0], 16.0 - to[1], 16.0 - from[0], 16.0 - from[1])
                "south" -> listOf(from[0], 16.0 - to[1], to[0], 16.0 - from[1])
                "west"  -> listOf(from[2], 16.0 - to[1], to[2], 16.0 - from[1])
                "east"  -> listOf(16.0 - to[2], 16.0 - to[1], 16.0 - from[2], 16.0 - from[1])
                else    -> listOf(0.0, 0.0, 16.0, 16.0)
            }
        }

        internal fun computeUVs(dir: String, uv: List<Double>, entry: AtlasEntry, mirror: Boolean, noVFlip: Boolean = false): List<FloatArray> {
            var u1r = uv[0] / 16.0; var u2r = uv[2] / 16.0
            if (mirror) { val t = u1r; u1r = u2r; u2r = t }
            val v1r = uv[1] / 16.0; val v2r = uv[3] / 16.0
            val au = entry.u2 - entry.u1; val av = entry.v2 - entry.v1
            val bu = entry.u1; val bv = entry.v1
            val tu1 = (bu + u1r * au).toFloat(); val tv1 = (bv + v1r * av).toFloat()
            val tu2 = (bu + u2r * au).toFloat(); val tv2 = (bv + v2r * av).toFloat()
            return if (dir in listOf("north", "south", "west", "east")) {
                listOf(floatArrayOf(tu1, tv1), floatArrayOf(tu2, tv1), floatArrayOf(tu2, tv2), floatArrayOf(tu1, tv2))
            } else {
                listOf(floatArrayOf(tu1, tv2), floatArrayOf(tu2, tv2), floatArrayOf(tu2, tv1), floatArrayOf(tu1, tv1))
            }
        }

        internal fun facePlaneCorners(dir: String, from: List<Double>, to: List<Double>): List<DoubleArray> {
            val fx = from[0]; val fy = from[1]; val fz = from[2]
            val tx = to[0]; val ty = to[1]; val tz = to[2]
            return when (dir) {
                "up" -> listOf(doubleArrayOf(fx,ty,tz),doubleArrayOf(tx,ty,tz),doubleArrayOf(tx,ty,fz),doubleArrayOf(fx,ty,fz))
                "down" -> listOf(doubleArrayOf(fx,fy,fz),doubleArrayOf(tx,fy,fz),doubleArrayOf(tx,fy,tz),doubleArrayOf(fx,fy,tz))
                "north" -> listOf(doubleArrayOf(fx,ty,fz),doubleArrayOf(tx,ty,fz),doubleArrayOf(tx,fy,fz),doubleArrayOf(fx,fy,fz))
                "south" -> listOf(doubleArrayOf(tx,ty,tz),doubleArrayOf(fx,ty,tz),doubleArrayOf(fx,fy,tz),doubleArrayOf(tx,fy,tz))
                "west" -> listOf(doubleArrayOf(fx,ty,tz),doubleArrayOf(fx,ty,fz),doubleArrayOf(fx,fy,fz),doubleArrayOf(fx,fy,tz))
                "east" -> listOf(doubleArrayOf(tx,ty,fz),doubleArrayOf(tx,ty,tz),doubleArrayOf(tx,fy,tz),doubleArrayOf(tx,fy,fz))
                else -> listOf(doubleArrayOf(fx,fy,fz),doubleArrayOf(fx,fy,fz),doubleArrayOf(fx,fy,fz),doubleArrayOf(fx,fy,fz))
            }
        }

        internal fun rotateElementPoint(p: DoubleArray, rot: ElementRotation): DoubleArray {
            val angle = Math.toRadians(rot.angle)
            val cx = rot.origin[0]; val cy = rot.origin[1]; val cz = rot.origin[2]
            val dx = p[0] - cx; val dy = p[1] - cy; val dz = p[2] - cz
            var x = dx; var y = dy; var z = dz
            val c = cos(angle); val s = sin(angle)
            when (rot.axis) {
                "x" -> { y = dy * c - dz * s; z = dy * s + dz * c }
                "y" -> { x = dx * c - dz * s; z = dx * s + dz * c }
                "z" -> { x = dx * c - dy * s; y = dx * s + dy * c }
            }
            if (rot.rescale) {
                val scale = 1.0 / kotlin.math.abs(c)
                val rx = x + cx; val ry = y + cy; val rz = z + cz
                return when (rot.axis) {
                    "x" -> doubleArrayOf(rx, cy + (ry - cy) * scale, cz + (rz - cz) * scale)
                    "y" -> doubleArrayOf(cx + (rx - cx) * scale, ry, cz + (rz - cz) * scale)
                    "z" -> doubleArrayOf(cx + (rx - cx) * scale, cy + (ry - cy) * scale, rz)
                    else -> doubleArrayOf(rx, ry, rz)
                }
            }
            return doubleArrayOf(x + cx, y + cy, z + cz)
        }

        internal fun rotatePoint(p: DoubleArray, rotX: Int, rotY: Int): DoubleArray {
            if (rotX == 0 && rotY == 0) return p.copyOf()
            var x = p[0]; var y = p[1]; var z = p[2]
            val rx = -Math.toRadians(rotX.toDouble())
            val ry = Math.toRadians(rotY.toDouble())
            val cx = 8.0; val cy = 8.0; val cz = 8.0
            if (rotX != 0) { val dy = y - cy; val dz = z - cz; y = cy + dy * cos(rx) - dz * sin(rx); z = cz + dy * sin(rx) + dz * cos(rx) }
            if (rotY != 0) { val dx = x - cx; val dz = z - cz; x = cx + dx * cos(ry) - dz * sin(ry); z = cz + dx * sin(ry) + dz * cos(ry) }
            return doubleArrayOf(x, y, z)
        }

        internal fun faceNormalToDir(corners: List<DoubleArray>): String {
            val e1 = doubleArrayOf(corners[1][0]-corners[0][0], corners[1][1]-corners[0][1], corners[1][2]-corners[0][2])
            val e2 = doubleArrayOf(corners[3][0]-corners[0][0], corners[3][1]-corners[0][1], corners[3][2]-corners[0][2])
            val nx = e1[1]*e2[2] - e1[2]*e2[1]; val ny = e1[2]*e2[0] - e1[0]*e2[2]; val nz = e1[0]*e2[1] - e1[1]*e2[0]
            val ax = Math.abs(nx); val ay = Math.abs(ny); val az = Math.abs(nz)
            return when { ay >= ax && ay >= az -> if (ny > 0) "up" else "down"
                az >= ax && az >= ay -> if (nz > 0) "south" else "north"
                else -> if (nx > 0) "east" else "west" }
        }

        internal fun applyFaceRotation(uvList: List<FloatArray>, rotation: Int): List<FloatArray> {
            val steps = ((rotation % 360) + 360) % 360 / 90
            if (steps == 0) return uvList
            val uvs = uvList.toMutableList()
            repeat(steps) { val last = uvs.removeAt(uvs.size - 1); uvs.add(0, last) }
            return uvs
        }

        internal fun isFaceOnBoundary(geoDir: String, rotatedCorners: List<DoubleArray>): Boolean {
            val eps = 0.01
            return when (geoDir) {
                "east"  -> rotatedCorners.all { it[0] >= 16.0 - eps }
                "west"  -> rotatedCorners.all { it[0] <= eps }
                "up"    -> rotatedCorners.all { it[1] >= 16.0 - eps }
                "down"  -> rotatedCorners.all { it[1] <= eps }
                "south" -> rotatedCorners.all { it[2] >= 16.0 - eps }
                "north" -> rotatedCorners.all { it[2] <= eps }
                else    -> false
            }
        }

        /** Factory for the per-call scratch buffer set. */
        internal fun newFaceScratch(): FaceScratch = FaceScratch()
    }
}

/**
 * Per-call scratch buffer set for face-level geometry transforms. Allocated
 * once at the top of [MeshBuilder.countFloorStats] and
 * [MeshBuilder.buildFloorsInto] and reused for every face processed by that
 * call. Eliminates the per-face `List` / `DoubleArray(3)` / `FloatArray(3)`
 * allocations that the legacy helpers produced (and that the GC otherwise
 * had to track across millions of faces for 500k-block models).
 *
 * **Not thread-safe**: one instance per [MeshBuilder] call, never shared.
 */
internal class FaceScratch {
    /** 4 corners × 3 doubles — output of [MeshBuilder.Companion.facePlaneCornersInto]. */
    val corners: DoubleArray = DoubleArray(12)
    /** 4 corners × 3 doubles — after per-element rotation ([MeshBuilder.Companion.rotateElementPointInto]). */
    val elemRotated: DoubleArray = DoubleArray(12)
    /** 4 corners × 3 doubles — after blockstate rotX/Y rotation ([MeshBuilder.Companion.rotatePointInto]). */
    val rotated: DoubleArray = DoubleArray(12)
    /** 4 corners × 3 doubles — after face-boundary offset (small ε to avoid z-fighting). */
    val finalRotated: DoubleArray = DoubleArray(12)
    /** 4 doubles — output of [MeshBuilder.Companion.getFaceUVInto]. */
    val uv: DoubleArray = DoubleArray(4)
    /** 4 × (u, v) float slots — output of [MeshBuilder.Companion.computeUVsInto]. */
    val baseUVs: Array<FloatArray> = Array(4) { FloatArray(2) }
    /** 4 × (u, v) float slots — output of [MeshBuilder.Companion.applyFaceRotationInto] (in-place). */
    val faceUVs: Array<FloatArray> = Array(4) { FloatArray(2) }
    /** 4 vertices × 3 floats — input to [FloorAccum.appendQuad]. */
    val verts: FloatArray = FloatArray(12)
    /** 3 floats — face normal vector (input to [FloorAccum.appendQuad]). */
    val normal: FloatArray = FloatArray(3)
    /** 3 doubles — scratch slot for per-vertex rotation result inside
     *  [MeshBuilder.processRawMeshInto] so the per-triangle normal
     *  computation doesn't allocate a fresh `DoubleArray(3)`. */
    val tmpVec3: DoubleArray = DoubleArray(3)
}

// ── Floor splitting ────────────────────────────────────────────────────────
//
// These helpers are kept at file scope (not inside MeshBuilder) so they can
// be unit-tested without instantiating a real ModelResolver / TexturePacker,
// both of which require a populated assets directory on disk.

internal data class FloorPlan(
    val effectiveFloorHeight: Int,
    val floorCount: Int,
)

internal fun computeFloorPlan(regionHeight: Int, floorHeight: Int): FloorPlan {
    require(regionHeight >= 0) { "regionHeight must be non-negative, got $regionHeight" }
    val fh = floorHeight.coerceAtLeast(0)
    val effective = if (fh == 0) regionHeight else fh
    val count = if (effective == 0) 1 else (regionHeight + effective - 1) / effective
    return FloorPlan(effectiveFloorHeight = effective, floorCount = count.coerceAtLeast(1))
}

internal fun floorIndexForY(y: Int, plan: FloorPlan): Int {
    val raw = y / plan.effectiveFloorHeight
    return raw.coerceAtMost(plan.floorCount - 1)
}

/**
 * Per-floor off-heap accumulator for positions, UVs, normals, and indices.
 *
 * Replaces the previous on-heap FloatBuf/IntBuf implementation. Off-heap memory
 * bypasses ART's per-process heap limit, which is critical on Android where the
 * default cap is 256 MB. The 64 KB on-heap staging array in [GlbWriter] is the
 * only on-heap allocation in the geometry path.
 */
internal class FloorAccum(initialCapacityFloats: Int = 1024, initialCapacityInts: Int = 1024) : AutoCloseable {
    val positions = OffHeapBuf(initialCapacityFloats * 4)              // 3 floats/vertex = 12 bytes/vertex
    val uvs = OffHeapBuf(initialCapacityFloats * 2 / 3 * 4)            // 2 floats/vertex = 8 bytes/vertex
    val normals = OffHeapBuf(initialCapacityFloats * 4)              // 3 floats/vertex = 12 bytes/vertex
    val indices = OffHeapBuf(initialCapacityInts * 4)                 // 1 int/index = 4 bytes/index
    val vertexCount: Int get() = positions.sizeBytes() / 12

    /**
     * Append one quad (4 vertices, 2 triangles) using local indices starting
     * from this accumulator's current vertexCount. Uses off-heap buffers; the
     * 64 KB on-heap staging array is the only on-heap allocation in the
     * geometry path.
     *
     * Arguments are flat arrays / per-vertex slots supplied by the caller's
     * [FaceScratch]; passing the scratch directly avoids the per-face
     * `List<FloatArray>(4)` and `FloatArray(3)` allocations the legacy
     * signature used to require.
     *
     * @param verts 12 floats — 4 vertices × (x, y, z)
     * @param uvs   4 entries, each a `FloatArray(2)` (u, v)
     * @param normal 3 floats — (nx, ny, nz), broadcast to all 4 vertices
     */
    fun appendQuad(
        verts: FloatArray,
        uvs: Array<FloatArray>,
        normal: FloatArray,
    ) {
        require(verts.size == 12) { "verts must be 12 floats, got ${verts.size}" }
        require(uvs.size == 4) { "uvs must have 4 entries, got ${uvs.size}" }
        require(normal.size == 3) { "normal must be 3 floats, got ${normal.size}" }
        val base = vertexCount
        for (f in verts) positions.putFloat(f)
        for (u in uvs) for (f in u) this.uvs.putFloat(f)
        val nx = normal[0]; val ny = normal[1]; val nz = normal[2]
        repeat(4) { this.normals.putFloat(nx); this.normals.putFloat(ny); this.normals.putFloat(nz) }
        this.indices.putInt(base)
        this.indices.putInt(base + 1)
        this.indices.putInt(base + 2)
        this.indices.putInt(base)
        this.indices.putInt(base + 2)
        this.indices.putInt(base + 3)
    }

    /**
     * Reset all buffers for the next floor's data. Keeps the underlying
     * native memory (capacity unchanged) to avoid re-allocation.
     */
    fun reset() {
        positions.clear()
        uvs.clear()
        normals.clear()
        indices.clear()
    }

    /**
     * Release all native memory. After close, the buffers should not be
     * reused.
     */
    override fun close() {
        positions.close()
        uvs.close()
        normals.close()
        indices.close()
    }
}

/**
 * Convert an off-heap float buffer to an on-heap FloatArray. Used by the
 * legacy [build] entry point to bridge to the [FloorSlice] data class which
 * still uses [FloatArray] for backward compatibility.
 */
internal fun offHeapFloatsToFloatArray(buf: OffHeapBuf): FloatArray {
    val bytes = buf.toByteArray()
    val out = FloatArray(bytes.size / 4)
    val sbb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    for (i in out.indices) out[i] = sbb.getFloat()
    return out
}

/**
 * Convert an off-heap int buffer to an on-heap IntArray. Used by the legacy
 * [build] entry point to bridge to the [FloorSlice] data class which still
 * uses [IntArray] for backward compatibility.
 */
internal fun offHeapIntsToIntArray(buf: OffHeapBuf): IntArray {
    val bytes = buf.toByteArray()
    val out = IntArray(bytes.size / 4)
    val sbb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    for (i in out.indices) out[i] = sbb.getInt()
    return out
}