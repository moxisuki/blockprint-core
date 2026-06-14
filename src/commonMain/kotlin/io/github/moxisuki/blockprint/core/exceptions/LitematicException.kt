package io.github.moxisuki.blockprint.core.exceptions

/**
 * Thrown for any litematic parsing / IO / structural error.
 *
 * The cause chain typically wraps a malformed NBT input, an unsupported
 * tag type, or a region whose declared size does not match the BlockStates
 * long-array length.
 */
class LitematicException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
