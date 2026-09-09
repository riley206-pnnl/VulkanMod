package net.vulkanmod;

import net.vulkanmod.shaders.ShaderPack;
import net.vulkanmod.shaders.ShaderPackConfig;
import net.vulkanmod.shaders.pack.FolderShaderPack;
import net.vulkanmod.shaders.pack.ShaderProperties;
import net.vulkanmod.shaders.transform.ProcessedShader;
import net.vulkanmod.shaders.transform.ShaderProcessor;
import net.vulkanmod.shaders.transform.ShaderUniform;
import net.vulkanmod.shaders.transform.Stage;
import net.vulkanmod.shaders.transform.StageSource;
import net.vulkanmod.vulkan.shader.SpirvCompiler;

import java.nio.file.Path;
import java.util.List;

/**
 * Offline smoke test for the pack loader + transpiler. Usage:
 *
 * <pre>./gradlew runTestShaderPack --args="/path/to/pack gbuffers_terrain"</pre>
 *
 * The default pack is the extracted Complementary Reimagined checkout and the
 * default program is gbuffers_terrain (overworld).
 */
public class TestShaderPack {
    private static boolean directTerrain;
    public static void main(String[] args) throws Exception {
        String packDir = args.length > 0 ? args[0] : "/tmp/opencode/cpl";
        String program = args.length > 1 ? args[1] : "gbuffers_terrain";
        String dimension = args.length > 2 ? args[2] : "world0";
        directTerrain = args.length > 3 && args[3].equals("direct");

        ShaderPack pack = java.nio.file.Files.isDirectory(Path.of(packDir))
                ? new FolderShaderPack(Path.of(packDir))
                : new net.vulkanmod.shaders.pack.ZipShaderPack(Path.of(packDir));

        System.out.println("=== PACK ===");
        System.out.println("name=" + pack.getName() + " hasShaders=" + pack.hasShaders());

        ShaderProperties props = ShaderProperties.load(pack);
        ShaderPackConfig config = ShaderPackConfig.load(pack);
        System.out.println("=== PASSES (" + dimension + ") ===");
        List<String> passes = ShaderProperties.passes(pack, dimension);
        System.out.println(passes);

        if (program.equals("ALL")) {
            System.out.println("=== SWEEP ===");
            int ok = 0, fail = 0;
            try (var files = java.nio.file.Files.list(Path.of(packDir, "shaders", "program"))) {
                List<String> names = files
                        .filter(p -> p.getFileName().toString().endsWith(".glsl"))
                        .map(p -> p.getFileName().toString().replaceFirst("\\.glsl$", ""))
                        .filter(n -> !n.startsWith("dh_")) // debug-helper programs, Iris-specific
                        .sorted().toList();
                for (String name : names) {
                    String line = ">>> " + name;
                    try {
                        runProgram(pack, config, name, dimension);
                        line += " OK";
                        ok++;
                    } catch (Throwable t) {
                        line += " FAIL " + summarize(t);
                        fail++;
                    }
                    System.out.println(line);
                }
            }
            System.out.println("=== SWEEP RESULT ok=" + ok + " fail=" + fail + " ===");
            if (fail > 0) System.exit(1);
            return;
        }

        runProgram(pack, config, program, dimension);
    }

    private static String summarize(Throwable t) {
        String m = t.getMessage();
        if (m == null) m = t.toString();
        m = m.replace("\n", " | ").replaceAll("\\s+", " ").trim();
        return m.length() > 300 ? m.substring(0, 300) + "..." : m;
    }

