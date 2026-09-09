package net.vulkanmod.render.shader;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.vulkanmod.render.chunk.build.thread.ThreadBuilderPack;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.shader.*;
import net.vulkanmod.Initializer;
import net.vulkanmod.shaders.*;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.render.texture.ImageUploadHelper;
import net.vulkanmod.vulkan.pass.DefaultMainPass;
import net.vulkanmod.vulkan.pass.MainPass;
import net.minecraft.client.Minecraft;

import java.util.function.Function;
import java.util.LinkedHashMap;
import java.util.Map;

public abstract class PipelineManager {
    public static VertexFormat terrainVertexFormat;

    static GraphicsPipeline
            terrainShader, terrainShaderEarlyZ,
            fastBlitPipeline, cloudsPipeline, packTerrainShader, packWaterShader;
    static GraphicsPipeline packShadowShader, packEntityShadowShader;
    static GraphicsPipeline packSkyShader, packSkyTexturedShader, packCloudShader, packWeatherShader;
    static GraphicsPipeline packEntityShader;
    static GraphicsPipeline packHandShader;
    private static final Map<String, GraphicsPipeline> packGBufferShaders = new LinkedHashMap<>();
    private static PackProgramRegistry packPrograms;
    private static ShaderPackConfig packConfig;
    private static ShaderPack activePack;
    /** Published only after the pack graph has completed construction. */
    private static boolean packRuntimeReady;
    private static String activeDimension = "world0";
    private static MainPass nativeMainPass;
    private static PackMainPass packMainPass;
    private static boolean loggedMainPassSelection;

    private static Function<TerrainRenderType, GraphicsPipeline> shaderGetter;

    public static void init() {
        nativeMainPass = Renderer.getInstance().getMainPass();
        setTerrainVertexFormat(CustomVertexFormat.COMPRESSED_TERRAIN);
        createBasicPipelines();
        setDefaultTerrainShaderGetter();
        if (Initializer.CONFIG.isShaderPackEnabled()
                && !Boolean.getBoolean("vulkanmod.disablePackRuntime")
                && ShaderPackManager.get() != null && ShaderPackManager.get().hasActivePack()) {
            try {
                ShaderPack pack = ShaderPackManager.get().getActivePack();
                initializePackPipelines(pack, "world0");
                activePack = pack;
                packRuntimeReady = true;
                if (Boolean.getBoolean("vulkanmod.disablePackMainPass")) {
                    // Diagnostic split: leave the native swapchain main pass
                    // active while still constructing pack resources and
                    // pipelines.  This separates main-pass/framebuffer
                    // recording faults from pack initialization faults.
                    Initializer.LOGGER.warn("Shader-pack main pass disabled by diagnostic property");
                }
            } catch (Exception e) {
                Initializer.LOGGER.error("Failed to initialize shader-pack pipeline; using built-in shaders", e);
                packTerrainShader = null;
                packWaterShader = null;
                packSkyShader = null;
                packSkyTexturedShader = null;
                packCloudShader = null;
                packWeatherShader = null;
                packEntityShader = null;
                packEntityShadowShader = null;
                packHandShader = null;
                PackCompositePipeline.destroy();
                Renderer.getInstance().setMainPass(DefaultMainPass.create());
                setDefaultTerrainShaderGetter();
                setTerrainVertexFormat(CustomVertexFormat.COMPRESSED_TERRAIN);
                activePack = null;
                packRuntimeReady = false;
            }
        } else if (Initializer.CONFIG.isShaderPackEnabled()
                && Boolean.getBoolean("vulkanmod.disablePackRuntime")) {
            Initializer.LOGGER.warn("Shader-pack runtime disabled by diagnostic property");
        }
        ThreadBuilderPack.defaultTerrainBuilderConstructor();
    }

