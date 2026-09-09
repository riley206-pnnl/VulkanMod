package net.vulkanmod.render.chunk;

import com.google.common.collect.Sets;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.ShadowFeatureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.Zone;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.shader.PipelineManager;
import net.vulkanmod.mixin.render.feature.FeatureRenderDispatcherAccessor;
import net.vulkanmod.shaders.PackFramebuffers;
import net.vulkanmod.shaders.PackCompositePipeline;
import net.vulkanmod.shaders.PackDebug;
import net.vulkanmod.shaders.PackTerrainPipeline;
import net.vulkanmod.shaders.PackShadowMatrices;
import net.vulkanmod.shaders.PackShadowPipeline;
import net.vulkanmod.shaders.PackSkyPipeline;
import net.vulkanmod.shaders.QuadRenderer;
import org.lwjgl.system.MemoryStack;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.render.chunk.build.RenderRegionBuilder;
import net.vulkanmod.render.chunk.build.task.TaskDispatcher;
import net.vulkanmod.render.chunk.build.task.ChunkTask;
import net.vulkanmod.render.chunk.graph.SectionGraph;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.render.profiling.BuildTimeProfiler;
import net.vulkanmod.render.profiling.Profiler;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.memory.buffer.IndexBuffer;
import net.vulkanmod.vulkan.memory.buffer.IndirectBuffer;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;

import java.util.*;

public class WorldRenderer {
    private static WorldRenderer INSTANCE;

    public static WorldRenderer init(EntityRenderDispatcher entityRenderDispatcher,
                                     BlockEntityRenderDispatcher blockEntityRenderDispatcher,
                                     RenderBuffers renderBuffers,
                                     LevelRenderState levelRenderState,
                                     FeatureRenderDispatcher featureRenderDispatcher)
    {
        if (INSTANCE != null) {
            return INSTANCE;
        }
        else {
            return INSTANCE = new WorldRenderer(entityRenderDispatcher, blockEntityRenderDispatcher, renderBuffers, levelRenderState, featureRenderDispatcher);
        }
    }

    public RenderRegionBuilder renderRegionCache;

    private final Minecraft minecraft;
    private ClientLevel level;
    private int renderDistance;
    private final RenderBuffers renderBuffers;

    private final EntityRenderDispatcher entityRenderDispatcher;
    private final BlockEntityRenderDispatcher blockEntityRenderDispatcher;
    private final LevelRenderState levelRenderState;
    private final FeatureRenderDispatcher featureRenderDispatcher;

    private float partialTick;
    private final Vector3d cameraPos = new Vector3d();
    private PackShadowMatrices.State activeShadowMatrices;
    private Matrix4f lastCameraModelView;
    private Matrix4f lastCameraProjection;
    private int lastCameraSectionX;
    private int lastCameraSectionY;
    private int lastCameraSectionZ;
    private float lastCameraX;
    private float lastCameraY;
    private float lastCameraZ;
    private float lastCamRotX;
    private float lastCamRotY;

    private SectionGrid sectionGrid;

    private SectionGraph sectionGraph;
    private boolean graphNeedsUpdate;

    private final Set<BlockEntity> globalBlockEntities = Sets.newHashSet();

    private final TaskDispatcher taskDispatcher;

    private double xTransparentOld;
    private double yTransparentOld;
    private double zTransparentOld;

    IndirectBuffer[] indirectBuffers;

    private long terrainSampler;

    private final List<Runnable> onAllChangedCallbacks = new ObjectArrayList<>();

    private WorldRenderer(EntityRenderDispatcher entityRenderDispatcher,
                          BlockEntityRenderDispatcher blockEntityRenderDispatcher,
                          RenderBuffers renderBuffers,
                          LevelRenderState levelRenderState,
                          FeatureRenderDispatcher featureRenderDispatcher)
    {
        this.minecraft = Minecraft.getInstance();
        this.renderBuffers = renderBuffers;
        this.entityRenderDispatcher = entityRenderDispatcher;
        this.blockEntityRenderDispatcher = blockEntityRenderDispatcher;
        this.levelRenderState = levelRenderState;
        this.featureRenderDispatcher = featureRenderDispatcher;

        this.renderRegionCache = new RenderRegionBuilder();
        this.taskDispatcher = new TaskDispatcher();

        ChunkTask.setTaskDispatcher(this.taskDispatcher);
        allocateIndirectBuffers();
        TerrainRenderType.updateMapping();

        Renderer.getInstance().addOnResizeCallback(() -> {
            if (this.indirectBuffers.length != Renderer.getFramesNum())
                allocateIndirectBuffers();
        });
    }

    private void allocateIndirectBuffers() {
        if (this.indirectBuffers != null)
            Arrays.stream(this.indirectBuffers).forEach(Buffer::scheduleFree);

        this.indirectBuffers = new IndirectBuffer[Renderer.getFramesNum()];

        for (int i = 0; i < this.indirectBuffers.length; ++i) {
            this.indirectBuffers[i] = new IndirectBuffer(1000000, MemoryTypes.HOST_MEM);
        }
    }

    private void benchCallback() {
        BuildTimeProfiler.runBench(this.graphNeedsUpdate || !this.taskDispatcher.isIdle());
    }

