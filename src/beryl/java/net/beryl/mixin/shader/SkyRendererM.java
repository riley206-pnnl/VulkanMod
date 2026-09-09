package net.beryl.mixin.shader;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.RenderSystem.AutoStorageIndexBuffer;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.beryl.render.RenderingPipeline;
import net.beryl.render.SkyRenderer2;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.level.MoonPhase;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.util.ColorUtil.ARGB;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRenderer.class)
public abstract class SkyRendererM {
   @Shadow
   @Final
   private AutoStorageIndexBuffer quadIndices;
   @Shadow
   @Final
   private GpuBuffer moonBuffer;
   @Shadow
   @Final
   private GpuBuffer bottomSkyBuffer;
   @Shadow
   @Final
   private TextureAtlas celestialsAtlas;
   @Shadow
   @Final
   private GpuBuffer sunBuffer;
   @Unique
   SkyRenderer2 skyRenderer2 = new SkyRenderer2();

   @Shadow
   protected abstract void renderStars(float var1, PoseStack var2);

   @Shadow
   protected abstract void renderMoon(MoonPhase var1, float var2, PoseStack var3);

   @Shadow
   protected abstract void renderSun(float var1, PoseStack var2);

   @Inject(method = "renderSkyDisc", at = @At("HEAD"), cancellable = true)
   public void renderSkyDisc(int skyColor, CallbackInfo ci) {
      if (RenderingPipeline.isUsingShaderPipeline()) {
         float r = ARGB.unpackR(skyColor);
         float g = ARGB.unpackG(skyColor);
         float b = ARGB.unpackB(skyColor);
         this.skyRenderer2.renderSkyDisc(r, g, b);
         ci.cancel();
      }
   }

   @Inject(method = "renderSunMoonAndStars", at = @At("HEAD"), cancellable = true)
   public void renderSunMoonAndStars(PoseStack poseStack, float sunAngle, float moonAngle, float h, MoonPhase moonPhase, float i, float j, CallbackInfo ci) {
      if (RenderingPipeline.isUsingShaderPipeline()) {
         poseStack.pushPose();
         poseStack.mulPose(Axis.YP.rotationDegrees(-90.0F));
         poseStack.mulPose(Axis.ZP.rotationDegrees(-RenderingPipeline.sunPathRotation));
         if (RenderingPipeline.texturedSun()) {
            poseStack.pushPose();
            poseStack.mulPose(Axis.XP.rotation(sunAngle));
            float f1 = (float)Math.sin(sunAngle);
            f1 *= 1.05F;
            f1 = Math.clamp(f1, -1.0F, 1.0F);
            f1 *= RenderingPipeline.sunPathRotation;
            poseStack.mulPose(Axis.YP.rotationDegrees(f1));
            this.renderSun(i, poseStack);
            poseStack.popPose();
         }

         poseStack.pushPose();
         poseStack.mulPose(Axis.XP.rotation(moonAngle));
         float f1 = (float)Math.sin(moonAngle);
         f1 *= 1.05F;
         f1 = Math.clamp(f1, -1.0F, 1.0F);
         f1 *= RenderingPipeline.sunPathRotation;
         poseStack.mulPose(Axis.YP.rotationDegrees(f1));
         this.renderMoon(moonPhase, i, poseStack);
         poseStack.popPose();
         if (j > 0.0F) {
            poseStack.pushPose();
            poseStack.mulPose(Axis.XP.rotation(h));
            this.renderStars(j, poseStack);
            poseStack.popPose();
         }

         poseStack.popPose();
         Renderer.clearAttachments(256);
         ci.cancel();
      }
   }

   @Inject(method = "renderSunriseAndSunset", at = @At("HEAD"), cancellable = true)
   public void renderSunriseAndSunset(PoseStack poseStack, float f, int i, CallbackInfo ci) {
      if (RenderingPipeline.isUsingShaderPipeline()) {
         ci.cancel();
      }
   }

   @Inject(method = "renderSun", at = @At("HEAD"), cancellable = true)
   private void renderSunInj(float f, PoseStack poseStack, CallbackInfo ci) {
      if (RenderingPipeline.isUsingShaderPipeline()) {
         Matrix4fStack matrix4fStack = RenderSystem.getModelViewStack();
         matrix4fStack.pushMatrix();
         matrix4fStack.mul(poseStack.last().pose());
         matrix4fStack.translate(0.0F, 100.0F, 0.0F);
         float scale = 30.0F;
         matrix4fStack.scale(scale, 1.0F, scale);
         float brightness = 10.0F;
         GpuBufferSlice gpuBufferSlice = RenderSystem.getDynamicUniforms()
            .writeTransform(matrix4fStack, new Vector4f(brightness, brightness, brightness, f), new Vector3f(), new Matrix4f());
         GpuTextureView gpuTextureView = Minecraft.getInstance().getMainRenderTarget().getColorTextureView();
         GpuTextureView gpuTextureView2 = Minecraft.getInstance().getMainRenderTarget().getDepthTextureView();
         GpuBuffer gpuBuffer = this.quadIndices.getBuffer(6);
         RenderPass renderPass = RenderSystem.getDevice()
            .createCommandEncoder()
            .createRenderPass(() -> "Sky sun", gpuTextureView, OptionalInt.empty(), gpuTextureView2, OptionalDouble.empty());

         try {
            renderPass.setPipeline(RenderPipelines.CELESTIAL);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", gpuBufferSlice);
            renderPass.bindTexture("Sampler0", this.celestialsAtlas.getTextureView(), this.celestialsAtlas.getSampler());
            renderPass.setVertexBuffer(0, this.sunBuffer);
            renderPass.setIndexBuffer(gpuBuffer, this.quadIndices.type());
            renderPass.drawIndexed(0, 0, 6, 1);
         } catch (Throwable var15) {
            if (renderPass != null) {
               try {
                  renderPass.close();
               } catch (Throwable var14) {
                  var15.addSuppressed(var14);
               }
            }

            throw var15;
         }

         if (renderPass != null) {
            renderPass.close();
         }

         matrix4fStack.popMatrix();
         ci.cancel();
      }
   }

