package io.github.moxisuki.blockprint.tools.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.engine_room.flywheel.lib.model.baked.NeoforgeMeshEmitter;
import io.github.moxisuki.blockprint.tools.FlywheelTextureProvenance;
import net.minecraft.client.renderer.block.model.BakedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = NeoforgeMeshEmitter.class, remap = false)
abstract class NeoforgeMeshEmitterMixin {
    @Invoker("getBuffer")
    protected abstract BufferBuilder blockprint$bufferFor(BakedQuad quad);

    @Inject(
        method = "putBulkData(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/renderer/block/model/BakedQuad;FFFFII)V",
        at = @At("HEAD")
    )
    private void blockprint$recordSimple(
        PoseStack.Pose pose,
        BakedQuad quad,
        float red,
        float green,
        float blue,
        float alpha,
        int light,
        int overlay,
        CallbackInfo ci
    ) {
        FlywheelTextureProvenance.record(blockprint$bufferFor(quad), quad.getSprite());
    }

    @Inject(
        method = "putBulkData(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/renderer/block/model/BakedQuad;[FFFFF[IIZ)V",
        at = @At("HEAD")
    )
    private void blockprint$recordAo(
        PoseStack.Pose pose,
        BakedQuad quad,
        float[] brightness,
        float red,
        float green,
        float blue,
        float alpha,
        int[] lights,
        int overlay,
        boolean readExistingColor,
        CallbackInfo ci
    ) {
        FlywheelTextureProvenance.record(blockprint$bufferFor(quad), quad.getSprite());
    }
}
