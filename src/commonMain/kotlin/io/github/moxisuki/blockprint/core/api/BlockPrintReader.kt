package io.github.moxisuki.blockprint.core.api

import io.github.moxisuki.blockprint.core.NbtReader
import io.github.moxisuki.blockprint.core.NbtTag
import io.github.moxisuki.blockprint.core.SchematicFormat
import io.github.moxisuki.blockprint.core.exceptions.BlockPrintException
import io.github.moxisuki.blockprint.core.exceptions.NbtFormatException
import io.github.moxisuki.blockprint.core.format.FormatDetector
import io.github.moxisuki.blockprint.core.format.buildinghelper.BuildingHelperReader
import io.github.moxisuki.blockprint.core.format.litematica.LitematicaReader
import io.github.moxisuki.blockprint.core.format.sponge.SpongeReader
import io.github.moxisuki.blockprint.core.format.structure.StructureReader
import io.github.moxisuki.blockprint.core.internal.LitematicParser
import io.github.moxisuki.blockprint.core.internal.NbtAccessors
import io.github.moxisuki.blockprint.core.model.BlockPrintDocument
import io.github.moxisuki.blockprint.core.model.BlockPrintSummary
import java.io.File
import java.io.InputStream

/**
 * Public entry point for parsing blueprint files.
 *
 * Detects format by content (not extension), supports gzip-wrapped
 * NBT, and accepts Litematica `.litematic`, WorldEdit Sponge v2/v3
 * (`.schematic`/`.schem`), vanilla Structure (`.nbt`), and
 * BuildingHelper JSON (`.json`).
 */
object BlockPrintReader {
    @JvmStatic
    fun read(file: File): BlockPrintDocument = file.inputStream().use { read(it) }

    @JvmStatic
    fun read(input: InputStream): BlockPrintDocument = input.use { stream ->
        val root = try { NbtReader.readRoot(stream) }
        catch (e: Exception) { throw BlockPrintException("NBT parse failed: ${e.message}", e) }
        parseRoot(root)
    }

    @JvmStatic
    fun read(bytes: ByteArray): BlockPrintDocument {
        if (bytes.isNotEmpty() && bytes[0] == '{'.code.toByte()) {
            return try {
                BuildingHelperReader.parse(bytes)
            } catch (e: BlockPrintException) {
                throw e
            } catch (e: Exception) {
                throw BlockPrintException("建筑小帮手解析失败: ${e.message}", e)
            }
        }
        val root = try { NbtReader.readRoot(bytes) }
        catch (e: Exception) { throw BlockPrintException("NBT parse failed: ${e.message}", e) }
        return parseRoot(root)
    }

    @JvmStatic
    fun readLenient(file: File): BlockPrintDocument = file.inputStream().use { readLenient(it) }
    @JvmStatic
    fun readLenient(input: InputStream): BlockPrintDocument = input.use { stream ->
        readLenient(stream.readBytes())
    }
    @JvmStatic
    fun readLenient(bytes: ByteArray): BlockPrintDocument {
        if (bytes.isNotEmpty() && bytes[0] == '{'.code.toByte()) {
            return try { BuildingHelperReader.parse(bytes) }
            catch (e: Exception) { throw BlockPrintException("建筑小帮手解析失败: ${e.message}", e) }
        }
        val root = NbtReader.readRoot(bytes)
        return BlockPrintDocument.fromLegacy(LitematicParser.parseLenient(root))
    }

    @JvmStatic
    @JvmOverloads
    fun detectFormat(file: File): SchematicFormat = file.inputStream().use { detectFormat(it) }
    @JvmStatic
    fun detectFormat(input: InputStream): SchematicFormat = input.use { stream ->
        detectFormat(stream.readBytes())
    }
    @JvmStatic
    fun detectFormat(bytes: ByteArray): SchematicFormat {
        if (bytes.isNotEmpty() && bytes[0] == '{'.code.toByte()) return SchematicFormat.BuildingHelper
        val root = try { NbtReader.readRoot(bytes) } catch (e: Exception) { return SchematicFormat.Unknown }
        return FormatDetector.detect(root)
    }

    // --- Peek ---
    @JvmStatic
    fun peek(file: File): BlockPrintSummary = file.inputStream().use { peek(it) }
    @JvmStatic
    fun peek(input: InputStream): BlockPrintSummary = input.use { stream ->
        peek(stream.readBytes())
    }
    @JvmStatic
    fun peek(bytes: ByteArray): BlockPrintSummary {
        if (bytes.isNotEmpty() && bytes[0] == '{'.code.toByte()) return try {
            BuildingHelperReader.readHeader(bytes)
        } catch (e: Exception) {
            throw BlockPrintException("BuildingHelper header parse failed: ${e.message}", e)
        }
        val root = try { NbtReader.readRoot(bytes) }
        catch (e: Exception) { throw BlockPrintException("NBT peek failed: ${e.message}", e) }
        return when (FormatDetector.detect(root)) {
            SchematicFormat.Litematica -> LitematicaReader.readHeader(root)
            SchematicFormat.Sponge -> SpongeReader.readHeader(root)
            SchematicFormat.Structure -> StructureReader.readHeader(root)
            SchematicFormat.PartialNbt -> BlockPrintSummary(
                format = SchematicFormat.PartialNbt, name = "", author = "", description = "",
                version = null,
                minecraftDataVersion = NbtAccessors.readIntOrNull(root, "MinecraftDataVersion"),
            )
            SchematicFormat.Unknown -> BlockPrintSummary(SchematicFormat.Unknown, "", "", "", null, null)
            SchematicFormat.BuildingHelper -> error("BuildingHelper routed by byte sniff above")
        }
    }

    // --- internals ---
    private fun parseRoot(root: NbtTag.CompoundTag): BlockPrintDocument = try {
        when (FormatDetector.detect(root)) {
            SchematicFormat.Litematica -> LitematicaReader.parse(root)
            SchematicFormat.Sponge -> SpongeReader.parse(root)
            SchematicFormat.Structure -> StructureReader.parse(root)
            SchematicFormat.PartialNbt -> BlockPrintDocument.fromLegacy(LitematicParser.parseLenient(root))
            SchematicFormat.Unknown, SchematicFormat.BuildingHelper -> throw BlockPrintException("Not a recognized schematic format")
        }
    } catch (e: NbtFormatException) {
        throw BlockPrintException("NBT parse failed at offset 0x${e.offset.toString(16)}", e)
    }
}