    public void setupRenderer(Camera camera, Frustum frustum, boolean isCapturedFrustum, boolean spectator) {
        Profiler profiler = Profiler.getMainProfiler();
        profiler.push("Setup_Renderer");

        ProfilerFiller mcProfiler = net.minecraft.util.profiling.Profiler.get();

        benchCallback();

        Vec3 camPos = camera.position();
        this.cameraPos.set(camPos.x(), camPos.y(), camPos.z());
        if (this.minecraft.options.getEffectiveRenderDistance() != this.renderDistance) {
            this.allChanged();
        }

        mcProfiler.push("camera");
        float cameraX = (float) cameraPos.x();
        float cameraY = (float) cameraPos.y();
        float cameraZ = (float) cameraPos.z();
        int sectionX = SectionPos.posToSectionCoord(cameraX);
        int sectionY = SectionPos.posToSectionCoord(cameraY);
        int sectionZ = SectionPos.posToSectionCoord(cameraZ);

        profiler.push("reposition");
        if (this.lastCameraSectionX != sectionX || this.lastCameraSectionY != sectionY || this.lastCameraSectionZ != sectionZ) {
            this.lastCameraSectionX = sectionX;
            this.lastCameraSectionY = sectionY;
            this.lastCameraSectionZ = sectionZ;
            this.sectionGrid.repositionCamera(cameraX, cameraZ);
        }
        profiler.pop();

        double entityDistanceScaling = this.minecraft.options.entityDistanceScaling().get();
        Entity.setViewScale(Mth.clamp((double) this.renderDistance / 8.0D, 1.0D, 2.5D) * entityDistanceScaling);

        mcProfiler.popPush("cull");

        mcProfiler.popPush("update");

        boolean cameraMoved = false;
        float d_xRot = Math.abs(camera.xRot() - this.lastCamRotX);
        float d_yRot = Math.abs(camera.yRot() - this.lastCamRotY);
        cameraMoved |= d_xRot > 2.0f || d_yRot > 2.0f;

        cameraMoved |= cameraX != this.lastCameraX || cameraY != this.lastCameraY || cameraZ != this.lastCameraZ;
        this.graphNeedsUpdate |= cameraMoved;

        if (!isCapturedFrustum) {
            //Debug
//            this.graphNeedsUpdate = true;

            if (this.graphNeedsUpdate()) {
                this.graphNeedsUpdate = false;
                this.lastCameraX = cameraX;
                this.lastCameraY = cameraY;
                this.lastCameraZ = cameraZ;
                this.lastCamRotX = camera.xRot();
                this.lastCamRotY = camera.yRot();

                this.sectionGraph.update(camera, frustum, spectator);
            }
        }

        this.indirectBuffers[Renderer.getCurrentFrame()].reset();

        mcProfiler.pop();
        profiler.pop();
    }

    public void uploadSections() {
        ProfilerFiller mcProfiler = net.minecraft.util.profiling.Profiler.get();
        mcProfiler.push("upload");

        Profiler profiler = Profiler.getMainProfiler();
        profiler.push("Uploads");

        try {
            if (this.taskDispatcher.updateSections())
                this.graphNeedsUpdate = true;
        } catch (Exception e) {
            Initializer.LOGGER.error(e.getMessage());
            allChanged();
        }

        profiler.pop();

        mcProfiler.pop();
    }

    public boolean isSectionCompiled(BlockPos blockPos) {
        RenderSection renderSection = this.sectionGrid.getSectionAtBlockPos(blockPos);
        return renderSection != null && renderSection.isCompiled();
    }

    public void allChanged() {
        if (this.level != null) {
            this.level.clearTintCaches();

            this.renderRegionCache.clear();
            this.taskDispatcher.createThreads(Initializer.CONFIG.builderThreads);

            this.graphNeedsUpdate = true;

            this.renderDistance = this.minecraft.options.getEffectiveRenderDistance();
            if (this.sectionGrid != null) {
                this.sectionGrid.freeAllBuffers();
            }

            this.taskDispatcher.clearBatchQueue();
            synchronized (this.globalBlockEntities) {
                this.globalBlockEntities.clear();
            }

            this.sectionGrid = new SectionGrid(this.level, this.renderDistance);
            this.sectionGraph = new SectionGraph(this.level, this.sectionGrid, this.taskDispatcher);

            this.onAllChangedCallbacks.forEach(Runnable::run);

            Entity entity = this.minecraft.getCameraEntity();
            if (entity != null) {
                this.sectionGrid.repositionCamera(entity.getX(), entity.getZ());
            }

        }
    }

    public void setLevel(@Nullable ClientLevel level) {
        this.lastCameraX = Float.MIN_VALUE;
        this.lastCameraY = Float.MIN_VALUE;
        this.lastCameraZ = Float.MIN_VALUE;
        this.lastCameraSectionX = Integer.MIN_VALUE;
        this.lastCameraSectionY = Integer.MIN_VALUE;
        this.lastCameraSectionZ = Integer.MIN_VALUE;
        // Do not let a dimension's light-space transform leak into the next
        // world's first frame. The shadow target is rebuilt lazily after the
        // first visible terrain submission, so the pre-shadow frame must use
        // deterministic bootstrap state rather than stale Nether/Overworld
        // matrices.
        this.activeShadowMatrices = null;

//        this.entityRenderDispatcher.setLevel(level);
        this.level = level;
        ChunkStatusMap.createInstance(renderDistance);
        if (level != null) {
            // Iris keeps separate world0/world-1/world1 program namespaces.
            // Rebuild the pack-owned pipelines before the new level starts
            // submitting geometry so Nether and End do not reuse overworld
            // macros, sky code, or composite passes.
            PipelineManager.reloadPackDimension(shaderDimension(level));
            this.allChanged();
        } else {
            if (this.sectionGrid != null) {
                this.sectionGrid.freeAllBuffers();
                this.sectionGrid = null;
            }

            this.taskDispatcher.stopThreads();

            this.graphNeedsUpdate = true;
        }

    }

