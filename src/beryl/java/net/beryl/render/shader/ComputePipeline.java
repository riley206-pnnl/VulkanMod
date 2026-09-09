package net.beryl.render.shader;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.buffer.UniformBuffer;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.SpirvCompiler;
import net.vulkanmod.vulkan.shader.Pipeline.Builder;
import net.vulkanmod.vulkan.shader.SpirvCompiler.SPIRV;
import net.vulkanmod.vulkan.shader.SpirvCompiler.ShaderKind;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo.Buffer;

public class ComputePipeline extends Pipeline {
   private final long pipeline;
   private long shaderModule = 0L;

   public ComputePipeline(Builder builder) {
      super(builder.name);
      this.buffers = builder.getUBOs();
      this.imageDescriptors = builder.getImageDescriptors();
      this.pushConstants = builder.getPushConstants();
      this.createDescriptorSetLayout();
      this.createPipelineLayout();
      String csh = (String)builder.getShadersSrc().get(ShaderKind.COMPUTE_SHADER);
      SPIRV spirv = SpirvCompiler.compileShader(String.format("%s.comp", this.name), csh, ShaderKind.COMPUTE_SHADER);
      this.shaderModule = createShaderModule(spirv.bytecode());
      this.pipeline = this.createPipeline();
      this.createDescriptorSets(Renderer.getFramesNum());
      PIPELINES.add(this);
   }

   private long createPipeline() {
      MemoryStack stack = MemoryStack.stackPush();

      long var6;
      try {
         ByteBuffer entryPoint = stack.UTF8("main");
         VkPipelineShaderStageCreateInfo shaderStage = VkPipelineShaderStageCreateInfo.calloc(stack);
         shaderStage.sType$Default();
         shaderStage.stage(32);
         shaderStage.module(this.shaderModule);
         shaderStage.pName(entryPoint);
         Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
         pipelineInfo.sType$Default();
         pipelineInfo.stage(shaderStage);
         pipelineInfo.layout(this.pipelineLayout);
         pipelineInfo.basePipelineHandle(0L);
         pipelineInfo.basePipelineIndex(-1);
         LongBuffer pipeline = stack.mallocLong(1);
         if (VK10.vkCreateComputePipelines(Vulkan.getVkDevice(), PIPELINE_CACHE, pipelineInfo, null, pipeline) != 0) {
            throw new RuntimeException("Failed to create graphics pipeline");
         }

         var6 = pipeline.get(0);
      } catch (Throwable var9) {
         if (stack != null) {
            try {
               stack.close();
            } catch (Throwable var8) {
               var9.addSuppressed(var8);
            }
         }

         throw var9;
      }

      if (stack != null) {
         stack.close();
      }

      return var6;
   }

   public void bindDescriptorSets(VkCommandBuffer commandBuffer, int frame) {
      UniformBuffer uniformBuffer = Renderer.getDrawer().getUniformBuffer();
      this.descriptorSets[frame].bindSets(commandBuffer, uniformBuffer, 1);
   }

   public void bindDescriptorSets(VkCommandBuffer commandBuffer, UniformBuffer uniformBuffer, int frame) {
      this.descriptorSets[frame].bindSets(commandBuffer, uniformBuffer, 1);
   }

   public void cleanUp() {
      VK10.vkDestroyShaderModule(Vulkan.getVkDevice(), this.shaderModule, null);
      this.destroyDescriptorSets();
      VK10.vkDestroyPipeline(Vulkan.getVkDevice(), this.pipeline, null);
      VK10.vkDestroyDescriptorSetLayout(Vulkan.getVkDevice(), this.descriptorSetLayout, null);
      VK10.vkDestroyPipelineLayout(Vulkan.getVkDevice(), this.pipelineLayout, null);
      PIPELINES.remove(this);
      Renderer.getInstance().removeUsedPipeline(this);
   }

   public long getId() {
      return this.pipeline;
   }
}
