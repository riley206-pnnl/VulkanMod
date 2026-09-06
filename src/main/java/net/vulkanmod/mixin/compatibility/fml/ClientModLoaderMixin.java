package net.vulkanmod.mixin.compatibility.fml;

import net.neoforged.fml.loading.EarlyLoadingScreenController;
import net.neoforged.neoforge.client.loading.ClientModLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientModLoader.class, remap = false)
public class ClientModLoaderMixin {
    @Shadow private static EarlyLoadingScreenController earlyLoadingScreen;

    @Inject(method = "finish", at = @At("HEAD"))
    private static void stopEarlyWindowTicks(CallbackInfo ci) {
        // WindowMixin has closed the OpenGL renderer and replaced its window.
        // Do not tick that controller or poll input before Minecraft finishes initializing.
        earlyLoadingScreen = null;
    }
}
