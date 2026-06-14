package io.github.moxisuki.blockprint.core.glb

import java.nio.file.Files
import java.nio.file.Path

data class PackedAtlas(
    val pngBytes: ByteArray,
    val width: Int,
    val height: Int,
    val mappings: Map<String, AtlasEntry>,
)

data class AtlasEntry(
    val u1: Float, val v1: Float,
    val u2: Float, val v2: Float,
)

/**
 * 把一组 Minecraft 贴图（`minecraft:textures/...` 路径）打包成一张大图（atlas）
 * 并返回 UV 映射。平台无关——所有像素读写通过 [ImageBackend]，由调用方在
 * 构造时注入（或用平台默认的 [createImageBackend]）。
 *
 * 装箱算法：自底向上倍增（16×16 → 32×16 → ... → maxAtlasWidth×16 → ...）。
 * 不是 max-rects、不是分行打包；优点是简单，缺点是对超大图集不够紧凑。
 */
class TexturePacker(
    private val assetsDirs: List<Path>,
    private val maxAtlasWidth: Int = 2048,
    private val backend: ImageBackend = createImageBackend(),
) {
    constructor(assetsDir: Path, maxAtlasWidth: Int = 2048, backend: ImageBackend = createImageBackend())
        : this(listOf(assetsDir), maxAtlasWidth, backend)

    fun pack(
        texturePaths: Set<String>,
        tinted: Map<String, Int> = emptyMap(),
        // 特殊染色：texture 路径 → 直接使用的 RGB 颜色（覆盖 colormap 逻辑）。
        // 用于红石粉这种"vanilla 用专用 BlockColor 而不是 colormap"的方块。
        specialTints: Map<String, Int> = emptyMap(),
    ): PackedAtlas {
        val textures = texturePaths.filter { it.isNotEmpty() }.mapNotNull { path ->
            val raw = readPng(path) ?: return@mapNotNull null
            val rgb = specialTints[path]
            val processed = when {
                rgb != null -> applyRgbTint(raw, rgb)
                tinted[path] != null -> applyTint(raw, tinted[path]!!)
                else -> raw
            }
            path to processed
        }

        if (textures.isEmpty()) {
            val empty = ImageData(16, 16, IntArray(16 * 16))
            return PackedAtlas(backend.encodePng(empty), 16, 16, emptyMap())
        }

        // 按面积降序放，大图先落位
        val sorted = textures.sortedByDescending { (it.second.width) * (it.second.height) }

        var atlasWidth = 16
        var atlasHeight = 16
        val placements = mutableMapOf<String, Placement>()

        for ((name, img) in sorted) {
            val placed = tryPlace(atlasWidth, atlasHeight, img, placements, name)
            if (placed != null) {
                placements[name] = placed
            } else {
                val expanded = expandAndPlace(sorted, placements, atlasWidth, atlasHeight, maxAtlasWidth)
                if (expanded != null) {
                    atlasWidth = expanded.first
                    atlasHeight = expanded.second
                    placements.clear()
                    placements.putAll(expanded.third)
                }
            }
        }

        // 装配 atlas：透明清零 + 逐张贴片拷贝像素
        val atlas = ImageData(atlasWidth, atlasHeight, IntArray(atlasWidth * atlasHeight))
        for ((name, place) in placements) {
            val src = textures.first { it.first == name }.second
            for (y in 0 until src.height) {
                val srcStart = y * src.width
                val dstStart = (place.y + y) * atlasWidth + place.x
                // System.arraycopy 对 IntArray 是 native 拷贝，比 Kotlin 的 for 循环快得多
                System.arraycopy(src.argb, srcStart, atlas.argb, dstStart, src.width)
            }
        }

        val pngBytes = backend.encodePng(atlas)

        val mappings = placements.mapValues { (_, p) ->
            AtlasEntry(
                u1 = p.x.toFloat() / atlasWidth,
                v1 = p.y.toFloat() / atlasHeight,
                u2 = (p.x + p.w).toFloat() / atlasWidth,
                v2 = (p.y + p.h).toFloat() / atlasHeight,
            )
        }

        return PackedAtlas(pngBytes, atlasWidth, atlasHeight, mappings)
    }

    private fun tryPlace(
        aw: Int, ah: Int, img: ImageData,
        existing: Map<String, Placement>, name: String,
    ): Placement? {
        val iw = img.width; val ih = img.height
        for (y in 0..ah - ih step 2) {
            for (x in 0..aw - iw step 2) {
                if (!overlaps(x, y, iw, ih, existing)) {
                    return Placement(x, y, iw, ih)
                }
            }
        }
        return null
    }

    private fun expandAndPlace(
        sorted: List<Pair<String, ImageData>>,
        existing: Map<String, Placement>,
        aw: Int, ah: Int, maxW: Int,
    ): Triple<Int, Int, Map<String, Placement>>? {
        var nw = aw; var nh = ah
        val all = mutableMapOf<String, Placement>()
        all.putAll(existing)
        var placedAny = false

        for ((name, img) in sorted) {
            if (name in all) continue
            var found = tryPlace(nw, nh, img, all, name)
            while (found == null && nw < maxW) {
                nw = minOf(nw * 2, maxW)
                found = tryPlace(nw, nh, img, all, name)
            }
            while (found == null) {
                nh *= 2
                found = tryPlace(nw, nh, img, all, name)
            }
            all[name] = found
            placedAny = true
        }

        return if (placedAny) Triple(nw, nh, all) else null
    }

    private fun overlaps(x: Int, y: Int, w: Int, h: Int, existing: Map<String, Placement>): Boolean {
        for (p in existing.values) {
            if (x < p.x + p.w && x + w > p.x && y < p.y + p.h && y + h > p.y) return true
        }
        return false
    }

    private fun applyTint(img: ImageData, tintIndex: Int): ImageData {
        val colormapFile = if (tintIndex == 0) "minecraft/textures/colormap/grass.png"
                          else "minecraft/textures/colormap/foliage.png"
        val tint = loadAcrossDirs(colormapFile)?.let { cmap ->
            cmap.argb[cmap.height / 2 * cmap.width + cmap.width / 2]
        } ?: 0xFF7CBD6B.toInt()

        return applyRgbTint(img, tint)
    }

    /**
     * 直接用给定的 RGB 颜色把灰度纹理染色（乘法，不是替换）。vanilla 红石粉就是用
     * 自己的 BlockColor 直接乘到灰度 dust 纹理上得到红色；本库没有 biome 数据时退化
     * 成单一 mid-range 红色。
     */
    private fun applyRgbTint(img: ImageData, rgb: Int): ImageData {
        val r = ((rgb shr 16) and 0xFF) / 255f
        val g = ((rgb shr 8) and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f

        val result = IntArray(img.width * img.height)
        for (i in img.argb.indices) {
            val pixel = img.argb[i]
            val a = (pixel shr 24) and 0xFF
            val pr = ((pixel shr 16) and 0xFF) / 255f
            val pg = ((pixel shr 8) and 0xFF) / 255f
            val pb = (pixel and 0xFF) / 255f
            val tr = (pr * r * 255).toInt().coerceIn(0, 255)
            val tg = (pg * g * 255).toInt().coerceIn(0, 255)
            val tb = (pb * b * 255).toInt().coerceIn(0, 255)
            result[i] = (a shl 24) or (tr shl 16) or (tg shl 8) or tb
        }
        return ImageData(img.width, img.height, result)
    }

    /**
     * 把 Minecraft 贴图路径（`minecraft:block/stone` 或 `minecraft:textures/block/stone`）
     * 解析为 `assets/minecraft/textures/block/stone.png` 形式，并通过 [backend] 读取。
     *
     * 高度 >16 的纹理（如水/熔岩的 16×512 动画图）按 vanilla 1.13 之前的行为裁剪到
     * 前 16 行——这些方块靠特殊渲染器取后面的帧，我们这里只画静态外观。
     */
    private fun loadAcrossDirs(relPath: String): ImageData? {
        for (dir in assetsDirs) {
            val data = backend.loadPng(dir.resolve(relPath))
            if (data != null) return data
        }
        return null
    }

    private fun readPng(texturePath: String): ImageData? {
        val parts = texturePath.split(":")
        val relPath = if (parts.size == 2) "${parts[0]}/${parts[1]}.png" else "minecraft/$texturePath.png"
        var data = loadAcrossDirs(relPath)
        // Banner 16 色：统一 fallback 到 base.png
        if (data == null && texturePath.contains("entity/banner/base_")) {
            data = loadAcrossDirs("minecraft/textures/entity/banner/base.png")
        }
        if (data == null) return null

        if (data.height > 16 && data.width == 16) {
            // 裁剪到前 16 行
            val cropped = IntArray(16 * 16)
            for (y in 0 until 16) {
                System.arraycopy(data.argb, y * data.width, cropped, y * 16, 16)
            }
            return ImageData(16, 16, cropped)
        }
        return data
    }

    private data class Placement(val x: Int, val y: Int, val w: Int, val h: Int)
}
