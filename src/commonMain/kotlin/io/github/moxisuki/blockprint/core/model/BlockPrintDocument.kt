package io.github.moxisuki.blockprint.core.model

import io.github.moxisuki.blockprint.core.Position
import io.github.moxisuki.blockprint.core.SchematicFormat

/**
 * Canonical document model. Produced by [io.github.moxisuki.blockprint.core.api.BlockPrintReader.read].
 *
 * - [minecraftDataVersion] mirrors NBT field `MinecraftDataVersion`.
 * - [version] is the schematic file format version.
 * - [regions] preserves NBT insertion order.
 */
data class BlockPrintDocument(
    val minecraftDataVersion: Int?,
    val version: Int?,
    val name: String,
    val author: String,
    val description: String,
    val regions: List<BlockPrintRegion>,
    val format: SchematicFormat = SchematicFormat.Unknown,
) {
    val primaryRegion: BlockPrintRegion? get() = regions.firstOrNull()

    fun blockCount(includeAir: Boolean = false): Int {
        var total = 0
        for (region in regions) {
            if (includeAir) {
                total += region.width * region.height * region.depth
            } else {
                region.rawBlocks.forEach { if (it != 0) total++ }
            }
        }
        return total
    }
}
