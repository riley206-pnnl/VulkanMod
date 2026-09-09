package net.vulkanmod.shaders.pack;

import net.vulkanmod.shaders.ShaderPack;
import net.vulkanmod.shaders.ShaderPackConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Parsed shaders.properties plus relevant dimension-specific overrides
 * (world0/world-1/world1) resolved from block.properties/dimension.properties.
 *
 * <p>This is a thin, lenient reader: unknown keys are preserved verbatim and
 * every lookup degrades gracefully to a default so a malformed or exotic pack
 * never crashes the loader.</p>
 */
public final class ShaderProperties {

    public record AlphaTest(String function, float reference) {}

    private final Map<String, String> base = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> dims = new LinkedHashMap<>();

    private ShaderProperties() {
    }

    /** Parse shaders.properties, block.properties and dimension.properties. */
    public static ShaderProperties load(ShaderPack pack) throws IOException {
        ShaderProperties props = new ShaderProperties();
        String base = pack.getSource("shaders/shaders.properties");
        props.parseInto(props.base, base);

        props.parseIntoDim(props.dims, "world0", pack.getSource("shaders/block.properties"));
        props.parseIntoDim(props.dims, "world-1", pack.getSource("shaders/dimension.properties"));
        props.parseIntoDim(props.dims, "world1", pack.getSource("shaders/dimension.properties"));
        return props;
    }

    private void parseInto(Map<String, String> target, String src) throws IOException {
        if (src == null) return;
        Properties p = new Properties();
        try (Reader r = new StringReader(src)) {
            p.load(r);
        }
        for (String name : p.stringPropertyNames()) {
            target.put(trim(name), trim(p.getProperty(name)));
        }
    }

    private void parseIntoDim(Map<String, Map<String, String>> target, String name, String src)
            throws IOException {
        if (src == null) return;
        Map<String, String> map = new LinkedHashMap<>();
        parseInto(map, src);
        target.put(name, map);
    }

    public String pumps() {
        return get("pumps", "all");
    }

    /**
     * Derive the ordered list of passes for a dimension from the set of shader
     * files that actually exist.  If a pack supplies a pass list, honour it;
     * otherwise retain the Iris/OptiFine canonical order.
     */
    public static List<String> passes(ShaderPack pack, String dim) throws IOException {
        return passes(pack, dim, null);
    }

    /** Return the resolved pass order, filtered by program enablement. */
    public static List<String> passes(ShaderPack pack, String dim, ShaderPackConfig config) throws IOException {
        List<String> out = new ArrayList<>();
        Set<String> have = discoveredPrograms(pack, dim);
        ShaderProperties metadata = load(pack);

        // This is the fallback order used by Iris when no explicit order is
        // present. Keep it centralized so metadata and fallback discovery
        // cannot silently disagree.
        List<String> canonical = List.of(
                "shadow", "shadowcomp",
                "prepare", "deferred", "deferred1", "deferred2",
                "gprepare",
                "composite", "composite1", "composite2", "composite3", "composite4",
                "composite5", "composite6", "composite7", "composite8", "composite9",
                "final",
                "final2");

        List<String> requested = metadata.explicitPassOrder(dim);
        if (requested.isEmpty()) {
            requested = canonical;
        } else {
            // A metadata list may intentionally mention only the passes it
            // wants to reorder. Append discovered canonical passes not listed
            // so a missing optional key cannot drop a real enabled program.
            LinkedHashSet<String> merged = new LinkedHashSet<>(requested);
            for (String name : canonical) merged.add(name);
            requested = new ArrayList<>(merged);
        }

        for (String n : requested) {
            boolean enabled = config == null
                    ? metadata.programEnabled(dim, n, true)
                    : config.programEnabled(dim, n);
            if (have.contains(n) && enabled) {
                out.add(n);
            }
        }
        return out;
    }

