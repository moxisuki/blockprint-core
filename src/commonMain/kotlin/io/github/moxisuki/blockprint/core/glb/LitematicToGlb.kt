package io.github.moxisuki.blockprint.core.glb

import io.github.moxisuki.blockprint.core.Litematic
import java.io.File
import java.nio.file.Path

object LitematicToGlb {

    fun convert(
        litematic: Litematic,
        assetsDirs: List<Path>,
        outputFile: File,
        regionIndex: Int = 0,
        options: GlbExportOptions = GlbExportOptions(),
        onProgress: ((Float) -> Unit)? = null,
    ) {
        val region = litematic.regions.getOrElse(regionIndex) {
            throw IllegalArgumentException("Region index $regionIndex out of bounds (${litematic.regions.size} regions)")
        }

        onProgress?.invoke(0.05f)
        val modelResolver = ModelResolver(assetsDirs)
        val texturePacker = TexturePacker(assetsDirs)
        onProgress?.invoke(0.20f)
        val meshBuilder = MeshBuilder(modelResolver, texturePacker, enableTinting = options.enableTinting)
        val glbWriter = GlbWriter()

        // Center the model at origin on all axes
        val originX = region.position.x - region.width / 2
        val originY = region.position.y - region.height / 2
        val originZ = region.position.z - region.depth / 2
        val output = meshBuilder.build(region, originX, originY, originZ, options) { p ->
            onProgress?.invoke(0.20f + p * 0.50f)
        }
        onProgress?.invoke(0.70f)
        println("Mesh: ${output.floors.size} floor(s), ${output.floors.sumOf { it.positions.size / 3 }} vertices, atlas ${output.atlasWidth}x${output.atlasHeight}")

        outputFile.outputStream().use { stream ->
            glbWriter.write(output, stream, options)
        }
        onProgress?.invoke(0.95f)
    }

    /** Convert to in-memory bytes via temp file. */
    fun convertToBytes(
        litematic: Litematic,
        assetsDirs: List<Path>,
        regionIndex: Int = 0,
        imageBackend: ImageBackend? = null,
        onProgress: ((Float) -> Unit)? = null,
        options: GlbExportOptions = GlbExportOptions(),
    ): ByteArray {
        val tmpFile = File.createTempFile("glb_", ".glb")
        try {
            convert(litematic, assetsDirs, tmpFile, regionIndex, options, onProgress)
            val bytes = tmpFile.readBytes()
            onProgress?.invoke(1.0f)
            return bytes
        } finally {
            tmpFile.delete()
        }
    }
}
