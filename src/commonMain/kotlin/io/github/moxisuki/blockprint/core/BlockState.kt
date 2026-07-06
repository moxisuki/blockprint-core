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

    companion object {
        /**
         * Parses a block state string like `"minecraft:stone"` or
         * `"minecraft:oak_log[axis=y,leaves=persistent]"` into a [BlockState].
         *
         * The input must contain a colon (`:`). Properties inside `[...]` are
         * parsed as `key=value` pairs separated by commas.
         *
         * @throws IllegalArgumentException if the input does not contain a colon
         *   or has malformed brackets.
         */
        fun parse(input: String): BlockState {
            val trimmed = input.trim()
            val bracket = trimmed.indexOf('[')
            if (bracket < 0) {
                require(':' in trimmed) { "Block state must contain a namespace colon: \"$trimmed\"" }
                return BlockState(trimmed, null)
            }
            val namePart = trimmed.substring(0, bracket)
            require(':' in namePart) { "Block state must contain a namespace colon: \"$trimmed\"" }
            val propsPart = trimmed.substring(bracket + 1, trimmed.length - 1)
            require(trimmed.endsWith(']')) { "Unclosed bracket in block state: \"$trimmed\"" }
            val properties = if (propsPart.isBlank()) {
                null
            } else {
                val map = linkedMapOf<String, String>()
                for (pair in propsPart.split(',')) {
                    val eq = pair.indexOf('=')
                    if (eq > 0) {
                        val k = pair.substring(0, eq).trim()
                        val v = pair.substring(eq + 1).trim()
                        if (k.isNotEmpty()) map[k] = v
                    }
                }
                map.takeIf { it.isNotEmpty() }
            }
            return BlockState(namePart, properties)
        }
    }
}
