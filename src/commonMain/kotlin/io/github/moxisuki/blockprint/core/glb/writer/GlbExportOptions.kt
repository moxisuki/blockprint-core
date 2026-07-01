package io.github.moxisuki.blockprint.core.glb.writer

/**
 * User-tunable options for the GLB export pipeline.
 *
 * @property floorHeight Height of one Y-axis floor in voxels. `0` (default) keeps
 *   the building as a single layer — equivalent to the pre-split behavior. Any
 *   positive value slices the building into `ceil(region.height / floorHeight)`
 *   floors. The top floor absorbs any remainder when `region.height` is not
 *   evenly divisible. Values greater than `region.height` collapse to 1 floor.
 * @property explodeGap Vertical gap in block units between consecutive floors,
 *   baked into each floor node's `translation.y` at export time. `0f` (default)
 *   produces zero translation on every floor — visually equivalent to no
 *   explode. Consumers can override the gap at runtime by mutating each floor
 *   node's `position.y`.
 * @property enableTinting Whether to apply vanilla biome tints (grass, leaves,
 *   redstone, etc.) and special block tints (water, lava) at export time.
 *   `true` (default) matches the pre-options behavior.
 */
data class GlbExportOptions(
    val floorHeight: Int = 0,
    val explodeGap: Float = 0f,
    val enableTinting: Boolean = true,
)
