package net.vulkanmod.shaders.pack;

import net.vulkanmod.shaders.ShaderPack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static net.vulkanmod.Initializer.LOGGER;

public class FolderShaderPack implements ShaderPack {

    private final Path root;

    public FolderShaderPack(Path root) {
        this.root = root;
    }

    @Override
    public String getName() {
        return root.getFileName().toString();
    }

    @Override
    public boolean exists(String path) {
        return Files.isRegularFile(root.resolve(path));
    }

    @Override
    public String getSource(String path) throws IOException {
        Path file = root.resolve(path);
        if (!Files.isRegularFile(file)) return null;
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Override
    public List<String> listFiles(String dir) {
        List<String> result = new ArrayList<>();
        Path base = root.resolve(dir);
        if (!Files.isDirectory(base)) return result;
        try (Stream<Path> stream = Files.walk(base)) {
            stream.filter(Files::isRegularFile)
                    .forEach(p -> result.add(root.relativize(p).toString().replace('\\', '/')));
        } catch (IOException e) {
            LOGGER.warn("Failed to list shader pack directory {}: {}", base, e.toString());
        }
        return result;
    }

    @Override
    public boolean hasShaders() {
        return exists("shaders/shaders.properties");
    }

    @Override
    public Path getFolder() {
        return root;
    }

    @Override
    public boolean isFolderPack() {
        return true;
    }

    @Override
    public void close() {
    }
}