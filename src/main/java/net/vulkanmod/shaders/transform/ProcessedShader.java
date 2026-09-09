package net.vulkanmod.shaders.transform;

import java.util.List;

/** The fully transformed GLSL for one stage plus its discovered resource layout. */
public record ProcessedShader(
        Stage stage,
        String program,
        String dimension,
        String glsl,
        List<ShaderUniform> uniforms,
        int uboSize,
        int maxFragmentOutputs,
        int[] drawBuffers) {

    public ProcessedShader(Stage stage, String program, String dimension, String glsl,
                           List<ShaderUniform> uniforms, int uboSize, int maxFragmentOutputs) {
        this(stage, program, dimension, glsl, uniforms, uboSize, maxFragmentOutputs, new int[]{0});
    }

    public List<ShaderUniform> samplers() {
        return uniforms.stream().filter(ShaderUniform::isSampler).toList();
    }

    public List<ShaderUniform> resources() {
        return uniforms.stream().filter(u -> u.isSampler() || u.isImage()).toList();
    }
}
