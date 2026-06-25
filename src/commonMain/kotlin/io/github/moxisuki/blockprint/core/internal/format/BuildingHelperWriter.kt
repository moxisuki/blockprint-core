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
@Deprecated("BuildingHelper format was experimental; use Sponge (.schem) or Litematica as canonical formats")
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
        // Inner payload (matching the standard Building Helper format):
        //   blockstatemap:[{Name:"...",Properties:{k:"v"}},...],
        //   endpos:{X:...,Y:...,Z:...},
        //   startpos:{X:...,Y:...,Z:...},
        //   statelist:[I;0,1,2,...]
        sb.append("blockstatemap:[")
        val entries = region.palette.entries
        for ((i, state) in entries.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append(blockStateToEmbedded(state))
        }
        sb.append(']')

        // BG2 native copy: pos.subtract(copyStart) yields relative coordinates
        // with startpos near (0,0,0) and the building extending into positive
        // X/Y/Z.  BG2 paste places blocks at (pos.pos + lookingAt), so the
        // building extends from lookingAt toward +X/+Y/+Z.  We follow the
        // same convention: startpos=(0,0,0), endpos=(W-1, H-1, D-1).
        val ex = region.width - 1
        val ey = region.height - 1
        val ez = region.depth - 1
        sb.append(",endpos:{X:").append(ex).append(",Y:").append(ey).append(",Z:").append(ez).append('}')
        sb.append(",startpos:{X:0,Y:0,Z:0}")

        // statelist: comma-separated. BuildingGadgets2 writes/reads via
        // BlockPos.betweenClosedStream(aabb), which iterates Y-outermost,
        // Z-mid, X-innermost with Y from minY to maxY (bottom-up).  This
        // matches the in-memory rawBlocks rawIndex = y * W * D + z * W + x.
        sb.append(",statelist:[I;")
        val raw = region.rawBlocks
        val layerSize = region.width * region.depth
        var firstCell = true
        for (y in 0 until region.height) {
            val base = y * layerSize
            for (i in 0 until layerSize) {
                if (!firstCell) sb.append(',')
                sb.append(raw[base + i])
                firstCell = false
            }
        }
        sb.append(']')

        val inner = "{$sb}"
        // Build the top-level JSON object to match the standard Building
        // Helper format: { "name", "statePosArrayList", "requiredItems" }.
        // Per the standard format there is no "author" field.
        val nameJson = "\"name\":${jsonString(source.name)}"
        val spJson = "\"statePosArrayList\":${jsonString(inner)}"

        // requiredItems: block-statistics map using the standard Building
        // Helper item reference format: each key is
        //   minecraft:Reference{ResourceKey[minecraft:item / blockName]=blockName}
        // Per the standard mod this format is generated internally for the
        // item registry; we emit the same format for exact compatibility.
        val blocks = region.rawBlocks
        val palette = region.palette
        val itemCounts = LinkedHashMap<String, Int>()
        for (idx in blocks) {
            if (idx == 0) continue // skip air
            val name = palette[idx].name
            val refKey = "minecraft:Reference{ResourceKey[minecraft:item / $name]=$name}"
            itemCounts[refKey] = (itemCounts[refKey] ?: 0) + 1
        }
        val reqItems = StringBuilder()
        var first = true
        for ((name, count) in itemCounts) {
            if (!first) reqItems.append(',')
            reqItems.append(jsonString(name)).append(':').append(count)
            first = false
        }
        val riJson = "\"requiredItems\":{${reqItems}}"
        return "{$nameJson,$spJson,$riJson}"
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
