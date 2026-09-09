package net.vulkanmod.shaders;

import net.vulkanmod.shaders.pack.ShaderProperties;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discovers the render programs exposed by a pack and gives the renderer a
 * stable semantic category for each one.  Keeping this separate from Vulkan
 * pipeline construction lets the renderer decide when and where a program is
 * used without duplicating pack-name string checks in mixins.
 */
public final class PackProgramRegistry {
    public enum Kind { GBUFFER, SHADOW, COMPUTE, POST, FINAL }

    public record Program(String name, Kind kind, boolean vertex, boolean fragment,
                          boolean enabled) {}

    private static final List<String> GBUFFER_NAMES = List.of(
            "gbuffers_terrain", "gbuffers_block", "gbuffers_textured", "gbuffers_basic",
            "gbuffers_entities", "gbuffers_entities_translucent", "gbuffers_entities_glowing",
            "gbuffers_hand", "gbuffers_skybasic", "gbuffers_skytextured", "gbuffers_clouds",
            "gbuffers_weather", "gbuffers_damagedblock", "gbuffers_beaconbeam",
            "gbuffers_lightning", "gbuffers_spidereyes", "gbuffers_armor_glint",
            "gbuffers_line", "gbuffers_water", "gbuffers_block_translucent");

    private final Map<Kind, List<Program>> programs = new EnumMap<>(Kind.class);

    private PackProgramRegistry() {}

    public static PackProgramRegistry discover(ShaderPack pack, ShaderPackConfig config,
                                               String dimension) throws IOException {
        PackProgramRegistry registry = new PackProgramRegistry();
        for (Kind kind : Kind.values()) registry.programs.put(kind, new ArrayList<>());

        Set<String> discovered = new LinkedHashSet<>(ShaderProperties.discoveredPrograms(pack, dimension));
        for (String name : GBUFFER_NAMES) {
            if (discovered.contains(name)) registry.add(pack, config, dimension, name, Kind.GBUFFER);
        }
        for (String name : List.of("shadow", "shadowcomp")) {
            if (discovered.contains(name)
                    || pack.exists("shaders/" + dimension + "/" + name + ".vsh")
                    || pack.exists("shaders/" + dimension + "/" + name + ".csh")
                    || pack.exists("shaders/" + name + ".vsh")
                    || pack.exists("shaders/" + name + ".csh")) {
                registry.add(pack, config, dimension, name,
                        name.equals("shadowcomp") ? Kind.COMPUTE : Kind.SHADOW);
            }
        }
        for (String name : config.orderedPasses(pack, dimension)) {
            if (name.equals("shadow") || name.equals("shadowcomp") || name.equals("final")) continue;
            registry.add(pack, config, dimension, name, Kind.POST);
        }
        if (config.programEnabled(dimension, "final")
                && (pack.exists("shaders/" + dimension + "/final.vsh")
                || pack.exists("shaders/final.vsh"))) {
            registry.add(pack, config, dimension, "final", Kind.FINAL);
        }
        return registry;
    }

    private void add(ShaderPack pack, ShaderPackConfig config, String dimension,
                     String name, Kind kind) {
        boolean vertex = pack.exists("shaders/" + dimension + "/" + name + ".vsh")
                || pack.exists("shaders/" + name + ".vsh");
        boolean fragment = pack.exists("shaders/" + dimension + "/" + name + ".fsh")
                || pack.exists("shaders/" + name + ".fsh");
        boolean compute = pack.exists("shaders/" + dimension + "/" + name + ".csh")
                || pack.exists("shaders/" + name + ".csh");
        programs.get(kind).add(new Program(name, kind, vertex || compute, fragment,
                config.programEnabled(dimension, name)));
    }

    public List<Program> programs(Kind kind) {
        return List.copyOf(programs.getOrDefault(kind, List.of()));
    }

    public List<Program> gBuffers() { return programs(Kind.GBUFFER); }
    public List<Program> shadows() { return programs(Kind.SHADOW); }
    public List<Program> compute() { return programs(Kind.COMPUTE); }
    public List<Program> post() { return programs(Kind.POST); }
    public Program finalProgram() {
        return programs(Kind.FINAL).stream().findFirst().orElse(null);
    }
}
