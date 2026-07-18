package io.github.moxisuki.blockprint.tools.mixin;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.vertex.BufferBuilder;
import dev.engine_room.flywheel.api.model.Model;
import io.github.moxisuki.blockprint.tools.FlywheelTextureProvenance;
import java.util.ArrayList;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "dev.engine_room.flywheel.lib.model.baked.MeshEmitter", remap = false)
abstract class MeshEmitterMixin {
    @Shadow private BufferBuilder[] bufferBuilders;
    @Shadow private int numBufferBuildersPopulated;

    @Unique private List<BufferBuilder> blockprint$provenanceBuffers = List.of();
    @Unique private int blockprint$meshStart;

    @Inject(method = "end", at = @At("HEAD"))
    private void blockprint$beforeEnd(ImmutableList.Builder<Model.ConfiguredMesh> out, CallbackInfo ci) {
        blockprint$meshStart = out.build().size();
        List<BufferBuilder> populated = new ArrayList<>(numBufferBuildersPopulated);
        for (int i = 0; i < numBufferBuildersPopulated; i++) {
            BufferBuilder buffer = bufferBuilders[i];
            if (buffer != null) {
                populated.add(buffer);
            }
        }
        // Keep every populated buffer in its original slot. Filtering to only
        // provenance-bearing buffers collapses gaps and can bind later sprite
        // streams to the wrong ConfiguredMesh when an engine emits an
        // untracked material before a normal BakedQuad material.
        blockprint$provenanceBuffers = populated;
    }

    @Inject(method = "end", at = @At("RETURN"))
    private void blockprint$afterEnd(ImmutableList.Builder<Model.ConfiguredMesh> out, CallbackInfo ci) {
        List<Model.ConfiguredMesh> allMeshes = out.build();
        if (blockprint$meshStart < allMeshes.size() && !blockprint$provenanceBuffers.isEmpty()) {
            FlywheelTextureProvenance.associate(
                blockprint$provenanceBuffers,
                allMeshes.subList(blockprint$meshStart, allMeshes.size())
            );
        }
        blockprint$provenanceBuffers = List.of();
    }
}
