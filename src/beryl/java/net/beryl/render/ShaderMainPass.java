package net.beryl.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.beryl.render.util.BlitUtil;
import net.beryl.render.util.SUtil;
import net.vulkanmod.render.engine.VkGpuDevice;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.framebuffer.Framebuffer.Builder;
import net.vulkanmod.vulkan.pass.MainPass;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkViewport.Buffer;

public class ShaderMainPass implements MainPass {
   public static ShaderMainPass PASS = new ShaderMainPass();
   public Framebuffer hdrFinalFramebuffer;
   public Framebuffer finalFramebuffer;
   public RenderPass renderPass;
   public RenderPass rebindPass;
   public RenderPass finalPass;
   private GraphicsPipeline blitGammaShader;
   private GraphicsPipeline blitGammaTriShader;
   private GraphicsPipeline blitShader;
   private GraphicsPipeline blitTriShader;
   private boolean earlyRenderPass = true;
   private GpuTexture colorAttachmentTexture;
   private GpuTextureView colorAttachmentTextureView;
   private GpuTexture depthAttachmentTexture;
   private final Reference2ReferenceOpenHashMap<Framebuffer, ShaderMainPass.FramebufferResources> framebufferResources = new Reference2ReferenceOpenHashMap();
   private Framebuffer currentFramebuffer;

