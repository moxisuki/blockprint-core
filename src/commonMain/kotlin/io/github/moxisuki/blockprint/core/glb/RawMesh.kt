package io.github.moxisuki.blockprint.core.glb

/**
 * 原始三角形 mesh 数据——直接写入 GLB，不经过 Element/Face/facePlaneCorners。
 * 用于 OBJ 等非矩形几何。
 */
data class RawMesh(
    val positions: List<Float>,  // 3*n floats (indexed vertices)
    val uvs: List<Float>,        // 2*n floats
    val normals: List<Float>,    // 3*n floats
    val indices: List<Int>? = null,  // null = sequential triangles (old format)
    val texture: String,
)
