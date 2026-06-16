package io.github.moxisuki.blockprint.core.glb

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

        // Build per-attribute byte arrays.
        val posBytes = FloatArray(totalPositions).also { buf ->
            var off = 0
            for (f in floors) { f.positions.copyInto(buf, off); off += f.positions.size }
        }
        val uvBytes = FloatArray(totalUvs).also { buf ->
            var off = 0
            for (f in floors) { f.uvs.copyInto(buf, off); off += f.uvs.size }
        }
        val nrmBytes = if (hasN) FloatArray(totalNormals).also { buf ->
            var off = 0
            for (f in floors) {
                val n = f.normals ?: continue
                n.copyInto(buf, off); off += n.size
            }
        } else FloatArray(0)

        // Index offsetting: each floor's local indices are shifted by the
        // cumulative vertex count of preceding floors so they reference the
        // correct vertices in the shared POSITION accessor.
        val idxSource = IntArray(totalIndices).also { buf ->
            var vertexOffset = 0
            var indexOffset = 0
            for (f in floors) {
                val verts = f.positions.size / 3
                for (i in f.indices.indices) {
                    buf[indexOffset + i] = f.indices[i] + vertexOffset
                }
                vertexOffset += verts
                indexOffset += f.indices.size
            }
        }
        val posBs = fa(posBytes); val uvBs = fa(uvBytes); val nrmBs = fa(nrmBytes)
        val idxBs = ia(idxSource)

        val pp = pad4(posBs); val pu = pad4(uvBs); val pi = pad4(idxBs); val pn = pad4(nrmBs); val pa = pad4(output.atlasPng)

        val tb = pp.size + pu.size + (if (hasN) pn.size else 0) + pi.size + pa.size
        var po = 0
        var no = -1
        var uo = 0
        var io = 0
        if (hasN) { no = po + pp.size; uo = no + pn.size; io = uo + pu.size }
        else { uo = po + pp.size; io = uo + pu.size }
        val ao = io + pi.size

        val mm = computeMinMax(posBytes, totalPositions / 3)
        val json = buildJson(
            floors = floors,
            options = options,
            totalPositions = totalPositions,
            totalUvs = totalUvs,
            totalNormals = totalNormals,
            totalIndices = totalIndices,
            perFloorIndices = floors.map { it.indices.size },
            posBytesSize = posBytes.size * 4,
            uvBytesSize = uvBytes.size * 4,
            nrmBytesSize = if (hasN) nrmBytes.size * 4 else 0,
            atlasSize = output.atlasPng.size,
            tb = tb,
            po = po, uo = uo, no = no, io = io, ao = ao,
            mm = mm, atlasWidth = output.atlasWidth, atlasHeight = output.atlasHeight,
            hasN = hasN,
        )

        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val pj = pad4(jsonBytes)
        val tl = 12 + 8 + pj.size + 8 + tb
        val buf = ByteBuffer.allocate(tl).apply { order(ByteOrder.LITTLE_ENDIAN) }
        buf.putInt(0x46546C67); buf.putInt(2); buf.putInt(tl)
        buf.putInt(pj.size); buf.putInt(0x4E4F534A); buf.put(jsonBytes)
        for (i in jsonBytes.size until pj.size) buf.put(0x20.toByte())
        buf.putInt(tb); buf.putInt(0x004E4942)
        if (hasN) { buf.put(pp); buf.put(pn); buf.put(pu); buf.put(pi); buf.put(pa) }
        else { buf.put(pp); buf.put(pu); buf.put(pi); buf.put(pa) }
        stream.write(buf.array())
    }

    private fun computeMinMax(pos: FloatArray, n: Int): FloatArray {
        if (n == 0) return floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)
        var a = Float.MAX_VALUE; var b = Float.MAX_VALUE; var c = Float.MAX_VALUE
        var x = -Float.MAX_VALUE; var y = -Float.MAX_VALUE; var z = -Float.MAX_VALUE
        for (i in 0 until n) {
            val px = pos[i * 3]; val py = pos[i * 3 + 1]; val pz = pos[i * 3 + 2]
            if (px < a) a = px; if (py < b) b = py; if (pz < c) c = pz
            if (px > x) x = px; if (py > y) y = py; if (pz > z) z = pz
        }
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

    private fun fa(a: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(a.size * 4).apply { order(ByteOrder.LITTLE_ENDIAN) }
        for (v in a) bb.putFloat(v)
        return bb.array()
    }
    private fun ia(a: IntArray): ByteArray {
        val bb = ByteBuffer.allocate(a.size * 4).apply { order(ByteOrder.LITTLE_ENDIAN) }
        for (v in a) bb.putInt(v)
        return bb.array()
    }
    private fun pad4(b: ByteArray): ByteArray {
        val r = b.size % 4
        if (r == 0) return b
        return ByteArray(b.size + (4 - r)).also { b.copyInto(it) }
    }
}
