package net.beryl.mixin.vkmod;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.vulkan.texture.SamplerManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public class WorldRendererMixin {
   @Shadow
   private boolean graphNeedsUpdate;

   @Inject(method = "setupRenderer", at = @At(value = "INVOKE", target = "Lnet/vulkanmod/render/chunk/WorldRenderer;graphNeedsUpdate()Z"))
   private void forceGraphUpdate(Camera camera, Frustum frustum, boolean isCapturedFrustum, boolean spectator, CallbackInfo ci) {
      this.graphNeedsUpdate = true;
   }

   @Redirect(method = "renderSectionLayer(Lnet/vulkanmod/render/vertex/TerrainRenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V", at = @At(value = "INVOKE", target = "Lnet/vulkanmod/vulkan/texture/SamplerManager;getTerrainSampler(IZI)J"))
   private long noLinearFiltering(int maxLod, boolean anisotropy, int maxAnisotropy) {
      return net.beryl.render.RenderingPipeline.isUsingShaderPipeline()
         ? SamplerManager.getSampler(false, false, maxLod, anisotropy, maxAnisotropy)
         : SamplerManager.getTerrainSampler(maxLod, anisotropy, maxAnisotropy);
   }
}
