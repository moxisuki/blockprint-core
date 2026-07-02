package io.github.moxisuki.blockprint.core

import io.github.moxisuki.blockprint.core.exceptions.BlockPrintException

/**
 * Recognized schematic file formats this library can read.
 *
 * Detection is based on the structure of the NBT root compound (or, for
 * BuildingHelper, on the leading byte of the file):
 *
 * | Format           | Marker                                                                |
 * |------------------|-----------------------------------------------------------------------|
 * | [Litematica]     | `Regions` compound (one entry per region)                             |
 * | [Sponge] v2      | `Palette` + `BlockData` at root, int `Width/Height/Length`            |
 * | [Sponge] v3      | `Schematic` wrapper compound, `Version=3`, `Short` dims, `Blocks`     |
 * | [Structure]      | `palette` + `blocks` lists (vanilla /structure)                       |
 * | [BuildingHelper] | leading `{` byte + `statePosArrayList` field (JSON)                   |
 * | [PartialNbt]     | `Size` compound, `size` list, or no size metadata                     |
 * | [Unknown]        | none of the above                                                     |
 *
 * Use [LitematicReader.detectFormat] to identify a file's format
 * before deciding whether to call [LitematicReader.read] (strict)
 * or [LitematicReader.readLenient] (for partial / debug files).
 */
enum class SchematicFormat(val displayName: String) {
    /** Standard Litematica `.litematic` file with `Regions` + `Palette` + `BlockStates`. */
    Litematica("Litematica (.litematic)"),

    /**
     * WorldEdit Schematic (Sponge Schematic spec). Saved with the `.schematic`
     * (long) or `.schem` (short, used by MCreator / Schematica) extension.
     * The reader accepts both **v2** (Palette + BlockData at root, int
     * `Width/Height/Length`, `Metadata/EnclosingSize`) and **v3** (a
     * `Schematic` wrapper compound, `Version=3`, `Short` dims, `Offset` as
     * `int[3]`, `Blocks.{Palette,Data,BlockEntities}`). The writer only
     * emits v3. "Sponge" is the historical implementation name; "WorldEdit"
     * is the common ecosystem-facing label.
     */
    Sponge("WorldEdit Schematic (.schematic / .schem)"),

    /**
     * Vanilla Minecraft `.nbt` schematic — produced by `/structure save`
     * or a structure block. Identified by `size` + `palette` + `blocks`
     * at the NBT root. Blocks are stored sparsely (only non-air entries
     * with explicit positions); the lib converts to a dense region on
     * load. Functionally a "generic NBT" target for any vanilla-style
     * palette-and-blocks layout.
     */
    Structure("Vanilla NBT (.nbt)"),

    /**
     * Generic NBT or partial litematic. The file may have a `size` /
     * `Size` / `EnclosingSize` at the root and a partial structure
     * (e.g. only `size` + `entities`); it is not a full Litematica.
     * Load with [LitematicReader.readLenient] to get a placeholder
     * region sized to the declared dimensions.
     */
    PartialNbt("Partial / generic NBT"),

    /** Root compound present but no recognized layout marker. */
    Unknown("Unknown / not a schematic"),

    /** BuildingHelper ("建筑小帮手") JSON blueprint with statePosArrayList + statelist. */
    BuildingHelper("BuildingHelper (.json)");

    companion object {
        /**
         * Classify the parsed NBT root. Returns [Litematica] if
         * `Regions` is present, [Structure] if `palette` + `blocks`
         * are present at the root, [Sponge] if `Metadata/EnclosingSize`
         * is present, [PartialNbt] if any of `Size` / `size` /
         * `EnclosingSize` is present, [Unknown] otherwise.
         */
        fun fromNbtRoot(root: NbtTag.CompoundTag): SchematicFormat = when {
            root.contains("Regions") -> Litematica
            // Sponge Schematic v3 (WorldEdit 7.3+): the root compound holds
            // a single child named "Schematic", whose content is a
            // CompoundTag with Version=3 + Width/Height/Length (Short) +
            // Blocks sub-compound.
            (root.get("Schematic") as? NbtTag.CompoundTag)
                ?.let { (it.get("Version") as? NbtTag.IntTag)?.value == 3 && it.contains("Blocks") } == true -> Sponge
            // Vanilla structure: palette is a list of compounds, blocks is a
            // list of compounds. We require both to be present AND be compound
            // lists (not just any tag with those names, to avoid false positives
            // from deeply-nested entity data that happens to share the name).
            (root.get("palette") as? NbtTag.ListTag)?.elementType == NbtTagType.Compound
                && ((root.get("blocks") as? NbtTag.ListTag)?.elementType == NbtTagType.Compound
                    || (root.get("Blocks") as? NbtTag.ListTag)?.elementType == NbtTagType.Compound) -> Structure
            (root.get("Metadata") as? NbtTag.CompoundTag)
                ?.contains("EnclosingSize") == true -> Sponge
            root.contains("Size") || root.contains("size") -> PartialNbt
            else -> Unknown
        }

        /**
         * Resolve a format from a filename extension. Accepts both bare
         * (`"litematic"`) and dotted (`".litematic"`) forms; case-insensitive.
         *
         * @throws LitematicException for unknown extensions.
         */
        @JvmStatic
        fun fromExtension(ext: String): SchematicFormat {
            val normalized = ext.trim().lowercase().removePrefix(".")
            return when (normalized) {
                "litematic" -> Litematica
                "schematic", "schem" -> Sponge
                "nbt" -> Structure
                "json" -> BuildingHelper
                else -> throw BlockPrintException(
                    "Cannot infer schematic format from extension '.$ext' " +
                        "(expected one of: .litematic, .schematic/.schem, .nbt, .json)",
                )
            }
        }
    }
}
