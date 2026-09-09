package net.beryl.render;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import net.beryl.BerylMod;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.Initializer;
import net.vulkanmod.interfaces.FrustumMixed;
import net.vulkanmod.render.chunk.ChunkArea;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.render.chunk.frustum.VFrustum;
import net.vulkanmod.render.chunk.util.AreaSetQueue;
import net.vulkanmod.render.chunk.util.ResettableQueue;
import net.vulkanmod.render.chunk.util.StaticQueue;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.render.profiling.Profiler;
import net.vulkanmod.render.shader.PipelineManager;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.memory.buffer.IndexBuffer;
import net.vulkanmod.vulkan.memory.buffer.IndirectBuffer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.texture.ImageUtil;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

public class ShadowMap {
   private static final boolean DEBUG_SHADOWS = Boolean.getBoolean("beryl.debugShadows");
   private long lastDebugLog;
   private final Matrix4f shadowView = new Matrix4f();
   private final Matrix4f shadowViewProjection = new Matrix4f();
   private final Minecraft minecraft;
   private final EntityRenderDispatcher entityRenderDispatcher;
   private final BlockEntityRenderDispatcher blockEntityRenderDispatcher;
   private final RenderBuffers renderBuffers;
   private ClientLevel level;
   private final LevelRenderState levelRenderState = new LevelRenderState();
   private final FeatureRenderDispatcher featureRenderDispatcher;
   private final SubmitNodeStorage submitNodeStorage;
   private Vector3f relativeLightPos = new Vector3f();
   private Vector3d lightPos = new Vector3d();
   private boolean captureFrustum;
   private Frustum capturedFrustum;
   private Frustum frustum;
   private boolean needsUpdate = true;
   private double camX;
   private double camY;
   private double camZ;
   private ShadowMapSectionGraph sectionGraph;
   private boolean graphNeedsUpdate;
   private final ResettableQueue<RenderSection> chunkQueue = new ResettableQueue();
   private AreaSetQueue chunkAreaQueue;
   private short lastFrame = 0;
   IndirectBuffer[] indirectBuffers;
   private final Reference2ReferenceOpenHashMap<ChunkArea, StaticQueue<RenderSection>> areaQueueMap = new Reference2ReferenceOpenHashMap();

   public ShadowMap(
      EntityRenderDispatcher entityRenderDispatcher,
      BlockEntityRenderDispatcher blockEntityRenderDispatcher,
      RenderBuffers renderBuffers,
      LevelRenderState levelRenderState,
      FeatureRenderDispatcher featureRenderDispatcher
   ) {
      this.minecraft = Minecraft.getInstance();
      this.entityRenderDispatcher = this.minecraft.getEntityRenderDispatcher();
      this.blockEntityRenderDispatcher = this.minecraft.getBlockEntityRenderDispatcher();
      this.renderBuffers = renderBuffers;
      this.featureRenderDispatcher = featureRenderDispatcher;
      this.submitNodeStorage = featureRenderDispatcher.getSubmitNodeStorage();
      this.allChanged();
      this.allocateIndirectBuffers();
   }

   public void allocateIndirectBuffers() {
      if (this.indirectBuffers == null || this.indirectBuffers.length != Renderer.getFramesNum()) {
         if (this.indirectBuffers != null) {
            Arrays.stream(this.indirectBuffers).forEach(Buffer::scheduleFree);
         }

         this.indirectBuffers = new IndirectBuffer[Renderer.getFramesNum()];

         for (int i = 0; i < this.indirectBuffers.length; i++) {
            this.indirectBuffers[i] = new IndirectBuffer(1000000, MemoryTypes.HOST_MEM);
         }
      }
   }

   public void allChanged() {
      WorldRenderer worldRenderer = WorldRenderer.getInstance();
      this.chunkAreaQueue = new AreaSetQueue(worldRenderer.getChunkAreaManager().size);
      this.sectionGraph = new ShadowMapSectionGraph(WorldRenderer.getLevel(), worldRenderer.getSectionGrid(), worldRenderer.getTaskDispatcher());
   }

