package io.github.moxisuki.blockprint.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import java.io.File

/**
 * Dump the raw NBT tree of the hardcoded litematic file.
 * Useful for figuring out why a file fails to parse.
 */
class NbtDumpTest {

    companion object {
        private val LITEMATIC_FILE: File =
            File("C:\\Users\\Administrator\\Documents\\CCO\\LitematicMobile\\test.litematic")
    }

    @Test
    fun `dump nbt tree`() {
        check(LITEMATIC_FILE.isFile) {
            "Litematic file not found at: ${LITEMATIC_FILE.absolutePath}"
        }

        val root = NbtReader.readRoot(LITEMATIC_FILE.readBytes())
        dump(root, indent = 0, name = "ROOT")
    }

    private fun dump(tag: io.github.moxisuki.litematicCore.NbtTag, indent: Int, name: String) {
        val pad = "  ".repeat(indent)
        when (tag) {
            is io.github.moxisuki.litematicCore.NbtTag.CompoundTag -> {
                println("${pad}Compound[$name] (${tag.value.size} entries)")
                for ((k, v) in tag.value) dump(v, indent + 1, k)
            }
            is io.github.moxisuki.litematicCore.NbtTag.ListTag -> {
                println("${pad}List[$name] (type=${tag.elementType}, size=${tag.value.size})")
                tag.value.forEachIndexed { i, v -> dump(v, indent + 1, "[$i]") }
            }
            is io.github.moxisuki.litematicCore.NbtTag.StringTag -> println("${pad}String[$name] = \"${tag.value}\"")
            is io.github.moxisuki.litematicCore.NbtTag.IntTag -> println("${pad}Int[$name] = ${tag.value}")
            is io.github.moxisuki.litematicCore.NbtTag.LongTag -> println("${pad}Long[$name] = ${tag.value}")
            is io.github.moxisuki.litematicCore.NbtTag.ShortTag -> println("${pad}Short[$name] = ${tag.value}")
            is io.github.moxisuki.litematicCore.NbtTag.ByteTag -> println("${pad}Byte[$name] = ${tag.value}")
            is io.github.moxisuki.litematicCore.NbtTag.FloatTag -> println("${pad}Float[$name] = ${tag.value}")
            is io.github.moxisuki.litematicCore.NbtTag.DoubleTag -> println("${pad}Double[$name] = ${tag.value}")
            is io.github.moxisuki.litematicCore.NbtTag.ByteArrayTag -> println("${pad}ByteArray[$name] (${tag.value.size} bytes)")
            is io.github.moxisuki.litematicCore.NbtTag.IntArrayTag -> println("${pad}IntArray[$name] (${tag.value.size} ints)")
            is io.github.moxisuki.litematicCore.NbtTag.LongArrayTag -> println("${pad}LongArray[$name] (${tag.value.size} longs, first 4: ${tag.value.take(4)})")
            is io.github.moxisuki.litematicCore.NbtTag.EndTag -> println("${pad}End[$name]")
        }
    }
}