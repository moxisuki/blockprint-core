package io.github.moxisuki.blockprint.core.glb.model

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Multipart fallback tests — when a multipart blockstate is resolved in
 * isolation (no neighbouring-block properties, as happens in
 * `BlockIconSynthesizer`), no `apply.when` clause matches, and the
 * resolver used to fall back to `minecraft:block/<name>`. For wall /
 * fence / chain / etc. that model file does not exist, so the resolver
 * returned `emptyCube()` and the rendered icon came out as a flat cube
 * (transparent black on screen).
 *
 * The fix in [ModelResolver.resolveMultipart] picks the first `apply`'s
 * model instead, so isolated icons resolve to a real geometry.
 */
class MultipartFallbackTest {

    @Test
    fun isolated_wall_falls_back_to_first_apply_model() {
        val tmp = Files.createTempDirectory("multipart-fallback")
        try {
            val bsDir = tmp.resolve("minecraft/blockstates")
            val modelDir = tmp.resolve("minecraft/models/block")
            Files.createDirectories(bsDir)
            Files.createDirectories(modelDir)

            // Synthetic andesite_wall blockstate: every apply is conditional
            // (up=true / north=low / east=low), so a resolve with empty
            // properties matches none of them and the fallback branch fires.
            Files.writeString(
                bsDir.resolve("andesite_wall.json"),
                """
                {
                  "multipart": [
                    { "apply": { "model": "minecraft:block/andesite_wall_post" }, "when": { "up": "true" } },
                    { "apply": { "model": "minecraft:block/andesite_wall_side" }, "when": { "north": "low" } },
                    { "apply": { "model": "minecraft:block/andesite_wall_side", "y": 90 }, "when": { "east": "low" } }
                  ]
                }
                """.trimIndent(),
            )

            // Minimal wall_post model with a non-cube shape: a 4-wide post
            // from y=0 to y=16. The pre-fix fallback pointed at
            // `minecraft:block/andesite_wall`, which does NOT exist on disk
            // — `resolveModel` would have returned `emptyCube()` (a full
            // 16x16x16 cube with from=[0,0,0] to=[16,16,16]). Asserting that
            // the post shape is present proves the multipart fallback
            // resolved to andesite_wall_post, not the broken fallback.
            Files.writeString(
                modelDir.resolve("andesite_wall_post.json"),
                """
                {
                  "elements": [
                    {
                      "from": [4, 0, 4],
                      "to":   [12, 16, 12],
                      "faces": {
                        "north": { "texture": "#wall" },
                        "south": { "texture": "#wall" },
                        "east":  { "texture": "#wall" },
                        "west":  { "texture": "#wall" },
                        "up":    { "texture": "#wall" },
                        "down":  { "texture": "#wall" }
                      }
                    }
                  ]
                }
                """.trimIndent(),
            )

            // Intentionally do NOT create minecraft:block/andesite_wall.json:
            // a successful pre-fix run would have resolved to that missing
            // file and returned emptyCube, and the post-shape assertion below
            // would have failed.
            val resolver = ModelResolver(listOf(tmp))
            val result = resolver.resolve("minecraft:andesite_wall", emptyMap())

            val postElement = result.elements.firstOrNull { elem ->
                elem.from == listOf(4.0, 0.0, 4.0) && elem.to == listOf(12.0, 16.0, 12.0)
            }
            assertTrue(
                "Expected wall_post geometry (from=[4,0,4] to=[12,16,12]) but got elements: ${result.elements}",
                postElement != null,
            )
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
