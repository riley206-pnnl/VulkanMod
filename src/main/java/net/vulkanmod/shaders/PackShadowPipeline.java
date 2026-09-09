package net.vulkanmod.shaders;

import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.shaders.transform.ProcessedShader;
import net.vulkanmod.shaders.transform.ShaderProcessor;
import net.vulkanmod.shaders.transform.Stage;
import net.vulkanmod.shaders.transform.StageSource;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineConfig;
import net.vulkanmod.vulkan.shader.SpirvCompiler;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.ManualUBO;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_ALL_GRAPHICS;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;

/** Builds the pack's light-space shadow graphics pipeline. */
public final class PackShadowPipeline {
    private static boolean entityShadowPass;
    private static final class State {
        final PackUniformBuffer uniforms;
        final long legacyPtr;
        final int[] drawBuffers;
        volatile PackShadowMatrices.State matrices;

        State(PackUniformBuffer uniforms, long legacyPtr, int[] drawBuffers,
              PackShadowMatrices.State matrices) {
            this.uniforms = uniforms;
            this.legacyPtr = legacyPtr;
            this.drawBuffers = drawBuffers;
            this.matrices = matrices;
        }
    }

    private static final Map<GraphicsPipeline, State> STATES = new IdentityHashMap<>();

    private PackShadowPipeline() {}

    public static void beginEntityShadowPass() { entityShadowPass = true; }
    public static void endEntityShadowPass() { entityShadowPass = false; }
    public static boolean isEntityShadowPass() { return entityShadowPass; }

    public static GraphicsPipeline create(ShaderPack pack, String dimension,
                                           PackShadowMatrices.State matrices) throws IOException {
        return create(pack, dimension, matrices, CustomVertexFormat.TERRAIN, true);
    }

