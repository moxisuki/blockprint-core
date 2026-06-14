package io.github.moxisuki.blockprint.core

/**
 * Top-level litematic document. Holds the file's metadata and its regions.
 *
 * - [minecraftDataVersion] mirrors NBT field `MinecraftDataVersion` (post-1.13.2 files).
 * - [version] is the litematic file format version (`Version` field, often 6).
 * - [name] / [author] / [description] are user-facing metadata and may be empty.
 * - [regions] preserves NBT insertion order.
 * - [format] tells you which schematic file format the document came from
 *   (Litematica / Sponge / PartialNbt / Unknown). Set automatically by
 *   [LitematicReader.read] and [LitematicReader.readLenient].
 */
data class Litematic(
    val minecraftDataVersion: Int?,
    val version: Int?,
    val name: String,
    val author: String,
    val description: String,
    val regions: List<LitematicRegion>,
    /** The schematic file format this document was loaded from. */
    val format: SchematicFormat = SchematicFormat.Unknown,
) {
    /** Convenience: first region, or null if the file has none. */
    val primaryRegion: LitematicRegion? get() = regions.firstOrNull()

    /** Total non-air block count summed across all regions. */
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
