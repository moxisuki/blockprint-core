package io.github.moxisuki.blockprint.core

import io.github.moxisuki.blockprint.core.exceptions.LitematicException
import io.github.moxisuki.blockprint.core.internal.BuildingHelperParser
import io.github.moxisuki.blockprint.core.internal.LitematicParser
import java.io.File
import java.io.InputStream

/**
 * Public entry point for parsing `.litematic` files.
 *
 * All methods are blocking and stateless; you can call them from any thread.
 * The library has no third-party dependencies and detects gzip wrapping
 * automatically, so you don't need to pre-decode the file.
 *
 * Typical use:
 * ```
 * val litematic = LitematicReader.read(File("house.litematic"))
 * val materials = MaterialList.from(litematic).toSortedByCount()
 * ```
 */
object LitematicReader {

    /** Read from a file path. */
    @JvmStatic
    fun read(file: File): Litematic = file.inputStream().use { read(it) }

    /** Read from an arbitrary input stream. The stream is fully consumed and closed. */
    @JvmStatic
    @Throws(LitematicException::class)
    fun read(input: InputStream): Litematic = input.use { stream ->
        val bytes = stream.readBytes()
        read(bytes)
    }

    /** Read from an in-memory byte array (raw or gzipped NBT). */
    @JvmStatic
    @Throws(LitematicException::class)
    fun read(bytes: ByteArray): Litematic {
        val root = NbtReader.readRoot(bytes)
        return LitematicParser.parse(root)
    }

    // ------------------------------------------------------------------
    // Lenient mode
    // ------------------------------------------------------------------

    /**
     * Lenient variant: accept partial / stripped litematic files.
     *
     * Standard [read] requires `Regions`, `Palette`, and `BlockStates`
     * to be present — incomplete or hand-crafted files throw
     * [LitematicException]. `readLenient` falls back to a single
     * empty region sized to whatever size metadata the file carries:
     *
     *   - Litematica `Size` compound at root
     *   - `size` list of 3 ints at root
     *   - Sponge `Metadata/EnclosingSize` compound
     *
     * The returned [Litematic] always has at least one region, so
     * `primaryRegion`, `regions.first()`, and [MaterialList.from] all
     * work. The block array will be all-air when no `BlockStates` are
     * present, so the material list will be empty.
     *
     * **Use this when**: you want to load any litematic-shaped NBT
     * (debug files, partial exports, files under construction).
     *
     * **Use [read] when**: you need the strict contract that an
     * incomplete file is an error.
     */
    @JvmStatic
    @JvmOverloads
    fun readLenient(file: File): Litematic = file.inputStream().use { readLenient(it) }

    /** Lenient variant. See [readLenient] for details. */
    @JvmStatic
    @Throws(LitematicException::class)
    fun readLenient(input: InputStream): Litematic = input.use { stream ->
        readLenient(stream.readBytes())
    }

    /** Lenient variant. See [readLenient] for details. */
    @JvmStatic
    @Throws(LitematicException::class)
    fun readLenient(bytes: ByteArray): Litematic {
        // 建筑小帮手 JSON 蓝图
        if (bytes.isNotEmpty() && bytes[0] == '{'.code.toByte()) {
            try {
                return BuildingHelperParser.parse(bytes)
            } catch (e: Exception) {
                throw LitematicException("建筑小帮手解析失败: ${e.message}", e)
            }
        }
        val root = NbtReader.readRoot(bytes)
        return LitematicParser.parseLenient(root)
    }

    // ------------------------------------------------------------------
    // Format detection
    // ------------------------------------------------------------------

    /**
     * Identify the schematic file format without throwing on partial
     * files. Useful for routing to [read] vs [readLenient], or for
     * showing the user what kind of file they have.
     *
     * This only inspects the NBT root structure; it does NOT validate
     * that the file is fully parseable. Call [read] / [readLenient]
     * afterwards to actually load the schematic.
     */
    @JvmStatic
    @JvmOverloads
    fun detectFormat(file: File): SchematicFormat = file.inputStream().use { detectFormat(it) }

    /** See [detectFormat]. */
    @JvmStatic
    fun detectFormat(input: InputStream): SchematicFormat = input.use { stream ->
        detectFormat(stream.readBytes())
    }

    /** See [detectFormat]. */
    @JvmStatic
    fun detectFormat(bytes: ByteArray): SchematicFormat {
        if (bytes.isNotEmpty() && bytes[0] == '{'.code.toByte()) {
            return SchematicFormat.BuildingHelper
        }
        return SchematicFormat.fromNbtRoot(NbtReader.readRoot(bytes))
    }
}
