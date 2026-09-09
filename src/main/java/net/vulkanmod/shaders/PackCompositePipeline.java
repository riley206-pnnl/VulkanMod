package net.vulkanmod.shaders;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.shaders.transform.ProcessedShader;
import net.vulkanmod.shaders.transform.ShaderProcessor;
import net.vulkanmod.shaders.transform.Stage;
import net.vulkanmod.shaders.transform.StageSource;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.SpirvCompiler;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.ManualUBO;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.util.*;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public final class PackCompositePipeline {
    public static final class Pass {
        public final String name;
        public final boolean isFinal;
        public final GraphicsPipeline pipeline;
        public final PackUniformBuffer uniforms;
        public final long legacyPtr;
        public final int[] drawBuffers;

        public Pass(String name, boolean isFinal, GraphicsPipeline pipeline,
                    PackUniformBuffer uniforms, long legacyPtr, int[] drawBuffers) {
            this.name = name;
            this.isFinal = isFinal;
            this.pipeline = pipeline;
            this.uniforms = uniforms;
            this.legacyPtr = legacyPtr;
            this.drawBuffers = drawBuffers;
        }

        public void cleanUp() {
            if (pipeline != null) pipeline.cleanUp();
            if (uniforms != null) uniforms.close();
            if (legacyPtr != 0L) MemoryUtil.nmemFree(legacyPtr);
        }
    }

    private static boolean active = false;
    private static final List<Pass> deferredPasses = new ArrayList<>();
    private static final List<Pass> compositePasses = new ArrayList<>();
    private static Pass finalPass = null;
    private static Pass debugCopyPass = null;
    private static ShaderPackConfig packConfig = null;

    // Debug probe: -Dvulkanmod.debugPack=<n>
    //  1 = replace final with flat-red quad (quad->swapchain path check)
    //  2 = skip all post-processing, sample raw colortex0 (terrain gbuffer check)
    //  3 = run deferred+composite chain, sample colortex3 (chain check)
    //  4 = sample depthtex0 (terrain depth/rasterization check)
    //  5..12 = sample after each successive post-processing stage
    //  14 = shadow depth, 15 = shadow color, 16 = contrast shadow depth
    //  17 = shadowtex1 comparison-sampler lookup
    private static final int debugMode = Integer.getInteger("vulkanmod.debugPack", 0);

    public static synchronized void init(ShaderPack pack, String dimension) throws IOException {
        destroy();
        packConfig = ShaderPackConfig.load(pack);
        QuadRenderer.init();

        // 1. Discover and build deferred passes
        for (String name : orderedPassNames(pack, dimension, true)) {
            if (packConfig.programEnabled(dimension, name) && hasProgram(pack, name, dimension)) {
                try {
                    deferredPasses.add(createPass(pack, name, dimension, false));
                    Initializer.LOGGER.info("Loaded deferred pass: {}", name);
                } catch (Exception e) {
                    Initializer.LOGGER.warn("Failed to load deferred pass {}: {}", name, e.toString(), e);
                }
            }
        }

        // 2. Discover and build composite passes
        for (String name : orderedPassNames(pack, dimension, false)) {
            if (packConfig.programEnabled(dimension, name) && hasProgram(pack, name, dimension)) {
                try {
                    compositePasses.add(createPass(pack, name, dimension, false));
                    Initializer.LOGGER.info("Loaded composite pass: {}", name);
                } catch (Exception e) {
                    Initializer.LOGGER.warn("Failed to load composite pass {}: {}", name, e.toString(), e);
                }
            }
        }

        // 3. Discover and build final pass. Probe modes 2 and 3 keep the
        // real intermediate passes above, but replace only presentation with
        // a diagnostic final shader.
        if (debugMode != 0) {
            try {
                finalPass = createDebugFinal(debugMode);
                Initializer.LOGGER.info("PackDebug: debug probe mode {} active", debugMode);
            } catch (Exception e) {
                Initializer.LOGGER.error("Failed to create debug final pass: {}", e.toString(), e);
            }
        } else if (packConfig.programEnabled(dimension, "final") && hasProgram(pack, "final", dimension)) {
            try {
                finalPass = createPass(pack, "final", dimension, true);
                Initializer.LOGGER.info("Loaded final pass: final");
            } catch (Exception e) {
                Initializer.LOGGER.error("Failed to load final pass: {}", e.toString(), e);
            }
        }

        if (debugMode == 13) {
            debugCopyPass = createDebugCopyPass();
            finalPass = createDebugFinal(2);
            Initializer.LOGGER.info("PackDebug: intermediate copy probe active");
        }

        active = (finalPass != null || !compositePasses.isEmpty());
    }

    public static boolean isActive() {
        return active;
    }

    public static void render(VkCommandBuffer cmd, Matrix4f cleanProjection) {
        if (!active) return;

        // End any active render pass before running post-processing
        Renderer.getInstance().endRenderPass();

        // Configure pipeline dynamic states for full-screen quads (no culling, no depth testing/writing, no blending)
        GlStateManager._disableCull();
        VRenderSystem.cullMode = VK_CULL_MODE_NONE;
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(false);
        VRenderSystem.depthTest = false;
        VRenderSystem.depthMask = false;
        GlStateManager._disableBlend();
        PipelineState.blendInfo.enabled = false;
        GlStateManager._colorMask(ColorTargetState.WRITE_ALL);
        VRenderSystem.colorMask(true, true, true, true);

        try (MemoryStack stack = stackPush()) {
            int width = PackFramebuffers.getWidth();
            int height = PackFramebuffers.getHeight();
            if (width <= 0 || height <= 0) return;

            PackFramebuffers.rebindReadTextures();

            // Ensure all G-buffer textures and depth textures are readable by shaders
            transitionGbuffersToRead(cmd, stack);

            if (debugMode == 0) {
                // Normal rendering: execute the complete deferred/composite
                // chain before the real final pass samples colortex3.
                for (Pass pass : deferredPasses) {
                    renderPass(pass, cmd, stack, cleanProjection, null, width, height);
                }
                for (Pass pass : compositePasses) {
                    renderPass(pass, cmd, stack, cleanProjection, null, width, height);
                }
            } else {
                // Probe modes: run only the portion of chain under test, sample via debug final.
                if (debugMode == 2) {
                    // Raw G-buffer probe: do not run deferred1, because it
                    // writes colortex0 and would mask the terrain result.
                } else if (debugMode == 13 && debugCopyPass != null) {
                    renderPass(debugCopyPass, cmd, stack, cleanProjection, null, width, height);
                } else if (debugMode >= 3) {
                    int stage = debugMode == 3 ? 8 : debugMode - 4;
                    int completed = 0;
                    for (Pass pass : deferredPasses) {
                        renderPass(pass, cmd, stack, cleanProjection, null, width, height);
                        completed++;
                        if (completed >= stage) break;
                    }
                    if (completed < stage) {
                        for (Pass pass : compositePasses) {
                            renderPass(pass, cmd, stack, cleanProjection, null, width, height);
                            completed++;
                            if (completed >= stage) break;
                        }
                    }
                }
                // Mode 1 (and default fallback): run nothing, flat-red final.
            }

            // 3. Run final presentation pass into SwapChain
            if (finalPass != null) {
                PackFramebuffers.rebindReadTextures();
                SwapChain swapChain = Renderer.getInstance().getSwapChain();
                VulkanImage swapImage = swapChain.getColorAttachment();
                renderPass(finalPass, cmd, stack, cleanProjection, swapImage, width, height);
            }

            // 4. Signal GUI phase to MainPass and begin GUI render pass on SwapChain
            if (Renderer.getInstance().getMainPass() instanceof PackMainPass pmp) {
                pmp.beginGuiPass(cmd, stack);
            }

        }

        PackDebug.frame();
        PackDebug.log("pipeline render: " + PackDebug.terrainSummary()
                + " resourceFallbacks=" + PackDebug.getResourceFallbacks());
    }

    private static void renderPass(Pass pass, VkCommandBuffer cmd, MemoryStack stack,
                                   Matrix4f cleanProjection, VulkanImage finalTarget,
                                   int width, int height) {
        if (pass.uniforms != null) {
            // Deferred/composite lighting also evaluates shadowtex lookups.
            // Keep it on the same light-space transform as the caster and
            // camera G-buffer rather than silently reverting to identity.
            PackUniforms.update(pass.uniforms, pass.legacyPtr, cleanProjection, true,
                    net.vulkanmod.render.chunk.WorldRenderer.getActiveShadowMatrices());
        }

        if (PackDebug.shouldLog()) {
            Initializer.LOGGER.info("[packdbg] pass={}: isFinal={} drawBuffers={}",
                    pass.name, pass.isFinal, java.util.Arrays.toString(pass.drawBuffers));
            if (!pass.isFinal && pass.name.equals("debug_copy")) {
                VulkanImage read = PackFramebuffers.getColortex(0);
                VulkanImage bound = VTextureSelector.getImage(VTextureSelector.getTextureIdx("colortex0"));
                Initializer.LOGGER.info("[packdbg] c0 read={} layout={} selector={} layout={}",
                        read == null ? 0L : read.getId(),
                        read == null ? -1 : read.getCurrentLayout(),
                        bound == null ? 0L : bound.getId(),
                        bound == null ? -1 : bound.getCurrentLayout());
            }
        }

        // Prepare color attachments
        VkRenderingAttachmentInfo.Buffer colorAttachments;
        if (pass.isFinal) {
            if (finalTarget == null) return;
            finalTarget.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            colorAttachments = VkRenderingAttachmentInfo.calloc(1, stack);
            colorAttachments.get(0).sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR);
            colorAttachments.get(0).imageView(finalTarget.getImageView());
            colorAttachments.get(0).imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            colorAttachments.get(0).loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE);
            colorAttachments.get(0).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        } else {
            int count = Math.max(1, pass.drawBuffers.length);
            colorAttachments = VkRenderingAttachmentInfo.calloc(count, stack);
            for (int i = 0; i < count; i++) {
                int bufIdx = pass.drawBuffers[i];
                VulkanImage targetImg = PackFramebuffers.getColortexWrite(bufIdx);
                if (targetImg == null) continue;
                targetImg.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

                var att = colorAttachments.get(i);
                att.sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR);
                att.imageView(targetImg.getImageView());
                att.imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                att.loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE);
                att.storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            }
        }

        // Begin dynamic rendering
        VkRect2D renderArea = VkRect2D.malloc(stack);
        renderArea.offset().set(0, 0);
        renderArea.extent().set(width, height);

        VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack);
        renderingInfo.sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_INFO_KHR);
        renderingInfo.renderArea(renderArea);
        renderingInfo.layerCount(1);
        renderingInfo.pColorAttachments(colorAttachments);

        KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, renderingInfo);

        // Set dynamic states: inverted viewport for OpenGL coordinate compatibility
        Renderer.setViewport(0, 0, width, height, stack);
        VkRect2D.Buffer pScissor = VkRect2D.malloc(1, stack);
        pScissor.offset().set(0, 0);
        pScissor.extent().set(width, height);
        vkCmdSetScissor(cmd, 0, pScissor);

        // Bind pipeline and descriptor sets
        Renderer.getInstance().bindGraphicsPipeline(pass.pipeline);
        // Refresh vanilla/custom sampler slots before descriptor-set binding.
        // The pass graph can change the active colortex image between passes,
        // and shader-pack samplers are intentionally owned by
        // VTextureSelector rather than the vanilla RenderSystem slots.
        VTextureSelector.bindShaderTextures(pass.pipeline);
        Renderer.getInstance().uploadAndBindUBOs(pass.pipeline);

        // Draw full-screen quad
        QuadRenderer.renderQuad(cmd);

        // End dynamic rendering
        KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);

        // If intermediate pass, flip written ping-pong buffers and transition to readable layout
        if (!pass.isFinal) {
            for (int bufIdx : pass.drawBuffers) {
                PackFramebuffers.flip(bufIdx);
                VulkanImage newRead = PackFramebuffers.getColortex(bufIdx);
                if (newRead != null) {
                    newRead.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                }
            }
        }
    }

    private static void transitionGbuffersToRead(VkCommandBuffer cmd, MemoryStack stack) {
        for (int i = 0; i < 8; i++) {
            VulkanImage img = PackFramebuffers.getColortex(i);
            if (img != null) {
                img.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
        }
        VulkanImage d0 = PackFramebuffers.getDepthtex0();
        if (d0 != null) {
            d0.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        VulkanImage d1 = PackFramebuffers.getDepthtex1();
        if (d1 != null) {
            d1.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        VulkanImage st0 = PackFramebuffers.getShadowtex0();
        if (st0 != null) st0.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        VulkanImage st1 = PackFramebuffers.getShadowtex1();
        if (st1 != null) st1.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        VulkanImage sc0 = PackFramebuffers.getShadowcolor0();
        if (sc0 != null) sc0.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        VulkanImage sc1 = PackFramebuffers.getShadowcolor1();
        if (sc1 != null) sc1.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        VulkanImage noise = PackFramebuffers.getNoisetex();
        if (noise != null) noise.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    private static Pass createPass(ShaderPack pack, String name, String dimension, boolean isFinal) throws IOException {
        ShaderProcessor processor = new ShaderProcessor(packConfig, false);
        ProcessedShader vertex = processor.process(new StageSource(pack, name, dimension, Stage.VERTEX, null));
        ProcessedShader fragment = processor.process(new StageSource(pack, name, dimension, Stage.FRAGMENT, null));

        String vInc = "vm_pack_" + name + "_" + dimension + ".vsh";
        String fInc = "vm_pack_" + name + "_" + dimension + ".fsh";
        SpirvCompiler.addVirtualInclude(vInc, vertex.glsl());
        SpirvCompiler.addVirtualInclude(fInc, fragment.glsl());
        var vertexSpirv = SpirvCompiler.compileVirtualShader("pack_" + name + ".vsh", vInc, SpirvCompiler.ShaderKind.VERTEX_SHADER);
        var fragmentSpirv = SpirvCompiler.compileVirtualShader("pack_" + name + ".fsh", fInc, SpirvCompiler.ShaderKind.FRAGMENT_SHADER);

        Pipeline.Builder builder = new Pipeline.Builder(CustomVertexFormat.QUAD, "pack_" + name);
        int[] drawBuffers = fragment.drawBuffers() != null && fragment.drawBuffers().length > 0
                ? fragment.drawBuffers()
                : new int[]{0};

        if (isFinal) {
            int swapFormat = Renderer.getInstance().getSwapChain() != null
                    ? Renderer.getInstance().getSwapChain().getFormat()
                    : VK_FORMAT_B8G8R8A8_UNORM;
            builder.setColorAttachmentFormats(swapFormat);
            builder.setDepthAttachmentFormat(0);
        } else {
            int[] formats = new int[drawBuffers.length];
            Arrays.fill(formats, PackFramebuffers.COLOR_FORMAT);
            builder.setColorAttachmentFormats(formats);
            builder.setDepthAttachmentFormat(0);
        }

        PackUniformBuffer uniforms = new PackUniformBuffer(vertex, fragment);
        int packSize = uniforms.size();
        ManualUBO packUbo = new ManualUBO(0, VK_SHADER_STAGE_ALL_GRAPHICS, packSize / 4);
        long packPtr = uniforms.address();
        packUbo.setSrc(packPtr, packSize);
        packUbo.setUseGlobalBuffer(true);
        builder.addUBO(packUbo);

        ManualUBO legacyUbo = new ManualUBO(1, VK_SHADER_STAGE_ALL_GRAPHICS, 464 / 4);
        long legacyPtr = MemoryUtil.nmemCalloc(1, 464);
        legacyUbo.setSrc(legacyPtr, 464);
        legacyUbo.setUseGlobalBuffer(true);
        builder.addUBO(legacyUbo);

        Map<Integer, ImageDescriptor> images = new LinkedHashMap<>();
        for (var sampler : java.util.stream.Stream.concat(vertex.resources().stream(), fragment.resources().stream()).toList()) {
            ImageDescriptor descriptor = new ImageDescriptor(
                    sampler.binding(), sampler.glslType(), sampler.name(),
                    VTextureSelector.getTextureIdx(sampler.name()),
                    sampler.isImage() ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            PackFramebuffers.bindCustomTexture(name, descriptor);
            images.merge(sampler.binding(), descriptor,
                    (existing, candidate) -> ImageDescriptor.prefer(existing, candidate,
                            name.equals("composite1")));
        }
        for (ImageDescriptor image : images.values()) builder.addImageDescriptor(image);

        builder.setShaderSpirv(SpirvCompiler.ShaderKind.VERTEX_SHADER, vertexSpirv.bytecode());
        builder.setShaderSpirv(SpirvCompiler.ShaderKind.FRAGMENT_SHADER, fragmentSpirv.bytecode());

        GraphicsPipeline pipeline = builder.createGraphicsPipeline();
        return new Pass(name, isFinal, pipeline, uniforms, legacyPtr, drawBuffers);
    }

    private static boolean hasProgram(ShaderPack pack, String program, String dimension) {
        return pack.exists("shaders/" + dimension + "/" + program + ".fsh")
                || pack.exists("shaders/" + program + ".fsh");
    }

    private static List<String> candidateDeferredNames() {
        List<String> list = new ArrayList<>();
        list.add("deferred");
        for (int i = 1; i <= 15; i++) list.add("deferred" + i);
        return list;
    }

    private static List<String> candidateCompositeNames() {
        List<String> list = new ArrayList<>();
        list.add("composite");
        for (int i = 1; i <= 15; i++) list.add("composite" + i);
        return list;
    }

    /** Resolve the pack's declared pass order instead of relying on the old
     * fixed deferred1/composite7 sequence. Unknown future pass names remain
     * harmlessly skipped until a graphics backend is registered for them. */
    private static List<String> orderedPassNames(ShaderPack pack, String dimension, boolean deferred) throws IOException {
        List<String> ordered = packConfig.orderedPasses(pack, dimension);
        List<String> result = new ArrayList<>();
        for (String name : ordered) {
            boolean isDeferred = name.equals("deferred") || name.startsWith("deferred");
            boolean isComposite = name.equals("composite") || name.startsWith("composite");
            if ((deferred && isDeferred) || (!deferred && isComposite)) result.add(name);
        }
        // Keep compatibility with packs whose metadata omits the pass list.
        if (result.isEmpty()) return deferred ? candidateDeferredNames() : candidateCompositeNames();
        return result;
    }

    public static synchronized void destroy() {
        active = false;
        for (Pass p : deferredPasses) p.cleanUp();
        deferredPasses.clear();
        for (Pass p : compositePasses) p.cleanUp();
        compositePasses.clear();
        if (debugCopyPass != null) {
            debugCopyPass.cleanUp();
            debugCopyPass = null;
        }
        if (finalPass != null) {
            finalPass.cleanUp();
            finalPass = null;
        }
        packConfig = null;
    }

    public static boolean debugModeActive() {
        return debugMode != 0;
    }

    private static Pass createDebugFinal(int probe) {
        int debugProbe = (probe >= 1 && probe <= 16) ? probe : 1;

        String vShader = """
                // CustomVertexFormat.QUAD is remapped by the Vulkan vertex
                // input table to the legacy pack locations 4/7.
                layout(location = 4) in vec4 vm_glVertex;
                layout(location = 7) in vec2 vm_uv;
                layout(location = 7) out vec2 v_uv;
                void main() {
                    gl_Position = vec4(vm_glVertex.xy, 0.0, 1.0);
                    v_uv = vm_uv;
                }
                """;

        String fShaderFlat = """
                layout(location = 0) out vec4 fragColor;
                void main() {
                    fragColor = vec4(1.0, 0.0, 0.0, 1.0);
                }
                """;

        String samplerName = switch (debugProbe) {
            case 2, 5, 6, 7, 8 -> "colortex0";
            case 3, 9, 10, 11, 12 -> "colortex3";
            case 4 -> "depthtex0";
            case 14, 16 -> "shadowtex0";
            case 15 -> "shadowcolor0";
            case 17 -> "shadowtex1";
            default -> null;
        };

        String fShaderSample = """
                layout(location = 0) out vec4 fragColor;
                layout(binding = 3) uniform sampler2D vm_debug_sampler;
                layout(location = 7) in vec2 v_uv;
                void main() {
                    fragColor = vec4(texture(vm_debug_sampler, v_uv).rgb, 1.0);
                }
                """;

        String fShaderDepth = """
                layout(location = 0) out vec4 fragColor;
                layout(binding = 3) uniform sampler2D vm_debug_sampler;
                layout(location = 7) in vec2 v_uv;
                void main() {
                    float depth = texture(vm_debug_sampler, v_uv).r;
                    fragColor = vec4(vec3(depth), 1.0);
                }
                """;
        String fShaderShadowContrast = """
                layout(location = 0) out vec4 fragColor;
                layout(binding = 3) uniform sampler2D vm_debug_sampler;
                layout(location = 7) in vec2 v_uv;
                void main() {
                    float depth = texture(vm_debug_sampler, v_uv).r;
                    float caster = clamp((1.0 - depth) * 16.0, 0.0, 1.0);
                    fragColor = vec4(vec3(caster), 1.0);
                }
                """;
        String fShaderShadowCompare = """
                layout(location = 0) out vec4 fragColor;
                layout(binding = 3) uniform sampler2DShadow vm_debug_sampler;
                layout(location = 7) in vec2 v_uv;
                void main() {
                    // A fixed reference makes the result a direct diagnostic
                    // of comparison-sampler state and shadow image contents.
                    float visible = texture(vm_debug_sampler, vec3(v_uv, 0.5));
                    fragColor = vec4(vec3(visible), 1.0);
                }
                """;

        String fShader = debugProbe == 17 ? fShaderShadowCompare
                : debugProbe == 16 ? fShaderShadowContrast
                : (debugProbe == 4 || debugProbe == 14) ? fShaderDepth
                : (samplerName != null ? fShaderSample : fShaderFlat);
        String vInc = "vm_probe.vsh";
        String fInc = "vm_probe.fsh";
        SpirvCompiler.addVirtualInclude(vInc, vShader);
        SpirvCompiler.addVirtualInclude(fInc, fShader);
        var vertexSpirv = SpirvCompiler.compileVirtualShader("vm_probe.vsh", vInc, SpirvCompiler.ShaderKind.VERTEX_SHADER);
        var fragmentSpirv = SpirvCompiler.compileVirtualShader("vm_probe.fsh", fInc, SpirvCompiler.ShaderKind.FRAGMENT_SHADER);

        int swapFormat = Renderer.getInstance().getSwapChain() != null
                ? Renderer.getInstance().getSwapChain().getFormat()
                : VK_FORMAT_B8G8R8A8_UNORM;

        Pipeline.Builder builder = new Pipeline.Builder(CustomVertexFormat.QUAD, "pack_debug_probe");
        builder.setColorAttachmentFormats(swapFormat);
        builder.setDepthAttachmentFormat(0);

        if (samplerName != null) {
            builder.addImageDescriptor(new ImageDescriptor(
                    3, debugProbe == 17 ? "sampler2DShadow" : "sampler2D", samplerName,
                    VTextureSelector.getTextureIdx(samplerName),
                    VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER));
        }

        builder.setShaderSpirv(SpirvCompiler.ShaderKind.VERTEX_SHADER, vertexSpirv.bytecode());
        builder.setShaderSpirv(SpirvCompiler.ShaderKind.FRAGMENT_SHADER, fragmentSpirv.bytecode());

        GraphicsPipeline pipeline = builder.createGraphicsPipeline();
        return new Pass("debug_final", true, pipeline, null, 0, new int[]{0});
    }

    private static Pass createDebugCopyPass() {
        String vShader = """
                layout(location = 4) in vec4 vm_glVertex;
                layout(location = 7) in vec2 vm_uv;
                layout(location = 7) out vec2 v_uv;
                void main() {
                    gl_Position = vec4(vm_glVertex.xy, 0.0, 1.0);
                    v_uv = vm_uv;
                }
                """;
        String fShader = """
                layout(location = 0) out vec4 fragColor;
                layout(binding = 3) uniform sampler2D vm_debug_sampler;
                layout(location = 7) in vec2 v_uv;
                void main() {
                    fragColor = texture(vm_debug_sampler, v_uv);
                }
                """;
        SpirvCompiler.addVirtualInclude("vm_probe_copy.vsh", vShader);
        SpirvCompiler.addVirtualInclude("vm_probe_copy.fsh", fShader);
        var vertexSpirv = SpirvCompiler.compileVirtualShader("vm_probe_copy.vsh", "vm_probe_copy.vsh", SpirvCompiler.ShaderKind.VERTEX_SHADER);
        var fragmentSpirv = SpirvCompiler.compileVirtualShader("vm_probe_copy.fsh", "vm_probe_copy.fsh", SpirvCompiler.ShaderKind.FRAGMENT_SHADER);

        Pipeline.Builder builder = new Pipeline.Builder(CustomVertexFormat.QUAD, "pack_debug_copy");
        builder.setColorAttachmentFormats(PackFramebuffers.COLOR_FORMAT);
        builder.setDepthAttachmentFormat(0);
        builder.addImageDescriptor(new ImageDescriptor(3, "sampler2D", "colortex0",
                VTextureSelector.getTextureIdx("colortex0"), VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER));
        builder.setShaderSpirv(SpirvCompiler.ShaderKind.VERTEX_SHADER, vertexSpirv.bytecode());
        builder.setShaderSpirv(SpirvCompiler.ShaderKind.FRAGMENT_SHADER, fragmentSpirv.bytecode());
        return new Pass("debug_copy", false, builder.createGraphicsPipeline(), null, 0, new int[]{0});
    }

    private PackCompositePipeline() {}
}
