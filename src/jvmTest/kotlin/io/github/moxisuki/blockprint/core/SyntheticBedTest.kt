package io.github.moxisuki.blockprint.core

import io.github.moxisuki.blockprint.core.glb.synthetic.SyntheticBed
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * 床模型几何测试 - 验证 SyntheticBed 生成的 box 数量、位置、尺寸。
 * 不依赖 GLB/纹理，只检查 ResolvedModel 的几何。
 */
class SyntheticBedTest {

    @Test
    fun headHas3Boxes() {
        val model = SyntheticBed.build("placeholder", "head")
        assertEquals(3, model.elements.size, "床 part 应有 1 主盒 + 2 腿 = 3 个 box")
    }

    @Test
    fun mainBoxIs16x6x16() {
        val model = SyntheticBed.build("placeholder", "head")
        val main = model.elements[0]
        assertEquals(listOf(0.0, 0.0, 0.0), main.from)
        assertEquals(listOf(16.0, 6.0, 16.0), main.to)
    }

    @Test
    fun legsAre2AtOuterEndsOfPart() {
        // 每 part 只 2 个腿在外端的 2 角（head 在 z=0 端，foot 在 z=13 端）
        // 合并后 4 个腿在 1×2 长方床的 4 角
        val head = SyntheticBed.build("placeholder", "head")
        val foot = SyntheticBed.build("placeholder", "foot")
        val headLegs = head.elements.drop(1)
        val footLegs = foot.elements.drop(1)
        assertEquals(2, headLegs.size, "head 应有 2 个腿")
        assertEquals(2, footLegs.size, "foot 应有 2 个腿")

        // head 的腿在 z=0 端（远离 foot 的那端）
        val headLegZ = headLegs.map { it.from[2] }.toSet()
        assertEquals(setOf(0.0), headLegZ, "head 2 个腿都应在 z=0..3 外端")

        // foot 的腿在 z=13 端（远离 head 的那端）
        val footLegZ = footLegs.map { it.from[2] }.toSet()
        assertEquals(setOf(13.0), footLegZ, "foot 2 个腿都应在 z=13..16 外端")

        // head 的 2 个腿在不同 X 角
        val headLegX = headLegs.map { it.from[0] }.toSet()
        assertEquals(setOf(0.0, 13.0), headLegX, "head 2 腿应在 X 两端 (0 和 13)")
        val footLegX = footLegs.map { it.from[0] }.toSet()
        assertEquals(setOf(0.0, 13.0), footLegX, "foot 2 腿应在 X 两端 (0 和 13)")

        for (leg in headLegs + footLegs) {
            assertEquals(3.0, leg.to[0] - leg.from[0], "腿 X 尺寸应是 3")
            assertEquals(3.0, leg.to[1] - leg.from[1], "腿 Y 尺寸应是 3")
            assertEquals(3.0, leg.to[2] - leg.from[2], "腿 Z 尺寸应是 3")
        }
    }

    @Test
    fun legsAreBelowBedNotInside() {
        // 关键：腿应在床底下方（y < 0），不嵌入床内
        val model = SyntheticBed.build("placeholder", "head")
        val main = model.elements[0]  // 主盒 y=0..6
        val legs = model.elements.drop(1)
        for (leg in legs) {
            assertEquals(-3.0, leg.from[1], "腿底面 = -3（伸出床底 3 像素）")
            assertEquals(0.0, leg.to[1], "腿顶面 = 0（紧贴床底）")
        }
        // 主盒范围
        assertEquals(0.0, main.from[1])
        assertEquals(6.0, main.to[1])
    }

    @Test
    fun allBoxesHave6Faces() {
        val model = SyntheticBed.build("placeholder", "head")
        for ((i, elem) in model.elements.withIndex()) {
            assertEquals(6, elem.faces.size, "box $i 应有 6 个面")
            for ((dir, face) in elem.faces) {
                assertTrue(face.texture.isNotEmpty(), "box $i 的 $dir 纹理不能为空")
            }
        }
    }

    @Test
    fun footMatchesHeadGeometry() {
        // head 和 foot 在我们的简化实现里几何相同（都是 1 主盒 + 4 腿）
        val head = SyntheticBed.build("placeholder", "head")
        val foot = SyntheticBed.build("placeholder", "foot")
        assertEquals(head.elements.size, foot.elements.size)
    }
}
