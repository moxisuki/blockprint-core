package io.github.moxisuki.blockprint.core.glb.synthetic

import io.github.moxisuki.blockprint.core.glb.Element
import io.github.moxisuki.blockprint.core.glb.Face
import io.github.moxisuki.blockprint.core.glb.ResolvedModel

/**
 * 讲台（lectern）的合成模型 —— 参考 lectern.json 的 3 元素几何，但避开原模型
 * 的 X -22.5° 旋转（会把顶板顶出 Y=16 方块边界），并正确地使用多纹理和对应的 UV 映射。
 */
object SyntheticLectern {

    fun build(dummyTexPath: String): ResolvedModel {
        // We use the specific textures required by lectern.json
        val baseTex = "minecraft:textures/block/lectern_base"
        val topTex = "minecraft:textures/block/lectern_top"
        val sidesTex = "minecraft:textures/block/lectern_sides"
        val frontTex = "minecraft:textures/block/lectern_front"
        val planksTex = "minecraft:textures/block/oak_planks"

        return ResolvedModel(listOf(
            // base: 16×2×16 底座
            Element(
                from = listOf(0.0, 0.0, 0.0),
                to = listOf(16.0, 2.0, 16.0),
                faces = mapOf(
                    "north" to Face(baseTex, listOf(0.0, 14.0, 16.0, 16.0), null, 0),
                    "east"  to Face(baseTex, listOf(0.0, 6.0, 16.0, 8.0), null, 0),
                    "south" to Face(baseTex, listOf(0.0, 6.0, 16.0, 8.0), null, 0),
                    "west"  to Face(baseTex, listOf(0.0, 6.0, 16.0, 8.0), null, 0),
                    "up"    to Face(baseTex, listOf(0.0, 0.0, 16.0, 16.0), null, 180),
                    "down"  to Face(planksTex, listOf(0.0, 0.0, 16.0, 16.0), null, 0),
                ),
                rotation = null,
            ),
            // pole: 8×13×8 主柱（居中）
            Element(
                from = listOf(4.0, 2.0, 4.0),
                to = listOf(12.0, 15.0, 12.0),
                faces = mapOf(
                    "north" to Face(frontTex, listOf(0.0, 0.0, 8.0, 13.0), null, 0),
                    "east"  to Face(sidesTex, listOf(2.0, 16.0, 15.0, 8.0), null, 90),
                    "south" to Face(frontTex, listOf(8.0, 3.0, 16.0, 16.0), null, 0),
                    "west"  to Face(sidesTex, listOf(2.0, 8.0, 15.0, 16.0), null, 90),
                ),
                rotation = null,
            ),
            // top: 16×3×13 顶部板（居中偏后，在 Z=[3, 16]）
            Element(
                from = listOf(0.0, 13.0, 3.0),
                to = listOf(16.0, 16.0, 16.0),
                faces = mapOf(
                    "north" to Face(sidesTex, listOf(0.0, 0.0, 16.0, 4.0), null, 0),
                    "east"  to Face(sidesTex, listOf(0.0, 4.0, 13.0, 8.0), null, 0),
                    "south" to Face(sidesTex, listOf(0.0, 4.0, 16.0, 8.0), null, 0),
                    "west"  to Face(sidesTex, listOf(0.0, 4.0, 13.0, 8.0), null, 0),
                    "up"    to Face(topTex, listOf(0.0, 1.0, 16.0, 14.0), null, 180),
                    "down"  to Face(planksTex, listOf(0.0, 0.0, 16.0, 13.0), null, 0),
                ),
                rotation = null,
            ),
        ))
    }
}
