package io.github.moxisuki.blockprint.core.glb.synthetic

import io.github.moxisuki.blockprint.core.glb.Element
import io.github.moxisuki.blockprint.core.glb.Face
import io.github.moxisuki.blockprint.core.glb.ResolvedModel

/**
 * vanilla 1.20+ 把 sign 类方块（oak_sign、birch_wall_sign、acacia_hanging_sign…）的
 * 模型清空，由 `SignRenderer` / `HangingSignRenderer` 接管（带链子、文本浮雕等）。
 * 我们没有文本浮雕渲染能力，只画几何 box。
 *
 * 三种形态：
 * - standing（地面立牌）：16×14×2 板子 + 1×12×1 支柱 → 简化成单 box 16×16×2
 * - wall（贴墙牌）：16×14×2 板子（无支柱）
 * - hanging（悬挂牌）：6×6×10 牌 + 链子 → 简化成 6×10×10 box，链子省略
 */
object SyntheticSign {

    /** 地面立牌：从地面上立起 14 高，2 厚。 */
    fun buildStanding(texPath: String): ResolvedModel {
        val tex = if (':' in texPath) texPath else "minecraft:textures/$texPath"
        return box(tex, w = 16.0, h = 16.0, d = 2.0, baseY = 0.0)
    }

    /** 贴墙牌：14 高 × 16 宽 × 2 厚，贴附在墙上（不画支柱）。 */
    fun buildWall(texPath: String): ResolvedModel {
        val tex = if (':' in texPath) texPath else "minecraft:textures/$texPath"
        return box(tex, w = 16.0, h = 14.0, d = 2.0, baseY = 2.0)
    }

    /** 悬挂牌：6×6×10 牌本体悬挂在链子上（链子省略）。 */
    fun buildHanging(texPath: String): ResolvedModel {
        val tex = if (':' in texPath) texPath else "minecraft:textures/$texPath"
        // 牌从 y=6 悬到 y=16（10 高），6 宽 × 6 厚
        return box(tex, w = 6.0, h = 10.0, d = 6.0, baseY = 6.0)
    }

    /**
     * 通用 helper：构造一个轴对齐的 box 几何，6 面都用 [texPath] 同纹理（vanilla sign
     * 纹理实际上是 64×32 atlas 里切的 3 段——本简化为整张图拉伸，视觉粗糙但位置正确）。
     */
    private fun box(tex: String, w: Double, h: Double, d: Double, baseY: Double): ResolvedModel {
        val faces = mapOf(
            "down"  to Face(tex, listOf(0.0, 0.0, w, d), "down", 0),
            "up"    to Face(tex, listOf(0.0, 0.0, w, d), "up", 0),
            "north" to Face(tex, listOf(0.0, 0.0, w, h), "north", 0),
            "south" to Face(tex, listOf(0.0, 0.0, w, h), "south", 0),
            "west"  to Face(tex, listOf(0.0, 0.0, d, h), "west", 0),
            "east"  to Face(tex, listOf(0.0, 0.0, d, h), "east", 0),
        )
        return ResolvedModel(listOf(
            Element(
                from = listOf((16 - w) / 2, baseY, (16 - d) / 2),
                to = listOf((16 + w) / 2, baseY + h, (16 + d) / 2),
                faces = faces,
                rotation = null,
            )
        ))
    }
}
