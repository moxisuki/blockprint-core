package io.github.moxisuki.blockprint.core.glb.writer

import io.github.moxisuki.blockprint.core.glb.mesh.FloorStats
import io.github.moxisuki.blockprint.core.glb.mesh.GlbAtlas
import io.github.moxisuki.blockprint.core.glb.platform.OffHeapBuf
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GlbWriter {

    fun write(output: GlbOutput, stream: OutputStream, options: GlbExportOptions = GlbExportOptions()) {
        val floors = output.floors
        val hasNormals = floors.any { it.normals != null }
        require(floors.all { it.positions.isEmpty() || (it.normals != null) == hasNormals }) {
            "All non-empty floors must either provide normals or omit them"
        }
        val totalPositions = floors.sumOf { it.positions.size }
        val totalUvs = floors.sumOf { it.uvs.size }
        val totalNormals = floors.sumOf { it.normals?.size ?: 0 }
        val totalIndices = floors.sumOf { it.indices.size }
        val perFloorVertices = floors.map { it.positions.size / 3 }.toIntArray()
        val perFloorIndices = floors.map { it.indices.size }.toIntArray()
        val mm = computeMinMax(floors)
        val perFloorBounds = FloatArray(floors.size * 6)
        for ((i, floor) in floors.withIndex()) {
            computeMinMax(listOf(floor)).copyInto(perFloorBounds, i * 6)
        }
        val stats = FloorStats(
            floorCount = floors.size,
            perFloorVertices = perFloorVertices,
            perFloorIndices = perFloorIndices,
            totalPositions = totalPositions,
            totalNormals = totalNormals,
            totalUvs = totalUvs,
            totalIndices = totalIndices,
            minX = mm[0], minY = mm[1], minZ = mm[2],
            maxX = mm[3], maxY = mm[4], maxZ = mm[5],
            perFloorBounds = perFloorBounds,
        )
        val atlas = GlbAtlas(output.atlasPng, output.atlasWidth, output.atlasHeight)
        val out = if (stream is BufferedOutputStream) stream else BufferedOutputStream(stream, 1 shl 16)
        // Header (GLB magic + version + total length + JSON chunk + BIN chunk header).
        out.write(buildHeader(atlas, stats, options))
        // Stream each floor via writeFloor.
        for ((idx, floor) in floors.withIndex()) {
            writeFloor(
                stream = out,
                floorIdx = idx,
                yMin = floor.yMin,
                yMax = floor.yMax,
                positions = floor.positions,
                uvs = floor.uvs,
                normals = floor.normals,
                indices = floor.indices,
                vertexOffset = 0,
            )
        }
        // Atlas (padded to 4-byte alignment inside the BIN chunk).
        out.write(atlas.pngBytes)
        val atlasPadded = pad4Size(atlas.pngBytes.size)
        repeat(atlasPadded - atlas.pngBytes.size) { out.write(0) }
        out.flush()
    }

    private fun pad4Size(n: Int): Int = if (n % 4 == 0) n else n + (4 - n % 4)

    private fun computeMinMax(floors: List<FloorSlice>): FloatArray {
        var a = Float.MAX_VALUE; var b = Float.MAX_VALUE; var c = Float.MAX_VALUE
        var x = -Float.MAX_VALUE; var y = -Float.MAX_VALUE; var z = -Float.MAX_VALUE
        var any = false
        for (f in floors) {
            val p = f.positions
            var i = 0
            while (i + 2 < p.size) {
                val px = p[i]; val py = p[i + 1]; val pz = p[i + 2]
                if (px < a) a = px; if (py < b) b = py; if (pz < c) c = pz
                if (px > x) x = px; if (py > y) y = py; if (pz > z) z = pz
                any = true
                i += 3
            }
        }
        if (!any) return floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)
        return floatArrayOf(a, b, c, x, y, z)
    }

    // ================================================================
    // Streaming entry points (added for the two-pass GLB pipeline).
    // See docs/superpowers/plans/2026-06-22-glb-streaming.md Task 3.
    // ================================================================

    /**
     * Build the GLB header + JSON chunk + BIN chunk header bytes for a region
     * whose per-floor data will be streamed via [writeFloor] or [writeStreaming].
     *
     * Caller is responsible for calling [writeFloor] (or providing a sink thunk
     * to [writeStreaming]) for each non-empty floor in ascending floorIdx order,
     * then appending the atlas PNG (padded to 4-byte alignment).
     *
     * Memory: builds a String for the JSON (typically a few KB) and returns the
     * bytes; no per-floor data is held.
     */
    internal fun buildHeader(
        atlas: GlbAtlas,
        stats: FloorStats,
        options: GlbExportOptions,
    ): ByteArray {
        // Each non-empty floor owns a contiguous POSITION | NORMAL? | UV |
        // indices segment. This matches writeFloor's byte order and allows the
        // producer to release a floor immediately after writing it.
        val nonEmptyFloorIdxs = mutableListOf<Int>()
        for (i in 0 until stats.floorCount) {
            if (stats.perFloorIndices[i] > 0) nonEmptyFloorIdxs.add(i)
        }
        val hasNormals = stats.totalNormals > 0
        var binaryOffset = 0
        val layouts = nonEmptyFloorIdxs.map { floorIdx ->
            val vertexCount = stats.perFloorVertices[floorIdx]
            val indexCount = stats.perFloorIndices[floorIdx]
            val positionOffset = binaryOffset
            binaryOffset += vertexCount * 12
            val normalOffset = if (hasNormals) binaryOffset else -1
            if (hasNormals) binaryOffset += vertexCount * 12
            val uvOffset = binaryOffset
            binaryOffset += vertexCount * 8
            val indexOffset = binaryOffset
            binaryOffset += indexCount * 4
            FloorBinaryLayout(
                floorIdx, vertexCount, indexCount,
                positionOffset, normalOffset, uvOffset, indexOffset,
            )
        }
        val atlasRaw = atlas.pngBytes.size
        val atlasPadded = pad4Size(atlasRaw)
        val atlasOffset = binaryOffset
        val tb = atlasOffset + atlasPadded
        require(layouts.sumOf { it.vertexCount * 3 } == stats.totalPositions)
        require(layouts.sumOf { it.vertexCount * 2 } == stats.totalUvs)
        require(layouts.sumOf { it.indexCount } == stats.totalIndices)
        require(!hasNormals || layouts.sumOf { it.vertexCount * 3 } == stats.totalNormals)
        // Build JSON.
        val json = buildPerFloorJson(
            stats = stats,
            layouts = layouts,
            options = options,
            atlasSize = atlasRaw,
            atlasOffset = atlasOffset,
            tb = tb,
            atlasWidth = atlas.width,
            atlasHeight = atlas.height,
        )
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val jsonPadded = pad4Size(jsonBytes.size)
        val tl = 12 + 8 + jsonPadded + 8 + tb
        // Assemble header bytes: GLB magic + version + total length; then JSON chunk header + JSON + padding; then BIN chunk header.
        val out = ByteArray(12 + 8 + jsonPadded + 8)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0x46546C67) // 'glTF'
        bb.putInt(2) // version
        bb.putInt(tl) // total length
        bb.putInt(jsonPadded) // JSON chunk length
        bb.putInt(0x4E4F534A) // 'JSON'
        // JSON bytes
        bb.put(jsonBytes)
        // Padding (spaces)
        repeat(jsonPadded - jsonBytes.size) { bb.put(0x20) }
        // BIN chunk header
        bb.putInt(tb)
        bb.putInt(0x004E4942) // 'BIN\0'
        return out
    }

    /**
     * Write one floor's worth of vertex / normal / UV / index bytes to [stream],
     * reading from off-heap [OffHeapBuf] sources through a 64 KB on-heap
     * staging buffer. Index values are translated by [vertexOffset] before
     * writing so they reference the shared POSITION accessor.
     */
    fun writeFloor(
        stream: OutputStream,
        floorIdx: Int,
        yMin: Int,
        yMax: Int,
        positions: OffHeapBuf,
        uvs: OffHeapBuf,
        normals: OffHeapBuf?,
        indices: OffHeapBuf,
        vertexOffset: Int,
    ) {
        @Suppress("UNUSED_PARAMETER") val unused1 = floorIdx
        @Suppress("UNUSED_PARAMETER") val unused2 = yMin
        @Suppress("UNUSED_PARAMETER") val unused3 = yMax

        val out = if (stream is BufferedOutputStream) stream else BufferedOutputStream(stream, 1 shl 16)
        writeOffHeapFloats(out, positions)
        if (normals != null) writeOffHeapFloats(out, normals)
        writeOffHeapFloats(out, uvs)
        writeOffHeapIndices(out, indices, vertexOffset)
        out.flush()
    }

    internal fun writeOffHeapFloats(out: OutputStream, src: OffHeapBuf) {
        val totalBytes = src.sizeBytes()
        if (totalBytes == 0) return
        val staging = ByteArray(1 shl 16) // 64 KB on-heap, reused across chunks
        var srcOffset = 0
        while (srcOffset < totalBytes) {
            val want = minOf(staging.size, totalBytes - srcOffset)
            val read = src.readBytes(staging, srcOffset, want)
            if (read == 0) break
            out.write(staging, 0, read)
            srcOffset += read
        }
    }

    internal fun writeOffHeapIndices(out: OutputStream, src: OffHeapBuf, vertexOffset: Int) {
        val totalBytes = src.sizeBytes()
        val numIndices = totalBytes / 4
        if (numIndices == 0) return
        val staging = ByteArray(1 shl 16) // 64 KB on-heap, reused across chunks
        val sbb = java.nio.ByteBuffer.wrap(staging).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var srcOffset = 0
        while (srcOffset < totalBytes) {
            val want = minOf(staging.size, totalBytes - srcOffset)
            val read = src.readBytes(staging, srcOffset, want)
            if (read == 0) break
            // Read the bytes we just got, add vertexOffset to each int, write back.
            sbb.clear()
            sbb.limit(read) // only process the bytes we read (last chunk may be partial)
            val nInts = read / 4
            for (j in 0 until nInts) {
                sbb.putInt(sbb.getInt(j * 4) + vertexOffset)
            }
            out.write(staging, 0, nInts * 4)
            srcOffset += read
        }
    }

    /**
     * Legacy [writeFloor] overload that accepts on-heap [FloatArray] / [IntArray]
     * sources, used by [write] (which consumes the legacy [FloorSlice] data
     * class). Internally copies the bytes into off-heap buffers and forwards
     * to the off-heap version. Kept for backward compatibility with
     * [FloorSlice] consumers and existing tests; the off-heap variant is
     * preferred for new code paths.
     */
    fun writeFloor(
        stream: OutputStream,
        floorIdx: Int,
        yMin: Int,
        yMax: Int,
        positions: FloatArray,
        uvs: FloatArray,
        normals: FloatArray?,
        indices: IntArray,
        vertexOffset: Int,
    ) {
        @Suppress("UNUSED_PARAMETER") val unused1 = floorIdx
        @Suppress("UNUSED_PARAMETER") val unused2 = yMin
        @Suppress("UNUSED_PARAMETER") val unused3 = yMax

        val posBuf = OffHeapBuf(positions.size * 4).also { buf ->
            for (v in positions) buf.putFloat(v)
        }
        val uvBuf = OffHeapBuf(uvs.size * 4).also { buf ->
            for (v in uvs) buf.putFloat(v)
        }
        val nrmBuf = normals?.let { arr ->
            OffHeapBuf(arr.size * 4).also { buf -> for (v in arr) buf.putFloat(v) }
        }
        val idxBuf = OffHeapBuf(indices.size * 4).also { buf ->
            for (v in indices) buf.putInt(v)
        }
        try {
            writeFloor(
                stream = stream,
                floorIdx = floorIdx,
                yMin = yMin,
                yMax = yMax,
                positions = posBuf,
                uvs = uvBuf,
                normals = nrmBuf,
                indices = idxBuf,
                vertexOffset = vertexOffset,
            )
        } finally {
            posBuf.close()
            uvBuf.close()
            nrmBuf?.close()
            idxBuf.close()
        }
    }

    /**
     * Stream the GLB to [stream] in one pass:
     *   1. Emit the header (magic + JSON chunk header + JSON + padding)
     *   2. Invoke [sink] — the sink should compute each floor's data and call
     *      [writeFloor] (or stream it to the same [stream] directly).
     *   3. Append the atlas PNG (padded to 4-byte alignment).
     */
    internal fun writeStreaming(
        stream: OutputStream,
        atlas: GlbAtlas,
        stats: FloorStats,
        options: GlbExportOptions,
        sink: () -> Unit,
    ) {
        val out = if (stream is BufferedOutputStream) stream else BufferedOutputStream(stream, 1 shl 16)
        // buildHeader now includes the BIN chunk header.
        out.write(buildHeader(atlas, stats, options))
        // Caller's sink is responsible for invoking writeFloor for each non-empty floor.
        sink()
        // Atlas.
        out.write(atlas.pngBytes)
        val atlasPadded = pad4Size(atlas.pngBytes.size)
        repeat(atlasPadded - atlas.pngBytes.size) { out.write(0) }
        out.flush()
    }

    private fun buildPerFloorJson(
        stats: FloorStats,
        layouts: List<FloorBinaryLayout>,
        options: GlbExportOptions,
        atlasSize: Int, atlasOffset: Int, tb: Int,
        atlasWidth: Int, atlasHeight: Int,
    ): String {
        val hasNormals = stats.totalNormals > 0
        val slotsPerFloor = if (hasNormals) 4 else 3
        val accessors = mutableListOf<String>()
        val bufferViews = mutableListOf<String>()
        val meshes = mutableListOf<String>()
        for ((meshIndex, layout) in layouts.withIndex()) {
            val base = meshIndex * slotsPerFloor
            val boundsOffset = layout.floorIndex * 6
            val bounds = stats.perFloorBounds
            val minX = bounds?.get(boundsOffset) ?: stats.minX
            val minY = bounds?.get(boundsOffset + 1) ?: stats.minY
            val minZ = bounds?.get(boundsOffset + 2) ?: stats.minZ
            val maxX = bounds?.get(boundsOffset + 3) ?: stats.maxX
            val maxY = bounds?.get(boundsOffset + 4) ?: stats.maxY
            val maxZ = bounds?.get(boundsOffset + 5) ?: stats.maxZ

            bufferViews += """{"buffer":0,"byteOffset":${layout.positionOffset},"byteLength":${layout.vertexCount * 12}}"""
            accessors += """{"bufferView":$base,"componentType":5126,"count":${layout.vertexCount},"type":"VEC3","min":[$minX,$minY,$minZ],"max":[$maxX,$maxY,$maxZ]}"""
            var uvSlot = base + 1
            if (hasNormals) {
                bufferViews += """{"buffer":0,"byteOffset":${layout.normalOffset},"byteLength":${layout.vertexCount * 12}}"""
                accessors += """{"bufferView":${base + 1},"componentType":5126,"count":${layout.vertexCount},"type":"VEC3"}"""
                uvSlot++
            }
            bufferViews += """{"buffer":0,"byteOffset":${layout.uvOffset},"byteLength":${layout.vertexCount * 8}}"""
            accessors += """{"bufferView":$uvSlot,"componentType":5126,"count":${layout.vertexCount},"type":"VEC2"}"""
            val indexSlot = base + slotsPerFloor - 1
            bufferViews += """{"buffer":0,"byteOffset":${layout.indexOffset},"byteLength":${layout.indexCount * 4}}"""
            accessors += """{"bufferView":$indexSlot,"componentType":5125,"count":${layout.indexCount},"type":"SCALAR"}"""
            val attributes = if (hasNormals) {
                """"POSITION":$base,"NORMAL":${base + 1},"TEXCOORD_0":$uvSlot"""
            } else {
                """"POSITION":$base,"TEXCOORD_0":$uvSlot"""
            }
            meshes += """{"primitives":[{"attributes":{$attributes},"indices":$indexSlot,"material":0}]}"""
        }
        val atlasView = bufferViews.size
        bufferViews += """{"buffer":0,"byteOffset":$atlasOffset,"byteLength":$atlasSize}"""
        val floorNodes = layouts.mapIndexed { meshIndex, layout ->
            val y = layout.floorIndex * options.explodeGap
            """{"translation":[0,$y,0],"mesh":$meshIndex}"""
        }
        val children = layouts.indices.joinToString(",") { (it + 1).toString() }
        val nodes = (listOf("""{"children":[$children]}""") + floorNodes).joinToString(",")
        return """{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":[$nodes],"meshes":[${meshes.joinToString(",")}],"accessors":[${accessors.joinToString(",")}],"bufferViews":[${bufferViews.joinToString(",")}],"buffers":[{"byteLength":$tb}],"images":[{"bufferView":$atlasView,"mimeType":"image/png"}],"textures":[{"source":0,"sampler":0}],"materials":[{"pbrMetallicRoughness":{"baseColorTexture":{"index":0}},"alphaMode":"MASK","alphaCutoff":0.05,"doubleSided":true}],"samplers":[{"magFilter":9728,"minFilter":9728,"wrapS":33071,"wrapT":33071}]}"""
    }

    private data class FloorBinaryLayout(
        val floorIndex: Int,
        val vertexCount: Int,
        val indexCount: Int,
        val positionOffset: Int,
        val normalOffset: Int,
        val uvOffset: Int,
        val indexOffset: Int,
    )

}
