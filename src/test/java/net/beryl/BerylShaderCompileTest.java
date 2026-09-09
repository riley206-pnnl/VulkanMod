package net.beryl;

import net.vulkanmod.vulkan.shader.SpirvCompiler;
import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Compile native Beryl assets with the same shaderc compiler used by the client. */
public class BerylShaderCompileTest {
    @Test
    public void compileAllShaderVariants() throws Exception {
        Path root = Path.of("src/beryl/resources/assets/beryl/shaders");
        // Register includes without bootstrapping Minecraft or a Vulkan device.
        for (Path includeRoot : List.of(Path.of("src/main/resources/assets/vulkanmod/shaders/include"), root)) {
            try (var paths = Files.walk(includeRoot)) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".glsl")).toList()) {
                    String name = includeRoot.relativize(path).toString();
                    SpirvCompiler.addVirtualInclude(name, Files.readString(path));
                    if (name.startsWith("include/")) {
                        SpirvCompiler.addVirtualInclude(path.getFileName().toString(), Files.readString(path));
                    }
                }
            }
        }
        int count = 0;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String name = path.toString();
                var kind = name.endsWith(".vsh") ? SpirvCompiler.ShaderKind.VERTEX_SHADER
                    : name.endsWith(".fsh") ? SpirvCompiler.ShaderKind.FRAGMENT_SHADER
                    : name.endsWith(".comp") ? SpirvCompiler.ShaderKind.COMPUTE_SHADER : null;
                if (kind == null) continue;
                String source = Files.readString(path);
                for (int mask = 0; mask < 16; mask++) {
                    String defines = ((mask & 1) != 0 ? "#define SSR\n" : "")
                        + ((mask & 2) != 0 ? "#define WATER_WAVING\n" : "")
                        + ((mask & 4) != 0 ? "#define COLORED_SHADOWS\n" : "")
                        + ((mask & 8) != 0 ? "#define TEXTURED_SUN\n" : "");
                    int newline = source.indexOf('\n') + 1;
                    try (var spirv = SpirvCompiler.compileShader(name + " variant " + mask,
                            source.substring(0, newline) + defines + source.substring(newline), kind)) {
                        org.junit.Assert.assertTrue(spirv.bytecode().remaining() > 0);
                    }
                    count++;
                }
            }
        }
        System.out.println("Compiled " + count + " native Beryl shader variants");
    }
}
