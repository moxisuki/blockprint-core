package io.github.moxisuki.blockprint.core

/**
 * Recognized schematic file formats this library can read.
 *
 * Detection is based on the structure of the NBT root compound:
 *
 * | Format                  | Marker tag in root                                  |
 * |-------------------------|----------------------------------------------------|
 * | [Litematica]            | `Regions` compound (one entry per region)          |
 * | [Sponge]                | `Metadata/EnclosingSize` compound                  |
 * | [Structure]             | `palette` + `blocks` lists (vanilla /structure)    |
 * | [PartialNbt]            | `Size` compound, `size` list, or no size metadata   |
 * | [Unknown]               | none of the above                                   |
 *
 * Use [LitematicReader.detectFormat] to identify a file's format
 * before deciding whether to call [LitematicReader.read] (strict)
 * or [LitematicReader.readLenient] (for partial / debug files).
 */
enum class SchematicFormat(val displayName: String) {
    /** Standard Litematica `.litematic` file with `Regions` + `Palette` + `BlockStates`. */
    Litematica("Litematica (.litematic)"),

    /** Sponge Schematic `.schematic` with `Metadata/EnclosingSize`. */
    Sponge("Sponge Schematic (.schematic)"),

    /**
     * Vanilla Minecraft structure file (from `/structure save` or a
     * structure block). Has `size` + `palette` + `blocks` at the root.
     * Blocks are stored sparsely (only non-air entries with explicit
     * positions); the lib converts to a dense region on load.
     */
    Structure("Vanilla structure (.nbt)"),

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
    }
}