   public void renderShadowMap(Camera camera, PoseStack poseStack, Matrix4f projection, DeltaTracker deltaTracker) {
      Profiler profiler = Profiler.getMainProfiler();
      this.setupRenderer(camera, poseStack, projection, deltaTracker);
      this.indirectBuffers[Renderer.getCurrentFrame()].reset();
      profiler.pop();
      profiler.push("render shadow map");
      Renderer.getInstance().beginRenderPass(RenderingPipeline.shadowRenderPass, RenderingPipeline.shadowFramebuffer);
      VRenderSystem.setClearColor(1.0F, 1.0F, 1.0F, 1.0F);
      VRenderSystem.clearDepth(1.0);
      Renderer.clearAttachments(16640, RenderingPipeline.shadowMapResolution, RenderingPipeline.shadowMapResolution);
      VRenderSystem.enableDepthTest();
      VRenderSystem.glDepthFun(515);
      this.renderLayer(TerrainRenderType.SOLID, this.camX, this.camY, this.camZ, poseStack.last().pose(), projection);
      this.renderLayer(TerrainRenderType.CUTOUT, this.camX, this.camY, this.camZ, poseStack.last().pose(), projection);
      profiler.pop();
      BufferSource bufferSource = this.renderBuffers.bufferSource();
      VRenderSystem.applyProjectionMatrix(projection);
      RenderSystem.getModelViewStack().pushMatrix().set(poseStack.last().pose());
      VRenderSystem.applyModelViewMatrix(RenderSystem.getModelViewMatrix());
      poseStack.pushPose();
      poseStack.last().pose().identity();
      this.extractVisibleEntities(camera, this.frustum, deltaTracker, this.levelRenderState);
      this.submitEntities(poseStack, this.levelRenderState, this.submitNodeStorage);
      this.renderBlockEntities(poseStack, this.levelRenderState, this.submitNodeStorage, deltaTracker.getGameTimeDeltaPartialTick(false));
      this.featureRenderDispatcher.renderAllFeatures();
      bufferSource.endBatch();
      poseStack.popPose();
      if (RenderingPipeline.coloredShadows()) {
         VulkanImage srcImage = RenderingPipeline.shadowFramebuffer.getDepthAttachment();
         VulkanImage dstImage = RenderingPipeline.shadowSolidDepthImage;
         Renderer.getInstance().endRenderPass();
         VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
         MemoryStack stack = MemoryStack.stackGet();
         srcImage.transitionImageLayout(stack, commandBuffer, 6);
         dstImage.transitionImageLayout(stack, commandBuffer, 7);
         ImageUtil.blitFramebuffer(srcImage, dstImage);
         srcImage.transitionImageLayout(stack, commandBuffer, 3);
         dstImage.transitionImageLayout(stack, commandBuffer, 5);
         VRenderSystem.disableBlend();
         Renderer.getInstance().beginRenderPass(RenderingPipeline.shadowRenderPassLoad, RenderingPipeline.shadowFramebuffer);
         this.renderLayer(TerrainRenderType.TRANSLUCENT, this.camX, this.camY, this.camZ, poseStack.last().pose(), projection);
      }

      RenderSystem.getModelViewStack().popMatrix();
      VRenderSystem.applyModelViewMatrix(RenderSystem.getModelViewStack());
   }

