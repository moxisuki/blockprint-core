package io.github.moxisuki.blockprint.core.glb

object CreateModObjAdapter {
    fun resolve(
        resolver: ModelResolver,
        name: String,
        properties: Map<String, String>?
    ): ResolvedModel? {
        // Belt (传送带)
        if (name == "belt") {
            val baseModel = resolver.resolveWithoutAdapter("create", name, properties)
            val part = properties?.get("part") ?: "middle"
            val slope = properties?.get("slope") ?: "horizontal"
            val hasCasing = properties?.get("casing") == "true"

            val elements = mutableListOf<Element>()
            val rawMeshes = mutableListOf<RawMesh>()

            // casing=true 时，baseModel 里已经是 Create 原版的套壳几何；
            // uncased 则 blockstate 只给 particle model，需要我们手工补真正的皮带环。
            if (hasCasing) {
                elements.addAll(baseModel.elements)
                rawMeshes.addAll(baseModel.rawMeshes)
            }

            val beltLoop = createBeltLoop(resolver, slope, part)
            if (beltLoop != null) {
                elements.addAll(beltLoop.elements)
                rawMeshes.addAll(beltLoop.rawMeshes)
            }

            // start / end / pulley 都需要木轮 + 横向轴。
            if (part == "start" || part == "end" || part == "pulley") {
                val pulleyModel = try {
                    resolver.resolveModel("create:block/belt_pulley")
                } catch (e: Exception) {
                    null
                }
                if (pulleyModel != null) {
                    // belt_pulley 默认轴是 Y 轴。在默认皮带 (facing=south) 下，轴应该是 X 轴。
                    // 物理预旋转后再交给 MeshBuilder 应用 blockstate rotX/rotY。
                    val rotatedPulley = rotateModel(pulleyModel, 90, 90)
                    elements.addAll(rotatedPulley.elements)
                    rawMeshes.addAll(rotatedPulley.rawMeshes)
                }
            }

            return ResolvedModel(elements, rawMeshes).copy(rotX = baseModel.rotX, rotY = baseModel.rotY)
        }

        // Funnel curtains (漏斗帘子)
        if (name == "andesite_funnel" || name == "brass_funnel") {
            val facing = properties?.get("facing") ?: "up"
            if (facing in listOf("north", "south", "east", "west")) {
                val baseModel = resolver.resolveWithoutAdapter("create", name, properties)
                val flapModel = createFlaps(resolver, "create:block/funnel/flap")
                if (flapModel != null) {
                    return mergeModels(baseModel, flapModel, baseModel.rotX, baseModel.rotY)
                }
                return baseModel
            }
        }
        if (name == "andesite_belt_funnel" || name == "brass_belt_funnel") {
            val baseModel = resolver.resolveWithoutAdapter("create", name, properties)
            val flapModel = createFlaps(resolver, "create:block/belt_funnel/flap")
            if (flapModel != null) {
                return mergeModels(baseModel, flapModel, baseModel.rotX, baseModel.rotY)
            }
            return baseModel
        }

        // 1. Small water wheel (水车)
        if (name == "water_wheel") {
            val baseModel = resolver.resolveWithoutAdapter("create", name, properties)
            val wheelModel = try {
                resolver.resolveModel("create:block/water_wheel/wheel")
            } catch (e: Exception) {
                null
            }
            if (wheelModel != null) {
                return mergeModels(baseModel, wheelModel, baseModel.rotX, baseModel.rotY)
            }
            return baseModel
        }

        // 2. Gearbox (齿轮箱需要两个相互垂直的轴，根据 axis 判定)
        if (name == "gearbox") {
            val baseModel = resolver.resolveWithoutAdapter("create", name, properties)
            val shaftModel = try {
                resolver.resolveModel("create:block/shaft")
            } catch (e: Exception) {
                null
            }
            if (shaftModel != null) {
                val activeAxis = getActiveAxis(name, properties)
                val shafts = when (activeAxis) {
                    "x" -> listOf(
                        shaftModel to (0 to 0),      // 垂直 Y 轴
                        shaftModel to (90 to 180)    // 水平 Z 轴
                    )
                    "z" -> listOf(
                        shaftModel to (0 to 0),      // 垂直 Y 轴
                        shaftModel to (90 to 90)     // 水平 X 轴
                    )
                    else -> listOf(
                        shaftModel to (90 to 90),    // 水平 X 轴
                        shaftModel to (90 to 180)    // 水平 Z 轴
                    )
                }
                return mergeModels(baseModel, shafts)
            }
            return baseModel
        }

        // 3. Transmission blocks that need single standard shaft (套管传动杆、离合器、齿轮变速器等)
        val needsShaft = when (name) {
            "andesite_encased_shaft",
            "brass_encased_shaft",
            "metal_girder_encased_shaft",
            "clutch",
            "gearshift",
            "sequenced_gearshift",
            "encased_chain_drive" -> true
            else -> false
        }
        if (needsShaft) {
            val baseModel = resolver.resolveWithoutAdapter("create", name, properties)
            val shaftModel = try {
                resolver.resolveModel("create:block/shaft")
            } catch (e: Exception) {
                null
            }
            if (shaftModel != null) {
                val activeAxis = getActiveAxis(name, properties)
                val shaftRotX = when (activeAxis) {
                    "x" -> 90
                    "z" -> 90
                    else -> 0
                }
                val shaftRotY = when (activeAxis) {
                    "x" -> 90
                    "z" -> 180
                    else -> 0
                }
                return mergeModels(baseModel, shaftModel, shaftRotX, shaftRotY)
            }
            return baseModel
        }

        // 4. Encased Cogwheels (安山岩/黄铜套管齿轮)
        val isEncasedCogwheel = name == "andesite_encased_cogwheel" || name == "brass_encased_cogwheel"
        if (isEncasedCogwheel) {
            val baseModel = resolver.resolveWithoutAdapter("create", name, properties)
            val cogModel = try {
                resolver.resolveModel("create:block/cogwheel_shaftless")
            } catch (e: Exception) {
                null
            }
            if (cogModel != null) {
                val topShaft = properties?.get("top_shaft")?.equals("true", ignoreCase = true) ?: true
                val bottomShaft = properties?.get("bottom_shaft")?.equals("true", ignoreCase = true) ?: true
                val shaftModel = createShaft(resolver, topShaft, bottomShaft)
                val mergedCog = if (shaftModel != null) {
                    mergeModels(cogModel, shaftModel, 0, 0)
                } else {
                    cogModel
                }
                return mergeModels(baseModel, mergedCog, baseModel.rotX, baseModel.rotY)
            }
            return baseModel
        }

        // 5. Encased Large Cogwheels (安山岩/黄铜套管大齿轮)
        val isEncasedLargeCogwheel = name == "andesite_encased_large_cogwheel" || name == "brass_encased_large_cogwheel"
        if (isEncasedLargeCogwheel) {
            val baseModel = resolver.resolveWithoutAdapter("create", name, properties)
            val cogModel = try {
                resolver.resolveModel("create:block/large_cogwheel_shaftless")
            } catch (e: Exception) {
                null
            }
            if (cogModel != null) {
                val topShaft = properties?.get("top_shaft")?.equals("true", ignoreCase = true) ?: true
                val bottomShaft = properties?.get("bottom_shaft")?.equals("true", ignoreCase = true) ?: true
                val shaftModel = createShaft(resolver, topShaft, bottomShaft)
                val mergedCog = if (shaftModel != null) {
                    mergeModels(cogModel, shaftModel, 0, 0)
                } else {
                    cogModel
                }
                return mergeModels(baseModel, mergedCog, baseModel.rotX, baseModel.rotY)
            }
            return baseModel
        }

        return null
    }

