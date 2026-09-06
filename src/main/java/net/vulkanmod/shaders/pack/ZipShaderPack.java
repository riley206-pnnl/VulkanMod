package net.vulkanmod.shaders.pack;

import net.vulkanmod.shaders.ShaderPack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static net.vulkanmod.Initializer.LOGGER;

public class ZipShaderPack implements ShaderPack {

    private final Path path;
    private final ZipFile zip;

    public ZipShaderPack(Path path) throws IOException {
        this.path = path;
        this.zip = new ZipFile(path.toFile());
    }

    @Override
    public String getName() {
        String name = path.getFileName().toString();
        if (name.endsWith(".zip") || name.endsWith(".jar"))
            name = name.substring(0, name.length() - 4);
        return name;
    }

    @Override
    public boolean exists(String path) {
        return zip.getEntry(normalize(path)) != null;
    }

    @Override
    public String getSource(String path) throws IOException {
        ZipEntry entry = zip.getEntry(normalize(path));
        if (entry == null || entry.isDirectory()) return null;
        try (var in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Override
    public List<String> listFiles(String dir) {
        String prefix = normalize(dir);
        List<String> result = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;
            String name = entry.getName().replace('\\', '/');
            if (name.startsWith(prefix)) result.add(name);
        }
        return result;
    }

    @Override
    public boolean hasShaders() {
        return exists("shaders/shaders.properties");
    }

    @Override
    public Path getFolder() {
        return null;
    }

    @Override
    public boolean isFolderPack() {
        return false;
    }

    @Override
    public void close() {
        try {
            zip.close();
        } catch (IOException e) {
            LOGGER.warn("Failed to close shader pack {}: {}", path, e.toString());
        }
    }

    private static String normalize(String path) {
        return path.replace('\\', '/');
    }
}