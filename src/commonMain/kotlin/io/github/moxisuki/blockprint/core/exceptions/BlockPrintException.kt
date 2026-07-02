package io.github.moxisuki.blockprint.core.exceptions

/**
 * Thrown for any blueprint (Litematica / Sponge / Structure /
 * BuildingHelper) parsing / IO / structural error.
 *
 * The cause chain typically wraps a malformed NBT input, an unsupported
 * tag type, or a region whose declared size does not match the BlockStates
 * long-array length.
 *
 * This is the canonical error type for the public BlockPrintReader API.
 * Format-specific callers may still throw a more specific subtype such as
 * [NbtFormatException]; both are caught and re-wrapped at the API boundary.
 */
class BlockPrintException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
