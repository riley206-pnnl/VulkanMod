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
        int maxFragmentOutputs) {

    public List<ShaderUniform> samplers() {
        return uniforms.stream().filter(ShaderUniform::isSampler).toList();
    }
}