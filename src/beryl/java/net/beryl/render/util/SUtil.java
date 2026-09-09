package net.beryl.render.util;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.List;
import net.beryl.render.shader.ComputePipeline;
import net.vulkanmod.render.shader.ShaderLoadUtil;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.PipelineConfig;
import net.vulkanmod.vulkan.shader.Pipeline.Builder;
import net.vulkanmod.vulkan.shader.SpirvCompiler.ShaderKind;

public class SUtil {
   public static final String RESOURCES_PATH = "/assets/beryl";
   public static final String SHADERS_PATH = "%s/shaders/".formatted(RESOURCES_PATH);

   public static GraphicsPipeline createGraphicsPipeline(VertexFormat format, String path) {
      String[] splitPaths = ShaderLoadUtil.splitPath(path);
      return createGraphicsPipeline(format, splitPaths[0], splitPaths[1], null);
   }

   public static GraphicsPipeline createGraphicsPipeline(VertexFormat format, String path, List<String> defines) {
      String[] splitPaths = ShaderLoadUtil.splitPath(path);
      return createGraphicsPipeline(format, splitPaths[0], splitPaths[1], defines);
   }

   public static GraphicsPipeline createGraphicsPipeline(VertexFormat format, String path, String name, List<String> defines) {
      String fullPath = ShaderLoadUtil.resolveShaderPath(SHADERS_PATH, path);
      JsonObject config = ShaderLoadUtil.getJsonConfig(fullPath, name);
      PipelineConfig pipelineConfig = PipelineConfig.fromJson(name, config);
      return createGraphicsPipeline(format, path, pipelineConfig, defines);
   }

   public static GraphicsPipeline createGraphicsPipeline(VertexFormat format, String path, PipelineConfig pipelineConfig, List<String> defines) {
      String fullPath = ShaderLoadUtil.resolveShaderPath(SHADERS_PATH, path);
      Builder pipelineBuilder = new Builder(format, path);
      pipelineBuilder.applyConfig(pipelineConfig);
      pipelineBuilder.setShaderSrc(
         ShaderKind.VERTEX_SHADER, ShaderLoadUtil.loadShader(fullPath, "%s.vsh".formatted(pipelineConfig.shaderPaths.get(ShaderKind.VERTEX_SHADER)), defines)
      );
      pipelineBuilder.setShaderSrc(
         ShaderKind.FRAGMENT_SHADER,
         ShaderLoadUtil.loadShader(fullPath, "%s.fsh".formatted(pipelineConfig.shaderPaths.get(ShaderKind.FRAGMENT_SHADER)), defines)
      );
      return pipelineBuilder.createGraphicsPipeline();
   }

   public static ComputePipeline createComputePipeline(String path, PipelineConfig config) {
      String[] splitPaths = ShaderLoadUtil.splitPath(path);
      return createComputePipeline(splitPaths[0], splitPaths[1], config);
   }

   public static ComputePipeline createComputePipeline(String path, String name, PipelineConfig pipelineConfig) {
      Builder pipelineBuilder = new Builder(path);
      path = ShaderLoadUtil.resolveShaderPath(SHADERS_PATH, path);
      pipelineBuilder.applyConfig(pipelineConfig);
      pipelineBuilder.setShaderSrc(ShaderKind.COMPUTE_SHADER, ShaderLoadUtil.loadShader(path, "%s.comp".formatted(name)));
      return new ComputePipeline(pipelineBuilder);
   }
}
