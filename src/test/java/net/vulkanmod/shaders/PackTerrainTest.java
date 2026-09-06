package net.vulkanmod.shaders;

import net.vulkanmod.render.vertex.VertexBuilder;
import net.vulkanmod.shaders.transform.*;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.Test;
import org.lwjgl.system.MemoryUtil;

import java.util.List;

import static org.junit.Assert.*;

public class PackTerrainTest {
    private static ProcessedShader stage(Stage stage, int offset) {
        return new ProcessedShader(stage, "test", "world0", "", List.of(
                new ShaderUniform("eyeBrightness", "ivec2", UniformKind.VALUE, 0, 0, offset, 8, stage.name())),
                offset + 16, 0);
    }

    @Test
    public void uniformWritesFollowLayoutAndLeaveOtherMembersAlone() {
        // Moving the member catches regression to Complementary-specific offsets.
        try (var data = new PackUniformBuffer(stage(Stage.VERTEX, 32), stage(Stage.FRAGMENT, 32))) {
            data.ivec2("eyeBrightness", 16, 240);
            data.scalar("absentOptionalUniform", 1.0f);
            assertEquals(16, MemoryUtil.memGetInt(data.address() + 32));
            assertEquals(240, MemoryUtil.memGetInt(data.address() + 36));
            assertEquals(0, MemoryUtil.memGetInt(data.address()));
            assertEquals(0, MemoryUtil.memGetInt(data.address() + 40));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void differentStageLayoutsAreRejectedBeforeUpload() {
        try (var ignored = new PackUniformBuffer(stage(Stage.VERTEX, 0), stage(Stage.FRAGMENT, 16))) {}
    }

    @Test(expected = IllegalArgumentException.class)
    public void oversizedWritesCannotCorruptAdjacentUniforms() {
        try (var data = new PackUniformBuffer(stage(Stage.VERTEX, 0))) {
            data.matrix("eyeBrightness", new Matrix4f());
        }
    }

    @Test
    public void uncompressedAndCompressedPositionsAgreeAwayFromOrigin() {
        var bytes = MemoryUtil.memCalloc(48);
        try {
            long p = MemoryUtil.memAddress(bytes);
            var plain = new VertexBuilder.DefaultVertexBuilder();
            var compressed = new VertexBuilder.CompressedVertexBuilder();
            plain.position(p, 5.5f, 9.25f, 12.125f);
            compressed.position(p + 32, 5.5f, 9.25f, 12.125f);
            float[] area = {1024, 64, -2048};
            float[] camera = {1088.75f, 87.5f, -1999.25f};
            float[] section = {32, 16, 48};
            for (int i = 0; i < 3; i++) {
                float expected = MemoryUtil.memGetShort(p + 32 + i * 2) / 2048.0f
                        + area[i] + 4.0f - camera[i] + section[i];
                float actual = MemoryUtil.memGetFloat(p + i * 4) + area[i] - camera[i] + section[i];
                assertEquals(expected, actual, 0.00001f);
            }
        } finally { MemoryUtil.memFree(bytes); }
    }

    @Test
    public void allLightLevelsSurviveVertexEncodingAndPackNormalization() {
        var bytes = MemoryUtil.memCalloc(32);
        try {
            long p = MemoryUtil.memAddress(bytes);
            var writer = new VertexBuilder.DefaultVertexBuilder();
            for (int block = 0; block <= 15; block++) {
                for (int sky = 0; sky <= 15; sky++) {
                    writer.light(p, (block * 16) | (sky * 16 << 16));
                    float blockUv = (MemoryUtil.memGetShort(p + 24) + 8.0f) / 256.0f;
                    float skyUv = (MemoryUtil.memGetShort(p + 26) + 8.0f) / 256.0f;
                    assertEquals(block / 15.0f, (blockUv - 0.03125f) * (16.0f / 15.0f), 0.00001f);
                    assertEquals(sky / 15.0f, (skyUv - 0.03125f) * (16.0f / 15.0f), 0.00001f);
                }
            }
        } finally { MemoryUtil.memFree(bytes); }
    }

    @Test
    public void missingNormalsAreGeneratedForFlatAndSlopedQuads() {
        var bytes = MemoryUtil.memCalloc(128);
        try {
            long p = MemoryUtil.memAddress(bytes);
            var writer = new VertexBuilder.DefaultVertexBuilder();
            for (float slope : new float[]{0, 0.5f, 1}) {
                float[][] positions = {{0, 0, 0}, {0, 0, 1}, {1, slope, 1}, {1, slope, 0}};
                for (int i = 0; i < 4; i++) {
                    writer.vertex(p + i * 32, positions[i][0], positions[i][1], positions[i][2],
                            -1, 0, 0, 240 << 16, 0);
                }
                VertexBuilder.DefaultVertexBuilder.completeQuadNormals(p, 4);
                float length = (float) Math.sqrt(1 + slope * slope);
                for (int i = 0; i < 4; i++) {
                    int n = MemoryUtil.memGetInt(p + i * 32 + 28);
                    assertEquals(-slope / length, net.vulkanmod.render.vertex.format.I32_SNorm.unpackX(n), 0.01f);
                    assertEquals(1 / length, net.vulkanmod.render.vertex.format.I32_SNorm.unpackY(n), 0.01f);
                    assertEquals(0, net.vulkanmod.render.vertex.format.I32_SNorm.unpackZ(n), 0.01f);
                }
            }
        } finally { MemoryUtil.memFree(bytes); }
    }

    @Test
    public void explicitNormalsSurviveAndDegenerateQuadsGetFiniteFallback() {
        var bytes = MemoryUtil.memCalloc(128);
        try {
            long p = MemoryUtil.memAddress(bytes);
            MemoryUtil.memPutInt(p + 28, 127); // producer supplied +X
            VertexBuilder.DefaultVertexBuilder.completeQuadNormals(p, 4);
            assertEquals(127, MemoryUtil.memGetInt(p + 28));
            for (int i = 1; i < 4; i++) assertEquals(127 << 8, MemoryUtil.memGetInt(p + i * 32 + 28));
        } finally { MemoryUtil.memFree(bytes); }
    }

    @Test
    public void fluidQuadsReceiveNormalsBeforeUpload() {
        var builder = new net.vulkanmod.render.vertex.TerrainBufferBuilder(128, 32,
                new VertexBuilder.DefaultVertexBuilder());
        try {
            builder.addVertex(0, 0, 0);
            builder.addVertex(0, 0, 1);
            builder.addVertex(1, 0, 1);
            builder.addVertex(1, 0, 0);
            builder.end();
            for (int i = 0; i < 4; i++) {
                assertEquals(127 << 8, MemoryUtil.memGetInt(builder.getPtr() + i * 32 + 28));
            }
        } finally { builder.free(); }
    }

    @Test
    public void fluidVertexConsumerPreservesLightmapCoordinates() {
        var builder = new net.vulkanmod.render.vertex.TerrainBufferBuilder(128, 32,
                new VertexBuilder.DefaultVertexBuilder());
        try {
            builder.addVertex(0, 1, 2).setUv2(80, 240);
            assertEquals(80, MemoryUtil.memGetShort(builder.getPtr() + 24));
            assertEquals(240, MemoryUtil.memGetShort(builder.getPtr() + 26));
        } finally { builder.free(); }
    }

    @Test
    public void vulkanDepthReconstructsOriginalViewPositionForPack() {
        Matrix4f vulkan = new Matrix4f().setPerspective(1.2f, 16.0f / 9.0f, 0.05f, 1024.0f, true);
        Matrix4f packInverse = new Matrix4f().m22(2).m32(-1).mul(vulkan).invert();
        for (float z : new float[]{-0.1f, -1, -10, -128}) {
            Vector4f original = new Vector4f(0.01f, 0.02f, z, 1);
            Vector4f clip = vulkan.transform(new Vector4f(original));
            float screenX = (clip.x / clip.w + 1) / 2;
            float screenY = 1 - (clip.y / clip.w + 1) / 2; // top-origin framebuffer
            Vector4f reconstructed = packInverse.transform(new Vector4f(
                    screenX * 2 - 1, (1 - screenY) * 2 - 1, clip.z / clip.w * 2 - 1, 1));
            reconstructed.div(reconstructed.w);
            assertEquals(original.x, reconstructed.x, 0.002f);
            assertEquals(original.y, reconstructed.y, 0.002f);
            assertEquals(original.z, reconstructed.z, 0.03f);
        }
    }
}
