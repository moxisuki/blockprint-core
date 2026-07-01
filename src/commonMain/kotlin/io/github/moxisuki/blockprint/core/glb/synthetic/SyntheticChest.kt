package io.github.moxisuki.blockprint.core.glb.synthetic

import io.github.moxisuki.blockprint.core.glb.model.Element
import io.github.moxisuki.blockprint.core.glb.model.Face
import io.github.moxisuki.blockprint.core.glb.model.ResolvedModel

/**
 * vanilla 1.13+ 把 chest 类方块的模型文件清空，让 `ChestRenderer` 接管。
 * 这里硬编码近似几何：14×14×14 箱体 + 5px 盖子 + 2×4 锁。
 *
 * UV 按 1.13+ 纹理实际布局（实测像素密度）：
 *   (16, 0)-(32,16) 100% → 盖子主面
 *   (16,16)-(32,32) 100% → 箱体主面
 *   (32, 0)-(48,16)  67% → 盖子侧
 *   (32,16)-(48,32)  70% → 箱体侧
 *   ( 0,32)-(16,48)  63% → 顶/底面
 *   (16,32)-(32,48)  69% → 底面
 */
object SyntheticChest {

    fun texNameFor(name: String): String? = when (name) {
        "chest" -> "normal"
        "trapped_chest" -> "trapped"
        "ender_chest" -> "ender"
        "copper_chest" -> "copper"
        "exposed_copper_chest" -> "copper_exposed"
        "weathered_copper_chest" -> "copper_weathered"
        "oxidized_copper_chest" -> "copper_oxidized"
        else -> null
    }

    fun build(texPath: String): ResolvedModel {
        val tex = if (':' in texPath) texPath else "minecraft:textures/$texPath"
        val uv = { u: Double, v: Double, w: Double, h: Double ->
            listOf(u * 16 / 64, v * 16 / 64, (u + w) * 16 / 64, (v + h) * 16 / 64)
        }

        // MCP ChestModel texOffs + addBox → 按 entity model UV 公式计算每面位移
        // 公式 for addBox(dx,dy,dz) with texOffs(U,V):
        //   west(U, V+dz, dz,dy) east(U+dz, V+dz, dz,dy)
        //   north(U+2*dz, V+dz, dx,dy) south(U+2*dz+dx, V+dz, dx,dy)
        //   down(U+dz, V, dx,dz) up(U+dz+dx, V, dx,dz)

        val elements = mutableListOf<Element>()

        // 底箱 texOffs(0,19) addBox(1,0,1, 14,10,14): dx=14 dy=10 dz=14
        // 去掉 "up" 面（Y=10 处与盖子底共面会闪烁）
        elements.add(Element(listOf(1.0, 0.0, 1.0), listOf(15.0, 10.0, 15.0), mapOf(
            "west"  to Face(tex, uv( 0.0, 33.0, 14.0, 10.0), null, 0),
            "east"  to Face(tex, uv(14.0, 33.0, 14.0, 10.0), null, 0),
            "north" to Face(tex, uv(28.0, 33.0, 14.0, 10.0), null, 0),
            "south" to Face(tex, uv(42.0, 33.0, 14.0, 10.0), null, 0),
            "down"  to Face(tex, uv(14.0, 19.0, 14.0, 14.0), null, 0),
        ), null))

        // 盖子 texOffs(0,0) addBox(1,0,0, 14,5,14): dx=14 dy=5 dz=14
        // 去掉 "down" 面（Y=10 处与底箱顶共面会闪烁）
        elements.add(Element(listOf(1.0, 10.0, 1.0), listOf(15.0, 15.0, 15.0), mapOf(
            "west"  to Face(tex, uv( 0.0, 14.0, 14.0,  5.0), null, 0),
            "east"  to Face(tex, uv(14.0, 14.0, 14.0,  5.0), null, 0),
            "north" to Face(tex, uv(28.0, 14.0, 14.0,  5.0), null, 0),
            "south" to Face(tex, uv(42.0, 14.0, 14.0,  5.0), null, 0),
            "up"    to Face(tex, uv(28.0,  0.0, 14.0, 14.0), null, 0),
        ), null))

        // 锁 texOffs(0,0) addBox(7,-2,14, 2,4,1): dx=2 dy=4 dz=1
        // Z=15..16 突出于盖子前表面，去掉 north（贴盖子面不可见）
        elements.add(Element(listOf(7.0, 8.0, 15.0), listOf(9.0, 12.0, 16.0), mapOf(
            "west"  to Face(tex, uv(0.0, 1.0, 1.0, 4.0), null, 0),
            "east"  to Face(tex, uv(1.0, 1.0, 1.0, 4.0), null, 0),
            "south" to Face(tex, uv(4.0, 1.0, 2.0, 4.0), null, 0),
            "down"  to Face(tex, uv(1.0, 0.0, 2.0, 1.0), null, 0),
            "up"    to Face(tex, uv(3.0, 0.0, 2.0, 1.0), null, 0),
        ), null))

        return ResolvedModel(elements)
    }
}
