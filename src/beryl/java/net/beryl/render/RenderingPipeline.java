package net.beryl.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.beryl.BerylMod;
import net.beryl.render.build.ExtTerrainBuilder;
import net.beryl.render.clouds.BerylCloudRenderer;
import net.beryl.render.shader.BerylPipelineConfigs;
import net.beryl.render.util.BlitUtil;
import net.beryl.render.util.SUtil;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.build.thread.ThreadBuilderPack;
import net.vulkanmod.render.engine.VkGpuDevice;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.render.profiling.Profiler;
import net.vulkanmod.render.shader.PipelineManager;
import net.vulkanmod.render.util.MathUtil;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.framebuffer.Framebuffer.Builder;
import net.vulkanmod.vulkan.pass.DefaultMainPass;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.SpirvCompiler;
import net.vulkanmod.vulkan.shader.Uniforms;
import net.vulkanmod.vulkan.texture.SamplerInfo;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.util.ColorUtil;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

public class RenderingPipeline {
   private static final VertexFormat TERRAIN_VERTEX_FORMAT = ShaderRendererResources.EXT_COMPRESSED_TERRAIN_FORMAT;
   static boolean useShaderPipeline = BerylMod.CONFIG.shadersOn;
   static boolean useTexturedSun = BerylMod.CONFIG.texturedSun;
   static boolean waterWaving;
   static boolean SSR_enabled = BerylMod.CONFIG.ssr;
   private static Framebuffer hdrFramebuffer;
   private static Framebuffer finalFramebuffer;
   private static Framebuffer tempFramebuffer;
   private static RenderPass hdrRenderPass1;
   private static RenderPass hdrRenderPass2;
   private static RenderPass tempRenderPass;
   private static boolean init = false;
   private static Minecraft mc;
   static float celestialAngle;
   static float sunAngle;
   static float shadowAngleInterval;
   public static float sunPathRotation = 20.0F;
   static float[] shadowLightPositionVector = new float[4];
   public static Matrix4f projection;
   public static Matrix4f view;
   private static final PoseStack shadowPoseStack = new PoseStack();
   private static final Vector3f lightScratch = new Vector3f();
   private static final Vector3f upScratch = new Vector3f();
   static Matrix4f lightProjection = new Matrix4f();
   public static Matrix4f lightView = new Matrix4f();
   public static Matrix4f lightVP = new Matrix4f();
   public static Vector3f lightPosWS = new Vector3f();
   public static Vector3f lightDirWS = new Vector3f();
   public static Vector3f lightDir = new Vector3f();
   public static MappedBuffer LightSpaceMatrixBuffer = new MappedBuffer(64);
   public static MappedBuffer LightSpaceOffsetBuffer = new MappedBuffer(12);
   public static MappedBuffer CameraPosBuffer = new MappedBuffer(12);
   static MappedBuffer LightDirBuffer = new MappedBuffer(12);
   static MappedBuffer LightColorBuffer = new MappedBuffer(12);
   static MappedBuffer AmbientLightColorBuffer = new MappedBuffer(12);
   static MappedBuffer SkyColorBuffer = new MappedBuffer(16);
   public static MappedBuffer UpVectorBuffer = new MappedBuffer(12);
   private static float GameTime;
   public static float NightMultiplier = 0.0F;
   static float LightIntensity = 1.0F;
   static float LightVisibility = 1.0F;
   static float AmbientLightFactor = 1.0F;
   static float MinAmbientLight = 0.0F;
   static float FogFactor = 0.0F;
   static float BloomStrength = 0.04F;
   private static RenderingStage renderingStage = RenderingStage.UNDEFINED;
   private static ShadowMap shadowMap;
   static int shadowMapResolution = BerylMod.CONFIG.shadowResolution;
   static float orthoMatrixHalfLength = 160.0F;
   static float ShadowTexelSize = 0.0F;
   static float WaterAbsorption = 0.15F;
   static float shadowBias = 0.0F;
   static float shadowDistortion = 1.0F;
   static long shadowSampler;
   static boolean coloredShadows = false;
   static Framebuffer shadowFramebuffer;
   static VulkanImage shadowSolidDepthImage;
   static GpuTextureView shadowTextureView;
   static GpuTextureView shadowDepthTextureView;
   static RenderPass shadowRenderPass;
   static RenderPass shadowRenderPassLoad;
   private static BerylCloudRenderer cloudRenderer;
   private static GraphicsPipeline finalShader;
   private static GraphicsPipeline terrainShader;
   private static GraphicsPipeline shadowShader;
   private static GraphicsPipeline entityShadowShader;
   private static GraphicsPipeline translucentShader;
   private static GraphicsPipeline translucentShader2;
   private static GraphicsPipeline entityShaderPipeline;
   private static GraphicsPipeline blockShaderPipeline;
   private static GraphicsPipeline particleShaderPipeline;
   private static GraphicsPipeline blitShader;
   private static GraphicsPipeline blitDepthShader;
   private static GraphicsPipeline blitDepthTriShader;
   private static GraphicsPipeline skyShader;
   private static GraphicsPipeline cloudShader;
   private static GraphicsPipeline starsShader;
   private static GraphicsPipeline currentShader;
   private static Bloom bloom;
   private static SimpleTexture NoiseTexture;
   private static ShaderMainPass shaderMainPass;
   private static boolean srgbColors = false;
   private static boolean reloadRequested;
   public static float handMaterial;
   private static float rainStrength;

   public static void toggleShaderPipeline() {
      BerylMod.CONFIG.shadersOn = !BerylMod.CONFIG.shadersOn;
      setUseShaderPipeline(BerylMod.CONFIG.shadersOn);
   }

