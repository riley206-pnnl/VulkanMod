package net.vulkanmod;

import net.vulkanmod.vulkan.shader.SpirvCompiler;
import net.vulkanmod.vulkan.shader.converter.SpirvPipeline;
import net.vulkanmod.vulkan.shader.converter.SpirvShader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class TestShader {
    public static void main(String[] args) throws Exception {
        String vshSrc = Files.readString(Path.of("/home/riley/.gemini/antigravity-cli/brain/1b3ca29c-b50e-4548-a429-56ab6190f33d/scratch/entity.vsh"));
        String fshSrc = Files.readString(Path.of("/home/riley/.gemini/antigravity-cli/brain/1b3ca29c-b50e-4548-a429-56ab6190f33d/scratch/entity.fsh"));

        vshSrc = vshSrc.substring(0, 14) + vshSrc.substring(14).replaceAll("(?m)^#version.*$", "");
        fshSrc = fshSrc.substring(0, 14) + fshSrc.substring(14).replaceAll("(?m)^#version.*$", "");

        var vSpirv = SpirvCompiler.compileShader("core/entity.vsh", vshSrc, SpirvCompiler.ShaderKind.VERTEX_SHADER);
        var vsSpirv = SpirvShader.createFromSpirv("core/entity.vsh", vSpirv.bytecode());

        var fSpirv = SpirvCompiler.compileShader("core/entity.fsh", fshSrc, SpirvCompiler.ShaderKind.FRAGMENT_SHADER);
        var fsSpirv = SpirvShader.createFromSpirv("core/entity.fsh", fSpirv.bytecode());

        System.out.println("=== VERTEX INPUTS BEFORE ===");
        for (var in : vsSpirv.inputs()) {
            System.out.println(in.name() + " -> location " + in.getLocation());
        }

        SpirvPipeline pipeline = new SpirvPipeline(vsSpirv, fsSpirv);
        pipeline.updateLocations(List.of("Position", "Color", "UV0", "UV1", "UV2", "Normal"));

        System.out.println("=== VERTEX INPUTS AFTER ===");
        for (var in : vsSpirv.inputs()) {
            System.out.println(in.name() + " -> location " + in.getLocation());
        }

        System.out.println("=== VERTEX OUTPUTS ===");
        for (var out : vsSpirv.outputs()) {
            System.out.println(out.name() + " -> location " + out.getLocation());
        }
        System.out.println("=== FRAGMENT INPUTS ===");
        for (var in : fsSpirv.inputs()) {
            System.out.println(in.name() + " -> location " + in.getLocation());
        }

        var samplers = pipeline.getSamplerList();
        System.out.println("=== SAMPLERS ===");
        for (var s : samplers) {
            System.out.println(s.toString());
        }

        var ubos = pipeline.createUBOs();
        System.out.println("=== UBOS ===");
        for (var u : ubos) {
            System.out.println(u.toString() + " size: " + u.getSize());
        }
    }
}
