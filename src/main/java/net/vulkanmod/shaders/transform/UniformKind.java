package net.vulkanmod.shaders.transform;

/** Kind of a discovered uniform: a texture/buffer sampler or a loose value folded into the pack UBO. */
public enum UniformKind {
    SAMPLER,
    IMAGE,
    VALUE
}