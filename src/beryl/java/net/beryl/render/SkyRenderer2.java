package net.beryl.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.MoonPhase;
import net.vulkanmod.render.VBO;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import org.joml.Matrix3f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;

public class SkyRenderer2 {
   VBO skyVBO = new VBO(true);
   VBO starVBO;
   VBO sunVBO;

   public SkyRenderer2() {
      MeshData meshData = buildSkyDisc(Tesselator.getInstance(), 16.0F);
      this.skyVBO.upload(meshData);
      this.starVBO = new VBO(true);
      this.starVBO.upload(drawStars(Tesselator.getInstance()));
      this.sunVBO = this.createSunVBO(Tesselator.getInstance());
   }

   public void renderSkyDisc(float f, float g, float h) {
      VRenderSystem.applyMVP(RenderingPipeline.view, RenderingPipeline.projection);
      VRenderSystem.disableCull();
      VRenderSystem.glDepthFun(515);
      VRenderSystem.disableDepthTest();
      VRenderSystem.depthMask(false);
      VRenderSystem.disableBlend();
      VRenderSystem.colorMask(true, true, true, true);
      VRenderSystem.disablePolygonOffset();
      VRenderSystem.setPolygonModeGL(6914);
      VRenderSystem.setPrimitiveTopologyGL(4);
      GraphicsPipeline pipeline = RenderingPipeline.getSkyShader();
      Renderer renderer = Renderer.getInstance();
      renderer.bindGraphicsPipeline(pipeline);
      VTextureSelector.bindShaderTextures(pipeline);
      renderer.uploadAndBindUBOs(pipeline);
      this.skyVBO.draw();
   }

   public void renderSunMoonAndStars(PoseStack poseStack, float f, float g, float h, MoonPhase moonPhase, float i, float j) {
      poseStack.pushPose();
      poseStack.mulPose(Axis.YP.rotationDegrees(-90.0F));
      poseStack.mulPose(Axis.XP.rotationDegrees(f * 360.0F));
      this.renderSun(i, poseStack);
      poseStack.popPose();
   }

   public static MeshData buildSkyDisc(Tesselator tesselator, float f) {
      float height = 128.0F;
      float length = 512.0F;
      float degToRad = (float) (Math.PI / 180.0);
      BufferBuilder bufferBuilder = tesselator.begin(Mode.TRIANGLES, DefaultVertexFormat.POSITION);
      float h0 = 0.0F;
      int step = 10;

      for (int i = -180; i < 180; i += step) {
         int j = i + step;
         bufferBuilder.addVertex(0.0F, height, 0.0F);
         bufferBuilder.addVertex(length * Mth.cos(i * degToRad), h0, length * Mth.sin(i * degToRad));
         bufferBuilder.addVertex(length * Mth.cos(j * degToRad), h0, length * Mth.sin(j * degToRad));
      }

      for (int i = -180; i < 180; i += step) {
         int var12 = i - step;
         bufferBuilder.addVertex(0.0F, -height, 0.0F);
         bufferBuilder.addVertex(length * Mth.cos(i * degToRad), h0, length * Mth.sin(i * degToRad));
         bufferBuilder.addVertex(length * Mth.cos(var12 * degToRad), h0, length * Mth.sin(var12 * degToRad));
      }

      return bufferBuilder.buildOrThrow();
   }

   public VBO createSunVBO(Tesselator tesselator) {
      VBO sunVBO = new VBO(true);
      BufferBuilder bufferbuilder = tesselator.begin(Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION);
      float radius = 5.0F;
      float distance = 100.0F;
      int vertices = 100;
      bufferbuilder.addVertex(0.0F, distance, 0.0F);
      float wi = (float) (Math.PI * 2) / vertices;
      float w0 = wi;

      for (int i = 0; i <= vertices; i++) {
         w0 += wi;
         bufferbuilder.addVertex(radius * Mth.cos(w0), distance, radius * Mth.sin(w0));
      }

      sunVBO.upload(bufferbuilder.buildOrThrow());
      return sunVBO;
   }

   public void renderSky() {
      GraphicsPipeline pipeline = RenderingPipeline.getSkyShader();
   }

   public void renderSun(float f, PoseStack poseStack) {
   }

