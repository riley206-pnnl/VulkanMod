package net.vulkanmod;

import org.lwjgl.util.shaderc.Shaderc;

import java.util.Locale;

import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_into_spv;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compilation_status_success;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_vertex_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_initialize;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_initialize;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_bytes;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_compilation_status;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_error_message;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_release;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_release;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_release;

public class TestBigSource {
    public static void main(String[] args) throws Exception {
        long compiler = shaderc_compiler_initialize();
        long options = shaderc_compile_options_initialize();

        int[] sizes = {10, 50, 100, 200, 300, 400, 800, 1024};
        for (int kb : sizes) {
            StringBuilder sb = new StringBuilder();
            sb.append("#version 450\nfloat data[256];\nvoid main(){ vec4 p=vec4(0.0);\n");
            // fill with junk to inflate beyond kb
            int target = kb * 1024;
            int i = 0;
            while (sb.length() < target) {
                sb.append("p += vec4(").append(i++).append(".0);\n");
            }
            sb.append("gl_Position=p;\n}");
            String src = sb.toString();
            long r = shaderc_compile_into_spv(compiler, src, shaderc_glsl_vertex_shader, "big", "main", options);
            int status = shaderc_result_get_compilation_status(r);
            System.out.printf(Locale.ROOT, "size=%d bytes status=%d (%s)%n", src.length(), status,
                    status == shaderc_compilation_status_success ? "OK" : "ERR");
            if (status != shaderc_compilation_status_success) {
                String err = shaderc_result_get_error_message(r);
                System.out.println("    " + (err == null ? "null" : err.substring(0, Math.min(err.length(), 200))));
            }
            shaderc_result_release(r);
        }
        shaderc_compile_options_release(options);
        shaderc_compiler_release(compiler);
    }
}