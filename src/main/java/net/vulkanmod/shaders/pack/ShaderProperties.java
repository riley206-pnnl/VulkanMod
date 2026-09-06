package net.vulkanmod.shaders.pack;

import net.vulkanmod.shaders.ShaderPack;

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
     * files that actually exist (world0/world-1/world1/<pass>.vsh plus the
     * dimensionless shadow/deferred/composite/final set). Packs that declare an
     * explicit order via shaders.properties are not yet handled.
     */
    public static List<String> passes(ShaderPack pack, String dim) throws IOException {
        List<String> out = new ArrayList<>();
        Set<String> have = new LinkedHashSet<>();
        try {
            List<String> files = pack.listFiles("shaders/" + dim);
            for (String f : files) {
                if (f.endsWith(".vsh")) {
                    String base = f.substring(0, f.length() - 4);
                    base = base.substring(base.lastIndexOf('/') + 1);
                    have.add(base);
                }
            }
        } catch (RuntimeException e) {
            // fall through with empty set
        }

        // Composite chain, in canonical order, a pass is valid if both shaders exist.
        String[] names = {
                "shadow", "shadowcomp",
                "prepare", "deferred", "deferred1", "deferred2",
                "gprepare",
                "composite", "composite1", "composite2", "composite3", "composite4",
                "composite5", "composite6", "composite7", "composite8", "composite9",
                "final",
                "final2"
        };
        for (String n : names) {
            if (have.contains(n)) out.add(n);
        }
        return out;
    }

    public String get(String key, String def) {
        String v = base.get(key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    public Map<String, String> forDim(String dim) {
        Map<String, String> m = dims.get(dim);
        return m == null ? Map.of() : m;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}