    private fun createBeltLoop(
        resolver: ModelResolver,
        slope: String,
        part: String
    ): ResolvedModel? {
        if (slope == "upward" || slope == "downward") {
            val diagonalPath = when (part) {
                "start" -> "create:block/belt/diagonal_start"
                "end" -> "create:block/belt/diagonal_end"
                else -> "create:block/belt/diagonal_middle"
            }
            return try {
                resolver.resolveModel(diagonalPath)
            } catch (e: Exception) {
                null
            }
        }

        val topPath = when (part) {
            "start" -> "create:block/belt/start"
            "end" -> "create:block/belt/end"
            else -> "create:block/belt/middle"
        }
        val bottomPath = when (part) {
            "start" -> "create:block/belt/start_bottom"
            "end" -> "create:block/belt/end_bottom"
            else -> "create:block/belt/middle_bottom"
        }

        val top = try {
            resolver.resolveModel(topPath)
        } catch (e: Exception) {
            return null
        }
        val bottom = try {
            resolver.resolveModel(bottomPath)
        } catch (e: Exception) {
            return top
        }

        return ResolvedModel(
            elements = top.elements + bottom.elements,
            rawMeshes = top.rawMeshes + bottom.rawMeshes,
        )
    }

    private fun createShaft(
        resolver: ModelResolver,
        topShaft: Boolean,
        bottomShaft: Boolean
    ): ResolvedModel? {
        if (!topShaft && !bottomShaft) return null
        val baseShaft = try {
            resolver.resolveModel("create:block/cogwheel_shaft")
        } catch (e: Exception) {
            return null
        }
        val elements = mutableListOf<Element>()
        for (elem in baseShaft.elements) {
            val fromY = if (topShaft && !bottomShaft) 8.0 else elem.from[1]
            val toY = if (!topShaft && bottomShaft) 8.0 else elem.to[1]
            val newFrom = listOf(elem.from[0], fromY, elem.from[2])
            val newTo = listOf(elem.to[0], toY, elem.to[2])

            val newFaces = elem.faces.mapValues { (dir, face) ->
                if (dir in listOf("north", "east", "south", "west")) {
                    val uv = face.uv ?: listOf(6.0, 0.0, 10.0, 16.0)
                    val u1 = uv[0]
                    val v1 = uv[1]
                    val u2 = uv[2]
                    val v2 = uv[3]
                    val newV1 = if (topShaft && !bottomShaft) {
                        v1
                    } else if (!topShaft && bottomShaft) {
                        v1 + (v2 - v1) / 2.0
                    } else {
                        v1
                    }
                    val newV2 = if (topShaft && !bottomShaft) {
                        v1 + (v2 - v1) / 2.0
                    } else if (!topShaft && bottomShaft) {
                        v2
                    } else {
                        v2
                    }
                    face.copy(uv = listOf(u1, newV1, u2, newV2))
                } else {
                    face
                }
            }

            val newRot = elem.rotation?.let { rot ->
                val originY = if (topShaft && !bottomShaft && rot.origin[1] < 8.0) 8.0
                              else if (!topShaft && bottomShaft && rot.origin[1] > 8.0) 8.0
                              else rot.origin[1]
                rot.copy(origin = listOf(rot.origin[0], originY, rot.origin[2]))
            }

            elements.add(elem.copy(from = newFrom, to = newTo, faces = newFaces, rotation = newRot))
        }
        return ResolvedModel(elements, baseShaft.rawMeshes)
    }


