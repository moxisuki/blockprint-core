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
        val totalPositions = floors.sumOf { it.positions.size }
        val totalUvs = floors.sumOf { it.uvs.size }
        val totalNormals = floors.sumOf { it.normals?.size ?: 0 }
        val totalIndices = floors.sumOf { it.indices.size }
        val perFloorVertices = floors.map { it.positions.size / 3 }.toIntArray()
        val perFloorIndices = floors.map { it.indices.size }.toIntArray()
        val mm = computeMinMax(floors)
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
        )
        val atlas = GlbAtlas(output.atlasPng, output.atlasWidth, output.atlasHeight)
        val out = if (stream is BufferedOutputStream) stream else BufferedOutputStream(stream, 1 shl 16)
        // Header (GLB magic + version + total length + JSON chunk + BIN chunk header).
        out.write(buildHeader(atlas, stats, options))
        // Stream each floor via writeFloor.
        var vertexOffset = 0
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
                vertexOffset = vertexOffset,
            )
            vertexOffset += floor.positions.size / 3
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
        // Decide which non-empty floors exist by perFloorIndices > 0.
        val nonEmptyFloorIdxs = mutableListOf<Int>()
        for (i in 0 until stats.floorCount) {
            if (stats.perFloorIndices[i] > 0) nonEmptyFloorIdxs.add(i)
        }
        val perFloorIdxSizes = nonEmptyFloorIdxs.map { stats.perFloorIndices[it] }
        // Compute BIN layout.
        val posBytes = stats.totalPositions * 4
        val nrmBytes = stats.totalNormals * 4
        val uvBytes = stats.totalUvs * 4
        val idxBytes = stats.totalIndices * 4
        val atlasRaw = atlas.pngBytes.size
        val atlasPadded = pad4Size(atlasRaw)
        val tb = posBytes + nrmBytes + uvBytes + idxBytes + atlasPadded
        // Build JSON.
        val json = buildJsonFromStats(
            stats = stats,
            perFloorIdxSizes = perFloorIdxSizes,
            options = options,
            posBytesSize = posBytes,
            uvBytesSize = uvBytes,
            nrmBytesSize = nrmBytes,
            atlasSize = atlasRaw,
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

    private fun buildJsonFromStats(
        stats: FloorStats,
        perFloorIdxSizes: List<Int>,
        options: GlbExportOptions,
        posBytesSize: Int, uvBytesSize: Int, nrmBytesSize: Int,
        atlasSize: Int, tb: Int,
        atlasWidth: Int, atlasHeight: Int,
    ): String {
        val mx = stats.minX; val my = stats.minY; val mz = stats.minZ
        val Mx = stats.maxX; val My = stats.maxY; val Mz = stats.maxZ
        val hasN = stats.totalNormals > 0
        val attributeMap = if (hasN) "\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2" else "\"POSITION\":0,\"TEXCOORD_0\":1"
        val uvBvIdx = if (hasN) 2 else 1
        val idxBvIdx = if (hasN) 3 else 2
        val atlasBvIdx = if (hasN) 4 else 3
        val indicesAccStart = if (hasN) 3 else 2
        val n = stats.totalPositions / 3
        val u = stats.totalUvs / 2
        val sharedAccessors = if (hasN) {
            """{"bufferView":0,"componentType":5126,"count":$n,"type":"VEC3","min":[$mx,$my,$mz],"max":[$Mx,$My,$Mz]},{"bufferView":1,"componentType":5126,"count":$n,"type":"VEC3"},{"bufferView":2,"componentType":5126,"count":$u,"type":"VEC2"}"""
        } else {
            """{"bufferView":0,"componentType":5126,"count":$n,"type":"VEC3","min":[$mx,$my,$mz],"max":[$Mx,$My,$Mz]},{"bufferView":1,"componentType":5126,"count":$u,"type":"VEC2"}"""
        }
        val perFloorAccessors = perFloorIdxSizes.mapIndexed { i, count ->
            val byteOffsetIntoIndices = perFloorIdxSizes.take(i).sum() * 4
            """{"bufferView":$idxBvIdx,"byteOffset":$byteOffsetIntoIndices,"componentType":5125,"count":$count,"type":"SCALAR"}"""
        }
        val imageAccessor = """{"bufferView":$atlasBvIdx,"componentType":5121,"count":1,"type":"SCALAR"}"""
        val accessors = "[" + listOf(sharedAccessors, perFloorAccessors.joinToString(","), imageAccessor).joinToString(",") + "]"
        val bufferViews = if (hasN)
            """[{"buffer":0,"byteOffset":0,"byteLength":$posBytesSize},{"buffer":0,"byteOffset":$posBytesSize,"byteLength":$nrmBytesSize},{"buffer":0,"byteOffset":${posBytesSize + nrmBytesSize},"byteLength":$uvBytesSize},{"buffer":0,"byteOffset":${posBytesSize + nrmBytesSize + uvBytesSize},"byteLength":${perFloorIdxSizes.sum() * 4}},{"buffer":0,"byteOffset":${posBytesSize + nrmBytesSize + uvBytesSize + perFloorIdxSizes.sum() * 4},"byteLength":$atlasSize}]"""
        else
            """[{"buffer":0,"byteOffset":0,"byteLength":$posBytesSize},{"buffer":0,"byteOffset":$posBytesSize,"byteLength":$uvBytesSize},{"buffer":0,"byteOffset":${posBytesSize + uvBytesSize},"byteLength":${perFloorIdxSizes.sum() * 4}},{"buffer":0,"byteOffset":${posBytesSize + uvBytesSize + perFloorIdxSizes.sum() * 4},"byteLength":$atlasSize}]"""
        val meshNodes = (0 until perFloorIdxSizes.size).joinToString(",") { i ->
            val y = i * options.explodeGap
            val translation = "[0,$y,0]"
            """{"translation":$translation,"mesh":$i}"""
        }
        return """{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"children":[${(1..perFloorIdxSizes.size).joinToString(",")}]},$meshNodes],"meshes":[${(0 until perFloorIdxSizes.size).joinToString(",") { i ->
            val indicesIdx = indicesAccStart + i
            val prim = """{"attributes":{$attributeMap},"indices":$indicesIdx,"material":0}"""
            """{"primitives":[$prim]}"""
        }}],"accessors":$accessors,"bufferViews":$bufferViews,"buffers":[{"byteLength":$tb}],"images":[{"bufferView":$atlasBvIdx,"mimeType":"image/png"}],"textures":[{"source":0,"sampler":0}],"materials":[{"pbrMetallicRoughness":{"baseColorTexture":{"index":0}},"alphaMode":"MASK","alphaCutoff":0.05,"doubleSided":true}],"samplers":[{"magFilter":9728,"minFilter":9728,"wrapS":33071,"wrapT":33071}]}"""
    }

}
