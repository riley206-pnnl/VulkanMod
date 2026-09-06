package net.vulkanmod.shaders.transform;

public enum Stage {
    VERTEX,
    FRAGMENT;

    public String glslDefine() {
        return this == VERTEX ? "VERTEX_SHADER" : "FRAGMENT_SHADER";
    }

    public static Stage fromFile(String file) {
        if (file == null) return VERTEX;
        return file.endsWith(".fsh") || file.endsWith(".frag") ? FRAGMENT : VERTEX;
    }
}