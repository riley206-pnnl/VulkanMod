package net.vulkanmod.mixin.render.weather;

import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.render.shader.PipelineManager;
import net.vulkanmod.shaders.PackCompositePipeline;
import net.vulkanmod.shaders.PackWeatherRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererM {
    @Inject(method = "render(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/state/level/WeatherRenderState;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void vulkanmod$renderPackWeather(Vec3 camera, WeatherRenderState state,
                                               LevelRenderState levelState, CallbackInfo ci) {
        if (PackCompositePipeline.isActive() && PipelineManager.getPackWeatherShader() != null
                && PackWeatherRenderer.render(camera, state)) {
            ci.cancel();
        }
    }
}
