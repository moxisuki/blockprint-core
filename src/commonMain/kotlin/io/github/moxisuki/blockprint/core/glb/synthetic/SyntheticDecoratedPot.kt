package io.github.moxisuki.blockprint.core.glb.synthetic

import io.github.moxisuki.blockprint.core.glb.Element
import io.github.moxisuki.blockprint.core.glb.Face
import io.github.moxisuki.blockprint.core.glb.ResolvedModel

/**
 * vanilla 1.13+ 把 decorated_pot 的模型清空，让 `DecoratedPotRenderer` 接管
 * （带陶艺图案、裂纹等）。我们没有运行时实体渲染器，所以这里硬编码一份"够用"的近似
 * 几何：参考 MCP `DecoratedPotRenderer.java`。
 *
 * 真实几何（参考 MCP DecoratedPotRenderer）：
 *   - top/bottom: 14×0×14 平面（厚度 0）
 *   - 4 sides: 14×16×0 平面（厚度 0）
 *   - neck: 8×3×8 罐口
 *
 * 简化为 2 个 box：罐体 14×16×14 + 罐口 8×3×8
 */
object SyntheticDecoratedPot {

    /** 块名是否是 decorated_pot。 */
    fun texNameFor(name: String): String? = when (name) {
        "minecraft:decorated_pot" -> "decorated_pot"
        else -> null
    }

    /**
     * @param texPath `minecraft:textures/entity/decorated_pot/<name>` 形式的纹理路径。
     * 几何（参考 MCP DecoratedPotRenderer）：2 个 box
     *   - 罐体 14×16×14
     *   - 罐口 8×3×8
     */
    fun build(texPath: String): ResolvedModel {
        val tex = if (':' in texPath) texPath else "minecraft:textures/$texPath"
        // 完整 16-px 空间 = 整张纹理（decorated_pot_side.png 是 16×16）
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
            // 罐体 14×16×14
            Element(
                from = listOf(1.0, 0.0, 1.0),
                to = listOf(15.0, 16.0, 15.0),
                faces = faces,
                rotation = null,
            ),
            // 罐口 8×3×8
            Element(
                from = listOf(4.0, 13.0, 4.0),
                to = listOf(12.0, 16.0, 12.0),
                faces = faces,
                rotation = null,
            ),
        ))
    }
}
