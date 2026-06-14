package io.github.moxisuki.blockprint.core.glb.synthetic

import io.github.moxisuki.blockprint.core.glb.Element
import io.github.moxisuki.blockprint.core.glb.Face
import io.github.moxisuki.blockprint.core.glb.ResolvedModel

/**
 * vanilla 1.13+ 把流体方块（水/岩浆）的模型清空，让 `FluidRenderer` 接管
 * （带流动动画、水位 level 等）。我们没有运行时实体渲染器，所以这里硬编码
 * 一份"够用"的近似几何：16×16×h box，h 按 level 0..7 取 16/14/12/10/8/6/4/2。
 *
 * 侧面 UV 只采底部 h 像素（pre-1.13 vanilla 行为）。
 *
 * 4 个变体：water / flowing_water / lava / flowing_lava
 *   - water: `textures/block/water_still`
 *   - lava:  `textures/block/lava_still`
 */
object SyntheticFluid {

    /** 块名（**去掉 "minecraft:" 前缀**）→ 纹理子路径。null 表示不是流体。 */
    fun texNameFor(name: String): String? = when (name) {
        "water", "flowing_water" -> "water_still"
        "lava", "flowing_lava" -> "lava_still"
        else -> null
    }

    /**
     * @param texPath `minecraft:textures/block/<name>` 形式的纹理路径。
     * @param level 流体 level（0..7 决定高度；8..15 退化同 0..7）
     *
     * 几何：单个 16×16×h box，h 按 level 取值
     *
     * **所有面 tintindex=0**——触发 MeshBuilder 的 specialTints colormap：
     *   - water → 0xFF1E5AA8（蓝色）
     *   - lava  → 0xFFD45E00（橙色）
     */
    fun build(texPath: String, level: Int): ResolvedModel {
        val tex = if (':' in texPath) texPath else "minecraft:textures/$texPath"
        val h = when (level and 0x7) {
            0 -> 16; 1 -> 14; 2 -> 12; 3 -> 10
            4 -> 8;  5 -> 6;  6 -> 4;  7 -> 2
            else -> 16
        }
        val sideV1 = 16.0 - h
        return ResolvedModel(listOf(
            Element(
                from = listOf(0.0, 0.0, 0.0),
                to = listOf(16.0, h.toDouble(), 16.0),
                faces = mapOf(
                    // 上下底面用全 16×16 + tintindex=0（触发蓝/橙 colormap）
                    "down"  to Face(tex, listOf(0.0, 0.0, 16.0, 16.0), "down", 0, 0),
                    "up"    to Face(tex, listOf(0.0, 0.0, 16.0, 16.0), "up", 0, 0),
                    // 侧面只采底部 h 像素 + tintindex=0
                    "north" to Face(tex, listOf(0.0, sideV1, 16.0, 16.0), "north", 0, 0),
                    "south" to Face(tex, listOf(0.0, sideV1, 16.0, 16.0), "south", 0, 0),
                    "west"  to Face(tex, listOf(0.0, sideV1, 16.0, 16.0), "west", 0, 0),
                    "east"  to Face(tex, listOf(0.0, sideV1, 16.0, 16.0), "east", 0, 0),
                )
            )
        ))
    }
}
