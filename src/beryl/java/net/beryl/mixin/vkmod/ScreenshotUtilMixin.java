package net.beryl.mixin.vkmod;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTexture;
import java.util.function.Consumer;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.ImageUtil;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.util.ColorUtil;
import net.vulkanmod.vulkan.util.ScreenshotUtil;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(ScreenshotUtil.class)
public class ScreenshotUtilMixin {
   @Overwrite
   public static void takeScreenshot(RenderTarget renderTarget, int mipLevel, Consumer<NativeImage> consumer) {
      int width = renderTarget.width;
      int height = renderTarget.height;
      GpuTexture gpuTexture = renderTarget.getColorTexture();
      if (gpuTexture == null) {
         throw new IllegalStateException("Tried to capture screenshot of an incomplete framebuffer");
      } else {
         Renderer.getInstance().flushCmds();
         VulkanImage colorAttachment = Renderer.getInstance().getSwapChain().getColorAttachment();
         NativeImage nativeImage = new NativeImage(width, height, false);
         long ptr = nativeImage.getPointer();
         ImageUtil.downloadTexture(colorAttachment, ptr);

         for (long l = 0L; l < width * height * 4L; l += 4L) {
            int v = MemoryUtil.memGetInt(ptr + l);
            if (Renderer.getInstance().getSwapChain().isBGRAformat) {
               v = ColorUtil.BGRAtoRGBA(v);
            }

            v |= -16777216;
            MemoryUtil.memPutInt(ptr + l, v);
         }

         consumer.accept(nativeImage);
      }
   }
}
