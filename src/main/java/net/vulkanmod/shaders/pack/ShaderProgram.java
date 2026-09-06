package net.vulkanmod.shaders.pack;

import java.util.List;

/**
 * A single shader pass belonging to a dimension world. Passes are ordered; a
 * pass with {@code target} null writes to the swapchain (the final screen).
 *
 * <p>Field names mirror OptiFine shaders.properties while remaining lenient:
 * unresolved data defaults harmlessly.</p>
 */
public record ShaderProgram(
        String name,
        String dimension,
        List<String> vsh,
        List<String> fsh,
        int width,
        int height,
        List<String> target,
        List<String> drawbuffers,
        String format,
        List<String> textures,
        boolean renderBeforeShadow,
        boolean active) {

    public static ShaderProgram disabled(String name, String dimension) {
        return new ShaderProgram(name, dimension, List.of(), List.of(), 0, 0,
                List.of(), List.of(), "", List.of(), false, false);
    }
}