    private static void runProgram(ShaderPack pack, ShaderPackConfig config,
                                   String program, String dimension) throws Exception {
        if (!pack.exists("shaders/" + dimension + "/" + program + ".vsh")
                && !pack.exists("shaders/" + program + ".vsh")) {
            System.out.println("Program " + program + " not found; run with a valid name");
            return;
        }

        // Use the same metadata/profile-aware processor as runtime. The
        // previous smoke test omitted ShaderPackConfig, so alpha-test,
        // profile defines, and other shaders.properties-controlled rewrites
        // could pass the sweep while failing during actual pipeline creation.
        ShaderProcessor processor = new ShaderProcessor(config, directTerrain);

        ProcessedShader vs = processor.process(new StageSource(pack, program, dimension, Stage.VERTEX, null));
        ProcessedShader fs = processor.process(new StageSource(pack, program, dimension, Stage.FRAGMENT, null));

        java.nio.file.Files.createDirectories(Path.of("/tmp/opencode"));
        java.nio.file.Files.writeString(Path.of("/tmp/opencode/dump_" + program + ".vsh"), vs.glsl());
        java.nio.file.Files.writeString(Path.of("/tmp/opencode/dump_" + program + ".fsh"), fs.glsl());

        System.out.println("=== VERTEX TRANSFORMED (head) ===");
        String[] vLines = vs.glsl().split("\n");
        for (int i = 0; i < Math.min(vLines.length, 60); i++) System.out.println(vLines[i]);
        System.out.println("   ... (" + vLines.length + " lines total)");

        System.out.println("=== VERTEX UNIFORMS ===");
        for (ShaderUniform u : vs.uniforms()) {
            System.out.println(format(u));
        }
        System.out.println("vertex uboSize=" + vs.uboSize());

        System.out.println("=== FRAGMENT TRANSFORMED (head) ===");
        String[] fLines = fs.glsl().split("\n");
        for (int i = 0; i < Math.min(fLines.length, 60); i++) System.out.println(fLines[i]);
        System.out.println("   ... (" + fLines.length + " lines total)");

        System.out.println("=== FRAGMENT UNIFORMS ===");
        for (ShaderUniform u : fs.uniforms()) {
            System.out.println(format(u));
        }
        System.out.println("fragment uboSize=" + fs.uboSize());
        System.out.println("maxFragmentOutputs=" + fs.maxFragmentOutputs());

        if (directTerrain) {
            try (var uniforms = new net.vulkanmod.shaders.PackUniformBuffer(vs, fs)) {
                uniforms.matrix("gbufferProjectionInverse", new org.joml.Matrix4f());
                uniforms.ivec2("eyeBrightness", 16, 240);
            }
        }
        System.out.println("=== COMPILE (vertex) ===");
        System.out.println("vertex glsl length=" + vs.glsl().length());
        SpirvCompiler.addVirtualInclude("vm_test_" + program + ".vsh", vs.glsl());
        var vSpirv = SpirvCompiler.compileVirtualShader(program + ".vsh", "vm_test_" + program + ".vsh", SpirvCompiler.ShaderKind.VERTEX_SHADER);
        System.out.println("vertex SPIR-V OK (" + vSpirv.bytecode().remaining() + " bytes)");

        System.out.println("=== COMPILE (fragment) ===");
        SpirvCompiler.addVirtualInclude("vm_test_" + program + ".fsh", fs.glsl());
        var fSpirv = SpirvCompiler.compileVirtualShader(program + ".fsh", "vm_test_" + program + ".fsh", SpirvCompiler.ShaderKind.FRAGMENT_SHADER);
        System.out.println("fragment SPIR-V OK (" + fSpirv.bytecode().remaining() + " bytes)");

        System.out.println("=== REFLECT (vertex SPIR-V) ===");
        printReflected(program + ".vsh", vSpirv.bytecode());

        System.out.println("=== REFLECT (fragment SPIR-V) ===");
        printReflected(program + ".fsh", fSpirv.bytecode());
    }

    private static void printReflected(String name, java.nio.ByteBuffer spirv) throws net.vulkanmod.vulkan.shader.converter.ShaderCompileException {
        var refl = net.vulkanmod.vulkan.shader.converter.SpirvShader.createFromSpirv(name, spirv);
        System.out.println("UBOs:");
        for (var ub : refl.uniformBuffers()) {
            System.out.println("  " + ub.name() + " binding=" + ub.getBinding() + " size=" + ub.size());
        }
        System.out.println("Samplers:");
        for (var s : refl.samplers()) {
            System.out.println("  " + s.name() + " binding=" + s.getBinding() + " dim=" + s.dimensions());
        }
        System.out.println("Inputs (locations):");
        for (var in : refl.inputs()) {
            System.out.println("  loc=" + in.getLocation() + " " + in.name());
        }
        System.out.println("Outputs (locations):");
        for (var out : refl.outputs()) {
            System.out.println("  loc=" + out.getLocation() + " " + out.name());
        }
    }

    private static String format(ShaderUniform u) {
        return switch (u.kind()) {
            case SAMPLER -> String.format("sampler %-24s type=%-18s binding=%d", u.name(), u.glslType(), u.binding());
            case IMAGE -> String.format("image   %-24s type=%-18s binding=%d", u.name(), u.glslType(), u.binding());
            case VALUE -> String.format("value   %-24s type=%-18s offset=%-5d size=%d", u.name(), u.glslType(), u.uboOffset(), u.uboSize());
        };
    }
}
