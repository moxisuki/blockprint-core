package io.github.moxisuki.blockprint.core

/**
 * One entry in a region's block-state palette.
 *
 * Mirrors the vanilla NBT structure:
 * ```
 * { Name: "minecraft:stone", Properties: { variant: "stone" } }
 * ```
 *
 * `properties` is `null` when the block has no state properties (e.g. dirt).
 * When non-null, keys are property names and values are stringified
 * property values.
 */
data class BlockState(
    val name: String,
    val properties: Map<String, String>? = null,
) {
    /** e.g. `minecraft:stone[variant=stone]`; omits the bracket when no properties. */
    override fun toString(): String {
        if (properties.isNullOrEmpty()) return name
        val props = properties.entries.joinToString(",") { "${it.key}=${it.value}" }
        return "$name[$props]"
    }
}