    /**
     * Select the pack main pass only at a frame boundary.  Menu/resource
     * reload frames must retain the native swapchain pass; switching the
     * renderer while a menu frame is being assembled can make native GUI
     * pipelines target the pack G-buffer and can invalidate the command
     * buffer on submission.
     */
    public static void ensurePackMainPassForFrame() {
        if (activePack == null || Boolean.getBoolean("vulkanmod.disablePackMainPass")) return;
        boolean world = Minecraft.getInstance().level != null;
        if (world && packMainPass == null && activePack != null && packRuntimeReady) {
            try {
                // Construct this only at a frame boundary after a ClientLevel
                // exists.  PackMainPass allocates pack-owned framebuffer
                // wrappers; doing that during title/resource startup can
                // interfere with the native swapchain pass.
                packMainPass = new PackMainPass(activePack);
            } catch (Throwable t) {
                Initializer.LOGGER.error("Failed to activate shader-pack main pass for world frame", t);
                return;
            }
        }
        MainPass desired = world ? packMainPass : nativeMainPass;
        if (System.getProperty("vulkanmod.debugPack") != null && !loggedMainPassSelection) {
            Initializer.LOGGER.info("Pack main-pass selection: world={}, current={}, desired={}",
                    world,
                    Renderer.getInstance().getMainPass() == null
                            ? "null" : Renderer.getInstance().getMainPass().getClass().getSimpleName(),
                    desired == null ? "null" : desired.getClass().getSimpleName());
            loggedMainPassSelection = true;
        }
        if (desired != null && Renderer.getInstance().getMainPass() != desired) {
            Renderer.getInstance().setMainPass(desired);
        }
    }

