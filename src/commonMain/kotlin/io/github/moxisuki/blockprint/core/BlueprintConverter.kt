package io.github.moxisuki.blockprint.core

import io.github.moxisuki.blockprint.core.exceptions.LitematicException
import io.github.moxisuki.blockprint.core.format.buildinghelper.BuildingHelperWriter
import io.github.moxisuki.blockprint.core.format.litematica.LitematicaWriter
import io.github.moxisuki.blockprint.core.format.sponge.SpongeWriter
import io.github.moxisuki.blockprint.core.format.structure.StructureWriter
import io.github.moxisuki.blockprint.core.model.BlockPrintDocument
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

/**
 * Public facade for converting between Minecraft blueprint formats:
 * Litematica, WorldEdit Schematic v3 (reader also accepts v2), vanilla NBT
 * Structure, and BuildingHelper.
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
 *
 * Two output styles:
 * - **ByteArray overloads** (`convert(...): ByteArray`) — in-memory.
 *   Convenient for small regions.
 * - **OutputStream overloads** (`convert(source, target, out: OutputStream)`) —
 *   streaming. Avoids building the full NBT tree in memory for the
 *   Litematica target. Use these for large schematics. Caller is
 *   responsible for providing a buffered stream (this class will
 *   wrap with a [GZIPOutputStream] for Litematica/Structure output and
 *   a [BufferedOutputStream] for the others).
 */
object BlueprintConverter {

    /** Convert an in-memory [Litematic] into the target format's byte payload. */
    @JvmStatic
    fun convert(source: Litematic, target: SchematicFormat): ByteArray {
        requireSingleRegion(source, target)
        val doc = BlockPrintDocument.fromLegacy(source)
        return when (target) {
            SchematicFormat.Litematica -> LitematicaWriter.write(doc)
            SchematicFormat.Sponge -> SpongeWriter.write(doc)
            SchematicFormat.Structure -> StructureWriter.write(doc)
            SchematicFormat.BuildingHelper -> BuildingHelperWriter.write(doc)
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
     * Streaming conversion: write the target-format payload directly to
     * [out] without holding the full encoded bytes in memory.
     *
     * Caller is responsible for closing [out]. This method does NOT
     * close the user-provided stream — write-side ownership stays with
     * the caller.
     *
     * For Litematica / Structure targets, the output is wrapped in
     * [GZIPOutputStream] (no extra work for the caller). For Sponge and
     * BuildingHelper targets, the output is raw NBT / JSON.
     *
     * Currently the source [Litematic] is still materialised in memory
     * (it's typically much smaller than the encoded form). The
     * streaming benefit is on the **write side**: no full NBT tree
     * built up by the writer, no in-memory byte payload. See
     * [LitematicaWriter.write] for the Litematica write path.
     */
    @JvmStatic
    fun convert(source: Litematic, target: SchematicFormat, out: OutputStream) {
        requireSingleRegion(source, target)
        val wrapped: OutputStream = when (target) {
            SchematicFormat.Litematica, SchematicFormat.Structure, SchematicFormat.Sponge ->
                GZIPOutputStream(BufferedOutputStream(out, 1 shl 16))
            else ->
                BufferedOutputStream(out, 1 shl 16)
        }
        wrapped.use { w ->
            val doc = BlockPrintDocument.fromLegacy(source)
            when (target) {
                SchematicFormat.Litematica -> LitematicaWriter.write(doc, w)
                SchematicFormat.Sponge -> SpongeWriter.write(doc, w)
                SchematicFormat.Structure -> StructureWriter.write(doc, w)
                SchematicFormat.BuildingHelper -> BuildingHelperWriter.write(doc, w)
                SchematicFormat.PartialNbt, SchematicFormat.Unknown ->
                    throw LitematicException(
                        "${target.displayName} is a read-side category; " +
                            "cannot be used as a convert target",
                    )
            }
        }
    }

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
        // Validate the source extension up front for a clearer error than
        // LitematicReader.read's generic failure mode.
        SchematicFormat.fromExtension(source.extension)
        val lit = LitematicReader.read(source)
        outFile.outputStream().use { stream ->
            convert(lit, target, stream)
        }
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
