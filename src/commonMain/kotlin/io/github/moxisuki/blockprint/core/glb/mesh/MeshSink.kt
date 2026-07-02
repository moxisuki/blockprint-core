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
 * Invoked from [MeshBuilder.buildFloorsInto]'s flushFloor callback
 * exactly once per non-empty floor in floor index order. The
 * [OffHeapBuf] references passed to the sink are owned by the
 * underlying [FloorAccum]. The contract on return:
 *
 * - Return `true` if the sink has consumed the buffers (e.g. cloned
 *   them via copyTo, or taken ownership of the OffHeapBufs
 *   themselves by storing the references in a longer-lived
 *   container). The producer will NOT call `acc.reset()` on the
 *   accumulators. This is the path the BlockPrintToGlb Pass 2 sink
 *   uses — the [GlbWriter] then streams directly from the captured
 *   OffHeapBufs, avoiding a per-floor copy.
 *
 * - Return `false` (or omit the expression) to indicate the sink did
 *   not retain a copy. The producer calls `acc.reset()` on the
 *   accumulators so the buffers are immediately reusable. This is
 *   the legacy / drain-sink contract.
 *
 * Returning `true` on a sink that did not actually consume the data
 * is a bug — the next floor's faces will be written into the
 * buffers the sink was supposed to keep, silently overwriting the
 * previously-emitted data. The current FloorAccum and the sink
 * both run on the same thread so a non-consuming-but-returning-true
 * sink is observable as a final-floor-empty output.
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
    ): Boolean
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