    /** Build all pack-owned pipelines for one Iris dimension namespace. */
    private static void initializePackPipelines(ShaderPack pack, String dimension) throws Exception {
        packConfig = ShaderPackConfig.load(pack);
        activeDimension = dimension;
        Initializer.LOGGER.info("Shader-pack profile {} resolved for {}: {}",
                packConfig.profile().tier(), dimension, packConfig.profileValues());
        packPrograms = PackProgramRegistry.discover(pack, packConfig, dimension);
        Initializer.LOGGER.info("Shader pack programs [{}]: gbuffer={}, shadow={}, compute={}, post={}, final={}",
                dimension, packPrograms.gBuffers().size(), packPrograms.shadows().size(),
                packPrograms.compute().size(), packPrograms.post().size(),
                packPrograms.finalProgram() == null ? "none" : packPrograms.finalProgram().name());
        // Allocate pack resources before pipeline descriptors are built so
        // per-program custom textures are available to ImageDescriptor.
        var swapChain = Renderer.getInstance().getSwapChain();
        if (swapChain != null && !Boolean.getBoolean("vulkanmod.disablePackFramebuffers")) {
            PackFramebuffers.init(swapChain.getWidth(), swapChain.getHeight(), pack, packConfig,
                    packConfig.shadowResolution(dimension));
        } else if (Boolean.getBoolean("vulkanmod.disablePackFramebuffers")) {
            Initializer.LOGGER.warn("Shader-pack framebuffer construction disabled by diagnostic property");
        }
        if (!Boolean.getBoolean("vulkanmod.disablePackShadow")
                && packConfig.shadowsEnabled()
                && packPrograms.shadows().stream().anyMatch(p -> p.name().equals("shadow"))) {
            var initialShadow = PackShadowMatrices.compute(0.25f, 0.0f, 0.0f, 0.0f,
                    (float) packConfig.shadowDistance(dimension), packConfig.shadowResolution(dimension));
            packShadowShader = PackShadowPipeline.create(pack, dimension, initialShadow);
            packEntityShadowShader = PackShadowPipeline.create(pack, dimension, initialShadow,
                    DefaultVertexFormat.ENTITY, false);
            Initializer.LOGGER.info("Loaded shader-pack shadow pipeline ({}px, distance={})",
                    packConfig.shadowResolution(dimension), packConfig.shadowDistance(dimension));
            Initializer.LOGGER.info("Loaded shader-pack entity shadow pipeline");
        }
        boolean disablePackGBuffer = Boolean.getBoolean("vulkanmod.disablePackGBuffer");
        if (disablePackGBuffer) {
            Initializer.LOGGER.warn("Shader-pack G-buffer pipeline construction disabled by diagnostic property");
        } else {
        packTerrainShader = PackTerrainPipeline.create(pack, dimension);
        if (packPrograms.gBuffers().stream().anyMatch(p -> p.name().equals("gbuffers_entities"))) {
            packEntityShader = PackTerrainPipeline.create(pack, dimension, "gbuffers_entities",
                    DefaultVertexFormat.ENTITY, false);
            Initializer.LOGGER.info("Loaded shader-pack entity G-buffer pipeline");
        }
        if (packPrograms.gBuffers().stream().anyMatch(p -> p.name().equals("gbuffers_hand"))) {
            packHandShader = PackTerrainPipeline.create(pack, dimension, "gbuffers_hand",
                    DefaultVertexFormat.ENTITY, false);
            Initializer.LOGGER.info("Loaded shader-pack hand G-buffer pipeline");
        }
        // Build the remaining immediate G-buffer categories from the same
        // reusable pack pipeline.  Discovery alone is not compatibility:
        // without this map block entities, translucent/glowing entities,
        // glint, particles, and line-like render types silently fell back to
        // the native pipeline and never reached the pack's MRTs.
        for (var program : packPrograms.gBuffers()) {
            String name = program.name();
            if (name.equals("gbuffers_terrain") || name.equals("gbuffers_water")
                    || name.equals("gbuffers_entities") || name.equals("gbuffers_hand")
                    || name.equals("gbuffers_skybasic") || name.equals("gbuffers_skytextured")
                    || name.equals("gbuffers_clouds") || name.equals("gbuffers_weather")) {
                continue;
            }
            try {
                GraphicsPipeline pipeline = PackTerrainPipeline.create(pack, dimension, name,
                        DefaultVertexFormat.ENTITY, false);
                packGBufferShaders.put(name, pipeline);
                Initializer.LOGGER.info("Loaded shader-pack G-buffer pipeline: {}", name);
            } catch (Exception e) {
                Initializer.LOGGER.warn("Failed to load shader-pack G-buffer {}: {}",
                        name, e.toString());
            }
        }
        if (packPrograms.gBuffers().stream().anyMatch(p -> p.name().equals("gbuffers_skybasic"))) {
            packSkyShader = PackSkyPipeline.create(pack, dimension, "gbuffers_skybasic");
            Initializer.LOGGER.info("Loaded shader-pack sky G-buffer pipeline");
        }
        if (packPrograms.gBuffers().stream().anyMatch(p -> p.name().equals("gbuffers_skytextured"))) {
            packSkyTexturedShader = PackSkyPipeline.create(pack, dimension, "gbuffers_skytextured");
            if (packSkyShader == null) packSkyShader = packSkyTexturedShader;
            Initializer.LOGGER.info("Loaded shader-pack textured-sky G-buffer pipeline");
        }
        if (packPrograms.gBuffers().stream().anyMatch(p -> p.name().equals("gbuffers_clouds"))) {
            packCloudShader = PackTerrainPipeline.create(pack, dimension, "gbuffers_clouds",
                    DefaultVertexFormat.POSITION_TEX_COLOR, false);
            Initializer.LOGGER.info("Loaded shader-pack clouds G-buffer pipeline");
        }
        if (packPrograms.gBuffers().stream().anyMatch(p -> p.name().equals("gbuffers_weather"))) {
            packWeatherShader = PackTerrainPipeline.create(pack, dimension, "gbuffers_weather",
                    DefaultVertexFormat.PARTICLE, false);
            Initializer.LOGGER.info("Loaded shader-pack weather G-buffer pipeline");
        }
        if (pack.exists("shaders/" + dimension + "/gbuffers_water.vsh")
                || pack.exists("shaders/gbuffers_water.vsh")) {
            packWaterShader = PackTerrainPipeline.create(pack, dimension, "gbuffers_water");
            Initializer.LOGGER.info("Loaded shader-pack water G-buffer pipeline");
        }
        setTerrainVertexFormat(CustomVertexFormat.TERRAIN);
        setShaderGetter(renderType -> renderType == TerrainRenderType.TRANSLUCENT
                && packWaterShader != null ? packWaterShader : packTerrainShader);
        }

        if (Boolean.getBoolean("vulkanmod.disablePackComposite")) {
            Initializer.LOGGER.warn("Shader-pack composite pipeline construction disabled by diagnostic property");
            setDefaultTerrainShaderGetter();
        } else {
            PackCompositePipeline.init(pack, dimension);
        }
        Initializer.LOGGER.info("Shader-pack pipeline active for {} (full deferred/composite/final): {}",
                dimension, pack.getName());
    }

