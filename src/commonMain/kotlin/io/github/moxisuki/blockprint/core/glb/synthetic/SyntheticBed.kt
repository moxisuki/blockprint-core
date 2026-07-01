package io.github.moxisuki.blockprint.core.glb.synthetic

import io.github.moxisuki.blockprint.core.glb.model.Element
import io.github.moxisuki.blockprint.core.glb.model.Face
import io.github.moxisuki.blockprint.core.glb.model.ResolvedModel

/**
 * 床 - entity model texOffs 公式（和 chest 同标准），
 * 侧面(west/east)使用标准 entity West/East UV 区域，
 * 并以 rotation=90 补偿 XP90° 导致的纵横比交换。
 */
object SyntheticBed {

    fun build(texPath: String, part: String): ResolvedModel {
        val tex = if (':' in texPath) texPath else "minecraft:textures/$texPath"
        val uv = { u: Double, v: Double, w: Double, h: Double ->
            listOf(u * 16 / 64, v * 16 / 64, (u + w) * 16 / 64, (v + h) * 16 / 64)
        }

        val mV = if (part == "head") 0.0 else 22.0
        val l1V = if (part == "head") 6.0 else 0.0
        val l2V = if (part == "head") 18.0 else 12.0

        // 主盒：texOffs(0,mV).addBox(0,0,0, 16,16,6)  dx=16 dy=16 dz=6
        // 标准 entity UV 布局（64×64 纹理）：
        //   West:  cols [0,  6), rows [mV+6, mV+22)   — 6×16
        //   North: cols [6, 22), rows [mV+6, mV+22)   — 16×16 (床顶面/毯子+枕头)
        //   East:  cols [22,28), rows [mV+6, mV+22)   — 6×16
        //   South: cols [28,44), rows [mV+6, mV+22)   — 16×16 (床底面)
        //   Up:    cols [6, 22), rows [mV,   mV+6)    — 16×6  (薄边)
        //   Down:  cols [22,38), rows [mV,   mV+6)    — 16×6  (薄边)
        // XP90°旋转映射: entity North→world up, South→down, Up→south, Down→north
        val mainFaces = mapOf(
            "up"    to Face(tex, uv( 6.0, mV+6.0, 16.0, 16.0), null, 0),  // entity North → bed top surface
            "down"  to Face(tex, uv(28.0, mV+6.0, 16.0, 16.0), null, 0),  // entity South → bed bottom
            "south" to Face(tex, uv(22.0, mV+6.0, 16.0, -6.0), null, 0),  // thin edge (foot-side), V翻转
            "north" to Face(tex, uv( 6.0, mV+6.0, 16.0, -6.0), null, 0),  // thin edge (wall-side), V翻转
            // 侧面：rotation=90 补偿 XP90° 导致的纵横比交换（entity UV 6×16 → world face 16×6）
            // east 需要翻转 V 方向（east face 顶点 Z 排序与 west 相反）
            "west"  to Face(tex, uv( 0.0, mV+6.0,  6.0, 16.0), null, 90),
            "east"  to Face(tex, uv(22.0, mV+22.0, 6.0,-16.0), null, 90),
        )

        // 腿: texOffs(50,lV).addBox(0,6,0, 3,3,3) → entity 公式
        fun leg(U: Double, lV: Double) = mapOf(
            "west"  to Face(tex, uv(U,     lV+3.0, 3.0, 3.0), null, 0),
            "east"  to Face(tex, uv(U+3.0, lV+3.0, 3.0, 3.0), null, 0),
            "up"    to Face(tex, uv(U+6.0, lV+3.0, 3.0, 3.0), null, 0),
            "down"  to Face(tex, uv(U+9.0, lV+3.0, 3.0, 3.0), null, 0),
            "north" to Face(tex, uv(U+3.0, lV,     3.0, 3.0), null, 0),
            "south" to Face(tex, uv(U+6.0, lV,     3.0, 3.0), null, 0),
        )

        val elements = mutableListOf<Element>()
        elements.add(Element(
            from = listOf(0.0, 4.0, 0.0), to = listOf(16.0, 10.0, 16.0),
            faces = mainFaces, rotation = null,
        ))
        if (part == "head") {
            elements.add(Element(from = listOf(0.0, 0.0, 0.0),  to = listOf(4.0, 4.0, 4.0),  faces = leg(50.0, l1V), rotation = null))
            elements.add(Element(from = listOf(12.0, 0.0, 0.0), to = listOf(16.0, 4.0, 4.0),  faces = leg(50.0, l2V), rotation = null))
        } else {
            elements.add(Element(from = listOf(0.0, 0.0, 12.0), to = listOf(4.0, 4.0, 16.0), faces = leg(50.0, l1V), rotation = null))
            elements.add(Element(from = listOf(12.0, 0.0, 12.0), to = listOf(16.0, 4.0, 16.0), faces = leg(50.0, l2V), rotation = null))
        }
        return ResolvedModel(elements)
    }
}
