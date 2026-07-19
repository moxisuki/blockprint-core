package io.github.moxisuki.blockprint.core.glb.synthetic

import io.github.moxisuki.blockprint.core.glb.model.Element
import io.github.moxisuki.blockprint.core.glb.model.Face
import io.github.moxisuki.blockprint.core.glb.model.ResolvedModel

/**
 * vanilla 1.20+ 把 sign 类方块（oak_sign、birch_wall_sign、acacia_hanging_sign…）的
 * 模型清空，由 `SignRenderer` / `HangingSignRenderer` 接管（带链子、文本浮雕等）。
 * 我们没有文本浮雕渲染能力，只画静态几何。
 *
 * 三种形态：
 * - standing（地面立牌）：木牌面 + 中央支柱
 * - wall（贴墙牌）：贴附在 north 面的木牌面，由 blockstate facing 旋转到墙面
 * - hanging（悬挂牌）：木牌面 + 两条吊链
 * - wall hanging（墙悬挂牌）：贴墙木牌面 + 两个短挂架
 */
object SyntheticSign {

    /** 地面立牌：中心木牌面 + 细支柱。 */
    fun buildStanding(texPath: String): ResolvedModel {
        val tex = fullTexturePath(texPath)
        return ResolvedModel(
            listOf(
                cuboid(tex, from = listOf(2.0, 7.0, 7.0), to = listOf(14.0, 15.0, 9.0)),
                cuboid(tex, from = listOf(7.0, 0.0, 7.25), to = listOf(9.0, 7.0, 8.75)),
            ),
        )
    }

    /** 贴墙牌：默认朝 north，背面贴在 south 边界；调用方根据 facing 旋转。 */
    fun buildWall(texPath: String): ResolvedModel {
        val tex = fullTexturePath(texPath)
        return ResolvedModel(
            listOf(
                cuboid(tex, from = listOf(2.0, 4.0, 14.0), to = listOf(14.0, 12.0, 16.0)),
            ),
        )
    }

    /** 天花板悬挂牌：木牌面 + 双链。 */
    fun buildHanging(texPath: String): ResolvedModel {
        val tex = fullTexturePath(texPath)
        return ResolvedModel(
            listOf(
                cuboid(tex, from = listOf(2.0, 4.0, 7.0), to = listOf(14.0, 12.0, 9.0)),
                cuboid(tex, from = listOf(3.0, 12.0, 7.25), to = listOf(4.0, 16.0, 8.75)),
                cuboid(tex, from = listOf(12.0, 12.0, 7.25), to = listOf(13.0, 16.0, 8.75)),
            ),
        )
    }

    /** 墙悬挂牌：默认朝 north，背面贴在 south 边界，带两个短挂架。 */
    fun buildWallHanging(texPath: String): ResolvedModel {
        val tex = fullTexturePath(texPath)
        return ResolvedModel(
            listOf(
                cuboid(tex, from = listOf(2.0, 4.0, 14.0), to = listOf(14.0, 12.0, 16.0)),
                cuboid(tex, from = listOf(3.0, 12.0, 14.25), to = listOf(4.0, 15.0, 15.75)),
                cuboid(tex, from = listOf(12.0, 12.0, 14.25), to = listOf(13.0, 15.0, 15.75)),
                cuboid(tex, from = listOf(3.0, 14.0, 11.0), to = listOf(4.0, 15.0, 16.0)),
                cuboid(tex, from = listOf(12.0, 14.0, 11.0), to = listOf(13.0, 15.0, 16.0)),
            ),
        )
    }

    private fun fullTexturePath(tex: String): String {
        // The face texture path must be the FULL texture path including
        // the `textures/` segment, otherwise TexturePacker.readPng builds
        // a wrong file path (e.g. `minecraft/entity/...` instead of
        // `minecraft/textures/entity/...`) and returns null, the texture
        // never makes it into the atlas, and every face with that texture
        // is silently dropped — leaving the icon with empty meshes.
        return if (tex.contains("/textures/")) tex
        else if (':' in tex) tex.substringBefore(':') + ":textures/" + tex.substringAfter(':')
        else "minecraft:textures/$tex"
    }

    private fun cuboid(tex: String, from: List<Double>, to: List<Double>): Element {
        val w = to[0] - from[0]
        val h = to[1] - from[1]
        val d = to[2] - from[2]
        val faces = mapOf(
            "down" to Face(tex, listOf(0.0, 0.0, w, d), "down", 0),
            "up" to Face(tex, listOf(0.0, 0.0, w, d), "up", 0),
            "north" to Face(tex, listOf(0.0, 0.0, w, h), "north", 0),
            "south" to Face(tex, listOf(0.0, 0.0, w, h), "south", 0),
            "west" to Face(tex, listOf(0.0, 0.0, d, h), "west", 0),
            "east" to Face(tex, listOf(0.0, 0.0, d, h), "east", 0),
        )
        return Element(from = from, to = to, faces = faces, rotation = null)
    }
}