    public static void setDefaultTerrainShaderGetter() {
        setShaderGetter(renderType -> terrainShader);
    }

    private static void createBasicPipelines() {
        terrainShader = createPipeline("terrain", "basic", PipelineConfigs.TERRAIN, CustomVertexFormat.COMPRESSED_TERRAIN);
        terrainShaderEarlyZ = createPipeline("terrain_early_z", "basic", CustomVertexFormat.COMPRESSED_TERRAIN);
        fastBlitPipeline = createPipeline("blit", "basic/blit", CustomVertexFormat.NONE);
        cloudsPipeline = createPipeline("clouds", "basic/clouds", DefaultVertexFormat.POSITION_COLOR);
    }

    private static GraphicsPipeline createPipeline(String configName, String shaderPath, PipelineConfig config, VertexFormat vertexFormat) {
        Pipeline.Builder pipelineBuilder = new Pipeline.Builder(vertexFormat, configName);

        final String path = ShaderLoadUtil.resolveShaderPath(shaderPath);

        pipelineBuilder.applyConfig(config);

        pipelineBuilder.setShaderSrc(SpirvCompiler.ShaderKind.VERTEX_SHADER, ShaderLoadUtil.loadShader(path, "%s.vsh".formatted(config.shaderPaths.get(SpirvCompiler.ShaderKind.VERTEX_SHADER))));
        pipelineBuilder.setShaderSrc(SpirvCompiler.ShaderKind.FRAGMENT_SHADER, ShaderLoadUtil.loadShader(path, "%s.fsh".formatted(config.shaderPaths.get(SpirvCompiler.ShaderKind.FRAGMENT_SHADER))));

        var pipeline = pipelineBuilder.createGraphicsPipeline();

        for (var buffer : pipeline.getBuffers()) {
            buffer.setUseGlobalBuffer(true);
        }

        return pipeline;
    }

    private static GraphicsPipeline createPipeline(String configName, String shaderPath, VertexFormat vertexFormat) {
        Pipeline.Builder pipelineBuilder = new Pipeline.Builder(vertexFormat, configName);

        final String path = ShaderLoadUtil.resolveShaderPath(shaderPath);
        JsonObject config = ShaderLoadUtil.getJsonConfig(path, configName);
        var pipelineConfig = PipelineConfig.fromJson(configName, config);
        pipelineBuilder.applyConfig(pipelineConfig);

        pipelineBuilder.setShaderSrc(SpirvCompiler.ShaderKind.VERTEX_SHADER, ShaderLoadUtil.loadShader(path, "%s.vsh".formatted(pipelineConfig.shaderPaths.get(SpirvCompiler.ShaderKind.VERTEX_SHADER))));
        pipelineBuilder.setShaderSrc(SpirvCompiler.ShaderKind.FRAGMENT_SHADER, ShaderLoadUtil.loadShader(path, "%s.fsh".formatted(pipelineConfig.shaderPaths.get(SpirvCompiler.ShaderKind.FRAGMENT_SHADER))));

        var pipeline = pipelineBuilder.createGraphicsPipeline();

        for (var buffer : pipeline.getBuffers()) {
            buffer.setUseGlobalBuffer(true);
        }

        return pipeline;
    }

    public static GraphicsPipeline getTerrainShader(TerrainRenderType renderType) {
        if (Boolean.getBoolean("vulkanmod.disablePackGBuffer")) {
            return terrainShader;
        }
        return shaderGetter.apply(renderType);
    }

