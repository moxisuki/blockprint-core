package io.github.moxisuki.blockprint.core.glb

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class GlbOutput(
    val positions: FloatArray,
    val uvs: FloatArray,
    val normals: FloatArray? = null,
    val indices: IntArray,
    val atlasPng: ByteArray,
    val atlasWidth: Int,
    val atlasHeight: Int,
)

class GlbWriter {
    fun write(output: GlbOutput, stream: OutputStream) {
        val vc = output.positions.size / 3
        val ic = output.indices.size
        val hasN = output.normals != null && output.normals!!.isNotEmpty()

        val posBytes = fa(output.positions); val uvBytes = fa(output.uvs)
        val idxBytes = ia(output.indices)
        val nrmBytes = if (hasN) fa(output.normals!!) else byteArrayOf()

        val pp = pad4(posBytes); val pu = pad4(uvBytes)
        val pi = pad4(idxBytes); val pn = pad4(nrmBytes); val pa = pad4(output.atlasPng)

        val tb = pp.size + pu.size + (if(hasN)pn.size else 0) + pi.size + pa.size
        val po=0; val no=if(hasN)pp.size else-1
        val uo=if(hasN)no+pn.size else pp.size
        val io=(if(hasN)uo+pu.size else uo+pu.size); val ao=io+pi.size

        val mm = computeMinMax(output.positions, vc)
        val json = buildJson(vc, ic, posBytes.size, uvBytes.size, nrmBytes.size, idxBytes.size,
            output.atlasPng.size, tb, po, uo, no, io, ao, mm, output.atlasWidth, output.atlasHeight, hasN)

        val jsonBytes = json.toByteArray(Charsets.UTF_8); val pj = pad4(jsonBytes)
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
        var a=Float.MAX_VALUE;var b=Float.MAX_VALUE;var c=Float.MAX_VALUE
        var x=-Float.MAX_VALUE;var y=-Float.MAX_VALUE;var z=-Float.MAX_VALUE
        for (i in 0 until n) { val px=pos[i*3];val py=pos[i*3+1];val pz=pos[i*3+2]
            if(px<a)a=px;if(py<b)b=py;if(pz<c)c=pz;if(px>x)x=px;if(py>y)y=py;if(pz>z)z=pz }
        return floatArrayOf(a,b,c,x,y,z)
    }

    private fun buildJson(vc:Int,ic:Int,ps:Int,us:Int,ns:Int,ix:Int,at:Int,tb:Int,
        po:Int,uo:Int,no:Int,io:Int,ao:Int,mm:FloatArray,aw:Int,ah:Int,hn:Boolean):String{
        val mx=mm[0];val my=mm[1];val mz=mm[2];val Mx=mm[3];val My=mm[4];val Mz=mm[5]
        val attr=if(hn)"\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2" else "\"POSITION\":0,\"TEXCOORD_0\":1"
        val acc=if(hn)
            """[{"bufferView":0,"componentType":5126,"count":$vc,"type":"VEC3","min":[$mx,$my,$mz],"max":[$Mx,$My,$Mz]},{"bufferView":1,"componentType":5126,"count":$vc,"type":"VEC3"},{"bufferView":2,"componentType":5126,"count":$vc,"type":"VEC2"},{"bufferView":3,"componentType":5125,"count":$ic,"type":"SCALAR"}]"""
            else
            """[{"bufferView":0,"componentType":5126,"count":$vc,"type":"VEC3","min":[$mx,$my,$mz],"max":[$Mx,$My,$Mz]},{"bufferView":1,"componentType":5126,"count":$vc,"type":"VEC2"},{"bufferView":2,"componentType":5125,"count":$ic,"type":"SCALAR"}]"""
        val bv=if(hn)
            """[{"buffer":0,"byteOffset":$po,"byteLength":$ps},{"buffer":0,"byteOffset":$no,"byteLength":$ns},{"buffer":0,"byteOffset":$uo,"byteLength":$us},{"buffer":0,"byteOffset":$io,"byteLength":$ix},{"buffer":0,"byteOffset":$ao,"byteLength":$at}]"""
            else
            """[{"buffer":0,"byteOffset":$po,"byteLength":$ps},{"buffer":0,"byteOffset":$uo,"byteLength":$us},{"buffer":0,"byteOffset":$io,"byteLength":$ix},{"buffer":0,"byteOffset":$ao,"byteLength":$at}]"""
        val iidx=if(hn)3 else 2; val ibv=if(hn)4 else 3
        return """{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],"meshes":[{"primitives":[{"attributes":{$attr},"indices":$iidx,"material":0}]}],"accessors":$acc,"bufferViews":$bv,"buffers":[{"byteLength":$tb}],"images":[{"bufferView":$ibv,"mimeType":"image/png"}],"textures":[{"source":0,"sampler":0}],"materials":[{"pbrMetallicRoughness":{"baseColorTexture":{"index":0}},"alphaMode":"MASK","alphaCutoff":0.05,"doubleSided":true}],"samplers":[{"magFilter":9728,"minFilter":9728,"wrapS":33071,"wrapT":33071}]}"""
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
    private fun pad4(b:ByteArray):ByteArray{val r=b.size%4;if(r==0)return b;return ByteArray(b.size+(4-r)).also{b.copyInto(it)}}
}
