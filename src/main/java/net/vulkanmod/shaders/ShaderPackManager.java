package net.vulkanmod.shaders;

import net.vulkanmod.shaders.pack.FolderShaderPack;
import net.vulkanmod.shaders.pack.ZipShaderPack;

import static net.vulkanmod.Initializer.CONFIG;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static net.vulkanmod.Initializer.LOGGER;

/**
 * Discovers OptiFine/Iris style shader packs in &lt;gameDir&gt;/shaderpacks and owns the
 * currently active pack. A pack is valid if it exposes shaders/shaders.properties,
 * either as a directory or an uncompressed .zip.
 */
public final class ShaderPackManager {

    public static final String NONE = "";

    private static ShaderPackManager instance;

    private final Path shaderpacksDir;
    private final List<ShaderPack> available = new ArrayList<>();
    private ShaderPack activePack;

    public static ShaderPackManager get() {
        return instance;
    }

    /** Create or refresh the manager for the given game directory. */
    public static void init(Path gameDir) {
        if (instance != null) instance.close();
        instance = new ShaderPackManager(gameDir.resolve("shaderpacks"));
        instance.scan();
        instance.applyConfig();
        LOGGER.info("ShaderPackManager: {} pack(s) available", instance.available.size());
    }

    private ShaderPackManager(Path shaderpacksDir) {
        this.shaderpacksDir = shaderpacksDir;
    }

    public void scan() {
        available.clear();
        if (!Files.isDirectory(shaderpacksDir)) {
            try {
                Files.createDirectories(shaderpacksDir);
            } catch (IOException e) {
                LOGGER.warn("Could not create shaderpacks dir {}: {}", shaderpacksDir, e.toString());
            }
            return;
        }

        try (Stream<Path> stream = Files.list(shaderpacksDir)) {
            stream.forEach(p -> {
                try {
                    if (Files.isDirectory(p)) {
                        FolderShaderPack pack = new FolderShaderPack(p);
                        if (pack.hasShaders()) available.add(pack);
                    } else if (isArchive(p)) {
                        try {
                            ZipShaderPack pack = new ZipShaderPack(p);
                            if (pack.hasShaders()) {
                                available.add(pack);
                            } else {
                                pack.close();
                            }
                        } catch (IOException e) {
                            LOGGER.warn("Failed to open shader pack {}: {}", p, e.toString());
                        }
                    }
                } catch (RuntimeException e) {
                    LOGGER.warn("Skipping shaderpack entry {}: {}", p, e.toString());
                }
            });
        } catch (IOException e) {
            LOGGER.warn("Failed to scan shaderpacks dir {}: {}", shaderpacksDir, e.toString());
        }
        available.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
    }

    private static boolean isArchive(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.endsWith(".zip") || n.endsWith(".jar");
    }

    public void applyConfig() {
        if (CONFIG != null && !CONFIG.isShaderPackEnabled()) {
            setActive(null);
            return;
        }
        select(CONFIG == null ? NONE : CONFIG.getShaderPackName());
    }

    public void select(String name) {
        if (name == null || name.isEmpty()) {
            setActive(null);
            return;
        }
        for (ShaderPack pack : available) {
            if (pack.getName().equals(name)) {
                setActive(pack);
                return;
            }
        }
        LOGGER.warn("Shader pack '{}' not found", name);
        setActive(null);
    }

    private void setActive(ShaderPack pack) {
        if (activePack != null && activePack != pack) {
            try {
                activePack.close();
            } catch (Exception e) {
                LOGGER.warn("Failed to close shader pack: {}", e.toString());
            }
        }
        activePack = pack;
        String name = pack == null ? NONE : pack.getName();
        if (CONFIG != null && !name.equals(CONFIG.getShaderPackName())) {
            CONFIG.setShaderPack(name);
            CONFIG.write();
            LOGGER.info("Active shader pack: '{}'", name);
        }
    }

    public List<ShaderPack> getAvailable() {
        return Collections.unmodifiableList(available);
    }

    public ShaderPack getActivePack() {
        return activePack;
    }

    public boolean hasActivePack() {
        return activePack != null;
    }

    private void close() {
        if (activePack != null) {
            try {
                activePack.close();
            } catch (Exception ignored) {
            }
            activePack = null;
        }
        available.clear();
    }
}
