package io.github.moxisuki.blockprint.core.glb.platform

/**
 * Abstraction over file-system access so the GLB pipeline can run on
 * both JVM ({@code java.nio.file.Path}) and Android (SAF / assets).
 */
interface FileAccessor {
    /** Read the file at [relPath] as a byte array, or null if not found. */
    fun readBytes(relPath: String): ByteArray?

    /** True when [relPath] exists and is a regular file. */
    fun exists(relPath: String): Boolean
}
