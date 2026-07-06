package io.github.moxisuki.blockprint.core.api

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.Position
import io.github.moxisuki.blockprint.core.glb.internal.JsonParser
import io.github.moxisuki.blockprint.core.model.BlockPrintDocument
import io.github.moxisuki.blockprint.core.model.BlockPrintRegion

/**
 * Synthesizes minimal [BlockPrintDocument]s containing a single block
 * at position (0, 0, 0).
 *
 * Designed for downstream tools (e.g. icon generators) that need to render
 * one block in isolation via [BlockPrintToGlb]. The synthesizer does not
 * know about specific block geometries — multipart models, custom OBJ
 * blocks (Create), tinted grass/leaves, doors/beds/etc. are all handled
 * by the existing model resolution pipeline downstream.
 *
 * The returned document has:
 * - one region named `Icon` of size 1×1×1 at position (0, 0, 0)
 * - a palette of size 2: index 0 = `minecraft:air`, index 1 = the target block
 * - `rawBlocks[0]` = 1 (the target block); the array has length 1
 *
 * The caller is expected to feed this document into
 * [BlockPrintToGlb.convertToBytes] along with assets directories that
 * contain the relevant `blockstates/`, `models/`, and `textures/` trees.
 *
 * @param namespace block namespace, e.g. `minecraft`, `create`
 * @param name block name, e.g. `stone`, `mechanical_cogwheel`
 * @param properties optional block-state properties (e.g. `type` to `top` for slabs);
 *                   empty for stateless blocks
 * @return a [BlockPrintDocument] suitable for [BlockPrintToGlb]
 * @throws IllegalArgumentException if `namespace` or `name` is empty
 */
object BlockIconSynthesizer {

    private const val REGION_NAME = "Icon"
    private const val DOC_NAME = "BlockIcon"
    private const val DOC_AUTHOR = "blockprint-icons"
    private const val DOC_DESCRIPTION = "Single-block icon"
    private val AIR = BlockState("minecraft:air", null)

    private val BUTTON_PREFERRED = linkedMapOf(
        "face" to "floor",
        "facing" to "north",
        "powered" to "false",
    )

    fun synthesize(
        namespace: String,
        name: String,
        properties: Map<String, String> = emptyMap(),
    ): BlockPrintDocument {
        require(namespace.isNotBlank()) { "namespace must not be blank" }
        require(name.isNotBlank()) { "name must not be blank" }

        val target = BlockState(
            name = "$namespace:$name",
            properties = properties.takeIf { it.isNotEmpty() },
        )
        val palette = BlockPalette(listOf(AIR, target))
        val region = BlockPrintRegion(
            name = REGION_NAME,
            width = 1,
            height = 1,
            depth = 1,
            position = Position.ZERO,
            palette = palette,
            blocks = intArrayOf(1),
        )
        return BlockPrintDocument(
            minecraftDataVersion = null,
            version = null,
            name = DOC_NAME,
            author = DOC_AUTHOR,
            description = DOC_DESCRIPTION,
            regions = listOf(region),
        )
    }

    /**
     * Synthesizes an icon document using a vanilla-style `blockstates/<name>.json`
     * to decide whether the block is multi-block (e.g. doors → 1×2×1 with both
     * halves) or single-block, and to pick a sensible default variant.
     *
     * Behaviour:
     * - If [name] ends with `_door`, returns a 1×2×1 region with the lower
     *   half at (0, 0, 0) and the upper half at (0, 1, 0), both using the
     *   fixed neutral state `facing=east,hinge=left,open=false`.
     * - For buttons and `*pressure_plate` blocks, prefers the
     *   `face=floor,facing=north,powered=false` variant when present; falls
     *   back to the first variant otherwise.
     * - For every other block, uses the first variant's property map (which
     *   mirrors the existing single-block behaviour for stateless blocks when
     *   the JSON's variant key is empty).
     * - When the JSON has no `variants` field (e.g. multipart fences), falls
     *   back to a stateless single block.
     *
     * @param blockstateJson the contents of the blockstate JSON file
     * @param namespace block namespace, e.g. `minecraft`
     * @param name block name, e.g. `acacia_door`
     */
    fun synthesizeFromBlockstate(
        blockstateJson: String,
        namespace: String,
        name: String,
    ): BlockPrintDocument {
        require(namespace.isNotBlank()) { "namespace must not be blank" }
        require(name.isNotBlank()) { "name must not be blank" }

        val blockFullName = "$namespace:$name"

        val variants: List<Map<String, String>> = run {
            val root = JsonParser.parseObject(blockstateJson)
            @Suppress("UNCHECKED_CAST")
            val variantsObj = root["variants"] as? Map<String, Any?> ?: emptyMap()
            // LinkedHashMap preserves JSON insertion order.
            variantsObj.keys.map(::parseVariantKey)
        }

        if (name.endsWith("_door")) {
            val lower = BlockState(
                name = blockFullName,
                properties = linkedMapOf(
                    "half" to "lower",
                    "facing" to "east",
                    "hinge" to "left",
                    "open" to "false",
                ),
            )
            val upper = BlockState(
                name = blockFullName,
                properties = linkedMapOf(
                    "half" to "upper",
                    "facing" to "east",
                    "hinge" to "left",
                    "open" to "false",
                ),
            )
            val palette = BlockPalette(listOf(AIR, lower, upper))
            val region = BlockPrintRegion(
                name = REGION_NAME,
                width = 1,
                height = 2,
                depth = 1,
                position = Position.ZERO,
                palette = palette,
            )
            region.setBlock(0, 0, 0, 1)
            region.setBlock(0, 1, 0, 2)
            return BlockPrintDocument(
                minecraftDataVersion = null,
                version = null,
                name = DOC_NAME,
                author = DOC_AUTHOR,
                description = DOC_DESCRIPTION,
                regions = listOf(region),
            )
        }

        val pickedProps = pickBestVariant(name, variants)
        return synthesize(namespace, name, pickedProps)
    }

    private fun pickBestVariant(
        blockName: String,
        variants: List<Map<String, String>>,
    ): Map<String, String> {
        if (variants.isEmpty()) return emptyMap()
        return when {
            blockName.endsWith("_button") || blockName.endsWith("pressure_plate") -> {
                variants.firstOrNull { it == BUTTON_PREFERRED } ?: variants.first()
            }
            else -> variants.first()
        }
    }

    private fun parseVariantKey(key: String): Map<String, String> {
        if (key.isEmpty()) return emptyMap()
        val out = linkedMapOf<String, String>()
        for (part in key.split(',')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val k = part.substring(0, eq).trim()
            val v = part.substring(eq + 1).trim()
            if (k.isNotEmpty()) out[k] = v
        }
        return out
    }
}
