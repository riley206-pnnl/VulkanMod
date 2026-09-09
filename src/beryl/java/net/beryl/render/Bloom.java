package net.beryl.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import java.nio.ByteBuffer;
import net.beryl.render.shader.ComputePipeline;
import net.beryl.render.util.BlitUtil;
import net.beryl.render.util.SUtil;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.framebuffer.Framebuffer.Builder;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.PipelineConfig;
import net.vulkanmod.vulkan.shader.PipelineConfig.UB;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkOffset2D;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfoKHR;
import org.lwjgl.vulkan.VkClearValue.Buffer;

public class Bloom {
   static final UB BLOOM_PC = UB.builder(0, 32).addUniform("vec2", "TexelSize").build();
   static final PipelineConfig BLOOM_PIPELINE_CFG = PipelineConfig.builder()
      .setPushConstants(BLOOM_PC)
      .addImageDescriptor(0, "sampler2D", "Sampler0", 0)
      .addImageDescriptor(1, "image2D", "Sampler3", 3)
      .build();
   Framebuffer[] framebuffers;
   RenderPass[] downsamplePasses;
   RenderPass[] upsamplePasses;
   RenderPass finalBlendPass;
   private final GraphicsPipeline downsampleShader;
   private final GraphicsPipeline blendShader;
   private final GraphicsPipeline finalBlendShader;
   private int width;
   private int height;
   private int mipCount;
   private final ComputePipeline downsampleCompute;
   private final ComputePipeline upsampleCompute;
   private final VulkanImage[] computeImages;
   private final int localGroupSize = 8;
   private boolean useComputeShaders;

   public Bloom(int mipCount) {
      this.mipCount = mipCount;
      this.downsampleShader = SUtil.createGraphicsPipeline(DefaultVertexFormat.POSITION_TEX, "bloom/downsample/downsample");
      this.blendShader = SUtil.createGraphicsPipeline(DefaultVertexFormat.POSITION_TEX, "bloom/blend/blend");
      this.finalBlendShader = SUtil.createGraphicsPipeline(DefaultVertexFormat.EMPTY, "bloom/finalBlend/finalBlend");
      this.computeImages = new VulkanImage[mipCount];
      this.downsampleCompute = SUtil.createComputePipeline("bloom_comp/downsample/downsample", BLOOM_PIPELINE_CFG);
      this.upsampleCompute = SUtil.createComputePipeline("bloom_comp/upsample/upsample", BLOOM_PIPELINE_CFG);
   }

   public void createFramebuffers(int width, int height, Framebuffer target) {
      if (this.framebuffers != null) {
         this.cleanUpFramebuffers();
      }
      this.width = width;
      this.height = height;
      this.framebuffers = new Framebuffer[this.mipCount];
      this.upsamplePasses = new RenderPass[this.mipCount - 1];
      this.downsamplePasses = new RenderPass[this.mipCount];

      for (int i = 0; i < this.mipCount; i++) {
         this.framebuffers[i] = new Builder(Math.max(1, this.width >> (i + 1)), Math.max(1, this.height >> (i + 1)), 1, false).setFormat(97).setLinearFiltering(true).build();
         this.downsamplePasses[i] = new net.vulkanmod.vulkan.framebuffer.RenderPass.Builder(this.framebuffers[i]).build();
      }

      for (int i = this.mipCount - 2; i >= 0; i--) {
         this.upsamplePasses[i] = new net.vulkanmod.vulkan.framebuffer.RenderPass.Builder(this.framebuffers[i]).setLoadOp(0).build();
      }

      this.finalBlendPass = new net.vulkanmod.vulkan.framebuffer.RenderPass.Builder(target).build();

      for (int i = 0; i < this.mipCount; i++) {
         this.computeImages[i] = VulkanImage.builder(Math.max(1, this.width >> (i + 1)), Math.max(1, this.height >> (i + 1)))
            .setFormat(97)
            .setUsage(12)
            .setLinearFiltering(true)
            .setClamp(true)
            .createVulkanImage();
      }
   }

