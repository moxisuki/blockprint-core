package io.github.moxisuki.blockprint.core.glb

import io.github.moxisuki.blockprint.core.glb.internal.JsonParser
import io.github.moxisuki.blockprint.core.glb.internal.JsonParser.asDouble
import io.github.moxisuki.blockprint.core.glb.internal.JsonParser.asList
import io.github.moxisuki.blockprint.core.glb.internal.JsonParser.asObject
import io.github.moxisuki.blockprint.core.glb.internal.JsonParser.asString
import io.github.moxisuki.blockprint.core.glb.internal.ObjParser
import io.github.moxisuki.blockprint.core.glb.synthetic.SyntheticBanner
import io.github.moxisuki.blockprint.core.glb.synthetic.SyntheticBed
import io.github.moxisuki.blockprint.core.glb.synthetic.SyntheticChest
import io.github.moxisuki.blockprint.core.glb.synthetic.SyntheticConduit
import io.github.moxisuki.blockprint.core.glb.synthetic.SyntheticDecoratedPot
import io.github.moxisuki.blockprint.core.glb.synthetic.SyntheticEnderDragonHead
import io.github.moxisuki.blockprint.core.glb.synthetic.SyntheticFluid
import io.github.moxisuki.blockprint.core.glb.synthetic.SyntheticLectern
import io.github.moxisuki.blockprint.core.glb.synthetic.SyntheticShulkerBox
import io.github.moxisuki.blockprint.core.glb.synthetic.SyntheticSign
import io.github.moxisuki.blockprint.core.glb.synthetic.SyntheticSkull
import java.nio.file.Files
import java.nio.file.Path

data class ResolvedModel(
    val elements: List<Element>,
    val rawMeshes: List<RawMesh> = emptyList(),
    val opaque: Boolean = true,
    val rotX: Int = 0,
    val rotY: Int = 0,
    val uvlock: Boolean = false,
) {
    val hasTextures: Boolean get() =
        elements.any { elem -> elem.faces.values.any { it.texture.isNotEmpty() } } ||
        rawMeshes.isNotEmpty()
}

data class Element(
    val from: List<Double>,
    val to: List<Double>,
    val faces: Map<String, Face>,
    val rotation: ElementRotation? = null,
    // 模型级旋转（来自 BlockModelRef 的 rotX/rotY）。单 variant 块这两个都是 0；
    // multipart 块的不同元素可能需要不同旋转（如栅栏的 4 个连接侧各自 rotY=0/90/180/270），
    // 所以必须按元素记录，不能在 ResolvedModel 上统一存。
    val modelRotX: Int = 0,
    val modelRotY: Int = 0,
)

data class ElementRotation(
    val origin: List<Double>,
    val axis: String,
    val angle: Double,
    val rescale: Boolean,
)

data class Face(
    val texture: String,
    val uv: List<Double>?,
    val cullface: String?,
    val rotation: Int = 0,
    val tintindex: Int? = null,
)

data class BlockModelRef(
    val modelPath: String,
    val rotX: Int = 0,
    val rotY: Int = 0,
    val uvlock: Boolean = false,
)

class ModelResolver(private val assetsDirs: List<Path>) {
    constructor(assetsDir: Path) : this(listOf(assetsDir))

    private fun resolveAssetFile(relPath: String): Path? =
        assetsDirs.map { it.resolve(relPath) }.firstOrNull { Files.isRegularFile(it) }

    private fun getPngDimensions(path: Path): Pair<Int, Int>? {
        return try {
            Files.newInputStream(path).use { stream ->
                val header = ByteArray(24)
                if (stream.read(header) != 24) return null
                if (header[0] != 0x89.toByte() || header[1] != 0x50.toByte() || header[2] != 0x4E.toByte() || header[3] != 0x47.toByte()) {
                    return null
                }
                val w = ((header[16].toInt() and 0xFF) shl 24) or
                        ((header[17].toInt() and 0xFF) shl 16) or
                        ((header[18].toInt() and 0xFF) shl 8) or
                        (header[19].toInt() and 0xFF)
                val h = ((header[20].toInt() and 0xFF) shl 24) or
                        ((header[21].toInt() and 0xFF) shl 16) or
                        ((header[22].toInt() and 0xFF) shl 8) or
                        (header[23].toInt() and 0xFF)
                Pair(w, h)
            }
        } catch (e: Exception) {
            null
        }
    }

