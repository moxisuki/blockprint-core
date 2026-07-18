package io.github.moxisuki.blockprint.tools;

import com.mojang.blaze3d.vertex.BufferBuilder;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.model.Model;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Sidecar metadata captured while Flywheel converts BakedQuads to meshes.
 *
 * <p>Flywheel intentionally keeps only final atlas UVs in a Mesh. Some mods
 * rewrite those UVs to anonymous atlas regions while retaining the logical
 * sprite only on the source BakedQuad. This registry carries that semantic
 * texture identity across the otherwise lossy conversion boundary.</p>
 */
public final class FlywheelTextureProvenance {
    private static final Map<BufferBuilder, List<TextureAtlasSprite>> SPRITES_BY_BUFFER = new IdentityHashMap<>();
    private static final Map<Mesh, List<TextureAtlasSprite>> SPRITES_BY_MESH = new IdentityHashMap<>();
    private static long recordedQuads;
    private static long associatedBuffers;
    private static long associatedMeshes;
    private static long meshLookups;
    private static long meshLookupHits;

    private FlywheelTextureProvenance() {
    }

    public static synchronized void record(BufferBuilder buffer, TextureAtlasSprite sprite) {
        if (buffer == null || sprite == null) {
            return;
        }
        SPRITES_BY_BUFFER.computeIfAbsent(buffer, ignored -> new ArrayList<>()).add(sprite);
        recordedQuads++;
    }

    public static synchronized boolean hasRecordedSprites(BufferBuilder buffer) {
        List<TextureAtlasSprite> sprites = SPRITES_BY_BUFFER.get(buffer);
        return sprites != null && !sprites.isEmpty();
    }

    public static synchronized void associate(
        List<BufferBuilder> buffers,
        List<Model.ConfiguredMesh> configuredMeshes
    ) {
        int count = Math.min(buffers.size(), configuredMeshes.size());
        for (int i = 0; i < count; i++) {
            List<TextureAtlasSprite> sprites = SPRITES_BY_BUFFER.remove(buffers.get(i));
            if (sprites == null || sprites.isEmpty()) {
                continue;
            }
            SPRITES_BY_MESH.put(configuredMeshes.get(i).mesh(), List.copyOf(sprites));
            associatedBuffers++;
            associatedMeshes++;
        }
    }

    public static synchronized List<TextureAtlasSprite> spritesFor(Mesh mesh) {
        meshLookups++;
        List<TextureAtlasSprite> sprites = SPRITES_BY_MESH.get(mesh);
        if (sprites == null) {
            return List.of();
        }
        meshLookupHits++;
        return sprites;
    }

    public static synchronized String diagnosticsSummary() {
        return "recordedQuads=" + recordedQuads
            + ", pendingBuffers=" + SPRITES_BY_BUFFER.size()
            + ", associatedBuffers=" + associatedBuffers
            + ", associatedMeshes=" + associatedMeshes
            + ", retainedMeshes=" + SPRITES_BY_MESH.size()
            + ", meshLookups=" + meshLookups
            + ", meshLookupHits=" + meshLookupHits;
    }
}
