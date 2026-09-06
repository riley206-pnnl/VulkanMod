package net.vulkanmod.shaders.transform;

import net.vulkanmod.shaders.ShaderPack;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.vulkanmod.Initializer.LOGGER;

/**
 * Turns OptiFine-style stage GLSL (as shipped in Complementary and similar Iris
 * packs) into Vulkan-friendly GLSL {460} that compiles cleanly under shaderc.
 *
 * <p>What the processor does:
 * <ul>
 *   <li>flattens {@code #include} against the pack layout ("/lib/x.glsl" resolves
 *       from the shaders/ root, relative paths against the current file's dir)</li>
 *   <li>strips {@code //[...]} GUI hint comments on option lines</li>
 *   <li>rewrites legacy texture functions and matrix built-ins</li>
 *   <li>rewrites {@code gl_FragColor}/{@code gl_FragData[n]} to an explicit output array</li>
 *   <li>assigns {@code layout(location = N)} to every interface variable; fragment
 *       inputs reuse the vertex output locations by name (map kept per program)</li>
 *   <li>collects loose uniforms: values fold into one std140 UBO, samplers/images
 *       get explicit sequential bindings</li>
 * </ul>
 *
 * <p>Instances are single program-scoped: call {@link #process} for the vertex
 * stage first, then the fragment stage of the same program.</p>
 */
public final class ShaderProcessor {

    private static final Pattern VERSION = Pattern.compile("^\\s*#version\\b.*$");
    private static final Pattern BUSY_LINE = Pattern.compile("^\\s*(#extension|#pragma)\\b.*$");
    private static final Pattern PRECISION = Pattern.compile("^\\s*precision\\s+[a-z]+\\s+[a-z]+\\s*;\\s*$");
    private static final Pattern INCLUDE = Pattern.compile("^\\s*#include\\s+\"([^\"]+)\"\\s*(//.*)?$");
    private static final Pattern GUI_HINT = Pattern.compile("([^\\\\]?)//\\[(.*?)\\]\\s*$");
    private static final Pattern LEGACY_LAYOUT = Pattern.compile("^\\s*layout\\((?<args>[^)]*)\\)\\s+");
    private static final Pattern INTERFACE = Pattern.compile(
            "^(?<qual>flat\\s+|centroid\\s+|sample\\s+)?(?<dir>in|out|attribute|varying)\\s+" +
                    "(?<type>[A-Za-z_][A-Za-z0-9_]*)\\s+(?<names>[^;(]+);");
    private static final Pattern UNIFORM = Pattern.compile(
            "^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+" +
                    "(?<qual>readonly|writeonly|coherent|volatile|restrict)?\\s*" +
                    "(?<type>[A-Za-z_][A-Za-z0-9_]*)\\s+(?<names>[^;]+);");
    private static final Pattern FRAG_DATA = Pattern.compile("gl_FragData\\s*\\[\\s*(\\d+)\\s*\\]");

    private static final Set<String> SAMPLER_TYPES = Set.of(
            "sampler1D", "sampler1DShadow", "sampler2D", "sampler2DShadow", "sampler3D",
            "samplerCube", "samplerCubeShadow", "sampler2DArray", "sampler2DArrayShadow",
            "sampler1DArray", "sampler1DArrayShadow", "sampler2DRect", "sampler2DRectShadow",
            "isampler1D", "isampler2D", "isampler3D", "isamplerCube", "isampler2DArray",
            "usampler1D", "usampler2D", "usampler3D", "usamplerCube", "usampler2DArray");

    /** Vertex outputs name -&gt; location, reused by the fragment stage of this program. */
    private final Map<String, Integer> vertexOutLocs = new LinkedHashMap<>();
    /** Custom fragment outputs (packs that declare their own out vars). */
    private final Map<String, Integer> customFragOutLocs = new LinkedHashMap<>();