   public static void setUseShaderPipeline(boolean b) {
      useShaderPipeline = b;
      if (useShaderPipeline) {
         PipelineManager.setTerrainVertexFormat(TERRAIN_VERTEX_FORMAT);
         ThreadBuilderPack.setTerrainBuilderConstructor(renderType -> new ExtTerrainBuilder(TerrainRenderType.getLayer(renderType).bufferSize()));
         CustomVertexFormat.setPositionOffset(TERRAIN_VERTEX_FORMAT == ShaderRendererResources.EXT_COMPRESSED_TERRAIN_FORMAT ? 4.0F : 0.0F);
         Renderer.getInstance().setMainPass(shaderMainPass);
         if (mc.getWindow() != null
            && (
               mc.getWindow().getWidth() != shaderMainPass.hdrFinalFramebuffer.getWidth()
                  || mc.getWindow().getHeight() != shaderMainPass.hdrFinalFramebuffer.getHeight()
            )) {
            resizeFramebuffers();
         }

         reloadRequested = true;
      } else {
         PipelineManager.setTerrainVertexFormat(CustomVertexFormat.COMPRESSED_TERRAIN);
         ThreadBuilderPack.defaultTerrainBuilderConstructor();
         CustomVertexFormat.setPositionOffset(4.0F);
         PipelineManager.setDefaultTerrainShaderGetter();
         Renderer.getInstance().setMainPass(DefaultMainPass.create());
      }

      if (mc.levelRenderer != null) {
         WorldRenderer.getInstance().resetSampler();
         mc.levelRenderer.allChanged();
      }
   }

   public static boolean isUsingShaderPipeline() {
      return useShaderPipeline;
   }

   public static void setSrgbColors(boolean b) {
      srgbColors = b;
   }

   public static boolean useSrgbColors() {
      return srgbColors;
   }

   public static void preInit() {
      mc = Minecraft.getInstance();
      net.vulkanmod.render.shader.ShaderLoadUtil.registerResourceOwner("beryl", RenderingPipeline.class);
      SpirvCompiler.addIncludePath("/assets/beryl/shaders/");
      SpirvCompiler.addIncludePath("/assets/beryl/shaders/include/");
      shaderMainPass = ShaderMainPass.PASS;
      shaderMainPass.init();
      setUseShaderPipeline(useShaderPipeline);
      Renderer.getInstance().addOnResizeCallback(RenderingPipeline::onResize);
   }

   public static void initResources() {
      initUniforms();
      coloredShadows = BerylMod.CONFIG.coloredShadows;
      loadShaders();
      if (shadowMap == null) {
         shadowMap = new ShadowMap(null, null, mc.renderBuffers(), null, mc.gameRenderer.getFeatureRenderDispatcher());
      }

      cloudRenderer = new BerylCloudRenderer();
      bloom = new Bloom(5);
      SwapChain swapChain = Renderer.getInstance().getSwapChain();
      int width = swapChain.getWidth();
      int height = swapChain.getHeight();
      createFramebuffers(width, height);
      shaderMainPass.setEarlyRenderPass(false);
      shaderMainPass.updateShaders();
      if (Renderer.getInstance().getBoundRenderPass() != null) {
         Renderer.getInstance().endRenderPass();
      }

      PipelineManager.setShaderGetter(RenderingPipeline::getShaderPipeline);
      WorldRenderer.getInstance().addOnAllChangedCallback(RenderingPipeline::onAllChanged);
      shadowMap.allChanged();
      ShaderRendererResources.initBlockIdMap();
      init = true;
   }

   static void initUniforms() {
      Uniforms.vec1f_uniformMap.put("HandMaterial", () -> handMaterial);
      Uniforms.vec1f_uniformMap.put("RainStrength", () -> rainStrength);
      Uniforms.mat4f_uniformMap.put("LightSpaceMat", RenderingPipeline::getLightSpaceMatrixBuffer);
      Uniforms.vec1f_uniformMap.put("LightIntensity", RenderingPipeline::getLightIntensity);
      Uniforms.vec1f_uniformMap.put("LightVisibility", (Supplier<Float>)() -> LightVisibility);
      Uniforms.vec1f_uniformMap.put("GameTime", (Supplier<Float>)() -> GameTime);
      Uniforms.vec1f_uniformMap.put("NightMultiplier", (Supplier<Float>)() -> NightMultiplier);
      Uniforms.vec1f_uniformMap.put("NightFactor", (Supplier<Float>)() -> NightMultiplier);
      Uniforms.vec1f_uniformMap.put("AmbientLightFactor", (Supplier<Float>)() -> AmbientLightFactor);
      Uniforms.vec1f_uniformMap.put("MinAmbientLight", (Supplier<Float>)() -> MinAmbientLight);
      Uniforms.vec1f_uniformMap.put("ShadowTexelSize", (Supplier<Float>)() -> ShadowTexelSize);
      Uniforms.vec1f_uniformMap.put("WaterAbsorption", (Supplier<Float>)() -> WaterAbsorption);
      Uniforms.vec1f_uniformMap.put("ShadowBias", (Supplier<Float>)() -> shadowBias);
      Uniforms.vec1f_uniformMap.put("ShadowDistortion", (Supplier<Float>)() -> shadowDistortion);
      Uniforms.vec1f_uniformMap.put("FogFactor", (Supplier<Float>)() -> FogFactor);
      Uniforms.vec1f_uniformMap.put("BloomStrength", (Supplier<Float>)() -> BloomStrength);
      Uniforms.vec3f_uniformMap.put("LightSpaceOffset", (Supplier<MappedBuffer>)() -> LightSpaceOffsetBuffer);
      Uniforms.vec3f_uniformMap.put("LightDir", RenderingPipeline::getLightDirBuffer);
      Uniforms.vec3f_uniformMap.put("LightColor", (Supplier<MappedBuffer>)() -> LightColorBuffer);
      Uniforms.vec3f_uniformMap.put("AmbientLight", (Supplier<MappedBuffer>)() -> AmbientLightColorBuffer);
      Uniforms.vec3f_uniformMap.put("CameraPos", (Supplier<MappedBuffer>)() -> CameraPosBuffer);
      Uniforms.vec3f_uniformMap.put("UpVector", (Supplier<MappedBuffer>)() -> UpVectorBuffer);
      Uniforms.vec4f_uniformMap.put("SkyColor", RenderingPipeline::getSkyColorBuffer);
   }

