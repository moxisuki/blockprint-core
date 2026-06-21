package io.github.moxisuki.blockprint.core.glb

import java.io.BufferedOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GlbWriter {

    fun write(output: GlbOutput, stream: OutputStream, options: GlbExportOptions = GlbExportOptions()) {
        val floors = output.floors
        val totalPositions = floors.sumOf { it.positions.size }
        val totalUvs = floors.sumOf { it.uvs.size }
        val hasN = floors.any { it.normals != null && it.normals!!.isNotEmpty() }
        val totalNormals = floors.sumOf { it.normals?.size ?: 0 }
        val totalIndices = floors.sumOf { it.indices.size }

        // Sizes/offsets are derived from counts alone — no need to materialize the
        // merged float/int arrays. The pos/norm/uv/idx chunks are each a whole
        // number of 4-byte elements, so they are inherently 4-byte aligned; only
        // the trailing atlas PNG needs padding inside the BIN chunk.
        val posBytesSize = totalPositions * 4
        val uvBytesSize = totalUvs * 4
        val nrmBytesSize = if (hasN) totalNormals * 4 else 0
        val idxBytesSize = totalIndices * 4
        val atlasRaw = output.atlasPng.size
        val atlasPadded = pad4Size(atlasRaw)

        // BIN layout: pos | (norm) | uv | idx | atlas(padded).
        val tb = posBytesSize + (if (hasN) nrmBytesSize else 0) + uvBytesSize + idxBytesSize + atlasPadded
        val po = 0
        val no: Int
        val uo: Int
        val io: Int
        if (hasN) { no = po + posBytesSize; uo = no + nrmBytesSize; io = uo + uvBytesSize }
        else { no = -1; uo = po + posBytesSize; io = uo + uvBytesSize }
        val ao = io + idxBytesSize

        val mm = computeMinMax(floors)
        val json = buildJson(
            floors = floors,
            options = options,
            totalPositions = totalPositions,
            totalUvs = totalUvs,
            totalNormals = totalNormals,
            totalIndices = totalIndices,
            perFloorIndices = floors.map { it.indices.size },
            posBytesSize = posBytesSize,
            uvBytesSize = uvBytesSize,
            nrmBytesSize = nrmBytesSize,
            atlasSize = atlasRaw,
            tb = tb,
            po = po, uo = uo, no = no, io = io, ao = ao,
            mm = mm, atlasWidth = output.atlasWidth, atlasHeight = output.atlasHeight,
            hasN = hasN,
        )

        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val jsonPadded = pad4Size(jsonBytes.size)
        val tl = 12 + 8 + jsonPadded + 8 + tb

        // Stream everything straight to the output. We never hold a second full
        // copy of the mesh: each attribute is encoded chunk-by-chunk through a
        // small fixed staging buffer, so writer peak memory is the staging buffer
        // plus the JSON string — not input + 68MB output side by side.
        val out = if (stream is BufferedOutputStream) stream else BufferedOutputStream(stream, 1 shl 16)

        val head = ByteBuffer.allocate(12 + 8).order(ByteOrder.LITTLE_ENDIAN)
        head.putInt(0x46546C67); head.putInt(2); head.putInt(tl)
        head.putInt(jsonPadded); head.putInt(0x4E4F534A)
        out.write(head.array())
        out.write(jsonBytes)
        repeat(jsonPadded - jsonBytes.size) { out.write(0x20) }

        val binHead = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        binHead.putInt(tb); binHead.putInt(0x004E4942)
        out.write(binHead.array())

        val staging = ByteArray(1 shl 16)
        val sbb = ByteBuffer.wrap(staging).order(ByteOrder.LITTLE_ENDIAN)

        for (f in floors) writeFloats(out, staging, sbb, f.positions)
        if (hasN) for (f in floors) f.normals?.let { writeFloats(out, staging, sbb, it) }
        for (f in floors) writeFloats(out, staging, sbb, f.uvs)
        // Index offsetting: each floor's local indices shift by the cumulative
        // vertex count of preceding floors so they reference the shared POSITION
        // accessor.
        var vertexOffset = 0
        for (f in floors) {
            writeIndices(out, staging, sbb, f.indices, vertexOffset)
            vertexOffset += f.positions.size / 3
        }
        out.write(output.atlasPng)
        repeat(atlasPadded - atlasRaw) { out.write(0) }
        out.flush()
    }

    /** Encode a FloatArray as little-endian bytes straight to [out] via a reusable staging buffer. */
    private fun writeFloats(out: OutputStream, staging: ByteArray, sbb: ByteBuffer, arr: FloatArray) {
        val cap = staging.size / 4
        var i = 0
        while (i < arr.size) {
            val chunk = minOf(cap, arr.size - i)
            sbb.clear()
            for (j in 0 until chunk) sbb.putFloat(arr[i + j])
            out.write(staging, 0, chunk * 4)
            i += chunk
        }
    }

    /** Encode an IntArray (each element shifted by [offset]) as little-endian bytes to [out]. */
    private fun writeIndices(out: OutputStream, staging: ByteArray, sbb: ByteBuffer, arr: IntArray, offset: Int) {
        val cap = staging.size / 4
        var i = 0
        while (i < arr.size) {
            val chunk = minOf(cap, arr.size - i)
            sbb.clear()
            for (j in 0 until chunk) sbb.putInt(arr[i + j] + offset)
            out.write(staging, 0, chunk * 4)
            i += chunk
        }
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

    private fun buildJson(
        floors: List<FloorSlice>,
        options: GlbExportOptions,
        totalPositions: Int, totalUvs: Int, totalNormals: Int, totalIndices: Int,
        perFloorIndices: List<Int>,
        posBytesSize: Int, uvBytesSize: Int, nrmBytesSize: Int,
        atlasSize: Int, tb: Int,
        po: Int, uo: Int, no: Int, io: Int, ao: Int,
        mm: FloatArray, atlasWidth: Int, atlasHeight: Int, hasN: Boolean,
    ): String {
        val mx = mm[0]; val my = mm[1]; val mz = mm[2]; val Mx = mm[3]; val My = mm[4]; val Mz = mm[5]
        val attributeMap = if (hasN) "\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2" else "\"POSITION\":0,\"TEXCOORD_0\":1"

        // Buffer-view layout:
        //   hasN=true:  bv0=pos, bv1=norm, bv2=uv, bv3=idx, bv4=atlas
        //   hasN=false: bv0=pos, bv1=uv, bv2=idx, bv3=atlas
        val uvBvIdx = if (hasN) 2 else 1
        val idxBvIdx = if (hasN) 3 else 2
        val atlasBvIdx = if (hasN) 4 else 3

        // Accessor layout:
        //   hasN=true:  acc0=pos, acc1=norm, acc2=uv, [acc3..acc(2+N)]=idx per floor, acc(3+N)=image
        //   hasN=false: acc0=pos, acc1=uv, [acc2..acc(1+N)]=idx per floor, acc(2+N)=image
        val indicesAccStart = if (hasN) 3 else 2
        val imageAccIdx = indicesAccStart + perFloorIndices.size

        val n = totalPositions / 3
        val u = totalUvs / 2
        val sharedAccessors = if (hasN) {
            """{"bufferView":0,"componentType":5126,"count":$n,"type":"VEC3","min":[$mx,$my,$mz],"max":[$Mx,$My,$Mz]},{"bufferView":1,"componentType":5126,"count":$n,"type":"VEC3"},{"bufferView":2,"componentType":5126,"count":$u,"type":"VEC2"}"""
        } else {
            """{"bufferView":0,"componentType":5126,"count":$n,"type":"VEC3","min":[$mx,$my,$mz],"max":[$Mx,$My,$Mz]},{"bufferView":1,"componentType":5126,"count":$u,"type":"VEC2"}"""
        }
        val perFloorAccessors = perFloorIndices.mapIndexed { i, count ->
            val byteOffsetIntoIndices = perFloorIndices.take(i).sum() * 4
            """{"bufferView":$idxBvIdx,"byteOffset":$byteOffsetIntoIndices,"componentType":5125,"count":$count,"type":"SCALAR"}"""
        }
        val imageAccessor = """{"bufferView":$atlasBvIdx,"componentType":5121,"count":1,"type":"SCALAR"}"""
        val accessors = "[" + listOf(sharedAccessors, perFloorAccessors.joinToString(","), imageAccessor).joinToString(",") + "]"

        val bufferViews = if (hasN)
            """[{"buffer":0,"byteOffset":$po,"byteLength":$posBytesSize},{"buffer":0,"byteOffset":$no,"byteLength":$nrmBytesSize},{"buffer":0,"byteOffset":$uo,"byteLength":$uvBytesSize},{"buffer":0,"byteOffset":$io,"byteLength":${perFloorIndices.sum() * 4}},{"buffer":0,"byteOffset":$ao,"byteLength":$atlasSize}]"""
        else
            """[{"buffer":0,"byteOffset":$po,"byteLength":$posBytesSize},{"buffer":0,"byteOffset":$uo,"byteLength":$uvBytesSize},{"buffer":0,"byteOffset":$io,"byteLength":${perFloorIndices.sum() * 4}},{"buffer":0,"byteOffset":$ao,"byteLength":$atlasSize}]"""

        val meshNodes = (0 until perFloorIndices.size).joinToString(",") { i ->
            val y = i * options.explodeGap
            val translation = "[0,$y,0]"
            """{"translation":$translation,"mesh":$i}"""
        }

        return """{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"children":[${(1..perFloorIndices.size).joinToString(",")}]},$meshNodes],"meshes":[${(0 until perFloorIndices.size).joinToString(",") { i ->
            val indicesIdx = indicesAccStart + i
            val prim = """{"attributes":{$attributeMap},"indices":$indicesIdx,"material":0}"""
            """{"primitives":[$prim]}"""
        }}],"accessors":$accessors,"bufferViews":$bufferViews,"buffers":[{"byteLength":$tb}],"images":[{"bufferView":$atlasBvIdx,"mimeType":"image/png"}],"textures":[{"source":0,"sampler":0}],"materials":[{"pbrMetallicRoughness":{"baseColorTexture":{"index":0}},"alphaMode":"MASK","alphaCutoff":0.05,"doubleSided":true}],"samplers":[{"magFilter":9728,"minFilter":9728,"wrapS":33071,"wrapT":33071}]}"""
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
     * using the existing 64 KB staging buffer. The index values are translated
     * by [vertexOffset] before writing so they reference the shared POSITION
     * accessor (which spans all floors).
     */
    internal fun writeFloor(
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
        // floorIdx / yMin / yMax are metadata for downstream consumers (e.g. the
        // FloorSlice wrapper in LitematicToGlb) but the byte stream doesn't use them.
        @Suppress("UNUSED_PARAMETER") val unused1 = floorIdx
        @Suppress("UNUSED_PARAMETER") val unused2 = yMin
        @Suppress("UNUSED_PARAMETER") val unused3 = yMax

        val out = if (stream is BufferedOutputStream) stream else BufferedOutputStream(stream, 1 shl 16)
        val staging = ByteArray(1 shl 16)
        val sbb = ByteBuffer.wrap(staging).order(ByteOrder.LITTLE_ENDIAN)

        writeFloats(out, staging, sbb, positions)
        if (normals != null && normals.isNotEmpty()) writeFloats(out, staging, sbb, normals)
        writeFloats(out, staging, sbb, uvs)
        writeIndices(out, staging, sbb, indices, vertexOffset)
        out.flush()
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
