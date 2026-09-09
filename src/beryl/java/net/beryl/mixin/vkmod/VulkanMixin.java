package net.beryl.mixin.vkmod;

import net.vulkanmod.vulkan.Vulkan;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Vulkan.class)
public class VulkanMixin {
   @Shadow
   public static boolean use24BitsDepthFormat;

   @Inject(method = "initVulkan", at = @At("HEAD"))
   private static void no24BitDpeth(long window, CallbackInfo ci) {
      use24BitsDepthFormat = false;
   }
}