   @Inject(method = "renderMoon", at = @At("HEAD"), cancellable = true)
   private void renderMoonInj(MoonPhase moonPhase, float f, PoseStack poseStack, CallbackInfo ci) {
      if (RenderingPipeline.isUsingShaderPipeline()) {
         int i = moonPhase.index() * 4;
         Matrix4fStack matrix4fStack = RenderSystem.getModelViewStack();
         matrix4fStack.pushMatrix();
         matrix4fStack.mul(poseStack.last().pose());
         matrix4fStack.translate(0.0F, 100.0F, 0.0F);
         matrix4fStack.scale(20.0F, 1.0F, 20.0F);
         GpuBufferSlice gpuBufferSlice = RenderSystem.getDynamicUniforms()
            .writeTransform(matrix4fStack, new Vector4f(1.0F, 1.0F, 1.0F, f), new Vector3f(), new Matrix4f());
         GpuTextureView gpuTextureView = Minecraft.getInstance().getMainRenderTarget().getColorTextureView();
         GpuTextureView gpuTextureView2 = Minecraft.getInstance().getMainRenderTarget().getDepthTextureView();
         GpuBuffer gpuBuffer = this.quadIndices.getBuffer(6);
         RenderPass renderPass = RenderSystem.getDevice()
            .createCommandEncoder()
            .createRenderPass(() -> "Sky moon", gpuTextureView, OptionalInt.empty(), gpuTextureView2, OptionalDouble.empty());

         try {
            renderPass.setPipeline(RenderPipelines.CELESTIAL);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", gpuBufferSlice);
            renderPass.bindTexture("Sampler0", this.celestialsAtlas.getTextureView(), this.celestialsAtlas.getSampler());
            renderPass.setVertexBuffer(0, this.moonBuffer);
            renderPass.setIndexBuffer(gpuBuffer, this.quadIndices.type());
            renderPass.drawIndexed(i, 0, 6, 1);
         } catch (Throwable var15) {
            if (renderPass != null) {
               try {
                  renderPass.close();
               } catch (Throwable var14) {
                  var15.addSuppressed(var14);
               }
            }

            throw var15;
         }

         if (renderPass != null) {
            renderPass.close();
         }

         matrix4fStack.popMatrix();
         ci.cancel();
      }
   }

   @Overwrite
   public void renderDarkDisc() {
      if (RenderingPipeline.isUsingShaderPipeline()) {
         Matrix4fStack matrix4fStack = RenderSystem.getModelViewStack();
         matrix4fStack.pushMatrix();
         matrix4fStack.translate(0.0F, 12.0F, 0.0F);
         GpuBufferSlice gpuBufferSlice = RenderSystem.getDynamicUniforms()
            .writeTransform(matrix4fStack, new Vector4f(0.0F, 0.0F, 0.0F, 1.0F), new Vector3f(), new Matrix4f());
         GpuTextureView gpuTextureView = Minecraft.getInstance().getMainRenderTarget().getColorTextureView();
         GpuTextureView gpuTextureView2 = Minecraft.getInstance().getMainRenderTarget().getDepthTextureView();
         RenderPass renderPass = RenderSystem.getDevice()
            .createCommandEncoder()
            .createRenderPass(() -> "Sky dark", gpuTextureView, OptionalInt.empty(), gpuTextureView2, OptionalDouble.empty());

         try {
            renderPass.setPipeline(RenderPipelines.SKY);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", gpuBufferSlice);
            renderPass.setVertexBuffer(0, this.bottomSkyBuffer);
            renderPass.draw(0, 10);
         } catch (Throwable var9) {
            if (renderPass != null) {
               try {
                  renderPass.close();
               } catch (Throwable var8) {
                  var9.addSuppressed(var8);
               }
            }

            throw var9;
         }

         if (renderPass != null) {
            renderPass.close();
         }

         matrix4fStack.popMatrix();
      }
   }

   @Inject(method = "renderStars", at = @At("HEAD"), cancellable = true)
   private void renderStars(float starBrightness, PoseStack poseStack, CallbackInfo ci) {
      if (RenderingPipeline.isUsingShaderPipeline()) {
         this.skyRenderer2.renderStars(starBrightness, poseStack);
         ci.cancel();
      }
   }
}
