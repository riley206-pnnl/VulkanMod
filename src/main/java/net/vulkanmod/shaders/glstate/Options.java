package net.vulkanmod.shaders.glstate;

import net.vulkanmod.shaders.ShaderPack;
import net.vulkanmod.shaders.pack.ShaderProperties;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the value of every user-configurable key (e.g. SHADOW_QUALITY,
 * WATER_REFRACTION_INTENSITY). For now values come solely from the pack's
 * default `common.glsl` `#define NAME value //[...]` declarations. A later
 * stage will layer in Iris-style option profiles and the config screen.
 */
public final class Options {

    private final Map<String, Object> values = new LinkedHashMap<>();

    private Options() {
    }

    public static Options fromPack(ShaderPack pack) throws IOException {
        Options o = new Options();
        String common = pack.getSource("shaders/lib/common.glsl");
        if (common != null) collectDefines(common, o.values);
        return o;
    }

    private static void collectDefines(String glsl, Map<String, Object> out) {
        for (String line : glsl.split("\n")) {
            String t = line.replaceAll("//.*", "").trim();
            if (!t.startsWith("#define")) continue;
            String rest = t.substring(8).trim();
            int sp = indexOfWhitespace(rest);
            if (sp <= 0) continue;
            String name = rest.substring(0, sp).trim();
            String value = rest.substring(sp).trim();
            value = value.split("//")[0].trim();
            if (name.isEmpty() || value.isEmpty()) continue;
            if (!Character.isLetter(name.charAt(0))) continue;
            out.put(name, coerce(value));
        }
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t') return i;
        }
        return -1;
    }

    private static Object coerce(String v) {
        String low = v.toLowerCase();
        if (low.equals("true")) return Boolean.TRUE;
        if (low.equals("false")) return Boolean.FALSE;
        if (looksNumeric(v)) {
            try {
                if (v.contains(".")) return Double.parseDouble(v);
                return Integer.parseInt(v);
            } catch (NumberFormatException ignored) {
            }
        }
        return v;
    }

    private static boolean looksNumeric(String v) {
        if (v.isEmpty()) return false;
        int i = 0;
        if (v.charAt(0) == '-' || v.charAt(0) == '+') i = 1;
        if (i >= v.length()) return false;
        boolean dot = false;
        for (; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '.') {
                if (dot) return false;
                dot = true;
            } else if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    public boolean getBoolean(String key, boolean def) {
        Object o = values.get(key);
        return o instanceof Boolean b ? b : def;
    }

    public int getInt(String key, int def) {
        Object o = values.get(key);
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try {
                return (int) Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    public double getDouble(String key, double def) {
        Object o = values.get(key);
        if (o instanceof Number n) return n.doubleValue();
        return def;
    }

    public String getString(String key, String def) {
        Object o = values.get(key);
        return o == null ? def : String.valueOf(o);
    }

    public Map<String, Object> values() {
        return values;
    }
}