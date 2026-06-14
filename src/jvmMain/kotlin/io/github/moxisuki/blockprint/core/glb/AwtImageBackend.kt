package io.github.moxisuki.blockprint.core.glb

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * JVM/desktop 端的 [ImageBackend] 镜像。expect/actual 要求每个目标平台
 * 都有一个 `actual` 声明；方法在 [AwtImageBackend] 里实现，这里只标记契约。
 */
actual interface ImageBackend {
    actual fun loadPng(path: Path): ImageData?
    actual fun encodePng(data: ImageData): ByteArray
}

/**
 * JVM/desktop 端的 [ImageBackend] 实现，基于 `java.awt.image.BufferedImage` +
 * `javax.imageio.ImageIO`。这是 Core 库原生的（也是唯一）实现，所有 jvmMain
 * 测试都跑在这上面。
 */
class AwtImageBackend : ImageBackend {

    override fun loadPng(path: Path): ImageData? {
        if (!java.nio.file.Files.isRegularFile(path)) return null
        val img = ImageIO.read(path.toFile()) ?: return null
        val w = img.width; val h = img.height
        // 强制转 ARGB（ImageIO 读出来的可能是 RGB/灰度/调色板等格式）
        val argb = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).apply {
            createGraphics().apply {
                drawImage(img, 0, 0, null)
                dispose()
            }
        }.getRGB(0, 0, w, h, null, 0, w)
        return ImageData(w, h, argb)
    }

    override fun encodePng(data: ImageData): ByteArray {
        val img = BufferedImage(data.width, data.height, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, data.width, data.height, data.argb, 0, data.width)
        val baos = ByteArrayOutputStream()
        ImageIO.write(img, "PNG", baos)
        return baos.toByteArray()
    }
}

actual fun createImageBackend(): ImageBackend = AwtImageBackend()
