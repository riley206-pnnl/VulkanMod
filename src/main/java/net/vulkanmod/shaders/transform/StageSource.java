package net.vulkanmod.shaders.transform;

import net.vulkanmod.shaders.ShaderPack;

/** Raw, resolved source for one stage of one pass of one dimension. */
public record StageSource(
        ShaderPack pack,
        String program,
        String dimension,
        Stage stage,
        String raw) {
}