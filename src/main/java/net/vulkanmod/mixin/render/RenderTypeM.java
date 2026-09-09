package net.vulkanmod.mixin.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.vulkanmod.interfaces.ExtendedRenderType;
import net.vulkanmod.render.engine.VkCommandEncoder;
import net.vulkanmod.render.engine.VkRenderPass;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.render.shader.PipelineManager;
import net.vulkanmod.shaders.PackTerrainPipeline;
import net.vulkanmod.shaders.PackShadowPipeline;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.interfaces.shader.ExtendedRenderPipeline;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Consumer;

@Mixin(RenderType.class)
public class RenderTypeM implements ExtendedRenderType {
    @Unique
    private static int vm$packTextureTraceCount;

    @Unique
    TerrainRenderType terrainRenderType;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void inj(String string, RenderSetup renderSetup, CallbackInfo ci) {
        terrainRenderType = switch (string) {
            case "solid" -> TerrainRenderType.SOLID;
            case "cutout" -> TerrainRenderType.CUTOUT;
            case "translucent" -> TerrainRenderType.TRANSLUCENT;
            case "tripwire" -> TerrainRenderType.TRIPWIRE;
            default -> null;
        };
    }

    @Override
    public TerrainRenderType getTerrainRenderType() {
        return terrainRenderType;
    }

    @Shadow @Final private RenderSetup state;
    @Shadow @Final protected String name;

