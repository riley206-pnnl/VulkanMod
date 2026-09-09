package net.beryl.mixin.vkmod;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.beryl.render.ShaderRendererResources;
import net.vulkanmod.render.vertex.QuadSorter;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(QuadSorter.class)
public class QuadSorterMixin {
   @Shadow
   private int vertexCount;
   @Shadow
   private Vector3f[] sortingPoints;
   @Shadow
   private float[] distances;
   @Shadow
   private int[] sortingPointsIndices;

   @Overwrite
   public void setupQuadSortingPoints(long bufferPtr, int vertexCount, VertexFormat format) {
      this.vertexCount = vertexCount;
      int pointCount = vertexCount / 4;
      Vector3f[] sortingPoints = new Vector3f[pointCount];
      int vertexSize = format.getVertexSize();
      int quadStride = vertexSize * 4;
      int offset = vertexSize * 2;
      boolean compressedFormat = format == ShaderRendererResources.EXT_COMPRESSED_TERRAIN_FORMAT;
      if (compressedFormat) {
         float invConv = 4.8828125E-4F;
         float convOffset = 4.0F;

         for (int m = 0; m < pointCount; m++) {
            long ptr = bufferPtr + (long)m * quadStride;
            short x0 = MemoryUtil.memGetShort(ptr + 0L);
            short y0 = MemoryUtil.memGetShort(ptr + 2L);
            short z0 = MemoryUtil.memGetShort(ptr + 4L);
            short x2 = MemoryUtil.memGetShort(ptr + offset + 0L);
            short y2 = MemoryUtil.memGetShort(ptr + offset + 2L);
            short z2 = MemoryUtil.memGetShort(ptr + offset + 4L);
            float xa = (x0 + x2) * 4.8828125E-4F * 0.5F + 4.0F;
            float ya = (y0 + y2) * 4.8828125E-4F * 0.5F + 4.0F;
            float za = (z0 + z2) * 4.8828125E-4F * 0.5F + 4.0F;
            sortingPoints[m] = new Vector3f(xa, ya, za);
         }
      } else {
         for (int m = 0; m < pointCount; m++) {
            long ptr = bufferPtr + (long)m * quadStride;
            float x0 = MemoryUtil.memGetFloat(ptr + 0L);
            float y0 = MemoryUtil.memGetFloat(ptr + 4L);
            float z0 = MemoryUtil.memGetFloat(ptr + 8L);
            float x2 = MemoryUtil.memGetFloat(ptr + offset + 0L);
            float y2 = MemoryUtil.memGetFloat(ptr + offset + 4L);
            float z2 = MemoryUtil.memGetFloat(ptr + offset + 8L);
            float q = (x0 + x2) * 0.5F;
            float r = (y0 + y2) * 0.5F;
            float s = (z0 + z2) * 0.5F;
            sortingPoints[m] = new Vector3f(q, r, s);
         }
      }

      this.sortingPoints = sortingPoints;
      this.distances = new float[pointCount];
      this.sortingPointsIndices = new int[pointCount];
   }
}
