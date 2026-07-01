package io.github.moxisuki.blockprint.core.glb.synthetic

import io.github.moxisuki.blockprint.core.glb.model.Element
import io.github.moxisuki.blockprint.core.glb.model.Face
import io.github.moxisuki.blockprint.core.glb.model.ResolvedModel

/**
 * 旗帜 - **完全匹配 MCP 模型坐标**（不缩放，旗帜延伸到方块上方）。
 *
 * MCP BannerModel (standing, p_375508_=true):
 *   Pole: addBox(-1, -42, -1, 2, 42, 2)  → 2×42×2 at Y=-42..0
 *   Bar:  addBox(-10,-44, -1, 20, 2, 2)  → 20×2×2 at Y=-44..-42
 *   Flag: addBox(-10,  0, -2, 20,40, 1) + offset(0,-44,0) → 20×40×1 at Y=-44..-4
 *
 * BannerRenderer: scale(2/3,-2/3,-2/3), translate(0.5,0,0.5)
 *   → 实际渲染约 13×28×13 旗面, 1.75 方块高
 *
 * 世界坐标映射（Y 轴正向上，方块底 Y=0）：
 *   模型 Y=-44 → 世界 Y≈29（旗顶）  模型 Y=0 → 世界 Y=0（杆底）
 *   模型 Z=-2 → 世界 Z≈1.3（旗面）  模型 Z=2 → 世界 Z≈-1.3
 *
 * 为适配 16px 方块空间，旗面缩放 0.5 倍以在方块内大致可见；
 * 杆/横杠保持真实宽度。纹理复用旗面区域 (0,0)-(42,42)。
 */
object SyntheticBanner {

    fun texNameFor(name: String): String? = when {
        name == "banner" -> "base"
        name.endsWith("_wall_banner") -> {
            val color = name.removeSuffix("_wall_banner")
            if (color in setOf("white","orange","magenta","light_blue","yellow","lime","pink","gray","light_gray","cyan","purple","blue","brown","green","red","black")) "base_$color" else "base"
        }
        name.endsWith("_banner") -> {
            val color = name.removeSuffix("_banner")
            if (color in setOf("white","orange","magenta","light_blue","yellow","lime","pink","gray","light_gray","cyan","purple","blue","brown","green","red","black")) "base_$color" else "base"
        }
        else -> null
    }

    fun isWall(name: String): Boolean = name.contains("_wall")

    fun build(texPath: String, isWall: Boolean): ResolvedModel {
        val tex = if (':' in texPath) texPath else "minecraft:textures/$texPath"
        val uv = { u: Double, v: Double, w: Double, h: Double ->
            listOf(u * 16 / 64, v * 16 / 64, (u + w) * 16 / 64, (v + h) * 16 / 64)
        }
        // 全部面用旗面内容区 (0,0)-(42,42)
        val full = uv(0.0, 0.0, 42.0, 42.0)
        // 杆/横杠用橡木板纹理（不染色），旗面用 banner base 染色
        val poleTex = "minecraft:textures/block/oak_planks"
        val poleUV = uv(0.0, 0.0, 16.0, 16.0)
        val poleFaces = mapOf(
            "south" to Face(poleTex, poleUV, null, 0),
            "north" to Face(poleTex, poleUV, null, 0),
            "east"  to Face(poleTex, poleUV, null, 0),
            "west"  to Face(poleTex, poleUV, null, 0),
            "up"    to Face(poleTex, poleUV, null, 0),
            "down"  to Face(poleTex, poleUV, null, 0),
        )
        val flagFaces = mapOf(
            "south" to Face(tex, full, null, 0, 0),
            "north" to Face(tex, full, null, 0, 0),
            "east"  to Face(tex, full, null, 0, 0),
            "west"  to Face(tex, full, null, 0, 0),
            "up"    to Face(tex, full, null, 0, 0),
            "down"  to Face(tex, full, null, 0, 0),
        )

        val elements = mutableListOf<Element>()

        if (isWall) {
            elements.add(Element(from = listOf(3.0, 20.0, 14.5), to = listOf(13.0, 21.0, 15.5), faces = poleFaces))
            elements.add(Element(from = listOf(3.0, 1.0, 15.5), to = listOf(13.0, 21.0, 16.0), faces = flagFaces))
        } else {
            elements.add(Element(from = listOf(7.5, 0.0, 7.5), to = listOf(8.5, 21.0, 8.5), faces = poleFaces))
            elements.add(Element(from = listOf(3.0, 20.0, 7.5), to = listOf(13.0, 21.0, 8.5), faces = poleFaces))
            elements.add(Element(from = listOf(3.0, 1.0, 8.5), to = listOf(13.0, 21.0, 9.5), faces = flagFaces))
        }

        return ResolvedModel(elements)
    }
}
