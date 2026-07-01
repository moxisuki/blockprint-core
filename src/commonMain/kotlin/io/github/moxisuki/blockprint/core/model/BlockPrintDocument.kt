package io.github.moxisuki.blockprint.core.model

import io.github.moxisuki.blockprint.core.Litematic as LegacyLitematic
import io.github.moxisuki.blockprint.core.LitematicRegion as LegacyRegion
import io.github.moxisuki.blockprint.core.Position
import io.github.moxisuki.blockprint.core.SchematicFormat

/**
 * New canonical document model. In Phase 2 readers will produce this
 * directly; in Phase 1 it wraps the legacy [LegacyLitematic] for
 * interop with existing readers.
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

    companion object {
        /** Adapter from the legacy model. Used only during migration. */
        fun fromLegacy(lit: LegacyLitematic): BlockPrintDocument = BlockPrintDocument(
            minecraftDataVersion = lit.minecraftDataVersion,
            version = lit.version,
            name = lit.name,
            author = lit.author,
            description = lit.description,
            regions = lit.regions.map { BlockPrintRegion.fromLegacy(it) },
            format = lit.format,
        )
    }
}