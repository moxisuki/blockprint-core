package io.github.moxisuki.blockprint.core.glb.model

import io.github.moxisuki.blockprint.core.glb.internal.JsonParser
import io.github.moxisuki.blockprint.core.glb.internal.JsonParser.asList
import io.github.moxisuki.blockprint.core.glb.internal.JsonParser.asObject
import io.github.moxisuki.blockprint.core.glb.internal.JsonParser.asString
import io.github.moxisuki.blockprint.core.glb.mesh.RawMesh
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads build-time baked model manifests emitted by tools/create-model-baker.
 *
 * The manifest is deliberately plain data: core does not depend on Minecraft,
 * NeoForge, or Create at runtime.  Gradle starts a real client once, captures
 * BakedQuad geometry, then this store maps blockstate properties back to
 * [ResolvedModel.rawMeshes].
 */
internal class BakedModelManifestStore(private val assetsDirs: List<Path>) {
    private val combinedManifests: Map<String, BakedBlock> by lazy { loadCombinedManifests() }
    private val splitBlockCache = mutableMapOf<String, BakedBlock?>()

    fun resolve(blockName: String, properties: Map<String, String>?): ResolvedModel? {
        val block = splitBlockCache.getOrPut(blockName) {
            loadSplitBlock(blockName)
        } ?: combinedManifests[blockName] ?: return null
        val state = block.findState(properties ?: emptyMap()) ?: return null
        return state.model
    }

    private fun loadSplitBlock(blockName: String): BakedBlock? {
        val namespace = blockName.substringBefore(':', missingDelimiterValue = "")
        val path = blockName.substringAfter(':', missingDelimiterValue = "")
        if (namespace.isEmpty() || path.isEmpty()) return null

        for (dir in assetsDirs) {
            val file = dir.resolve("blockprint")
                .resolve("baked-models")
                .resolve("by-block")
                .resolve(namespace)
                .resolve("$path.json")
            if (!Files.isRegularFile(file)) continue
            val root = runCatching {
                JsonParser.parseObject(Files.readAllBytes(file).decodeToString())
            }.getOrElse { continue }
            if (root["schema"].asString() != "blockprint.baked-models.v1") continue
            return parseBlocks(root)[blockName]
        }

        val resourcePath = "blockprint/baked-models/by-block/$namespace/$path.json"
        val root = loadBundledManifest(resourcePath) ?: return null
        if (root["schema"].asString() != "blockprint.baked-models.v1") return null
        return parseBlocks(root)[blockName]
    }

    private fun loadCombinedManifests(): Map<String, BakedBlock> {
        val result = linkedMapOf<String, BakedBlock>()
        for (manifest in manifestFiles()) {
            val root = runCatching {
                JsonParser.parseObject(Files.readAllBytes(manifest).decodeToString())
            }.getOrElse { continue }
            if (root["schema"].asString() != "blockprint.baked-models.v1") continue
            result.putAll(parseBlocks(root))
        }
        return result
    }

    private fun parseBlocks(root: Map<String, Any?>): Map<String, BakedBlock> {
        val result = linkedMapOf<String, BakedBlock>()
        for (blockRaw in root["blocks"].asList()) {
            val blockObj = blockRaw.asObject()
            val id = blockObj["id"].asString()
            if (id.isEmpty()) continue
            val states = blockObj["states"].asList().map { stateRaw ->
                parseState(stateRaw.asObject())
            }
            result[id] = BakedBlock(id, states)
        }
        return result
    }

    private fun manifestFiles(): List<Path> {
        val files = mutableListOf<Path>()
        for (dir in assetsDirs) {
            val bakedDir = dir.resolve("blockprint").resolve("baked-models")
            if (!Files.isDirectory(bakedDir)) continue
            Files.list(bakedDir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json", ignoreCase = true) }
                    .sorted()
                    .forEach(files::add)
            }
        }
        return files
    }

    private fun loadBundledManifest(resourcePath: String): Map<String, Any?>? {
        val loader = Thread.currentThread().contextClassLoader
            ?: BakedModelManifestStore::class.java.classLoader
        val stream = loader.getResourceAsStream(resourcePath)
            ?: BakedModelManifestStore::class.java.classLoader?.getResourceAsStream(resourcePath)
            ?: ClassLoader.getSystemResourceAsStream(resourcePath)
            ?: return null
        return stream.bufferedReader(Charsets.UTF_8).use { reader ->
            runCatching { JsonParser.parseObject(reader.readText()) }.getOrNull()
        }
    }

    private fun parseState(obj: Map<String, Any?>): BakedState {
        val key = obj["key"].asString()
        val meshes = obj["meshes"].asList().mapNotNull { meshRaw ->
            parseMesh(meshRaw.asObject())
        }
        return BakedState(
            key = key,
            properties = parseStateKey(key),
            model = ResolvedModel(elements = emptyList(), rawMeshes = meshes),
        )
    }

    private fun parseMesh(obj: Map<String, Any?>): RawMesh? {
        val texture = obj["texture"].asString()
        if (texture.isEmpty()) return null
        val positions = floatList(obj["positions"])
        val uvs = floatList(obj["uvs"])
        if (positions.isEmpty() || uvs.isEmpty()) return null
        val normals = floatList(obj["normals"])
        val indices = intList(obj["indices"]).ifEmpty { null }
        return RawMesh(
            positions = positions,
            uvs = uvs,
            normals = normals,
            indices = indices,
            texture = texture,
        )
    }

    private fun floatList(value: Any?): List<Float> =
        value.asList().mapNotNull { (it as? Number)?.toFloat() }

    private fun intList(value: Any?): List<Int> =
        value.asList().mapNotNull { (it as? Number)?.toInt() }

    private fun parseStateKey(key: String): Map<String, String> {
        if (key.isBlank()) return emptyMap()
        return key.split(",")
            .mapNotNull { item ->
                val parts = item.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .toMap()
    }

    private fun queryKey(properties: Map<String, String>): String =
        properties.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }

    private inner class BakedBlock(
        val id: String,
        private val states: List<BakedState>,
    ) {
        private val byKey = states.associateBy { it.key }

        fun findState(query: Map<String, String>): BakedState? {
            byKey[queryKey(query)]?.let { return it }
            if (states.isEmpty()) return null

            // Blueprints sometimes omit default-ish properties such as
            // waterlogged=false.  Choose the baked state that does not
            // contradict supplied properties, preferring states that match
            // more explicit query keys and then states whose omitted values
            // are false.  This keeps the resolver deterministic without a
            // per-mod rule table.
            return states
                .asSequence()
                .filter { state ->
                    state.properties.all { (key, value) ->
                        query[key]?.let { it == value } ?: true
                    }
                }
                .maxWithOrNull(
                    compareBy<BakedState> { state ->
                        state.properties.keys.count { query[it] == state.properties[it] }
                    }.thenBy { state ->
                        state.properties.count { (key, value) -> key !in query && value == "false" }
                    }.thenByDescending { state ->
                        state.properties.count { (key, _) -> key !in query }
                    },
                )
        }
    }

    private data class BakedState(
        val key: String,
        val properties: Map<String, String>,
        val model: ResolvedModel,
    )
}
