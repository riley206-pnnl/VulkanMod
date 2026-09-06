package net.vulkanmod.mixin.render;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FogRenderer.class)
public class FogRendererMixin {

    @Inject(method = "setupFog", at = @At("RETURN"))
    private void onSetupFog(Camera camera, int renderDistance, DeltaTracker deltaTracker, float darkenWorldAmount,
                            ClientLevel level, CallbackInfoReturnable<FogData> cir) {
        FogData fog = cir.getReturnValue();
        VRenderSystem.fogData = fog;
        Vector4f color = fog.color;
        VRenderSystem.setShaderFogColor(color.x(), color.y(), color.z(), color.w());
    }
}
