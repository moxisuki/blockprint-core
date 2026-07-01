package io.github.moxisuki.blockprint.core.glb

import io.github.moxisuki.blockprint.core.Litematic
import io.github.moxisuki.blockprint.core.glb.mesh.FloorSink
import io.github.moxisuki.blockprint.core.glb.mesh.FloorStats
import io.github.moxisuki.blockprint.core.glb.mesh.GlbAtlas
import io.github.moxisuki.blockprint.core.glb.mesh.MeshBuilder
import io.github.moxisuki.blockprint.core.glb.mesh.RawMesh
import io.github.moxisuki.blockprint.core.glb.mesh.computeFloorPlan
import io.github.moxisuki.blockprint.core.glb.model.CreateModObjAdapter
import io.github.moxisuki.blockprint.core.glb.model.Element
import io.github.moxisuki.blockprint.core.glb.model.ModelResolver
import io.github.moxisuki.blockprint.core.glb.platform.FileAccessor
import io.github.moxisuki.blockprint.core.glb.platform.ImageBackend
import io.github.moxisuki.blockprint.core.glb.platform.OffHeapBuf
import io.github.moxisuki.blockprint.core.glb.texture.TexturePacker
import io.github.moxisuki.blockprint.core.glb.writer.GlbExportOptions
import io.github.moxisuki.blockprint.core.glb.writer.GlbOutput
import io.github.moxisuki.blockprint.core.glb.writer.GlbWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Path

object LitematicToGlb {

    /**
     * Stream the GLB to [outputFile]. Existing signature; rewritten internally
     * to use the two-pass pipeline.
     */
    @JvmStatic
    @JvmOverloads
    fun convert(
        litematic: Litematic,
        assetsDirs: List<Path>,
        outputFile: File,
        regionIndex: Int = 0,
        options: GlbExportOptions = GlbExportOptions(),
        onProgress: ((Float) -> Unit)? = null,
    ) {
        outputFile.outputStream().use { stream ->
            run(litematic, assetsDirs, regionIndex, options, onProgress, stream)
        }
    }

    /**
     * Stream the GLB straight to [outputStream]. The stream is flushed before
     * this method returns; the caller manages close.
     */
    @JvmStatic
    @JvmOverloads
    fun convert(
        litematic: Litematic,
        assetsDirs: List<Path>,
        outputStream: OutputStream,
        regionIndex: Int = 0,
        options: GlbExportOptions = GlbExportOptions(),
        onProgress: ((Float) -> Unit)? = null,
    ) {
        run(litematic, assetsDirs, regionIndex, options, onProgress, outputStream)
    }