    public static void setShaderGetter(Function<TerrainRenderType, GraphicsPipeline> consumer) {
        shaderGetter = consumer;
    }

    public static void setTerrainVertexFormat(VertexFormat format) {
        terrainVertexFormat = format;
    }

    public static VertexFormat getTerrainVertexFormat() {
        return terrainVertexFormat;
    }

    public static GraphicsPipeline getTerrainDirectShader(RenderType renderType) {
        return terrainShader;
    }

    public static GraphicsPipeline getTerrainIndirectShader(RenderType renderType) {
        return terrainShaderEarlyZ;
    }

    public static GraphicsPipeline getFastBlitPipeline() {
        return fastBlitPipeline;
    }

    public static GraphicsPipeline getCloudsPipeline() {
        return cloudsPipeline;
    }

    public static GraphicsPipeline getPackTerrainShader() {
        return packTerrainShader;
    }

    public static boolean isPackShader(GraphicsPipeline pipeline) {
        return pipeline != null && (pipeline == packTerrainShader || pipeline == packWaterShader
                || packGBufferShaders.containsValue(pipeline));
    }

    public static GraphicsPipeline getPackGBufferShader(String program) {
        return packGBufferShaders.get(program);
    }

    /** Rebuild pack programs when the client changes dimension. */
    public static synchronized void reloadPackDimension(String dimension) {
        if (activePack == null || dimension == null || dimension.equals(activeDimension)) return;
        ShaderPack pack = activePack;
        try {
            packRuntimeReady = false;
            // Pack reload replaces pipelines, descriptors, and framebuffer
            // images.  Those objects may still be referenced by the command
            // buffer submitted for the frame that triggered the dimension
            // change; destroying them immediately can poison the device and
            // only report VK_ERROR_DEVICE_LOST on a later fence wait.
            ImageUploadHelper.INSTANCE.submitCommands();
            Vulkan.waitIdle();
            invalidatePackMainPass();
            destroyPackPipelines();
            // Pipeline descriptors may reference pack-owned custom images.
            // Recreate those images on a dimension transition even when the
            // swapchain dimensions and shadow resolution are unchanged.
            PackFramebuffers.cleanUp();
            initializePackPipelines(pack, dimension);
            packRuntimeReady = true;
            Initializer.LOGGER.info("Reloaded shader-pack programs for dimension {}", dimension);
        } catch (Exception e) {
            Initializer.LOGGER.error("Failed to reload shader-pack dimension {}; disabling pack pipelines", dimension, e);
            destroyPackPipelines();
            setDefaultTerrainShaderGetter();
            setTerrainVertexFormat(CustomVertexFormat.COMPRESSED_TERRAIN);
            packRuntimeReady = false;
        }
    }

    /** Recompile the active pack after a resource/shader reload. */
    public static synchronized void reloadActivePack() {
        if (activePack == null) return;
        ShaderPack pack = activePack;
        String dimension = activeDimension;
        try {
            packRuntimeReady = false;
            // Resource reload can replace every pack-owned Vulkan handle.
            // Drain prior submissions before tearing down the old graph.
            ImageUploadHelper.INSTANCE.submitCommands();
            Vulkan.waitIdle();
            invalidatePackMainPass();
            destroyPackPipelines();
            // A resource reload can replace custom textures in the archive
            // without changing the framebuffer dimensions. Do not retain
            // descriptors pointing at the old Vulkan image handles.
            PackFramebuffers.cleanUp();
            initializePackPipelines(pack, dimension);
            packRuntimeReady = true;
            Initializer.LOGGER.info("Reloaded shader-pack pipelines for {}", dimension);
        } catch (Exception e) {
            Initializer.LOGGER.error("Failed to reload active shader pack", e);
            destroyPackPipelines();
            setDefaultTerrainShaderGetter();
            setTerrainVertexFormat(CustomVertexFormat.COMPRESSED_TERRAIN);
            packRuntimeReady = false;
        }
    }