    public void addOnAllChangedCallback(Runnable runnable) {
        this.onAllChangedCallbacks.add(runnable);
    }

    public void clearOnAllChangedCallbacks() {
        this.onAllChangedCallbacks.clear();
    }

    public void renderSectionLayer(TerrainRenderType renderType, double camX, double camY, double camZ, Matrix4f modelView, Matrix4f projection) {
        renderSectionLayer(renderType, camX, camY, camZ, modelView, projection, null);
    }

    public void renderSectionLayer(TerrainRenderType renderType, double camX, double camY, double camZ, Matrix4f modelView, Matrix4f projection, Matrix4f cleanProjection) {
        // With uniqueOpaqueLayer enabled, the chunk graph places both solid
        // and cutout geometry in the CUTOUT buffer and the SOLID callback is
        // intentionally empty.  Shadow/sky preparation must therefore run on
        // whichever callback is the real first opaque layer.
        boolean firstOpaqueLayer = renderType == TerrainRenderType.SOLID
                || (renderType == TerrainRenderType.CUTOUT && Initializer.CONFIG.uniqueOpaqueLayer);
        if (firstOpaqueLayer) {
            lastCameraModelView = new Matrix4f(modelView);
            lastCameraProjection = new Matrix4f(projection);
        }
        Renderer.getInstance().getMainPass().rebindMainTarget();

        if (firstOpaqueLayer && PackCompositePipeline.isActive()) {
            // The pack may read an attachment before any pass writes it.
            // End the vanilla scope before issuing the explicit transfer
            // clears, then let the sky/terrain paths establish their own
            // rendering scopes.
            Renderer.getInstance().endRenderPass();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PackFramebuffers.clearColorTargets(Renderer.getCommandBuffer(), stack);
            }
        }

        if (firstOpaqueLayer && PipelineManager.getPackSkyShader() != null
                && !Boolean.getBoolean("vulkanmod.disablePackSky")) {
            renderPackSky(modelView, projection, cleanProjection);
            Renderer.getInstance().getMainPass().rebindMainTarget();
        } else if (firstOpaqueLayer && PipelineManager.getPackSkyShader() != null
                && Boolean.getBoolean("vulkanmod.disablePackSky")) {
            Initializer.LOGGER.warn("Pack sky stage disabled by diagnostic property");
        }

        this.sortTranslucentSections(camX, camY, camZ);

        ProfilerFiller mcProfiler = net.minecraft.util.profiling.Profiler.get();
        Zone zone = mcProfiler.zone(() -> "render_" + renderType);

        final boolean isTranslucent = renderType == TerrainRenderType.TRANSLUCENT;

        if (!isTranslucent) {
            GlStateManager._disableBlend();
        } else {
            GlStateManager._enableBlend();
            VRenderSystem.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        }

        VRenderSystem.enableCull();
        VRenderSystem.glDepthFun(GL11.GL_LEQUAL);

        GlStateManager._enableDepthTest();
        GlStateManager._depthMask(true);

        GlStateManager._colorMask(com.mojang.blaze3d.pipeline.ColorTargetState.WRITE_ALL);
        GlStateManager._disablePolygonOffset();
        VRenderSystem.setPolygonModeGL(GL11.GL_FILL);

        VRenderSystem.applyMVP(modelView, projection);
        VRenderSystem.setPrimitiveTopologyGL(GL11.GL_TRIANGLES);

        Renderer.pushDebugSection("Terrain_" + renderType);

