package net.vulkanmod.shaders;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Runtime feature values exposed to an Iris/OptiFine style shader pack.
 *
 * <p>The pack remains the source of truth for shader code and program
 * enablement; this profile only supplies the host-side defaults which Iris
 * normally gets from its options screen.  Keeping the values in one object
 * prevents individual shader stages from silently receiving different
 * feature defines.</p>
 */
public final class ShaderFeatureProfile {
    public enum Tier { LOW, MEDIUM, HIGH, ULTRA }

    private final Tier tier;
    private final Map<String, String> values;

    private ShaderFeatureProfile(Tier tier, Map<String, String> values) {
        this.tier = tier;
        this.values = Map.copyOf(values);
    }

    public static ShaderFeatureProfile fromSystemProperty() {
        String raw = System.getProperty("vulkanmod.shaderProfile", "HIGH");
        Tier tier;
        try {
            tier = Tier.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            tier = Tier.HIGH;
        }
        return defaults(tier);
    }

    public static ShaderFeatureProfile defaults(Tier tier) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("SHADOW_QUALITY", switch (tier) {
            case LOW -> "-1";
            case MEDIUM -> "1";
            case HIGH -> "2";
            case ULTRA -> "3";
        });
        v.put("shadowDistance", switch (tier) {
            case LOW -> "64.0";
            case MEDIUM -> "128.0";
            case HIGH -> "192.0";
            case ULTRA -> "256.0";
        });
        v.put("shadowResolution", switch (tier) {
            case LOW -> "512";
            case MEDIUM -> "1024";
            case HIGH -> "2048";
            case ULTRA -> "4096";
        });
        v.put("TAA_MODE", "0");
        v.put("RP_MODE", "1");
        v.put("BLOCK_REFLECT_QUALITY", tier == Tier.LOW ? "0" : "1");
        v.put("LIGHTSHAFT_QUALI_DEFINE", tier == Tier.LOW ? "0" : tier == Tier.MEDIUM ? "1" : "2");
        v.put("LIGHTSHAFT_BEHAVIOUR", tier == Tier.LOW ? "0" : "1");
        v.put("RAIN_PUDDLES", tier == Tier.ULTRA ? "1" : "0");
        v.put("ANISOTROPIC_FILTER", tier == Tier.ULTRA ? "8" : "0");
        // Colored voxel lighting and world-space reflection require additional
        // image/SSBO infrastructure; keep them opt-in until that backend is
        // available, while leaving the profile extension point intact.
        v.put("COLORED_LIGHTING", "0");
        v.put("WORLD_SPACE_REFLECTIONS", tier == Tier.ULTRA ? "1" : "-1");
        v.put("ENTITY_SHADOW", tier == Tier.LOW ? "-1" : "1");
        v.put("FXAA_DEFINE", "1");
        // Complementary's declared High profile uses detail level 2;
        // Ultra is the tier that enables the extra auxiliary MRTs.
        v.put("DETAIL_QUALITY", tier == Tier.LOW ? "0" : tier == Tier.MEDIUM ? "2" : tier == Tier.HIGH ? "2" : "3");
        v.put("CLOUD_QUALITY", tier == Tier.LOW ? "1" : tier == Tier.MEDIUM ? "2" : "3");
        return new ShaderFeatureProfile(tier, v);
    }

    public Tier tier() {
        return tier;
    }

    public String get(String name, String fallback) {
        return values.getOrDefault(name, fallback);
    }

    public Map<String, String> values() {
        return values;
    }

    /** Return this tier with pack-declared profile values applied. */
    public ShaderFeatureProfile withOverrides(Map<String, String> overrides) {
        if (overrides == null || overrides.isEmpty()) return this;
        Map<String, String> merged = new LinkedHashMap<>(values);
        merged.putAll(overrides);
        return new ShaderFeatureProfile(tier, merged);
    }

    public int shadowResolution() {
        return Integer.parseInt(values.getOrDefault("shadowResolution", "1024"));
    }
}
