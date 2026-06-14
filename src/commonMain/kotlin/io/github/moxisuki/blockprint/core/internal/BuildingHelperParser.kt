package io.github.moxisuki.blockprint.core.internal

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.Litematic
import io.github.moxisuki.blockprint.core.LitematicRegion
import io.github.moxisuki.blockprint.core.Position
import io.github.moxisuki.blockprint.core.SchematicFormat

internal object BuildingHelperParser {

    fun parse(bytes: ByteArray): Litematic {
        val text = bytes.decodeToString()
        val name = jsonField(text, "name")?.ifEmpty { null } ?: "建筑小帮手蓝图"
        val author = jsonField(text, "author") ?: ""
        val statePosStr = jsonField(text, "statePosArrayList")
            ?: throw IllegalArgumentException("建筑小帮手: 缺少 statePosArrayList")
        val inner = unescape(statePosStr)

        val palette = parseBlockPalette(inner)
        val (startX, startY, startZ) = parsePos(inner, "startpos")
        val (endX, endY, endZ) = parsePos(inner, "endpos")
        val statelist = parseStateList(inner)

        val width = endX - startX + 1
        val height = endY - startY + 1
        val depth = endZ - startZ + 1
        val total = width * height * depth

        val blocks = if (statelist.size >= total) {
            statelist.copyOf(total)
        } else {
            IntArray(total).also { a -> for (i in statelist.indices) a[i] = statelist[i] }
        }

        val region = LitematicRegion(
            name = "Default",
            width = width,
            height = height,
            depth = depth,
            position = Position(startX, startY, startZ),
            palette = BlockPalette(palette),
            blocks = blocks,
        )

        return Litematic(
            name = name,
            author = author,
            description = "",
            version = null,
            minecraftDataVersion = null,
            regions = listOf(region),
            format = SchematicFormat.BuildingHelper,
        )
    }

    private fun unescape(s: String): String = s
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")

    private fun jsonField(json: String, key: String): String? {
        val qk = "\"$key\""
        val start = json.indexOf(qk)
        if (start < 0) return null
        val colon = json.indexOf(':', start + qk.length)
        if (colon < 0) return null
        var i = colon + 1
        while (i < json.length && (json[i] == ' ' || json[i] == '\t')) i++
        if (i >= json.length || json[i] != '"') return null
        val begin = i + 1
        var end = begin
        while (end < json.length) {
            if (json[end] == '\\') { end += 2; continue }
            if (json[end] == '"') break
            end++
        }
        return if (end > begin) json.substring(begin, end) else null
    }

    private fun parseBlockPalette(inner: String): List<BlockState> {
        val result = mutableListOf<BlockState>()
        val nameRegex = Regex("""Name:"([^"]+)"""")
        val propsRegex = Regex("""Properties:\{([^}]*)\}""")
        var i = 0
        while (true) {
            val bs = inner.indexOf("{Name:", i)
            if (bs < 0) break
            val be = findMatchingBrace(inner, bs)
            val seg = inner.substring(bs, be + 1)
            val nm = nameRegex.find(seg)?.groupValues?.getOrNull(1) ?: ""
            if (nm.isEmpty()) { i = be + 1; continue }
            val props = mutableMapOf<String, String>()
            propsRegex.find(seg)?.let { m ->
                Regex("""(\w+):"([^"]*)"""").findAll(m.groupValues[1]).forEach { kv ->
                    props[kv.groupValues[1]] = kv.groupValues[2]
                }
            }
            result.add(BlockState(nm, props.ifEmpty { null }))
            i = be + 1
        }
        return result.ifEmpty { listOf(BlockState("minecraft:air")) }
    }

    private fun findMatchingBrace(s: String, start: Int): Int {
        var d = 0
        for (j in start until s.length) {
            when (s[j]) {
                '{' -> d++
                '}' -> { d--; if (d == 0) return j }
            }
        }
        return s.length - 1
    }

    private fun parsePos(inner: String, key: String): Triple<Int, Int, Int> {
        val k = "$key:"
        val ki = inner.indexOf(k)
        if (ki < 0) return Triple(0, 0, 0)
        val x = intAfter(inner, "X:", ki) ?: 0
        val y = intAfter(inner, "Y:", ki) ?: 0
        val z = intAfter(inner, "Z:", ki) ?: 0
        return Triple(x, y, z)
    }

    private fun intAfter(s: String, prefix: String, from: Int): Int? {
        val i = s.indexOf(prefix, from)
        if (i < 0) return null
        var j = i + prefix.length
        val neg = j < s.length && s[j] == '-'
        if (neg) j++
        var v = 0
        while (j < s.length && s[j] in '0'..'9') {
            v = v * 10 + (s[j] - '0')
            j++
        }
        if (j == i + prefix.length || (neg && j == i + prefix.length + 1)) return null
        return if (neg) -v else v
    }

    private fun parseStateList(inner: String): IntArray {
        val prefix = "statelist:[I;"
        val i = inner.indexOf(prefix)
        if (i < 0) return IntArray(0)
        val start = i + prefix.length
        val end = inner.indexOf(']', start)
        if (end < 0) return IntArray(0)
        val nums = inner.substring(start, end).split(",").mapNotNull { it.trim().toIntOrNull() }
        return nums.toIntArray()
    }
}