   static void loadShaders() {
      List<String> defines = new ArrayList<>();
      if (coloredShadows()) {
         defines.add("COLORED_SHADOWS");
      }

      if (texturedSun()) {
         defines.add("TEXTURED_SUN");
      }

      if (wavingWater()) {
         defines.add("WATER_WAVING");
      }

      if (SSR_enabled) {
         defines.add("SSR");
      }

      finalShader = SUtil.createGraphicsPipeline(DefaultVertexFormat.EMPTY, "final/final");
      blitShader = SUtil.createGraphicsPipeline(DefaultVertexFormat.POSITION_TEX, "blit/blit");
      blitDepthShader = SUtil.createGraphicsPipeline(DefaultVertexFormat.POSITION_TEX, "blit/blit_depth");
      blitDepthTriShader = SUtil.createGraphicsPipeline(DefaultVertexFormat.EMPTY, "blit/blit_depth_tri");
      skyShader = SUtil.createGraphicsPipeline(DefaultVertexFormat.POSITION, "sky/sky", defines);
      cloudShader = SUtil.createGraphicsPipeline(DefaultVertexFormat.POSITION_COLOR_NORMAL, "clouds/clouds");
      starsShader = SUtil.createGraphicsPipeline(DefaultVertexFormat.POSITION_COLOR, "sky/stars");
      VertexFormat terrainVertexFormat = TERRAIN_VERTEX_FORMAT;
      terrainShader = SUtil.createGraphicsPipeline(terrainVertexFormat, "terrain", BerylPipelineConfigs.getTerrainConfig(coloredShadows()), defines);
      shadowShader = SUtil.createGraphicsPipeline(terrainVertexFormat, "shadow/shadow");
      entityShadowShader = SUtil.createGraphicsPipeline(DefaultVertexFormat.ENTITY, "shadow/entity");
      translucentShader = SUtil.createGraphicsPipeline(
         terrainVertexFormat, "translucent", BerylPipelineConfigs.getTranslucentTerrainConfig(SSREnabled()), defines
      );
      translucentShader2 = SUtil.createGraphicsPipeline(ShaderRendererResources.EXT_BLOCK, "translucent/translucent");
      entityShaderPipeline = SUtil.createGraphicsPipeline(DefaultVertexFormat.ENTITY, "entity/entity");
      blockShaderPipeline = SUtil.createGraphicsPipeline(ShaderRendererResources.BLOCK_NORMAL, "block/block");
      particleShaderPipeline = SUtil.createGraphicsPipeline(DefaultVertexFormat.PARTICLE, "particle/particle");
   }

   public static void clearResources() {
      if (init) {
         init = false;
         WorldRenderer.getInstance().clearOnAllChangedCallbacks();
         freeFramebuffers();
         freeShadowFramebuffer();
         finalShader.scheduleCleanUp();
         terrainShader.scheduleCleanUp();
         shadowShader.scheduleCleanUp();
         entityShadowShader.scheduleCleanUp();
         translucentShader.scheduleCleanUp();
         translucentShader2.scheduleCleanUp();
         entityShaderPipeline.scheduleCleanUp();
         blockShaderPipeline.scheduleCleanUp();
         particleShaderPipeline.scheduleCleanUp();
         blitShader.scheduleCleanUp();
         blitDepthShader.scheduleCleanUp();
         blitDepthTriShader.scheduleCleanUp();
         skyShader.scheduleCleanUp();
         cloudShader.scheduleCleanUp();
         starsShader.scheduleCleanUp();
         bloom.cleanUp();
      }
   }

   static void onResize() {
      if (useShaderPipeline) {
         resizeFramebuffers();
         if (shadowMap != null) {
            shadowMap.allocateIndirectBuffers();
         }
      }
   }

   static void setupEntityShader(GraphicsPipeline pipeline) {
      ShaderRenderPipeline.of(RenderPipelines.ENTITY_SOLID).redirectPipeline(pipeline);
      ShaderRenderPipeline.of(RenderPipelines.ENTITY_TRANSLUCENT).redirectPipeline(pipeline);
      ShaderRenderPipeline.of(RenderPipelines.ENTITY_CUTOUT).redirectPipeline(pipeline);
      ShaderRenderPipeline.of(RenderPipelines.ENTITY_CUTOUT_CULL).redirectPipeline(pipeline);
      ShaderRenderPipeline.of(RenderPipelines.ENTITY_CUTOUT_Z_OFFSET).redirectPipeline(pipeline);
      ShaderRenderPipeline.of(RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD).redirectPipeline(pipeline);
      ShaderRenderPipeline.of(RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE).redirectPipeline(pipeline);
      ShaderRenderPipeline.of(RenderPipelines.EYES).redirectPipeline(pipeline);
      ShaderRenderPipeline.of(RenderPipelines.ARMOR_CUTOUT_NO_CULL).redirectPipeline(pipeline);
      ShaderRenderPipeline.of(RenderPipelines.ITEM_TRANSLUCENT).redirectPipeline(pipeline);
      ShaderRenderPipeline.of(RenderPipelines.ITEM_CUTOUT).redirectPipeline(pipeline);
      ShaderRenderPipeline.of(RenderPipelines.BANNER_PATTERN).redirectPipeline(pipeline);
      ShaderRenderPipeline.of(RenderPipelines.END_CRYSTAL_BEAM).redirectPipeline(pipeline);
      ShaderRenderPipeline.of(RenderPipelines.BREEZE_WIND).redirectPipeline(pipeline);
   }

   static void setupMovingBlockShader(GraphicsPipeline pipeline) {
      ShaderRenderPipeline.of(RenderPipelines.SOLID_BLOCK).redirectPipeline(pipeline, ShaderRendererResources.BLOCK_NORMAL);
      ShaderRenderPipeline.of(RenderPipelines.CUTOUT_BLOCK).redirectPipeline(pipeline, ShaderRendererResources.BLOCK_NORMAL);
      ShaderRenderPipeline.of(RenderPipelines.TRANSLUCENT_BLOCK).redirectPipeline(pipeline, ShaderRendererResources.BLOCK_NORMAL);
   }