   private void cleanUpFramebuffers() {
      for (Framebuffer toFree : this.framebuffers) {
         toFree.cleanUp();
      }

      for (RenderPass toFree : this.upsamplePasses) {
         toFree.cleanUp();
      }

      for (RenderPass toFree : this.downsamplePasses) {
         toFree.cleanUp();
      }

      for (VulkanImage toFree : this.computeImages) {
         toFree.free();
      }

      this.finalBlendPass.cleanUp();
      this.framebuffers = null;
   }

   public void cleanUp() {
      this.cleanUpFramebuffers();
      this.downsampleShader.scheduleCleanUp();
      this.blendShader.scheduleCleanUp();
      this.finalBlendShader.scheduleCleanUp();
      this.downsampleCompute.scheduleCleanUp();
      this.upsampleCompute.scheduleCleanUp();
   }

   public void render(VkCommandBuffer commandBuffer, Framebuffer in) {
      this.useComputeShaders = true;
      if (this.useComputeShaders) {
         MemoryStack stack = MemoryStack.stackPush();

         try {
            this.computeDownsamples(stack, commandBuffer, in);
            this.computeUpsamples(stack, commandBuffer);
         } catch (Throwable var9) {
            if (stack != null) {
               try {
                  stack.close();
               } catch (Throwable var7) {
                  var9.addSuppressed(var7);
               }
            }

            throw var9;
         }

         if (stack != null) {
            stack.close();
         }
      } else {
         MemoryStack stack = MemoryStack.stackPush();

         try {
            this.renderDownsamples(stack, commandBuffer, in);
            this.renderUpsamples(stack, commandBuffer);
            Renderer.setViewport(0, 0, this.width, this.height);
         } catch (Throwable var8) {
            if (stack != null) {
               try {
                  stack.close();
               } catch (Throwable var6) {
                  var8.addSuppressed(var6);
               }
            }

            throw var8;
         }

         if (stack != null) {
            stack.close();
         }
      }
   }

   private void computeDownsamples(MemoryStack stack, VkCommandBuffer commandBuffer, Framebuffer in) {
      VK10.vkCmdBindPipeline(commandBuffer, 1, this.downsampleCompute.getId());
      VulkanImage srcImage = in.getColorAttachment();
      VulkanImage.transitionLayout(stack, commandBuffer, srcImage, 1, 5, 1024, 256, 2048, 32);
      VTextureSelector.bindTexture(srcImage);
      VulkanImage outImage = this.computeImages[0];
      VTextureSelector.bindTexture(3, outImage);
      VulkanImage.transitionLayout(stack, commandBuffer, outImage, 5, 1, VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK10.VK_ACCESS_SHADER_READ_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_ACCESS_SHADER_WRITE_BIT);
      this.computeDownsample(stack, commandBuffer, in.getColorAttachment(), this.computeImages[0]);

      for (int i = 1; i < this.mipCount; i++) {
         srcImage = this.computeImages[i - 1];
         VulkanImage.transitionLayout(stack, commandBuffer, srcImage, 1, 5, 2048, 64, 2048, 32);
         VTextureSelector.bindTexture(srcImage);
         outImage = this.computeImages[i];
         VTextureSelector.bindTexture(3, outImage);
         VulkanImage.transitionLayout(stack, commandBuffer, outImage, 5, 1, 2048, 32, 2048, 64);
         this.computeDownsample(stack, commandBuffer, this.computeImages[i - 1], this.computeImages[i]);
      }
   }

   private void computeDownsample(MemoryStack stack, VkCommandBuffer commandBuffer, VulkanImage srcImage, VulkanImage outImage) {
      ByteBuffer pushConstants = stack.calloc(8);
      pushConstants.putFloat(0, 1.0F / outImage.width);
      pushConstants.putFloat(4, 1.0F / outImage.height);
      VK10.vkCmdPushConstants(commandBuffer, this.downsampleCompute.getLayout(), 32, 0, pushConstants);
      Renderer.getInstance().addUsedPipeline(this.downsampleCompute);
      Renderer.getInstance().uploadAndBindUBOs(this.downsampleCompute);
      int xInv = outImage.width / 8 + (outImage.width % 8 > 0 ? 1 : 0);
      int yInv = outImage.height / 8 + (outImage.height % 8 > 0 ? 1 : 0);
      VK10.vkCmdDispatch(Renderer.getCommandBuffer(), xInv, yInv, 1);
   }

