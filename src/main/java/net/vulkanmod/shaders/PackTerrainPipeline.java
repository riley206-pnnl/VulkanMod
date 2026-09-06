package net.vulkanmod.shaders;

import net.vulkanmod.shaders.transform.ProcessedShader;
import net.vulkanmod.shaders.transform.ShaderProcessor;
import net.vulkanmod.shaders.transform.Stage;
import net.vulkanmod.shaders.transform.StageSource;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.material.FogType;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineConfig;
import net.vulkanmod.vulkan.shader.SpirvCompiler;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.ManualUBO;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import org.lwjgl.system.MemoryUtil;
import org.joml.Matrix4f;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.IdentityHashMap;

import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_ALL_GRAPHICS;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;

/** Builds the first runtime shader-pack pass: gbuffers_terrain. */
public final class PackTerrainPipeline {
    private PackTerrainPipeline() {}

    private static final Map<GraphicsPipeline, State> STATES = new IdentityHashMap<>();
    private record State(PackUniformBuffer uniforms, long legacyPtr) {}

    public static GraphicsPipeline create(ShaderPack pack, String dimension) throws IOException {
        ShaderProcessor processor = new ShaderProcessor(true);
        ProcessedShader vertex = processor.process(new StageSource(pack, "gbuffers_terrain", dimension, Stage.VERTEX, null));
        ProcessedShader fragment = processor.process(new StageSource(pack, "gbuffers_terrain", dimension, Stage.FRAGMENT, null));
        // The transformed Complementary stage is hundreds of kilobytes. Feed
        // it through shaderc's virtual include callback so the JNI entrypoint
        // only receives a tiny wrapper and does not exhaust MemoryStack.
        String vertexInclude = "vm_pack_gbuffers_terrain_" + dimension + ".vsh";
        String fragmentInclude = "vm_pack_gbuffers_terrain_" + dimension + ".fsh";
        SpirvCompiler.addVirtualInclude(vertexInclude, vertex.glsl());
        SpirvCompiler.addVirtualInclude(fragmentInclude, fragment.glsl());
        var vertexSpirv = SpirvCompiler.compileVirtualShader("pack_gbuffers_terrain.vsh", vertexInclude, SpirvCompiler.ShaderKind.VERTEX_SHADER);
        var fragmentSpirv = SpirvCompiler.compileVirtualShader("pack_gbuffers_terrain.fsh", fragmentInclude, SpirvCompiler.ShaderKind.FRAGMENT_SHADER);

        Pipeline.Builder builder = new Pipeline.Builder(net.vulkanmod.render.vertex.CustomVertexFormat.TERRAIN,
                                                         "pack_gbuffers_terrain");
        PackUniformBuffer uniforms = new PackUniformBuffer(vertex, fragment);
        int packSize = uniforms.size();
        ManualUBO packUbo = new ManualUBO(0, VK_SHADER_STAGE_ALL_GRAPHICS, packSize / 4);
        long packPtr = uniforms.address();
        packUbo.setSrc(packPtr, packSize);
        packUbo.setUseGlobalBuffer(true);
        builder.addUBO(packUbo);

        ManualUBO legacyUbo = new ManualUBO(1, VK_SHADER_STAGE_ALL_GRAPHICS, 432 / 4);
        long legacyPtr = MemoryUtil.nmemCalloc(1, 432);
        legacyUbo.setSrc(legacyPtr, 432);
        legacyUbo.setUseGlobalBuffer(true);
        builder.addUBO(legacyUbo);

        UBO sectionData = new UBO("vm_SectionData", 2, VK_SHADER_STAGE_VERTEX_BIT, 4096, null);
        sectionData.setUseGlobalBuffer(false);
        builder.addUBO(sectionData);

        builder.applyConfig(PipelineConfig.builder().setPushConstants(
                PipelineConfig.UB.builder(0, VK_SHADER_STAGE_VERTEX_BIT)
                        .addUniform("vec3", "vm_ModelOffset").build()).build());

        Map<Integer, ImageDescriptor> images = new LinkedHashMap<>();
        for (var sampler : java.util.stream.Stream.concat(vertex.samplers().stream(), fragment.samplers().stream()).toList()) {
            images.putIfAbsent(sampler.binding(), new ImageDescriptor(
                    sampler.binding(), "sampler2D", sampler.name(),
                    net.vulkanmod.vulkan.texture.VTextureSelector.getTextureIdx(sampler.name()),
                    VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER));
        }
        for (ImageDescriptor image : images.values()) builder.addImageDescriptor(image);
        builder.setShaderSpirv(SpirvCompiler.ShaderKind.VERTEX_SHADER, vertexSpirv.bytecode());
        builder.setShaderSpirv(SpirvCompiler.ShaderKind.FRAGMENT_SHADER, fragmentSpirv.bytecode());
        GraphicsPipeline pipeline = builder.createGraphicsPipeline();
        STATES.put(pipeline, new State(uniforms, legacyPtr));
        return pipeline;
    }