   private void setupRenderer(Camera camera, PoseStack poseStack, Matrix4f projection, DeltaTracker deltaTracker) {
      this.levelRenderState.reset();
      this.submitNodeStorage.clear();
      this.levelRenderState.cameraRenderState.pos = camera.position();
      this.levelRenderState.cameraRenderState.blockPos = camera.blockPosition();
      this.level = WorldRenderer.getLevel();
      Vec3 cameraPos = camera.position();
      Profiler profiler = Profiler.getMainProfiler();
      profiler.push("setup shadow queue");
      this.camX = cameraPos.x();
      this.camY = cameraPos.y();
      this.camZ = cameraPos.z();
      double posTranslationX = this.camX - Math.floor(this.camX / 2.0) * 2.0;
      double posTranslationY = this.camY - Math.floor(this.camY / 2.0) * 2.0;
      double posTranslationZ = this.camZ - Math.floor(this.camZ / 2.0) * 2.0;
      float xDir = RenderingPipeline.lightDirWS.x();
      float yDir = RenderingPipeline.lightDirWS.y();
      float zDir = RenderingPipeline.lightDirWS.z();
      float dist = BerylMod.CONFIG.shadowRenderDistance;
      dist = Math.min(dist, 24.0F);
      int xOffset = (int)(xDir * dist * 16.0F);
      int yOffset = 64;
      int zOffset = (int)(zDir * dist * 16.0F);
      double x = cameraPos.x + xOffset;
      double y = cameraPos.y + yOffset;
      double z = cameraPos.z + zOffset;
      this.lightPos.set(x, y, z);
      Matrix4f view = shadowView.set(RenderingPipeline.lightView);
      view.translate((float)posTranslationX, (float)posTranslationY, (float)posTranslationZ);
      Matrix4f VPLight = shadowViewProjection.set(RenderingPipeline.lightProjection).mul(view);
      VPLight.get(RenderingPipeline.LightSpaceMatrixBuffer.buffer);
      poseStack.last().pose().set(view);
      this.frustum = this.prepareCullFrustum(poseStack.last().pose(), projection, this.lightPos);
      VFrustum vFrustum = ((FrustumMixed)this.frustum).customFrustum();
      vFrustum.calculateFrustum(poseStack.last().pose(), projection);
      vFrustum.setCamOffset(this.camX, this.camY, this.camZ);
      this.sectionGraph.update(camera, vFrustum, false);
   }

   private void renderLayer(TerrainRenderType renderType, double camX, double camY, double camZ, Matrix4f modelView, Matrix4f projection) {
      // With merged opaque terrain there is no SOLID buffer to submit.
      if (renderType == TerrainRenderType.SOLID && Initializer.CONFIG.uniqueOpaqueLayer) return;
      boolean isTranslucent = renderType == TerrainRenderType.TRANSLUCENT;
      GlStateManager._disableCull();
      VRenderSystem.disableCull();
      VRenderSystem.cullMode = org.lwjgl.vulkan.VK10.VK_CULL_MODE_NONE;
      VRenderSystem.glDepthFun(515);
      GlStateManager._enableDepthTest();
      GlStateManager._depthMask(true);
      GlStateManager._colorMask(15);
      GlStateManager._disablePolygonOffset();
      VRenderSystem.setPolygonModeGL(6914);
      VRenderSystem.applyMVP(modelView, projection);
      VRenderSystem.setPrimitiveTopologyGL(4);
      Renderer renderer = Renderer.getInstance();
      GraphicsPipeline pipeline = PipelineManager.getTerrainShader(renderType);
      renderer.bindGraphicsPipeline(pipeline);
      TextureManager textureManager = Minecraft.getInstance().getTextureManager();
      AbstractTexture blockAtlasTexture = textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS);
      GpuTextureView texView = blockAtlasTexture.getTextureView();
      boolean useAnisotropy = this.minecraft.options.textureFiltering().get() == TextureFilteringMethod.ANISOTROPIC;
      int maxAnisotropy = this.minecraft.options.maxAnisotropyValue();
      VkGpuTexture texture = (VkGpuTexture)texView.texture();
      long sampler = SamplerManager.getSampler(true, false, 0, false, -1);
      texture.getVulkanImage().setSampler(sampler);
      VRenderSystem.setShaderTexture(0, blockAtlasTexture.getTextureView());
      VRenderSystem.setShaderTexture(2, Minecraft.getInstance().gameRenderer.lightmap());
      VTextureSelector.bindShaderTextures(pipeline);
      long currentTimeMs = System.currentTimeMillis();
      float fadeTime = ((Double)Minecraft.getInstance().options.chunkSectionFadeInTime().get()).floatValue();
      int fadeTimeMs = (int)(fadeTime * 1000.0F);
      float fadeTimeInv = fadeTime > 0.0F ? 1.0F / (fadeTime * 1000.0F) : 1.0F;
      IndexBuffer indexBuffer = Renderer.getDrawer().getQuadsIndexBuffer().getIndexBuffer();
      Renderer.getDrawer().bindIndexBuffer(Renderer.getCommandBuffer(), indexBuffer, indexBuffer.indexType.value);
      int currentFrame = Renderer.getCurrentFrame();
      UBO sectionData = pipeline.getUBO(2);
      if (sectionData != null) {
         sectionData.setUseGlobalBuffer(false);
      }

