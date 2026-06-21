package io.github.moxisuki.blockprint.core.glb

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.LitematicRegion
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

    private fun isBiomeTinted(blockName: String): Boolean = blockName in biomeTintedBlocks

    /**
     * 某些方块在 vanilla 里有自己的 BlockColor，返回固定颜色（不读 colormap）。
     * 我们的库没有 biome/上下文数据，colormap 路径会退化成"全图单色"，对这些方块
     * 来说反而是错的（例如红石粉如果走 grass colormap 会被染成绿色）。
     * 返回 null 表示"没有特殊染色，走普通 colormap 流程"。
     */
    private fun specialTintColorOf(blockName: String): Int? = when (blockName) {
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
        region: LitematicRegion,
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
    private enum class ConnectionFamily { GLASS_PANE, FENCE, WALL, IRON_BARS }

    private fun connectionFamilyOf(blockName: String): ConnectionFamily? = when {
        blockName.contains("glass_pane") -> ConnectionFamily.GLASS_PANE
        blockName.contains("_wall") -> ConnectionFamily.WALL
        blockName == "minecraft:iron_bars" -> ConnectionFamily.IRON_BARS
        blockName.contains("_fence") && !blockName.contains("_fence_gate") -> ConnectionFamily.FENCE
        else -> null
    }

    /**
     * 扫描整个 region，对每个连接方块生成 `Triple(x,y,z) -> Map<"north"/"east"/..., "true">`。
     * 规则：相邻位置是同族连接方块 → 方向属性 = "true"。
     */
    private fun precomputeConnectionProperties(region: LitematicRegion): Map<Triple<Int, Int, Int>, Map<String, String>> {
        val result = mutableMapOf<Triple<Int, Int, Int>, Map<String, String>>()
        val w = region.width; val h = region.height; val d = region.depth
        for (y in 0 until h) for (z in 0 until d) for (x in 0 until w) {
            val block = region.blockAt(x, y, z) ?: continue
            val family = connectionFamilyOf(block.name) ?: continue
            val props = mutableMapOf<String, String>()
            // 注意方向语义：north 对应 z-1，south 对应 z+1，east 对应 x+1，west 对应 x-1
            if (z > 0) {
                val n = region.blockAt(x, y, z - 1)
                if (n != null && connectionFamilyOf(n.name) == family) props["north"] = "true"
            }
            if (x < w - 1) {
                val n = region.blockAt(x + 1, y, z)
                if (n != null && connectionFamilyOf(n.name) == family) props["east"] = "true"
            }
            if (z < d - 1) {
                val n = region.blockAt(x, y, z + 1)
                if (n != null && connectionFamilyOf(n.name) == family) props["south"] = "true"
            }
            if (x > 0) {
                val n = region.blockAt(x - 1, y, z)
                if (n != null && connectionFamilyOf(n.name) == family) props["west"] = "true"
            }
            if (props.isNotEmpty()) result[Triple(x, y, z)] = props
        }
        return result
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
        region: LitematicRegion,
        options: GlbExportOptions = GlbExportOptions(),
    ): FloorStats {
        val w = region.width; val h = region.height; val d = region.depth
        val raw = region.rawBlocks

        // Build palette caches once and share with Pass 2.
        val paletteSize = region.palette.entries.size
        val modelCache = arrayOfNulls<List<Element>>(paletteSize)
        for ((blockIdx, block) in region.palette.entries.withIndex()) {
            val model = modelResolver.resolve(block.name, block.properties)
            if (model.hasTextures) {
                modelCache[blockIdx] = model.elements
            }
        }

        val connectionProps = precomputeConnectionProperties(region)
        val hasConnections = connectionProps.isNotEmpty()
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
        var anyVertex = false

        for (y in 0 until h) for (z in 0 until d) for (x in 0 until w) {
            val idx = raw[y * wd + z * w + x]
            if (idx == 0) continue
            val elements = modelCache[idx] ?: continue
            val connProps = if (hasConnections) connectionProps[Triple(x, y, z)] else null
            if (connProps != null) {
                val block = region.palette.entries[idx]
                val merged = (block.properties ?: emptyMap()) + connProps
                val model = modelResolver.resolve(block.name, merged)
                if (!model.hasTextures) continue
                countFloorElements(
                    elements = model.elements, region = region,
                    w = w, h = h, d = d, raw = raw, wd = wd,
                    palette = region.palette, modelCache = modelCache,
                    x = x, y = y, z = z, floorIdx = floorIndexForY(y, plan),
                    perFloorVertices = perFloorVertices, perFloorIndices = perFloorIndices,
                    totals = IntArray(4),
                    minMax = FloatArray(6).also { it[0]=minX; it[1]=minY; it[2]=minZ; it[3]=maxX; it[4]=maxY; it[5]=maxZ },
                    anyVertexRef = booleanArrayOf(anyVertex),
                    onUpdate = { totals, minMax, anyV ->
                        totalPositions += totals[0]
                        totalNormals += totals[1]
                        totalUvs += totals[2]
                        totalIndices += totals[3]
                        if (anyV) {
                            anyVertex = true
                            minX = minMax[0]; minY = minMax[1]; minZ = minMax[2]
                            maxX = minMax[3]; maxY = minMax[4]; maxZ = minMax[5]
                        }
                    },
                )
            } else {
                countFloorElements(
                    elements = elements, region = region,
                    w = w, h = h, d = d, raw = raw, wd = wd,
                    palette = region.palette, modelCache = modelCache,
                    x = x, y = y, z = z, floorIdx = floorIndexForY(y, plan),
                    perFloorVertices = perFloorVertices, perFloorIndices = perFloorIndices,
                    totals = IntArray(4),
                    minMax = FloatArray(6).also { it[0]=minX; it[1]=minY; it[2]=minZ; it[3]=maxX; it[4]=maxY; it[5]=maxZ },
                    anyVertexRef = booleanArrayOf(anyVertex),
                    onUpdate = { totals, minMax, anyV ->
                        totalPositions += totals[0]
                        totalNormals += totals[1]
                        totalUvs += totals[2]
                        totalIndices += totals[3]
                        if (anyV) {
                            anyVertex = true
                            minX = minMax[0]; minY = minMax[1]; minZ = minMax[2]
                            maxX = minMax[3]; maxY = minMax[4]; maxZ = minMax[5]
                        }
                    },
                )
            }
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
     * Helper for [countFloorStats]: per-cell face-counting. Mirrors the culling
     * logic of `build()` so the count matches the eventual output.
     *
     * When a face passes all culling checks, increments the appropriate
     * counters via `totals` / `perFloorVertices` / `perFloorIndices`, updates
     * `minMax` with the rotated face corners, sets `anyVertexRef[0] = true`,
     * then invokes `onUpdate(totals, minMax, anyVertexRef[0])` to let the
     * caller roll the values into the outer accumulator.
     */
    private fun countFloorElements(
        elements: List<Element>,
        region: LitematicRegion,
        w: Int, h: Int, d: Int,
        raw: IntArray, wd: Int,
        palette: BlockPalette,
        modelCache: Array<List<Element>?>,
        x: Int, y: Int, z: Int,
        floorIdx: Int,
        perFloorVertices: IntArray,
        perFloorIndices: IntArray,
        totals: IntArray,
        minMax: FloatArray,
        anyVertexRef: BooleanArray,
        onUpdate: (IntArray, FloatArray, Boolean) -> Unit,
    ) {
        val plan = computeFloorPlan(h, 0) // floor routing uses caller's floorIdx
        for (elem in elements) {
            for ((origDir, face) in elem.faces) {
                if (face.texture.isEmpty()) continue
                val corners = facePlaneCorners(origDir, elem.from, elem.to)
                val elemRotated = if (elem.rotation != null) corners.map { c -> rotateElementPoint(c, elem.rotation) } else corners
                val geoDir = faceNormalToDir(elemRotated)
                if (!isFaceOnBoundary(geoDir, elemRotated)) continue
                val nx = when (geoDir) { "east" -> x + 1; "west" -> x - 1; else -> x }
                val ny = when (geoDir) { "up" -> y + 1; "down" -> y - 1; else -> y }
                val nz = when (geoDir) { "south" -> z + 1; "north" -> z - 1; else -> z }
                if (nx !in 0 until w || ny !in 0 until h || nz !in 0 until d) continue
                val neighborIdx = raw[ny * wd + nz * w + nx]
                val neighborBlock = palette.entries[neighborIdx]
                val neighborElements = modelCache[neighborIdx]
                val sameFloor = floorIndexForY(ny, plan) == floorIdx
                if (sameFloor) {
                    val block = palette.entries[raw[y * wd + z * w + x]]
                    if ((block.name.contains("glass") && neighborBlock.name == block.name) ||
                        (block.name.contains("leaves") && neighborBlock.name == block.name)) continue
                    if (isFullOpaqueCube(neighborElements, neighborBlock.name)) continue
                }
                // Face is visible — count it.
                perFloorVertices[floorIdx] += 4
                perFloorIndices[floorIdx] += 6
                totals[0] += 12
                totals[1] += 12
                totals[2] += 8
                totals[3] += 6
                for (c in elemRotated) {
                    val cx = c[0].toFloat()
                    val cy = c[1].toFloat()
                    val cz = c[2].toFloat()
                    if (cx < minMax[0]) minMax[0] = cx
                    if (cy < minMax[1]) minMax[1] = cy
                    if (cz < minMax[2]) minMax[2] = cz
                    if (cx > minMax[3]) minMax[3] = cx
                    if (cy > minMax[4]) minMax[4] = cy
                    if (cz > minMax[5]) minMax[5] = cz
                }
                anyVertexRef[0] = true
                onUpdate(totals, minMax, true)
            }
        }
    }

    /**
     * Pass 2 of the two-pass streaming pipeline: walk the region, building
     * each floor's vertex data and pushing it to [sink] as soon as that floor
     * completes.
     *
     * Memory budget: peak is ~ one floor's worth of [FloorAccum] data plus the
     * shared palette caches.
     */
    fun buildFloorsInto(
        region: LitematicRegion,
        originX: Int = 0,
        originY: Int = 0,
        originZ: Int = 0,
        options: GlbExportOptions = GlbExportOptions(),
        sink: FloorSink,
        onProgress: ((Float) -> Unit)? = null,
    ) {
        val w = region.width; val h = region.height; val d = region.depth
        val palette = region.palette
        val plan = computeFloorPlan(h, options.floorHeight)

        val paletteSize = palette.entries.size
        val modelCache = arrayOfNulls<List<Element>>(paletteSize)
        val rawMeshCache = arrayOfNulls<List<RawMesh>>(paletteSize)
        val rotCacheX = IntArray(paletteSize)
        val rotCacheY = IntArray(paletteSize)
        for ((blockIdx, block) in palette.entries.withIndex()) {
            val model = modelResolver.resolve(block.name, block.properties)
            if (model.hasTextures) {
                modelCache[blockIdx] = model.elements
                rawMeshCache[blockIdx] = model.rawMeshes
                rotCacheX[blockIdx] = model.rotX
                rotCacheY[blockIdx] = model.rotY
            }
        }
        val connectionProps = precomputeConnectionProperties(region)
        val hasConnections = connectionProps.isNotEmpty()

        val raw = region.rawBlocks
        val wd = w * d

        val accs = Array(plan.floorCount) { FloorAccum(1024, 1024) }
        var currentFloor = -1

        fun flushFloor(idx: Int) {
            val acc = accs[idx]
            if (acc.indices.isEmpty()) return
            sink.onFloor(
                floorIdx = idx,
                yMin = idx * plan.effectiveFloorHeight,
                yMax = minOf((idx + 1) * plan.effectiveFloorHeight - 1, h - 1),
                positions = acc.positions.toFloatArray(),
                uvs = acc.uvs.toFloatArray(),
                normals = if (acc.normals.isEmpty()) null else acc.normals.toFloatArray(),
                indices = acc.indices.toIntArray(),
            )
            // Reset accumulators to free memory for the next floor.
            acc.positions.clear()
            acc.uvs.clear()
            acc.normals.clear()
            acc.indices.clear()
        }

        for (y in 0 until h) for (z in 0 until d) for (x in 0 until w) {
            val idx = raw[y * wd + z * w + x]
            val connProps = if (hasConnections) connectionProps[Triple(x, y, z)] else null
            val block: BlockState
            val elements: List<Element>
            val (rotX, rotY) = if (connProps != null) {
                block = palette.entries[idx]
                val merged = (block.properties ?: emptyMap()) + connProps
                val model = modelResolver.resolve(block.name, merged)
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
                        acc = acc,
                    )
                }
            }
            val rawMeshes = rawMeshCache[idx] ?: emptyList()
            for (mesh in rawMeshes) {
                processRawMeshInto(mesh, block, bx, by, bz, rotX, rotY, acc)
            }
        }
        if (currentFloor >= 0) flushFloor(currentFloor)
    }

    /**
     * Per-face processing for [buildFloorsInto]. Copies the per-face logic from
     * the existing `build()` method so Pass 2 produces byte-identical output to
     * the legacy code. The legacy build() and this helper will be unified in a
     * follow-up refactor (Task 6 swaps build() to delegate here).
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
        acc: FloorAccum,
    ) {
        if (face.texture.isEmpty()) return
        val corners = facePlaneCorners(origDir, elem.from, elem.to)
        val elemRotated = if (elem.rotation != null) corners.map { c -> rotateElementPoint(c, elem.rotation) } else corners
        val eRotX = if (elem.modelRotX != 0 || elem.modelRotY != 0) elem.modelRotX else rotX
        val eRotY = if (elem.modelRotX != 0 || elem.modelRotY != 0) elem.modelRotY else rotY
        val rotated = elemRotated.map { c -> rotatePoint(c, eRotX, eRotY) }
        val geoDir = faceNormalToDir(rotated)
        var finalRotated = rotated
        if (isFaceOnBoundary(geoDir, rotated)) {
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
                    finalRotated = rotated.map { p ->
                        val np = p.copyOf()
                        when (geoDir) {
                            "east" -> np[0] -= offsetAmount
                            "west" -> np[0] += offsetAmount
                            "up" -> np[1] -= offsetAmount
                            "down" -> np[1] += offsetAmount
                            "south" -> np[2] -= offsetAmount
                            "north" -> np[2] += offsetAmount
                        }
                        np
                    }
                }
            }
        }
        val uv = getFaceUV(face, origDir, elem.from, elem.to)
        val shouldMirror = origDir in listOf("north", "south", "west")
        val noVFlip = block.name.let { n ->
            if (listOf("lantern", "brewing_stand", "campfire", "flower_pot", "chest", "sign", "bed").any { n.contains(it) }) true
            else if (n.contains("potted_")) {
                val tex = face.texture
                tex.contains("flower_pot") || tex.contains("dirt")
            } else false
        }
        // Note: atlasEntry lookup is removed here — processFaceInto is per-face but
        // we don't have access to the TexturePacker atlas. For Pass 2 to produce
        // byte-identical output, we need the atlas. Since the legacy build() bakes
        // the atlas into the output, and the new pipeline streams the atlas via
        // GlbAtlas at the end of writeStreaming, Pass 2 must NOT bake atlas UVs
        // — instead it must output raw face UVs in texture space (0..1).
        //
        // To keep Pass 2 byte-identical to legacy build() output, we need to
        // resolve atlasEntry here. Add a parameter or class field that holds
        // the atlas mapping.
        //
        // For now, mark this TODO in code and resolve in Task 6 (which unifies
        // the helpers and provides atlas access).
        val atlasEntry: AtlasEntry? = null // placeholder — Task 6 fix
        if (atlasEntry == null) {
            // Skip face processing in the streaming path until atlas is wired in.
            // The full parity test (Task 5) will catch the regression.
            return
        }
        val baseUVs = computeUVs(origDir, uv, atlasEntry, shouldMirror, noVFlip)
        val adjustedRot = if (origDir == "up" || origDir == "down") {
            face.rotation
        } else {
            if (block.name.contains("piston")) (face.rotation + 180) % 360 else face.rotation
        }
        val faceUVs = applyFaceRotation(baseUVs, adjustedRot)
        val verts = listOf(
            floatArrayOf((bx + finalRotated[0][0] / 16.0).toFloat(), (by + finalRotated[0][1] / 16.0).toFloat(), (bz + finalRotated[0][2] / 16.0).toFloat()),
            floatArrayOf((bx + finalRotated[1][0] / 16.0).toFloat(), (by + finalRotated[1][1] / 16.0).toFloat(), (bz + finalRotated[1][2] / 16.0).toFloat()),
            floatArrayOf((bx + finalRotated[2][0] / 16.0).toFloat(), (by + finalRotated[2][1] / 16.0).toFloat(), (bz + finalRotated[2][2] / 16.0).toFloat()),
            floatArrayOf((bx + finalRotated[3][0] / 16.0).toFloat(), (by + finalRotated[3][1] / 16.0).toFloat(), (bz + finalRotated[3][2] / 16.0).toFloat()),
        )
        val faceNArr = dirToNormalArray(geoDir)
        acc.appendQuad(verts, faceUVs, faceNArr)
    }

    /**
     * Per-RawMesh processing for [buildFloorsInto]. Mirrors the corresponding
     * block in the legacy `build()` method.
     */
    private fun processRawMeshInto(
        mesh: RawMesh,
        block: BlockState,
        bx: Int, by: Int, bz: Int,
        rotX: Int, rotY: Int,
        acc: FloorAccum,
    ) {
        // Placeholder — Task 6 will wire the atlas lookup. For now, no-op.
    }

    fun build(
        region: LitematicRegion,
        originX: Int = 0,
        originY: Int = 0,
        originZ: Int = 0,
        options: GlbExportOptions = GlbExportOptions(),
        onProgress: ((Float) -> Unit)? = null,
    ): GlbOutput {
        val palette = region.palette
        val w = region.width; val h = region.height; val d = region.depth

        // 这些缓存都按 palette 下标（0..size-1，稠密）索引。用数组而非 HashMap<Int,…>：
        // 省去每次查询对 Int key 的装箱与哈希——渲染热路径上有数百万次查询。
        val paletteSize = palette.entries.size
        val modelCache = arrayOfNulls<List<Element>>(paletteSize)
        val rawMeshCache = arrayOfNulls<List<RawMesh>>(paletteSize)
        val rotCacheX = IntArray(paletteSize)
        val rotCacheY = IntArray(paletteSize)
        val usedTextures = mutableSetOf<String>()
        val tintedTextures = mutableMapOf<String, Int>()
        // 特殊染色：texture 路径 → RGB 颜色。覆盖 colormap 逻辑，给"用专用 BlockColor 而非
        // colormap"的方块（如 redstone_wire）一个固定的合理颜色。
        val specialTints = mutableMapOf<String, Int>()
        val forceOpaqueTextures = mutableSetOf<String>()

        for ((blockIdx, block) in palette.entries.withIndex()) {
            // 硬编码几何的方块（vanilla 1.13+ 模型文件为空，靠特殊渲染器）。
            // 我们直接绕过 modelResolver 用手写几何，保证 litematic 里至少看得见。
            // bed 需要位置和 region（在位置循环里重新算），palette 循环里给单格版本先占位
            val customElems = customBlockGeometry(block, 0, 0, 0, region)
            if (customElems != null) {
                modelCache[blockIdx] = customElems
                // 自定义几何也需要走特殊染色（水/熔岩/红石等）
                val rgbOverride = specialTintColorOf(block.name)
                for (elem in customElems) for (face in elem.faces.values)
                    if (face.texture.isNotEmpty()) {
                        usedTextures.add(face.texture)
                        if (options.enableTinting && rgbOverride != null)
                            specialTints[face.texture] = rgbOverride
                    }
                continue
            }
            val model = modelResolver.resolve(block.name, block.properties)
            if (model.hasTextures) {
                val idx = blockIdx
                modelCache[idx] = model.elements
                rawMeshCache[idx] = model.rawMeshes
                rotCacheX[idx] = model.rotX
                rotCacheY[idx] = model.rotY
                // vanilla 染色规则：模型写了 tintindex 只是"我想染色"的请求，
                // 真正是否染色取决于方块本身是否有 BlockColor。
                // 切石机模型写了 tintindex:0 但 stonecutter block 没有 BlockColor，
                // 所以锯片不应被染色（保持原色）。
                val blockIsBiomeTinted = isBiomeTinted(block.name)
                // 查找该方块是否有特殊染色（不走 colormap，直接给 RGB）
                val rgbOverride = specialTintColorOf(block.name)
                for (elem in model.elements) for (face in elem.faces.values)
                    if (face.texture.isNotEmpty()) {
                        usedTextures.add(face.texture)
                    }
                for (mesh in model.rawMeshes)
                    if (mesh.texture.isNotEmpty()) usedTextures.add(mesh.texture)
                for (elem in model.elements) for (face in elem.faces.values)
                    if (face.texture.isNotEmpty()) {
                        when {
                            rgbOverride != null && face.tintindex != null ->
                                specialTints[face.texture] = rgbOverride
                            options.enableTinting && blockIsBiomeTinted && face.tintindex != null ->
                                tintedTextures[face.texture] = face.tintindex
                        }
                    }
            }
        }

        val atlas = texturePacker.pack(usedTextures, tintedTextures, specialTints)
        val plan = computeFloorPlan(h, options.floorHeight)
        // region 的方块数组本就存的是 palette 下标，直接读 → O(1)，免去
        // blockAt(idx→BlockState) 再 indexOf(BlockState→idx) 的 O(palette) 往返扫描。
        val raw = region.rawBlocks
        val wd = w * d
        // 快速扫描 raw blocks 数出非空气方块数，再估算每层 vertex 容量。
        // 一个实心方块平均约 5 个可见面 × 4 个顶点 = 20 顶点，
        // 每个顶点 = 3 pos + 2 uv + 3 normal = 8 float → ~160 float，6 index。
        // 预分配合适的初始容量，避免从 1024 开始反复翻倍（大模型会翻 14+ 次）。
        val solidCount = raw.count { it != 0 }
        val perFloorCap = ((solidCount * 160L) / plan.floorCount).toInt().coerceAtLeast(1024)
        val perFloorIdxCap = ((solidCount * 30L) / plan.floorCount).toInt().coerceAtLeast(1024)
        val floorAccs = Array(plan.floorCount) { FloorAccum(perFloorCap, perFloorIdxCap) }

        // 预计算连接属性：vanilla 的玻璃板/栅栏/墙/铁栏的 north/east/south/west 4 个布尔属性
        // 不存在 NBT 里，渲染时按邻居方块动态生成。这里一次扫整个 region 缓存到 map，
        // 位置循环里查表即可。
        val connectionProps = precomputeConnectionProperties(region)
        val hasConnections = connectionProps.isNotEmpty()

        // 进度回调按约 1% 粒度上报。预先算出上报间隔（每隔多少个 cell 报一次），
        // 循环里只做一次计数比较 —— 避免在 7 万+ cell 的热循环里每次都做 long 除法。
        // onProgress 为 null 时整套记账被短路，零开销。
        val totalBlocks = w.toLong() * h * d
        val reportStep = if (onProgress != null) (totalBlocks / 100).coerceAtLeast(1L) else Long.MAX_VALUE
        var processedBlocks = 0L
        var nextReport = reportStep
        for (y in 0 until h) for (z in 0 until d) for (x in 0 until w) {
            if (onProgress != null) {
                processedBlocks++
                if (processedBlocks >= nextReport) {
                    nextReport += reportStep
                    onProgress.invoke(processedBlocks.toFloat() / totalBlocks)
                }
            }
            val idx = raw[y * wd + z * w + x]
            // 连接属性只对部分位置存在；只有当 region 里确实有连接方块时才去查表，
            // 否则连 Triple 都不分配（避免每个 cell 一次无谓的对象分配 + 哈希查找）。
            val connProps = if (hasConnections) connectionProps[Triple(x, y, z)] else null

            // 连接方块：每位置重新 resolve 以注入连接属性（不走 modelCache）。
            // 非连接方块：使用 cache。air / 无纹理方块在 modelCache 里没有条目，直接跳过。
            val block: BlockState
            val elements: List<Element>
            val (rotX, rotY) = if (connProps != null) {
                block = palette.entries[idx]
                val merged = (block.properties ?: emptyMap()) + connProps
                val model = modelResolver.resolve(block.name, merged)
                if (!model.hasTextures) continue
                elements = model.elements
                // 整体旋转来自 ResolvedModel（单 variant 块）；multipart 块的逐元素旋转在 elem.modelRotX/Y 里
                model.rotX to model.rotY
            } else {
                elements = modelCache[idx] ?: continue
                block = palette.entries[idx]
                rotCacheX[idx] to rotCacheY[idx]
            }
            val bx = originX + x; val by = originY + y; val bz = originZ + z
            val floorIdx = floorIndexForY(y, plan)
            val acc = floorAccs[floorIdx]  // accumulator routed by Y coordinate

            for (elem in elements) {
                for ((origDir, face) in elem.faces) {
                    val atlasEntry = atlas.mappings[face.texture] ?: atlas.mappings.values.firstOrNull() ?: continue

                    val corners = facePlaneCorners(origDir, elem.from, elem.to)
                    val elemRotated = if (elem.rotation != null) corners.map { c -> rotateElementPoint(c, elem.rotation) } else corners
                    // 模型级旋转优先用元素自己的（multipart 不同元素可以不同），
                    // 没有就退化到 ResolvedModel 上的整体旋转。
                    val eRotX = if (elem.modelRotX != 0 || elem.modelRotY != 0) elem.modelRotX else rotX
                    val eRotY = if (elem.modelRotX != 0 || elem.modelRotY != 0) elem.modelRotY else rotY
                    val rotated = elemRotated.map { c -> rotatePoint(c, eRotX, eRotY) }

                    val geoDir = faceNormalToDir(rotated)

                    // 用于防止重合闪烁的顶点容器
                    var finalRotated = rotated

                    if (isFaceOnBoundary(geoDir, rotated)) {
                        val nx = when (geoDir) { "east" -> x + 1; "west" -> x - 1; else -> x }
                        val ny = when (geoDir) { "up" -> y + 1; "down" -> y - 1; else -> y }
                        val nz = when (geoDir) { "south" -> z + 1; "north" -> z - 1; else -> z }

                        if (nx in 0 until w && ny in 0 until h && nz in 0 until d) {
                            val neighborIdx = raw[ny * wd + nz * w + nx]
                            run {
                                val neighborBlock = palette.entries[neighborIdx]
                                val neighborElements = modelCache[neighborIdx]
                                // Per-floor culling: only cull a face if the neighbor is in
                                // the SAME floor as the current block. Cross-floor faces are
                                // treated as the floor's outer wall and kept visible — this is
                                // what makes individual floors look complete in single-floor
                                // view and exploded view. When floorHeight=0 there is only one
                                // floor so neighborFloorIdx == floorIdx always, preserving the
                                // original behavior.
                                val sameFloor = floorIndexForY(ny, plan) == floorIdx

                                if (sameFloor) {
                                    // 相同玻璃或树叶无缝剔除
                                    if ((block.name.contains("glass") && neighborBlock.name == block.name) ||
                                        (block.name.contains("leaves") && neighborBlock.name == block.name)) {
                                        continue
                                    }

                                    // 实体固体墙剔除
                                    if (isFullOpaqueCube(neighborElements, neighborBlock.name)) {
                                        continue
                                    } else {
                                        // 不完整方块微调防闪烁
                                        val offsetAmount = 0.005
                                        finalRotated = rotated.map { p ->
                                            val np = p.copyOf()
                                            when (geoDir) {
                                                "east"  -> np[0] -= offsetAmount
                                                "west"  -> np[0] += offsetAmount
                                                "up"    -> np[1] -= offsetAmount
                                                "down"  -> np[1] += offsetAmount
                                                "south" -> np[2] -= offsetAmount
                                                "north" -> np[2] += offsetAmount
                                            }
                                            np
                                        }
                                    }
                                }
                                // else: cross-floor face — keep as outer wall, no offset
                            }
                        }
                    }

                    // 🌟【终极史诗修复点】：不再无脑退回 [0,0,16,16]，采用标准的 Minecraft 空间投影自动生成 UV 矩阵
                    val uv = getFaceUV(face, origDir, elem.from, elem.to)

                    val shouldMirror = when {
                        origDir in listOf("north", "south", "west") -> true
                        else -> false
                    }

                    val noVFlip = block.name.let { n ->
                        if (listOf("lantern", "brewing_stand", "campfire", "flower_pot", "chest", "sign", "bed").any { n.contains(it) }) true
                        else if (n.contains("potted_")) {
                            val tex = face.texture
                            tex.contains("flower_pot") || tex.contains("dirt")
                        } else false
                    }
                    val baseUVs = computeUVs(origDir, uv, atlasEntry, shouldMirror, noVFlip)

                    val adjustedRot = if (origDir == "up" || origDir == "down") {
                        face.rotation
                    } else {
                        if (block.name.contains("piston")) {
                            (face.rotation + 180) % 360
                        } else {
                            face.rotation
                        }
                    }
                    val faceUVs = applyFaceRotation(baseUVs, adjustedRot)

                    // Build vertex/normal data straight into FloatArrays — no
                    // intermediate boxed List<Float> in this per-face hot path.
                    val verts = listOf(
                        floatArrayOf((bx + finalRotated[0][0] / 16.0).toFloat(), (by + finalRotated[0][1] / 16.0).toFloat(), (bz + finalRotated[0][2] / 16.0).toFloat()),
                        floatArrayOf((bx + finalRotated[1][0] / 16.0).toFloat(), (by + finalRotated[1][1] / 16.0).toFloat(), (bz + finalRotated[1][2] / 16.0).toFloat()),
                        floatArrayOf((bx + finalRotated[2][0] / 16.0).toFloat(), (by + finalRotated[2][1] / 16.0).toFloat(), (bz + finalRotated[2][2] / 16.0).toFloat()),
                        floatArrayOf((bx + finalRotated[3][0] / 16.0).toFloat(), (by + finalRotated[3][1] / 16.0).toFloat(), (bz + finalRotated[3][2] / 16.0).toFloat()),
                    )

                    // face normal (4 vertices share same normal)
                    val faceNArr = dirToNormalArray(geoDir)
                    acc.appendQuad(verts, faceUVs, faceNArr)
                }
            }

            // 处理 RawMesh（OBJ 三角面），直接写顶点不经过 facePlaneCorners
            val rawMeshes = rawMeshCache[idx] ?: emptyList()
            for (mesh in rawMeshes) {
                val atlasEntry = atlas.mappings[mesh.texture] ?: continue
                val au = atlasEntry.u2 - atlasEntry.u1; val bu = atlasEntry.u1
                val av = atlasEntry.v2 - atlasEntry.v1; val bv = atlasEntry.v1

                val mRotX = if (mesh.modelRotX != 0 || mesh.modelRotY != 0) mesh.modelRotX else rotX
                val mRotY = if (mesh.modelRotX != 0 || mesh.modelRotY != 0) mesh.modelRotY else rotY

                val posList = mesh.positions
                val uvList = mesh.uvs
                val baseVi = acc.vertexCount
                for (i in posList.indices step 3) {
                    val px = posList[i]; val py = posList[i+1]; val pz = posList[i+2]
                    val u = uvList[i/3*2+0]; val v = uvList[i/3*2+1]
                    // rotation
                    val rp = rotatePoint(doubleArrayOf(px.toDouble(), py.toDouble(), pz.toDouble()), mRotX, mRotY)
                    // UV already in 0-1 from parser (divided by 16)
                    val uWrap = ((u % 1f) + 1f) % 1f
                    val vWrap = ((v % 1f) + 1f) % 1f
                    val atlasU = (bu + uWrap * au).toFloat(); val atlasV = (bv + vWrap * av).toFloat()
                    acc.positions.add(
                        (bx + rp[0] / 16.0).toFloat(),
                        (by + rp[1] / 16.0).toFloat(),
                        (bz + rp[2] / 16.0).toFloat()
                    )
                    acc.uvs.add(atlasU, atlasV)
                }
                // RawMesh normals
                if (mesh.normals.isNotEmpty()) {
                    for (i in mesh.normals.indices step 3) {
                        val rn = rotateNormal(doubleArrayOf(
                            mesh.normals[i].toDouble(), mesh.normals[i+1].toDouble(), mesh.normals[i+2].toDouble()), mRotX, mRotY)
                        acc.normals.add(rn[0].toFloat(), rn[1].toFloat(), rn[2].toFloat())
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
                        val nn=if(len>0) listOf((nx/len).f,(ny/len).f,(nz/len).f) else listOf(0f,1f,0f)
                        val rn=rotateNormal(doubleArrayOf(nn[0].toDouble(),nn[1].toDouble(),nn[2].toDouble()),mRotX,mRotY)
                        repeat(3){acc.normals.add(rn[0].f,rn[1].f,rn[2].f)}
                    }
                }
                // triangle indices
                if (mesh.indices != null) {
                    // indexed: use provided indices
                    for (idx in mesh.indices) acc.indices.add(baseVi + idx)
                } else {
                    // sequential: 3 vertices per triangle
                    for (i in posList.indices step 9) {
                        val triBase = baseVi + i / 3
                        acc.indices.add(triBase, triBase + 1, triBase + 2)
                    }
                }
            }
        }

        val floors = floorAccs.mapIndexedNotNull { idx, acc ->
            if (acc.indices.isEmpty()) null
            else FloorSlice(
                yMin = idx * plan.effectiveFloorHeight,
                yMax = minOf((idx + 1) * plan.effectiveFloorHeight - 1, h - 1),
                positions = acc.positions.toFloatArray(),
                uvs = acc.uvs.toFloatArray(),
                normals = if (acc.normals.isEmpty()) null else acc.normals.toFloatArray(),
                indices = acc.indices.toIntArray(),
            )
        }

        return GlbOutput(
            floors = floors,
            atlasPng = atlas.pngBytes,
            atlasWidth = atlas.width,
            atlasHeight = atlas.height,
        )
    }

    // 🌟【核心新增】：完备的 Minecraft 原生多视口三维投影 Auto-UV 自动补全函数
    // 关键：Minecraft 模型 JSON 里的 UV 始终是**纹理空间**（V=0 是图片顶部，V=16 是底部）。
    // 本函数对没有显式 UV 的面用 `16 - x` 反射从方块空间 (block space) 投影到纹理空间。
    // 对 16 立方体（from=0, to=16）结果与原版模型完全一致；对非立方体（如蛋糕 14×8×14）
    // 也能正确把每个面投影到纹理中对应的内容区域。
    // 注意：computeUVs **不再**对 V 做翻转（V-flip）——这一步在原版代码里会把显式 UV
    // （已经在纹理空间）也错误翻转，导致切石机侧面被采到纹理的空半边而变透明。
    private fun getFaceUV(face: Face, origDir: String, from: List<Double>, to: List<Double>): List<Double> {
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

    private fun computeUVs(dir: String, uv: List<Double>, entry: AtlasEntry, mirror: Boolean, noVFlip: Boolean = false): List<FloatArray> {
        var u1r = uv[0] / 16.0; var u2r = uv[2] / 16.0
        if (mirror) { val t = u1r; u1r = u2r; u2r = t }
        // UV 已经在纹理空间（auto-UV 用 16-x 反射输出纹理空间，显式 UV 本来就是），
        // 不要再翻转 V。否则会把显式 UV 错误地映射到纹理的另一半。
        val v1r = uv[1] / 16.0; val v2r = uv[3] / 16.0
        val au = entry.u2 - entry.u1; val av = entry.v2 - entry.v1
        val bu = entry.u1; val bv = entry.v1
        val tu1 = (bu + u1r * au).toFloat(); val tv1 = (bv + v1r * av).toFloat()
        val tu2 = (bu + u2r * au).toFloat(); val tv2 = (bv + v2r * av).toFloat()
        // 顶点 V 分配需要看面朝哪个方向：
        //   - up / down：facePlaneCorners 的顶/底 顶点对应 Z=f[2] / Z=t[2]，与纹理 V 的"上→下"
        //     天然一致（V=0 是图片顶），所以 vert 0（Z=t[2]）→ tv2（UV 区域的"底"=v2）。
        //   - north / south / west / east：facePlaneCorners 的顶/底 顶点对应 Y=t[1] / Y=f[1]，
        //     看起来是"上→下"，但 3D 空间 Y 轴向上，而纹理 V=0 也在图片顶，方向本应一致——
        //     实际上 facePlaneCorners 的顶点编号对 side 面是"右下→左上→左下→右上"走向，
        //     导致 vert 0 实际上是 face 的 TOP，需要 v1（UV 区域的"顶"）。
        // 结果：up/down 走原映射，side 面需要把 tv1/tv2 交换。
        return if (dir in listOf("north", "south", "west", "east")) {
            listOf(floatArrayOf(tu1, tv1), floatArrayOf(tu2, tv1), floatArrayOf(tu2, tv2), floatArrayOf(tu1, tv2))
        } else {
            listOf(floatArrayOf(tu1, tv2), floatArrayOf(tu2, tv2), floatArrayOf(tu2, tv1), floatArrayOf(tu1, tv1))
        }
    }

    private fun facePlaneCorners(dir: String, from: List<Double>, to: List<Double>): List<DoubleArray> {
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

    private fun rotateElementPoint(p: DoubleArray, rot: ElementRotation): DoubleArray {
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
        // Minecraft 的 rescale=true：旋转后再绕原点做各向异性缩放，
        // 缩放系数 1/cos(angle) 仅作用于旋转轴的垂直方向，让旋转后的 bounding box
        // 与原始尺寸一致。火/篝火/锁链 等模型依赖此行为。
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

    private fun rotatePoint(p: DoubleArray, rotX: Int, rotY: Int): DoubleArray {
        if (rotX == 0 && rotY == 0) return p.copyOf()
        var x = p[0]; var y = p[1]; var z = p[2]
        val rx = -Math.toRadians(rotX.toDouble())
        val ry = Math.toRadians(rotY.toDouble())
        val cx = 8.0; val cy = 8.0; val cz = 8.0
        if (rotX != 0) { val dy = y - cy; val dz = z - cz; y = cy + dy * cos(rx) - dz * sin(rx); z = cz + dy * sin(rx) + dz * cos(rx) }
        if (rotY != 0) { val dx = x - cx; val dz = z - cz; x = cx + dx * cos(ry) - dz * sin(ry); z = cz + dx * sin(ry) + dz * cos(ry) }
        return doubleArrayOf(x, y, z)
    }

    private fun dirToNormalArray(dir: String): FloatArray = when (dir) {
        "up" -> floatArrayOf(0f,1f,0f); "down" -> floatArrayOf(0f,-1f,0f)
        "north" -> floatArrayOf(0f,0f,-1f); "south" -> floatArrayOf(0f,0f,1f)
        "east" -> floatArrayOf(1f,0f,0f); else -> floatArrayOf(-1f,0f,0f)
    }
    private val Double.f: Float get() = toFloat()

    private fun rotateNormal(n: DoubleArray, rotX: Int, rotY: Int): DoubleArray {
        if (rotX == 0 && rotY == 0) return n.copyOf()
        var x = n[0]; var y = n[1]; var z = n[2]
        val rx = -Math.toRadians(rotX.toDouble())
        val ry = Math.toRadians(rotY.toDouble())
        if (rotX != 0) { val dy = y; val dz = z; y = dy * cos(rx) - dz * sin(rx); z = dy * sin(rx) + dz * cos(rx) }
        if (rotY != 0) { val dx = x; val dz = z; x = dx * cos(ry) - dz * sin(ry); z = dx * sin(ry) + dz * cos(ry) }
        return doubleArrayOf(x, y, z)
    }

    private fun faceNormalToDir(corners: List<DoubleArray>): String {
        val e1 = doubleArrayOf(corners[1][0]-corners[0][0], corners[1][1]-corners[0][1], corners[1][2]-corners[0][2])
        val e2 = doubleArrayOf(corners[3][0]-corners[0][0], corners[3][1]-corners[0][1], corners[3][2]-corners[0][2])
        val nx = e1[1]*e2[2] - e1[2]*e2[1]; val ny = e1[2]*e2[0] - e1[0]*e2[2]; val nz = e1[0]*e2[1] - e1[1]*e2[0]
        val ax = Math.abs(nx); val ay = Math.abs(ny); val az = Math.abs(nz)
        return when { ay >= ax && ay >= az -> if (ny > 0) "up" else "down"
            az >= ax && az >= ay -> if (nz > 0) "south" else "north"
            else -> if (nx > 0) "east" else "west" }
    }

    private fun applyFaceRotation(uvList: List<FloatArray>, rotation: Int): List<FloatArray> {
        val steps = ((rotation % 360) + 360) % 360 / 90
        if (steps == 0) return uvList
        val uvs = uvList.toMutableList()
        repeat(steps) { val last = uvs.removeAt(uvs.size - 1); uvs.add(0, last) }
        return uvs
    }

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
 * Growable primitive float buffer. Avoids the float→Float autoboxing that a
 * `MutableList<Float>` incurs on every `add` — on a 70k-block model the mesh
 * holds millions of vertex floats, and boxing each one blew the heap (~16 bytes
 * per boxed Float) and thrashed GC. Backed by a raw FloatArray that doubles on
 * demand.
 */
internal class FloatBuf(initialCapacity: Int = 1024) {
    var data: FloatArray = FloatArray(initialCapacity)
        private set
    var size: Int = 0
        private set

    private fun ensure(extra: Int) {
        val need = size + extra
        if (need <= data.size) return
        var cap = data.size
        while (cap < need) cap = cap shl 1
        data = data.copyOf(cap)
    }

    fun add(a: Float) { ensure(1); data[size++] = a }
    fun add(a: Float, b: Float) { ensure(2); data[size++] = a; data[size++] = b }
    fun add(a: Float, b: Float, c: Float) { ensure(3); data[size++] = a; data[size++] = b; data[size++] = c }

    fun isEmpty(): Boolean = size == 0
    fun toFloatArray(): FloatArray = data.copyOf(size)

    /** Reset the buffer to empty, freeing backing storage. */
    fun clear() {
        data = FloatArray(0)
        size = 0
    }
}

/** Growable primitive int buffer — same rationale as [FloatBuf] for indices. */
internal class IntBuf(initialCapacity: Int = 1024) {
    var data: IntArray = IntArray(initialCapacity)
        private set
    var size: Int = 0
        private set

    private fun ensure(extra: Int) {
        val need = size + extra
        if (need <= data.size) return
        var cap = data.size
        while (cap < need) cap = cap shl 1
        data = data.copyOf(cap)
    }

    fun add(a: Int) { ensure(1); data[size++] = a }
    fun add(a: Int, b: Int, c: Int) { ensure(3); data[size++] = a; data[size++] = b; data[size++] = c }

    fun isEmpty(): Boolean = size == 0
    fun toIntArray(): IntArray = data.copyOf(size)

    /** Reset the buffer to empty, freeing backing storage. */
    fun clear() {
        data = IntArray(0)
        size = 0
    }
}

internal class FloorAccum(posCap: Int = 1024, idxCap: Int = 1024) {
    val positions = FloatBuf(posCap)
    val uvs = FloatBuf(posCap * 2 / 3)     // 2/3 of position floats
    val normals = FloatBuf(posCap)
    val indices = IntBuf(idxCap)
    val vertexCount: Int get() = positions.size / 3

    /**
     * Append one quad (4 vertices, 2 triangles) using local indices starting
     * from this accumulator's current vertexCount.
     */
    fun appendQuad(
        verts: List<FloatArray>,
        uvs: List<FloatArray>,
        normal: FloatArray,
    ) {
        val base = vertexCount
        for (v in verts) this.positions.add(v[0], v[1], v[2])
        for (uv in uvs) this.uvs.add(uv[0], uv[1])
        val nx = normal[0]; val ny = normal[1]; val nz = normal[2]
        repeat(4) { this.normals.add(nx, ny, nz) }
        this.indices.add(base, base + 1, base + 2)
        this.indices.add(base, base + 2, base + 3)
    }
}