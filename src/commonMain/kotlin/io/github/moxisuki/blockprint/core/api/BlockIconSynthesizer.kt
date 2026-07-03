package io.github.moxisuki.blockprint.core.api

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.Position
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
}
