package io.github.moxisuki.blockprint.core.glb.mesh

import io.github.moxisuki.blockprint.core.glb.platform.OffHeapBuf

/**
 * Pre-computed size counts for a region's floors, used to lay out the GLB
 * BIN chunk before any vertex data is produced.
 *
 * Built by [MeshBuilder.countFloorStats] in Pass 1 (no vertex allocation),
 * consumed by [GlbWriter.writeStreaming] to size the BIN chunk.
 */
internal data class FloorStats(
    val floorCount: Int,
    val perFloorVertices: IntArray,   // size = floorCount
    val perFloorIndices: IntArray,     // size = floorCount
    val totalPositions: Int,
    val totalNormals: Int,
    val totalUvs: Int,
    val totalIndices: Int,
    val minX: Float,
    val minY: Float,
    val minZ: Float,
    val maxX: Float,
    val maxY: Float,
    val maxZ: Float,
) {
    init {
        require(floorCount >= 0) { "floorCount must be non-negative, got $floorCount" }
        require(perFloorVertices.size == floorCount) {
            "perFloorVertices.size (${perFloorVertices.size}) must equal floorCount ($floorCount)"
        }
        require(perFloorIndices.size == floorCount) {
            "perFloorIndices.size (${perFloorIndices.size}) must equal floorCount ($floorCount)"
        }
        require(totalPositions >= 0 && totalUvs >= 0 && totalIndices >= 0) {
            "totals must be non-negative, got pos=$totalPositions uv=$totalUvs idx=$totalIndices"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FloorStats) return false
        return floorCount == other.floorCount &&
            perFloorVertices.contentEquals(other.perFloorVertices) &&
            perFloorIndices.contentEquals(other.perFloorIndices) &&
            totalPositions == other.totalPositions &&
            totalNormals == other.totalNormals &&
            totalUvs == other.totalUvs &&
            totalIndices == other.totalIndices &&
            minX == other.minX && minY == other.minY && minZ == other.minZ &&
            maxX == other.maxX && maxY == other.maxY && maxZ == other.maxZ
    }

    override fun hashCode(): Int {
        var result = floorCount
        result = 31 * result + perFloorVertices.contentHashCode()
        result = 31 * result + perFloorIndices.contentHashCode()
        result = 31 * result + totalPositions
        result = 31 * result + totalNormals
        result = 31 * result + totalUvs
        result = 31 * result + totalIndices
        result = 31 * result + minX.hashCode()
        result = 31 * result + minY.hashCode()
        result = 31 * result + minZ.hashCode()
        result = 31 * result + maxX.hashCode()
        result = 31 * result + maxY.hashCode()
        result = 31 * result + maxZ.hashCode()
        return result
    }
}

/**
 * Consumes one floor's worth of mesh data at a time.
 *
 * Invoked from [GlbWriter.writeStreaming]'s sink callback exactly once per
 * non-empty floor in floor index order. The [OffHeapBuf] references are
 * borrowed — the sink MUST consume them before returning and MUST NOT retain
 * the references past its return (the producer reuses the buffers for the next
 * floor).
 */
fun interface FloorSink {
    fun onFloor(
        floorIdx: Int,
        yMin: Int,
        yMax: Int,
        positions: OffHeapBuf,   // size = vertices * 3 (floats)
        uvs: OffHeapBuf,         // size = vertices * 2 (floats)
        normals: OffHeapBuf?,    // size = vertices * 3 (floats), or null
        indices: OffHeapBuf,      // size = triangles * 3 (ints)
    )
}

/**
 * Texture atlas bundled with the GLB BIN chunk's tail bytes.
 */
internal data class GlbAtlas(
    val pngBytes: ByteArray,
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0 && height > 0) { "atlas dimensions must be positive, got ${width}x$height" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GlbAtlas) return false
        return width == other.width && height == other.height &&
            pngBytes.contentEquals(other.pngBytes)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + pngBytes.contentHashCode()
        return result
    }
}
