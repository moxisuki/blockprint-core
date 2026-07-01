package io.github.moxisuki.blockprint.core.exceptions

/**
 * Thrown by the GLB layer on export failures. The reader API
 * catches this and re-throws as [BlockPrintException] with the
 * underlying cause preserved.
 */
class GlbExportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)