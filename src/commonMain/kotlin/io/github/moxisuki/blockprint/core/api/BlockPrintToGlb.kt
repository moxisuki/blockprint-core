package io.github.moxisuki.blockprint.core.api

import io.github.moxisuki.blockprint.core.model.BlockPrintDocument
import io.github.moxisuki.blockprint.core.model.BlockPrintRegion
import io.github.moxisuki.blockprint.core.glb.mesh.FloorSink
import io.github.moxisuki.blockprint.core.glb.mesh.FloorStats
import io.github.moxisuki.blockprint.core.glb.mesh.GlbAtlas
import io.github.moxisuki.blockprint.core.glb.mesh.MeshBuilder
import io.github.moxisuki.blockprint.core.glb.mesh.RawMesh
import io.github.moxisuki.blockprint.core.glb.mesh.computeFloorPlan
import io.github.moxisuki.blockprint.core.glb.model.Element
import io.github.moxisuki.blockprint.core.glb.model.ModelResolver
import io.github.moxisuki.blockprint.core.glb.model.ResolvedModel
import io.github.moxisuki.blockprint.core.glb.platform.FileAccessor
import io.github.moxisuki.blockprint.core.glb.platform.ImageBackend
import io.github.moxisuki.blockprint.core.glb.platform.OffHeapBuf
import io.github.moxisuki.blockprint.core.glb.platform.createImageBackend
import io.github.moxisuki.blockprint.core.glb.texture.TexturePacker
import io.github.moxisuki.blockprint.core.glb.writer.GlbExportOptions
import io.github.moxisuki.blockprint.core.glb.writer.GlbOutput
import io.github.moxisuki.blockprint.core.glb.writer.GlbWriter
import java.io.ByteArrayOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Path
import java.nio.ByteBuffer
import java.nio.ByteOrder

object BlockPrintToGlb {

    /**
     * Stream the GLB to [outputFile]. Existing signature; rewritten internally
     * to use the two-pass pipeline.
     */
    @JvmStatic
    // 1.0: regionIndex kept for back-compat with 0.2 API
    fun convert(
        document: BlockPrintDocument,
        assetsDirs: List<Path>,
        outputFile: File,
        regionIndex: Int = 0,
        options: GlbExportOptions = GlbExportOptions(),
        onProgress: ((Float) -> Unit)? = null,
    ) {
        outputFile.outputStream().use { stream ->
            run(document, assetsDirs, regionIndex, options, null, onProgress, stream)
        }
    }

    /**
     * Stream the GLB straight to [outputStream]. The stream is flushed before
     * this method returns; the caller manages close.
     */
    @JvmStatic
    // 1.0: regionIndex kept for back-compat with 0.2 API
    fun convert(
        document: BlockPrintDocument,
        assetsDirs: List<Path>,
        outputStream: OutputStream,
        regionIndex: Int = 0,
        options: GlbExportOptions = GlbExportOptions(),
        onProgress: ((Float) -> Unit)? = null,
    ) {
        run(document, assetsDirs, regionIndex, options, null, onProgress, outputStream)
    }

    /**
     * Convert to an in-memory byte array. The entire GLB is held as a single
     * ByteArray; for very large outputs (>= ~100 MB) use the [File] overload
     * instead to avoid OOM on memory-constrained devices.
     */
    @JvmStatic
    // 1.0: regionIndex kept for back-compat with 0.2 API
    fun convertToBytes(
        document: BlockPrintDocument,
        assetsDirs: List<Path>,
        regionIndex: Int = 0,
        imageBackend: ImageBackend? = null,
        onProgress: ((Float) -> Unit)? = null,
        options: GlbExportOptions = GlbExportOptions(),
    ): ByteArray {
        val baos = ByteArrayOutputStream(64 * 1024)
        run(document, assetsDirs, regionIndex, options, imageBackend, onProgress, baos)
        return baos.toByteArray()
    }

