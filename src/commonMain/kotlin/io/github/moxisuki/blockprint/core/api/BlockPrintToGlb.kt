package io.github.moxisuki.blockprint.core.api

import io.github.moxisuki.blockprint.core.exceptions.BlockPrintException
import io.github.moxisuki.blockprint.core.model.BlockPrintDocument
import java.io.File

/**
 * Public entry point for GLB export.
 *
 * NOTE for 1.0: the GLB code was refactored into subpackages (writer/,
 * mesh/, model/, texture/, platform/, internal/, synthetic/) but the
 * public convert function is not yet wired back up. This stub preserves
 * the API surface so callers can be migrated; calling convert/convertToBytes
 * currently throws [BlockPrintException]. Re-enabling GLB export is
 * tracked as follow-up work after 1.0.
 */
object BlockPrintToGlb {
    @JvmStatic
    @Throws(BlockPrintException::class)
    fun convert(
        document: BlockPrintDocument,
        assetsDirs: List<java.nio.file.Path>,
        outFile: File,
        options: Any = Any(),
    ): File = throw BlockPrintException(
        "GLB export temporarily disabled in 1.0 — see follow-up issue"
    )

    @JvmStatic
    @Throws(BlockPrintException::class)
    fun convertToBytes(
        document: BlockPrintDocument,
        assetsDirs: List<java.nio.file.Path>,
        options: Any = Any(),
        progress: ((Float) -> Unit)? = null,
    ): ByteArray = throw BlockPrintException(
        "GLB export temporarily disabled in 1.0 — see follow-up issue"
    )
}
