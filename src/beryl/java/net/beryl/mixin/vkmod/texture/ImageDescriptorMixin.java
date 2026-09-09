package net.beryl.mixin.vkmod.texture;

import net.beryl.render.RenderingPipeline;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ImageDescriptor.class)
public class ImageDescriptorMixin {
   @Redirect(method = "getImageView", at = @At(value = "INVOKE", target = "Lnet/vulkanmod/vulkan/texture/VulkanImage;getImageView()J"))
   private long redirectImageView(VulkanImage image) {
      return RenderingPipeline.useSrgbColors() && image.format == 37 ? image.getImageView(43) : image.getImageView();
   }
}