   private void computeUpsamples(MemoryStack stack, VkCommandBuffer commandBuffer) {
      VK10.vkCmdBindPipeline(commandBuffer, 1, this.upsampleCompute.getId());

      for (int i = this.mipCount - 2; i >= 0; i--) {
         VulkanImage srcImage = this.computeImages[i + 1];
         VulkanImage.transitionLayout(stack, commandBuffer, srcImage, 1, 5, 2048, 64, 2048, 32);
         VTextureSelector.bindTexture(srcImage);
         VulkanImage outImage = this.computeImages[i];
         VulkanImage.transitionLayout(stack, commandBuffer, outImage, 5, 1,
            VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT,
            VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
         VTextureSelector.bindTexture(3, outImage);
         ByteBuffer pushConstants = stack.calloc(8);
         pushConstants.putFloat(0, 1.0F / outImage.width);
         pushConstants.putFloat(4, 1.0F / outImage.height);
         VK10.vkCmdPushConstants(commandBuffer, this.upsampleCompute.getLayout(), 32, 0, pushConstants);
         Renderer.getInstance().addUsedPipeline(this.upsampleCompute);
         Renderer.getInstance().uploadAndBindUBOs(this.upsampleCompute);
         int xInv = outImage.width / 8 + (outImage.width % 8 > 0 ? 1 : 0);
         int yInv = outImage.height / 8 + (outImage.height % 8 > 0 ? 1 : 0);
         VK10.vkCmdDispatch(Renderer.getCommandBuffer(), xInv, yInv, 1);
      }

      VulkanImage.transitionLayout(stack, commandBuffer, this.computeImages[0], 1, 5, 2048, 64, 128, 32);
   }

   private void renderDownsamples(MemoryStack stack, VkCommandBuffer commandBuffer, Framebuffer in) {
      in.getColorAttachment().transitionImageLayout(stack, commandBuffer, 5);
      VTextureSelector.bindTexture(in.getColorAttachment());
      this.doDownsampleRenderPass(stack, commandBuffer, 0);

      for (int i = 1; i < this.framebuffers.length; i++) {
         Framebuffer prev = this.framebuffers[i - 1];
         prev.getColorAttachment().transitionImageLayout(stack, commandBuffer, 5);
         VTextureSelector.bindTexture(prev.getColorAttachment());
         this.doDownsampleRenderPass(stack, commandBuffer, i);
      }
   }

   private void doDownsampleRenderPass(MemoryStack stack, VkCommandBuffer commandBuffer, int i) {
      Framebuffer framebuffer = this.framebuffers[i];
      framebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, 2);
      framebuffer.beginRenderPass(commandBuffer, this.downsamplePasses[i], stack);
      Renderer.setViewport(0, 0, framebuffer.getWidth(), framebuffer.getHeight());
      BlitUtil.blitQuadFramebuffer(this.downsampleShader);
      Renderer.getInstance().endRenderPass(commandBuffer);
   }

   private void renderUpsamples(MemoryStack stack, VkCommandBuffer commandBuffer) {
      VRenderSystem.enableBlend();
      VRenderSystem.blendFunc(1, 1);

      for (int i = this.framebuffers.length - 2; i >= 0; i--) {
         Framebuffer prev = this.framebuffers[i + 1];
         prev.getColorAttachment().transitionImageLayout(stack, commandBuffer, 5);
         VTextureSelector.bindTexture(prev.getColorAttachment());
         this.doUpsampleRenderPass(stack, commandBuffer, i);
      }

      VRenderSystem.blendFunc(770, 771);
      this.framebuffers[0].getColorAttachment().transitionImageLayout(stack, commandBuffer, 5);
   }

