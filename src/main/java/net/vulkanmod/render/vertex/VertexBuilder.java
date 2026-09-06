package net.vulkanmod.render.vertex;

import org.lwjgl.system.MemoryUtil;

public interface VertexBuilder {
    void vertex(long ptr, float x, float y, float z, int color, float u, float v, int light, int packedNormal);

    void position(long ptr, float x, float y, float z);

    void color(long ptr, int color);

    void uv(long ptr, float u, float v);

    void light(long ptr, int light);

    void normal(long ptr, int normal);

    int getStride();

    class DefaultVertexBuilder implements VertexBuilder {
        private static final int VERTEX_SIZE = 32;

        /** Minecraft's BLOCK format no longer contains normals. Recover them
         * from the final quad geometry when a producer did not supply one. */
        public static void completeQuadNormals(long ptr, int vertexCount) {
            for (int vertex = 0; vertex + 3 < vertexCount; vertex += 4) {
                long q = ptr + (long) vertex * VERTEX_SIZE;
                float ax = MemoryUtil.memGetFloat(q + 64) - MemoryUtil.memGetFloat(q);
                float ay = MemoryUtil.memGetFloat(q + 68) - MemoryUtil.memGetFloat(q + 4);
                float az = MemoryUtil.memGetFloat(q + 72) - MemoryUtil.memGetFloat(q + 8);
                float bx = MemoryUtil.memGetFloat(q + 96) - MemoryUtil.memGetFloat(q + 32);
                float by = MemoryUtil.memGetFloat(q + 100) - MemoryUtil.memGetFloat(q + 36);
                float bz = MemoryUtil.memGetFloat(q + 104) - MemoryUtil.memGetFloat(q + 40);
                float nx = ay * bz - az * by;
                float ny = az * bx - ax * bz;
                float nz = ax * by - ay * bx;
                float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                int normal = length > 0 && Float.isFinite(length)
                        ? net.vulkanmod.render.vertex.format.I32_SNorm.packNormal(nx / length, ny / length, nz / length)
                        : net.vulkanmod.render.vertex.format.I32_SNorm.packNormal(0.0f, 1.0f, 0.0f);
                for (int i = 0; i < 4; i++) {
                    long n = q + (long) i * VERTEX_SIZE + 28;
                    if ((MemoryUtil.memGetInt(n) & 0xFFFFFF) == 0) MemoryUtil.memPutInt(n, normal);
                }
            }
        }

        public void vertex(long ptr, float x, float y, float z, int color, float u, float v, int light, int packedNormal) {
            MemoryUtil.memPutFloat(ptr + 0, x);
            MemoryUtil.memPutFloat(ptr + 4, y);
            MemoryUtil.memPutFloat(ptr + 8, z);

            MemoryUtil.memPutInt(ptr + 12, color);

            MemoryUtil.memPutFloat(ptr + 16, u);
            MemoryUtil.memPutFloat(ptr + 20, v);

            MemoryUtil.memPutShort(ptr + 24, (short) (light & '\uffff'));
            MemoryUtil.memPutShort(ptr + 26, (short) (light >> 16 & '\uffff'));

            MemoryUtil.memPutInt(ptr + 28, packedNormal);
        }

        @Override
        public void position(long ptr, float x, float y, float z) {
            MemoryUtil.memPutFloat(ptr + 0, x);
            MemoryUtil.memPutFloat(ptr + 4, y);
            MemoryUtil.memPutFloat(ptr + 8, z);
        }

        @Override
        public void color(long ptr, int color) {
            MemoryUtil.memPutInt(ptr + 12, color);
        }

        @Override
        public void uv(long ptr, float u, float v) {
            MemoryUtil.memPutFloat(ptr + 16, u);
            MemoryUtil.memPutFloat(ptr + 20, v);
        }

        @Override
        public void light(long ptr, int light) {
            MemoryUtil.memPutShort(ptr + 24, (short) (light & 0xFFFF));
            MemoryUtil.memPutShort(ptr + 26, (short) ((light >>> 16) & 0xFFFF));
        }

        @Override
        public void normal(long ptr, int normal) {
            MemoryUtil.memPutInt(ptr + 28, normal);
        }

        @Override
        public int getStride() {
            return VERTEX_SIZE;
        }
    }

    class CompressedVertexBuilder implements VertexBuilder {
        private static final int VERTEX_SIZE = 16;

        public static final float POS_CONV_MUL = 2048.0f;
        public static final float POS_OFFSET = -4.0f;
        public static final float POS_OFFSET_CONV = POS_OFFSET * POS_CONV_MUL;

        public static final float UV_CONV_MUL = 32768.0f;

        public void vertex(long ptr, float x, float y, float z, int color, float u, float v, int light, int packedNormal) {
            final short sX = (short) (x * POS_CONV_MUL + POS_OFFSET_CONV);
            final short sY = (short) (y * POS_CONV_MUL + POS_OFFSET_CONV);
            final short sZ = (short) (z * POS_CONV_MUL + POS_OFFSET_CONV);

            MemoryUtil.memPutShort(ptr + 0, sX);
            MemoryUtil.memPutShort(ptr + 2, sY);
            MemoryUtil.memPutShort(ptr + 4, sZ);

            final short l = (short) (((light >>> 8) & 0xFF00) | (light & 0xFF));
            MemoryUtil.memPutShort(ptr + 6, l);

            MemoryUtil.memPutShort(ptr + 8, (short) (u * UV_CONV_MUL));
            MemoryUtil.memPutShort(ptr + 10, (short) (v * UV_CONV_MUL));

            MemoryUtil.memPutInt(ptr + 12, color);
        }

        @Override
        public void position(long ptr, float x, float y, float z) {
            final short sX = (short) (x * POS_CONV_MUL + POS_OFFSET_CONV);
            final short sY = (short) (y * POS_CONV_MUL + POS_OFFSET_CONV);
            final short sZ = (short) (z * POS_CONV_MUL + POS_OFFSET_CONV);

            MemoryUtil.memPutShort(ptr + 0, sX);
            MemoryUtil.memPutShort(ptr + 2, sY);
            MemoryUtil.memPutShort(ptr + 4, sZ);
        }

        @Override
        public void color(long ptr, int color) {
            MemoryUtil.memPutInt(ptr + 12, color);
        }

        @Override
        public void uv(long ptr, float u, float v) {
            MemoryUtil.memPutShort(ptr + 8, (short) (u * UV_CONV_MUL));
            MemoryUtil.memPutShort(ptr + 10, (short) (v * UV_CONV_MUL));
        }

        @Override
        public void light(long ptr, int light) {
            final short l = (short) (((light >>> 8) & 0xFF00) | (light & 0xFF));
            MemoryUtil.memPutShort(ptr + 6, l);
        }

        @Override
        public void normal(long ptr, int normal) {

        }

        @Override
        public int getStride() {
            return VERTEX_SIZE;
        }
    }

}
