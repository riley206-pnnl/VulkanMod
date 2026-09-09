package net.beryl.render.build;

import net.beryl.render.ShaderRendererResources;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.vulkanmod.render.vertex.TerrainBuilder;
import net.vulkanmod.render.vertex.VertexBuilder;
import org.lwjgl.system.MemoryUtil;

public class ExtTerrainBuilder extends TerrainBuilder {
   private float u0;
   private float v0;
   private final ExtTerrainBuilder.ExtCompVertexBuilder extVertexBuilder = (ExtTerrainBuilder.ExtCompVertexBuilder)this.vertexBuilder;

   public ExtTerrainBuilder(int size) {
      super(size, new ExtTerrainBuilder.ExtCompVertexBuilder());
   }

   public void setBlockAttributes(BlockState blockState) {
      this.extVertexBuilder.blockId = ShaderRendererResources.getBlockId(blockState);
   }

   public void setFluidBlockAttributes(FluidState fluidState) {
      this.extVertexBuilder.blockId = ShaderRendererResources.getBlockId(fluidState);
   }

   public void setUV0(float u0, float v0) {
      this.u0 = u0;
      this.v0 = v0;
   }

   public void setWaterDepth(float waterDepth) {
      this.extVertexBuilder.waterDepth = waterDepth;
   }

   static class ExtCompVertexBuilder implements VertexBuilder {
      private static final int VERTEX_SIZE = 28;
      public static final float POS_CONV_MUL = 2048.0F;
      public static final float POS_OFFSET = -4.0F;
      public static final float POS_OFFSET_CONV = -8192.0F;
      public static final float UV_CONV_MUL = 32768.0F;
      int blockId = -1;
      float waterDepth = 0.0F;

      public void vertex(long ptr, float x, float y, float z, int color, float u, float v, int light, int packedNormal) {
         short sX = (short)(x * 2048.0F + -8192.0F);
         short sY = (short)(y * 2048.0F + -8192.0F);
         short sZ = (short)(z * 2048.0F + -8192.0F);
         MemoryUtil.memPutShort(ptr + 0L, sX);
         MemoryUtil.memPutShort(ptr + 2L, sY);
         MemoryUtil.memPutShort(ptr + 4L, sZ);
         short l = (short)(light >>> 8 & 0xFF00 | light & 0xFF);
         MemoryUtil.memPutShort(ptr + 6L, l);
         MemoryUtil.memPutShort(ptr + 8L, (short)(u * 32768.0F));
         MemoryUtil.memPutShort(ptr + 10L, (short)(v * 32768.0F));
         MemoryUtil.memPutInt(ptr + 12L, color);
         MemoryUtil.memPutInt(ptr + 16L, packedNormal);
         MemoryUtil.memPutInt(ptr + 20L, this.blockId);
         MemoryUtil.memPutFloat(ptr + 24L, this.waterDepth);
      }

      public void position(long ptr, float x, float y, float z) {
         short sX = (short)(x * 2048.0F + -8192.0F);
         short sY = (short)(y * 2048.0F + -8192.0F);
         short sZ = (short)(z * 2048.0F + -8192.0F);
         MemoryUtil.memPutShort(ptr + 0L, sX);
         MemoryUtil.memPutShort(ptr + 2L, sY);
         MemoryUtil.memPutShort(ptr + 4L, sZ);
         MemoryUtil.memPutInt(ptr + 20L, this.blockId);
         MemoryUtil.memPutFloat(ptr + 24L, this.waterDepth);
      }

      public void color(long ptr, int color) {
         MemoryUtil.memPutInt(ptr + 12L, color);
      }

      public void uv(long ptr, float u, float v) {
         MemoryUtil.memPutShort(ptr + 8L, (short)(u * 32768.0F));
         MemoryUtil.memPutShort(ptr + 10L, (short)(v * 32768.0F));
      }

      public void light(long ptr, int light) {
         short l = (short)(light >>> 8 & 0xFF00 | light & 0xFF);
         MemoryUtil.memPutShort(ptr + 6L, l);
      }

      public void normal(long ptr, int normal) {
         MemoryUtil.memPutInt(ptr + 16L, normal);
      }

      public int getStride() {
         return 28;
      }
   }

   static class ExtVertexBuilder implements VertexBuilder {
      private static final int VERTEX_SIZE = 40;
      int blockId = -1;
      float waterDepth = 0.0F;

      public void vertex(long ptr, float x, float y, float z, int color, float u, float v, int light, int packedNormal) {
         MemoryUtil.memPutFloat(ptr + 0L, x);
         MemoryUtil.memPutFloat(ptr + 4L, y);
         MemoryUtil.memPutFloat(ptr + 8L, z);
         MemoryUtil.memPutInt(ptr + 12L, color);
         MemoryUtil.memPutFloat(ptr + 16L, u);
         MemoryUtil.memPutFloat(ptr + 20L, v);
         MemoryUtil.memPutShort(ptr + 24L, (short)(light & 65535));
         MemoryUtil.memPutShort(ptr + 26L, (short)(light >> 16 & 65535));
         MemoryUtil.memPutInt(ptr + 28L, packedNormal);
         MemoryUtil.memPutInt(ptr + 32L, this.blockId);
         MemoryUtil.memPutFloat(ptr + 36L, this.waterDepth);
      }

      public void position(long ptr, float x, float y, float z) {
         MemoryUtil.memPutFloat(ptr + 0L, x);
         MemoryUtil.memPutFloat(ptr + 4L, y);
         MemoryUtil.memPutFloat(ptr + 8L, z);
         MemoryUtil.memPutInt(ptr + 32L, this.blockId);
         MemoryUtil.memPutFloat(ptr + 36L, this.waterDepth);
      }

      public void color(long ptr, int color) {
         MemoryUtil.memPutInt(ptr + 12L, color);
      }

      public void uv(long ptr, float u, float v) {
         MemoryUtil.memPutFloat(ptr + 16L, u);
         MemoryUtil.memPutFloat(ptr + 20L, v);
      }

      public void light(long ptr, int light) {
         MemoryUtil.memPutShort(ptr + 24L, (short)(light & 65535));
         MemoryUtil.memPutShort(ptr + 26L, (short)(light >> 16 & 65535));
      }

      public void normal(long ptr, int normal) {
         MemoryUtil.memPutInt(ptr + 28L, normal);
      }

      public int getStride() {
         return 40;
      }
   }
}
