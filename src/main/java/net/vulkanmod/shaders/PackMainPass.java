package net.vulkanmod.shaders;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.vulkanmod.render.engine.VkGpuDevice;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.pass.MainPass;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkRect2D;

import java.util.function.IntSupplier;

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;

public class PackMainPass implements MainPass {
    private final ShaderPack pack;

    // G-Buffer Scene Target (colortex0 + depthtex0)
    private Framebuffer gbufferFbo;
    private RenderPass gbufferMainRenderPass;
    private RenderPass gbufferAuxRenderPass;
    private GpuTexture gbufferColorTexture;
    private GpuTextureView gbufferColorTextureView;
    private GpuTexture gbufferDepthTexture;
    private GpuTextureView gbufferDepthTextureView;

    // SwapChain Display Target (GUI phase)
    private Framebuffer swapChainFramebuffer;
    private RenderPass swapChainMainRenderPass;
    private RenderPass swapChainAuxRenderPass;
    private GpuTexture[] swapChainColorTextures;
    private GpuTextureView[] swapChainColorTextureViews;
    private IntSupplier imageIdxSupplier;
    private GpuTexture swapChainDepthTexture;
    private GpuTextureView swapChainDepthTextureView;

    // Startup/resource reload happens before the first world frame.  Treat
    // that period as GUI phase so vanilla menu pipelines discover the
    // swapchain format instead of the pack's R16F G-buffer format.
    private boolean guiPhase = false;

    public PackMainPass(ShaderPack pack) {
        this.pack = pack;
        createResources();
    }