    /** Sampler name -&gt; binding, shared across stages of a program so the
     * pipeline's descriptor set stays consistent. */
    private final Map<String, Integer> samplerBindings = new LinkedHashMap<>();

    private final boolean directTerrain;

    public ShaderProcessor() {
        this(false);
    }

    /** Direct terrain rendering has no shadow/deferred/history attachments yet. */
    public ShaderProcessor(boolean directTerrain) {
        this.directTerrain = directTerrain;
    }

    private int vertexInCount;
    private int vertexOutCount;
    private int fragOutCount;
    private int binding = 3; // 0 = pack UBO, 1 = legacy matrices, 2 = terrain section data

    /**
     * Process one stage. Vertex must be processed before the fragment of the
     * same program so locations line up.
     */
    public ProcessedShader process(StageSource src) throws IOException {
        String[] stage = readStage(src);
        List<String> lines = flatten(stage[0], src.pack(), stage[1]);

        String text = String.join("\n", lines);
        if (directTerrain && src.program().equals("gbuffers_terrain")) {
            // Use Complementary's own feature switches, before its derived
            // macros are evaluated. Never sample nonexistent shadow/history
            // attachments or request attributes the terrain mesh doesn't have.
            Map<String, String> options = Map.of(
                    "SHADOW_QUALITY", "-1", "TAA_MODE", "0",
                    "BLOCK_REFLECT_QUALITY", "1", "RAIN_PUDDLES", "0",
                    "RP_MODE", "1", "ANISOTROPIC_FILTER", "0",
                    "COLORED_LIGHTING", "0", "LIGHTSHAFT_BEHAVIOUR", "0");
            for (var option : options.entrySet()) {
                text = text.replaceAll("(?m)^([ \t]*#define[ \t]+" + option.getKey()
                        + ")[ \t]+[^\r\n]*", "$1 " + option.getValue());
            }
            if (src.stage() == Stage.FRAGMENT) {
                // Vulkan's framebuffer origin is at the top, while packs
                // reconstruct view positions from bottom-origin coordinates.
                text = text.replace("gl_FragCoord", "vm_FragCoord");
            }
        }
        text = stripHints(text);
        text = rewriteLegacy(text);
        text = rewriteShadowAccess(text);
        text = rewriteOutputs(text, src.stage());

        List<String> body = new ArrayList<>(java.util.Arrays.asList(text.split("\n", -1)));
        List<ShaderUniform> uniforms = new ArrayList<>();
        List<String> valueMembers = new ArrayList<>();

        boolean customOuts = false;

        List<String> processed = new ArrayList<>(body.size());
        boolean active = true;
        ArrayDeque<Region> regions = new ArrayDeque<>();
        for (String line : body) {
            String stripped = line.strip();

            if (stripped.startsWith("#if") || stripped.startsWith("#elif")) {
                Region top = regions.peek();
                Boolean c = evalStageCond(stripped, src.stage());
                if (stripped.startsWith("#elif")) {
                    if (top != null && top.known) {
                        boolean now = c != null && c && !top.taken;
                        top.taken = top.taken || now;
                        active = now;
                    }
                    processed.add(line);
                    continue;
                }
                Region r = new Region(c != null && c, c != null, active);
                regions.push(r);
                if (c != null) active = c;
                processed.add(line);
                continue;
            }
            if (stripped.startsWith("#else")) {
                Region top = regions.peek();
                if (top != null && top.known) {
                    boolean now = !top.taken;
                    top.taken = true;
                    active = now;
                }
                processed.add(line);
                continue;
            }
            if (stripped.startsWith("#endif")) {
                Region top = regions.isEmpty() ? null : regions.pop();
                if (top != null) active = top.parentActive;
                processed.add(line);
                continue;
            }

            if (!active) {
                processed.add(line);
                continue;
            }

            if (VERSION.matcher(stripped).matches()
                    || BUSY_LINE.matcher(stripped).matches()
                    || PRECISION.matcher(stripped).matches()) {
                continue;
            }

            // Complementary's terrain metadata is supplied by the renderer
            // as a temporary constant until block-id/mid-block attributes are
            // implemented in TerrainBuilder. Do not expose absent locations
            // 0 and 1 to Vulkan in this first terrain slice.
            if (src.stage() == Stage.VERTEX && src.program().equals("gbuffers_terrain")
                    && (stripped.startsWith("attribute vec4 mc_Entity")
                    || stripped.startsWith("attribute vec2 mc_midTexCoord")
                    || stripped.startsWith("attribute vec4 mc_midTexCoord"))) {
                processed.add(stripped.startsWith("attribute vec4 mc_Entity")
                        ? "#define mc_Entity vec4(0.0)"
                        : "#define mc_midTexCoord vec4(0.0)");
                continue;
            }

            String emitted = rewriteInterface(stripped, src.stage());
            if (emitted != null) {
if (src.stage() == Stage.FRAGMENT && emitted.startsWith("customout")) {
                    customOuts = true;
                    processed.add(emitted.substring("customout".length()));
                } else {
                    processed.add(emitted);
                }
                continue;
            }

            if (!stripped.startsWith("#")) {
                Matcher m = UNIFORM.matcher(stripped);
                if (m.matches()) {
                    String type = m.group("type").trim();
                    String[] nameList = splitNames(m.group("names").trim());
                    if (SAMPLER_TYPES.contains(type) || isImageType(type)) {
                        String qual = m.group("qual");
                        boolean keepQual = isImageType(type) && qual != null;
                        StringBuilder sb = new StringBuilder();
                        for (String name : nameList) {
                            int b = samplerBindings.computeIfAbsent(name, n -> binding++);
                            sb.append("layout(binding = ").append(b).append(", set = 0) ");
                            if (keepQual) sb.append(qual).append(' ');
                            sb.append("uniform ").append(type)
                                    .append(' ').append(name).append(";");
                            uniforms.add(new ShaderUniform(name, type, UniformKind.SAMPLER, 0, b, 0, 0, src.stage().name()));
                            if (nameList.length > 1) sb.append('\n');
                        }
                        processed.add(stripped.startsWith("layout(")
                                ? stripped
                                : sb.toString());
                    } else {
                        for (String name : nameList) {
                            String cleaned = name.split("=")[0].trim();
                            if (cleaned.isEmpty()) continue;
                            TypeLayout tl = TypeLayout.of(type);
                            valueMembers.add(tl.glslType(cleaned));
                        }
                    }
                    continue;
                }
            }

            processed.add(line);
        }

        StringBuilder header = new StringBuilder();
        String stageMacro = src.stage() == Stage.VERTEX ? "VERTEX_SHADER" : "FRAGMENT_SHADER";
        header.append("#define ").append(stageMacro).append('\n');
        String dimMacro = switch (src.dimension()) {
            case "world-1" -> "THE_NETHER";
            case "world1" -> "THE_END";
            default -> "OVERWORLD";
        };
        header.append("#define ").append(dimMacro).append('\n');
        String progMacro = src.program().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Za-z0-9_]", "_");
        header.append("#define ").append(progMacro).append('\n');

