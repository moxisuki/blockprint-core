package io.github.moxisuki.blockprint.core.glb.synthetic

import io.github.moxisuki.blockprint.core.glb.Element
import io.github.moxisuki.blockprint.core.glb.Face
import io.github.moxisuki.blockprint.core.glb.ResolvedModel

/**
 * vanilla 1.13+ 把 shulker_box 类方块（17 色）的模型清空，让 `ShulkerBoxRenderer` 接管
 * （带盖子开合动画、染色彩色版本等）。我们没有运行时实体渲染器，所以这里硬编码
 * 一份"够用"的近似几何：16×16×16 box，盖子永远关闭。
 *
 * 17 个变体（16 色 + 未染色）：
 *   - white / orange / magenta / light_blue / yellow / lime / pink /
 *     gray / light_gray / cyan / purple / blue / brown / green / red / black
 *   - shulker_box（未染色默认版）
 */
object SyntheticShulkerBox {

    /** 块名 → shulker 纹理文件名后缀。null 表示不是 shulker_box。 */
    fun texNameFor(name: String): String? = when {
        name == "shulker_box" -> "shulker"
        name.endsWith("_shulker_box") -> {
            val color = name.removeSuffix("_shulker_box")
            when (color) {
                "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
                "gray", "light_gray", "cyan", "purple", "blue", "brown", "green",
                "red", "black" -> "shulker_$color"
                else -> null
            }
        }
        else -> null
    }

    /**
     * @param texPath `minecraft:textures/entity/shulker/<name>` 形式的纹理路径。
     *
     * 几何：单个 16×16×16 box。
     *
     * 纹理实际布局（实测像素密度，纹理是 64×64）：
     *   - (0, 0)-(15, 15)：空白（X=0-15 透明，X=32-47 几乎透明）
     *   - (16, 0)-(31, 15)：盖子左边（密集）
     *   - (0, 16)-(63, 23)：**body 区域**（100% 密度）← 用这个
     *   - (0, 24)-(63, 31)：底部
     *
     * UV 简化：6 面都用 body 区域 (`uv(0, 16, 16, 8)` in 64-px 空间)，
     * 16×8 区域被拉伸到 16×16 面上——视觉粗糙但能看出是 shulker。
     */
    fun build(texPath: String): ResolvedModel {
        val tex = if (':' in texPath) texPath else "minecraft:textures/$texPath"
        val uv = { u: Double, v: Double, w: Double, h: Double ->
            // 64×64 atlas → 16-像素空间
            listOf(u * 16 / 64, v * 16 / 64, (u + w) * 16 / 64, (v + h) * 16 / 64)
        }
        // 用 body 区域 (0, 16)-(16, 24) in 64-px 空间：100% 密度、不透明
        val bodyUV = uv(0.0, 16.0, 16.0, 8.0)
        val faces = mapOf(
            "down"  to Face(tex, bodyUV, null, 0),
            "up"    to Face(tex, bodyUV, null, 0),
            "north" to Face(tex, bodyUV, null, 0),
            "south" to Face(tex, bodyUV, null, 0),
            "east"  to Face(tex, bodyUV, null, 0),
            "west"  to Face(tex, bodyUV, null, 0),
        )
        return ResolvedModel(listOf(
            Element(
                from = listOf(0.0, 0.0, 0.0),
                to = listOf(16.0, 16.0, 16.0),
                faces = faces,
                rotation = null,
            )
        ))
    }
}