    /**
     * Shared implementation backing all public methods.
     *
     * 1. Resolve the region.
     * 2. Build palette caches once (shared with both buildFloorsInto passes).
     * 3. Pack atlas from the cached palette state.
     * 4. Pass 1 (counting): run buildFloorsInto with a counting sink to derive
     *    accurate FloorStats. countFloorStats alone under-counts faces that
     *    processFaceInto drops (atlas-lookup misses, model rotations that
     *    change geoDir, etc.), so we drive both passes from buildFloorsInto
     *    which guarantees the stats exactly match the bytes Pass 2 will emit.
     * 5. Write GLB header (magic + JSON + BIN header) using those stats.
     * 6. Pass 2 (streaming): buildFloorsInto again, this time with a sink that
     *    writes each floor via GlbWriter.writeFloor.
     * 7. Append atlas PNG (padded to 4-byte alignment).
     * 8. Flush.
     *
     * The atlas itself is buffered (typically a few KB) but the per-floor mesh
     * data — usually the bulk of the GLB — is streamed directly to the caller's
     * OutputStream without being held in memory.
     */
    private fun run(
        document: BlockPrintDocument,
        assetsDirs: List<Path>,
        regionIndex: Int,
        options: GlbExportOptions,
        imageBackend: ImageBackend?,
        onProgress: ((Float) -> Unit)?,
        outputStream: OutputStream,
    ) {
        val region = document.regions.getOrElse(regionIndex) {
            throw IllegalArgumentException(
                "Region index $regionIndex out of bounds (${document.regions.size} regions)",
            )
        }

        onProgress?.invoke(0.05f)
        val modelResolver = ModelResolver(assetsDirs)
        val texturePacker = TexturePacker(assetsDirs, backend = imageBackend ?: createImageBackend())
        onProgress?.invoke(0.20f)

        val meshBuilder = MeshBuilder(modelResolver, texturePacker, enableTinting = options.enableTinting)
        val glbWriter = GlbWriter()

        try {

        // Build palette caches once (shared with buildFloorsInto calls below).
        val paletteSize = region.palette.entries.size
        val modelCache = arrayOfNulls<List<Element>>(paletteSize)
        val rawMeshTextureCache = arrayOfNulls<List<String>>(paletteSize)
        for ((blockIdx, block) in region.palette.entries.withIndex()) {
            val model = modelResolver.resolve(block.name, block.properties)
            if (model.hasTextures) {
                modelCache[blockIdx] = model.elements
                rawMeshTextureCache[blockIdx] = model.rawMeshes
                    .mapNotNull { mesh -> mesh.texture.takeIf { it.isNotEmpty() } }
            }
        }
        // Shared connection-variant cache. The two buildFloorsInto passes
        // both consult this map; cells with the same (name, props) re-use
        // one ResolvedModel instead of re-walking the multipart graph
        // per-cell. N (palette) + K (unique orientations) resolves instead
        // of N + M (cells) for the connection-heavy path.
        val connVariantCache = mutableMapOf<Pair<String, String>, ResolvedModel>()

        // Pack atlas from cached palette state. Atlas is needed before we can accurately
        // size the BIN chunk header — processFaceInto/processRawMeshInto drop any face
        // whose texture is missing from the atlas, and countFloorStats does not model
        // that drop. We compute accurate stats from a first buildFloorsInto pass below.
        val atlas = texturePacker.pack(
            collectUsedTextures(meshBuilder, region, modelCache, rawMeshTextureCache),
            collectTintedTextures(meshBuilder, region, modelCache, options.enableTinting),
            collectSpecialTints(meshBuilder, region, modelCache),
        )
        onProgress?.invoke(0.30f)

        // Origin offset (center the model on all axes).
        val originX = region.position.x - region.width / 2
        val originY = region.position.y - region.height / 2
        val originZ = region.position.z - region.depth / 2

        val glbAtlas = GlbAtlas(atlas.pngBytes, atlas.width, atlas.height)

        // Pass 1 builds one floor at a time only long enough to collect exact
        // byte counts and bounds. Returning false lets MeshBuilder reset and
        // reuse a single accumulator instead of retaining the whole model.
        val plan = computeFloorPlan(region.height, options.floorHeight)
        val perFloorVertices = IntArray(plan.floorCount)
        val perFloorIndices = IntArray(plan.floorCount)
        val perFloorBounds = FloatArray(plan.floorCount * 6)
        var totalPositions = 0
        var totalNormals = 0
        var totalUvs = 0
        var totalIndices = 0
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var hasGeometry = false

        meshBuilder.buildFloorsInto(
            region = region, originX = originX, originY = originY, originZ = originZ,
            options = options, atlas = atlas,
            sharedModelCache = modelCache,
            sharedConnVariantCache = connVariantCache,
            sink = FloorSink { floorIdx, _, _, positions, uvs, normals, indices ->
                perFloorVertices[floorIdx] = positions.sizeBytes() / 12
                perFloorIndices[floorIdx] = indices.sizeBytes() / 4
                totalPositions += positions.sizeBytes() / 4
                totalNormals += (normals?.sizeBytes() ?: 0) / 4
                totalUvs += uvs.sizeBytes() / 4
                totalIndices += indices.sizeBytes() / 4
                val bounds = readPositionBounds(positions)
                bounds.copyInto(perFloorBounds, floorIdx * 6)
                if (bounds[0] < minX) minX = bounds[0]
                if (bounds[1] < minY) minY = bounds[1]
                if (bounds[2] < minZ) minZ = bounds[2]
                if (bounds[3] > maxX) maxX = bounds[3]
                if (bounds[4] > maxY) maxY = bounds[4]
                if (bounds[5] > maxZ) maxZ = bounds[5]
                hasGeometry = true
                false
            },
        )

        if (!hasGeometry) {
            minX = 0f; minY = 0f; minZ = 0f
            maxX = 0f; maxY = 0f; maxZ = 0f
        }
        val stats = FloorStats(
            floorCount = plan.floorCount,
            perFloorVertices = perFloorVertices,
            perFloorIndices = perFloorIndices,
            totalPositions = totalPositions,
            totalNormals = totalNormals,
            totalUvs = totalUvs,
            totalIndices = totalIndices,
            minX = minX, minY = minY, minZ = minZ,
            maxX = maxX, maxY = maxY, maxZ = maxZ,
            perFloorBounds = perFloorBounds,
        )
        onProgress?.invoke(0.65f)

        val out = if (outputStream is BufferedOutputStream) outputStream
            else BufferedOutputStream(outputStream, 1 shl 16)
        out.write(glbWriter.buildHeader(glbAtlas, stats, options))

        // Pass 2 writes each floor synchronously in the exact per-floor BIN
        // layout declared by GlbWriter, then returns false so its buffers are
        // immediately reset/reused.
        val nonEmptyFloors = perFloorIndices.count { it > 0 }.coerceAtLeast(1)
        var writtenFloors = 0
        meshBuilder.buildFloorsInto(
            region = region, originX = originX, originY = originY, originZ = originZ,
            options = options, atlas = atlas,
            sharedModelCache = modelCache,
            sharedConnVariantCache = connVariantCache,
            sink = FloorSink { _, _, _, positions, uvs, normals, indices ->
                glbWriter.writeOffHeapFloats(out, positions)
                if (normals != null) glbWriter.writeOffHeapFloats(out, normals)
                glbWriter.writeOffHeapFloats(out, uvs)
                glbWriter.writeOffHeapIndices(out, indices, 0)
                writtenFloors++
                onProgress?.invoke(0.65f + 0.30f * writtenFloors / nonEmptyFloors)
                false
            },
        )

        // Append atlas PNG (padded to 4-byte alignment).
        out.write(glbAtlas.pngBytes)
        val atlasPadded = pad4Size(glbAtlas.pngBytes.size)
        repeat(atlasPadded - glbAtlas.pngBytes.size) { out.write(0) }

        out.flush()
        onProgress?.invoke(1.0f)
        } finally {
            // Drop the model/parser caches we accumulated during this
            // conversion.  Without this, each call leaks ~MB of
            // ResolvedModel + element/face structures until the next
            // GC, which on Android ART can be hundreds of ms during
            // a preview-screen handoff.
            modelResolver.close()
            texturePacker.close()
        }
    }

