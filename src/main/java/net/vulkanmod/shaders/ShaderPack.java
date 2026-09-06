package net.vulkanmod.shaders;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * A loaded OptiFine/Iris style shader pack. Paths are relative to the pack root,
 * e.g. "shaders/shaders.properties" or "shaders/program/gbuffers_terrain.glsl".
 */
public interface ShaderPack extends Closeable {

    String getName();

    /** True if the pack exposes a path that can be read via {@link #getSource}. */
    boolean exists(String path);

    /** Read a UTF-8 text resource. Returns null if absent. */
    String getSource(String path) throws IOException;

    /**
     * All files under the given directory, relative to pack root, forward slashes.
     * Only regular files; directories excluded.
     */
    List<String> listFiles(String dir);

    /** True when the pack contains a shaders/ tree with a shaders.properties. */
    boolean hasShaders();

    /** The directory this pack lives in (returns null for non-folder packs). */
    Path getFolder();

    boolean isFolderPack();
}