    private fun getActiveAxis(name: String, properties: Map<String, String>?): String {
        val props = properties ?: emptyMap()
        if (name == "sequenced_gearshift" && props["vertical"] == "true") return "y"
        
        val axis = props["axis"]
        if (axis != null) return axis
        
        if (name == "metal_girder_encased_shaft") {
            return "z"
        }
        return "y"
    }

    private fun mergeModels(
        base: ResolvedModel,
        extras: List<Pair<ResolvedModel, Pair<Int, Int>>>
    ): ResolvedModel {
        val baseRotX = base.rotX
        val baseRotY = base.rotY

        val mergedElements = base.elements.map { elem ->
            val rx = if (elem.modelRotX != 0 || elem.modelRotY != 0) elem.modelRotX else baseRotX
            val ry = if (elem.modelRotX != 0 || elem.modelRotY != 0) elem.modelRotY else baseRotY
            elem.copy(modelRotX = rx, modelRotY = ry)
        }.toMutableList()

        val mergedRawMeshes = base.rawMeshes.map { mesh ->
            val rx = if (mesh.modelRotX != 0 || mesh.modelRotY != 0) mesh.modelRotX else baseRotX
            val ry = if (mesh.modelRotX != 0 || mesh.modelRotY != 0) mesh.modelRotY else baseRotY
            mesh.copy(modelRotX = rx, modelRotY = ry)
        }.toMutableList()

        for ((extra, rot) in extras) {
            val (extraRotX, extraRotY) = rot
            mergedElements.addAll(extra.elements.map { elem ->
                elem.copy(modelRotX = extraRotX, modelRotY = extraRotY)
            })
            mergedRawMeshes.addAll(extra.rawMeshes.map { mesh ->
                mesh.copy(modelRotX = extraRotX, modelRotY = extraRotY)
            })
        }

        return base.copy(
            elements = mergedElements,
            rawMeshes = mergedRawMeshes,
            rotX = 0,
            rotY = 0
        )
    }

