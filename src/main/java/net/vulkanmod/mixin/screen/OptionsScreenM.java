package net.vulkanmod.mixin.screen;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.VOptionScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.function.Supplier;

@Mixin(OptionsScreen.class)
public abstract class OptionsScreenM extends Screen {

    protected OptionsScreenM(Component title) {
        super(title);
    }

    @ModifyArg(
        method = "init",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/options/OptionsScreen;openScreenButton(Lnet/minecraft/network/chat/Component;Ljava/util/function/Supplier;)Lnet/minecraft/client/gui/components/Button;"
        ),
        index = 1
    )
    private Supplier<Screen> redirectVideoScreen(Supplier<Screen> supplier) {
        return () -> {
            Screen s = supplier.get();
            if (s instanceof VideoSettingsScreen) {
                return new VOptionScreen(Component.literal("Video Settings"), this);
            }
            return s;
        };
    }
}