   static void setupParticleShader(GraphicsPipeline pipeline) {
      ShaderRenderPipeline.of(RenderPipelines.OPAQUE_PARTICLE).redirectPipeline(pipeline);
      ShaderRenderPipeline.of(RenderPipelines.TRANSLUCENT_PARTICLE).redirectPipeline(pipeline);
      ShaderRenderPipeline.of(RenderPipelines.WEATHER_NO_DEPTH_WRITE).redirectPipeline(pipeline);
      ShaderRenderPipeline.of(RenderPipelines.WEATHER_DEPTH_WRITE).redirectPipeline(pipeline);
   }

   static void resetShaders() {
      ShaderRenderPipeline.of(RenderPipelines.ENTITY_SOLID).resetPipeline();
      ShaderRenderPipeline.of(RenderPipelines.ENTITY_TRANSLUCENT).resetPipeline();
      ShaderRenderPipeline.of(RenderPipelines.ENTITY_CUTOUT).resetPipeline();
      ShaderRenderPipeline.of(RenderPipelines.ENTITY_CUTOUT_CULL).resetPipeline();
      ShaderRenderPipeline.of(RenderPipelines.ENTITY_CUTOUT_Z_OFFSET).resetPipeline();
      ShaderRenderPipeline.of(RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD).resetPipeline();
      ShaderRenderPipeline.of(RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE).resetPipeline();
      ShaderRenderPipeline.of(RenderPipelines.EYES).resetPipeline();
      ShaderRenderPipeline.of(RenderPipelines.ARMOR_CUTOUT_NO_CULL).resetPipeline();
      ShaderRenderPipeline.of(RenderPipelines.ITEM_TRANSLUCENT).resetPipeline();
      ShaderRenderPipeline.of(RenderPipelines.ITEM_CUTOUT).resetPipeline();
      ShaderRenderPipeline.of(RenderPipelines.BANNER_PATTERN).resetPipeline();
      ShaderRenderPipeline.of(RenderPipelines.END_CRYSTAL_BEAM).resetPipeline();
      ShaderRenderPipeline.of(RenderPipelines.BREEZE_WIND).resetPipeline();
      ShaderRenderPipeline.of(RenderPipelines.SOLID_BLOCK).resetPipeline();
      ShaderRenderPipeline.of(RenderPipelines.CUTOUT_BLOCK).resetPipeline();
      ShaderRenderPipeline.of(RenderPipelines.TRANSLUCENT_BLOCK).resetPipeline();
      ShaderRenderPipeline.of(RenderPipelines.OPAQUE_PARTICLE).resetPipeline();
      ShaderRenderPipeline.of(RenderPipelines.TRANSLUCENT_PARTICLE).resetPipeline();
      ShaderRenderPipeline.of(RenderPipelines.WEATHER_NO_DEPTH_WRITE).resetPipeline();
      ShaderRenderPipeline.of(RenderPipelines.WEATHER_DEPTH_WRITE).resetPipeline();
   }

   public static void createFramebuffers(int width, int height) {
      createShadowFramebuffers();
      hdrFramebuffer = new Builder("hdrFramebuffer", width, height, 1, true).setFormat(97).build();
      finalFramebuffer = new Builder("finalFramebuffer", width, height, 1, false).setFormat(97).build();
      tempFramebuffer = new Builder("tempFramebuffer", width, height, 1, true).setFormat(97).setLinearFiltering(true).build();
      net.vulkanmod.vulkan.framebuffer.RenderPass.Builder builder = new net.vulkanmod.vulkan.framebuffer.RenderPass.Builder(hdrFramebuffer);
      builder.getDepthAttachmentInfo().setOps(2, 0);
      hdrRenderPass1 = builder.build();
      builder = new net.vulkanmod.vulkan.framebuffer.RenderPass.Builder(hdrFramebuffer).setLoadOp(0);
      builder.getDepthAttachmentInfo().setOps(0, 0);
      hdrRenderPass2 = builder.build();
      builder = new net.vulkanmod.vulkan.framebuffer.RenderPass.Builder(tempFramebuffer);
      builder.getDepthAttachmentInfo().setOps(2, 0);
      tempRenderPass = builder.build();
      bloom.createFramebuffers(width, height, finalFramebuffer);
   }

   public static void createShadowFramebuffers() {
      if (shadowFramebuffer != null) {
         freeShadowFramebuffer();
      }

      shadowMapResolution = BerylMod.CONFIG.shadowResolution;
      shadowFramebuffer = new Builder(shadowMapResolution, shadowMapResolution, 1, true).setFormat(37).setLinearFiltering(false).build();
      if (coloredShadows()) {
         shadowSolidDepthImage = VulkanImage.builder(shadowMapResolution, shadowMapResolution).setFormat(126).createVulkanImage();
      }

      VkGpuDevice device = (VkGpuDevice)RenderSystem.getDevice().backend;
      VkGpuTexture attachmentTexture = device.gpuTextureFromVulkanImage(shadowFramebuffer.getColorAttachment());
      shadowTextureView = device.createTextureView(attachmentTexture);
      attachmentTexture = device.gpuTextureFromVulkanImage(shadowFramebuffer.getDepthAttachment());
      shadowDepthTextureView = device.createTextureView(attachmentTexture);
      net.vulkanmod.vulkan.framebuffer.RenderPass.Builder builder = new net.vulkanmod.vulkan.framebuffer.RenderPass.Builder(shadowFramebuffer);
      builder.getDepthAttachmentInfo().setOps(2, 0);
      shadowRenderPass = builder.build();
      builder = new net.vulkanmod.vulkan.framebuffer.RenderPass.Builder(shadowFramebuffer);
      builder.getColorAttachmentInfo().setOps(0, 0);
      builder.getDepthAttachmentInfo().setOps(0, 0);
      shadowRenderPassLoad = builder.build();
   }

