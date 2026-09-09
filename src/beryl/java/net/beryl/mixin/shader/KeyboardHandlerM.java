package net.beryl.mixin.shader;

import com.mojang.blaze3d.platform.InputConstants;
import net.beryl.BerylMod;
import net.beryl.render.RenderingPipeline;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public class KeyboardHandlerM {
   @Inject(
      method = "keyPress",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/KeyMapping;set(Lcom/mojang/blaze3d/platform/InputConstants$Key;Z)V",
         ordinal = 1,
         shift = Shift.AFTER
      )
   )
   private void injOverlayToggle(long l, int i, KeyEvent keyEvent, CallbackInfo ci) {
      if (InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), 342) && keyEvent.key() == 298) {
         RenderingPipeline.scheduleReload();
      }

      BerylMod.handleKeys();
   }
}