    private static void destroyPackPipelines() {
        if (PackCompositePipeline.isActive()) PackCompositePipeline.destroy();
        if (packTerrainShader != null) {
            packTerrainShader.cleanUp();
            PackTerrainPipeline.release(packTerrainShader);
            packTerrainShader = null;
        }
        if (packWaterShader != null) {
            packWaterShader.cleanUp();
            PackTerrainPipeline.release(packWaterShader);
            packWaterShader = null;
        }
        if (packSkyShader != null) {
            GraphicsPipeline sky = packSkyShader;
            sky.cleanUp();
            PackSkyPipeline.release(sky);
            packSkyShader = null;
            if (packSkyTexturedShader == sky) packSkyTexturedShader = null;
        }
        if (packSkyTexturedShader != null) {
            packSkyTexturedShader.cleanUp();
            PackSkyPipeline.release(packSkyTexturedShader);
            packSkyTexturedShader = null;
        }
        if (packCloudShader != null) {
            packCloudShader.cleanUp();
            PackTerrainPipeline.release(packCloudShader);
            packCloudShader = null;
        }
        if (packWeatherShader != null) {
            packWeatherShader.cleanUp();
            PackTerrainPipeline.release(packWeatherShader);
            packWeatherShader = null;
        }
        if (packEntityShader != null) {
            packEntityShader.cleanUp();
            PackTerrainPipeline.release(packEntityShader);
            packEntityShader = null;
        }
        if (packHandShader != null) {
            packHandShader.cleanUp();
            PackTerrainPipeline.release(packHandShader);
            packHandShader = null;
        }
        if (packShadowShader != null) {
            packShadowShader.cleanUp();
            PackShadowPipeline.release(packShadowShader);
            packShadowShader = null;
        }
        if (packEntityShadowShader != null) {
            packEntityShadowShader.cleanUp();
            PackShadowPipeline.release(packEntityShadowShader);
            packEntityShadowShader = null;
        }
        for (GraphicsPipeline pipeline : packGBufferShaders.values()) {
            pipeline.cleanUp();
            PackTerrainPipeline.release(pipeline);
        }
        packGBufferShaders.clear();
        packPrograms = null;
        packConfig = null;
    }

    public static void destroyPipelines() {
        terrainShaderEarlyZ.cleanUp();
        terrainShader.cleanUp();
        fastBlitPipeline.cleanUp();
        cloudsPipeline.cleanUp();
        if (PackCompositePipeline.isActive()) {
            PackCompositePipeline.destroy();
        }
        invalidatePackMainPass();
        PackFramebuffers.cleanUp();
        QuadRenderer.cleanUp();
        destroyPackPipelines();
        if (nativeMainPass != null) {
            Renderer.getInstance().setMainPass(nativeMainPass);
        }
        activePack = null;
        packRuntimeReady = false;
        activeDimension = "world0";
    }

    /** Drop pack framebuffer wrappers before their underlying images are replaced. */
    private static void invalidatePackMainPass() {
        if (packMainPass == null) return;
        if (Renderer.getInstance().getMainPass() == packMainPass && nativeMainPass != null) {
            Renderer.getInstance().setMainPass(nativeMainPass);
        }
        packMainPass.cleanUp();
        packMainPass = null;
    }

    public static PackProgramRegistry getPackPrograms() {
        return packPrograms;
    }

    public static GraphicsPipeline getPackShadowShader() {
        return packShadowShader;
    }

    public static GraphicsPipeline getPackEntityShadowShader() {
        return packEntityShadowShader;
    }

    public static GraphicsPipeline getPackSkyShader() {
        return packSkyShader;
    }

    public static GraphicsPipeline getPackCloudShader() {
        return packCloudShader;
    }

    public static GraphicsPipeline getPackWeatherShader() {
        return packWeatherShader;
    }

    public static GraphicsPipeline getPackEntityShader() {
        return packEntityShader;
    }

    public static GraphicsPipeline getPackHandShader() {
        return packHandShader;
    }

    public static ShaderPackConfig getPackConfig() {
        return packConfig;
    }

    public static boolean isPackShadowShader(GraphicsPipeline pipeline) {
        return pipeline != null && pipeline == packShadowShader;
    }
}
