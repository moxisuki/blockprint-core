package io.github.moxisuki.blockprint.core

import java.io.File
import java.io.InputStream

/**
 * High-level entry point for reading NBT (`.nbt`) files, including
 * vanilla Minecraft datatypes and partial / malformed litematic files
 * that the strict [LitematicReader] would reject.
 *
 * The root tag of an NBT file is always a named [NbtTag.CompoundTag].
 * Vanilla NBT files use an empty name for the root.
 *
 * This class is a thin wrapper around [NbtReader] + [NbtTag]: it
 * exposes the parsed tree via [root] and adds convenience accessors
 * for the most common shapes (size triples, litematic metadata, etc.).
 *
 * Supports gzip-wrapped NBT (the standard format Mojang uses) and
 * raw NBT; auto-detected by the 1F 8B magic header.
 *
 * ### Example: read a vanilla NBT file
 *
 * ```kotlin
 * val doc = NbtDocument.read(File("test.nbt"))
 * val name = doc.root.get("name") as? NbtTag.StringTag
 * val size = (doc.root.get("size") as? NbtTag.ListTag)?.value
 *     ?.filterIsInstance<NbtTag.IntTag>()
 *     ?.map { it.value }
 *     ?: listOf(0, 0, 0)
 * ```
 *
 * ### Example: probe a partial litematic
 *
 * `LitematicReader` requires `Palette`, `BlockStatePalette`, and
 * `BlockStates` — files that are still being edited (or that were
 * stripped down for testing) will fail. Use `NbtDocument` to inspect
 * the raw structure:
 *
 * ```kotlin
 * val doc = NbtDocument.read(File("test2.nbt"))  // size + entities only
 * check(doc.root.contains("Regions")) { "not a litematic" }
 * val width  = (doc.root.get("size") as? NbtTag.ListTag)?.value?.get(0)
 * val height = (doc.root.get("size") as? NbtTag.ListTag)?.value?.get(1)
 * val depth  = (doc.root.get("size") as? NbtTag.ListTag)?.value?.get(2)
 * ```
 */
class NbtDocument private constructor(
    /**
     * The root tag's name. The NBT spec says vanilla files use an empty
     * string; some custom NBT dialects use a meaningful name.
     */
    val rootName: String,

    /**
     * The parsed root compound. Use [NbtTag.CompoundTag.get] /
     * [NbtTag.CompoundTag.require] to navigate.
     */
    val root: NbtTag.CompoundTag,
) {
    companion object {
        /** Read an NBT file from disk. Closes the file automatically. */
        @JvmStatic
        fun read(file: File): NbtDocument = read(file.readBytes())

        /** Read NBT from a stream. Closes the stream automatically. */
        @JvmStatic
        fun read(input: InputStream): NbtDocument = read(input.readBytes())

        /**
         * Read NBT from a raw byte payload. Auto-detects gzip vs raw.
         *
         * @throws IllegalArgumentException if the payload is not
         *   valid NBT (unknown tag id, truncated, non-compound root).
         */
        @JvmStatic
        fun read(bytes: ByteArray): NbtDocument {
            val root = NbtReader.readRoot(bytes)
            // NBT root is conventionally named "" but we expose it via
            // a property for callers that care.
            return NbtDocument(rootName = "", root = root)
        }
    }
}