    private fun mergeModels(
        base: ResolvedModel,
        extra: ResolvedModel,
        extraRotX: Int,
        extraRotY: Int
    ): ResolvedModel {
        return mergeModels(base, listOf(extra to (extraRotX to extraRotY)))
    }

    private fun createFlaps(resolver: ModelResolver, path: String): ResolvedModel? {
        val baseFlap = try {
            resolver.resolveModel(path)
        } catch (e: Exception) {
            return null
        }
        val elements = mutableListOf<Element>()
        val rawMeshes = mutableListOf<RawMesh>()
        for (i in 0..3) {
            val dx = -i * 3.0
            for (elem in baseFlap.elements) {
                val newFrom = elem.from.toMutableList()
                newFrom[0] = newFrom[0] + dx
                val newTo = elem.to.toMutableList()
                newTo[0] = newTo[0] + dx
                val newRot = elem.rotation?.let { rot ->
                    val newOrigin = rot.origin.toMutableList()
                    newOrigin[0] = newOrigin[0] + dx
                    rot.copy(origin = newOrigin)
                }
                elements.add(elem.copy(from = newFrom, to = newTo, rotation = newRot))
            }
            for (mesh in baseFlap.rawMeshes) {
                val positions = mesh.positions.mapIndexed { index, value ->
                    if (index % 3 == 0) (value + dx).toFloat() else value
                }
                rawMeshes.add(mesh.copy(positions = positions))
            }
        }
        return ResolvedModel(elements, rawMeshes)
    }

    private fun rotatePoint(p: List<Double>, rotX: Int, rotY: Int): List<Double> {
        if (rotX == 0 && rotY == 0) return p
        var x = p[0]; var y = p[1]; var z = p[2]
        val rx = -kotlin.math.PI * rotX / 180.0
        val ry = kotlin.math.PI * rotY / 180.0
        val cx = 8.0; val cy = 8.0; val cz = 8.0
        if (rotX != 0) {
            val dy = y - cy; val dz = z - cz
            y = cy + dy * kotlin.math.cos(rx) - dz * kotlin.math.sin(rx)
            z = cz + dy * kotlin.math.sin(rx) + dz * kotlin.math.cos(rx)
        }
        if (rotY != 0) {
            val dx = x - cx; val dz = z - cz
            x = cx + dx * kotlin.math.cos(ry) - dz * kotlin.math.sin(ry)
            z = cz + dx * kotlin.math.sin(ry) + dz * kotlin.math.cos(ry)
        }
        return listOf(x, y, z)
    }