    private void createResources() {
        cleanUp();

        SwapChain swapChain = Renderer.getInstance().getSwapChain();
        int width = swapChain.getWidth();
        int height = swapChain.getHeight();
        if (width <= 0 || height <= 0) {
            width = 854;
            height = 480;
        }

        // 1. Initialize shader pack textures
        try {
            PackFramebuffers.init(width, height, this.pack, ShaderPackConfig.load(this.pack));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Unable to resolve shader-pack framebuffer profile", e);
        }

        // 2. Build G-Buffer Framebuffer around colortex0 and depthtex0
        VulkanImage c0 = PackFramebuffers.getColortex(0);
        VulkanImage d0 = PackFramebuffers.getDepthtex0();

        this.gbufferFbo = Framebuffer.builder(c0, d0).build();

        RenderPass.Builder rpBuilder = RenderPass.builder(this.gbufferFbo);
        rpBuilder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        rpBuilder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE);
        rpBuilder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE);
        this.gbufferMainRenderPass = rpBuilder.build();

        rpBuilder = RenderPass.builder(this.gbufferFbo);
        rpBuilder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        rpBuilder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        rpBuilder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        this.gbufferAuxRenderPass = rpBuilder.build();

        VkGpuDevice device = VkGpuDevice.getInstance();
        this.gbufferColorTexture = device.gpuTextureFromVulkanImage(c0);
        this.gbufferColorTextureView = device.createTextureView(this.gbufferColorTexture);
        this.gbufferDepthTexture = device.gpuTextureFromVulkanImage(d0);
        this.gbufferDepthTextureView = device.createTextureView(this.gbufferDepthTexture);

        // 3. Build SwapChain Framebuffer and passes for GUI
        if (swapChain.hasImages()) {
            this.swapChainFramebuffer = swapChain;
        } else {
            this.swapChainFramebuffer = Framebuffer.builder(10, 10, 1, true).build();
        }

        rpBuilder = RenderPass.builder(this.swapChainFramebuffer);
        rpBuilder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        rpBuilder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE);
        rpBuilder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE);
        this.swapChainMainRenderPass = rpBuilder.build();

        rpBuilder = RenderPass.builder(this.swapChainFramebuffer);
        rpBuilder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        rpBuilder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        rpBuilder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        this.swapChainAuxRenderPass = rpBuilder.build();

        if (this.swapChainFramebuffer == swapChain) {
            var images = swapChain.getImages();
            int count = images.size();
            this.swapChainColorTextures = new GpuTexture[count];
            this.swapChainColorTextureViews = new GpuTextureView[count];
            for (int i = 0; i < count; ++i) {
                VkGpuTexture tex = device.gpuTextureFromVulkanImage(images.get(i));
                this.swapChainColorTextures[i] = tex;
                this.swapChainColorTextureViews[i] = device.createTextureView(tex);
            }
            this.imageIdxSupplier = Renderer::getCurrentImage;
        } else {
            this.swapChainColorTextures = new GpuTexture[1];
            this.swapChainColorTextureViews = new GpuTextureView[1];
            VkGpuTexture tex = device.gpuTextureFromVulkanImage(this.swapChainFramebuffer.getColorAttachment());
            this.swapChainColorTextures[0] = tex;
            this.swapChainColorTextureViews[0] = device.createTextureView(tex);
            this.imageIdxSupplier = () -> 0;
        }

        this.swapChainDepthTexture = device.gpuTextureFromVulkanImage(this.swapChainFramebuffer.getDepthAttachment());
        this.swapChainDepthTextureView = device.createTextureView(this.swapChainDepthTexture);
    }

    public void setGuiPhase(boolean guiPhase) {
        this.guiPhase = guiPhase;
    }

    public void beginGuiPass(VkCommandBuffer cmd, MemoryStack stack) {
        this.guiPhase = true;
        Renderer.getInstance().endRenderPass(cmd);

        VulkanImage swapImage = this.swapChainFramebuffer.getColorAttachment();
        swapImage.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

        VkMemoryBarrier.Buffer memBarrier = VkMemoryBarrier.calloc(1, stack);
        memBarrier.sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER);
        memBarrier.srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
        memBarrier.dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
        vkCmdPipelineBarrier(cmd,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                0, memBarrier, null, null);

        Renderer.getInstance().beginRenderPass(this.swapChainAuxRenderPass, this.swapChainFramebuffer);

        Renderer.setViewport(0, 0, this.swapChainFramebuffer.getWidth(), this.swapChainFramebuffer.getHeight(), stack);
        VkRect2D.Buffer pScissor = this.swapChainFramebuffer.scissor(stack);
        vkCmdSetScissor(cmd, 0, pScissor);

        // Clear depth so GUI elements (held items, tooltip depth, etc.) don't conflict
        Renderer.clearAttachments(0x0100);

        com.mojang.blaze3d.opengl.GlStateManager._disableCull();
        VRenderSystem.cullMode = VK_CULL_MODE_NONE;
        com.mojang.blaze3d.opengl.GlStateManager._enableDepthTest();
        com.mojang.blaze3d.opengl.GlStateManager._depthMask(true);
        VRenderSystem.depthTest = true;
        VRenderSystem.depthMask = true;
        com.mojang.blaze3d.opengl.GlStateManager._enableBlend();
        com.mojang.blaze3d.opengl.GlStateManager._colorMask(com.mojang.blaze3d.pipeline.ColorTargetState.WRITE_ALL);
    }

    public boolean isGuiPhase() {
        return this.guiPhase;
    }

    @Override
    public void begin(VkCommandBuffer commandBuffer, MemoryStack stack) {
        // If outside a world (main menu / title screen), run directly in GUI phase
        if (Minecraft.getInstance().level == null) {
            this.guiPhase = true;
            this.swapChainFramebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            Renderer.getInstance().beginRenderPass(this.swapChainMainRenderPass, this.swapChainFramebuffer);
            Renderer.setViewport(0, 0, this.swapChainFramebuffer.getWidth(), this.swapChainFramebuffer.getHeight(), stack);
            VkRect2D.Buffer pScissor = this.swapChainFramebuffer.scissor(stack);
            vkCmdSetScissor(commandBuffer, 0, pScissor);
            return;
        }

        // World rendering phase. Keep the native sky/frame-graph clear on the
        // swapchain first. The vanilla sky pass executes before the chunk
        // opaque callback where the pack G-buffer is opened; binding the
        // R16F G-buffer here would make vanilla's swapchain-format sky
        // pipeline render against incompatible attachment formats and can
        // reset the GPU on drivers that do not report the mismatch cleanly.
        this.guiPhase = false;

        PackFramebuffers.resetPingPong();

        VulkanImage swapImage = this.swapChainFramebuffer.getColorAttachment();
        swapImage.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        Renderer.getInstance().beginRenderPass(this.swapChainMainRenderPass, this.swapChainFramebuffer);

        Renderer.setViewport(0, 0, this.swapChainFramebuffer.getWidth(), this.swapChainFramebuffer.getHeight(), stack);
        VkRect2D.Buffer pScissor = this.swapChainFramebuffer.scissor(stack);
        vkCmdSetScissor(commandBuffer, 0, pScissor);
    }

    @Override
    public void end(VkCommandBuffer commandBuffer) {
        Renderer.getInstance().endRenderPass(commandBuffer);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            Renderer.getInstance().getSwapChain().getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
        }

        int result = vkEndCommandBuffer(commandBuffer);
        if (result != VK_SUCCESS) {
            throw new RuntimeException("Failed to record command buffer: " + result);
        }
    }

    @Override
    public void rebindMainTarget() {
        VkCommandBuffer cmd = Renderer.getCommandBuffer();
        RenderPass bound = Renderer.getInstance().getBoundRenderPass();

        if (this.guiPhase) {
            if (bound == this.swapChainMainRenderPass || bound == this.swapChainAuxRenderPass) return;
            Renderer.getInstance().endRenderPass(cmd);
            Renderer.getInstance().beginRenderPass(this.swapChainAuxRenderPass, this.swapChainFramebuffer);
            try (MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                Renderer.setViewport(0, 0, this.swapChainFramebuffer.getWidth(), this.swapChainFramebuffer.getHeight(), stack);
                VkRect2D.Buffer pScissor = this.swapChainFramebuffer.scissor(stack);
                vkCmdSetScissor(cmd, 0, pScissor);
            }
        } else {
            // The diagnostic G-buffer bypass keeps native terrain on the
            // swapchain, whose format matches the built-in Vulkan pipeline.
            // Do not switch it into the pack's R16F target in that mode.
            if (Boolean.getBoolean("vulkanmod.disablePackGBuffer")) return;
            if (bound == this.gbufferMainRenderPass || bound == this.gbufferAuxRenderPass) return;
            Renderer.getInstance().endRenderPass(cmd);
            Renderer.getInstance().beginRenderPass(this.gbufferAuxRenderPass, this.gbufferFbo);
            try (MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                Renderer.setViewport(0, 0, this.gbufferFbo.getWidth(), this.gbufferFbo.getHeight(), stack);
                VkRect2D.Buffer pScissor = this.gbufferFbo.scissor(stack);
                vkCmdSetScissor(cmd, 0, pScissor);
            }
        }
    }

    @Override
    public void cleanUp() {
        if (this.gbufferMainRenderPass != null) this.gbufferMainRenderPass.cleanUp();
        if (this.gbufferAuxRenderPass != null) this.gbufferAuxRenderPass.cleanUp();
        if (this.swapChainMainRenderPass != null) this.swapChainMainRenderPass.cleanUp();
        if (this.swapChainAuxRenderPass != null) this.swapChainAuxRenderPass.cleanUp();
        if (this.gbufferFbo != null) {
            this.gbufferFbo.cleanUp(false);
            this.gbufferFbo = null;
        }
    }

    @Override
    public void onResize() {
        createResources();
    }

    @Override
    public Framebuffer getMainFramebuffer() {
        return this.usesSwapChainTarget() ? this.swapChainFramebuffer : this.gbufferFbo;
    }

    @Override
    public GpuTexture getColorAttachment() {
        if (this.usesSwapChainTarget()) {
            return this.swapChainColorTextures[this.imageIdxSupplier.getAsInt()];
        }
        return this.gbufferColorTexture;
    }

    @Override
    public GpuTextureView getColorAttachmentView() {
        if (this.usesSwapChainTarget()) {
            return this.swapChainColorTextureViews[this.imageIdxSupplier.getAsInt()];
        }
        return this.gbufferColorTextureView;
    }

    @Override
    public GpuTexture getDepthAttachment() {
        return this.usesSwapChainTarget() ? this.swapChainDepthTexture : this.gbufferDepthTexture;
    }

    @Override
    public GpuTextureView getDepthAttachmentView() {
        return this.usesSwapChainTarget() ? this.swapChainDepthTextureView : this.gbufferDepthTextureView;
    }

    private boolean usesSwapChainTarget() {
        return this.guiPhase || Minecraft.getInstance().level == null;
    }
}
