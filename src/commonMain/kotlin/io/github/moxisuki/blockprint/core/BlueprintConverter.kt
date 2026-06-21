package io.github.moxisuki.blockprint.core

import io.github.moxisuki.blockprint.core.exceptions.LitematicException
import io.github.moxisuki.blockprint.core.internal.format.BuildingHelperWriter
import io.github.moxisuki.blockprint.core.internal.format.LitematicWriter
import io.github.moxisuki.blockprint.core.internal.format.SpongeWriter
import io.github.moxisuki.blockprint.core.internal.format.StructureWriter
import java.io.File
import java.io.InputStream

/**
 * Public facade for converting between Minecraft blueprint formats:
 * Litematica, Sponge Schematic, vanilla Structure, and BuildingHelper.
 *
 * All conversion goes through the in-memory [Litematic] model:
 * read any source via [LitematicReader], then `convert` to any
 * supported target.
 *
 * Targets `PartialNbt` and `Unknown` are not real output formats —
 * they are read-side categories that cannot be written.
 *
 * Multi-region input is allowed only for the [SchematicFormat.Litematica]
 * target; all other targets reject it with [LitematicException].
 */
object BlueprintConverter {

    /** Convert an in-memory [Litematic] into the target format's byte payload. */
    @JvmStatic
    @JvmOverloads
    fun convert(source: Litematic, target: SchematicFormat): ByteArray {
        requireSingleRegion(source, target)
        return when (target) {
            SchematicFormat.Litematica -> LitematicWriter.write(source)
            SchematicFormat.Sponge -> SpongeWriter.write(source)
            SchematicFormat.Structure -> StructureWriter.write(source)
            SchematicFormat.BuildingHelper -> BuildingHelperWriter.write(source)
            SchematicFormat.PartialNbt, SchematicFormat.Unknown ->
                throw LitematicException(
                    "${target.displayName} is a read-side category; " +
                        "cannot be used as a convert target",
                )
        }
    }

    /**
     * Read raw [source] bytes (auto-detecting the format) and convert
     * to [target]. Source must be one of the four supported formats.
     */
    @JvmStatic
    fun convert(source: ByteArray, target: SchematicFormat): ByteArray {
        val lit = LitematicReader.read(source)
        return convert(lit, target)
    }

    /** Stream variant of [convert]. The stream is fully consumed and closed. */
    @JvmStatic
    fun convert(source: InputStream, target: SchematicFormat): ByteArray =
        source.use { convert(it.readBytes(), target) }

    /**
     * File-level convenience. Source format is inferred from `source`'s
     * extension; target format is inferred from `outFile`'s extension by
     * default (override via [target]). `outFile` is overwritten.
     *
     * @throws LitematicException if either extension is unknown.
     */
    @JvmStatic
    @JvmOverloads
    fun convert(
        source: File,
        outFile: File,
        target: SchematicFormat = SchematicFormat.fromExtension(outFile.extension),
    ) {
        val sourceFormat = SchematicFormat.fromExtension(source.extension)
        val lit = LitematicReader.read(source)
        outFile.writeBytes(convert(lit, target))
    }

    private fun requireSingleRegion(source: Litematic, target: SchematicFormat) {
        if (target != SchematicFormat.Litematica && source.regions.size > 1) {
            throw LitematicException(
                "Format ${target.displayName} does not support multiple " +
                    "regions; source has ${source.regions.size}. " +
                    "Pick one with primaryRegion or split first.",
            )
        }
    }
}
