package io.github.moxisuki.blockprint.core.glb.platform

import java.nio.file.Path

/**
 * 平台无关的图像 I/O 抽象，承载 GLB 纹理集的 PNG 读写。
 *
 * 之所以不用 BufferedImage/Bitmap 直传：这两个类在 JVM（`java.awt.*`）和
 * Android（`android.graphics.*`）上分别存在但互不通用；为了让同一份
 * commonMain 代码能在两个平台编译，把图像操作压到 3 个 expect/actual 方法上。
 *
 * 所有方法必须是纯函数：实现不得持有可变全局状态（不同 GLB 转换可能并发跑）。
 */
expect interface ImageBackend {
    /**
     * 读取指定路径的 PNG 并解码为 ARGB 像素数组。
     * - 成功：返回 width/height/argb 三元组
     * - 文件不存在或不是合法 PNG：返回 null
     *
     * 返回的 argb 数组必须是 ARGB_8888 格式（每像素一个 Int，bit 布局见 [ImageData.argb]）。
     * 实现应在内部转换任何非 ARGB 源（如 RGB_565、灰度、调色板 PNG）。
     */
    fun loadPng(path: Path): ImageData?

    /**
     * 把 ARGB 像素数组编码为 PNG 字节流。
     * 实现应使用无损、最高质量设置（PNG 是无损格式，没有质量参数）。
     */
    fun encodePng(data: ImageData): ByteArray
}

/**
 * 平台无关的图像内存表示。
 *
 * @param width  像素宽度
 * @param height 像素高度
 * @param argb   ARGB_8888 像素数组，长度 = width * height，按行主序排列：
 *               `argb[y * width + x]` 是 (x, y) 处的像素
 *
 * 颜色 bit 布局（与 `android.graphics.Color` / Java `BufferedImage.TYPE_INT_ARGB` 一致）：
 * ```
 *   bit 31..24: alpha (0xFF = 不透明)
 *   bit 23..16: red
 *   bit 15.. 8: green
 *   bit  7.. 0: blue
 * ```
 */
data class ImageData(
    val width: Int,
    val height: Int,
    val argb: IntArray,
) {
    init {
        require(argb.size == width * height) {
            "argb.size (${argb.size}) must equal width * height (${width * height})"
        }
    }
    fun getRGB(x: Int, y: Int): Int = argb[y * width + x]
    fun setRGB(x: Int, y: Int, rgb: Int) { argb[y * width + x] = rgb }
}

/**
 * 平台默认的 [ImageBackend] 工厂。
 * - JVM：返回基于 `java.awt.image.BufferedImage` + `javax.imageio.ImageIO` 的实现
 * - Android：返回基于 `android.graphics.Bitmap` + `BitmapFactory` 的实现
 *
 * 调用方一般通过默认参数 [TexturePacker] 的构造函数间接拿到，
 * 无需关心具体类。
 */
expect fun createImageBackend(): ImageBackend
