package io.github.moxisuki.blockprint.core.glb.internal

import io.github.moxisuki.blockprint.core.glb.RawMesh
import java.nio.file.Files
import java.nio.file.Path

object ObjParser {

    fun parse(objPath: Path, textures: Map<String, String>, flipV: Boolean): List<RawMesh> {
        val lines = Files.readAllLines(objPath)
        val verts = mutableListOf<FloatArray>()
        val uvs = mutableListOf<FloatArray>()
        var curMtl = ""
        val triData = mutableMapOf<String, MutableList<List<Int>>>() // material → [(v1,t1, v2,t2, v3,t3), ...]

        var mtlFile: Path? = null
        for (line in lines) {
            when {
                line.startsWith("mtllib ") -> mtlFile = objPath.parent.resolve(line.substringAfter("mtllib ").trim())
                line.startsWith("v ") -> {
                    val p = line.trim().split("\\s+".toRegex())
                    verts.add(floatArrayOf(p[1].toFloat(), p[2].toFloat(), p[3].toFloat()))
                }
                line.startsWith("vt ") -> {
                    val p = line.trim().split("\\s+".toRegex())
                    uvs.add(floatArrayOf(p[1].toFloat(), p.getOrElse(2) { "0" }.toFloat()))
                }
                line.startsWith("usemtl ") -> { curMtl = line.substringAfter("usemtl ").trim() }
                line.startsWith("f ") -> {
                    val parts = line.trim().split("\\s+".toRegex()).drop(1)
                    val raw = parts.map { t ->
                        val s = t.split("/")
                        intArrayOf(s[0].toInt(), s.getOrNull(1)?.toIntOrNull() ?: 0)
                    }
                    // 正索引化 (0-based)
                    val idx = raw.map { arr ->
                        val vi = if (arr[0] > 0) arr[0] - 1 else verts.size + arr[0]
                        val ti = if (arr[1] > 0) arr[1] - 1 else if (arr[1] < 0) uvs.size + arr[1] else -1
                        Pair(vi, ti)
                    }
                    // fan triangulation
                    val group = triData.getOrPut(curMtl) { mutableListOf() }
                    for (k in 1 until idx.size - 1) {
                        val a = idx[0]; val b = idx[k]; val c = idx[k + 1]
                        group.add(listOf(a.first, a.second, b.first, b.second, c.first, c.second))
                    }
                }
            }
        }

        val mtlTextures = mutableMapOf<String, String>()
        if (mtlFile != null && Files.isRegularFile(mtlFile)) {
            var mat = ""
            for (line in Files.readAllLines(mtlFile)) {
                when {
                    line.startsWith("newmtl ") -> mat = line.substringAfter("newmtl ").trim()
                    line.startsWith("map_Kd ") -> mtlTextures[mat] = line.substringAfter("map_Kd ").trim()
                }
            }
        }

        val scale = 16.0
        val meshes = mutableListOf<RawMesh>()

        for ((mtl, triangles) in triData) {
            val texRef = mtlTextures[mtl] ?: continue
            val texPath = if (texRef.startsWith("#")) textures[texRef.removePrefix("#")] ?: texRef else texRef
            if (texPath.isEmpty()) continue

            // 索引去重: (vi,ti) → outputIndex
            val vMap = LinkedHashMap<Long, Int>() // key: (vi<<20)|(ti&0xFFFFF)
            val posList = mutableListOf<Float>()
            val uvList = mutableListOf<Float>()
            val nrmAcc = mutableListOf<Float>() // 累加法向
            val idxOut = mutableListOf<Int>()

            for (tri in triangles) {
                val t0 = tri[0]; val t1 = tri[1]; val t2 = tri[2]
                val t3 = tri[3]; val t4 = tri[4]; val t5 = tri[5]

                // 面法向
                val ax = verts[t0]; val bx = verts[t2]; val cx = verts[t4]
                val e1x = bx[0]-ax[0]; val e1y = bx[1]-ax[1]; val e1z = bx[2]-ax[2]
                val e2x = cx[0]-ax[0]; val e2y = cx[1]-ax[1]; val e2z = cx[2]-ax[2]
                val nx = e1y*e2z - e1z*e2y; val ny = e1z*e2x - e1x*e2z; val nz = e1x*e2y - e1y*e2x
                val len = Math.sqrt((nx*nx + ny*ny + nz*nz).toDouble()).toFloat()
                val nnx = if (len > 0) nx/len else 0f
                val nny = if (len > 0) ny/len else 1f
                val nnz = if (len > 0) nz/len else 0f

                fun emitVertex(vi: Int, ti: Int): Int {
                    val key = (vi.toLong() shl 20) or (ti.toLong() and 0xFFFFF)
                    return vMap.getOrPut(key) {
                        val rv = verts[vi]
                        posList.addAll(listOf((rv[0]*scale).toFloat(), (rv[1]*scale).toFloat(), (rv[2]*scale).toFloat()))
                        nrmAcc.addAll(listOf(nnx, nny, nnz)) // initialize with first face normal
                        val uv = if (ti in 0 until uvs.size) uvs[ti] else floatArrayOf(0f, 0f)
                        uvList.addAll(listOf(uv[0], if (flipV) 1f - uv[1] else uv[1]))
                        vMap.size
                    }
                }

                val i0 = emitVertex(t0, t1)
                val i1 = emitVertex(t2, t3)
                val i2 = emitVertex(t4, t5)

                // 累加法向（用第一个面的法向初始化，不累加）
                // 法向已在 emitVertex 时设置

                idxOut.addAll(listOf(i0, i1, i2))
            }

            meshes.add(RawMesh(posList, uvList, nrmAcc, idxOut.toList(), texPath))
        }

        return meshes
    }
}
