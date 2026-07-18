package io.github.moxisuki.blockprint.core.api

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.NbtReader
import io.github.moxisuki.blockprint.core.NbtTag
import io.github.moxisuki.blockprint.core.NbtTagType
import io.github.moxisuki.blockprint.core.Position
import io.github.moxisuki.blockprint.core.SchematicFormat
import io.github.moxisuki.blockprint.core.exceptions.BlockPrintException
import io.github.moxisuki.blockprint.core.exceptions.NbtFormatException
import io.github.moxisuki.blockprint.core.format.FormatDetector
import io.github.moxisuki.blockprint.core.format.buildinghelper.BuildingHelperReader
import io.github.moxisuki.blockprint.core.format.litematica.LitematicaReader
import io.github.moxisuki.blockprint.core.format.sponge.SpongeReader
import io.github.moxisuki.blockprint.core.format.structure.StructureReader
import io.github.moxisuki.blockprint.core.internal.NbtAccessors
import io.github.moxisuki.blockprint.core.model.BlockPrintDocument
import io.github.moxisuki.blockprint.core.model.BlockPrintRegion
import io.github.moxisuki.blockprint.core.model.BlockPrintSummary
import io.github.moxisuki.blockprint.core.model.checkedVolume
import java.io.File
import java.io.BufferedInputStream
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
        val buffered = if (stream is BufferedInputStream) stream else BufferedInputStream(stream)
        if (looksLikeBuildingHelperJson(buffered)) {
            return@use try {
                BuildingHelperReader.parse(buffered.readBytes())
            } catch (e: BlockPrintException) {
                throw e
            } catch (e: Exception) {
                throw BlockPrintException("建筑小帮手解析失败: ${e.message}", e)
            }
        }
        val root = try { NbtReader.readRoot(buffered) }
        catch (e: Exception) { throw BlockPrintException("NBT parse failed: ${e.message}", e) }
        parseRoot(root)
    }

    @JvmStatic
    fun read(bytes: ByteArray): BlockPrintDocument {
        if (looksLikeBuildingHelperJson(bytes)) {
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
        if (looksLikeBuildingHelperJson(bytes)) {
            return try { BuildingHelperReader.parse(bytes) }
            catch (e: Exception) { throw BlockPrintException("建筑小帮手解析失败: ${e.message}", e) }
        }
        val root = NbtReader.readRoot(bytes)
        // Lenient path: try the strict parsers via parseRoot; for partial files
        // whose declared format the strict parser rejects, fall back to a
        // placeholder region sized to whatever size metadata is present.
        return try {
            parseRoot(root)
        } catch (_: BlockPrintException) {
            parsePartialPlaceholder(root)
        }
    }

    @JvmStatic
    fun detectFormat(file: File): SchematicFormat = file.inputStream().use { detectFormat(it) }
    @JvmStatic
    fun detectFormat(input: InputStream): SchematicFormat = input.use { stream ->
        detectFormat(stream.readBytes())
    }
    @JvmStatic
    fun detectFormat(bytes: ByteArray): SchematicFormat {
        if (looksLikeBuildingHelperJson(bytes)) return SchematicFormat.BuildingHelper
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
        if (looksLikeBuildingHelperJson(bytes)) return try {
            BuildingHelperReader.readHeader(bytes)
        } catch (e: Exception) {
            throw BlockPrintException("BuildingHelper header parse failed: ${e.message}", e)
        }
        val root = try { NbtReader.readRootHeader(bytes, skipSubtreeNames = setOf("Regions", "Schematic")) }
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
            SchematicFormat.PartialNbt -> parsePartialPlaceholder(root)
            SchematicFormat.Unknown, SchematicFormat.BuildingHelper -> throw BlockPrintException("Not a recognized schematic format")
        }
    } catch (e: NbtFormatException) {
        throw BlockPrintException("NBT parse failed at offset 0x${e.offset.toString(16)}", e)
    }

    /** Build a placeholder document for partial / stripped schematic files. */
    private fun parsePartialPlaceholder(root: NbtTag.CompoundTag): BlockPrintDocument {
        val (w, h, d) = readSizeLenient(root)
        val region = BlockPrintRegion(
            name = "Default", width = w, height = h, depth = d,
            position = Position.ZERO,
            palette = BlockPalette(listOf(BlockState("minecraft:air", null))),
            blocks = IntArray(checkedVolume(w, h, d, "Partial NBT placeholder")),
        )
        val meta = root.get("Metadata") as? NbtTag.CompoundTag
        return BlockPrintDocument(
            minecraftDataVersion = NbtAccessors.readIntOrNull(root, "MinecraftDataVersion"),
            version = NbtAccessors.readIntOrNull(root, "Version"),
            name = NbtAccessors.readStringOrEmpty(meta, "Name")
                .ifEmpty { NbtAccessors.readStringOrEmpty(root, "Name") },
            author = NbtAccessors.readStringOrEmpty(meta, "Author")
                .ifEmpty { NbtAccessors.readStringOrEmpty(root, "Author") },
            description = NbtAccessors.readStringOrEmpty(root, "Description"),
            regions = listOf(region),
            format = FormatDetector.detect(root),
        )
    }

    private fun readSizeLenient(root: NbtTag.CompoundTag): Triple<Int, Int, Int> {
        (root.get("Size") as? NbtTag.CompoundTag)?.let { return tripleFrom(it) }
        (root.get("size") as? NbtTag.ListTag)?.let { list ->
            if (list.elementType == NbtTagType.Int && list.value.size == 3) {
                val ints = list.value.map { (it as NbtTag.IntTag).value }
                return Triple(ints[0], ints[1], ints[2])
            }
        }
        (root.get("Metadata") as? NbtTag.CompoundTag)?.get("EnclosingSize")?.let { en ->
            if (en is NbtTag.CompoundTag) return tripleFrom(en)
        }
        // Sponge v3 fallback
        val w = (root.get("Width") as? NbtTag.ShortTag)?.value?.toInt()
        val h = (root.get("Height") as? NbtTag.ShortTag)?.value?.toInt()
        val d = (root.get("Length") as? NbtTag.ShortTag)?.value?.toInt()
        if (w != null && h != null && d != null) return Triple(w, h, d)
        throw BlockPrintException("Cannot determine size: no Size / size / EnclosingSize / Width+Height+Length")
    }

    private fun tripleFrom(c: NbtTag.CompoundTag): Triple<Int, Int, Int> {
        val x = (c.get("x") as? NbtTag.IntTag)?.value ?: 1
        val y = (c.get("y") as? NbtTag.IntTag)?.value ?: 1
        val z = (c.get("z") as? NbtTag.IntTag)?.value ?: 1
        return Triple(x, y, z)
    }

    private fun looksLikeBuildingHelperJson(input: BufferedInputStream): Boolean {
        input.mark(JSON_SNIFF_LIMIT)
        val prefix = ByteArray(JSON_SNIFF_LIMIT)
        val count = input.read(prefix)
        input.reset()
        return count > 0 && looksLikeBuildingHelperJson(prefix, count)
    }

    private fun looksLikeBuildingHelperJson(bytes: ByteArray, length: Int = bytes.size): Boolean {
        var i = 0
        val end = minOf(length, bytes.size)
        if (end >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            i = 3
        }
        while (i < end && bytes[i].toInt().toChar().isWhitespace()) i++
        return i < end && bytes[i] == '{'.code.toByte()
    }

    private const val JSON_SNIFF_LIMIT = 4096
}
