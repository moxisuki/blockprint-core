package io.github.moxisuki.blockprint.core

/**
 * Material list — how many times each block appears in a litematic.
 *
 * Lookup is by the resolved block name (with the `minecraft:` namespace),
 * so two regions that share a palette entry share a single count. The list
 * is mutable because the typical use is to grow it as you iterate.
 *
 * Use [toSortedList] / [toSortedByCount] for stable, presentation-ready views.
 */
class MaterialList : LinkedHashMap<String, Int>() {
    /** Add one occurrence of [blockName]. */
    fun add(blockName: String, count: Int = 1) {
        require(count >= 0) { "Count must be non-negative, got $count" }
        if (count == 0) return
        merge(blockName, count, Int::plus)
    }

    /** Returns entries sorted by block name (alphabetical). */
    fun toSortedList(): List<Pair<String, Int>> = entries
        .map { it.key to it.value }
        .sortedBy { it.first }

    /** Returns entries sorted by count, descending. */
    fun toSortedByCount(): List<Pair<String, Int>> = entries
        .map { it.key to it.value }
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

    companion object {
        /**
         * Build a [MaterialList] from a [Litematic] by walking every region's
         * decoded block array. Index 0 is treated as air and skipped.
         */
        fun from(litematic: Litematic, includeAir: Boolean = false): MaterialList {
            val out = MaterialList()
            for (region in litematic.regions) {
                val palette = region.palette.entries
                for (id in region.rawBlocks) {
                    if (id == 0 && !includeAir) continue
                    if (id < 0 || id >= palette.size) {
                        out.add("unknown", 1)
                    } else {
                        out.add(palette[id].name, 1)
                    }
                }
            }
            return out
        }
    }
}