    /**
     * Read a pack-declared pass order.  The aliases cover the forms used by
     * common Iris/OptiFine packs and keep unknown metadata harmless.
     */
    private List<String> explicitPassOrder(String dim) {
        String raw = firstNonBlank(
                base.get("program." + dim + ".order"),
                base.get("program.order." + dim),
                base.get("pass.order." + dim),
                base.get("program.order"),
                base.get("pass.order"),
                dims.getOrDefault(dim, Map.of()).get("program.order"),
                dims.getOrDefault(dim, Map.of()).get("pass.order"));
        if (raw == null) return List.of();

        List<String> result = new ArrayList<>();
        for (String token : raw.split("[,;\\s]+")) {
            String name = token.trim();
            if (!name.isEmpty() && !name.equals("-")) result.add(name);
        }
        return result;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    /** Discover all vertex/fragment program stems for a dimension. */
    public static Set<String> discoveredPrograms(ShaderPack pack, String dim) {
        Set<String> have = new LinkedHashSet<>();
        try {
            for (String f : pack.listFiles("shaders/" + dim)) {
                if (f.endsWith(".vsh") || f.endsWith(".fsh")) {
                    String base = f.substring(0, f.length() - 4);
                    have.add(base.substring(base.lastIndexOf('/') + 1));
                }
            }
        } catch (RuntimeException ignored) {
            // A broken archive should be reported by the caller, not abort a
            // pack scan that can still provide fallback rendering.
        }
        return have;
    }

    /** Resolve an explicit program.<name>.enabled property if present. */
    public boolean programEnabled(String dimension, String program, boolean fallback) {
        String key = "program." + dimension + "/" + program + ".enabled";
        String value = dims.getOrDefault(dimension, Map.of()).get(key);
        if (value == null) {
            value = dims.getOrDefault(dimension, Map.of()).get("program." + program + ".enabled");
        }
        if (value == null) {
            value = base.get(key);
        }
        if (value == null) {
            value = base.get("program." + program + ".enabled");
        }
        if (value == null || value.isBlank()) return fallback;
        return !value.equalsIgnoreCase("false") && !value.equalsIgnoreCase("off")
                && !value.equals("0");
    }

    public String get(String key, String def) {
        String v = base.get(key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    /** Resolve a dimension override before the pack-wide value. */
    public String get(String dimension, String key, String def) {
        String v = dims.getOrDefault(dimension, Map.of()).get(key);
        if (v == null || v.isEmpty()) v = base.get(key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    /** Parse a shaders.properties profile line such as "profile.HIGH". */
    public Map<String, String> profile(String tier) {
        String raw = base.get("profile." + tier.toUpperCase(java.util.Locale.ROOT));
        if (raw == null || raw.isBlank()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (String token : raw.trim().split("\\s+")) {
            int equals = token.indexOf('=');
            if (equals > 0 && equals < token.length() - 1) {
                result.put(token.substring(0, equals).trim(), token.substring(equals + 1).trim());
            }
        }
        return result;
    }

    public Map<String, String> forDim(String dim) {
        Map<String, String> m = dims.get(dim);
        return m == null ? Map.of() : m;
    }

    /** Return custom texture declarations for one Iris program namespace. */
    public Map<String, String> customTextures(String program) {
        String prefix = "texture." + program + ".";
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : base.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.put(entry.getKey().substring(prefix.length()), entry.getValue());
            }
        }
        return result;
    }

    /** Resolve OptiFine/Iris alphaTest.<program>=FUNC reference metadata. */
    public AlphaTest alphaTest(String dimension, String program) {
        String raw = property(dimension, "alphaTest." + program);
        if (raw == null || raw.equalsIgnoreCase("off") || raw.equalsIgnoreCase("false")) return null;
        String[] parts = raw.trim().split("\\s+");
        if (parts.length < 2) return null;
        try {
            return new AlphaTest(parts[0].toUpperCase(java.util.Locale.ROOT), Float.parseFloat(parts[1]));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** True when the pack explicitly disables blending for one MRT target. */
    public boolean blendDisabled(String dimension, String program, int target) {
        String value = property(dimension, "blend." + program + ".colortex" + target);
        if (value == null) value = property(dimension, "blend." + program);
        return value != null && (value.equalsIgnoreCase("off")
                || value.equalsIgnoreCase("false") || value.equals("0"));
    }

    private String property(String dimension, String key) {
        String value = dims.getOrDefault(dimension, Map.of()).get(key);
        if (value == null || value.isBlank()) value = base.get(key);
        return value == null ? null : value.trim();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
