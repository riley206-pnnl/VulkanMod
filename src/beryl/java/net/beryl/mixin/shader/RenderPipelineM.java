package net.beryl.mixin.shader;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.beryl.render.ShaderRenderPipeline;
import net.vulkanmod.interfaces.shader.ExtendedRenderPipeline;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(RenderPipeline.class)
public class RenderPipelineM implements ShaderRenderPipeline {
   @Shadow
   @Final
   private VertexFormat vertexFormat;
   @Unique
   GraphicsPipeline storedPipeline;
   @Unique
   boolean redirected;
   @Unique
   VertexFormat shaderVertexFormat;

   @Override
   public void redirectPipeline(GraphicsPipeline pipeline) {
      this.redirectPipeline(pipeline, null);
   }

   @Override
   public void redirectPipeline(GraphicsPipeline pipeline, VertexFormat format) {
      ExtendedRenderPipeline extPipeline = ExtendedRenderPipeline.of((RenderPipeline)(Object)this);
      if (!this.redirected) {
         this.storedPipeline = extPipeline.getPipeline();
         this.redirected = true;
      }

      extPipeline.setPipeline(pipeline);
      if (format != null) {
         this.shaderVertexFormat = format;
      }
   }

   @Override
   public void resetPipeline() {
      if (!this.redirected) return;
      ExtendedRenderPipeline extPipeline = ExtendedRenderPipeline.of((RenderPipeline)(Object)this);
      extPipeline.setPipeline(this.storedPipeline);
      this.storedPipeline = null;
      this.redirected = false;
      this.shaderVertexFormat = null;
   }

   @Overwrite
   public VertexFormat getVertexFormat() {
      return this.shaderVertexFormat != null ? this.shaderVertexFormat : this.vertexFormat;
   }
}
