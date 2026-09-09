package net.beryl.mixin.shader.accessor;

import net.minecraft.client.renderer.entity.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntityRenderer.class)
public interface EntityRendererAccessor {
   @Accessor
   float getShadowRadius();

   @Accessor
   float getShadowStrength();
}