   public static void resizeFramebuffers() {
      Framebuffer swapChain = Renderer.getInstance().getSwapChain();
      int width = swapChain.getWidth();
      int height = swapChain.getHeight();
      if (width > 0 && height > 0) {
         shaderMainPass.initFramebuffer();
         resizeFramebuffers(width, height);
      }
   }

   public static void resizeFramebuffers(int width, int height) {
      if (init) {
         freeFramebuffers();
         createFramebuffers(width, height);
      }
   }

   private static void freeFramebuffers() {
      hdrFramebuffer.cleanUp();
      finalFramebuffer.cleanUp();
      tempFramebuffer.cleanUp();
      hdrRenderPass1.cleanUp();
      hdrRenderPass2.cleanUp();
      tempRenderPass.cleanUp();
      hdrFramebuffer = null;
      finalFramebuffer = null;
   }

   private static void freeShadowFramebuffer() {
      shadowFramebuffer.cleanUp();
      shadowRenderPass.cleanUp();
      shadowRenderPassLoad.cleanUp();
      if (shadowSolidDepthImage != null) {
         shadowSolidDepthImage.free();
      }

      shadowFramebuffer = null;
      shadowSolidDepthImage = null;
   }

   public static void onAllChanged() {
      if (init) {
         shadowMap.allChanged();
      }
   }

