package net.vulkanmod.vulkan.shader;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.lwjgl.system.NativeResource;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.shaderc.ShadercIncludeResolveI;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import org.lwjgl.util.shaderc.ShadercIncludeResultReleaseI;
import org.lwjgl.vulkan.VK12;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memASCII;
import static org.lwjgl.util.shaderc.Shaderc.*;

public class SpirvCompiler {
    private static final boolean DEBUG = true;
    private static final boolean OPTIMIZATIONS = false;

    private static long compiler;
    private static long options;

    //The dedicated Includer and Releaser Inner Classes used to Initialise #include Support for ShaderC
    private static final ShaderIncluder SHADER_INCLUDER = new ShaderIncluder();
    private static final ShaderReleaser SHADER_RELEASER = new ShaderReleaser();
    private static final long pUserData = 0;

    private static ObjectArrayList<String> includePaths;

    /**
     * Transformed GLSL supplied in-process (used for shader packs). Contents are
     * served by the includer instead of the classpath. Registering a large source
     * here avoids LWJGL's MemoryStack cap (~64KB) on the straw entry shader that
     * {@code #include}s it.
     */
    private static final Map<String, String> VIRTUAL_INCLUDES = new HashMap<>();

    static {
        init();
    }

    private static void init() {
        compiler = shaderc_compiler_initialize();

        if (compiler == NULL) {
            throw new RuntimeException("Failed to create shader compiler");
        }

        options = shaderc_compile_options_initialize();

        if (options == NULL) {
            throw new RuntimeException("Failed to create compiler options");
        }

        if (OPTIMIZATIONS)
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);

        if (DEBUG)
            shaderc_compile_options_set_generate_debug_info(options);

        shaderc_compile_options_set_target_env(options, shaderc_env_version_vulkan_1_2, VK12.VK_API_VERSION_1_2);
        shaderc_compile_options_set_include_callbacks(options, SHADER_INCLUDER, SHADER_RELEASER, pUserData);

        Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
        Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);

        includePaths = new ObjectArrayList<>();
        addIncludePath("/assets/vulkanmod/shaders/include/");
    }

    public static void addIncludePath(String path) {
        includePaths.add(path.endsWith("/") ? path : path + "/");
    }

    /** Serve {@code content} as an includable resource named {@code name}. */
    public static void addVirtualInclude(String name, String content) {
        VIRTUAL_INCLUDES.put(name, content);
    }

    /** Compile a virtual include as the whole program via a tiny entry shader. */
    public static SPIRV compileVirtualShader(String filename, String includeName, ShaderKind shaderKind) {
        String content = VIRTUAL_INCLUDES.get(includeName);
        if (content == null) {
            throw new IllegalArgumentException("No virtual include registered: " + includeName);
        }
        // The entry provides the version so the (transformed) include body must not
        // redeclare it; the 64KB MemoryStack cap only applies to this tiny string.
        return compileShader(filename, "#version 450\n#include \"" + includeName + "\"", shaderKind);
    }

    public static SPIRV compileShader(String filename, String source, ShaderKind shaderKind) {
        if (source == null) {
            throw new NullPointerException("Source for %s.%s is null".formatted(filename, shaderKind));
        }

        long result = shaderc_compile_into_spv(compiler, source, shaderKind.kind, filename, "main", options);

        if (result == NULL) {
            throw new RuntimeException("Failed to compile shader %s into SPRI-V".formatted(filename ));
        }

        if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
            String errorMessage = shaderc_result_get_error_message(result);
            throw new RuntimeException("Failed to compile shader %s into SPIR-V:\n\t%s".formatted(filename, errorMessage));
        }

        return new SPIRV(result, shaderc_result_get_bytes(result));
    }

    public enum ShaderKind {
        VERTEX_SHADER(shaderc_glsl_vertex_shader),
        GEOMETRY_SHADER(shaderc_glsl_geometry_shader),
        FRAGMENT_SHADER(shaderc_glsl_fragment_shader),
        COMPUTE_SHADER(shaderc_glsl_compute_shader);

        private final int kind;

        ShaderKind(int kind) {
            this.kind = kind;
        }
    }

    private static class ShaderIncluder implements ShadercIncludeResolveI {


        @Override
        public long invoke(long user_data, long requested_source, int type, long requesting_source, long include_depth) {
            var requesting = memASCII(requesting_source);
            var requested = memASCII(requested_source);

            String virtual = VIRTUAL_INCLUDES.get(requested);
            if (virtual != null) {
                return includeResult(requested, virtual, user_data);
            }

            try {
                for (String includePath : includePaths) {
                    try (var stream = net.vulkanmod.render.shader.ShaderLoadUtil.getInputStream(includePath + requested)) {
                        if (stream != null) {
                            return includeResult(requested, new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), user_data);
                        }
                    }
                }
            } catch (IOException e) {
                return includeResult("", e.toString(), user_data);
            }
            // Report failure to shaderc; exceptions must not escape a native callback.
            return includeResult("", requesting + ": Unable to find " + requested + " in include paths", user_data);
        }
    }

    private static long includeResult(String name, String content, long userData) {
        // The buffers must remain alive until shaderc calls the release callback.
        return ShadercIncludeResult.calloc()
                .source_name(org.lwjgl.system.MemoryUtil.memUTF8(name, false))
                .content(org.lwjgl.system.MemoryUtil.memUTF8(content, false))
                .user_data(userData).address();
    }

    private static class ShaderReleaser implements ShadercIncludeResultReleaseI {
        @Override
        public void invoke(long user_data, long include_result) {
            ShadercIncludeResult result = ShadercIncludeResult.create(include_result);
            org.lwjgl.system.MemoryUtil.memFree(result.source_name());
            org.lwjgl.system.MemoryUtil.memFree(result.content());
            result.free();
        }
    }

    public static final class SPIRV implements NativeResource {

        private final long handle;
        private ByteBuffer bytecode;

        public SPIRV(long handle, ByteBuffer bytecode) {
            this.handle = handle;
            this.bytecode = bytecode;
        }

        public ByteBuffer bytecode() {
            return bytecode;
        }

        @Override
        public void free() {
//            shaderc_result_release(handle);
            bytecode = null; // Help the GC
        }
    }

}