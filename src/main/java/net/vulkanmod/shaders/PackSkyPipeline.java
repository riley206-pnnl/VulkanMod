package net.vulkanmod.shaders;

import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.shaders.transform.ProcessedShader;
import net.vulkanmod.shaders.transform.ShaderProcessor;
import net.vulkanmod.shaders.transform.Stage;
import net.vulkanmod.shaders.transform.StageSource;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.SpirvCompiler;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.ManualUBO;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
import static org.lwjgl.vulkan.VK10.VK_CULL_MODE_NONE;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_ALL_GRAPHICS;

/** Fullscreen compatibility path for pack-controlled sky gradients/discs. */
public final class PackSkyPipeline {
    private record State(PackUniformBuffer uniforms, long legacyPtr) {}
    private static final Map<GraphicsPipeline, State> STATES = new IdentityHashMap<>();

    private PackSkyPipeline() {}

    public static GraphicsPipeline create(ShaderPack pack, String dimension) throws IOException {
        return create(pack, dimension, "gbuffers_skybasic");
    }

    public static GraphicsPipeline create(ShaderPack pack, String dimension, String program) throws IOException {
        ShaderPackConfig config = ShaderPackConfig.load(pack);
        ShaderProcessor processor = new ShaderProcessor(config, false);
        ProcessedShader vertex = processor.process(new StageSource(pack, program, dimension, Stage.VERTEX, null));
        ProcessedShader fragment = processor.process(new StageSource(pack, program, dimension, Stage.FRAGMENT, null));
        String vi = "vm_pack_" + program + "_" + dimension + ".vsh";
        String fi = "vm_pack_" + program + "_" + dimension + ".fsh";
        SpirvCompiler.addVirtualInclude(vi, vertex.glsl());
        String fragmentSource = fragment.glsl();
        // Diagnostic only: distinguish a sky shader/output-path failure from
        // a later deferred/final pass washing out the sky. The normal path is
        // unchanged when the property is absent.
        int skyDebug = Integer.getInteger("vulkanmod.debugSky", 0);
        if (skyDebug == 1) {
            fragmentSource = fragmentSource.replace(
                    "_fragOut[0] = color;",
                    "_fragOut[0] = vec4(0.08, 0.32, 0.95, 1.0);");
        } else if (skyDebug == 2) {
            // View-ray probe: if this is black/NaN, the sky shader's
            // gbufferProjectionInverse or fragment-depth convention is wrong.
            fragmentSource = fragmentSource.replace(
                    "_fragOut[0] = color;",
                    "_fragOut[0] = vec4(normalize(viewPos.xyz) * 0.5 + 0.5, 1.0);");
        } else if (skyDebug == 3) {
            fragmentSource = fragmentSource.replace(
                    "_fragOut[0] = color;",
                    "_fragOut[0] = vec4(sunVec * 0.5 + 0.5, 1.0);");
        } else if (skyDebug == 4) {
            fragmentSource = fragmentSource.replace(
                    "_fragOut[0] = color;",
                    "_fragOut[0] = vec4(upVec * 0.5 + 0.5, 1.0);");
        } else if (skyDebug == 5) {
            fragmentSource = fragmentSource.replace(
                    "_fragOut[0] = color;",
                    "_fragOut[0] = vec4(vec3(SdotU * 0.5 + 0.5), 1.0);");
        }
        SpirvCompiler.addVirtualInclude(fi, fragmentSource);

        var vs = SpirvCompiler.compileVirtualShader("pack_" + program + ".vsh", vi, SpirvCompiler.ShaderKind.VERTEX_SHADER);
        var fs = SpirvCompiler.compileVirtualShader("pack_" + program + ".fsh", fi, SpirvCompiler.ShaderKind.FRAGMENT_SHADER);
        Pipeline.Builder builder = new Pipeline.Builder(CustomVertexFormat.QUAD, "pack_" + program);
        PackUniformBuffer uniforms = new PackUniformBuffer(vertex, fragment);
        ManualUBO packUbo = new ManualUBO(0, VK_SHADER_STAGE_ALL_GRAPHICS, uniforms.size() / 4);
        packUbo.setSrc(uniforms.address(), uniforms.size());
        packUbo.setUseGlobalBuffer(true);
        builder.addUBO(packUbo);
        ManualUBO legacy = new ManualUBO(1, VK_SHADER_STAGE_ALL_GRAPHICS, 464 / 4);
        long legacyPtr = MemoryUtil.nmemCalloc(1, 464);
        legacy.setSrc(legacyPtr, 464);
        legacy.setUseGlobalBuffer(true);
        builder.addUBO(legacy);
        Map<Integer, ImageDescriptor> images = new LinkedHashMap<>();
        for (var sampler : java.util.stream.Stream.concat(vertex.resources().stream(), fragment.resources().stream()).toList()) {
            ImageDescriptor descriptor = new ImageDescriptor(sampler.binding(), sampler.glslType(), sampler.name(),
                    VTextureSelector.getTextureIdx(sampler.name()),
                    sampler.isImage() ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            PackFramebuffers.bindCustomTexture(program, descriptor);
            images.merge(sampler.binding(), descriptor, ImageDescriptor::prefer);
        }
        for (ImageDescriptor image : images.values()) builder.addImageDescriptor(image);
        builder.setColorAttachmentFormats(new int[]{PackFramebuffers.COLOR_FORMAT});
        // beginSky binds only colortex0; there is no depth attachment in the
        // sky render scope. Keeping the default main-pass depth format here
        // makes the graphics pipeline incompatible with that scope.
        builder.setDepthAttachmentFormat(0);
        builder.setShaderSpirv(SpirvCompiler.ShaderKind.VERTEX_SHADER, vs.bytecode());
        builder.setShaderSpirv(SpirvCompiler.ShaderKind.FRAGMENT_SHADER, fs.bytecode());
        // This is a fullscreen compatibility primitive, not vanilla sky
        // geometry. Capture a state that cannot cull one triangle or reject
        // it against the camera depth buffer, then restore the global state
        // for subsequent native and pack pipeline creation.
        int oldCullMode = net.vulkanmod.vulkan.VRenderSystem.cullMode;
        boolean oldDepthTest = net.vulkanmod.vulkan.VRenderSystem.depthTest;
        boolean oldDepthMask = net.vulkanmod.vulkan.VRenderSystem.depthMask;
        net.vulkanmod.vulkan.VRenderSystem.cullMode = VK_CULL_MODE_NONE;
        net.vulkanmod.vulkan.VRenderSystem.depthTest = false;
        net.vulkanmod.vulkan.VRenderSystem.depthMask = false;
        GraphicsPipeline pipeline;
        try {
            pipeline = builder.createGraphicsPipeline();
        } finally {
            net.vulkanmod.vulkan.VRenderSystem.cullMode = oldCullMode;
            net.vulkanmod.vulkan.VRenderSystem.depthTest = oldDepthTest;
            net.vulkanmod.vulkan.VRenderSystem.depthMask = oldDepthMask;
        }
        STATES.put(pipeline, new State(uniforms, legacyPtr));
        return pipeline;
    }

    public static boolean isSkyShader(GraphicsPipeline pipeline) { return STATES.containsKey(pipeline); }

    public static void update(GraphicsPipeline pipeline, Matrix4f cleanProjection) {
        State state = STATES.get(pipeline);
        if (state != null) PackUniforms.update(state.uniforms(), state.legacyPtr(), cleanProjection, true);
    }

    public static void release(GraphicsPipeline pipeline) {
        State state = STATES.remove(pipeline);
        if (state != null) {
            state.uniforms().close();
            MemoryUtil.nmemFree(state.legacyPtr());
        }
    }
}