        boolean legacyMatrices = text.contains("vm_glModelViewProjectionMatrix")
                || text.contains("vm_glModelViewMatrix") || text.contains("vm_glProjectionMatrix")
                || text.contains("vm_glNormalMatrix") || text.contains("vm_glTextureMatrix");
        boolean legacyLightMatrix = text.contains("vm_glTextureMatrixLight");
        boolean legacyFog = text.contains("vm_glFog");
        if (legacyMatrices || legacyFog) {
            header.append("layout(std140, binding = 1, set = 0) uniform LegacyMatrices {\n")
                    .append("    mat4 vm_glModelViewProjectionMatrix;\n")
                    .append("    mat4 vm_glModelViewMatrix;\n")
                    .append("    mat4 vm_glProjectionMatrix;\n")
                    .append("    mat4 vm_glModelViewMatrixInverse;\n")
                    .append("    mat3 vm_glNormalMatrix;\n")
                    .append("    mat4 vm_glTextureMatrix;\n");
            if (legacyLightMatrix) {
                header.append("    mat4 vm_glTextureMatrixLight;\n");
            }
            if (legacyFog) {
                header.append("    vec4 vm_glFog_color;\n")
                        .append("    float vm_glFog_start;\n")
                        .append("    float vm_glFog_end;\n")
                        .append("    float vm_glFog_density;\n")
                        .append("    float vm_glFog_scale;\n");
            }
            header.append("};\n");
        }

