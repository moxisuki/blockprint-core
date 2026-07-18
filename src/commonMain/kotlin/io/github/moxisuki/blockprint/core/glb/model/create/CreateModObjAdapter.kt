package io.github.moxisuki.blockprint.core.glb.model.create

import io.github.moxisuki.blockprint.core.glb.mesh.RawMesh
import io.github.moxisuki.blockprint.core.glb.model.Element
import io.github.moxisuki.blockprint.core.glb.model.Face
import io.github.moxisuki.blockprint.core.glb.model.ModelResolver
import io.github.moxisuki.blockprint.core.glb.model.ResolvedModel

object CreateModObjAdapter {
    fun resolve(
        resolver: ModelResolver,
        name: String,
        properties: Map<String, String>?
    ): ResolvedModel? = resolveStaticAssembly(resolver, name, properties)

    private fun resolveStaticAssembly(
        resolver: ModelResolver,
        name: String,
        properties: Map<String, String>?
    ): ResolvedModel? {
        if (name == "fluid_pipe") return createFluidPipeBody(properties)

        val assembly = CreateStaticAssemblyManifest.byBlockName[name] ?: return null
        val baseModel = try {
            resolver.resolveWithoutAdapter("create", name, properties)
        } catch (e: Exception) {
            return null
        }

        val extras = mutableListOf<Pair<ResolvedModel, Pair<Int, Int>>>()
        for (part in assembly.parts) {
            if (!matchesPartProperties(part, properties)) continue
            extras.addAll(resolvePartModels(resolver, name, part, baseModel, properties))
        }
        return if (extras.isEmpty()) baseModel else mergeModels(baseModel, extras)
    }

    private fun resolvePartModels(
        resolver: ModelResolver,
        blockName: String,
        part: CreateStaticAssemblyPart,
        baseModel: ResolvedModel,
        properties: Map<String, String>?,
    ): List<Pair<ResolvedModel, Pair<Int, Int>>> {
        return when (part.selector) {
            CreateStaticPartSelector.FIXED -> {
                val partModel = resolveModelOrNull(resolver, part.model) ?: return emptyList()
                listOf(partModel to rotationForStaticPart(part.transform, baseModel, properties))
            }
            CreateStaticPartSelector.BELT_LOOP -> {
                val beltLoop = createBeltLoop(
                    resolver = resolver,
                    slope = properties?.get("slope") ?: "horizontal",
                    part = properties?.get("part") ?: "middle",
                ) ?: return emptyList()
                listOf(beltLoop to (baseModel.rotX to baseModel.rotY))
            }
            CreateStaticPartSelector.BELT_PULLEY -> {
                val beltPart = properties?.get("part") ?: "middle"
                if (beltPart != "start" && beltPart != "end" && beltPart != "pulley") return emptyList()
                val pulley = resolveModelOrNull(resolver, part.model) ?: return emptyList()
                listOf(rotateModel(pulley, 90, 90) to (baseModel.rotX to baseModel.rotY))
            }
            CreateStaticPartSelector.SHAFT_AXIS -> {
                val shaft = resolveModelOrNull(resolver, part.model) ?: return emptyList()
                listOf(shaft to shaftAxisRotation(blockName, properties))
            }
            CreateStaticPartSelector.GEARBOX_SHAFTS -> {
                val shaft = resolveModelOrNull(resolver, part.model) ?: return emptyList()
                when (getActiveAxis(blockName, properties)) {
                    "x" -> listOf(
                        shaft to (0 to 0),
                        shaft to (90 to 180),
                    )
                    "z" -> listOf(
                        shaft to (0 to 0),
                        shaft to (90 to 90),
                    )
                    else -> listOf(
                        shaft to (90 to 90),
                        shaft to (90 to 180),
                    )
                }
            }
            CreateStaticPartSelector.ENCASED_COGWHEEL,
            CreateStaticPartSelector.ENCASED_LARGE_COGWHEEL -> {
                val cog = resolveModelOrNull(resolver, part.model) ?: return emptyList()
                val topShaft = properties?.get("top_shaft")?.equals("true", ignoreCase = true) ?: true
                val bottomShaft = properties?.get("bottom_shaft")?.equals("true", ignoreCase = true) ?: true
                val shaft = createCogwheelShaft(resolver, topShaft, bottomShaft)
                val mergedCog = if (shaft != null) {
                    mergeModels(cog, listOf(shaft to (0 to 0)))
                } else {
                    cog
                }
                listOf(mergedCog to (baseModel.rotX to baseModel.rotY))
            }
            CreateStaticPartSelector.FUNNEL_FLAPS -> {
                if (!blockName.endsWith("_belt_funnel")) {
                    val facing = properties?.get("facing") ?: "up"
                    if (facing !in listOf("north", "south", "east", "west")) return emptyList()
                }
                val flaps = createFlaps(resolver, part.model) ?: return emptyList()
                listOf(flaps to (baseModel.rotX to baseModel.rotY))
            }
            CreateStaticPartSelector.STEAM_ENGINE -> createSteamEngineAssembly(resolver)?.let {
                listOf(it to steamEngineRotation(baseModel, properties))
            } ?: emptyList()
            CreateStaticPartSelector.MECHANICAL_ARM -> createMechanicalArmAssembly(resolver)?.let {
                listOf(it to (baseModel.rotX to baseModel.rotY))
            } ?: emptyList()
            CreateStaticPartSelector.FLUID_PIPE_ATTACHMENTS -> createFluidPipeAttachments(resolver, properties)
        }
    }

