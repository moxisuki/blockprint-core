package io.github.moxisuki.blockprint.core.testutil

import io.github.moxisuki.blockprint.core.NbtTag
import io.github.moxisuki.blockprint.core.NbtTagType
import io.github.moxisuki.blockprint.core.NbtWriter
import java.io.File

object TestBlueprintFixtures {
    fun minimalLitematicBytes(): ByteArray {
        val region = NbtTag.CompoundTag(listOf(
            "Size" to int3(2, 1, 1),
            "Position" to int3(0, 0, 0),
            "BlockStatePalette" to NbtTag.ListTag(
                NbtTagType.Compound,
                listOf(NbtTag.CompoundTag(listOf("Name" to NbtTag.StringTag("minecraft:air")))),
            ),
            "BlockStates" to NbtTag.LongArrayTag(longArrayOf(0L)),
        ))
        val root = NbtTag.CompoundTag(listOf(
            "Version" to NbtTag.IntTag(7),
            "MinecraftDataVersion" to NbtTag.IntTag(3953),
            "Metadata" to NbtTag.CompoundTag(listOf(
                "Name" to NbtTag.StringTag("fixture"),
                "Author" to NbtTag.StringTag("tests"),
                "EnclosingSize" to int3(2, 1, 1),
            )),
            "Regions" to NbtTag.CompoundTag(listOf("main" to region)),
        ))
        return NbtWriter.writeRootToGzipBytes(root)
    }

    fun minimalLitematicFile(): File = File.createTempFile("blockprint-fixture-", ".litematic").apply {
        writeBytes(minimalLitematicBytes())
        deleteOnExit()
    }

    private fun int3(x: Int, y: Int, z: Int) = NbtTag.CompoundTag(listOf(
        "x" to NbtTag.IntTag(x),
        "y" to NbtTag.IntTag(y),
        "z" to NbtTag.IntTag(z),
    ))
}