    private val modelCache = mutableMapOf<String, ResolvedModel>()

    fun resolve(blockName: String, properties: Map<String, String>? = null): ResolvedModel {
        val ns = blockName.substringBefore(':')
        val name = blockName.substringAfter(':')
        val synthetic = syntheticModel(name, ns, properties)
        if (synthetic != null) {
            // 床（_bed）：head/foot 已经由 "part" 属性决定，模型几何固定（head 在 -Z 端、
            // foot 在 +Z 端）。facing 只决定床头朝向哪个方向，**不能再用 rotY=180 翻转
            // 模型**——否则会把分布在 4 角的床腿翻到床中部，4 根腿挤在一起。
            // 箱子/告示牌仍需要 facing 旋转（它们的模型是对称的，facing 决定贴图朝向）。
            if (name.endsWith("_bed")) return synthetic

            val p = properties ?: emptyMap()
            val facing = p["facing"]
            val rot = p["rotation"]?.toIntOrNull()
            val rotY = when {
                facing != null -> when (facing) { "north" -> 0; "south" -> 180; "east" -> 90; "west" -> 270; else -> 0 }
                rot != null -> (rot * 22.5).toInt()
                else -> 0
            }
            if (rotY != 0) return synthetic.copy(rotY = rotY)
            return synthetic
        }
        val refs = resolveBlockstate(ns, name, properties)
        if (refs.size == 1) {
            val r = refs[0]
            val baseModel = resolveModel(r.modelPath)
            if (r.rotX == 0 && r.rotY == 0) return baseModel
            return baseModel.copy(rotX = r.rotX, rotY = r.rotY, uvlock = r.uvlock)
        }
        // Multipart: 合并所有子模型，并把每个 BlockModelRef 的 rotX/rotY 写到对应元素上。
        // 之前把整批元素共用一个 rotX/rotY 会让栅栏的 4 个连接侧（rotY 各不相同）全部错位。
        val allElements = mutableListOf<Element>()
        for (r in refs) {
            val model = resolveModel(r.modelPath)
            for (elem in model.elements) {
                allElements.add(elem.copy(modelRotX = r.rotX, modelRotY = r.rotY))
            }
        }
        return ResolvedModel(allElements)
    }

    private fun resolveBlockstate(ns: String, name: String, props: Map<String, String>?): List<BlockModelRef> {
        // 注册名→资产名映射（旧版/别名→最新资产名）
        val assetName = when (name) {
            "chain" -> "iron_chain"
            "funnel" -> "andesite_funnel"
            else -> name
        }
        val path = resolveAssetFile("$ns/blockstates/$assetName.json")
        if (path == null) return listOf(BlockModelRef(fallbackModel(ns, name)))

        val json = Files.readAllBytes(path).toString(Charsets.UTF_8)
        val root = JsonParser.parseObject(json)

        if (root.containsKey("variants")) {
            val variants = root["variants"].asObject()
            val entry = if (!props.isNullOrEmpty()) {
                findBestVariant(variants, props)
            } else {
                variants[""] ?: findBestVariant(variants, emptyMap())
            }

            if (entry == null) return listOf(BlockModelRef(fallbackModel(ns, name)))

            val ref = when (entry) {
                is List<*> -> {
                    val first = entry.firstOrNull().asObject()
                    BlockModelRef(first["model"]?.asString() ?: fallbackModel(ns, name),
                        rotX = first["x"]?.asDouble()?.toInt() ?: 0,
                        rotY = first["y"]?.asDouble()?.toInt() ?: 0,
                        uvlock = first["uvlock"]?.let { it as? Boolean } ?: false)
                }
                is Map<*, *> -> {
                    val obj = entry.asObject()
                    BlockModelRef(obj["model"]?.asString() ?: fallbackModel(ns, name),
                        rotX = obj["x"]?.asDouble()?.toInt() ?: 0,
                        rotY = obj["y"]?.asDouble()?.toInt() ?: 0,
                        uvlock = obj["uvlock"]?.let { it as? Boolean } ?: false)
                }
                else -> BlockModelRef(fallbackModel(ns, name))
            }
            return listOf(ref)
        }

        if (root.containsKey("multipart")) return resolveMultipart(root, ns, name, props)
        return listOf(BlockModelRef(fallbackModel(ns, name)))
    }

