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
import com.mojang.blaze3d.vertex.VertexFormat;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.IdentityHashMap;

import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_ALL_GRAPHICS;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;

/** Builds shader-pack terrain-like G-buffer passes. */
public final class PackTerrainPipeline {
    private PackTerrainPipeline() {}

    private static long lastTerrainMatrixLogFrame = -1L;

    private static final Map<GraphicsPipeline, State> STATES = new IdentityHashMap<>();
    private record State(PackUniformBuffer uniforms, long legacyPtr, int[] drawBuffers) {}

    public static GraphicsPipeline create(ShaderPack pack, String dimension) throws IOException {
        return create(pack, dimension, "gbuffers_terrain");
    }

    public static GraphicsPipeline create(ShaderPack pack, String dimension, String program) throws IOException {
        return create(pack, dimension, program, net.vulkanmod.render.vertex.CustomVertexFormat.TERRAIN, true);
    }

    public static GraphicsPipeline create(ShaderPack pack, String dimension, String program,
                                          VertexFormat vertexFormat, boolean sectioned) throws IOException {
        ShaderPackConfig config = ShaderPackConfig.load(pack);
        ShaderProcessor processor = new ShaderProcessor(config, sectioned);
        ProcessedShader vertex = processor.process(new StageSource(pack, program, dimension, Stage.VERTEX, null));
        ProcessedShader fragment = processor.process(new StageSource(pack, program, dimension, Stage.FRAGMENT, null));
        // The transformed Complementary stage is hundreds of kilobytes. Feed
        // it through shaderc's virtual include callback so the JNI entrypoint
        // only receives a tiny wrapper and does not exhaust MemoryStack.
        String vertexInclude = "vm_pack_" + program + "_" + dimension + ".vsh";
        String fragmentInclude = "vm_pack_" + program + "_" + dimension + ".fsh";
        String vertexSource = vertex.glsl();
        // Terrain and water vertices already arrive in camera-relative world
        // space from the shared section shim.  Use the renderer's active
        // Vulkan matrices for rasterization; reconstructing through the
        // pack's inverse matrices applies camera/projection conversion a
        // second time and produces exploded terrain on Vulkan.
        if (program.equals("gbuffers_terrain") || program.equals("gbuffers_water")) {
            vertexSource = vertexSource.replace(
                    "vec4 position = gbufferModelViewInverse * vm_glModelViewMatrix * vm_glVertex;",
                    "vec4 position = vm_glVertex;");
            vertexSource = vertexSource.replace(
                    "gl_Position = vm_glProjectionMatrix * gbufferModelView * position;",
                    "gl_Position = vm_glProjectionMatrix * vm_glModelViewMatrix * vm_glVertex;");
            if (Boolean.getBoolean("vulkanmod.dumpPackTerrainVertex")) {
                try {
                    java.nio.file.Files.writeString(java.nio.file.Path.of("/tmp/vulkanmod-pack-" + program + ".vsh"), vertexSource);
                } catch (java.io.IOException ignored) {
                    // Diagnostic dump only.
                }
            }
        }
        SpirvCompiler.addVirtualInclude(vertexInclude, vertexSource);
        String fragmentSource = fragment.glsl();
        // Diagnostic only: prove that terrain fragments reach the MRT before
        // investigating Complementary's texture/lighting code.
        if (program.equals("gbuffers_terrain") && Boolean.getBoolean("vulkanmod.debugTerrainColor")) {
            fragmentSource = fragmentSource.replace(
                    "_fragOut[0] = color;",
                    "_fragOut[0] = vec4(1.0, 0.0, 1.0, 1.0);");
        }
        if (program.equals("gbuffers_terrain") && Boolean.getBoolean("vulkanmod.debugTerrainTexture")) {
            fragmentSource = fragmentSource.replace(
                    "_fragOut[0] = color;",
                    "_fragOut[0] = texture(tex, texCoord);");
        }
        if (program.equals("gbuffers_terrain") && Boolean.getBoolean("vulkanmod.debugTerrainColorInput")) {
            fragmentSource = fragmentSource.replace(
                    "_fragOut[0] = color;",
                    "_fragOut[0] = vec4(glColor.rgb, 1.0);");
        }
        if (program.equals("gbuffers_terrain") && Boolean.getBoolean("vulkanmod.debugTerrainLightmap")) {
            fragmentSource = fragmentSource.replace(
                    "_fragOut[0] = color;",
                    "_fragOut[0] = vec4(lmCoord, 0.0, 1.0);");
        }
        if (program.equals("gbuffers_terrain") && Boolean.getBoolean("vulkanmod.debugTerrainNormal")) {
            fragmentSource = fragmentSource.replace(
                    "_fragOut[0] = color;",
                    "_fragOut[0] = vec4(normalize(normal) * 0.5 + 0.5, 1.0);");
        }
        SpirvCompiler.addVirtualInclude(fragmentInclude, fragmentSource);
        var vertexSpirv = SpirvCompiler.compileVirtualShader("pack_" + program + ".vsh", vertexInclude, SpirvCompiler.ShaderKind.VERTEX_SHADER);
        var fragmentSpirv = SpirvCompiler.compileVirtualShader("pack_" + program + ".fsh", fragmentInclude, SpirvCompiler.ShaderKind.FRAGMENT_SHADER);

        Pipeline.Builder builder = new Pipeline.Builder(vertexFormat,
                                                         "pack_" + program);
        PackUniformBuffer uniforms = new PackUniformBuffer(vertex, fragment);
        int packSize = uniforms.size();
        ManualUBO packUbo = new ManualUBO(0, VK_SHADER_STAGE_ALL_GRAPHICS, packSize / 4);
        long packPtr = uniforms.address();
        packUbo.setSrc(packPtr, packSize);
        packUbo.setUseGlobalBuffer(true);
        builder.addUBO(packUbo);

        int legacySize = legacySize(vertex, fragment);
        ManualUBO legacyUbo = new ManualUBO(1, VK_SHADER_STAGE_ALL_GRAPHICS, legacySize / 4);
        long legacyPtr = MemoryUtil.nmemCalloc(1, legacySize);
        legacyUbo.setSrc(legacyPtr, legacySize);
        legacyUbo.setUseGlobalBuffer(true);
        builder.addUBO(legacyUbo);

        if (sectioned) {
            UBO sectionData = new UBO("vm_SectionData", 2, VK_SHADER_STAGE_VERTEX_BIT, 4096, null);
            sectionData.setUseGlobalBuffer(false);
            builder.addUBO(sectionData);

            builder.applyConfig(PipelineConfig.builder().setPushConstants(
                    PipelineConfig.UB.builder(0, VK_SHADER_STAGE_VERTEX_BIT)
                            .addUniform("vec4", "vm_ModelOffset").build()).build());
        }

        Map<Integer, ImageDescriptor> images = new LinkedHashMap<>();
        for (var sampler : java.util.stream.Stream.concat(vertex.resources().stream(), fragment.resources().stream()).toList()) {
            ImageDescriptor descriptor = new ImageDescriptor(
                    sampler.binding(), sampler.glslType(), sampler.name(),
                    net.vulkanmod.vulkan.texture.VTextureSelector.getTextureIdx(sampler.name()),
                    sampler.isImage() ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            PackFramebuffers.bindCustomTexture(program, descriptor);
            images.merge(sampler.binding(), descriptor, ImageDescriptor::prefer);
        }
        int fragCount = Math.max(1, fragment.drawBuffers() != null ? fragment.drawBuffers().length : fragment.maxFragmentOutputs());
        int[] colorFormats = new int[fragCount];
        java.util.Arrays.fill(colorFormats, PackFramebuffers.COLOR_FORMAT);
        builder.setColorAttachmentFormats(colorFormats);
        boolean[] blendDisabled = new boolean[fragCount];
        if (fragment.drawBuffers() != null) {
            for (int i = 0; i < Math.min(fragCount, fragment.drawBuffers().length); i++) {
                blendDisabled[i] = config.blendDisabled(dimension, program, fragment.drawBuffers()[i]);
            }
        }
        builder.setColorBlendDisabled(blendDisabled);
        builder.setDepthAttachmentFormat(PackFramebuffers.DEPTH_FORMAT);

        for (ImageDescriptor image : images.values()) builder.addImageDescriptor(image);
        builder.setShaderSpirv(SpirvCompiler.ShaderKind.VERTEX_SHADER, vertexSpirv.bytecode());
        builder.setShaderSpirv(SpirvCompiler.ShaderKind.FRAGMENT_SHADER, fragmentSpirv.bytecode());
        GraphicsPipeline pipeline = builder.createGraphicsPipeline();
        STATES.put(pipeline, new State(uniforms, legacyPtr, fragment.drawBuffers()));
        return pipeline;
    }

    private static int legacySize(ProcessedShader vertex, ProcessedShader fragment) {
        // Reserve the complete compatibility block for every G-buffer
        // program. A later reload can select a fog-using variant without
        // changing the descriptor ABI or leaving the legacy tail absent.
        return 464;
    }

    public static int[] getDrawBuffers(GraphicsPipeline pipeline) {
        State state = STATES.get(pipeline);
        return state != null ? state.drawBuffers() : null;
    }

    public static void update(GraphicsPipeline pipeline) {
        update(pipeline, null);
    }

    public static void update(GraphicsPipeline pipeline, Matrix4f cleanProjection) {
        update(pipeline, cleanProjection, null);
    }

    /** Upload camera uniforms and the light-space transform used by shadow samplers. */
    public static void update(GraphicsPipeline pipeline, Matrix4f cleanProjection,
                              PackShadowMatrices.State shadowMatrices) {
        State state = STATES.get(pipeline);
        if (state == null) return;
        PackUniforms.update(state.uniforms(), state.legacyPtr(), cleanProjection, false, shadowMatrices);
        if (PackDebug.shouldLog() && lastTerrainMatrixLogFrame != PackDebug.frameNumber()) {
            lastTerrainMatrixLogFrame = PackDebug.frameNumber();
            Matrix4f mv = new Matrix4f(VRenderSystem.getModelViewMatrix().buffer.asFloatBuffer());
            Matrix4f proj = new Matrix4f(VRenderSystem.getProjectionMatrix().buffer.asFloatBuffer());
            net.vulkanmod.Initializer.LOGGER.info("[packdbg] terrain matrices mv[{} {} {} {} | {} {} {} {} | {} {} {} {} | {} {} {} {}] proj[{} {} {} {} | {} {} {} {} | {} {} {} {} | {} {} {} {}]",
                    mv.m00(), mv.m01(), mv.m02(), mv.m03(), mv.m10(), mv.m11(), mv.m12(), mv.m13(),
                    mv.m20(), mv.m21(), mv.m22(), mv.m23(), mv.m30(), mv.m31(), mv.m32(), mv.m33(),
                    proj.m00(), proj.m01(), proj.m02(), proj.m03(), proj.m10(), proj.m11(), proj.m12(), proj.m13(),
                    proj.m20(), proj.m21(), proj.m22(), proj.m23(), proj.m30(), proj.m31(), proj.m32(), proj.m33());
        }
    }

    public static void release(GraphicsPipeline pipeline) {
        State state = STATES.remove(pipeline);
        if (state == null) return;
        state.uniforms().close();
        MemoryUtil.nmemFree(state.legacyPtr());
    }
}
