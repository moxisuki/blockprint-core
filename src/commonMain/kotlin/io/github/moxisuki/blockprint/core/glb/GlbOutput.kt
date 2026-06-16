package io.github.moxisuki.blockprint.core.glb

/**
 * Final mesh output produced by MeshBuilder and consumed by GlbWriter.
 *
 * The building is always represented as a list of FloorSlice entries — never
 * as a single flat mesh. When the user does not opt in to floor splitting
 * (i.e. GlbExportOptions.floorHeight == 0) the list contains exactly one
 * element spanning the whole region, which the writer then emits as a single
 * glTF mesh / primitive.
 *
 * @property floors Ordered list of floor slices, sorted by ascending yMin.
 *   Empty floors (no blocks) are dropped by the builder and never appear here.
 * @property atlasPng PNG-encoded bytes of the texture atlas shared by all floors.
 * @property atlasWidth Atlas width in pixels.
 * @property atlasHeight Atlas height in pixels.
 */
data class GlbOutput(
    val floors: List<FloorSlice>,
    val atlasPng: ByteArray,
    val atlasWidth: Int,
    val atlasHeight: Int,
) {
    override fun equals(other: Any?): Boolean = this === other ||
        (other is GlbOutput &&
            floors == other.floors &&
            atlasPng.contentEquals(other.atlasPng) &&
            atlasWidth == other.atlasWidth &&
            atlasHeight == other.atlasHeight)

    override fun hashCode(): Int {
        var r = floors.hashCode()
        r = 31 * r + atlasPng.contentHashCode()
        r = 31 * r + atlasWidth
        r = 31 * r + atlasHeight
        return r
    }
}

/**
 * One Y-axis slice of the building.
 *
 * Indices are local to this floor: the smallest index is always 0, and the
 * largest is positions.size / 3 - 1. GlbWriter offsets these indices by the
 * cumulative vertex count of preceding floors when concatenating them into
 * the shared indices bufferView.
 *
 * @property yMin Inclusive minimum Y voxel covered by this floor.
 * @property yMax Inclusive maximum Y voxel covered by this floor.
 * @property positions Flat float array of vertex positions (x0, y0, z0, x1, y1, z1, ...).
 * @property uvs Flat float array of texture coordinates (u0, v0, u1, v1, ...).
 * @property normals Flat float array of vertex normals, or null if the floor
 *   contains no normals (only possible for OBJ-modeled floors that produced
 *   no face normals — in practice the builder always writes normals).
 * @property indices Flat int array of triangle indices into positions / uvs / normals.
 */
data class FloorSlice(
    val yMin: Int,
    val yMax: Int,
    val positions: FloatArray,
    val uvs: FloatArray,
    val normals: FloatArray?,
    val indices: IntArray,
) {
    override fun equals(other: Any?): Boolean = this === other ||
        (other is FloorSlice &&
            yMin == other.yMin &&
            yMax == other.yMax &&
            positions.contentEquals(other.positions) &&
            uvs.contentEquals(other.uvs) &&
            ((normals == null && other.normals == null) ||
                (normals != null && other.normals != null && normals.contentEquals(other.normals))) &&
            indices.contentEquals(other.indices))

    override fun hashCode(): Int {
        var r = yMin
        r = 31 * r + yMax
        r = 31 * r + positions.contentHashCode()
        r = 31 * r + uvs.contentHashCode()
        r = 31 * r + (normals?.contentHashCode() ?: 0)
        r = 31 * r + indices.contentHashCode()
        return r
    }
}
