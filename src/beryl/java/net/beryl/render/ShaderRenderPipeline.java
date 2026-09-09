package net.beryl.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;

public interface ShaderRenderPipeline {
   static ShaderRenderPipeline of(RenderPipeline renderPipeline) {
      return (ShaderRenderPipeline)renderPipeline;
   }

   void redirectPipeline(GraphicsPipeline var1, VertexFormat var2);

   void redirectPipeline(GraphicsPipeline var1);

   void resetPipeline();
}