    private fun resolveModelOrNull(resolver: ModelResolver, model: String): ResolvedModel? =
        try {
            resolver.resolveModel(model)
        } catch (e: Exception) {
            null
        }

    private fun rotationForStaticPart(
        transform: CreateStaticPartTransform,
        baseModel: ResolvedModel,
        properties: Map<String, String>?,
    ): Pair<Int, Int> =
        when (transform) {
            CreateStaticPartTransform.WORLD -> 0 to 0
            CreateStaticPartTransform.BASE_BLOCKSTATE -> baseModel.rotX to baseModel.rotY
            CreateStaticPartTransform.FACING_FROM_SOUTH -> rotateSouthToFacing(properties?.get("facing") ?: "south")
            CreateStaticPartTransform.FACING_OPPOSITE_FROM_SOUTH ->
                rotateSouthToFacing(oppositeFacing(properties?.get("facing") ?: "south"))
            CreateStaticPartTransform.DEPLOYER_POLE -> deployerRotation(properties, axisDirectionMatters = true)
            CreateStaticPartTransform.DEPLOYER_HAND -> deployerRotation(properties, axisDirectionMatters = false)
            CreateStaticPartTransform.KINETIC_SHAFT_AXIS -> kineticShaftRotation(baseModel, properties)
            CreateStaticPartTransform.STEAM_ENGINE -> steamEngineRotation(baseModel, properties)
            CreateStaticPartTransform.MECHANICAL_ARM -> baseModel.rotX to baseModel.rotY
        }

    private fun matchesPartProperties(
        part: CreateStaticAssemblyPart,
        properties: Map<String, String>?,
    ): Boolean {
        if (part.properties.isEmpty()) return true
        val blockProperties = properties ?: emptyMap()
        return part.properties.all { (key, expected) -> blockProperties[key] == expected }
    }

    private fun rotateSouthToFacing(facing: String): Pair<Int, Int> =
        when (facing) {
            "up" -> 90 to 0
            "down" -> 270 to 0
            "north" -> 0 to 180
            "south" -> 0 to 0
            "east" -> 0 to 270
            "west" -> 0 to 90
            else -> 0 to 0
        }

    private fun oppositeFacing(facing: String): String =
        when (facing) {
            "up" -> "down"
            "down" -> "up"
            "north" -> "south"
            "south" -> "north"
            "east" -> "west"
            "west" -> "east"
            else -> facing
        }

    private fun deployerRotation(properties: Map<String, String>?, axisDirectionMatters: Boolean): Pair<Int, Int> {
        val facing = properties?.get("facing") ?: "south"
        val base = rotateSouthToFacing(facing)
        if (!axisDirectionMatters) return base

        // Create applies an additional 90° roll to the deployer pole for half
        // of the axis/facing combinations. The current mesh path supports X/Y
        // model rotations but not a third independent Z-roll, so keep the
        // facing-correct static pose and let the base blockstate carry the
        // visible axis casing.
        return base
    }

    private fun kineticShaftRotation(baseModel: ResolvedModel, properties: Map<String, String>?): Pair<Int, Int> {
        val axis = properties?.get("axis")
        return when (axis) {
            "x" -> 90 to 90
            "z" -> 90 to 0
            "y" -> 0 to 0
            else -> 90 to baseModel.rotY
        }
    }