   public void renderStars(float f, PoseStack poseStack) {
      float starBrightness = f * 1.5F;
      if (starBrightness > 0.0F) {
         Matrix4fStack matrix4fStack = RenderSystem.getModelViewStack();
         matrix4fStack.pushMatrix();
         matrix4fStack.mul(poseStack.last().pose());
         GraphicsPipeline pipeline = RenderingPipeline.getStarsShader();
         VRenderSystem.setShaderColor(starBrightness, starBrightness, starBrightness, 1.0F);
         VRenderSystem.applyModelViewMatrix(RenderSystem.getModelViewMatrix());
         VRenderSystem.calculateMVP();
         this.starVBO.bind(pipeline);
         this.starVBO.draw();
         matrix4fStack.popMatrix();
         VRenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
      }
   }

   private static GpuBuffer buildCelestialQuad(String string, TextureAtlasSprite textureAtlasSprite) {
      VertexFormat vertexFormat = DefaultVertexFormat.POSITION_TEX;
      ByteBufferBuilder byteBufferBuilder = ByteBufferBuilder.exactlySized(4 * vertexFormat.getVertexSize());

      GpuBuffer gpuBuffer;
      try {
         BufferBuilder bufferBuilder = new BufferBuilder(byteBufferBuilder, Mode.QUADS, vertexFormat);
         bufferBuilder.addVertex(-1.0F, 0.0F, -1.0F).setUv(textureAtlasSprite.getU0(), textureAtlasSprite.getV0());
         bufferBuilder.addVertex(1.0F, 0.0F, -1.0F).setUv(textureAtlasSprite.getU1(), textureAtlasSprite.getV0());
         bufferBuilder.addVertex(1.0F, 0.0F, 1.0F).setUv(textureAtlasSprite.getU1(), textureAtlasSprite.getV1());
         bufferBuilder.addVertex(-1.0F, 0.0F, 1.0F).setUv(textureAtlasSprite.getU0(), textureAtlasSprite.getV1());
         MeshData meshData = bufferBuilder.buildOrThrow();

         try {
            gpuBuffer = RenderSystem.getDevice().createBuffer(() -> string, 32, meshData.vertexBuffer());
         } catch (Throwable var11) {
            if (meshData != null) {
               try {
                  meshData.close();
               } catch (Throwable var10) {
                  var11.addSuppressed(var10);
               }
            }

            throw var11;
         }

         if (meshData != null) {
            meshData.close();
         }
      } catch (Throwable var12) {
         if (byteBufferBuilder != null) {
            try {
               byteBufferBuilder.close();
            } catch (Throwable var9) {
               var12.addSuppressed(var9);
            }
         }

         throw var12;
      }

      if (byteBufferBuilder != null) {
         byteBufferBuilder.close();
      }

      return gpuBuffer;
   }

   public static MeshData drawStars(Tesselator tesselator) {
      RandomSource randomSource = RandomSource.create(10842L);
      BufferBuilder bufferBuilder = tesselator.begin(Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

      for (int i = 0; i < 1500; i++) {
         float g = randomSource.nextFloat() * 2.0F - 1.0F;
         float h = randomSource.nextFloat() * 2.0F - 1.0F;
         float j = randomSource.nextFloat() * 2.0F - 1.0F;
         float k = 0.15F + randomSource.nextFloat() * 0.3F;
         float brightness = 1.5F * randomSource.nextFloat() + 0.5F;
         float l = Mth.lengthSquared(g, h, j);
         if (!(l <= 0.010000001F) && !(l >= 1.0F)) {
            Vector3f vector3f = new Vector3f(g, h, j).normalize(100.0F);
            float m = (float)(randomSource.nextDouble() * (float) Math.PI * 2.0);
            Matrix3f matrix3f = new Matrix3f().rotateTowards(new Vector3f(vector3f).negate(), new Vector3f(0.0F, 1.0F, 0.0F)).rotateZ(-m);
            bufferBuilder.addVertex(new Vector3f(k, -k, 0.0F).mul(matrix3f).add(vector3f)).setColor(brightness, brightness, brightness, 1.0F);
            bufferBuilder.addVertex(new Vector3f(k, k, 0.0F).mul(matrix3f).add(vector3f)).setColor(brightness, brightness, brightness, 1.0F);
            bufferBuilder.addVertex(new Vector3f(-k, k, 0.0F).mul(matrix3f).add(vector3f)).setColor(brightness, brightness, brightness, 1.0F);
            bufferBuilder.addVertex(new Vector3f(-k, -k, 0.0F).mul(matrix3f).add(vector3f)).setColor(brightness, brightness, brightness, 1.0F);
         }
      }

      return bufferBuilder.buildOrThrow();
   }
}