      Set<TerrainRenderType> allowedRenderTypes = TerrainRenderType.SEMI_COMPACT_RENDER_TYPES;
      if (allowedRenderTypes.contains(renderType)) {
         renderType.setCutoutUniform();

         int areaCount = 0;
         int drawnCount = 0;
         for (var areas = this.sectionGraph.getChunkAreaQueue().iterator(); areas.hasNext();) {
            ChunkArea chunkArea = areas.next();
            StaticQueue<RenderSection> queue = chunkArea.shadowSectionQueue;
            DrawBuffers drawBuffers = chunkArea.getDrawBuffers();
            areaCount++;
            if (drawBuffers.getAreaBuffer(renderType) != null && queue.size() > 0) {
               drawnCount++;
               drawBuffers.bindBuffers(Renderer.getCommandBuffer(), pipeline, renderType, sectionData, camX, camY, camZ, currentTimeMs, fadeTimeMs, fadeTimeInv);
               renderer.uploadAndBindUBOs(pipeline);
               drawBuffers.buildDrawBatchesDirectPack(this.lightPos, queue, renderType, true);
            }
         }
         if (DEBUG_SHADOWS && currentTimeMs - lastDebugLog >= 1000) {
            lastDebugLog = currentTimeMs;
            Initializer.LOGGER.info("[ShadowMap] renderLayer {}: drawn {}/{} areas", renderType, drawnCount, areaCount);
         }
      }

      VRenderSystem.setModelOffset(0.0F, 0.0F, 0.0F);
      renderer.pushConstants(pipeline);

