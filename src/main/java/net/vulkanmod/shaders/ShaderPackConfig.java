package net.vulkanmod.shaders;

import net.vulkanmod.shaders.pack.ShaderProperties;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Resolved host configuration for one shader pack and dimension. */
public final class ShaderPackConfig {
    private final ShaderProperties properties;
    private final ShaderFeatureProfile profile;

    private ShaderPackConfig(ShaderProperties properties, ShaderFeatureProfile profile) {
        this.properties = properties;
        this.profile = profile;
    }

    public static ShaderPackConfig load(ShaderPack pack) throws IOException {
        ShaderProperties properties = ShaderProperties.load(pack);
        ShaderFeatureProfile hostProfile = ShaderFeatureProfile.fromSystemProperty();
        return new ShaderPackConfig(properties,
                hostProfile.withOverrides(properties.profile(hostProfile.tier().name())));
    }

    public ShaderProperties properties() {
        return properties;
    }

    public ShaderFeatureProfile profile() {
        return profile;
    }

    public String define(String name, String fallback) {
        // An explicit pack-wide value (for example shadowDistance) must win
        // over the host tier default.  Profile values remain the fallback
        // when the pack does not declare that key.
        return properties.get(name, profile.get(name, fallback));
    }

    /** Resolve a feature value with the active dimension override first. */
    public String define(String dimension, String name, String fallback) {
        String resolved = properties.get(name, profile.get(name, fallback));
        return properties.get(dimension, name, resolved);
    }

    public double shadowDistance() {
        try {
            return Double.parseDouble(define("shadowDistance", "192.0"));
        } catch (NumberFormatException e) {
            return 192.0;
        }
    }

    public double shadowDistance(String dimension) {
        try {
            return Double.parseDouble(define(dimension, "shadowDistance", "192.0"));
        } catch (NumberFormatException e) {
            return 192.0;
        }
    }

    public int shadowResolution() {
        try {
            return Integer.parseInt(profile.get("shadowResolution", "1024"));
        } catch (NumberFormatException e) {
            return 1024;
        }
    }

    public int shadowResolution(String dimension) {
        try {
            return Integer.parseInt(define(dimension, "shadowResolution", "1024"));
        } catch (NumberFormatException e) {
            return 1024;
        }
    }

    public boolean shadowsEnabled() {
        return !"-1".equals(define("SHADOW_QUALITY", "2"));
    }

    public boolean programEnabled(String dimension, String program) {
        return properties.programEnabled(dimension, program, true);
    }

    public ShaderProperties.AlphaTest alphaTest(String dimension, String program) {
        return properties.alphaTest(dimension, program);
    }

    public boolean blendDisabled(String dimension, String program, int target) {
        return properties.blendDisabled(dimension, program, target);
    }

    public List<String> orderedPasses(ShaderPack pack, String dimension) throws IOException {
        return properties.passes(pack, dimension, this);
    }

    public Map<String, String> profileValues() {
        return profile.values();
    }
}
