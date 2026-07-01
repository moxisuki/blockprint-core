package io.github.moxisuki.blockprint.core.api

import io.github.moxisuki.blockprint.core.Litematic
import io.github.moxisuki.blockprint.core.LitematicReader
import io.github.moxisuki.blockprint.core.LitematicRegion
import io.github.moxisuki.blockprint.core.SchematicFormat
import io.github.moxisuki.blockprint.core.exceptions.LitematicException
import java.io.File
import java.io.InputStream

/**
 * Temporary forwarder. Will be promoted to the new read API in Phase 2/3
 * with [BlockPrintDocument] / [BlockPrintRegion] return types.
 */
object BlockPrintReader {
    @JvmStatic
    fun read(file: File): Litematic = LitematicReader.read(file)
    @JvmStatic
    fun read(input: InputStream): Litematic = LitematicReader.read(input)
    @JvmStatic
    fun read(bytes: ByteArray): Litematic = LitematicReader.read(bytes)
    @JvmStatic
    fun readLenient(file: File): Litematic = LitematicReader.readLenient(file)
    @JvmStatic
    fun readLenient(input: InputStream): Litematic = LitematicReader.readLenient(input)
    @JvmStatic
    fun readLenient(bytes: ByteArray): Litematic = LitematicReader.readLenient(bytes)
    @JvmStatic
    fun detectFormat(file: File): SchematicFormat = LitematicReader.detectFormat(file)
    @JvmStatic
    fun detectFormat(input: InputStream): SchematicFormat = LitematicReader.detectFormat(input)
    @JvmStatic
    fun detectFormat(bytes: ByteArray): SchematicFormat = LitematicReader.detectFormat(bytes)
}
