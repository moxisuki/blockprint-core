package io.github.moxisuki.blockprint.core.glb.synthetic

import io.github.moxisuki.blockprint.core.glb.Element
import io.github.moxisuki.blockprint.core.glb.Face
import io.github.moxisuki.blockprint.core.glb.ResolvedModel

/**
 * vanilla 1.13+ 把 conduit 的模型清空，让 `ConduitRenderer` 接管
 * （带旋转动画、眼睛睁开闭合、wind texture 等）。
 * 我们没有运行时实体渲染器，所以这里硬编码一份"够用"的近似几何：参考 MCP `ConduitRenderer.java`。
 *
 * 真实几何（参考 MCP ConduitRenderer.createConduitModel）：
 *   - wind: (-8, -8, -8) → (8, 8, 8) = 16×16×16 外框
 *   - shell: (-3, -3, -3) → (3, 3, 3) = 6×6×6 内核
 */
object SyntheticConduit {

    /** 块名是否是 conduit。 */
    fun texNameFor(name: String): String? = when (name) {
        "minecraft:conduit" -> "conduit"
        else -> null
    }

    /**
     * @param texPath `minecraft:textures/entity/conduit/<name>` 形式的纹理路径。
     * 几何（参考 MCP ConduitRenderer）：2 个 box
     *   - wind 外框 16×16×16
     *   - shell 内核 6×6×6
     */
    fun build(texPath: String): ResolvedModel {
        val tex = if (':' in texPath) texPath else "minecraft:textures/$texPath"
        // 完整 16-px 空间 = 整张纹理（conduit/base.png 是 32×16）
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
            // 外框 16×16×16
            Element(
                from = listOf(0.0, 0.0, 0.0),
                to = listOf(16.0, 16.0, 16.0),
                faces = faces,
                rotation = null,
            ),
            // 内核 6×6×6
            Element(
                from = listOf(5.0, 5.0, 5.0),
                to = listOf(11.0, 11.0, 11.0),
                faces = faces,
                rotation = null,
            ),
        ))
    }
}
