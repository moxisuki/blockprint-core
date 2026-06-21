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
        atlas: PackedAtlas? = null,
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
                        atlas = atlas,
                        acc = acc,
                    )
                }
            }
            val rawMeshes = rawMeshCache[idx] ?: emptyList()
            for (mesh in rawMeshes) {
                processRawMeshInto(mesh, block, bx, by, bz, rotX, rotY, atlas, acc)
            }
        }
        if (currentFloor >= 0) flushFloor(currentFloor)
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
    ) {
        if (face.texture.isEmpty()) return
        val atlasEntry = if (atlas != null) atlas.mappings[face.texture] ?: atlas.mappings.values.firstOrNull() else null
        if (atlasEntry == null) return
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

    fun build(
        region: LitematicRegion,
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
                collectedFloors.add(
                    FloorSlice(
                        yMin = yMin, yMax = yMax,
                        positions = positions,
                        uvs = uvs,
                        normals = normals,
                        indices = indices,
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
        region: LitematicRegion,
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
        region: LitematicRegion,
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
        region: LitematicRegion,
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