package net.beryl.render.util;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

public class BlitUtil {
   private static final Matrix4f BLIT_PROJECTION = new Matrix4f().setOrtho(0, 1, 0, 1, 0, 1, true);
   public static void blitQuad(GraphicsPipeline pipeline, float x0, float y0, float x1, float y1) {
      setupBlitMVP();
      Renderer.getInstance().bindGraphicsPipeline(pipeline);
      Renderer.getInstance().uploadAndBindUBOs(pipeline);
      Tesselator tesselator = Tesselator.getInstance();
      BufferBuilder bufferbuilder = tesselator.begin(Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
      bufferbuilder.addVertex(x0, y0, 0.0F).setUv(0.0F, 1.0F);
      bufferbuilder.addVertex(x1, y0, 0.0F).setUv(1.0F, 1.0F);
      bufferbuilder.addVertex(x1, y1, 0.0F).setUv(1.0F, 0.0F);
      bufferbuilder.addVertex(x0, y1, 0.0F).setUv(0.0F, 0.0F);
      MeshData meshData = bufferbuilder.build();
      Renderer.getDrawer().draw(meshData.vertexBuffer(), Mode.QUADS, meshData.drawState().format(), meshData.drawState().vertexCount());
      meshData.close();
   }

   public static void blitFramebuffer(GraphicsPipeline pipeline, VulkanImage image) {
      VTextureSelector.bindTexture(image);
      blitFramebuffer(pipeline);
   }

   public static void blitFramebuffer(GraphicsPipeline pipeline) {
      Renderer.getInstance().bindGraphicsPipeline(pipeline);
      Renderer.getInstance().uploadAndBindUBOs(pipeline);
      VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
      VK10.vkCmdDraw(commandBuffer, 3, 1, 0, 0);
   }

   public static void blitQuadFramebuffer(GraphicsPipeline pipeline, VulkanImage attachment) {
      VTextureSelector.bindTexture(attachment);
      blitFramebuffer(pipeline);
   }

   public static void blitQuadFramebuffer(GraphicsPipeline pipeline) {
      blitQuad(pipeline, 0.0F, 0.0F, 1.0F, 1.0F);
   }

   public static void setupBlitMVP() {
      Matrix4f matrix4f = BLIT_PROJECTION;
      VRenderSystem.applyProjectionMatrix(matrix4f);
      Matrix4fStack posestack = RenderSystem.getModelViewStack();
      posestack.pushMatrix();
      posestack.identity();
      VRenderSystem.applyModelViewMatrix(posestack);
      VRenderSystem.calculateMVP();
      posestack.popMatrix();
   }
}
