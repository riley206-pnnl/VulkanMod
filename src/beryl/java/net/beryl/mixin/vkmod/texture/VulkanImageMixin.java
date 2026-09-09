package net.beryl.mixin.vkmod.texture;

import net.vulkanmod.vulkan.texture.VulkanImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(VulkanImage.class)
public class VulkanImageMixin {
   @ModifyVariable(method = "createImage", at = @At("STORE"), name = "flags")
   private int addFlag(int flags) {
      int var2;
      return var2 = flags | 8;
   }
}
