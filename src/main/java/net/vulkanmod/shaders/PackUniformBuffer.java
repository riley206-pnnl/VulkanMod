package net.vulkanmod.shaders;

import net.vulkanmod.shaders.transform.ProcessedShader;
import net.vulkanmod.shaders.transform.ShaderUniform;
import net.vulkanmod.shaders.transform.UniformKind;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/** CPU storage indexed by the actual std140 layout, not pack-specific offsets. */
public final class PackUniformBuffer implements AutoCloseable {
    private final Map<String, ShaderUniform> layout = new HashMap<>();
    private final ByteBuffer data;

    public PackUniformBuffer(ProcessedShader... stages) {
        int size = 16;
        for (var stage : stages) {
            size = Math.max(size, stage.uboSize());
            for (var uniform : stage.uniforms()) {
                if (uniform.kind() != UniformKind.VALUE) continue;
                var previous = layout.putIfAbsent(uniform.name(), uniform);
                if (previous != null && (previous.uboOffset() != uniform.uboOffset()
                        || !previous.glslType().equals(uniform.glslType()))) {
                    throw new IllegalArgumentException("Pack uniform differs between stages: " + uniform.name());
                }
            }
        }
        data = MemoryUtil.memCalloc(size);
    }

    public long address() { return MemoryUtil.memAddress(data); }
    public int size() { return data.capacity(); }

    private long address(String name, int bytes) {
        var uniform = layout.get(name);
        if (uniform == null) return 0L;
        if (bytes > uniform.uboSize() || uniform.uboOffset() + bytes > size()) {
            throw new IllegalArgumentException("Pack uniform is too small: " + name);
        }
        return address() + uniform.uboOffset();
    }

    public void integer(String name, int value) {
        long p = address(name, 4);
        if (p != 0) MemoryUtil.memPutInt(p, value);
    }

    public void scalar(String name, float value) {
        long p = address(name, 4);
        if (p != 0) MemoryUtil.memPutFloat(p, value);
    }

    public void ivec2(String name, int x, int y) {
        long p = address(name, 8);
        if (p != 0) {
            MemoryUtil.memPutInt(p, x);
            MemoryUtil.memPutInt(p + 4, y);
        }
    }

    public void ivec3(String name, int x, int y, int z) {
        long p = address(name, 12);
        if (p != 0) {
            MemoryUtil.memPutInt(p, x);
            MemoryUtil.memPutInt(p + 4, y);
            MemoryUtil.memPutInt(p + 8, z);
        }
    }

    public void vec3(String name, float x, float y, float z) {
        long p = address(name, 12);
        if (p != 0) {
            MemoryUtil.memPutFloat(p, x);
            MemoryUtil.memPutFloat(p + 4, y);
            MemoryUtil.memPutFloat(p + 8, z);
        }
    }

    public void vec4(String name, float x, float y, float z, float w) {
        long p = address(name, 16);
        if (p != 0) {
            vec3(name, x, y, z);
            MemoryUtil.memPutFloat(p + 12, w);
        }
    }

    public void matrix(String name, Matrix4f value) {
        long p = address(name, 64);
        if (p != 0) value.get(MemoryUtil.memByteBuffer(p, 64));
    }

    @Override
    public void close() { MemoryUtil.memFree(data); }
}