    private fun readPositionBounds(positions: OffHeapBuf): FloatArray {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        // 60 KiB is divisible by 12, so chunks never split an XYZ vertex.
        val staging = ByteArray(60 * 1024)
        var offset = 0
        val total = positions.sizeBytes()
        while (offset < total) {
            val wanted = minOf(staging.size, total - offset)
            val read = positions.readBytes(staging, offset, wanted)
            check(read > 0) { "Unexpected end of position buffer at byte $offset of $total" }
            val bytes = ByteBuffer.wrap(staging, 0, read).order(ByteOrder.LITTLE_ENDIAN)
            while (bytes.remaining() >= 12) {
                val x = bytes.float; val y = bytes.float; val z = bytes.float
                if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
                if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
            }
            offset += read
        }
        return if (total == 0) floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)
        else floatArrayOf(minX, minY, minZ, maxX, maxY, maxZ)
    }

    private fun pad4Size(n: Int): Int = if (n % 4 == 0) n else n + (4 - n % 4)

    // The three collect* helpers below mirror MeshBuilder's old internal collection
    // logic. They're kept here (instead of imported from MeshBuilder) because the
    // legacy build() needed them inline; a future refactor can promote them to a
    // shared internal helper class.
    private fun collectUsedTextures(
        meshBuilder: MeshBuilder,
        region: BlockPrintRegion,
        modelCache: Array<List<Element>?>,
        rawMeshTextureCache: Array<List<String>?>,
    ): Set<String> {
        val used = mutableSetOf<String>()
        for ((_, modelElements) in modelCache.withIndex()) {
            if (modelElements == null) continue
            for (elem in modelElements) for (face in elem.faces.values)
                if (face.texture.isNotEmpty()) used.add(face.texture)
        }
        for (textures in rawMeshTextureCache) {
            if (textures == null) continue
            used.addAll(textures)
        }
        return used
    }

    private fun collectTintedTextures(
        meshBuilder: MeshBuilder,
        region: BlockPrintRegion,
        modelCache: Array<List<Element>?>,
        enableTinting: Boolean,
    ): Map<String, Int> {
        if (!enableTinting) return emptyMap()
        val tinted = mutableMapOf<String, Int>()
        for ((idx, modelElements) in modelCache.withIndex()) {
            val block = region.palette.entries[idx]
            if (!meshBuilder.isBiomeTinted(block.name)) continue
            if (modelElements == null) continue
            for (elem in modelElements) for (face in elem.faces.values)
                if (face.texture.isNotEmpty() && face.tintindex != null)
                    tinted[face.texture] = face.tintindex
        }
        return tinted
    }

    private fun collectSpecialTints(
        meshBuilder: MeshBuilder,
        region: BlockPrintRegion,
        modelCache: Array<List<Element>?>,
    ): Map<String, Int> {
        val specials = mutableMapOf<String, Int>()
        for ((idx, modelElements) in modelCache.withIndex()) {
            val block = region.palette.entries[idx]
            val rgbOverride = meshBuilder.specialTintColorOf(block.name) ?: continue
            if (modelElements == null) continue
            for (elem in modelElements) for (face in elem.faces.values)
                if (face.texture.isNotEmpty() && face.tintindex != null)
                    specials[face.texture] = rgbOverride
        }
        return specials
    }
}