    public static void update(GraphicsPipeline pipeline) {
        State state = STATES.get(pipeline);
        if (state == null) return;
        PackUniformBuffer uniforms = state.uniforms();
        long l = state.legacyPtr();

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = WorldRenderer.getLevel();
        Camera camera = minecraft.gameRenderer.getMainCamera();
        BlockPos cameraBlock = camera.blockPosition();
        long gameTime = level == null ? 0L : level.getLevelData().getGameTime();
        long dayTime = level == null ? 6000L : level.getOverworldClockTime();
        float sunAngle = Math.floorMod(dayTime, 24000L) / 24000.0f;
        float rainStrength = level == null ? 0.0f : level.getRainLevel(1.0f);
        float width = minecraft.getWindow().getWidth();
        float height = minecraft.getWindow().getHeight();
        float cameraX = (float) camera.position().x;
        float cameraY = (float) camera.position().y;
        float cameraZ = (float) camera.position().z;
        int skyLight = level == null ? 15 : level.getBrightness(LightLayer.SKY, cameraBlock);
        int blockLight = level == null ? 15 : level.getBrightness(LightLayer.BLOCK, cameraBlock);
        org.joml.Vector3fc forward = camera.forwardVector();

        // Use the named std140 layout emitted by ShaderProcessor.
        // Populate the core Iris/OptiFine inputs that affect
        // projection, lighting and fog; absent optional systems remain at
        // their zero-initialized defaults.
        uniforms.integer("isEyeInWater", camera.getFluidInCamera() == FogType.WATER ? 1 : 0);
        uniforms.integer("worldTime", (int) Math.floorMod(dayTime, 24000L));
        uniforms.integer("worldDay", (int) (dayTime / 24000L));
        uniforms.scalar("aspectRatio", height > 0.0f ? width / height : 1.0f);
        uniforms.scalar("eyeAltitude", cameraY);
        uniforms.scalar("frameTime", 1.0f / 60.0f);
        uniforms.scalar("frameTimeCounter", gameTime / 20.0f);
        uniforms.scalar("far", minecraft.options.getEffectiveRenderDistance() * 16.0f);
        uniforms.scalar("near", 0.05f);
        uniforms.scalar("rainStrength", rainStrength);
        uniforms.scalar("screenBrightness", minecraft.options.gamma().get().floatValue());
        uniforms.scalar("viewHeight", height);
        uniforms.scalar("viewWidth", width);
        uniforms.scalar("wetness", rainStrength);
        uniforms.scalar("sunAngle", sunAngle);
        if (minecraft.player != null) {
            uniforms.scalar("playerMood", minecraft.player.getCurrentMood());
            uniforms.integer("is_invisible", minecraft.player.isInvisible() ? 1 : 0);
        }
        uniforms.ivec2("atlasSize", VRenderSystem.getTextureSize().getInt(0), VRenderSystem.getTextureSize().getInt(4));
        uniforms.ivec2("eyeBrightness", blockLight * 16, skyLight * 16);
        uniforms.vec3("cameraPosition", cameraX, cameraY, cameraZ);
        uniforms.vec3("previousCameraPosition", cameraX, cameraY, cameraZ);
        uniforms.vec3("eyePosition", cameraX, cameraY, cameraZ);
        uniforms.vec3("relativeEyePosition", 0.0f, 0.0f, 0.0f);
        uniforms.vec3("playerLookVector", forward.x(), forward.y(), forward.z());
        uniforms.vec4("entityColor", 1.0f, 1.0f, 1.0f, 1.0f);
        uniforms.ivec3("cameraPositionInt", (int) Math.floor(cameraX), (int) Math.floor(cameraY), (int) Math.floor(cameraZ));
        uniforms.ivec3("previousCameraPositionInt", (int) Math.floor(cameraX), (int) Math.floor(cameraY), (int) Math.floor(cameraZ));
        uniforms.vec3("cameraPositionFract", fract(cameraX), fract(cameraY), fract(cameraZ));
        uniforms.vec3("previousCameraPositionFract", fract(cameraX), fract(cameraY), fract(cameraZ));
        uniforms.scalar("endFlashIntensityM", 0.0f);
        uniforms.scalar("starter", 1.0f); // starter: terrain is ready to render
        uniforms.scalar("frameTimeSmooth", 1.0f / 60.0f);
        uniforms.scalar("eyeBrightnessM", skyLight / 15.0f);
        uniforms.scalar("eyeBrightnessM2", skyLight == 15 ? 1.0f : 0.0f);
        uniforms.scalar("rainFactor", rainStrength);

        int skyColor = camera.attributeProbe().getValue(net.minecraft.world.attribute.EnvironmentAttributes.SKY_COLOR, 1.0f);
        uniforms.vec3("skyColor", ((skyColor >>> 16) & 255) / 255.0f,
                ((skyColor >>> 8) & 255) / 255.0f, (skyColor & 255) / 255.0f);

        var fogColor = VRenderSystem.getShaderFogColor().buffer.asFloatBuffer();
        uniforms.vec3("fogColor", fogColor.get(0), fogColor.get(1), fogColor.get(2));

        MemoryUtil.memCopy(net.vulkanmod.vulkan.VRenderSystem.getMVP().ptr, l, 64);
        MemoryUtil.memCopy(net.vulkanmod.vulkan.VRenderSystem.getModelViewMatrix().ptr, l + 64, 64);
        MemoryUtil.memCopy(net.vulkanmod.vulkan.VRenderSystem.getProjectionMatrix().ptr, l + 128, 64);
        Matrix4f mv = new Matrix4f(net.vulkanmod.vulkan.VRenderSystem.getModelViewMatrix().buffer.asFloatBuffer());
        Matrix4f projection = new Matrix4f(net.vulkanmod.vulkan.VRenderSystem.getProjectionMatrix().buffer.asFloatBuffer());
        Matrix4f mvInverse = new Matrix4f(mv).invert();
        // Vertex clipping uses Vulkan Z in [0,1]. Pack ScreenToView expects
        // an OpenGL inverse projection, since it expands depth to [-1,1].
        Matrix4f packProjection = new Matrix4f().m22(2.0f).m32(-1.0f).mul(projection);
        Matrix4f projectionInverse = new Matrix4f(packProjection).invert();
        mvInverse.get(MemoryUtil.memByteBuffer(l + 192, 64));
        new org.joml.Matrix3f(mv).invert().transpose().get3x4(MemoryUtil.memByteBuffer(l + 256, 48));
        putIdentity4(l + 304);
        putIdentity4(l + 368);
        uniforms.matrix("gbufferModelView", mv);
        uniforms.matrix("gbufferModelViewInverse", mvInverse);
        uniforms.matrix("gbufferProjection", packProjection);
        uniforms.matrix("gbufferProjectionInverse", projectionInverse);
        uniforms.matrix("gbufferPreviousModelView", mv);
        uniforms.matrix("gbufferPreviousProjection", packProjection);
        uniforms.matrix("shadowModelView", new Matrix4f());
        uniforms.matrix("shadowModelViewInverse", new Matrix4f());
        uniforms.matrix("shadowProjection", new Matrix4f());
        uniforms.matrix("shadowProjectionInverse", new Matrix4f());
    }

    public static void release(GraphicsPipeline pipeline) {
        State state = STATES.remove(pipeline);
        if (state == null) return;
        state.uniforms().close();
        MemoryUtil.nmemFree(state.legacyPtr());
    }

    private static float fract(float value) {
        return value - (float) Math.floor(value);
    }

    private static void putIdentity4(long ptr) {
        new Matrix4f().get(MemoryUtil.memByteBuffer(ptr, 64));
    }
}
