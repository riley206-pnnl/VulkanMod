package net.beryl.mixin.vkmod.clouds;

import net.beryl.render.RenderingPipeline;
import net.beryl.render.clouds.BerylCloudRenderer;
import net.vulkanmod.render.sky.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CloudRenderer.class)
public class CloudRendererMixin {
   @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
   public void renderClouds(float cloudHeight, int cloudColor, double camX, double camY, double camZ, long gameTime, float partialTicks, CallbackInfo ci) {
      if (RenderingPipeline.isUsingShaderPipeline()) {
         RenderingPipeline.getCloudRenderer().renderClouds(cloudHeight, cloudColor, camX, camY, camZ, gameTime, partialTicks);
         ci.cancel();
      }
   }

   @Inject(method = "resetBuffer", at = @At("HEAD"), cancellable = true)
   public void resetBuffer(CallbackInfo ci) {
      if (RenderingPipeline.isUsingShaderPipeline()) {
         BerylCloudRenderer cloudRenderer = RenderingPipeline.getCloudRenderer();
         if (cloudRenderer != null) {
            RenderingPipeline.getCloudRenderer().resetBuffer();
         }

         ci.cancel();
      }
   }

   @Inject(method = "loadTexture", at = @At("HEAD"))
   public void loadTexture(CallbackInfo ci) {
      if (RenderingPipeline.isUsingShaderPipeline() && RenderingPipeline.getCloudRenderer() != null) {
         RenderingPipeline.getCloudRenderer().loadTexture();
      }
   }
}