    private fun findBestVariant(variants: Map<String, Any?>, props: Map<String, String>): Any? {
        val sortedProps = props.entries.sortedBy { it.key }
        val exactKey = sortedProps.joinToString(",") { "${it.key}=${it.value}" }
        variants[exactKey]?.let { return it }

        // 当 props 为空或无精确匹配时，先尝试找"默认值" variant：
        // 排序 keys 保证确定性，每属性取第一个出现的值作为默认值
        if (props.isEmpty()) {
            val defaultProps = mutableMapOf<String, String>()
            for (key in variants.keys.sorted()) {
                if (key.isEmpty()) continue
                for (kv in key.split(",")) {
                    val (k, v) = kv.split("=", limit = 2)
                    if (k !in defaultProps) defaultProps[k] = v
                }
            }
            if (defaultProps.isNotEmpty()) {
                val defaultKey = defaultProps.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
                variants[defaultKey]?.let { return it }
            }
        }

        // fallback：约束宽松匹配
        for ((key, value) in variants) {
            if (key.isEmpty()) continue
            val constraints = key.split(",").map { it.split("=", limit = 2) }.associate { it[0] to it[1] }
            if (constraints.all { (k, v) -> props[k] == null || props[k] == v }) return value
        }

        return variants[""]
    }

    fun resolveModel(modelPath: String): ResolvedModel {
        val parts = modelPath.split(":")
        val relPath = if (parts.size == 2) "${parts[0]}/models/${parts[1]}.json"
                       else "minecraft/models/$modelPath.json"
        val fullPath = resolveAssetFile(relPath) ?: return emptyCube()

        val json = Files.readAllBytes(fullPath).toString(Charsets.UTF_8)
        val root = JsonParser.parseObject(json)

        // NeoForge OBJ 加载器：解析 OBJ 模型
        val loader = root["loader"]?.asString()
        if (loader == "neoforge:obj") {
            return resolveObjModel(root, fullPath)
        }

        val textures = resolveTextures(root, fullPath)
        val elements = parseElements(root, textures)

        if (elements.isNotEmpty()) return ResolvedModel(elements)

        val parent = root["parent"]?.asString()
        return if (!parent.isNullOrEmpty()) {
            resolveWithParent(parent, textures)
        } else {
            emptyCube()
        }
    }

    private fun resolveObjModel(root: Map<String, Any?>, modelJsonPath: Path): ResolvedModel {
        // 获取纹理：从 parent 或自身 textures
        val parentName = root["parent"]?.asString()
        val textures = if (!parentName.isNullOrEmpty()) {
            resolveObjParentTextures(parentName, modelJsonPath)
        } else {
            resolveTextures(root, modelJsonPath)
        }

        // 解析 OBJ 文件路径
        val objModelPath = root["model"]?.asString() ?: return emptyCube()
        val flipV = root["flip_v"]?.let { it as? Boolean } ?: false

        val objRelPath = if (':' in objModelPath) {
            val p = objModelPath.split(":")
            "${p[0]}/${p[1]}"
        } else objModelPath

        val objFullPath = resolveAssetFile(objRelPath) ?: return emptyCube()

        val rawMeshes = ObjParser.parse(objFullPath, textures, flipV)
        return if (rawMeshes.isNotEmpty()) ResolvedModel(emptyList(), rawMeshes) else emptyCube()
    }

