package net.vulkanmod.mixin.render;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Camera.class)
public abstract class CameraMixin {
    // Perspective setup moved from GameRenderer to Camera in 26.1.
    @ModifyVariable(method = "setupPerspective", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private float infiniteFarPlane(float farPlane) {
        return Float.POSITIVE_INFINITY;
    }
}
