package io.github.moxisuki.blockprint.core.glb.synthetic

import io.github.moxisuki.blockprint.core.glb.Element
import io.github.moxisuki.blockprint.core.glb.Face
import io.github.moxisuki.blockprint.core.glb.ResolvedModel

/**
 * 讲台（lectern）的合成模型 —— 参考 lecern.json 的 3 元素几何，但避开原模型
 * 的 X -22.5° 旋转（会把顶板顶出 Y=16 方块边界）和多纹理交叉 UV 问题。
 *
 * 几何（3 个 box，类似 vanilla 3 元素）：
 *   - base: 16×2×16 底座（Y=0..2）
 *   - pole: 8×13×8 主柱（Y=2..15）
 *   - top:  16×3×13 顶部板（Y=13..16），不旋转 / 纯粹轴对齐
 *
 * 纹理：全部用 lectern_base（16×16）做占位 —— 位置正确、结构清晰。
 * 书本 / 凹凸等细节省略（vanilla 是通过独立模型附加的）。
 */
object SyntheticLectern {

    fun build(texPath: String): ResolvedModel {
        val tex = if (':' in texPath) texPath else "minecraft:textures/$texPath"
        val fullUV = listOf(0.0, 0.0, 16.0, 16.0)
        val faces = mapOf(
            "down"  to Face(tex, fullUV, null, 0),
            "up"    to Face(tex, fullUV, null, 0),
            "north" to Face(tex, fullUV, null, 0),
            "south" to Face(tex, fullUV, null, 0),
            "east"  to Face(tex, fullUV, null, 0),
            "west"  to Face(tex, fullUV, null, 0),
        )
        return ResolvedModel(listOf(
            // base: 16×2×16 底座
            Element(
                from = listOf(0.0, 0.0, 0.0),
                to = listOf(16.0, 2.0, 16.0),
                faces = faces, rotation = null,
            ),
            // pole: 8×13×8 主柱（居中）
            Element(
                from = listOf(4.0, 2.0, 4.0),
                to = listOf(12.0, 15.0, 12.0),
                faces = faces, rotation = null,
            ),
            // top: 16×3×13 顶部板（居中偏前，约 3 像素高）
            Element(
                from = listOf(0.0, 13.0, 0.0),
                to = listOf(16.0, 16.0, 13.0),
                faces = faces, rotation = null,
            ),
        ))
    }
}