      VRenderSystem.applyModelViewMatrix(RenderSystem.getModelViewMatrix());
   }

   private Frustum prepareCullFrustum(Matrix4f matrix4f, Matrix4f matrix4f2, Vector3d vec3) {
      Frustum frustum = null;
      if (this.capturedFrustum != null && !this.captureFrustum) {
         frustum = this.capturedFrustum;
      } else {
         frustum = new Frustum(matrix4f, matrix4f2);
         frustum.prepare(vec3.x(), vec3.y(), vec3.z());
      }

      if (this.captureFrustum) {
         this.capturedFrustum = frustum;
         this.captureFrustum = false;
      }

      return frustum;
   }

   private void extractVisibleEntities(Camera camera, Frustum frustum, DeltaTracker deltaTracker, LevelRenderState levelRenderState) {
      Vec3 vec3 = camera.position();
      double d = vec3.x();
      double e = vec3.y();
      double f = vec3.z();
      TickRateManager tickRateManager = this.level.tickRateManager();
      Entity.setViewScale(
         Mth.clamp(this.minecraft.options.getEffectiveRenderDistance() / 8.0, 1.0, 2.5) * (Double)this.minecraft.options.entityDistanceScaling().get()
      );

      for (Entity entity : this.level.entitiesForRendering()) {
         if (this.entityRenderDispatcher.shouldRender(entity, frustum, d, e, f) || entity.hasIndirectPassenger(this.minecraft.player)) {
            BlockPos blockPos = entity.blockPosition();
            if (this.level.isOutsideBuildHeight(blockPos.getY()) || WorldRenderer.getInstance().isSectionCompiled(blockPos)) {
               if (entity.tickCount == 0) {
                  entity.xOld = entity.getX();
                  entity.yOld = entity.getY();
                  entity.zOld = entity.getZ();
               }

               float g = deltaTracker.getGameTimeDeltaPartialTick(!tickRateManager.isEntityFrozen(entity));
               EntityRenderState entityRenderState = this.extractEntity(entity, g);
               levelRenderState.entityRenderStates.add(entityRenderState);
            }
         }
      }
   }

   private void submitEntities(PoseStack poseStack, LevelRenderState levelRenderState, SubmitNodeCollector submitNodeCollector) {
      Vec3 vec3 = levelRenderState.cameraRenderState.pos;
      double d = vec3.x();
      double e = vec3.y();
      double f = vec3.z();

      for (EntityRenderState entityRenderState : levelRenderState.entityRenderStates) {
         if (!levelRenderState.haveGlowingEntities) {
            entityRenderState.outlineColor = 0;
         }

         this.entityRenderDispatcher
            .submit(
               entityRenderState,
               levelRenderState.cameraRenderState,
               entityRenderState.x - d,
               entityRenderState.y - e,
               entityRenderState.z - f,
               poseStack,
               submitNodeCollector
            );
      }
   }

   public void renderBlockEntities(PoseStack poseStack, LevelRenderState levelRenderState, SubmitNodeStorage submitNodeStorage, float partialTick) {
      Vec3 vec3 = levelRenderState.cameraRenderState.pos;
      double camX = vec3.x();
      double camY = vec3.y();
      double camZ = vec3.z();

      for (RenderSection renderSection : this.sectionGraph.getBlockEntitiesSections()) {
         List<BlockEntity> list = renderSection.getCompiledSection().getBlockEntities();
         if (!list.isEmpty()) {
            for (BlockEntity blockEntity : list) {
               BlockPos blockPos = blockEntity.getBlockPos();
               CrumblingOverlay crumblingOverlay = null;
               BlockEntityRenderState blockEntityRenderState = this.blockEntityRenderDispatcher
                  .tryExtractRenderState(blockEntity, partialTick, crumblingOverlay);
               if (blockEntityRenderState != null) {
                  levelRenderState.blockEntityRenderStates.add(blockEntityRenderState);
               }
            }
         }
      }

      Iterator<BlockEntity> iterator = this.level.getGloballyRenderedBlockEntities().iterator();

      while (iterator.hasNext()) {
         BlockEntity blockEntity2 = iterator.next();
         if (blockEntity2.isRemoved()) {
            iterator.remove();
         } else {
            BlockEntityRenderState blockEntityRenderState2 = this.blockEntityRenderDispatcher.tryExtractRenderState(blockEntity2, partialTick, null);
            if (blockEntityRenderState2 != null) {
               levelRenderState.blockEntityRenderStates.add(blockEntityRenderState2);
            }
         }
      }

      for (BlockEntityRenderState blockEntityRenderState : levelRenderState.blockEntityRenderStates) {
         BlockPos blockPos = blockEntityRenderState.blockPos;
         float dx = (float)(blockPos.getX() - camX);
         float dy = (float)(blockPos.getY() - camY);
         float dz = (float)(blockPos.getZ() - camZ);
         float distSq = dx * dx + dy * dy + dz * dz;
         if (!(distSq > 1600.0F)) {
            poseStack.pushPose();
            poseStack.translate(dx, dy, dz);
            BlockEntityRenderDispatcher blockEntityRenderDispatcher = this.minecraft.getBlockEntityRenderDispatcher();
            blockEntityRenderDispatcher.submit(blockEntityRenderState, poseStack, submitNodeStorage, levelRenderState.cameraRenderState);
            poseStack.popPose();
         }
      }
   }

   public void setRelativeLightPos(float x, float y, float z) {
      this.relativeLightPos.set(x, y, z);
   }

   private void checkPoseStack(PoseStack poseStack) {
      if (!poseStack.isEmpty()) {
         throw new IllegalStateException("Pose stack not empty");
      }
   }

   private EntityRenderState extractEntity(Entity entity, float f) {
      return this.entityRenderDispatcher.extractEntity(entity, f);
   }
}