    private fun resolveObjParentTextures(parentName: String, modelJsonPath: Path): Map<String, String> {
        val parts = parentName.split(":")
        val relPath = if (parts.size == 2) "${parts[0]}/models/${parts[1]}.json"
                       else "minecraft/models/$parentName.json"
        val parentPath = resolveAssetFile(relPath) ?: return emptyMap()
        val json = Files.readAllBytes(parentPath).toString(Charsets.UTF_8)
        val root = JsonParser.parseObject(json)
        return resolveTextures(root, parentPath)
    }

    private fun resolveWithParent(parentPath: String, childTextures: Map<String, String>): ResolvedModel {
        val parts = parentPath.split(":")
        val relPath = if (parts.size == 2) "${parts[0]}/models/${parts[1]}.json"
                       else "minecraft/models/$parentPath.json"
        val fullPath = resolveAssetFile(relPath) ?: return emptyCube()

        val json = Files.readAllBytes(fullPath).toString(Charsets.UTF_8)
        val root = JsonParser.parseObject(json)
        val parentTextures = root["textures"]?.asObject() ?: emptyMap()

        val mergedTextures = mutableMapOf<String, String>()
        for ((k, v) in parentTextures) {
            val tv = extractTextureRef(v)
            if (tv.isEmpty()) continue
            mergedTextures[k] = if (tv.startsWith("#")) {
                childTextures[tv.removePrefix("#")] ?: tv
            } else resolveTexturePath(tv, fullPath)
        }
        for ((k, v) in childTextures) {
            mergedTextures[k] = v
        }

        val elements = parseElements(root, mergedTextures)
        if (elements.isNotEmpty()) return ResolvedModel(elements)

        val grandParent = root["parent"]?.asString()
        return if (!grandParent.isNullOrEmpty()) {
            resolveWithParent(grandParent, mergedTextures)
        } else {
            emptyCube()
        }
    }

    private fun resolveTextures(root: Map<String, Any?>, modelFile: Path): Map<String, String> {
        val tex = root["textures"]?.asObject() ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        for ((k, v) in tex) {
            val tv = extractTextureRef(v)
            if (tv.isEmpty()) continue
            result[k] = if (tv.startsWith("#")) tv else resolveTexturePath(tv, modelFile)
        }
        return result
    }

    private fun extractTextureRef(value: Any?): String {
        return when (value) {
            is String -> value
            is Map<*, *> -> value["sprite"]?.asString() ?: ""
            else -> ""
        }
    }

    private fun resolveTexturePath(ref: String, modelFile: Path): String {
        val parts = ref.split(":")
        return if (parts.size == 2) {
            "${parts[0]}:textures/${parts[1]}"
        } else {
            "minecraft:textures/$ref"
        }
    }

    private fun parseElements(root: Map<String, Any?>, textures: Map<String, String>): List<Element> {
        val elementsArr = root["elements"]?.asList() ?: return emptyList()
        val result = mutableListOf<Element>()
        for (e in elementsArr) {
            val obj = e.asObject()
            val from = obj["from"]?.asList()?.map { it.asDouble() } ?: listOf(0.0, 0.0, 0.0)
            val to = obj["to"]?.asList()?.map { it.asDouble() } ?: listOf(16.0, 16.0, 16.0)
            val rotObj = obj["rotation"]?.asObject()
            val elemRot = if (rotObj != null) {
                ElementRotation(
                    origin = rotObj["origin"]?.asList()?.map { it.asDouble() } ?: listOf(8.0, 8.0, 8.0),
                    axis = rotObj["axis"]?.asString() ?: "y",
                    angle = rotObj["angle"]?.asDouble() ?: 0.0,
                    rescale = rotObj["rescale"]?.let { it as? Boolean } ?: false,
                )
            } else null
            val facesMap = obj["faces"]?.asObject() ?: continue
            val faces = mutableMapOf<String, Face>()
            for ((dir, faceObj) in facesMap) {
                val fo = faceObj.asObject()
                val texRef = fo["texture"]?.asString() ?: continue
                val cull = fo["cullface"]?.asString()
                val uvArr = fo["uv"]?.asList()?.map { it.asDouble() }
                val rot = fo["rotation"]?.asDouble()?.toInt() ?: 0
                val tint = fo["tintindex"]?.asDouble()?.toInt()
                val resolvedTex = if (texRef.startsWith("#")) {
                    textures[texRef.removePrefix("#")] ?: texRef
                } else {
                    resolveTexturePath(texRef, Path.of(""))
                }
                faces[dir] = Face(texture = resolvedTex, uv = uvArr, cullface = cull, rotation = rot, tintindex = tint)
            }
            if (faces.isNotEmpty()) result.add(Element(from, to, faces, elemRot))
        }
        return result
    }