    private fun rotateNormal(n: List<Double>, rotX: Int, rotY: Int): List<Double> {
        if (rotX == 0 && rotY == 0) return n
        var x = n[0]; var y = n[1]; var z = n[2]
        val rx = -kotlin.math.PI * rotX / 180.0
        val ry = kotlin.math.PI * rotY / 180.0
        if (rotX != 0) {
            val dy = y; val dz = z
            y = dy * kotlin.math.cos(rx) - dz * kotlin.math.sin(rx)
            z = dy * kotlin.math.sin(rx) + dz * kotlin.math.cos(rx)
        }
        if (rotY != 0) {
            val dx = x; val dz = z
            x = dx * kotlin.math.cos(ry) - dz * kotlin.math.sin(ry)
            z = dx * kotlin.math.sin(ry) + dz * kotlin.math.cos(ry)
        }
        return listOf(x, y, z)
    }

    private fun rotateModel(model: ResolvedModel, rx: Int, ry: Int): ResolvedModel {
        if (rx == 0 && ry == 0) return model
        val elements = model.elements.map { elem ->
            val corners = listOf(
                listOf(elem.from[0], elem.from[1], elem.from[2]),
                listOf(elem.to[0], elem.from[1], elem.from[2]),
                listOf(elem.from[0], elem.to[1], elem.from[2]),
                listOf(elem.to[0], elem.to[1], elem.from[2]),
                listOf(elem.from[0], elem.from[1], elem.to[2]),
                listOf(elem.to[0], elem.from[1], elem.to[2]),
                listOf(elem.from[0], elem.to[1], elem.to[2]),
                listOf(elem.to[0], elem.to[1], elem.to[2])
            ).map { rotatePoint(it, rx, ry) }
            
            val xs = corners.map { it[0] }
            val ys = corners.map { it[1] }
            val zs = corners.map { it[2] }
            
            val newFrom = listOf(xs.minOrNull() ?: 0.0, ys.minOrNull() ?: 0.0, zs.minOrNull() ?: 0.0)
            val newTo = listOf(xs.maxOrNull() ?: 16.0, ys.maxOrNull() ?: 16.0, zs.maxOrNull() ?: 16.0)
            
            val newFaces = mutableMapOf<String, Face>()
            for ((dir, face) in elem.faces) {
                val normal = when (dir) {
                    "up" -> listOf(0.0, 1.0, 0.0)
                    "down" -> listOf(0.0, -1.0, 0.0)
                    "north" -> listOf(0.0, 0.0, -1.0)
                    "south" -> listOf(0.0, 0.0, 1.0)
                    "east" -> listOf(1.0, 0.0, 0.0)
                    else -> listOf(-1.0, 0.0, 0.0)
                }
                val rotatedNormal = rotateNormal(normal, rx, ry)
                val newDir = when {
                    rotatedNormal[1] > 0.5 -> "up"
                    rotatedNormal[1] < -0.5 -> "down"
                    rotatedNormal[2] < -0.5 -> "north"
                    rotatedNormal[2] > 0.5 -> "south"
                    rotatedNormal[0] > 0.5 -> "east"
                    else -> "west"
                }
                newFaces[newDir] = face
            }
            
            val newRot = elem.rotation?.let { rot ->
                val newOrigin = rotatePoint(rot.origin, rx, ry)
                rot.copy(origin = newOrigin)
            }
            
            elem.copy(from = newFrom, to = newTo, faces = newFaces, rotation = newRot)
        }
        
        val rawMeshes = model.rawMeshes.map { mesh ->
            val positions = mesh.positions.chunked(3).flatMap { pos ->
                rotatePoint(pos.map { it.toDouble() }, rx, ry).map { it.toFloat() }
            }
            val normals = mesh.normals.chunked(3).flatMap { norm ->
                rotateNormal(norm.map { it.toDouble() }, rx, ry).map { it.toFloat() }
            }
            mesh.copy(positions = positions, normals = normals)
        }
        
        return ResolvedModel(elements, rawMeshes)
    }
}
