package io.github.moxisuki.blockprint.core.glb.synthetic

import io.github.moxisuki.blockprint.core.glb.model.Element
import io.github.moxisuki.blockprint.core.glb.model.Face
import io.github.moxisuki.blockprint.core.glb.model.ResolvedModel

/**
 * Vanilla 1.13+ 把 `dragon_head` / `dragon_wall_head` 的模型清空，
 * 让 `DragonHeadBlockEntityRenderer` 接管（参考 MCP `DragonHeadModel.java`）。
 * 我们没有运行时实体渲染器，所以这里硬编码一份"够用"的近似几何。
 */
object SyntheticEnderDragonHead {

    fun texNameFor(name: String): String? = when (name) {
        "dragon_head", "dragon_wall_head" -> "entity/enderdragon/dragon"
        else -> null
    }

    fun isWall(name: String): Boolean = name.contains("_wall_")

    fun build(texPath: String, isWall: Boolean): ResolvedModel {
        val tex = if (':' in texPath) texPath else "minecraft:$texPath"

        // Helper to convert pixel UV coordinates to 16.0 block-model UV coordinates
        // dragon.png is 256x256, so: uv_block = pixel * 16.0 / 256.0 = pixel / 16.0
        fun uv(u: Double, v: Double, w: Double, h: Double): List<Double> =
            listOf(u / 16.0, v / 16.0, (u + w) / 16.0, (v + h) / 16.0)

        // Minecraft standard box UV mapper
        fun boxFaces(
            u: Double, v: Double,
            dx: Double, dy: Double, dz: Double,
            mirror: Boolean
        ): Map<String, Face> {
            return if (!mirror) {
                mapOf(
                    "up"    to Face(tex, uv(u + dz, v, dx, dz), null, 0),
                    "down"  to Face(tex, uv(u + dz + dx, v, dx, dz), null, 0),
                    "east"  to Face(tex, uv(u, v + dz, dz, dy), null, 0),
                    "north" to Face(tex, uv(u + dz, v + dz, dx, dy), null, 0),
                    "west"  to Face(tex, uv(u + dz + dx, v + dz, dz, dy), null, 0),
                    "south" to Face(tex, uv(u + 2 * dz + dx, v + dz, dx, dy), null, 0),
                )
            } else {
                mapOf(
                    "up"    to Face(tex, uv(u + dz + dx, v, -dx, dz), null, 0),
                    "down"  to Face(tex, uv(u + 2 * dz + dx, v, -dx, dz), null, 0),
                    "east"  to Face(tex, uv(u + dz, v + dz, -dz, dy), null, 0),
                    "north" to Face(tex, uv(u + dz + dx, v + dz, -dx, dy), null, 0),
                    "west"  to Face(tex, uv(u + 2 * dz, v + dz, -dz, dy), null, 0),
                    "south" to Face(tex, uv(u + 2 * dz + 2 * dx, v + dz, -dx, dy), null, 0),
                )
            }
        }

        // wall variant Z shift
        // Ground variant's back of head (upper_head max Z) is at 12.5.
        // Wall variant needs the back of the head to touch the wall (Z = 16.0).
        // So the shift is 16.0 - 12.5 = 3.5.
        val zShift = if (isWall) 3.5 else 0.0

        fun box(
            fromX: Double, fromY: Double, fromZ: Double,
            toX: Double, toY: Double, toZ: Double,
            u: Double, v: Double,
            dx: Double, dy: Double, dz: Double,
            mirror: Boolean
        ): Element {
            return Element(
                from = listOf(fromX, fromY, fromZ + zShift),
                to = listOf(toX, toY, toZ + zShift),
                faces = boxFaces(u, v, dx, dy, dz, mirror),
                rotation = null,
            )
        }

        return ResolvedModel(listOf(
            // upper_head: (-8.0F, -8.0F, -10.0F, 16, 16, 16, 112, 30)
            // Block space coordinates: from=[2.0, 1.986666, 0.5], to=[14.0, 13.986666, 12.5]
            box(2.0, 1.986666, 0.5,  14.0, 13.986666, 12.5,  112.0, 30.0, 16.0, 16.0, 16.0, false),

            // upper_lip: (-6.0F, -1.0F, -24.0F, 12, 5, 16, 176, 44)
            // Block space coordinates: from=[3.5, 4.986666, -10.0], to=[12.5, 8.736666, 2.0]
            box(3.5, 4.986666, -10.0, 12.5, 8.736666, 2.0,  176.0, 44.0, 12.0, 5.0, 16.0, false),

            // jaw: (-6.0F, 0.0F, -16.0F, 12.0F, 4.0F, 16.0F, 176, 65)
            // Block space coordinates: from=[3.5, 1.986666, -10.0], to=[12.5, 4.986666, 2.0]
            box(3.5, 1.986666, -10.0, 12.5, 4.986666, 2.0,  176.0, 65.0, 12.0, 4.0, 16.0, false),

            // scale L: (-5.0F, -12.0F, -4.0F, 2, 4, 6, 0, 0) - mirror=true
            // Block space coordinates: from=[10.25, 13.986666, 5.0], to=[11.75, 16.986666, 9.5]
            box(10.25, 13.986666, 5.0, 11.75, 16.986666, 9.5,  0.0, 0.0, 2.0, 4.0, 6.0, true),

            // scale R: (3.0F, -12.0F, -4.0F, 2, 4, 6, 0, 0) - mirror=false
            // Block space coordinates: from=[4.25, 13.986666, 5.0], to=[5.75, 16.986666, 9.5]
            box(4.25, 13.986666, 5.0, 5.75, 16.986666, 9.5,  0.0, 0.0, 2.0, 4.0, 6.0, false),

            // nostril L: (-5.0F, -3.0F, -22.0F, 2, 2, 4, 112, 0) - mirror=true
            // Block space coordinates: from=[10.25, 8.736666, -8.5], to=[11.75, 10.236666, -5.5]
            box(10.25, 8.736666, -8.5, 11.75, 10.236666, -5.5,  112.0, 0.0, 2.0, 2.0, 4.0, true),

            // nostril R: (3.0F, -3.0F, -22.0F, 2, 2, 4, 112, 0) - mirror=false
            // Block space coordinates: from=[4.25, 8.736666, -8.5], to=[5.75, 10.236666, -5.5]
            box(4.25, 8.736666, -8.5, 5.75, 10.236666, -5.5,  112.0, 0.0, 2.0, 2.0, 4.0, false),
        ))
    }
}
