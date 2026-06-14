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
        enableTinting: Boolean = true,
    ) {
        val region = litematic.regions.getOrElse(regionIndex) {
            throw IllegalArgumentException("Region index $regionIndex out of bounds (${litematic.regions.size} regions)")
        }

        val modelResolver = ModelResolver(assetsDirs)
        val texturePacker = TexturePacker(assetsDirs)
        val meshBuilder = MeshBuilder(modelResolver, texturePacker, enableTinting = enableTinting)
        val glbWriter = GlbWriter()

        // Center the model at origin on all axes
        val originX = region.position.x - region.width / 2
        val originY = region.position.y - region.height / 2
        val originZ = region.position.z - region.depth / 2
        val output = meshBuilder.build(region, originX, originY, originZ)
        println("Mesh: ${output.positions.size / 3} vertices, ${output.indices.size / 3} triangles, atlas ${output.atlasWidth}x${output.atlasHeight}")

        outputFile.outputStream().use { stream ->
            glbWriter.write(output, stream)
        }
    }

    /** Convert to in-memory bytes via temp file. */
    fun convertToBytes(
        litematic: Litematic,
        assetsDirs: List<Path>,
        regionIndex: Int = 0,
        imageBackend: ImageBackend? = null,
        onProgress: ((Float) -> Unit)? = null,
    ): ByteArray {
        onProgress?.invoke(0.05f)
        val tmpFile = File.createTempFile("glb_", ".glb")
        try {
            convert(litematic, assetsDirs, tmpFile, regionIndex, enableTinting = true)
            onProgress?.invoke(0.95f)
            val bytes = tmpFile.readBytes()
            onProgress?.invoke(1.0f)
            return bytes
        } finally {
            tmpFile.delete()
        }
    }
}