    /**
     * Convert to an in-memory byte array. The entire GLB is held as a single
     * ByteArray; for very large outputs (>= ~100 MB) use the [File] overload
     * instead to avoid OOM on memory-constrained devices.
     */
    @JvmStatic
    @JvmOverloads
    fun convertToBytes(
        litematic: Litematic,
        assetsDirs: List<Path>,
        regionIndex: Int = 0,
        imageBackend: ImageBackend? = null,
        onProgress: ((Float) -> Unit)? = null,
        options: GlbExportOptions = GlbExportOptions(),
    ): ByteArray {
        val baos = ByteArrayOutputStream(64 * 1024)
        run(litematic, assetsDirs, regionIndex, options, onProgress, baos)
        onProgress?.invoke(1.0f)
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
        litematic: Litematic,
        assetsDirs: List<Path>,
        regionIndex: Int,
        options: GlbExportOptions,
        onProgress: ((Float) -> Unit)?,
        outputStream: OutputStream,
    ) {
        val region = litematic.regions.getOrElse(regionIndex) {
            throw IllegalArgumentException(
                "Region index $regionIndex out of bounds (${litematic.regions.size} regions)",
            )
        }

        onProgress?.invoke(0.05f)
        val modelResolver = ModelResolver(assetsDirs)
        val texturePacker = TexturePacker(assetsDirs)
        onProgress?.invoke(0.20f)

        val meshBuilder = MeshBuilder(modelResolver, texturePacker, enableTinting = options.enableTinting)
        val glbWriter = GlbWriter()

        try {

        // Build palette caches once (shared with buildFloorsInto calls below).
        val paletteSize = region.palette.entries.size
        val modelCache = arrayOfNulls<List<Element>>(paletteSize)
        for ((blockIdx, block) in region.palette.entries.withIndex()) {
            val model = modelResolver.resolve(block.name, block.properties)
            if (model.hasTextures) {
                modelCache[blockIdx] = model.elements
            }
        }

        // Pack atlas from cached palette state. Atlas is needed before we can accurately
        // size the BIN chunk header — processFaceInto/processRawMeshInto drop any face
        // whose texture is missing from the atlas, and countFloorStats does not model
        // that drop. We compute accurate stats from a first buildFloorsInto pass below.
        val atlas = texturePacker.pack(
            collectUsedTextures(meshBuilder, region, modelCache),
            collectTintedTextures(meshBuilder, region, modelCache, options.enableTinting),
            collectSpecialTints(meshBuilder, region, modelCache),
        )
        onProgress?.invoke(0.30f)

        // Origin offset (center the model on all axes).
        val originX = region.position.x - region.width / 2
        val originY = region.position.y - region.height / 2
        val originZ = region.position.z - region.depth / 2

        val glbAtlas = GlbAtlas(atlas.pngBytes, atlas.width, atlas.height)

        // Pass 1 (counting): run buildFloorsInto with a sink that only counts bytes per floor.
        // This mirrors what Pass 2 will write, so the BIN chunk header is accurate.
        val plan = computeFloorPlan(region.height, options.floorHeight)
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
        var pass1FloorsSeen = 0

        meshBuilder.buildFloorsInto(
            region = region,
            originX = originX,
            originY = originY,
            originZ = originZ,
            options = options,
            atlas = atlas,
            sink = FloorSink { floorIdx, _, _, positions, uvs, normals, indices ->
                val posBytes = positions.sizeBytes()
                val uvBytes = uvs.sizeBytes()
                val nrmBytes = normals?.sizeBytes() ?: 0
                val idxBytes = indices.sizeBytes()
                perFloorVertices[floorIdx] = posBytes / 12
                perFloorIndices[floorIdx] = idxBytes / 4
                totalPositions += posBytes / 4
                totalNormals += nrmBytes / 4
                totalUvs += uvBytes / 4
                totalIndices += idxBytes / 4
                pass1FloorsSeen++
                onProgress?.invoke(0.30f + (pass1FloorsSeen.toFloat() / plan.floorCount) * 0.35f)
                // Scan positions for min/max (streamed via OffHeapBuf.readBytes).
                // readBytes writes to target[0..], so we use a 2-stage approach:
                //   1. Read a chunk of up to CHUNK_SIZE bytes into staging.
                //   2. Process complete vertices (3 floats = 12 bytes) from a
                //      combined view of `carry` (leftover from prior chunk) +
                //      `staging`.
                //   3. Save any unconsumed trailing bytes (< 12) into `carry`
                //      for the next iteration.
                if (posBytes > 0) {
                    val positionsBytes = positions.sizeBytes()
                    val staging = ByteArray(1 shl 12) // 4096 bytes per chunk
                    val carry = ByteArray(12)          // at most 11 leftover bytes
                    var carryLen = 0
                    var srcOffset = 0
                    // Loop while there is data to process: either more bytes
                    // in the source, or a complete vertex waiting in carry.
                    while (srcOffset < positionsBytes || carryLen >= 12) {
                        val want = if (srcOffset < positionsBytes)
                            minOf(staging.size, positionsBytes - srcOffset)
                        else 0
                        val read = if (want > 0) positions.readBytes(staging, srcOffset, want) else 0
                        if (read == 0) break
                        srcOffset += read
                        // Build a combined view: carry || staging[0..read).
                        val totalLen = carryLen + read
                        val combined = ByteArray(totalLen)
                        if (carryLen > 0) System.arraycopy(carry, 0, combined, 0, carryLen)
                        System.arraycopy(staging, 0, combined, carryLen, read)
                        val bb = java.nio.ByteBuffer.wrap(combined, 0, totalLen)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        while (bb.remaining() >= 12) {
                            val px = bb.getFloat(); val py = bb.getFloat(); val pz = bb.getFloat()
                            if (px < minX) minX = px
                            if (py < minY) minY = py
                            if (pz < minZ) minZ = pz
                            if (px > maxX) maxX = px
                            if (py > maxY) maxY = py
                            if (pz > maxZ) maxZ = pz
                            anyVertex = true
                        }
                        val leftover = bb.remaining()
                        if (leftover > 0) {
                            System.arraycopy(combined, bb.position(), carry, 0, leftover)
                        }
                        carryLen = leftover
                    }
                }
            },
        )
        val stats = FloorStats(
            floorCount = plan.floorCount,
            perFloorVertices = perFloorVertices,
            perFloorIndices = perFloorIndices,
            totalPositions = totalPositions,
            totalNormals = totalNormals,
            totalUvs = totalUvs,
            totalIndices = totalIndices,
            minX = if (anyVertex) minX else 0f,
            minY = if (anyVertex) minY else 0f,
            minZ = if (anyVertex) minZ else 0f,
            maxX = if (anyVertex) maxX else 0f,
            maxY = if (anyVertex) maxY else 0f,
            maxZ = if (anyVertex) maxZ else 0f,
        )
        onProgress?.invoke(0.65f)

        // Help the GC reclaim Pass 1 accumulators before we clone data in Pass 2.
        // On Android where ART has a 256 MB heap and allocateDirect counts
        // against it, this can be the difference between OOM and success.
        System.gc()

        // Write GLB header (magic + JSON + BIN header).
        outputStream.write(glbWriter.buildHeader(glbAtlas, stats, options))

        // Pass 2: run buildFloorsInto once, clone each floor's buffers
        // (off-heap→off-heap via copyTo), then write in the order the
        // JSON buffer views expect:
        //   ALL positions → ALL normals → ALL uvs → ALL indices → atlas.
        val posBufs = mutableListOf<OffHeapBuf>()
        val nrmBufs = mutableListOf<OffHeapBuf?>()
        val uvBufs = mutableListOf<OffHeapBuf>()
        val idxBufs = mutableListOf<OffHeapBuf>()
        val totalVertices = stats.totalPositions / 3
        val reportStep: Long = if (onProgress != null) (totalVertices / 100).coerceAtLeast(1).toLong() else Long.MAX_VALUE
        var processed = 0L
        var nextReport = reportStep

        meshBuilder.buildFloorsInto(
            region = region, originX = originX, originY = originY, originZ = originZ,
            options = options, atlas = atlas,
            sink = FloorSink { _, _, _, positions, uvs, normals, indices ->
                val pc = OffHeapBuf(positions.sizeBytes()); positions.copyTo(pc); posBufs.add(pc)
                val uc = OffHeapBuf(uvs.sizeBytes()); uvs.copyTo(uc); uvBufs.add(uc)
                val nc = if (normals != null) { val n = OffHeapBuf(normals.sizeBytes()); normals.copyTo(n); n } else null
                nrmBufs.add(nc)
                val ic = OffHeapBuf(indices.sizeBytes()); indices.copyTo(ic); idxBufs.add(ic)
                if (onProgress != null) {
                    processed += positions.sizeBytes() / 12
                    while (processed >= nextReport) {
                        nextReport += reportStep
                        onProgress.invoke(0.65f + (processed.toFloat() / totalVertices).coerceAtMost(1f) * 0.30f)
                    }
                }
            },
        )
        // Reclaim Pass 2 source accumulators before we start streaming
        // the clones (which temporarily doubles the in-flight memory).
        System.gc()
        try {
            for (buf in posBufs) glbWriter.writeOffHeapFloats(outputStream, buf)
            for (buf in nrmBufs) { if (buf != null) glbWriter.writeOffHeapFloats(outputStream, buf) }
            for (buf in uvBufs) glbWriter.writeOffHeapFloats(outputStream, buf)
            var vertexOffset = 0
            for (i in idxBufs.indices) {
                glbWriter.writeOffHeapIndices(outputStream, idxBufs[i], vertexOffset)
                vertexOffset += posBufs[i].sizeBytes() / 12
            }
        } finally {
            for (buf in posBufs) buf.close()
            for (buf in nrmBufs) { buf?.close() }
            for (buf in uvBufs) buf.close()
            for (buf in idxBufs) buf.close()
        }
        onProgress?.invoke(0.95f)

        // Append atlas PNG (padded to 4-byte alignment).
        outputStream.write(glbAtlas.pngBytes)
        val atlasPadded = pad4Size(glbAtlas.pngBytes.size)
        repeat(atlasPadded - glbAtlas.pngBytes.size) { outputStream.write(0) }

        outputStream.flush()

        // Force GC to reclaim the OffHeapBuf / FloorAccum memory we just
        // released.  Otherwise the consumer (Filament engine init, atlas
        // upload, etc.) triggers a GC mid-frame, which causes visible
        // jank right after the GLB finishes generating.
        System.gc()
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

    private fun pad4Size(n: Int): Int = if (n % 4 == 0) n else n + (4 - n % 4)

    // The three collect* helpers below mirror MeshBuilder's old internal collection
    // logic. They're kept here (instead of imported from MeshBuilder) because the
    // legacy build() needed them inline; a future refactor can promote them to a
    // shared internal helper class.
    private fun collectUsedTextures(
        meshBuilder: MeshBuilder,
        region: io.github.moxisuki.blockprint.core.LitematicRegion,
        modelCache: Array<List<Element>?>,
    ): Set<String> {
        val used = mutableSetOf<String>()
        for ((_, modelElements) in modelCache.withIndex()) {
            if (modelElements == null) continue
            for (elem in modelElements) for (face in elem.faces.values)
                if (face.texture.isNotEmpty()) used.add(face.texture)
        }
        return used
    }

    private fun collectTintedTextures(
        meshBuilder: MeshBuilder,
        region: io.github.moxisuki.blockprint.core.LitematicRegion,
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
        region: io.github.moxisuki.blockprint.core.LitematicRegion,
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