package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.GameRenderer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    // Capture the completed world projection before upload to the GPU-only uniform buffer.
    // The second upload in renderLevel is the hand/HUD projection, so only intercept the first.
    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;",
            ordinal = 0), index = 0, require = 1)
    private Matrix4f captureWorldProjection(Matrix4f projection) {
        VRenderSystem.applyProjectionMatrix(projection);
        return projection;
    }
}
