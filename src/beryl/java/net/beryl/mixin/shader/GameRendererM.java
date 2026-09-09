package net.beryl.mixin.shader;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.PoseStack;
import net.beryl.render.RenderingPipeline;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.effect.MobEffects;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererM {
   @Shadow
   @Final
   private Minecraft minecraft;
   @Shadow
   @Final
   private Camera mainCamera;
   @Shadow
   private float spinningEffectTime;
   @Shadow
   private float spinningEffectSpeed;
   @Shadow
   @Final
   private FogRenderer fogRenderer;
   @Shadow
   @Final
   private CrossFrameResourcePool resourcePool;
   @Shadow
   @Final
   private RenderBuffers renderBuffers;
   @Shadow
   @Final
   private SubmitNodeStorage submitNodeStorage;
   @Shadow
   @Final
   private ScreenEffectRenderer screenEffectRenderer;
   @Shadow
   @Final
   private FeatureRenderDispatcher featureRenderDispatcher;
   @Shadow
   @Final
   private ProjectionMatrixBuffer levelProjectionMatrixBuffer;
   @Shadow
   @Final
   private GameRenderState gameRenderState;
   @Unique
   private boolean rendering = false;

   @Shadow
   protected abstract boolean shouldRenderBlockOutline();

   @Shadow
   protected abstract void bobHurt(CameraRenderState var1, PoseStack var2);

   @Shadow
   protected abstract void bobView(CameraRenderState var1, PoseStack var2);

   @Shadow
   protected abstract void renderItemInHand(CameraRenderState var1, float var2, Matrix4fc var3);

   @Redirect(method = "renderLevel", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;mul(Lorg/joml/Matrix4fc;)Lorg/joml/Matrix4f;", ordinal = 0))
   private Matrix4f red1(Matrix4f instance, Matrix4fc right) {
      return instance;
   }

   @Redirect(
      method = "renderLevel",
      at = @At(
         value = "INVOKE",
         target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V",
         ordinal = 0
      )
   )
   private void red2(GpuBufferSlice projectionMatrixBuffer, ProjectionType type) {
   }

   @WrapOperation(
      method = "renderLevel",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZLnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;)V"
      )
   )
   private void onRenderLevel(
      LevelRenderer instance,
      GraphicsResourceAllocator resourceAllocator,
      DeltaTracker deltaTracker,
      boolean renderOutline,
      CameraRenderState cameraState,
      Matrix4fc modelViewMatrix,
      GpuBufferSlice terrainFog,
      Vector4f fogColor,
      boolean shouldRenderSky,
      ChunkSectionsToRender chunkSectionsToRender,
      Operation<Void> original,
      @Local PoseStack poseStack,
      @Local LocalPlayer localPlayer
   ) {
      float worldPartialTicks = deltaTracker.getGameTimeDeltaPartialTick(false);
      float cameraEntityPartialTicks = this.mainCamera.getCameraEntityPartialTicks(deltaTracker);
      LocalPlayer player = this.minecraft.player;
      ProfilerFiller profiler = Profiler.get();
      OptionsRenderState optionsState = this.gameRenderState.optionsRenderState;
      profiler.push("matrices");
      Matrix4f projectionMatrix1 = new Matrix4f(cameraState.projectionMatrix);
      PoseStack bobStack = new PoseStack();
      this.bobHurt(cameraState, bobStack);
      if (optionsState.bobView) {
         this.bobView(cameraState, bobStack);
      }

      float screenEffectScale = optionsState.screenEffectScale;
      float portalIntensity = Mth.lerp(worldPartialTicks, player.oPortalEffectIntensity, player.portalEffectIntensity);
      float nauseaIntensity = player.getEffectBlendFactor(MobEffects.NAUSEA, worldPartialTicks);
      float spinningEffectIntensity = Math.max(portalIntensity, nauseaIntensity) * (screenEffectScale * screenEffectScale);
      if (spinningEffectIntensity > 0.0F) {
         float skew = 5.0F / (spinningEffectIntensity * spinningEffectIntensity + 5.0F) - spinningEffectIntensity * 0.04F;
         skew *= skew;
         Vector3f axis = new Vector3f(0.0F, Mth.SQRT_OF_TWO / 2.0F, Mth.SQRT_OF_TWO / 2.0F);
         float angle = (this.spinningEffectTime + worldPartialTicks * this.spinningEffectSpeed) * (float) (Math.PI / 180.0);
         projectionMatrix1.rotate(angle, axis);
         projectionMatrix1.scale(1.0F / skew, 1.0F, 1.0F);
         projectionMatrix1.rotate(-angle, axis);
      }

      RenderingPipeline.projection = projectionMatrix1;
      RenderSystem.setProjectionMatrix(this.levelProjectionMatrixBuffer.getBuffer(RenderingPipeline.projection), ProjectionType.PERSPECTIVE);
      Quaternionf quaternionf = this.mainCamera.rotation().conjugate(new Quaternionf());
      Matrix4f viewMatrix = new Matrix4f().rotate(quaternionf);
      RenderingPipeline.view = new Matrix4f(viewMatrix);
      this.minecraft
         .levelRenderer
         .renderLevel(this.resourcePool, deltaTracker, renderOutline, cameraState, viewMatrix, terrainFog, fogColor, shouldRenderSky, chunkSectionsToRender);
   }

   @Inject(method = "renderLevel", at = @At("RETURN"))
   private void endShaderRender(DeltaTracker deltaTracker, CallbackInfo ci) {
      RenderingPipeline.endRender();
   }

   @Redirect(
      method = "renderLevel",
      at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V")
   )
   private void clearDepth(CommandEncoder instance, GpuTexture depthTexture, double clearDepth) {
      if (!RenderingPipeline.isUsingShaderPipeline()) {
         instance.clearDepthTexture(depthTexture, clearDepth);
         return;
      }
      CameraRenderState cameraState = this.gameRenderState.levelRenderState.cameraRenderState;
      float fov = cameraState.hudFov;
      float aspect = (float)this.minecraft.getWindow().getWidth() / this.minecraft.getWindow().getHeight();
      Matrix4f proj = new Matrix4f().perspective(fov * (float) (Math.PI / 180.0), aspect, 0.05F, 100.0F, true);
      VRenderSystem.applyProjectionMatrix(proj);
      VRenderSystem.calculateMVP();
      Renderer.clearAttachments(256);
   }

   @Redirect(
      method = "renderLevel",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;FLorg/joml/Matrix4fc;)V"
      )
   )
   private void redirectRenderIteminHand(GameRenderer instance, CameraRenderState cameraState, float deltaPartialTick, Matrix4fc modelViewMatrix) {
      if (!RenderingPipeline.isUsingShaderPipeline()) {
         this.renderItemInHand(cameraState, deltaPartialTick, modelViewMatrix);
         return;
      }
      this.renderItemInHand(cameraState, deltaPartialTick, RenderingPipeline.view);
      // Hand vertices include inverse camera rotation; flush with the matching view
      // while the HDR target and isolated hand depth are still active.
      var stack = RenderSystem.getModelViewStack();
      stack.pushMatrix().set(RenderingPipeline.view);
      try {
         this.featureRenderDispatcher.renderAllFeatures();
         this.renderBuffers.bufferSource().endBatch();
      } finally {
         stack.popMatrix();
         VRenderSystem.applyModelViewMatrix(stack);
         VRenderSystem.calculateMVP();
      }
   }
}
