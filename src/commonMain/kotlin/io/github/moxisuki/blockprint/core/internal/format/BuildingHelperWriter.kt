package io.github.moxisuki.blockprint.core.internal.format

import io.github.moxisuki.blockprint.core.Litematic
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Encode a [Litematic] as a Building Helper ("建筑小帮手") JSON
 * blueprint.
 *
 * The output is plain UTF-8 JSON, not NBT. The `statePosArrayList`
 * value embeds a Java-Internal-Format-ish text payload that the
 * existing [io.github.moxisuki.blockprint.core.internal.BuildingHelperParser]
 * can re-parse.
 *
 * JSON double-quotes inside the embedded payload are escaped
 * with `\"` (the parser unescapes `\"` → `"` before reading the
 * embedded text).
 */
internal object BuildingHelperWriter {

    fun write(source: Litematic): ByteArray {
        val baos = ByteArrayOutputStream()
        write(source, baos)
        return baos.toByteArray()
    }

    /** Stream the Building Helper JSON payload to [out].  Building Helper
     *  output is plain UTF-8 JSON, not NBT, so no NbtWriter is involved. */
    fun write(source: Litematic, out: OutputStream) {
        val region = source.regions.firstOrNull()
            ?: throw IllegalArgumentException("BuildingHelperWriter: source has no regions")
        val json = buildJson(source, region)
        out.write(json.encodeToByteArray())
    }

    private fun buildJson(source: Litematic, region: io.github.moxisuki.blockprint.core.LitematicRegion): String {
        val sb = StringBuilder()
        // Inner payload: palette entries (so the parser can read them),
        // then startpos / endpos / statelist.
        for (state in region.palette.entries) {
            sb.append(blockStateToEmbedded(state))
        }
        val sx = region.position.x
        val sy = region.position.y
        val sz = region.position.z
        val ex = sx + region.width - 1
        val ey = sy + region.height - 1
        val ez = sz + region.depth - 1
        sb.append("startpos:X:").append(sx).append(",Y:").append(sy).append(",Z:").append(sz)
        sb.append("endpos:X:").append(ex).append(",Y:").append(ey).append(",Z:").append(ez)

        // statelist: comma-separated, y-major order matches region.rawBlocks.
        sb.append("statelist:[I;")
        val raw = region.rawBlocks
        for (i in raw.indices) {
            if (i > 0) sb.append(',')
            sb.append(raw[i])
        }
        sb.append(']')

        val inner = sb.toString()
        // Build the top-level JSON object. Values are simple — name and author
        // are escaped to handle any quotes / backslashes. The inner payload
        // contains literal `"` chars (from `Name:"..."` etc.), so it must be
        // JSON-escaped exactly once: `\` → `\\` and `"` → `\"`. Doing it
        // twice would leave `\"` in the decoded text, which breaks the
        // parser's `Name:"..."` regex.
        val nameJson = "\"name\":${jsonString(source.name)}"
        val authorJson = "\"author\":${jsonString(source.author)}"
        val spJson = "\"statePosArrayList\":${jsonString(inner)}"
        return "{$nameJson,$authorJson,$spJson}"
    }

    private fun blockStateToEmbedded(state: io.github.moxisuki.blockprint.core.BlockState): String {
        val props = state.properties
        val propsPart = if (props.isNullOrEmpty()) "" else {
            val pairs = props.entries.joinToString(",") { (k, v) -> "$k:\"$v\"" }
            ",Properties:{$pairs}"
        }
        return "{Name:\"${state.name}\"$propsPart}"
    }

    private fun jsonString(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }
}