    private fun fallbackModel(ns: String, name: String): String {
        // chain → iron_chain 映射（1.21 注册名 vs 资产名不一致）
        val modelName = when (name) {
            "chain" -> "iron_chain"
            else -> name
        }
        return "minecraft:block/$modelName"
    }

    private fun resolveMultipart(root: Map<String, Any?>, ns: String, name: String, props: Map<String, String>?): List<BlockModelRef> {
        val parts = root["multipart"]?.asList() ?: return listOf(BlockModelRef(fallbackModel(ns, name)))
        val p = props ?: emptyMap()
        val results = mutableListOf<BlockModelRef>()
        for (entry in parts) {
            val obj = entry.asObject()
            val whenObj = obj["when"]
            if (whenObj != null && !matchesWhen(whenObj, p)) continue
            // apply 在 Minecraft 规范里既可以是单对象 {model, x, y, uvlock}，
            // 也可以是数组（多个模型叠加，火的 floor0/floor1 即此形式）。
            val applyRaw = obj["apply"] ?: continue
            val applyList: List<Map<String, Any?>> = when (applyRaw) {
                is List<*> -> applyRaw.mapNotNull { (it as? Map<*, *>)?.asObject() }
                is Map<*, *> -> listOf(applyRaw.asObject())
                else -> continue
            }
            for (apply in applyList) {
                val model = apply["model"]?.asString() ?: continue
                results.add(BlockModelRef(model,
                    rotX = apply["x"]?.asDouble()?.toInt() ?: 0,
                    rotY = apply["y"]?.asDouble()?.toInt() ?: 0,
                    uvlock = apply["uvlock"]?.let { it as? Boolean } ?: false))
            }
        }
        return results.ifEmpty { listOf(BlockModelRef(fallbackModel(ns, name))) }
    }

    private fun matchesWhen(whenObj: Any?, props: Map<String, String>): Boolean {
        val conditions = whenObj.asObject()
        if (conditions.containsKey("OR")) {
            val orList = conditions["OR"]?.asList() ?: return false
            return orList.any { matchesWhen(it, props) }
        }
        if (conditions.containsKey("AND")) {
            val andList = conditions["AND"]?.asList() ?: return false
            return andList.all { matchesWhen(it, props) }
        }
        for ((key, value) in conditions) {
            val expected = value.asString()
            val actual = props[key]
            if (actual == null) {
                // 缺失属性视为 Minecraft 默认值 "false"（仅当 expected 本身是 "false" 时算命中）
                if (expected != "false") return false
                continue
            }
            if (!propertyMatches(actual, expected)) return false
        }
        return true
    }

    private fun propertyMatches(actual: String, expected: String): Boolean {
        val options = expected.split("|")
        return options.any { it == actual || it == "true" && actual == "true" || it == "false" && actual == "false" }
    }

