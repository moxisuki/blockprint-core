package io.github.moxisuki.blockprint.core.exceptions

/**
 * Thrown by the NBT layer when bytes are malformed. The reader API
 * catches this and re-throws as [BlockPrintException] with the
 * underlying cause preserved.
 */
class NbtFormatException(
    val offset: Long,
    message: String,
) : RuntimeException("NBT error at offset 0x${offset.toString(16)}: $message")