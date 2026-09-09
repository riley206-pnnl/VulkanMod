package net.beryl.render.shader;

import net.vulkanmod.vulkan.shader.PipelineConfig;
import net.vulkanmod.vulkan.shader.PipelineConfig.Builder;
import net.vulkanmod.vulkan.shader.PipelineConfig.UB;
import net.vulkanmod.vulkan.shader.SpirvCompiler.ShaderKind;

public class BerylPipelineConfigs {
   public static final UB COMMON_UB0 = UB.builder(0, 31)
      .addUniform("mat4", "MVP")
      .addUniform("mat4", "ModelViewMat")
      .addUniform("mat4", "LightSpaceMat")
      .addUniform("vec3", "LightSpaceOffset")
      .build();
   public static final UB COMMON_UB1 = UB.builder(1, 31)
      .addUniform("vec4", "ColorModulator")
      .addUniform("vec4", "SkyColor")
      .addUniform("vec4", "FogColor")
      .addUniform("vec3", "UpVector")
      .addUniform("float", "FogStart")
      .addUniform("float", "FogEnd")
      .addUniform("float", "FogEnvironmentalStart")
      .addUniform("float", "FogEnvironmentalEnd")
      .addUniform("vec3", "LightDir")
      .addUniform("vec3", "LightColor")
      .addUniform("vec3", "AmbientLight")
      .addUniform("vec3", "CameraPos")
      .addUniform("float", "LightIntensity")
      .addUniform("float", "LightVisibility")
      .addUniform("float", "NightFactor")
      .addUniform("float", "AmbientLightFactor")
      .addUniform("float", "MinAmbientLight")
      .addUniform("float", "FogFactor")
      .addUniform("float", "ShadowTexelSize")
      .addUniform("float", "ShadowBias")
      .addUniform("float", "ShadowDistortion")
      .addUniform("float", "RainStrength")
      .build();
   public static final UB TERRAIN_UB2 = UB.builder(2, 1).setSize(4096).build();
   public static final UB TERRAIN_PC = UB.builder(0, 1).addUniform("vec3", "ModelOffset").build();

   public static PipelineConfig getTerrainConfig(boolean coloredShadows) {
      Builder builder = PipelineConfig.builder()
         .withShader(ShaderKind.VERTEX_SHADER, "terrain")
         .withShader(ShaderKind.FRAGMENT_SHADER, "terrain")
         .addUB(COMMON_UB0)
         .addUB(COMMON_UB1)
         .addUB(TERRAIN_UB2)
         .setPushConstants(TERRAIN_PC)
         .addImageDescriptor(3, "sampler2D", "Sampler0", 0)
         .addImageDescriptor(4, "sampler2D", "LightTexture", 2)
         .addImageDescriptor(5, "sampler2DShadow", "ShadowMap", 3);
      if (coloredShadows) {
         builder.addImageDescriptor(6, "sampler2DShadow", "ShadowMap1", 4).addImageDescriptor(7, "sampler2D", "ShadowColor", 5);
      }

      return builder.build();
   }

   public static PipelineConfig getTranslucentTerrainConfig(boolean ssr) {
      Builder builder = PipelineConfig.builder()
         .withShader(ShaderKind.VERTEX_SHADER, "translucent")
         .withShader(ShaderKind.FRAGMENT_SHADER, "translucent")
         .addUB(
            UB.builder(0, 31)
               .addUniform("mat4", "MVP")
               .addUniform("mat4", "ProjMat")
               .addUniform("mat4", "ModelViewMat")
               .addUniform("mat4", "LightSpaceMat")
               .addUniform("vec3", "LightSpaceOffset")
               .build()
         )
         .addUB(
            UB.builder(1, 31)
               .addUniform("vec4", "ColorModulator")
               .addUniform("vec4", "SkyColor")
               .addUniform("vec4", "FogColor")
               .addUniform("vec3", "UpVector")
               .addUniform("float", "FogStart")
               .addUniform("float", "FogEnd")
               .addUniform("float", "FogEnvironmentalStart")
               .addUniform("float", "FogEnvironmentalEnd")
               .addUniform("vec3", "LightDir")
               .addUniform("vec3", "LightColor")
               .addUniform("vec3", "AmbientLight")
               .addUniform("vec3", "CameraPos")
               .addUniform("float", "GameTime")
               .addUniform("float", "LightIntensity")
               .addUniform("float", "LightVisibility")
               .addUniform("float", "NightFactor")
               .addUniform("float", "AmbientLightFactor")
               .addUniform("float", "MinAmbientLight")
               .addUniform("float", "FogFactor")
               .addUniform("float", "ShadowTexelSize")
               .addUniform("float", "ShadowBias")
               .addUniform("float", "ShadowDistortion")
               .addUniform("float", "WaterAbsorption")
               .build()
         )
         .addUB(TERRAIN_UB2)
         .setPushConstants(TERRAIN_PC)
         .addImageDescriptor(3, "sampler2D", "Sampler0", 0)
         .addImageDescriptor(4, "sampler2D", "LightTexture", 2)
         .addImageDescriptor(5, "sampler2DShadow", "ShadowMap", 3);
      if (ssr) {
         builder.addImageDescriptor(6, "sampler2D", "Framebuffer", 4).addImageDescriptor(7, "sampler2D", "Depthbuffer", 5);
      }

      return builder.build();
   }
}
