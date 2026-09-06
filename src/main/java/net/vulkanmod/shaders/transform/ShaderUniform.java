package net.vulkanmod.shaders.transform;

/** A uniform extracted from pack GLSL. Samplers/images keep their own binding; values join the pack UBO. */
public record ShaderUniform(
        String name,
        String glslType,
        UniformKind kind,
        int set,
        int binding,
        int uboOffset,
        int uboSize,
        String includes) {

    public boolean isSampler() {
        return kind == UniformKind.SAMPLER;
    }

    public boolean hasUboLayout() {
        return kind == UniformKind.VALUE;
    }
}