        // Terrain shader packs expect the old OptiFine/Iris vertex contract,
        // while the renderer supplies the section-relative TERRAIN format.
        // Keep this shim private to gbuffers_terrain so other pack programs
        // retain ordinary vertex inputs.
        boolean terrainShim = src.stage() == Stage.VERTEX && src.program().equals("gbuffers_terrain");
        if (terrainShim) {
            header.append("#define VM_TERRAIN_SHIM\n")
                    .append("layout(std140, binding = 2, set = 0) uniform vm_SectionData {\n")
                    .append("    ivec4 vm_SectionOffsets[128];\n")
                    .append("    vec4 vm_SectionFadeFactors[128];\n")
                    .append("};\n")
                    .append("layout(push_constant) uniform vm_TerrainPushConstants {\n")
                    .append("    vec3 vm_ModelOffset;\n")
                    .append("};\n");
        }

        if (src.stage() == Stage.VERTEX
                && (text.contains("vm_glVertex") || text.contains("vm_glNormal")
                || text.contains("vm_glColor") || text.contains("vm_glMultiTexCoord")
                || text.contains("vm_glModelViewProjectionMatrix * vm_glVertex"))) {
            if (terrainShim) {
                header.append("layout(location = 4) in vec3 vm_TerrainVertex;\n")
                        .append("layout(location = 5) in vec4 vm_TerrainNormal;\n")
                        .append("layout(location = 6) in vec4 vm_TerrainColor;\n")
                        .append("layout(location = 7) in vec2 vm_TerrainUV0;\n")
                        .append("layout(location = 8) in ivec2 vm_TerrainUV2;\n")
                        .append("#define vm_glVertex vec4(vm_TerrainVertex + vm_ModelOffset + vec3(bitfieldExtract(ivec3(vm_SectionOffsets[gl_InstanceIndex >> 2][gl_InstanceIndex & 3]) >> ivec3(0, 16, 8), 0, 8)), 1.0)\n")
                        .append("#define vm_glNormal vm_TerrainNormal.xyz\n")
                        .append("#define vm_glColor vm_TerrainColor\n")
                        .append("#define vm_glMultiTexCoord0 vec4(vm_TerrainUV0, 0.0, 1.0)\n")
                        .append("#define vm_glMultiTexCoord1 vec4((vec2(vm_TerrainUV2) + 8.0) / 256.0, 0.0, 1.0)\n");
                for (int i = 2; i < 8; i++) {
                    header.append("#define vm_glMultiTexCoord").append(i).append(" vec4(0.0)\n");
                }
            } else {
                int leg = Math.max(4, vertexInCount);
                header.append("layout(location = ").append(leg++).append(") in vec4 vm_glVertex;\n");
                header.append("layout(location = ").append(leg++).append(") in vec3 vm_glNormal;\n");
                header.append("layout(location = ").append(leg++).append(") in vec4 vm_glColor;\n");
                for (int i = 0; i < 8; i++) {
                    header.append("layout(location = ").append(leg++).append(") in vec4 vm_glMultiTexCoord").append(i).append(";\n");
                }
            }
        }

