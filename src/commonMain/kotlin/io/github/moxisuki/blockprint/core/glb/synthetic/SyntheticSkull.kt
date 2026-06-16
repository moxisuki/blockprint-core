package io.github.moxisuki.blockprint.core.glb.synthetic

import io.github.moxisuki.blockprint.core.glb.Element
import io.github.moxisuki.blockprint.core.glb.Face
import io.github.moxisuki.blockprint.core.glb.ResolvedModel

/**
 * vanilla 1.13+ 把 skull/head 类方块（14 变体：7 种类型 × 2 种放置）的模型清空，
 * 让 `SkullBlockEntityRenderer` 接管（带旋转动画、不同生物头骨贴图等）。
 * 我们没有运行时实体渲染器，所以这里硬编码一份"够用"的近似几何：参考 MCP `SkullModel.java`。
 *
 * 真实几何（参考 MCP SkullModel.createHeadModel）：
 *   - head: (-4, -8, -4) → (4, 0, 4) = 8×8×8 头骨，中心在 (0, -4, 0)
 *
 * 14 个变体 + 各自动的纹理尺寸：
 *   - mob 类（skeleton/creeper/zombie/piglin/wither_skeleton）：纹理 64×32
 *   - player：纹理 64×64
 *   - dragon：纹理 256×256
 */
object SyntheticSkull {

    /** 块名 → 纹理子路径。null 表示不是 skull。 */
    fun texNameFor(name: String): String? = when (name) {
        "skeleton_skull"      -> "skeleton/skeleton"
        "skeleton_wall_skull"-> "skeleton/skeleton"
        "wither_skeleton_skull"      -> "skeleton/wither_skeleton"
        "wither_skeleton_wall_skull"-> "skeleton/wither_skeleton"
        "zombie_head"        -> "zombie/zombie"
        "zombie_wall_head"  -> "zombie/zombie"
        "creeper_head"       -> "creeper/creeper"
        "creeper_wall_head"  -> "creeper/creeper"
        "piglin_head"        -> "piglin/piglin"
        "piglin_wall_head"   -> "piglin/piglin"
        "player_head"        -> "player/wide/steve"
        "player_wall_head"   -> "player/wide/steve"
        else -> null
    }

    /** 是否是 wall 变体（贴墙的薄形） */
    fun isWall(name: String): Boolean = name.contains("_wall_")

    /**
     * @param texPath `minecraft:textures/entity/<...>` 形式的纹理路径。
     * 几何（参考 MCP SkullModel）：单个 8×8×8 box，居中在方块内
     */
    fun build(texPath: String, isWall: Boolean): ResolvedModel {
        val tex = if (':' in texPath) texPath else "minecraft:textures/$texPath"
        // 完整 16-px 空间 = 整张纹理（不管 64×32 / 64×64 / 256×256 都能覆盖）
        val fullUV = listOf(0.0, 0.0, 16.0, 16.0)
        val faces = mapOf(
            "down"  to Face(tex, fullUV, null, 0),
            "up"    to Face(tex, fullUV, null, 0),
            "north" to Face(tex, fullUV, null, 0),
            "south" to Face(tex, fullUV, null, 0),
            "east"  to Face(tex, fullUV, null, 0),
            "west"  to Face(tex, fullUV, null, 0),
        )
        return if (isWall) {
            // wall 变体：贴墙 8×8×4 box
            ResolvedModel(listOf(
                Element(
                    from = listOf(4.0, 4.0, 12.0),
                    to = listOf(12.0, 12.0, 16.0),
                    faces = faces,
                    rotation = null,
                )
            ))
        } else {
            // ground 变体：8×8×8 box，居中方块
            ResolvedModel(listOf(
                Element(
                    from = listOf(4.0, 4.0, 4.0),
                    to = listOf(12.0, 12.0, 12.0),
                    faces = faces,
                    rotation = null,
                )
            ))
        }
    }
}
