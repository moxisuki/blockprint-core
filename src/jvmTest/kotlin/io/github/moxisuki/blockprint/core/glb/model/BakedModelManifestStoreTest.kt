package io.github.moxisuki.blockprint.core.glb.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class BakedModelManifestStoreTest {
    @Test
    fun resolverPrefersGeneratedBakedManifest() {
        val assets = Files.createTempDirectory("blockprint-baked-manifest-test")
        val manifestDir = assets.resolve("blockprint").resolve("baked-models")
        Files.createDirectories(manifestDir)
        Files.writeString(
            manifestDir.resolve("test.json"),
            """
            {
              "schema": "blockprint.baked-models.v1",
              "blocks": [
                {
                  "id": "example:probe_block",
                  "states": [
                    {
                      "key": "facing=north,waterlogged=false",
                      "meshes": [
                        {
                          "texture": "example:textures/block/probe",
                          "positions": [0,0,0, 16,0,0, 16,16,0, 0,16,0],
                          "uvs": [0,0, 1,0, 1,1, 0,1],
                          "normals": [0,0,-1, 0,0,-1, 0,0,-1, 0,0,-1],
                          "indices": [0,1,2, 0,2,3]
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        ModelResolver(listOf(assets)).use { resolver ->
            val model = resolver.resolve("example:probe_block", mapOf("facing" to "north"))

            assertTrue(model.elements.isEmpty())
            assertEquals(1, model.rawMeshes.size)
            val mesh = model.rawMeshes.first()
            assertNotNull(mesh)
            assertEquals("example:textures/block/probe", mesh.texture)
            assertEquals(listOf(0f, 0f, 0f, 16f, 0f, 0f, 16f, 16f, 0f, 0f, 16f, 0f), mesh.positions)
            assertEquals(listOf(0, 1, 2, 0, 2, 3), mesh.indices)
        }
    }

    @Test
    fun resolverLoadsSplitManifestForOneBlock() {
        val assets = Files.createTempDirectory("blockprint-baked-split-manifest-test")
        val manifestDir = assets.resolve("blockprint")
            .resolve("baked-models")
            .resolve("by-block")
            .resolve("example")
        Files.createDirectories(manifestDir)
        Files.writeString(
            manifestDir.resolve("split_probe.json"),
            """
            {
              "schema": "blockprint.baked-models.v1",
              "blocks": [
                {
                  "id": "example:split_probe",
                  "states": [
                    {
                      "key": "",
                      "meshes": [
                        {
                          "texture": "example:textures/block/split_probe",
                          "positions": [0,0,0, 16,0,0, 16,16,0],
                          "uvs": [0,0, 1,0, 1,1],
                          "normals": [0,0,-1, 0,0,-1, 0,0,-1],
                          "indices": [0,1,2]
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        ModelResolver(listOf(assets)).use { resolver ->
            val model = resolver.resolve("example:split_probe")

            assertEquals(1, model.rawMeshes.size)
            assertEquals("example:textures/block/split_probe", model.rawMeshes.first().texture)
        }
    }
}