        int uboSize = 0;
        if (!valueMembers.isEmpty()) {
            StringBuilder block = new StringBuilder();
            block.append("layout(std140, binding = 0, set = 0) uniform PackUniforms {\n");
            int offset = 0;
            int maxAlign = 1;
            for (String member : valueMembers) {
                String type = member.substring(0, member.indexOf(' '));
                String name = member.substring(member.indexOf(' ') + 1, member.length() - 1).trim();
                TypeLayout tl = TypeLayout.of(type);
                offset = align(offset, tl.align);
                uniforms.add(new ShaderUniform(name, type, UniformKind.VALUE, 0, 0, offset, tl.size, src.stage().name()));
                block.append("    ").append(member).append('\n');
                offset += tl.size;
                maxAlign = Math.max(maxAlign, tl.align);
            }
            uboSize = align(offset, maxAlign);
            block.append("};\n");
            header.append(block);
        }

        int maxFrag = src.stage() == Stage.FRAGMENT && !customOuts ? Math.max(1, maxFragIndex(text) + 1) : 0;
        if (src.stage() == Stage.FRAGMENT && !customOuts) {
            header.append("layout(location = 0, set = 0) out vec4 _fragOut[").append(maxFrag).append("];\n");
        }

        if (directTerrain && src.program().equals("gbuffers_terrain") && src.stage() == Stage.FRAGMENT) {
            header.append("#define vm_FragCoord vec4(gl_FragCoord.x, viewHeight - gl_FragCoord.y, gl_FragCoord.z, gl_FragCoord.w)\n");
        }
        String glsl = header.toString() + String.join("\n", processed) + "\n";
        return new ProcessedShader(src.stage(), src.program(), src.dimension(),
                glsl.replace("\r\n", "\n").replace("\r", "\n"), uniforms,
                uboSize, maxFrag);
    }

    /** Whether the stage macro name tests the given stage. */
    private static final Pattern STAGE_IFDEF = Pattern.compile("^#(?:if|elif)(?:def|ndef)\\s+(\\w+)\\s*$");
    private static final Pattern STAGE_DEFINED = Pattern.compile(
            "^(?:#if|#elif)\\s+(?:!\\s*)?defined\\s*\\(?\\s*(\\w+)\\s*\\)?\\s*$");

    private static final class Region {
        boolean taken;
        boolean known;
        boolean parentActive;

        Region(boolean taken, boolean known, boolean parentActive) {
            this.taken = taken;
            this.known = known;
            this.parentActive = parentActive;
        }
    }

    /**
     * Evaluates a preprocessor condition that tests the stage macros
     * (VERTEX_SHADER/FRAGMENT_SHADER); returns null for any other expression.
     */
    private Boolean evalStageCond(String stripped, Stage stage) {
        if (!stripped.startsWith("#if")) return null;
        Matcher m = STAGE_IFDEF.matcher(stripped);
        if (m.matches()) {
            if (!isStageName(m.group(1))) return null; // non-stage test: unknown
            boolean val = stageMacroOf(stage).equals(m.group(1));
            return stripped.startsWith("#ifndef") ? !val : val;
        }
        Matcher d = STAGE_DEFINED.matcher(stripped);
        if (d.matches()) {
            if (!isStageName(d.group(1))) return null; // non-stage test: unknown
            boolean val = stageMacroOf(stage).equals(d.group(1));
            boolean negated = stripped.contains("!");
            return negated != val;
        }
        return null;
    }

    private static boolean isStageName(String name) {
        return name.equals("VERTEX_SHADER") || name.equals("FRAGMENT_SHADER");
    }

    private static String stageMacroOf(Stage stage) {
        return stage == Stage.VERTEX ? "VERTEX_SHADER" : "FRAGMENT_SHADER";
    }

    private boolean isInterfaceLine(String stripped) {
        if (stripped.isEmpty() || stripped.startsWith("#")) return false;
        if (stripped.contains("(") || stripped.contains("{")) return false;
        if (!stripped.endsWith(";")) return false;
        Matcher m = INTERFACE.matcher(stripped);
        return m.matches();
    }

    /**
     * Returns null when the line is not an interface decl. Emits the rewritten
     * line, or "customout&lt;line&gt;" for fragment out variables (so the caller can
     * mark that this stage writes custom outputs and skip the _fragOut array).
     */
    private String rewriteInterface(String stripped, Stage stage) {
        Matcher m = INTERFACE.matcher(stripped);
        if (!m.matches()) return null;
        String qual = m.group("qual") == null ? "" : m.group("qual");
        String dir = m.group("dir");
        String type = m.group("type");
        String[] names = splitNames(m.group("names"));

        String prefix;
        switch (dir) {
            case "attribute" -> {
                StringBuilder sb = new StringBuilder();
                for (String n : names) sb.append("layout(location = ").append(vertexInCount++).append(") in ").append(type).append(' ').append(n).append(";\n");
                return sb.toString();
            }
            case "varying" -> {
                // treated as in/out depending on stage
                if (stage == Stage.VERTEX) {
                    StringBuilder sb = new StringBuilder();
                    for (String n : names) {
                        int loc = vertexOutCount++;
                        vertexOutLocs.put(n, loc);
                        sb.append("layout(location = ").append(loc).append(") ").append(qual).append("out ").append(type).append(' ').append(n).append(";\n");
                    }
                    return sb.toString();
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (String n : names) {
                        Integer loc = vertexOutLocs.get(n);
                        if (loc == null) loc = 0;
                        sb.append("layout(location = ").append(loc).append(") ").append(qual).append("in ").append(type).append(' ').append(n).append(";\n");
                    }
                    return sb.toString();
                }
            }
            case "in" -> {
                StringBuilder sb = new StringBuilder();
                for (String n : names) {
                    Integer loc;
                    if (stage == Stage.FRAGMENT) {
                        loc = vertexOutLocs.get(n);
                        if (loc == null) {
                            LOGGER.warn("Fragment input '{}' has no matching vertex output", n);
                            loc = 0;
                        }
                    } else {
                        loc = vertexInCount++;
                    }
                    sb.append("layout(location = ").append(loc).append(") ").append(qual).append("in ").append(type).append(' ').append(n).append(";\n");
                }
                return sb.toString();
            }
            case "out" -> {
                StringBuilder sb = new StringBuilder();
                if (stage == Stage.VERTEX) {
                    for (String n : names) {
                        int loc = vertexOutCount++;
                        vertexOutLocs.put(n, loc);
                        sb.append("layout(location = ").append(loc).append(") ").append(qual).append("out ").append(type).append(' ').append(n).append(";\n");
                    }
                    return sb.toString();
                } else {
                    for (String n : names) {
                        int loc = fragOutCount++;
                        customFragOutLocs.put(n, loc);
                        sb.append("layout(location = ").append(loc).append(") ").append(qual).append("out ").append(type).append(' ').append(n).append(";\n");
                    }
                    return "customout" + sb;
                }
            }
            default -> {
                return null;
            }
        }
    }

    private String rewriteOutputs(String text, Stage stage) {
        if (stage != Stage.FRAGMENT) {
            return text.replace("gl_FragColor", "_fragOut[0]");
        }
        Matcher m = FRAG_DATA.matcher(text);
        text = m.replaceAll("_fragOut[$1]");
        return text.replace("gl_FragColor", "_fragOut[0]");
    }

    private String rewriteLegacy(String text) {
        String t = text
                .replace("texture2DLod(", "textureLod(")
                .replace("texture2DProjLod(", "textureProjLod(")
                .replace("texture2DProj(", "textureProj(")
                .replace("texture2D(", "texture(")
                .replace("texture3D(", "texture(")
                .replace("textureCubeLod(", "textureLod(")
                .replace("textureCube(", "texture(")
                .replace("texture2DArray(", "texture(")
                .replace("shadow2DProjLod(", "textureProjLod(")
                .replace("shadow2DProj(", "textureProj(")
                .replace("shadow2DLod(", "textureLod(")
                .replace("shadow2D(", "texture(");
        if (t.contains("gl_TextureMatrix")) {
            t = t.replace("gl_TextureMatrix[1]", "vm_glTextureMatrixLight")
                    .replace("gl_TextureMatrix[0]", "gl_TextureMatrix")
                    .replace("gl_TextureMatrix[2]", "gl_TextureMatrix")
                    .replace("gl_TextureMatrix[3]", "gl_TextureMatrix");
        }
        // glslang reserves the gl_ prefix even for identifiers we synthesize
        // (UBO members, vertex inputs), so rename every legacy token we emulate.
        t = t.replace("gl_ModelViewProjectionMatrix", "vm_glModelViewProjectionMatrix")
                .replace("gl_ModelViewMatrixInverse", "vm_glModelViewMatrixInverse")
                .replace("gl_ModelViewMatrix", "vm_glModelViewMatrix")
                .replace("gl_ProjectionMatrix", "vm_glProjectionMatrix")
                .replace("gl_NormalMatrix", "vm_glNormalMatrix")
                .replace("gl_TextureMatrix", "vm_glTextureMatrix");
        t = t.replace("gl_Vertex", "vm_glVertex")
                .replace("gl_Normal", "vm_glNormal")
                .replace("gl_Color", "vm_glColor");
        for (int i = 7; i >= 0; i--) {
            t = t.replace("gl_MultiTexCoord" + i, "vm_glMultiTexCoord" + i);
        }
        // ftransform() == gl_ModelViewProjectionMatrix * gl_Vertex
        t = t.replace("ftransform()", "(vm_glModelViewProjectionMatrix * vm_glVertex)");
        // OptiFine legacy fog intrinsics
        t = t.replace("gl_Fog.color", "vm_glFog_color")
                .replace("gl_Fog.start", "vm_glFog_start")
                .replace("gl_Fog.end", "vm_glFog_end")
                .replace("gl_Fog.density", "vm_glFog_density")
                .replace("gl_Fog.scale", "vm_glFog_scale");
        return t;
    }

    /**
     * Packs written for OptiFine freely use {@code texture(shadowtex, coords).x}
     * or {@code .z} on the float result of a {@code sampler2DShadow} sample.
     * That is illegal glslang, so strip the redundant component access for
     * every sampler declared as {@code sampler2DShadow} in this program.
     */
    private String rewriteShadowAccess(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("uniform\\s+sampler2DShadow\\s+(\\w+)")
                .matcher(text);
        java.util.Set<String> shadow = new java.util.HashSet<>();
        while (m.find()) {
            shadow.add(m.group(1));
        }
        if (shadow.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        int n = text.length();
        while (i < n) {
            for (String name : shadow) {
                String head = "texture(" + name + ",";
                if (text.startsWith(head, i)) {
                    int depth = 1;
                    for (int j = i + head.length() - 1; j < n && depth > 0; j++) {
                        char c = text.charAt(j);
                        if (c == '(') depth++;
                        else if (c == ')') depth--;
                        if (depth == 0) {
                            int end = j + 1;
                            if (end + 1 < n && text.charAt(end) == '.'
                                    && (text.charAt(end + 1) == 'x' || text.charAt(end + 1) == 'z')
                                    && (end + 2 >= n || !Character.isLetterOrDigit(text.charAt(end + 2)))) {
                                out.append(text, i, end);
                                i = end + 2;
                            } else {
                                out.append(text, i, end);
                                i = end;
                            }
                            break;
                        }
                    }
                    continue;
                }
            }
            if (i < n) {
                out.append(text.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    private String stripHints(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (GUI_HINT.matcher(line).find() && line.trim().startsWith("#define")) {
                sb.append(GUI_HINT.matcher(line).replaceAll("$1")).append('\n');
            } else {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private int maxFragIndex(String text) {
        int max = 0;
        Matcher m = Pattern.compile("_fragOut\\s*\\[\\s*(\\d+)\\s*\\]").matcher(text);
        while (m.find()) {
            max = Math.max(max, Integer.parseInt(m.group(1)));
        }
        return max;
    }

    private static String[] splitNames(String names) {
        String[] parts = names.split(",");
        String[] out = new String[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = parts[i].trim();
        return out;
    }

    private static boolean isImageType(String type) {
        return type.contains("image");
    }

    private static int align(int v, int a) {
        return (v + a - 1) / a * a;
    }

    // ----- include flattening -----

    private String[] readStage(StageSource src) throws IOException {
        String ext = src.stage() == Stage.VERTEX ? ".vsh" : ".fsh";
        String dimPath = "shaders/" + src.dimension() + "/" + src.program() + ext;
        String basePath = "shaders/" + src.program() + ext;
        String source = src.pack().getSource(dimPath);
        String used = dimPath;
        if (source == null) {
            source = src.pack().getSource(basePath);
            used = basePath;
        }
        if (source == null) {
            throw new IOException("Shader stage missing for program '" + src.program() + "' (" + src.dimension() + ", " + src.stage() + ")");
        }
        return new String[]{source, used};
    }

    private List<String> flatten(String source, ShaderPack pack, String path) throws IOException {
        List<String> out = new ArrayList<>();
        Deque<String> stack = new ArrayDeque<>();
        flattenInto(out, source, pack, path, stack);
        return out;
    }

    private void flattenInto(List<String> out, String source, ShaderPack pack, String path,
                             Deque<String> stack) throws IOException {
        if (stack.contains(path)) {
            LOGGER.warn("Shader include cycle at {} (skipped)", path);
            return;
        }
        stack.push(path);
        String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
        for (String line : source.split("\n", -1)) {
            Matcher m = INCLUDE.matcher(line.stripLeading());
            if (m.matches()) {
                String inc = m.group(1).trim();
                String resolved;
                if (inc.startsWith("/")) {
                    resolved = "shaders/" + inc.substring(1);
                } else {
                    resolved = dir + inc;
                }
                String incSrc = pack.getSource(resolved);
                if (incSrc == null) {
                    LOGGER.warn("Missing include '{}' referenced by {}", inc, path);
                    continue;
                }
                // OptiFine/Iris repeat includes at every include site; packs guard
                // against double-processing via #ifdef stage sections or manual
                // #ifndef wrappers, so no pragma-once dedupe is applied here.
                flattenInto(out, incSrc, pack, resolved, stack);
            } else {
                out.add(line);
            }
        }
        stack.pop();
    }

    /** std140 member sizing used to derive ubo offsets (and later CPU layout). */
    private record TypeLayout(int align, int size, String glsl) {
        static TypeLayout of(String type) {
            return switch (type) {
                case "float", "int", "uint", "bool" -> new TypeLayout(4, 4, type);
                case "vec2", "ivec2", "uvec2", "bvec2" -> new TypeLayout(8, 8, type);
                case "vec3", "ivec3", "uvec3", "bvec3" -> new TypeLayout(16, 12, type);
                case "vec4", "ivec4", "uvec4", "bvec4" -> new TypeLayout(16, 16, type);
                case "mat2" -> new TypeLayout(16, 32, type);
                case "mat3" -> new TypeLayout(16, 48, type);
                case "mat4" -> new TypeLayout(16, 64, type);
                default -> {
                    if (type.contains("*")) {
                        // pointer/other: give it a planner safe offset
                        yield new TypeLayout(16, 64, "vec4");
                    }
                    LOGGER.warn("Unhandled uniform type '{}' (treating as vec4)", type);
                    yield new TypeLayout(16, 16, "vec4");
                }
            };
        }

        String glslType(String name) {
            return glsl + " " + name + ";";
        }
    }
}
