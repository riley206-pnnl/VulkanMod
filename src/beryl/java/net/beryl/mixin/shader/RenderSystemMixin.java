package net.beryl.mixin.shader;

import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import net.vulkanmod.vulkan.VRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderSystem.class)
public class RenderSystemMixin {
   @Inject(method = "bindDefaultUniforms", at = @At("HEAD"))
   private static void setupUniforms(RenderPass renderPass, CallbackInfo ci) {
      VRenderSystem.applyModelViewMatrix(RenderSystem.getModelViewMatrix());
      VRenderSystem.calculateMVP();
   }
}