   public static void beginRender(Matrix4fc view, Vector4f clearColor, DeltaTracker deltaTracker) {
      if (useShaderPipeline) {
         if (reloadRequested) {
            Renderer.getInstance().endRenderPass();
            clearResources();
            reloadRequested = false;
         }
         if (!init) {
            initResources();
         }

         Camera camera = mc.gameRenderer.getMainCamera();
         VRenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
         setSrgbColors(true);
         ColorUtil.useGammaCorrection(true);
         float partialTicks = deltaTracker.getGameTimeDeltaPartialTick(true);
         setShaderGameTime(WorldRenderer.getLevel().getGameTime(), partialTicks);
         updateLight(partialTicks, camera, (Matrix4f)view, clearColor);
         MemoryStack stack = MemoryStack.stackPush();

         try {
            if (!Renderer.isRecording()) {
               Renderer.getInstance().beginFrame();
            }

            VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
            Renderer.getInstance().endRenderPass();
            setShader(shadowShader);
            Matrix4f lightProjection = RenderingPipeline.lightProjection;
            setupEntityShader(entityShadowShader);
            PoseStack poseStack1 = shadowPoseStack;
            poseStack1.last().pose().set(lightView);
            renderingStage = RenderingStage.SHADOW_MAP;
            RenderSystem.outputColorTextureOverride = shadowTextureView;
            RenderSystem.outputDepthTextureOverride = shadowDepthTextureView;
            shadowMap.renderShadowMap(camera, poseStack1, lightProjection, deltaTracker);
            RenderSystem.outputColorTextureOverride = null;
            RenderSystem.outputDepthTextureOverride = null;
            VRenderSystem.applyProjectionMatrix(projection);
            renderingStage = RenderingStage.TERRAIN;
            setShader(terrainShader);
            setupEntityShader(entityShaderPipeline);
            setupMovingBlockShader(blockShaderPipeline);
            setupParticleShader(particleShaderPipeline);
            Renderer.getInstance().endRenderPass();
            shadowFramebuffer.getDepthAttachment().transitionImageLayout(stack, commandBuffer, 5);
            shadowFramebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, 5);
            if (shadowSampler == 0L) {
               shadowSampler = SamplerManager.createTextureSampler(
                  SamplerInfo.builder().setAddressMode(2).setFiltering(1, 1, 1).setCompare(true, 1).createSamplerInfo()
               );
            }

            if (coloredShadows()) {
               VTextureSelector.bindTexture(3, shadowSolidDepthImage);
               VTextureSelector.bindTexture(4, shadowFramebuffer.getDepthAttachment());
               VTextureSelector.bindTexture(5, shadowFramebuffer.getColorAttachment());
               shadowSolidDepthImage.setSampler(shadowSampler);
               shadowFramebuffer.getDepthAttachment().setSampler(shadowSampler);
            } else {
               VTextureSelector.bindTexture(3, shadowFramebuffer.getDepthAttachment());
               shadowFramebuffer.getDepthAttachment().setSampler(shadowSampler);
            }

            Renderer.getInstance().beginRenderPass(hdrRenderPass1, hdrFramebuffer);
            shaderMainPass.setCurrentFramebuffer(hdrFramebuffer);
            Renderer.clearAttachments(16640, hdrFramebuffer.getWidth(), hdrFramebuffer.getHeight());
         } catch (Throwable var10) {
            if (stack != null) {
               try {
                  stack.close();
               } catch (Throwable var9) {
                  var10.addSuppressed(var9);
               }
            }

            throw var10;
         }

         if (stack != null) {
            stack.close();
         }
      }
   }

   public static void copyAndBindFramebuffer() {
      if (useShaderPipeline) {
         if (SSR_enabled) {
            MemoryStack stack = MemoryStack.stackPush();

            try {
               VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
               Renderer.getInstance().endRenderPass(commandBuffer);
               hdrFramebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, 5);
               VTextureSelector.bindTexture(hdrFramebuffer.getColorAttachment());
               hdrFramebuffer.getDepthAttachment().transitionImageLayout(stack, commandBuffer, 5);
               VTextureSelector.bindTexture(3, hdrFramebuffer.getDepthAttachment());
               Renderer.getInstance().beginRenderPass(tempRenderPass, tempFramebuffer);
               Renderer.clearAttachments(16640);
               VRenderSystem.setPrimitiveTopologyGL(4);
               VRenderSystem.enableDepthTest();
               VRenderSystem.depthMask(true);
               VRenderSystem.disableBlend();
               BlitUtil.blitFramebuffer(blitDepthTriShader, hdrFramebuffer.getColorAttachment());
               VRenderSystem.applyProjectionMatrix(projection);
               VRenderSystem.applyModelViewMatrix(RenderSystem.getModelViewMatrix());
               Renderer.getInstance().endRenderPass(commandBuffer);
               long sampler = SamplerManager.getSampler(true, false, 0);
               hdrFramebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, 2);
               hdrFramebuffer.getDepthAttachment().transitionImageLayout(stack, commandBuffer, 3);
               VTextureSelector.bindTexture(3, shadowFramebuffer.getDepthAttachment());
               tempFramebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, 5);
               tempFramebuffer.getDepthAttachment().transitionImageLayout(stack, commandBuffer, 5);
               Renderer.getInstance().beginRenderPass(hdrRenderPass2, hdrFramebuffer);
               tempFramebuffer.getColorAttachment().setSampler(sampler);
               VTextureSelector.bindTexture(4, tempFramebuffer.getColorAttachment());
               VTextureSelector.bindTexture(5, tempFramebuffer.getDepthAttachment());
            } catch (Throwable var5) {
               if (stack != null) {
                  try {
                     stack.close();
                  } catch (Throwable var4) {
                     var5.addSuppressed(var4);
                  }
               }

               throw var5;
            }

            if (stack != null) {
               stack.close();
            }
         }
      }
   }

   public static void endRender() {
      if (useShaderPipeline) {
         MemoryStack stack = MemoryStack.stackPush();

         try {
            VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
            Renderer.getInstance().endRenderPass(commandBuffer);
            renderingStage = RenderingStage.UNDEFINED;
            shadowFramebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, 5);
            VRenderSystem.setPrimitiveTopologyGL(4);
            VRenderSystem.enableDepthTest();
            VRenderSystem.depthMask(true);
            VRenderSystem.disableBlend();
            Profiler profiler = Profiler.getMainProfiler();
            profiler.push("bloom pass");
            VulkanImage postProcessInput = hdrFramebuffer.getColorAttachment();
            if (BloomStrength > 0.0F) {
               bloom.render(commandBuffer, hdrFramebuffer);
               bloom.blendFramebuffer(stack, commandBuffer, postProcessInput, finalFramebuffer);
               postProcessInput = finalFramebuffer.getColorAttachment();
            }
            Renderer.getInstance().endRenderPass();
            profiler.pop();
            VRenderSystem.disableBlend();
            VRenderSystem.disableDepthTest();
            postProcessInput.transitionImageLayout(stack, commandBuffer, 5);
            VTextureSelector.bindTexture(postProcessInput);
            shaderMainPass.beginFinalRenderPass();
            BlitUtil.blitFramebuffer(finalShader, postProcessInput);
            setSrgbColors(false);
            ColorUtil.useGammaCorrection(false);
            resetShaders();
         } catch (Throwable var4) {
            if (stack != null) {
               try {
                  stack.close();
               } catch (Throwable var3) {
                  var4.addSuppressed(var3);
               }
            }

            throw var4;
         }

         if (stack != null) {
            stack.close();
         }
      }
   }

   private static void drawShadowMap() {
      VTextureSelector.bindTexture(shadowFramebuffer.getColorAttachment());
      BlitUtil.blitQuad(blitShader, 0.7F, 0.7F, 1.0F, 1.0F);
   }

   private static void drawBloomFB() {
      VulkanImage image = bloom.framebuffers[0].getColorAttachment();
      VTextureSelector.bindTexture(image);
      BlitUtil.blitQuad(blitShader, 0.0F, 0.7F, 0.3F, 1.0F);
   }

   private static void updateLight(float partialTicks, Camera camera, Matrix4f camView, Vector4f clearColor) {
      EnvironmentAttributeProbe environmentAttributeProbe = camera.attributeProbe();
      celestialAngle = (Float)environmentAttributeProbe.getValue(EnvironmentAttributes.SUN_ANGLE, partialTicks) * 0.0027777778F;
      sunAngle = celestialAngle < 0.75F ? celestialAngle + 0.25F : celestialAngle - 0.75F;
      boolean endDim = mc.level.dimension().identifier().getPath().contains("end");
      boolean netherDim = mc.level.dimension().identifier().getPath().contains("nether");
      MinAmbientLight = ((Double)Minecraft.getInstance().options.gamma().get()).floatValue() * 0.05F + 0.01F;
      if (celestialAngle > 0.3F && celestialAngle < 0.7F) {
         celestialAngle += 0.5F;
         NightMultiplier = 1.0F;
      } else if (celestialAngle >= 0.25F && celestialAngle <= 0.3F) {
         NightMultiplier = MathUtil.clamp(0.0F, 1.0F, (celestialAngle - 0.25F) / 0.05F);
      } else if (celestialAngle >= 0.7F && celestialAngle <= 0.75F) {
         NightMultiplier = MathUtil.clamp(0.0F, 1.0F, 1.0F - (celestialAngle - 0.7F) / 0.05F);
      } else {
         NightMultiplier = 0.0F;
      }

      if (endDim) {
         celestialAngle = 0.0F;
      }

      lightView.identity();
      lightView.translate(0.0F, 0.0F, -100.0F);
      lightView.rotateX((float)Math.toRadians(90.0F));
      lightView.rotateY((float)Math.toRadians(90.0F));
      lightView.rotateZ((float)Math.toRadians(celestialAngle * -360.0F));
      lightView.rotateX((float)Math.toRadians(-sunPathRotation));
      lightPosWS.set(lightView.m02(), lightView.m12(), lightView.m22());
      lightView.identity();
      lightView.rotateX((float)Math.toRadians(90.0F));
      lightView.rotateZ((float)Math.toRadians(celestialAngle * -360.0F));
      lightView.rotateX((float)Math.toRadians(-sunPathRotation));
      lightDirWS.set(lightPosWS.x(), lightPosWS.y(), lightPosWS.z());
      lightDirWS.normalize();
      lightDirWS.mulDirection(camView, lightDir);
      Vector3f vec3 = lightScratch.set(lightView.m02(), lightView.m12(), lightView.m22()).mul(100.0F);
      shadowMap.setRelativeLightPos(vec3.x(), vec3.y(), vec3.z());
      Vector3f upVec = upScratch.set(camView.m10(), camView.m11(), camView.m12());
      upVec.get(UpVectorBuffer.buffer);
      float LdotUp = upVec.dot(lightDir);
      LdotUp = Math.max(LdotUp, 0.0F);
      float PI4th = (float) (Math.PI / 4);
      shadowBias = (float)Math.max(Math.acos(LdotUp) - PI4th, 0.0) / PI4th;
      shadowBias = 1.0E-4F;
      Vector3f lightSamplingOffset = lightScratch.set(lightDirWS.x(), 0.0F, lightDirWS.z())
         .mul(0.6F * (1.0F - lightPosWS.y()));
      LightSpaceOffsetBuffer.buffer.putFloat(0, lightSamplingOffset.x());
      LightSpaceOffsetBuffer.buffer.putFloat(4, lightSamplingOffset.y());
      LightSpaceOffsetBuffer.buffer.putFloat(8, lightSamplingOffset.z());
      LightDirBuffer.buffer.putFloat(0, lightDir.x());
      LightDirBuffer.buffer.putFloat(4, lightDir.y());
      LightDirBuffer.buffer.putFloat(8, lightDir.z());
      Vec3 cameraPos = camera.position();
      CameraPosBuffer.putFloat(0, (float)cameraPos.x);
      CameraPosBuffer.putFloat(4, (float)cameraPos.y);
      CameraPosBuffer.putFloat(8, (float)cameraPos.z);
      float rainLevel = WorldRenderer.getLevel().getRainLevel(partialTicks);
      float thunderLevel = WorldRenderer.getLevel().getThunderLevel(partialTicks);
      rainStrength = (netherDim || endDim) ? 0.0F : rainLevel;
      LightVisibility = 1.0F - rainLevel;
      if (netherDim) {
         LightVisibility = 0.0F;
      } else if (endDim) {
         LightVisibility = 0.0F;
      }

      LightIntensity = 1.0F;
      LightIntensity = LightIntensity * (0.2F + 0.8F * LightVisibility);
      LightIntensity *= 0.5F + 0.5F * (1.0F - thunderLevel);
      float sunsetFactor = 1.0F;
      if (NightMultiplier >= 1.0F) {
         AmbientLightFactor = 0.5F;
         AmbientLightFactor *= 0.3F;
         BloomStrength = 0.06F;
         float i = 0.2F * LightIntensity;
         setLightColor(i, i, i);
      } else {
         AmbientLightFactor = LdotUp < 0.25F ? LdotUp * 2.0F + 0.5F : 1.0F;
         AmbientLightFactor *= 0.3F;
         BloomStrength = 0.04F;
         float var29 = (1.0F - LdotUp) * 1.1F;
         float var30 = var29 * var29;
         sunsetFactor = Math.min(var30, 1.0F);
         float i = 8.0F * (1.0F - NightMultiplier) * LightIntensity;
         i = MathUtil.lerp(1.0F, 0.9F, sunsetFactor) * i;
         float sunsetFactor2 = sunsetFactor * (1.0F - rainLevel);
         float r = MathUtil.lerp(1.0F, 0.75F, sunsetFactor2) * i;
         float g = MathUtil.lerp(0.9F, 0.27F, sunsetFactor2) * i;
         float b = MathUtil.lerp(0.8F, 0.15F, sunsetFactor2) * i;
         setLightColor(r, g, b);
      }

      float stormAmbient = (1.0F - 0.25F * rainStrength) * (1.0F - 0.25F * thunderLevel);
      setAmbientLightColor(0.6F * stormAmbient, 0.65F * stormAmbient, 0.75F * stormAmbient);
      if (endDim) {
         float i = 3.0F;
         float r = 0.9F * i;
         float g = 0.7F * i;
         float b = 1.0F * i;
         setLightColor(r, g, b);
         setAmbientLightColor(0.7F, 0.35F, 0.7F);
      }

      float invSunsetFactor = 1.0F - sunsetFactor;
      float skyR = 0.4F * (0.6F + 0.4F * invSunsetFactor);
      float skyG = 0.6F * (0.6F + 0.4F * invSunsetFactor);
      float skyB = 1.0F * (0.6F + 0.4F * invSunsetFactor);
      float invRainFactor = 1.0F - rainLevel;
      skyR = MathUtil.lerp(skyR, 0.24F, rainLevel);
      skyG = MathUtil.lerp(skyG, 0.27F, rainLevel);
      skyB = MathUtil.lerp(skyB, 0.31F, rainLevel);
      skyR = MathUtil.lerp(skyR, 0.10F, thunderLevel);
      skyG = MathUtil.lerp(skyG, 0.12F, thunderLevel);
      skyB = MathUtil.lerp(skyB, 0.16F, thunderLevel);
      float invNightFactor = 1.0F - NightMultiplier;
      skyR *= invNightFactor;
      skyG *= invNightFactor;
      skyB *= invNightFactor;
      skyR += NightMultiplier * 0.025F * stormAmbient;
      skyG += NightMultiplier * 0.045F * stormAmbient;
      skyB += NightMultiplier * 0.095F * stormAmbient;
      setSkyColor(skyR, skyG, skyB, 1.0F);
      if (camera.getFluidInCamera() == FogType.WATER) {
         FogFactor = 0.02F;
         VRenderSystem.setShaderFogColor(0.1F, 0.2F, 0.6F, 1.0F);
      } else if (camera.getFluidInCamera() != FogType.LAVA && camera.getFluidInCamera() != FogType.POWDER_SNOW) {
         FogFactor = 3.0E-4F;
         FogFactor += rainLevel * 0.005F;
         FogFactor += thunderLevel * 0.005F;
         float fogColorM = (1.0F - NightMultiplier) * 0.8F + 0.2F;
         fogColorM *= 0.3F * (1.0F - rainLevel) + 0.7F;
         fogColorM *= 0.3F * (1.0F - thunderLevel) + 0.7F;
         VRenderSystem.setShaderFogColor(0.8F * fogColorM, 0.9F * fogColorM, 0.9F * fogColorM, 1.0F);
         if (netherDim) {
            FogFactor = 0.005F;
            VRenderSystem.setShaderFogColor(0.6F, 0.6F, 0.6F, 1.0F);
            clearColor.set(0.6F, 0.6F, 0.6F, 1.0F);
            setSkyColor(0.6F, 0.6F, 0.6F, 1.0F);
         } else if (endDim) {
            FogFactor = 0.005F;
            float r = 0.18F;
            float g = 0.015F;
            float b = 0.2F;
            VRenderSystem.setShaderFogColor(r, g, b, 1.0F);
            setSkyColor(r, g, b, 1.0F);
         }
      } else {
         FogFactor = 0.6F;
      }

      if (camera.entity() instanceof LivingEntity livingEntity) {
         if (livingEntity.hasEffect(MobEffects.BLINDNESS)) {
            FogFactor = 0.6F;
            LightVisibility = 0.0F;
            VRenderSystem.setShaderFogColor(0.0F, 0.0F, 0.0F, 1.0F);
            setSkyColor(0.0F, 0.0F, 0.0F, 1.0F);
            setLightColor(0.0F, 0.0F, 0.0F);
         }

         if (livingEntity.hasEffect(MobEffects.NIGHT_VISION)) {
            MinAmbientLight = 0.6F;
         }
      }

      if (netherDim) {
         BloomStrength = 0.08F;
      }

      BloomStrength = BloomStrength * BerylMod.CONFIG.bloomIntensity;
      FogFactor = FogFactor * BerylMod.CONFIG.atmFogIntensity;
      WaterAbsorption = BerylMod.CONFIG.waterAbsorption;
      ShadowTexelSize = 1.0F / shadowMapResolution;
      int shadowDistance = BerylMod.CONFIG.shadowRenderDistance;
      shadowDistortion = 1.0F - 1.2F / shadowDistance;
      orthoMatrixHalfLength = shadowDistance * 16.0F;
      lightProjection.setOrthoSymmetric(orthoMatrixHalfLength * 2.0F, orthoMatrixHalfLength * 2.0F, -100.0F, 192.0F, true);
      lightVP.set(lightProjection);
      lightVP.mul(lightView);
      lightVP.get(LightSpaceMatrixBuffer.buffer);
   }

   public static void setColoredShadows(boolean coloredShadows) {
      if (RenderingPipeline.coloredShadows != coloredShadows) {
         RenderingPipeline.coloredShadows = coloredShadows;
         reloadRequested = true;
      }
   }

   public static boolean coloredShadows() {
      return coloredShadows;
   }

   public static void setUseTexturedSun(boolean useTexturedSun) {
      if (RenderingPipeline.useTexturedSun != useTexturedSun) {
         RenderingPipeline.useTexturedSun = useTexturedSun;
         reloadRequested = true;
      }
   }

   public static void setWaterWaving(boolean waterWaving) {
      if (RenderingPipeline.waterWaving != waterWaving) {
         RenderingPipeline.waterWaving = waterWaving;
         reloadRequested = true;
      }
   }

   public static void setSSR(boolean ssr) {
      if (SSR_enabled != ssr) {
         SSR_enabled = ssr;
         reloadRequested = true;
      }
   }

   public static boolean texturedSun() {
      return useTexturedSun;
   }

   public static boolean wavingWater() {
      return waterWaving;
   }

   public static boolean SSREnabled() {
      return SSR_enabled;
   }

   public static void scheduleReload() {
      reloadRequested = true;
   }

   public static void setShader(GraphicsPipeline pipeline) {
      currentShader = pipeline;
   }

   public static GraphicsPipeline getShaderPipeline(TerrainRenderType renderType) {
      if (renderType == TerrainRenderType.TRANSLUCENT && currentShader != shadowShader) {
         VRenderSystem.blendFuncSeparate(770, 771, 1, 0);
         return translucentShader;
      } else {
         return currentShader;
      }
   }

   public static void setSkyColor(float r, float g, float b, float a) {
      ColorUtil.setRGBA_Buffer(SkyColorBuffer, r, g, b, a);
   }

   public static void setLightColor(float r, float g, float b) {
      LightColorBuffer.putFloat(0, r);
      LightColorBuffer.putFloat(4, g);
      LightColorBuffer.putFloat(8, b);
   }

   public static void setAmbientLightColor(float r, float g, float b) {
      AmbientLightColorBuffer.putFloat(0, r);
      AmbientLightColorBuffer.putFloat(4, g);
      AmbientLightColorBuffer.putFloat(8, b);
   }

   public static void setShaderGameTime(long l, float f) {
      float g = ((float)(l % 24000L) + f) / 24000.0F;
      GameTime = g;
   }

   public static float getGameTime() {
      return GameTime;
   }

   public static RenderingStage getRenderingStage() {
      return renderingStage;
   }

   public static MappedBuffer getSkyColorBuffer() {
      return SkyColorBuffer;
   }

   public static GraphicsPipeline getSkyShader() {
      return skyShader;
   }

   public static GraphicsPipeline getCloudShader() {
      return cloudShader;
   }

   public static GraphicsPipeline getStarsShader() {
      return starsShader;
   }

   public static GraphicsPipeline getCurrentShader() {
      return currentShader;
   }

   public static MappedBuffer getLightDirBuffer() {
      return LightDirBuffer;
   }

   public static MappedBuffer getLightSpaceMatrixBuffer() {
      return LightSpaceMatrixBuffer;
   }

   public static float getLightIntensity() {
      return LightIntensity;
   }

   public static BerylCloudRenderer getCloudRenderer() {
      return cloudRenderer;
   }

   static {
      waterWaving = BerylMod.CONFIG.waterWaving;
   }
}