    private fun shaftAxisRotation(blockName: String, properties: Map<String, String>?): Pair<Int, Int> =
        when (getActiveAxis(blockName, properties)) {
            "x" -> 90 to 90
            "z" -> 90 to 0
            else -> 0 to 0
        }

    private fun getActiveAxis(name: String, properties: Map<String, String>?): String {
        val props = properties ?: emptyMap()
        if (name == "sequenced_gearshift" && props["vertical"] == "true") return "y"
        props["axis"]?.let { return it }
        if (name == "metal_girder_encased_shaft") return "z"
        return "y"
    }

    private fun steamEngineRotation(baseModel: ResolvedModel, properties: Map<String, String>?): Pair<Int, Int> {
        // SteamEngineRenderer orients the three moving partials from the same
        // local pose as the block model, then offsets them along the engine's
        // output axis.  We do not have the neighbouring PoweredShaftBlockEntity
        // in the model resolver, so the static preview uses the blockstate
        // orientation as the stable baseline and leaves roll-dependent animation
        // at rest.
        return baseModel.rotX to baseModel.rotY
    }

    private fun createBeltLoop(
        resolver: ModelResolver,
        slope: String,
        part: String,
    ): ResolvedModel? {
        if (slope == "upward" || slope == "downward") {
            val diagonalPath = when (part) {
                "start" -> "create:block/belt/diagonal_start"
                "end" -> "create:block/belt/diagonal_end"
                else -> "create:block/belt/diagonal_middle"
            }
            return resolveModelOrNull(resolver, diagonalPath)
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

        val top = resolveModelOrNull(resolver, topPath) ?: return null
        val bottom = resolveModelOrNull(resolver, bottomPath) ?: return top

        return ResolvedModel(
            elements = top.elements + bottom.elements,
            rawMeshes = top.rawMeshes + bottom.rawMeshes,
        )
    }

    private fun createCogwheelShaft(
        resolver: ModelResolver,
        topShaft: Boolean,
        bottomShaft: Boolean,
    ): ResolvedModel? {
        if (!topShaft && !bottomShaft) return null
        val baseShaft = resolveModelOrNull(resolver, "create:block/cogwheel_shaft") ?: return null
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
                val originY = if (topShaft && !bottomShaft && rot.origin[1] < 8.0) {
                    8.0
                } else if (!topShaft && bottomShaft && rot.origin[1] > 8.0) {
                    8.0
                } else {
                    rot.origin[1]
                }
                rot.copy(origin = listOf(rot.origin[0], originY, rot.origin[2]))
            }

            elements.add(elem.copy(from = newFrom, to = newTo, faces = newFaces, rotation = newRot))
        }
        return ResolvedModel(elements, baseShaft.rawMeshes)
    }

    private fun createFlaps(resolver: ModelResolver, modelPath: String): ResolvedModel? {
        val baseFlap = resolveModelOrNull(resolver, modelPath) ?: return null
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

    private fun createSteamEngineAssembly(resolver: ModelResolver): ResolvedModel? {
        val piston = resolveModelOrNull(resolver, "create:block/steam_engine/piston") ?: return null
        val linkage = resolveModelOrNull(resolver, "create:block/steam_engine/linkage") ?: return null
        val connector = resolveModelOrNull(resolver, "create:block/steam_engine/shaft_connector") ?: return null

        // Use a deterministic angle=0 pose from SteamEngineRenderer:
        // piston offset = (-14/16 + 20/16) block = 6px.
        // linkage also has the renderer's extra 1-block pre-translation.
        // connector sits two blocks out at the powered shaft.
        val parts = listOf(
            translateModel(piston, 0.0, 6.0, 0.0),
            translateModel(linkage, 0.0, 22.0, 0.0),
            translateModel(connector, 0.0, 32.0, 0.0),
        )
        return mergeResolvedModels(parts)
    }

    private fun createMechanicalArmAssembly(resolver: ModelResolver): ResolvedModel? {
        val fullArm = resolveModelOrNull(resolver, "create:block/mechanical_arm/item") ?: return null

        // The item model is Create's own complete static composition of the
        // dynamic ArmRenderer partials, authored one block below the placed
        // block. Move it back into placed-block coordinates, then remove the
        // base plate because the ordinary blockstate already renders it.
        val placed = translateModel(fullArm, 0.0, 16.0, 0.0)
        val elements = placed.elements.filterNot { element ->
            element.from == listOf(0.0, 0.0, 0.0) &&
                element.to == listOf(16.0, 6.0, 16.0)
        }
        return placed.copy(elements = elements)
    }

    private fun createFluidPipeAttachments(
        resolver: ModelResolver,
        properties: Map<String, String>?,
    ): List<Pair<ResolvedModel, Pair<Int, Int>>> {
        val p = properties ?: emptyMap()
        val openDirections = PIPE_DIRECTIONS.filter { p[it] == "true" }
        if (openDirections.isEmpty()) return emptyList()

        val parts = mutableListOf<Pair<ResolvedModel, Pair<Int, Int>>>()
        for (direction in openDirections) {
            val connection = resolveModelOrNull(resolver, "create:block/fluid_pipe/connection/$direction")
            if (connection != null) parts.add(connection to (0 to 0))
        }

        if (shouldDrawStaticPipeCasing(openDirections)) {
            val casing = resolveModelOrNull(resolver, "create:block/fluid_pipe/casing")
            if (casing != null) parts.add(casing to (0 to 0))
        }
        return parts
    }

    private fun createFluidPipeBody(properties: Map<String, String>?): ResolvedModel {
        val p = properties ?: emptyMap()
        val openDirections = PIPE_DIRECTIONS.filter { p[it] == "true" }
        val boxes = mutableListOf<Pair<List<Double>, List<Double>>>()
        boxes.add(listOf(4.0, 4.0, 4.0) to listOf(12.0, 12.0, 12.0))
        for (direction in openDirections) {
            boxes.add(
                when (direction) {
                    "down" -> listOf(4.0, 0.0, 4.0) to listOf(12.0, 4.0, 12.0)
                    "up" -> listOf(4.0, 12.0, 4.0) to listOf(12.0, 16.0, 12.0)
                    "north" -> listOf(4.0, 4.0, 0.0) to listOf(12.0, 12.0, 4.0)
                    "south" -> listOf(4.0, 4.0, 12.0) to listOf(12.0, 12.0, 16.0)
                    "west" -> listOf(0.0, 4.0, 4.0) to listOf(4.0, 12.0, 12.0)
                    else -> listOf(12.0, 4.0, 4.0) to listOf(16.0, 12.0, 12.0)
                },
            )
        }

        val elements = boxes.map { (from, to) ->
            Element(
                from = from,
                to = to,
                faces = pipeBoxFaces(from, to, openDirections),
            )
        }.toMutableList()
        if (shouldDrawStaticPipeCasing(openDirections)) {
            elements.add(
                Element(
                    from = listOf(3.0, 3.0, 3.0),
                    to = listOf(13.0, 13.0, 13.0),
                    faces = cubeFaces("create:textures/block/pipes"),
                ),
            )
        }
        return ResolvedModel(elements = elements)
    }

    private fun pipeBoxFaces(
        from: List<Double>,
        to: List<Double>,
        openDirections: List<String>,
    ): Map<String, Face> {
        val faces = cubeFaces("create:textures/block/pipes").toMutableMap()

        // Avoid coplanar z-fighting between neighbouring connected pipes. The
        // wall faces still run all the way to the block boundary, so the tube
        // stays visually continuous while the hidden end cap is omitted.
        if (from[0] <= 0.0 && "west" in openDirections) faces.remove("west")
        if (to[0] >= 16.0 && "east" in openDirections) faces.remove("east")
        if (from[1] <= 0.0 && "down" in openDirections) faces.remove("down")
        if (to[1] >= 16.0 && "up" in openDirections) faces.remove("up")
        if (from[2] <= 0.0 && "north" in openDirections) faces.remove("north")
        if (to[2] >= 16.0 && "south" in openDirections) faces.remove("south")
        return faces
    }

    private fun cubeFaces(texture: String): Map<String, Face> =
        mapOf(
            "down" to Face(texture, listOf(0.0, 0.0, 8.0, 8.0), null),
            "up" to Face(texture, listOf(0.0, 0.0, 8.0, 8.0), null),
            "north" to Face(texture, listOf(0.0, 0.0, 8.0, 8.0), null),
            "south" to Face(texture, listOf(0.0, 0.0, 8.0, 8.0), null),
            "west" to Face(texture, listOf(0.0, 0.0, 8.0, 8.0), null),
            "east" to Face(texture, listOf(0.0, 0.0, 8.0, 8.0), null),
        )

    private fun shouldDrawStaticPipeCasing(openDirections: List<String>): Boolean =
        listOf("x", "y", "z").any { axis ->
            openDirections.count { pipeDirectionAxis(it) != axis } > 2
        }

    private fun pipeDirectionAxis(direction: String): String =
        when (direction) {
            "east", "west" -> "x"
            "up", "down" -> "y"
            else -> "z"
        }

    private fun oppositePipeDirection(direction: String): String =
        when (direction) {
            "north" -> "south"
            "south" -> "north"
            "east" -> "west"
            "west" -> "east"
            "up" -> "down"
            "down" -> "up"
            else -> direction
        }

    private fun translateModel(model: ResolvedModel, dx: Double, dy: Double, dz: Double): ResolvedModel {
        val delta = listOf(dx, dy, dz)
        val elements = model.elements.map { elem ->
            val newFrom = List(3) { i -> elem.from[i] + delta[i] }
            val newTo = List(3) { i -> elem.to[i] + delta[i] }
            val newRot = elem.rotation?.let { rot ->
                rot.copy(origin = List(3) { i -> rot.origin[i] + delta[i] })
            }
            elem.copy(from = newFrom, to = newTo, rotation = newRot)
        }
        val rawMeshes = model.rawMeshes.map { mesh ->
            val positions = mesh.positions.mapIndexed { index, value ->
                (value + delta[index % 3]).toFloat()
            }
            mesh.copy(positions = positions)
        }
        return model.copy(elements = elements, rawMeshes = rawMeshes)
    }

    private fun mergeResolvedModels(models: List<ResolvedModel>): ResolvedModel =
        ResolvedModel(
            elements = models.flatMap { it.elements },
            rawMeshes = models.flatMap { it.rawMeshes },
            opaque = models.all { it.opaque },
        )

    private fun rotatePoint(p: List<Double>, rotX: Int, rotY: Int): List<Double> {
        if (rotX == 0 && rotY == 0) return p
        var x = p[0]
        var y = p[1]
        var z = p[2]
        val rx = -kotlin.math.PI * rotX / 180.0
        val ry = kotlin.math.PI * rotY / 180.0
        val cx = 8.0
        val cy = 8.0
        val cz = 8.0
        if (rotX != 0) {
            val dy = y - cy
            val dz = z - cz
            y = cy + dy * kotlin.math.cos(rx) - dz * kotlin.math.sin(rx)
            z = cz + dy * kotlin.math.sin(rx) + dz * kotlin.math.cos(rx)
        }
        if (rotY != 0) {
            val dx = x - cx
            val dz = z - cz
            x = cx + dx * kotlin.math.cos(ry) - dz * kotlin.math.sin(ry)
            z = cz + dx * kotlin.math.sin(ry) + dz * kotlin.math.cos(ry)
        }
        return listOf(x, y, z)
    }

    private fun rotateNormal(n: List<Double>, rotX: Int, rotY: Int): List<Double> {
        if (rotX == 0 && rotY == 0) return n
        var x = n[0]
        var y = n[1]
        var z = n[2]
        val rx = -kotlin.math.PI * rotX / 180.0
        val ry = kotlin.math.PI * rotY / 180.0
        if (rotX != 0) {
            val dy = y
            val dz = z
            y = dy * kotlin.math.cos(rx) - dz * kotlin.math.sin(rx)
            z = dy * kotlin.math.sin(rx) + dz * kotlin.math.cos(rx)
        }
        if (rotY != 0) {
            val dx = x
            val dz = z
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
                listOf(elem.to[0], elem.to[1], elem.to[2]),
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

    private fun mergeModels(
        base: ResolvedModel,
        extras: List<Pair<ResolvedModel, Pair<Int, Int>>>
    ): ResolvedModel {
        val baseRotX = base.rotX
        val baseRotY = base.rotY

        val mergedElements = base.elements.filter { it.hasRenderableFace() }.map { elem ->
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
            mergedElements.addAll(
                extra.elements
                    .filter { it.hasRenderableFace() }
                    .map { elem -> elem.copy(modelRotX = extraRotX, modelRotY = extraRotY) },
            )
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

    private fun Element.hasRenderableFace(): Boolean =
        faces.values.any { face -> face.texture.isNotEmpty() }

    private val PIPE_DIRECTIONS = listOf("down", "up", "north", "south", "west", "east")
}