   private void doUpsampleRenderPass(MemoryStack stack, VkCommandBuffer commandBuffer, int i) {
      Framebuffer framebuffer = this.framebuffers[i];
      framebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, 2);
      framebuffer.beginRenderPass(commandBuffer, this.upsamplePasses[i], stack);
      Renderer.setViewport(0, 0, framebuffer.getWidth(), framebuffer.getHeight());
      BlitUtil.blitQuadFramebuffer(this.blendShader);
      Renderer.getInstance().endRenderPass(commandBuffer);
   }

   public void blendFramebuffer(MemoryStack stack, VkCommandBuffer commandBuffer, VulkanImage image, Framebuffer target) {
      target.getColorAttachment().transitionImageLayout(stack, commandBuffer, 2);
      image.transitionImageLayout(stack, commandBuffer, 5);
      VTextureSelector.bindTexture(3, image);
      VulkanImage bloomImage;
      if (this.useComputeShaders) {
         bloomImage = this.computeImages[0];
      } else {
         bloomImage = this.framebuffers[0].getColorAttachment();
         bloomImage.transitionImageLayout(stack, commandBuffer, 5);
      }

      VTextureSelector.bindTexture(bloomImage);
      VRenderSystem.disableBlend();
      this.doRenderPass(stack, commandBuffer, target, this.finalBlendPass, this.finalBlendShader);
   }

   private void doRenderPass(MemoryStack stack, VkCommandBuffer commandBuffer, Framebuffer framebuffer, RenderPass renderPass, GraphicsPipeline pipeline) {
      framebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, 2);
      Renderer.getInstance().beginRenderPass(renderPass, framebuffer);
      Renderer.setViewport(0, 0, framebuffer.getWidth(), framebuffer.getHeight());
      BlitUtil.blitFramebuffer(pipeline);
      Renderer.getInstance().endRenderPass(commandBuffer);
   }

   private void doRenderPassDynamic(MemoryStack stack, VkCommandBuffer commandBuffer, Framebuffer framebuffer, GraphicsPipeline pipeline) {
      framebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, 2);
      Buffer clearValues = VkClearValue.calloc(2, stack);
      ((VkClearValue)clearValues.get(0)).color().float32(stack.floats(0.0F, 0.0F, 0.0F, 1.0F));
      ((VkClearValue)clearValues.get(1)).depthStencil().set(1.0F, 0);
      org.lwjgl.vulkan.VkRenderingAttachmentInfo.Buffer colorAttachment = VkRenderingAttachmentInfo.calloc(1, stack);
      colorAttachment.sType(1000044001);
      colorAttachment.imageView(framebuffer.getColorAttachment().getImageView());
      colorAttachment.imageLayout(2);
      colorAttachment.loadOp(0);
      colorAttachment.storeOp(0);
      colorAttachment.clearValue((VkClearValue)clearValues.get(0));
      VkRect2D renderArea = VkRect2D.calloc(stack);
      renderArea.offset(VkOffset2D.calloc(stack).set(0, 0));
      renderArea.extent(VkExtent2D.calloc().set(framebuffer.getWidth(), framebuffer.getHeight()));
      VkRenderingInfoKHR renderingInfo = VkRenderingInfoKHR.calloc(stack);
      renderingInfo.sType(1000044000);
      renderingInfo.renderArea(renderArea);
      renderingInfo.layerCount(1);
      renderingInfo.pColorAttachments(colorAttachment);
      Renderer.setViewport(0, 0, framebuffer.getWidth(), framebuffer.getHeight());
      KHRDynamicRendering.vkCmdBeginRenderingKHR(commandBuffer, renderingInfo);
      BlitUtil.blitQuadFramebuffer(pipeline);
      KHRDynamicRendering.vkCmdEndRenderingKHR(commandBuffer);
   }

   public void finalBlend(VkCommandBuffer commandBuffer, Framebuffer framebuffer) {
      MemoryStack stack = MemoryStack.stackPush();

      try {
         BlitUtil.blitQuadFramebuffer(this.finalBlendShader);
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
   }
}