        Renderer renderer = Renderer.getInstance();
        GraphicsPipeline pipeline = PipelineManager.getTerrainShader(renderType);
        boolean isPackShader = PipelineManager.isPackShader(pipeline);
        // Shader-pack draws depend on a distinct firstInstance for every
        // section because vm_SectionData is indexed from gl_InstanceIndex.
        // Keep the pack baseline on direct indexed draws until the indirect
        // command path has an equivalent per-section contract.
        final boolean indirectDraw = Initializer.CONFIG.indirectDraw && !isPackShader;
        if (isPackShader) {
            renderer.endRenderPass();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PackFramebuffers.beginTerrainMRT(Renderer.getCommandBuffer(), stack, PackTerrainPipeline.getDrawBuffers(pipeline));
            }
        }
        renderer.bindGraphicsPipeline(pipeline);

        TextureManager textureManager = Minecraft.getInstance().getTextureManager();
        AbstractTexture atlasTexture = textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS);
        var texView = atlasTexture.getTextureView();
        boolean useAnisotropy = this.minecraft.options.textureFiltering().get() == TextureFilteringMethod.ANISOTROPIC;
        int maxAnisotropy = this.minecraft.options.maxAnisotropyValue();
        var texture = (VkGpuTexture)texView.texture();

        if (this.terrainSampler == 0L) {
            this.terrainSampler = SamplerManager.getTerrainSampler(texture.getVulkanImage().mipLevels - 1, useAnisotropy, maxAnisotropy);
        }

        texture.getVulkanImage().setSampler(this.terrainSampler);

        VRenderSystem.setShaderTexture(0, texView);
        VRenderSystem.setShaderTexture(2, Minecraft.getInstance().gameRenderer.lightmap());

        VTextureSelector.bindShaderTextures(pipeline);

        int atlasTexWidth = texView.getWidth(0);
        int atlasTexHeight = texView.getHeight(0);

        VRenderSystem.setTextureSize(atlasTexWidth, atlasTexHeight);
        VRenderSystem.setCurrentTime((int) System.currentTimeMillis());
        // The shadow pass is rendered immediately before the camera G-buffer.
        // Reuse its light-space matrices here so Complementary's shadow2D
        // lookups address the map that was just populated instead of the
        // identity transform used by the old compatibility path.
        PackTerrainPipeline.update(pipeline, cleanProjection, activeShadowMatrices);

        long currentTimeMs = System.currentTimeMillis();
        float fadeTime = Minecraft.getInstance().options.chunkSectionFadeInTime().get().floatValue();
        int fadeTimeMs = (int) (fadeTime * 1000);
        float fadeTimeInv = fadeTime > 0 ? 1 / (fadeTime * 1000) : 1;

        IndexBuffer indexBuffer = Renderer.getDrawer().getQuadsIndexBuffer().getIndexBuffer();
        Renderer.getDrawer().bindIndexBuffer(Renderer.getCommandBuffer(), indexBuffer, indexBuffer.indexType.value);

        UBO sectionData = pipeline.getUBO(2);
        sectionData.setUseGlobalBuffer(false);

        int currentFrame = Renderer.getCurrentFrame();
        Set<TerrainRenderType> allowedRenderTypes = TerrainRenderType.SEMI_COMPACT_RENDER_TYPES;
        if (allowedRenderTypes.contains(renderType)) {
            renderType.setCutoutUniform();

            int areaCount = 0, drawnCount = 0, noBufferCount = 0, emptyQueueCount = 0;
            for (Iterator<ChunkArea> iterator = this.sectionGraph.getChunkAreaQueue().iterator(isTranslucent); iterator.hasNext(); ) {
                ChunkArea chunkArea = iterator.next();
                var queue = chunkArea.sectionQueue;
                DrawBuffers drawBuffers = chunkArea.drawBuffers;
                areaCount++;

                if (drawBuffers.getAreaBuffer(renderType) != null && queue.size() > 0) {
                    drawnCount++;

                    drawBuffers.bindBuffers(Renderer.getCommandBuffer(), pipeline, renderType,
                                            sectionData,
                                            camX, camY, camZ,
                                            currentTimeMs, fadeTimeMs, fadeTimeInv);

                    renderer.uploadAndBindUBOs(pipeline);

                    if (indirectDraw) {
                        drawBuffers.buildDrawBatchesIndirect(cameraPos, indirectBuffers[currentFrame], queue, renderType,
                                Boolean.getBoolean("vulkanmod.debugTerrainNoCulling"));
                    }
                    else {
                        if (isPackShader) {
                            drawBuffers.buildDrawBatchesDirectPack(cameraPos, queue, renderType,
                                    Boolean.getBoolean("vulkanmod.debugTerrainNoCulling"));
                        } else {
                            drawBuffers.buildDrawBatchesDirect(cameraPos, queue, renderType,
                                    Boolean.getBoolean("vulkanmod.debugTerrainNoCulling"));
                        }
                    }
                } else {
                    if (drawBuffers.getAreaBuffer(renderType) == null) noBufferCount++;
                    if (queue.size() == 0) emptyQueueCount++;
                }
            }
            if ((renderType == TerrainRenderType.SOLID || renderType == TerrainRenderType.CUTOUT) && areaCount > 0) {
                PackDebug.setTerrainStats(areaCount, drawnCount, noBufferCount, emptyQueueCount);
                if (Boolean.getBoolean("vulkanmod.debugChunkReadiness") && PackDebug.shouldLog()) {
                    Initializer.LOGGER.info("VulkanMod DRAW: renderType={}, areas={}, drawn={}, noBuffer={}, emptyQueue={}", renderType, areaCount, drawnCount, noBufferCount, emptyQueueCount);
                }
            }
        }

        if (renderType == TerrainRenderType.CUTOUT || renderType == TerrainRenderType.TRIPWIRE) {
            indirectBuffers[currentFrame].submitUploads();
//            uniformBuffers.submitUploads();
        }

        // Need to reset push constants in case the pipeline will still be used for rendering
        if (!indirectDraw) {
            VRenderSystem.setModelOffset(0, 0, 0);
            renderer.pushConstants(pipeline);
        }

        if (isPackShader) {
            PackFramebuffers.endTerrainMRT(Renderer.getCommandBuffer());
            renderer.getMainPass().rebindMainTarget();
        }

        // The visible opaque section draw also submits/uploads the chunk
        // buffers used by the shadow caster.  Run the shadow stage after that
        // draw, but still before LevelRenderer begins the deferred/composite
        // chain, so the shadow map contains real terrain and can be sampled by
        // composite1.  Running it before the opaque draw leaves the first
        // frame with an empty caster set (shadow drawn=0).
        if (firstOpaqueLayer && PipelineManager.getPackShadowShader() != null) {
            renderPackShadow(camX, camY, camZ, modelView, projection, cleanProjection);
            renderer.getMainPass().rebindMainTarget();
        }

        Renderer.popDebugSection();

        zone.close();
    }

    private void renderPackShadow(double camX, double camY, double camZ,
                                  Matrix4f cameraModelView, Matrix4f cameraProjection,
                                  Matrix4f cleanProjection) {
        Renderer renderer = Renderer.getInstance();
        GraphicsPipeline shadow = PipelineManager.getPackShadowShader();
        if (shadow == null) return;
        if (Boolean.getBoolean("vulkanmod.disablePackShadow")) {
            // Runtime isolation switch only. It lets us distinguish a device
            // loss in the shadow render/secondary-depth copy from a failure
            // in the camera G-buffer or composite chain without changing the
            // normal High-quality path.
            Initializer.LOGGER.warn("Pack shadow stage disabled by diagnostic property");
            return;
        }

        ClientLevel level = this.level;
        long worldDayTime = level == null ? 6000L : level.getOverworldClockTime();
        long debugTime = Long.getLong("vulkanmod.debugTime", -1L);
        long dayTime = debugTime >= 0L ? debugTime : worldDayTime;
        float sunAngle = Math.floorMod(dayTime, 24000L) / 24000.0f;
        var config = PipelineManager.getPackConfig();
        float distance = config == null ? 192.0f : (float) config.shadowDistance();
        PackShadowMatrices.State matrices = PackShadowMatrices.compute(
                sunAngle, (float) camX, (float) camY, (float) camZ,
                distance, PackFramebuffers.getShadowResolution());
        activeShadowMatrices = matrices;
        PackShadowPipeline.setMatrices(shadow, matrices);

        renderer.endRenderPass();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PackFramebuffers.beginShadow(Renderer.getCommandBuffer(), stack,
                    PackShadowPipeline.getDrawBuffers(shadow));
        }
        // Shadow rendering owns its raster state.  Do not inherit the
        // fullscreen sky/composite state (depth disabled, writes masked, or
        // non-triangle topology) from the preceding camera pass.
        GlStateManager._disableBlend();
        GlStateManager._disableCull();
        VRenderSystem.cullMode = org.lwjgl.vulkan.VK10.VK_CULL_MODE_NONE;
        GlStateManager._colorMask(com.mojang.blaze3d.pipeline.ColorTargetState.WRITE_ALL);
        VRenderSystem.colorMask(true, true, true, true);
        GlStateManager._enableDepthTest();
        GlStateManager._depthMask(true);
        // Shadow rendering must not inherit wireframe/point mode or polygon
        // offset from a preceding debug/terrain pass.  In line mode the
        // shadow attachment contains only sparse triangle edges, which looks
        // exactly like an empty shadow map when sampled by the composite.
        GlStateManager._disablePolygonOffset();
        VRenderSystem.setPolygonModeGL(GL11.GL_FILL);
        VRenderSystem.glDepthFun(Boolean.getBoolean("vulkanmod.debugShadowDepthAlways")
                ? GL11.GL_ALWAYS : GL11.GL_LEQUAL);
        VRenderSystem.setPrimitiveTopologyGL(GL11.GL_TRIANGLES);
        renderer.bindGraphicsPipeline(shadow);

        TextureManager textureManager = Minecraft.getInstance().getTextureManager();
        AbstractTexture atlasTexture = textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS);
        var texView = atlasTexture.getTextureView();
        var texture = (VkGpuTexture) texView.texture();
        texture.getVulkanImage().setSampler(this.terrainSampler);
        VRenderSystem.setShaderTexture(0, texView);
        VRenderSystem.setShaderTexture(2, Minecraft.getInstance().gameRenderer.lightmap());
        VTextureSelector.bindShaderTextures(shadow);
        // The pack's shadow vertex shader reconstructs the original caster
        // position from ftransform() using shadowProjectionInverse and
        // shadowModelViewInverse.  Uploading the camera MVP here (before the
        // light MVP is installed) makes that reconstruction land in the
        // wrong space and produces an apparently empty shadow map.
        // VRenderSystem's legacy MVP is the host/Vulkan-facing matrix. The
        // pack-facing shadowProjection remains OpenGL convention and is
        // converted once by PackShadowPipeline.update before ftransform() is
        // evaluated. Feeding the OpenGL form here would apply that conversion
        // twice and put the casters in the wrong light-space depth range.
        VRenderSystem.applyMVP(matrices.modelView(), matrices.renderProjection());
        PackShadowPipeline.update(shadow, cleanProjection);

        int currentFrame = Renderer.getCurrentFrame();
        UBO sectionData = shadow.getUBO(2);
        sectionData.setUseGlobalBuffer(false);
        IndexBuffer indexBuffer = Renderer.getDrawer().getQuadsIndexBuffer().getIndexBuffer();
        Renderer.getDrawer().bindIndexBuffer(Renderer.getCommandBuffer(), indexBuffer, indexBuffer.indexType.value);
        long currentTimeMs = System.currentTimeMillis();
        int fadeTimeMs = 0;
        float fadeTimeInv = 1.0f;
        int shadowDrawn = 0;
        int shadowAreasInFrustum = 0;
        int shadowAreasChecked = 0;
        boolean debugShadowBounds = Boolean.getBoolean("vulkanmod.debugShadowBounds");
        StringBuilder shadowBounds = debugShadowBounds ? new StringBuilder() : null;
        // Pack shadow vertex shaders index vm_SectionData from the draw's
        // firstInstance.  The camera pack path already uses direct indexed
        // draws because that contract is not guaranteed by the legacy
        // indirect batching path; use the same path for casters so trees and
        // terrain cannot silently miss the shadow map.
        final boolean shadowIndirect = false;
        // Cutout geometry is essential for foliage, panes and many block
        // entities.  Complementary expects it in shadowtex0 just like solid
        // terrain, with the pack's shadow fragment performing alpha discard.
        for (TerrainRenderType casterType : new TerrainRenderType[]{TerrainRenderType.SOLID, TerrainRenderType.CUTOUT}) {
            casterType.setCutoutUniform();
            for (Iterator<ChunkArea> iterator = this.sectionGraph.getChunkAreaQueue().iterator(false); iterator.hasNext();) {
                ChunkArea area = iterator.next();
                DrawBuffers buffers = area.drawBuffers;
                if (buffers.getAreaBuffer(casterType) == null || area.sectionQueue.size() == 0) continue;
                shadowDrawn++;
                if (debugShadowBounds && shadowAreasChecked < 8) {
                    var areaPos = area.getPosition();
                    float minX = (float) (areaPos.x() - camX);
                    float minY = (float) (areaPos.y() - camY);
                    float minZ = (float) (areaPos.z() - camZ);
                    var bounds = PackShadowMatrices.transformBounds(matrices,
                            minX, minY, minZ, minX + 128.0f, minY + 128.0f, minZ + 128.0f);
                    if (bounds.intersectsUnitCube()) shadowAreasInFrustum++;
                    shadowBounds.append(" area[").append(areaPos.x()).append(',')
                            .append(areaPos.y()).append(',').append(areaPos.z())
                            .append("] ndc=").append(bounds)
                            .append(" inside=").append(bounds.intersectsUnitCube());
                    shadowAreasChecked++;
                }
                buffers.bindBuffers(Renderer.getCommandBuffer(), shadow, casterType,
                        sectionData, camX, camY, camZ, currentTimeMs, fadeTimeMs, fadeTimeInv);
                renderer.uploadAndBindUBOs(shadow);
                if (Boolean.getBoolean("vulkanmod.debugShadowDraws")) {
                    var firstSection = area.sectionQueue.iterator(false);
                    while (firstSection.hasNext()) {
                        if (buffers.debugPackShadowDraw(firstSection.next(), casterType)) break;
                    }
                }
                if (shadowIndirect) {
                    buffers.buildDrawBatchesIndirect(cameraPos, indirectBuffers[currentFrame],
                            area.sectionQueue, casterType, true);
                } else {
                    buffers.buildDrawBatchesDirectPack(cameraPos, area.sectionQueue, casterType, true);
                }
            }
        }
        if (shadowIndirect) indirectBuffers[currentFrame].submitUploads();
        if (!shadowIndirect) {
            VRenderSystem.setModelOffset(0, 0, 0);
            renderer.pushConstants(shadow);
        }
        PackFramebuffers.endShadow(Renderer.getCommandBuffer());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PackFramebuffers.copyShadowDepthToSecondary(Renderer.getCommandBuffer(), stack);
            PackFramebuffers.transitionShadowsToRead(Renderer.getCommandBuffer(), stack);
        }
        VRenderSystem.applyMVP(cameraModelView, cameraProjection);
        PackDebug.setShadowDrawn(shadowDrawn);
        if (PackDebug.shouldLog()) {
            Initializer.LOGGER.info("VulkanMod DRAW: {}", PackDebug.shadowSummary());
        }
        if (debugShadowBounds && PackDebug.shouldLog()) {
            Initializer.LOGGER.info("VulkanMod SHADOW_BOUNDS: checked={} inside={}{}",
                    shadowAreasChecked, shadowAreasInFrustum,
                    shadowBounds == null ? "" : shadowBounds);
        }
        Initializer.LOGGER.debug("Pack shadow rendered at angle {}", sunAngle);
    }

    /** Render submitted entity/block-entity shadow quads into the populated
     * shadow map without clearing the terrain depth/color attachments. */
    public void renderPackEntityShadows() {
        GraphicsPipeline shadow = PipelineManager.getPackEntityShadowShader();
        if (shadow == null || activeShadowMatrices == null) return;

        Renderer renderer = Renderer.getInstance();
        renderer.endRenderPass();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PackFramebuffers.beginShadow(Renderer.getCommandBuffer(), stack,
                    PackShadowPipeline.getDrawBuffers(shadow), false);
        }
        GlStateManager._disableBlend();
        // Entity and foliage shadow meshes are two-sided in the light pass.
        GlStateManager._disableCull();
        VRenderSystem.cullMode = org.lwjgl.vulkan.VK10.VK_CULL_MODE_NONE;
        GlStateManager._enableDepthTest();
        GlStateManager._depthMask(true);
        GlStateManager._disablePolygonOffset();
        VRenderSystem.setPolygonModeGL(GL11.GL_FILL);
        VRenderSystem.glDepthFun(GL11.GL_LEQUAL);
        VRenderSystem.setPrimitiveTopologyGL(GL11.GL_TRIANGLES);
        renderer.bindGraphicsPipeline(shadow);
        PackShadowPipeline.setMatrices(shadow, activeShadowMatrices);
        // RenderTypeM reads RenderSystem's model-view matrix when it uploads
        // each immediate entity mesh.  Setting only Vulkan's cached MVP here
        // leaves those meshes in camera space while using the shadow
        // projection, so the draw count looks healthy but the caster lands
        // outside the directional shadow map.
        VRenderSystem.applyModelViewMatrix(activeShadowMatrices.modelView());
        // Entity shadow meshes use the same host MVP contract as terrain:
        // rasterize with Vulkan's zero-to-one projection, while the pack UBO
        // exposes the conventional OpenGL shadowProjection.
        VRenderSystem.applyProjectionMatrix(activeShadowMatrices.renderProjection());
        VRenderSystem.calculateMVP();
        org.joml.Matrix4fStack shadowModelViewStack = RenderSystem.getModelViewStack();
        shadowModelViewStack.pushMatrix();
        shadowModelViewStack.set(activeShadowMatrices.modelView());
        PackShadowPipeline.update(shadow, null);
        VTextureSelector.bindShaderTextures(shadow);

        FeatureRenderDispatcherAccessor access = (FeatureRenderDispatcherAccessor) (Object) this.featureRenderDispatcher;
        var bufferSource = access.vulkanmod$getBufferSource();
        var outlineBufferSource = access.vulkanmod$getOutlineBufferSource();
        var crumblingBufferSource = access.vulkanmod$getCrumblingBufferSource();
        int collections = 0;
        PackDebug.setEntityShadowDraws(0);
        PackShadowPipeline.beginEntityShadowPass();
        try {
            for (SubmitNodeCollection collection : this.featureRenderDispatcher.getSubmitNodeStorage()
                    .getSubmitsPerOrder().values()) {
                // Render the actual entity/model geometry in light space. The
                // vanilla shadow renderer only emits a projected ground blob;
                // it cannot populate Complementary's directional shadow map
                // for the player, mobs, armor, or held items.
                access.vulkanmod$getModelFeatureRenderer().renderSolid(
                        collection, bufferSource, outlineBufferSource, crumblingBufferSource);
                access.vulkanmod$getModelPartFeatureRenderer().renderSolid(
                        collection, bufferSource, outlineBufferSource, crumblingBufferSource);
                access.vulkanmod$getItemFeatureRenderer().renderSolid(
                        collection, bufferSource, outlineBufferSource);
                access.vulkanmod$getModelFeatureRenderer().renderTranslucent(
                        collection, bufferSource, outlineBufferSource, crumblingBufferSource);
                access.vulkanmod$getModelPartFeatureRenderer().renderTranslucent(
                        collection, bufferSource, outlineBufferSource, crumblingBufferSource);
                access.vulkanmod$getItemFeatureRenderer().renderTranslucent(
                        collection, bufferSource, outlineBufferSource);
                collections++;
            }
            bufferSource.endBatch();
        } finally {
            PackShadowPipeline.endEntityShadowPass();
            shadowModelViewStack.popMatrix();
            PackFramebuffers.endShadow(Renderer.getCommandBuffer());
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PackFramebuffers.copyShadowDepthToSecondary(Renderer.getCommandBuffer(), stack);
                PackFramebuffers.transitionShadowsToRead(Renderer.getCommandBuffer(), stack);
            }
        }
        if (lastCameraModelView != null && lastCameraProjection != null) {
            VRenderSystem.applyMVP(lastCameraModelView, lastCameraProjection);
            Renderer.getInstance().getMainPass().rebindMainTarget();
        }
        PackDebug.setEntityShadowCollections(collections);
    }

    private void renderPackSky(Matrix4f modelView, Matrix4f projection, Matrix4f cleanProjection) {
        Renderer renderer = Renderer.getInstance();
        GraphicsPipeline sky = PipelineManager.getPackSkyShader();
        if (sky == null) return;
        PackDebug.log("sky pass begin");
        renderer.endRenderPass();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PackFramebuffers.beginSky(Renderer.getCommandBuffer(), stack);
        }
        // GraphicsPipeline lazily creates a Vulkan variant from the current
        // GL-derived state at bind time. Set the fullscreen state before the
        // first bind; setting it only while creating PackSkyPipeline is not
        // sufficient because the actual variant may be created here.
        GlStateManager._disableCull();
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(false);
        GlStateManager._disableBlend();
        VRenderSystem.setPrimitiveTopologyGL(GL11.GL_TRIANGLES);
        renderer.bindGraphicsPipeline(sky);
        VTextureSelector.bindShaderTextures(sky);
        VRenderSystem.applyMVP(modelView, projection);
        PackSkyPipeline.update(sky, cleanProjection);
        renderer.uploadAndBindUBOs(sky);
        QuadRenderer.renderQuad(Renderer.getCommandBuffer());
        PackFramebuffers.endSky(Renderer.getCommandBuffer());
    }

    private void sortTranslucentSections(double camX, double camY, double camZ) {
        ProfilerFiller mcProfiler = net.minecraft.util.profiling.Profiler.get();
        mcProfiler.push("translucent_sort");
        double d0 = camX - this.xTransparentOld;
        double d1 = camY - this.yTransparentOld;
        double d2 = camZ - this.zTransparentOld;
        if (d0 * d0 + d1 * d1 + d2 * d2 > 2.0D) {
            this.xTransparentOld = camX;
            this.yTransparentOld = camY;
            this.zTransparentOld = camZ;
            int j = 0;

            Iterator<RenderSection> iterator = this.sectionGraph.getSectionQueue().iterator(false);

            while (iterator.hasNext() && j < 200) {
                RenderSection section = iterator.next();
                section.resortTransparency(this.taskDispatcher, this.cameraPos);

                if (!section.isCompletelyEmpty()) {
                    ++j;
                }
            }
        }

        mcProfiler.pop();
    }

    public void renderBlockEntities(PoseStack poseStack, LevelRenderState levelRenderState,
                                    SubmitNodeStorage submitNodeStorage,
                                    Long2ObjectMap<SortedSet<BlockDestructionProgress>> destructionProgress) {
        Profiler profiler = Profiler.getMainProfiler();
        profiler.pop();
        profiler.push("Block-entities");

        Vec3 vec3 = levelRenderState.cameraRenderState.pos;
        double camX = vec3.x();
        double camY = vec3.y();
        double camZ = vec3.z();

        for (RenderSection renderSection : this.sectionGraph.getBlockEntitiesSections()) {
            List<BlockEntity> list = renderSection.getCompiledSection().getBlockEntities();
            if (!list.isEmpty()) {
                for (BlockEntity blockEntity : list) {
                    BlockPos blockPos = blockEntity.getBlockPos();
                    SortedSet<BlockDestructionProgress> sortedSet = destructionProgress.get(blockPos.asLong());
                    ModelFeatureRenderer.CrumblingOverlay crumblingOverlay;
                    if (sortedSet != null && !sortedSet.isEmpty()) {
                        poseStack.pushPose();
                        poseStack.translate(blockPos.getX() - camX, blockPos.getY() - camY, blockPos.getZ() - camZ);
                        crumblingOverlay = new ModelFeatureRenderer.CrumblingOverlay(sortedSet.last()
                                                                                              .getProgress(), poseStack.last());
                        poseStack.popPose();
                    } else {
                        crumblingOverlay = null;
                    }

                    BlockEntityRenderState blockEntityRenderState = this.blockEntityRenderDispatcher.tryExtractRenderState(blockEntity, this.partialTick, crumblingOverlay);
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
                BlockEntityRenderState blockEntityRenderState2 = this.blockEntityRenderDispatcher.tryExtractRenderState(blockEntity2, this.partialTick, null);
                if (blockEntityRenderState2 != null) {
                    levelRenderState.blockEntityRenderStates.add(blockEntityRenderState2);
                }
            }
        }

        for (BlockEntityRenderState blockEntityRenderState : levelRenderState.blockEntityRenderStates) {
            BlockPos blockPos = blockEntityRenderState.blockPos;
            poseStack.pushPose();
            poseStack.translate(blockPos.getX() - camX, blockPos.getY() - camY, blockPos.getZ() - camZ);
            var blockEntityRenderDispatcher = this.minecraft.getBlockEntityRenderDispatcher();
            blockEntityRenderDispatcher.submit(blockEntityRenderState, poseStack, submitNodeStorage, levelRenderState.cameraRenderState);
            poseStack.popPose();
        }
    }

    private static String shaderDimension(ClientLevel level) {
        String path = level.dimension().identifier().getPath();
        return switch (path) {
            case "the_nether", "nether" -> "world-1";
            case "the_end", "end" -> "world1";
            default -> "world0";
        };
    }

    public void resetSampler() {
        this.terrainSampler = 0L;
    }

    public void setPartialTick(float partialTick) {
        this.partialTick = partialTick;
    }

    public void scheduleGraphUpdate() {
        this.graphNeedsUpdate = true;
    }

    public boolean graphNeedsUpdate() {
        return this.graphNeedsUpdate;
    }

    public int getVisibleSectionsCount() {
        return this.sectionGraph.getSectionQueue().size();
    }

    public void setSectionDirty(int x, int y, int z, boolean flag) {
        this.sectionGrid.setDirty(x, y, z, flag);

        this.renderRegionCache.remove(x, z);
    }

    public void onChunkReadyToRender(ChunkPos chunkPos) {
        if (this.sectionGrid != null) {
            for (int x1 = chunkPos.x() - 1; x1 <= chunkPos.x() + 1; ++x1) {
                for (int z1 = chunkPos.z() - 1; z1 <= chunkPos.z() + 1; ++z1) {
                    if (this.renderRegionCache != null) {
                        this.renderRegionCache.remove(x1, z1);
                    }
                    for (RenderSection section : this.sectionGrid.getRenderSectionsAt(x1, z1)) {
                        if (section != null && (section.xOffset >> 4) == x1 && (section.zOffset >> 4) == z1) {
                            section.setDirty(false);
                        }
                    }
                }
            }
            this.graphNeedsUpdate = true;
        }
    }

    public SectionGrid getSectionGrid() {
        return this.sectionGrid;
    }

    public ChunkAreaManager getChunkAreaManager() {
        if (this.sectionGrid == null)
            return null;
        return this.sectionGrid.chunkAreaManager;
    }

    public TaskDispatcher getTaskDispatcher() {
        return taskDispatcher;
    }

    public short getLastFrame() {
        return this.sectionGraph.getLastFrame();
    }

    public int getRenderDistance() {
        return this.renderDistance;
    }

    public String getChunkStatistics() {
        if (this.sectionGraph == null) {
            return null;
        }

        return this.sectionGraph.getStatistics();
    }

    public void cleanUp() {
        if (indirectBuffers != null)
            Arrays.stream(indirectBuffers).forEach(Buffer::scheduleFree);
    }

    public static WorldRenderer getInstance() {
        return INSTANCE;
    }

    /** Light-space state for the camera G-buffer and immediate entity draws. */
    public static PackShadowMatrices.State getActiveShadowMatrices() {
        return INSTANCE == null ? null : INSTANCE.activeShadowMatrices;
    }

    public static ClientLevel getLevel() {
        return INSTANCE.level;
    }

    public static Vector3d getCameraPos() {
        return INSTANCE.cameraPos;
    }

}
