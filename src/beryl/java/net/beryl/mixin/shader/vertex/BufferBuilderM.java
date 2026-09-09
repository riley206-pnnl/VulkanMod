package net.beryl.mixin.shader.vertex;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.beryl.render.RenderingPipeline;
import net.beryl.render.ShaderRendererResources;
import net.vulkanmod.vulkan.util.ColorUtil;
import net.vulkanmod.vulkan.util.ColorUtil.ARGB;
import net.vulkanmod.vulkan.util.ColorUtil.RGBA;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(BufferBuilder.class)
public abstract class BufferBuilderM {
   @Shadow
   protected abstract long beginElement(VertexFormatElement var1);

   @ModifyVariable(method = "<init>", at = @At("STORE"), name = "isBlockFormat")
   private boolean redirectFastFormat(boolean isBlockFormat, @Local(name = "format") VertexFormat format) {
      return isBlockFormat || format == ShaderRendererResources.BLOCK_NORMAL;
   }

   @Overwrite
   public VertexConsumer setColor(int i) {
      long l = this.beginElement(VertexFormatElement.COLOR);
      if (l != -1L) {
         float r = ARGB.unpackR(i);
         float g = ARGB.unpackG(i);
         float b = ARGB.unpackB(i);
         float a = ARGB.unpackA(i);
         if (RenderingPipeline.useSrgbColors()) {
            r = ColorUtil.gamma(r);
            g = ColorUtil.gamma(g);
            b = ColorUtil.gamma(b);
         }

         int color = RGBA.pack(r, g, b, a);
         MemoryUtil.memPutInt(l, color);
      }

      return (BufferBuilder)(Object)this;
   }
}
