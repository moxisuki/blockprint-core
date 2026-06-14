package io.github.moxisuki.blockprint.core.glb

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * JVM [FileAccessor] that searches a list of base directories for a
 * relative path. The first matching regular file wins.
 */
class PathFileAccessor(
    private val dirs: List<Path>,
) : FileAccessor {

    /** Convenience constructor for a single base directory. */
    constructor(dir: Path) : this(listOf(dir))

    override fun readBytes(relPath: String): ByteArray? {
        for (dir in dirs) {
            val file = dir.resolve(relPath)
            if (Files.isRegularFile(file, *emptyArray<LinkOption>())) {
                return Files.readAllBytes(file)
            }
        }
        return null
    }

    override fun exists(relPath: String): Boolean {
        for (dir in dirs) {
            if (Files.isRegularFile(dir.resolve(relPath), *emptyArray<LinkOption>())) {
                return true
            }
        }
        return false
    }
}