   public void begin(VkCommandBuffer commandBuffer, MemoryStack stack) {
      if (this.earlyRenderPass) {
         Framebuffer framebuffer = this.finalFramebuffer;
         framebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, 2);
         Renderer.getInstance().beginRenderPass(this.renderPass, framebuffer);
         Renderer.clearAttachments(16640);
         Renderer.getInstance().setBoundFramebuffer(framebuffer);
         Buffer pViewport = framebuffer.viewport(stack);
         VK10.vkCmdSetViewport(commandBuffer, 0, pViewport);
         org.lwjgl.vulkan.VkRect2D.Buffer pScissor = framebuffer.scissor(stack);
         VK10.vkCmdSetScissor(commandBuffer, 0, pScissor);
         this.currentFramebuffer = this.finalFramebuffer;
      }
   }

   public void end(VkCommandBuffer commandBuffer) {
      Renderer.getInstance().endRenderPass(commandBuffer);
      MemoryStack stack = MemoryStack.stackPush();

      try {
         this.finalFramebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, 5);
         SwapChain swapChain = Renderer.getInstance().getSwapChain();
         if (swapChain.hasImages()) {
            swapChain.getColorAttachment().transitionImageLayout(stack, commandBuffer, 2);
            Renderer.getInstance().beginRenderPass(this.finalPass, swapChain);
            VRenderSystem.disableDepthTest();
            VRenderSystem.disableCull();
            VRenderSystem.disableBlend();
            BlitUtil.blitFramebuffer(this.blitTriShader, this.finalFramebuffer.getColorAttachment());
            Renderer.getInstance().endRenderPass(commandBuffer);
            swapChain.getColorAttachment().transitionImageLayout(stack, commandBuffer, 1000001002);
         }
      } catch (Throwable var6) {
         if (stack != null) {
            try {
               stack.close();
            } catch (Throwable var5) {
               var6.addSuppressed(var5);
            }
         }

         throw var6;
      }

      if (stack != null) {
         stack.close();
      }

      int result = VK10.vkEndCommandBuffer(commandBuffer);
      if (result != 0) {
         throw new RuntimeException("Failed to record command buffer:" + result);
      }
   }

   public void rebindMainTarget() {
      VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
      Framebuffer boundFramebuffer = Renderer.getInstance().getBoundFramebuffer();
      if (boundFramebuffer != this.currentFramebuffer) {
         Renderer.getInstance().endRenderPass(commandBuffer);
         Renderer.getInstance().beginRenderPass(this.rebindPass, this.currentFramebuffer);
      }
   }

   public void bindAsTexture() {
      VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
      RenderPass boundRenderPass = Renderer.getInstance().getBoundRenderPass();
      if (boundRenderPass == this.renderPass) {
         Renderer.getInstance().endRenderPass(commandBuffer);
      }

      MemoryStack stack = MemoryStack.stackPush();

      try {
         this.finalFramebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, 5);
      } catch (Throwable var7) {
         if (stack != null) {
            try {
               stack.close();
            } catch (Throwable var6) {
               var7.addSuppressed(var6);
            }
         }

         throw var7;
      }

      if (stack != null) {
         stack.close();
      }

      VTextureSelector.bindTexture(this.finalFramebuffer.getColorAttachment());
   }

   public void beginFinalRenderPass() {
      Renderer.getInstance().beginRenderPass(this.renderPass, this.finalFramebuffer);
      this.currentFramebuffer = this.finalFramebuffer;
   }

   public void init() {
      this.initShaders();
      this.initFramebuffer();
   }

   public void initShaders() {
      this.blitGammaShader = SUtil.createGraphicsPipeline(DefaultVertexFormat.POSITION_TEX, "blit/gamma");
      this.blitGammaTriShader = SUtil.createGraphicsPipeline(DefaultVertexFormat.EMPTY, "blit/blitGammaTri");
      this.blitTriShader = SUtil.createGraphicsPipeline(DefaultVertexFormat.EMPTY, "blit/blitTri");
      this.blitShader = SUtil.createGraphicsPipeline(DefaultVertexFormat.POSITION_TEX, "blit/blit");
   }

   public void updateShaders() {
      this.cleanUpShaders();
      this.initShaders();
   }

   public void initFramebuffer() {
      if (this.hdrFinalFramebuffer != null) {
         this.hdrFinalFramebuffer.cleanUp();
         this.finalFramebuffer.cleanUp();
         this.renderPass.cleanUp();
         this.rebindPass.cleanUp();
         this.finalPass.cleanUp();
      }

      Framebuffer swapChain = Renderer.getInstance().getSwapChain();
      this.hdrFinalFramebuffer = new Builder("hdrFinalFramebuffer", swapChain.getWidth(), swapChain.getHeight(), 1, true).setFormat(97).build();
      this.finalFramebuffer = new Builder("finalFramebuffer", swapChain.getWidth(), swapChain.getHeight(), 1, true).setFormat(37).build();
      net.vulkanmod.vulkan.framebuffer.RenderPass.Builder builder = new net.vulkanmod.vulkan.framebuffer.RenderPass.Builder(this.finalFramebuffer);
      builder.getColorAttachmentInfo().setOps(2, 0);
      builder.getDepthAttachmentInfo().setOps(2, 0);
      this.renderPass = builder.build();
      builder = RenderPass.builder(swapChain);
      builder.getColorAttachmentInfo().setFinalLayout(2);
      builder.getColorAttachmentInfo().setOps(2, 0);
      builder.getDepthAttachmentInfo().setOps(2, 0);
      this.finalPass = builder.build();
      builder = RenderPass.builder(this.finalFramebuffer);
      builder.getColorAttachmentInfo().setFinalLayout(2);
      builder.getColorAttachmentInfo().setOps(0, 0);
      builder.getDepthAttachmentInfo().setOps(0, 0);
      this.rebindPass = builder.build();
      this.currentFramebuffer = this.hdrFinalFramebuffer;
      this.createAttachmentTextures();
   }

   public void setEarlyRenderPass(boolean earlyRenderPass) {
      this.earlyRenderPass = earlyRenderPass;
   }

   public void setCurrentFramebuffer(Framebuffer currentFramebuffer) {
      this.currentFramebuffer = currentFramebuffer;
   }

   public void cleanUp() {
      this.hdrFinalFramebuffer.cleanUp();
      this.finalFramebuffer.cleanUp();
      this.renderPass.cleanUp();
      this.rebindPass.cleanUp();
      this.finalPass.cleanUp();
      this.framebufferResources.clear();
      this.cleanUpShaders();
   }

   private void cleanUpShaders() {
      this.blitGammaShader.scheduleCleanUp();
      this.blitGammaTriShader.scheduleCleanUp();
      this.blitShader.scheduleCleanUp();
      this.blitTriShader.scheduleCleanUp();
   }

   public void onResize() {
      this.createAttachmentTextures();
   }

   public GpuTexture getColorAttachment() {
      ShaderMainPass.FramebufferResources res = this.getFramebuffereResources(this.currentFramebuffer);
      return res.colorAttachmentTexture;
   }

   public GpuTextureView getColorAttachmentView() {
      ShaderMainPass.FramebufferResources res = this.getFramebuffereResources(this.currentFramebuffer);
      return res.colorAttachmentTextureView;
   }

   public GpuTexture getDepthAttachment() {
      ShaderMainPass.FramebufferResources res = this.getFramebuffereResources(this.currentFramebuffer);
      return res.depthAttachmentTexture;
   }

   private ShaderMainPass.FramebufferResources getFramebuffereResources(Framebuffer framebuffer) {
      return (ShaderMainPass.FramebufferResources)this.framebufferResources
         .computeIfAbsent(framebuffer, framebuffer1 -> this.createFramebufferResources((Framebuffer)framebuffer1));
   }

   private ShaderMainPass.FramebufferResources createFramebufferResources(Framebuffer framebuffer) {
      VkGpuDevice device = (VkGpuDevice)RenderSystem.getDevice().backend;
      VkGpuTexture attachmentTexture = device.gpuTextureFromVulkanImage(framebuffer.getColorAttachment());
      GpuTextureView attachmentTextureView = device.createTextureView(attachmentTexture);
      VkGpuTexture depthAttachmentTexture = device.gpuTextureFromVulkanImage(framebuffer.getDepthAttachment());
      return new ShaderMainPass.FramebufferResources(attachmentTexture, attachmentTextureView, depthAttachmentTexture);
   }

   private void createAttachmentTextures() {
      this.framebufferResources.clear();
      VkGpuDevice device = (VkGpuDevice)RenderSystem.getDevice().backend;
      VkGpuTexture attachmentTexture = device.gpuTextureFromVulkanImage(this.finalFramebuffer.getColorAttachment());
      GpuTextureView attachmentTextureView = device.createTextureView(attachmentTexture);
      this.colorAttachmentTexture = attachmentTexture;
      this.colorAttachmentTextureView = attachmentTextureView;
      this.depthAttachmentTexture = device.gpuTextureFromVulkanImage(this.finalFramebuffer.getDepthAttachment());
   }

   record FramebufferResources(GpuTexture colorAttachmentTexture, GpuTextureView colorAttachmentTextureView, GpuTexture depthAttachmentTexture) {
   }
}
