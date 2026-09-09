package net.beryl.mixin.shader;

import net.beryl.render.RenderingPipeline;
import net.beryl.render.ShaderMainPass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.vulkanmod.vulkan.memory.MemoryManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftM {
   @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"))
   private void inj1(Screen screen, boolean bl, boolean bl2, CallbackInfo ci) {
      MemoryManager.getInstance().addFrameOp(RenderingPipeline::clearResources);
      ShaderMainPass.PASS.setEarlyRenderPass(true);
   }
}