    public static GraphicsPipeline create(ShaderPack pack, String dimension,
                                           PackShadowMatrices.State matrices,
                                           VertexFormat vertexFormat,
                                           boolean terrain) throws IOException {
        ShaderPackConfig config = ShaderPackConfig.load(pack);
        ShaderProcessor processor = new ShaderProcessor(config, false, terrain);
        ProcessedShader vertex = processor.process(new StageSource(pack, "shadow", dimension, Stage.VERTEX, null));
        ProcessedShader fragment = processor.process(new StageSource(pack, "shadow", dimension, Stage.FRAGMENT, null));

        String vInc = "vm_pack_shadow_" + dimension + ".vsh";
        String fInc = "vm_pack_shadow_" + dimension + ".fsh";
        String vertexSource = vertex.glsl();
        boolean debugShadowColor = Boolean.getBoolean("vulkanmod.debugShadowColor");
        boolean debugShadowRaw = terrain && Boolean.getBoolean("vulkanmod.debugShadowRaw");
        if (debugShadowRaw || (terrain && Boolean.getBoolean("vulkanmod.debugShadowDirect"))) {
            // Keep the generated declarations/shims (including the section
            // UBO and vm_glVertex macro), but bypass the entire pack shadow
            // main. This is a strict diagnostic for the host caster contract:
            // if this still produces only sparse fragments, the problem is
            // before Complementary's shadow math (vertex offsets, viewport,
            // or light-space rasterization), not in the pack shader.
            int wrapper = vertexSource.lastIndexOf("\n#undef main\nvoid main() {");
            if (wrapper >= 0) {
                String body = debugShadowRaw
                        ? "    // Raw caster diagnostic: remove all shadow matrix and depth-convention math.\n"
                        + "    gl_Position = vec4(vm_glVertex.xy / 128.0, 0.0, 1.0);\n"
                        : "    gl_Position = shadowProjection * shadowModelView * vm_glVertex;\n"
                        + "    gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w);\n";
                vertexSource = vertexSource.substring(0, wrapper)
                        + "\n#undef main\nvoid main() {\n"
                        + body
                        + "}\n";
            }
        }
        String fragmentSource = fragment.glsl();
        if (debugShadowColor) {
            // Isolate the Vulkan shadow attachment/sampling path from the
            // pack's alpha/voxel discard and shadow-color logic.  The normal
            // path remains untouched; this source is only selected by the
            // explicit diagnostic property.
            fragmentSource = """
                    layout(location = 0) out vec4 _fragOut[2];
                    void main() {
                        _fragOut[0] = vec4(1.0, 0.0, 1.0, 1.0);
                        _fragOut[1] = vec4(1.0, 0.0, 1.0, 1.0);
                    }
                    """;
        }
        SpirvCompiler.addVirtualInclude(vInc, vertexSource);
        SpirvCompiler.addVirtualInclude(fInc, fragmentSource);
        var vSpirv = SpirvCompiler.compileVirtualShader("pack_shadow.vsh", vInc, SpirvCompiler.ShaderKind.VERTEX_SHADER);
        var fSpirv = SpirvCompiler.compileVirtualShader("pack_shadow.fsh", fInc, SpirvCompiler.ShaderKind.FRAGMENT_SHADER);

        Pipeline.Builder builder = new Pipeline.Builder(vertexFormat, terrain ? "pack_shadow" : "pack_entity_shadow");
        PackUniformBuffer uniforms = new PackUniformBuffer(vertex, fragment);
        ManualUBO packUbo = new ManualUBO(0, VK_SHADER_STAGE_ALL_GRAPHICS,
                uniforms.size() / 4);
        packUbo.setSrc(uniforms.address(), uniforms.size());
        packUbo.setUseGlobalBuffer(true);
        builder.addUBO(packUbo);

        // Keep the legacy matrix/fog descriptor stable across shadow variants.
        int legacySize = 464;
        ManualUBO legacyUbo = new ManualUBO(1, VK_SHADER_STAGE_ALL_GRAPHICS, legacySize / 4);
        long legacyPtr = MemoryUtil.nmemCalloc(1, legacySize);
        legacyUbo.setSrc(legacyPtr, legacySize);
        legacyUbo.setUseGlobalBuffer(true);
        builder.addUBO(legacyUbo);

        // Shadow casters use the same section-relative chunk buffers as the
        // regular terrain pass.  The processor's shadow terrain shim applies
        // the section offset and the per-draw model offset before projection.
        if (terrain) {
            UBO sectionUbo = new UBO("SectionData", 2, VK_SHADER_STAGE_VERTEX_BIT, 4096, null);
            sectionUbo.setUseGlobalBuffer(false);
            builder.addUBO(sectionUbo);
        }

        builder.applyConfig(PipelineConfig.builder().setPushConstants(
                PipelineConfig.UB.builder(0, VK_SHADER_STAGE_VERTEX_BIT)
                        .addUniform("vec4", "vm_ModelOffset").build()).build());

        Map<Integer, ImageDescriptor> images = new LinkedHashMap<>();
        for (var sampler : java.util.stream.Stream.concat(vertex.resources().stream(), fragment.resources().stream()).toList()) {
            ImageDescriptor descriptor = new ImageDescriptor(sampler.binding(), sampler.glslType(), sampler.name(),
                    VTextureSelector.getTextureIdx(sampler.name()),
                    sampler.isImage() ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            PackFramebuffers.bindCustomTexture(terrain ? "shadow" : "gbuffers_entities", descriptor);
            images.merge(sampler.binding(), descriptor, ImageDescriptor::prefer);
        }
        for (ImageDescriptor image : images.values()) builder.addImageDescriptor(image);

        int[] drawBuffers = fragment.drawBuffers() == null || fragment.drawBuffers().length == 0
                ? new int[]{0} : fragment.drawBuffers();
        int[] colors = new int[drawBuffers.length];
        Arrays.fill(colors, PackFramebuffers.SHADOW_COLOR_FORMAT);
        builder.setColorAttachmentFormats(colors);
        builder.setDepthAttachmentFormat(PackFramebuffers.DEPTH_FORMAT);
        builder.setShaderSpirv(SpirvCompiler.ShaderKind.VERTEX_SHADER, vSpirv.bytecode());
        builder.setShaderSpirv(SpirvCompiler.ShaderKind.FRAGMENT_SHADER, fSpirv.bytecode());

        GraphicsPipeline pipeline = builder.createGraphicsPipeline();
        STATES.put(pipeline, new State(uniforms, legacyPtr, drawBuffers, matrices));
        return pipeline;
    }

    public static boolean isShadowShader(GraphicsPipeline pipeline) {
        return STATES.containsKey(pipeline);
    }

    public static int[] getDrawBuffers(GraphicsPipeline pipeline) {
        State state = STATES.get(pipeline);
        return state == null ? null : state.drawBuffers;
    }

    public static void update(GraphicsPipeline pipeline, Matrix4f cleanProjection) {
        State state = STATES.get(pipeline);
        if (state != null) {
            PackUniforms.update(state.uniforms, state.legacyPtr, cleanProjection, false, state.matrices);
            // ftransform() must use the same OpenGL projection as the
            // pack's shadowProjectionInverse. The vertex wrapper converts
            // the final, distorted position to Vulkan depth afterward.
            Matrix4f clipToOpenGL = new Matrix4f().m22(2.0f).m32(-1.0f);
            Matrix4f legacyMvp = new Matrix4f(MemoryUtil.memByteBuffer(state.legacyPtr, 64).asFloatBuffer());
            Matrix4f legacyProjection = new Matrix4f(MemoryUtil.memByteBuffer(state.legacyPtr + 128, 64).asFloatBuffer());
            new Matrix4f(clipToOpenGL).mul(legacyMvp).get(MemoryUtil.memByteBuffer(state.legacyPtr, 64));
            clipToOpenGL.mul(legacyProjection).get(MemoryUtil.memByteBuffer(state.legacyPtr + 128, 64));
        }
    }

    public static void setMatrices(GraphicsPipeline pipeline, PackShadowMatrices.State matrices) {
        State state = STATES.get(pipeline);
        if (state != null && matrices != null) state.matrices = matrices;
    }

    public static void release(GraphicsPipeline pipeline) {
        State state = STATES.remove(pipeline);
        if (state != null) {
            state.uniforms.close();
            MemoryUtil.nmemFree(state.legacyPtr);
        }
    }
}
