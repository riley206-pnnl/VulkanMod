package net.beryl.mixin.shader.vertex;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(VertexConsumer.class)
public interface VertexConsumerM {
   @Shadow
   VertexConsumer setColor(int var1, int var2, int var3, int var4);

   @Overwrite
   default VertexConsumer setColor(float r, float g, float b, float a) {
      return this.setColor((int)(r * 255.0F), (int)(g * 255.0F), (int)(b * 255.0F), (int)(a * 255.0F));
   }

   private static float gamma(float f) {
      return (float)Math.pow(f, 2.2);
   }
}
