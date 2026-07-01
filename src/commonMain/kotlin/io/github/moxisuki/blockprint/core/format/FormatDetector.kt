package io.github.moxisuki.blockprint.core.format

import io.github.moxisuki.blockprint.core.NbtTag
import io.github.moxisuki.blockprint.core.NbtTagType
import io.github.moxisuki.blockprint.core.SchematicFormat

/**
 * Content-sniffing detector for NBT schematic roots. Order matches
 * real-world frequency: Litematica first (most common), then
 * WorldEdit/Sponge v3, vanilla Structure, Sponge v2, PartialNbt,
 * Unknown.
 */
internal object FormatDetector {
    fun detect(root: NbtTag.CompoundTag): SchematicFormat = when {
        root.contains("Regions") -> SchematicFormat.Litematica
        (root.get("Schematic") as? NbtTag.CompoundTag)
            ?.let { (it.get("Version") as? NbtTag.IntTag)?.value == 3 && it.contains("Blocks") } == true -> SchematicFormat.Sponge
        (root.get("palette") as? NbtTag.ListTag)?.elementType == NbtTagType.Compound &&
            ((root.get("blocks") as? NbtTag.ListTag)?.elementType == NbtTagType.Compound
                || (root.get("Blocks") as? NbtTag.ListTag)?.elementType == NbtTagType.Compound) -> SchematicFormat.Structure
        (root.get("Metadata") as? NbtTag.CompoundTag)
            ?.contains("EnclosingSize") == true -> SchematicFormat.Sponge
        root.contains("Size") || root.contains("size") -> SchematicFormat.PartialNbt
        else -> SchematicFormat.Unknown
    }
}
