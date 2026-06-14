package io.github.moxisuki.blockprint.core.glb

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.nio.file.Path

/**
 * Android 端的 [ImageBackend] 镜像。方法在 [BitmapImageBackend] 里实现，
 * 这里只标记契约。
 */
actual interface ImageBackend {
    actual fun loadPng(path: Path): ImageData?
    actual fun encodePng(data: ImageData): ByteArray
}

/**
 * Android 端的 [ImageBackend] 实现，基于 `android.graphics.Bitmap` +
 * `BitmapFactory`。与 jvmMain 的 [AwtImageBackend] 行为对齐：
 * - `loadPng` 总是返回 ARGB_8888 的 [ImageData]
 * - `encodePng` 用 PNG（无损）压缩
 *
 * 注意事项：
 * - 大贴图（>4096×4096 或单边超出 Bitmap 上限）会被 BitmapFactory 拒绝。
 *   这与 desktop 行为一致：超大贴图本就该被拆成多张或降低分辨率。
 * - 旧版 Android（API 21）`Bitmap.Config.ARGB_8888` + `compress(PNG)` 都支持，
 *   无需版本分支。
 */
class BitmapImageBackend : ImageBackend {

    override fun loadPng(path: Path): ImageData? {
        if (!java.nio.file.Files.isRegularFile(path)) return null
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp: Bitmap = BitmapFactory.decodeFile(path.toAbsolutePath().toString(), opts)
            ?: return null
        val w = bmp.width; val h = bmp.height
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        bmp.recycle()
        return ImageData(w, h, argb)
    }

    override fun encodePng(data: ImageData): ByteArray {
        val bmp = Bitmap.createBitmap(data.width, data.height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(data.argb, 0, data.width, 0, 0, data.width, data.height)
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, /* quality = */ 100, baos)
        bmp.recycle()
        return baos.toByteArray()
    }
}

actual fun createImageBackend(): ImageBackend = BitmapImageBackend()
