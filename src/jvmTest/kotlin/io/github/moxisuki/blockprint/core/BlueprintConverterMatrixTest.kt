package io.github.moxisuki.blockprint.core

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class BlueprintConverterMatrixTest {

    private fun sampleLitematic(): Litematic {
        val palette = BlockPalette(
            listOf(
                BlockState("minecraft:air"),
                BlockState("minecraft:stone"),
                BlockState("minecraft:dirt"),
            ),
        )
        val region = LitematicRegion(
            name = "Main", width = 2, height = 1, depth = 1,
            position = Position.ZERO, palette = palette, blocks = intArrayOf(1, 2),
        )
        return Litematic(
            minecraftDataVersion = 3465, version = 6,
            name = "n", author = "a", description = "",
            regions = listOf(region), format = SchematicFormat.Litematica,
        )
    }

    @Test
    fun all_16_cross_pairs_preserve_block_identity() {
        val lit = sampleLitematic()
        // For each (source format, target format) pair, convert the source
        // domain model -> source format bytes -> target format bytes, then
        // verify the target round-trips back to the same in-memory blocks.
        val sources = listOf(
            "litematica" to BlueprintConverter.convert(lit, SchematicFormat.Litematica),
            "sponge" to BlueprintConverter.convert(lit, SchematicFormat.Sponge),
            "structure" to BlueprintConverter.convert(lit, SchematicFormat.Structure),
            "buildingHelper" to BlueprintConverter.convert(lit, SchematicFormat.BuildingHelper),
        )
        // Pre-decode each source row into an in-memory Litematic once.
        // Sponge and Litematica use strict read; Structure and BuildingHelper
        // use lenient read (Structure has no `Regions`, BuildingHelper is JSON).
        val sourcesDomain = sources.associate { (name, bytes) ->
            val decode = when (name) {
                "litematica", "sponge" -> { b: ByteArray -> LitematicReader.read(b) }
                else -> { b: ByteArray -> LitematicReader.readLenient(b) }
            }
            name to decode(bytes)
        }
        for (srcName in sourcesDomain.keys) {
            val srcDomain = sourcesDomain.getValue(srcName)
            for (target in listOf(
                SchematicFormat.Litematica,
                SchematicFormat.Sponge,
                SchematicFormat.Structure,
                SchematicFormat.BuildingHelper,
            )) {
                // Convert source Litematic -> target bytes.
                val stage1 = BlueprintConverter.convert(srcDomain, target)
                // Convert target bytes -> Litematic. Pick the right decoder:
                //   Litematica / Sponge target: strict NBT read works.
                //   Structure target: lacks strict litematica layout, use lenient.
                //   BuildingHelper target: JSON, lenient detects it.
                val read = when (target) {
                    SchematicFormat.Litematica, SchematicFormat.Sponge ->
                        LitematicReader.read(stage1)
                    SchematicFormat.Structure, SchematicFormat.BuildingHelper ->
                        LitematicReader.readLenient(stage1)
                    SchematicFormat.PartialNbt, SchematicFormat.Unknown ->
                        error("unreachable: real format only")
                }
                val r = read.regions.single()
                // StructureWriter drops air from palette & shifts state indices
                // by -1; parseStructure prepends air & shifts back by +1. The
                // net effect is that round-tripping through Structure recovers
                // the original intArrayOf(1, 2). The same holds for the other
                // three formats (they preserve the air-at-0 invariant), so a
                // single expected value covers all 16 pairs.
                assertArrayEquals(
                    "mismatch: src=$srcName target=$target",
                    intArrayOf(1, 2), r.rawBlocks,
                )
            }
        }
    }
}