    private fun emptyCube(): ResolvedModel {
        val faces = mapOf(
            "down" to Face("", listOf(0.0, 0.0, 16.0, 16.0), "down", 0),
            "up" to Face("", listOf(0.0, 0.0, 16.0, 16.0), "up", 0),
            "north" to Face("", listOf(0.0, 0.0, 16.0, 16.0), "north", 0),
            "south" to Face("", listOf(0.0, 0.0, 16.0, 16.0), "south", 0),
            "west" to Face("", listOf(0.0, 0.0, 16.0, 16.0), "west", 0),
            "east" to Face("", listOf(0.0, 0.0, 16.0, 16.0), "east", 0),
        )
        val element = Element(listOf(0.0, 0.0, 0.0), listOf(16.0, 16.0, 16.0), faces, null)
        return ResolvedModel(listOf(element))
    }

    private val syntheticChests = setOf("chest", "trapped_chest", "ender_chest",
        "copper_chest", "exposed_copper_chest", "weathered_copper_chest", "oxidized_copper_chest")

    private fun syntheticModel(name: String, ns: String, properties: Map<String, String>?): ResolvedModel? {
        SyntheticChest.texNameFor(name)?.let { return SyntheticChest.build("minecraft:textures/entity/chest/$it") }
        SyntheticShulkerBox.texNameFor(name)?.let { return SyntheticShulkerBox.build("minecraft:textures/entity/shulker/$it") }
        SyntheticBanner.texNameFor(name)?.let { return SyntheticBanner.build("minecraft:textures/entity/banner/$it", SyntheticBanner.isWall(name)) }
        SyntheticEnderDragonHead.texNameFor(name)?.let {
            return SyntheticEnderDragonHead.build("minecraft:textures/${it}", SyntheticEnderDragonHead.isWall(name))
        }
        SyntheticSkull.texNameFor(name)?.let { texName ->
            val texPath = "minecraft:textures/entity/$texName"
            val relPath = "minecraft/textures/entity/$texName.png"
            val file = resolveAssetFile(relPath)
            var texW = 64.0
            var texH = 32.0
            if (file != null) {
                val dims = getPngDimensions(file)
                if (dims != null) {
                    texW = dims.first.toDouble()
                    texH = dims.second.toDouble()
                }
            }
            return SyntheticSkull.build(texPath, SyntheticSkull.isWall(name), texW, texH)
        }
        SyntheticConduit.texNameFor(name)?.let { return SyntheticConduit.build("minecraft:textures/entity/conduit/$it") }
        SyntheticDecoratedPot.texNameFor(name)?.let { return SyntheticDecoratedPot.build("minecraft:textures/entity/decorated_pot/$it") }
        SyntheticFluid.texNameFor(name)?.let {
            val level = properties?.get("level")?.toIntOrNull() ?: 0
            return SyntheticFluid.build("minecraft:textures/block/$it", level)
        }
        if (name == "lectern") {
            val synthetic = SyntheticLectern.build("minecraft:textures/block/lectern_base")
            val p = properties ?: emptyMap()
            val facing = p["facing"]
            val rotY = when (facing) { "north" -> 0; "south" -> 180; "east" -> 90; "west" -> 270; else -> 0 }
            return if (rotY != 0) synthetic.copy(rotY = rotY) else synthetic
        }
        if (name.endsWith("_bed")) {
            val color = name.removeSuffix("_bed")
            val part = properties?.get("part") ?: "head"
            val synthetic = SyntheticBed.build("minecraft:textures/entity/bed/$color", part)
            val p = properties ?: emptyMap()
            val facing = p["facing"]
            val rotY = when (facing) { "north" -> 0; "south" -> 180; "east" -> 90; "west" -> 270; else -> 0 }
            return if (rotY != 0) synthetic.copy(rotY = rotY) else synthetic
        }
        if (name.endsWith("_hanging_sign")) {
            val wood = name.removeSuffix("_hanging_sign")
            return SyntheticSign.buildHanging("minecraft:entity/signs/hanging/$wood")
        }
        if (name.endsWith("_wall_sign")) {
            val wood = name.removeSuffix("_wall_sign")
            return SyntheticSign.buildWall("minecraft:entity/signs/$wood")
        }
        if (name.endsWith("_sign")) {
            val wood = name.removeSuffix("_sign")
            return SyntheticSign.buildStanding("minecraft:entity/signs/$wood")
        }
        return null
    }
}
