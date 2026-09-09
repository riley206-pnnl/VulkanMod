package net.beryl.mixin.shader;

import net.beryl.render.RenderingPipeline;
import net.minecraft.core.Direction;
import net.minecraft.world.level.CardinalLighting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CardinalLighting.class)
public class CardinalLightingMixin {
   @Inject(method = "byFace", at = @At("HEAD"), cancellable = true)
   public void byFace(Direction direction, CallbackInfoReturnable<Float> cir) {
      if (RenderingPipeline.isUsingShaderPipeline()) {
         cir.setReturnValue(1.0F);
      }
   }
}
