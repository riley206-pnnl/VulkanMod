package net.beryl.mixin.vkmod;

import net.beryl.render.RenderingPipeline;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Renderer.class)
public class RendererMixin {
   @Inject(method = "initRenderer", at = @At("RETURN"))
   private static void init(CallbackInfo ci) {
      RenderingPipeline.preInit();
   }
}