    @Overwrite
    public void draw(MeshData meshData) {
        Matrix4fStack matrix4fStack = RenderSystem.getModelViewStack();
        final var renderSetupAccessor = (RenderSetupAccessor) (Object) this.state;
        Consumer<Matrix4fStack> consumer = renderSetupAccessor.layeringTransform().getModifier();
        if (consumer != null) {
            matrix4fStack.pushMatrix();
            consumer.accept(matrix4fStack);
        }

        GpuBufferSlice gpuBufferSlice = RenderSystem.getDynamicUniforms()
                                                    .writeTransform(RenderSystem.getModelViewMatrix(),
                                                                    new Vector4f(1.0F, 1.0F, 1.0F, 1.0F),
                                                                    new Vector3f(),
                                                                    renderSetupAccessor.textureTransform().getMatrix());

        Map<String, RenderSetup.TextureAndSampler> map = this.state.getTextures();

        // ShadowFeatureRenderer batches directional entity shadows through the
        // normal RenderType API. During the pack shadow target pass, bypass the
        // native output target and draw that batch into the already-bound
        // shadow rendering scope with the pack's ENTITY shadow contract.
        if (PackShadowPipeline.isEntityShadowPass()
                && (this.name.equals("entity_shadow") || isEntityShadowGeometry(this.name))
                && PipelineManager.getPackEntityShadowShader() != null) {
            GraphicsPipeline shadowPipeline = PipelineManager.getPackEntityShadowShader();
            for (Map.Entry<String, RenderSetup.TextureAndSampler> entry : map.entrySet()) {
                if (VTextureSelector.getTextureIdx(entry.getKey()) == 0
                        && entry.getValue().textureView().texture() instanceof net.vulkanmod.render.engine.VkGpuTexture texture) {
                    texture.getVulkanImage().setSampler(((net.vulkanmod.render.engine.VkSampler) entry.getValue().sampler()).getId());
                    VTextureSelector.bindTexture(0, texture.getVulkanImage());
                    // bindShaderTextures also consults RenderSystem's
                    // texture-view slots. Keep that source in sync or the
                    // stale terrain texture can overwrite the entity skin.
                    VRenderSystem.setShaderTexture(0, entry.getValue().textureView());
                }
            }
            VTextureSelector.bindShaderTextures(shadowPipeline);
            VRenderSystem.applyModelViewMatrix(RenderSystem.getModelViewMatrix());
            VRenderSystem.calculateMVP();
            PackShadowPipeline.update(shadowPipeline, null);
            Renderer renderer = Renderer.getInstance();
            renderer.bindGraphicsPipeline(shadowPipeline);
            renderer.uploadAndBindUBOs(shadowPipeline);
            renderer.getDrawer().draw(meshData.vertexBuffer(), meshData.indexBuffer(),
                    meshData.drawState().mode(), meshData.drawState().format(), meshData.drawState().vertexCount());
            net.vulkanmod.shaders.PackDebug.setEntityShadowDraws(
                    net.vulkanmod.shaders.PackDebug.getEntityShadowDraws() + 1);
            meshData.close();
            if (consumer != null) matrix4fStack.popMatrix();
            return;
        }

        // The shader-pack world pipeline ends before GUI rendering begins.
        // Inventory/player previews and other screen entities must remain in
        // the swapchain GUI pass; routing them through the world MRT makes
        // their atlas/lighting state incompatible with the GUI projection
        // and produces magenta or otherwise corrupted previews.
        boolean packGuiPhase = Renderer.getInstance().getMainPass()
                instanceof net.vulkanmod.shaders.PackMainPass packMainPass
                && packMainPass.isGuiPhase();
        GraphicsPipeline packPipeline = packGuiPhase ? null : packPipelineFor(this.name);
        if (packPipeline != null) {
            ExtendedRenderPipeline.of(renderSetupAccessor.pipeline()).setPipeline(packPipeline);
            VRenderSystem.applyModelViewMatrix(RenderSystem.getModelViewMatrix());
            VRenderSystem.calculateMVP();
            // Immediate entity/block-entity render types carry their actual
            // skin, block-entity atlas, glint, or particle texture in the
            // RenderType texture map. The pack descriptor set is keyed by
            // VTextureSelector slots, so rebind those textures before the
            // descriptor refresh. Without this, entity shaders sample the
            // previous terrain/default texture (typically producing missing
            // or magenta player skins).
            for (Map.Entry<String, RenderSetup.TextureAndSampler> entry : map.entrySet()) {
                if (Boolean.getBoolean("vulkanmod.debugPackTextures") && vm$packTextureTraceCount < 80) {
                    Object rawTexture = entry.getValue().textureView().texture();
                    String textureInfo = rawTexture instanceof net.vulkanmod.render.engine.VkGpuTexture gpu
                            ? "vkId=" + gpu.glId() + ", image=" + gpu.getVulkanImage()
                            : String.valueOf(rawTexture);
                    net.vulkanmod.Initializer.LOGGER.info(
                            "[packdbg] immediate texture renderType={} key={} slot={} {}",
                            this.name, entry.getKey(), VTextureSelector.getTextureIdx(entry.getKey()), textureInfo);
                    vm$packTextureTraceCount++;
                }
                if (entry.getValue().textureView().texture()
                        instanceof net.vulkanmod.render.engine.VkGpuTexture texture) {
                    int textureIndex = VTextureSelector.getTextureIdx(entry.getKey());
                    texture.getVulkanImage().setSampler(
                            ((net.vulkanmod.render.engine.VkSampler) entry.getValue().sampler()).getId());
                    VTextureSelector.bindTexture(textureIndex, texture.getVulkanImage());
                    if (textureIndex >= 0 && textureIndex < 12) {
                        VRenderSystem.setShaderTexture(textureIndex, entry.getValue().textureView());
                    }
                }
            }
            VTextureSelector.bindShaderTextures(packPipeline);
            PackTerrainPipeline.update(packPipeline, null,
                    net.vulkanmod.render.chunk.WorldRenderer.getActiveShadowMatrices());

            // Entity/hand render types are immediate draws.  Selecting the
            // pack pipeline alone is not enough: the vanilla encoder would
            // still open its native render target, bypassing colortex0 and
            // the configured DRAWBUFFERS attachments.  Route the mesh through
            // the same MRT scope used by chunk terrain.
            if (net.vulkanmod.shaders.PackCompositePipeline.isActive()) {
                Renderer renderer = Renderer.getInstance();
                renderer.endRenderPass();
                try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    net.vulkanmod.shaders.PackFramebuffers.beginTerrainMRT(
                            Renderer.getCommandBuffer(), stack,
                            PackTerrainPipeline.getDrawBuffers(packPipeline));
                }
                renderer.bindGraphicsPipeline(packPipeline);
                renderer.uploadAndBindUBOs(packPipeline);
                renderer.getDrawer().draw(meshData.vertexBuffer(), meshData.indexBuffer(),
                        meshData.drawState().mode(), meshData.drawState().format(),
                        meshData.drawState().vertexCount());
                net.vulkanmod.shaders.PackFramebuffers.endTerrainMRT(Renderer.getCommandBuffer());
                renderer.getMainPass().rebindMainTarget();
                meshData.close();
                if (consumer != null) matrix4fStack.popMatrix();
                return;
            }
        }

        GpuBuffer gpuBuffer = renderSetupAccessor.pipeline().getVertexFormat().uploadImmediateVertexBuffer(meshData.vertexBuffer());
        GpuBuffer gpuBuffer2;
        VertexFormat.IndexType indexType;
        if (meshData.indexBuffer() == null) {
            RenderSystem.AutoStorageIndexBuffer autoStorageIndexBuffer = RenderSystem.getSequentialBuffer(meshData.drawState().mode());
            gpuBuffer2 = autoStorageIndexBuffer.getBuffer(meshData.drawState().indexCount());
            indexType = autoStorageIndexBuffer.type();
        } else {
            gpuBuffer2 = renderSetupAccessor.pipeline().getVertexFormat().uploadImmediateIndexBuffer(meshData.indexBuffer());
            indexType = meshData.drawState().indexType();
        }

        RenderTarget renderTarget = renderSetupAccessor.outputTarget().getRenderTarget();
        GpuTextureView gpuTextureView = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : renderTarget.getColorTextureView();
        GpuTextureView gpuTextureView2 = renderTarget.useDepth ? (RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : renderTarget.getDepthTextureView()) : null;

        try (RenderPass renderPass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Immediate draw for " + this.name, gpuTextureView, OptionalInt.empty(), gpuTextureView2, OptionalDouble.empty())) {
            renderPass.setPipeline(renderSetupAccessor.pipeline());
            ScissorState scissorState = RenderSystem.getScissorStateForRenderTypeDraws();
            if (scissorState.enabled()) {
                renderPass.enableScissor(scissorState.x(), scissorState.y(), scissorState.width(), scissorState.height());
            }

            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", gpuBufferSlice);
            renderPass.setVertexBuffer(0, gpuBuffer);

            for(Map.Entry<String, RenderSetup.TextureAndSampler> entry : map.entrySet()) {
                renderPass.bindTexture(entry.getKey(), entry.getValue().textureView(), entry.getValue().sampler());
            }

            VRenderSystem.applyModelViewMatrix(RenderSystem.getModelViewMatrix());
            VRenderSystem.calculateMVP();

//            renderPass.setIndexBuffer(gpuBuffer2, indexType);
//            renderPass.drawIndexed(0, 0, meshData.drawState().indexCount(), 1);

            VkCommandEncoder commandEncoder = (VkCommandEncoder) (Object) RenderSystem.getDevice().backend.createCommandEncoder();
            commandEncoder.trySetup((VkRenderPass) (Object) renderPass.backend);

            Renderer.getDrawer().draw(meshData.vertexBuffer(), meshData.indexBuffer(), meshData.drawState().mode(), meshData.drawState().format(), meshData.drawState().vertexCount());
        }

        if (meshData != null) {
            meshData.close();
        }

        if (consumer != null) {
            matrix4fStack.popMatrix();
        }

    }

    @Unique
    private static boolean isEntityShadowGeometry(String name) {
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        return n.contains("entity") || n.contains("armor") || n.contains("spider_eyes")
                || n.contains("eyes") || n.contains("glint") || n.contains("item")
                || n.contains("hand") || n.contains("player");
    }

    @Unique
    private static GraphicsPipeline packPipelineFor(String name) {
        if (Boolean.getBoolean("vulkanmod.disablePackGBuffer")) {
            return null;
        }
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        if ((n.contains("hand") || n.contains("first_person") || n.contains("item_in_hand"))
                && PipelineManager.getPackHandShader() != null) {
            return PipelineManager.getPackHandShader();
        }
        if ((n.contains("entity") || n.contains("armor") || n.contains("spider_eyes")
                || n.contains("glint") || n.contains("eyes"))
                && PipelineManager.getPackEntityShader() != null) {
            if (n.contains("glint")) {
                GraphicsPipeline glint = PipelineManager.getPackGBufferShader("gbuffers_armor_glint");
                if (glint != null) return glint;
            }
            if (n.contains("translucent")) {
                GraphicsPipeline translucent = PipelineManager.getPackGBufferShader("gbuffers_entities_translucent");
                if (translucent != null) return translucent;
            }
            if (n.contains("glow") || n.contains("eyes")) {
                GraphicsPipeline glowing = PipelineManager.getPackGBufferShader("gbuffers_entities_glowing");
                if (glowing != null) return glowing;
            }
            return PipelineManager.getPackEntityShader();
        }
        if (n.contains("block_entity") || n.contains("blockentity")) {
            if (n.contains("translucent")) {
                GraphicsPipeline translucent = PipelineManager.getPackGBufferShader("gbuffers_block_translucent");
                if (translucent != null) return translucent;
            }
            GraphicsPipeline block = PipelineManager.getPackGBufferShader("gbuffers_block");
            if (block != null) return block;
        }
        if (n.contains("particle")) {
            GraphicsPipeline basic = PipelineManager.getPackGBufferShader("gbuffers_basic");
            if (basic != null) return basic;
        }
        if (n.contains("text") || n.contains("sign")) {
            GraphicsPipeline textured = PipelineManager.getPackGBufferShader("gbuffers_textured");
            if (textured != null) return textured;
            GraphicsPipeline basic = PipelineManager.getPackGBufferShader("gbuffers_basic");
            if (basic != null) return basic;
        }
        if (n.contains("line") || n.contains("leash") || n.contains("outline")) {
            GraphicsPipeline line = PipelineManager.getPackGBufferShader("gbuffers_line");
            if (line != null) return line;
        }
        return null;
    }
}
