package net.beryl.option;

import java.util.ArrayList;
import java.util.List;
import net.beryl.BerylMod;
import net.beryl.render.RenderingPipeline;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.OptionBlock;
import net.vulkanmod.config.option.CyclingOption;
import net.vulkanmod.config.option.Option;
import net.vulkanmod.config.option.OptionPage;
import net.vulkanmod.config.option.PerformanceImpact;
import net.vulkanmod.config.option.RangeOption;
import net.vulkanmod.config.option.SwitchOption;

public class BerylOptions {
   public static Config config = BerylMod.CONFIG;

   public static List<OptionPage> getOptionPages() {
      List<OptionPage> optionPages = new ArrayList<>();
      OptionPage page = new OptionPage("Shaders", getOptions());
      optionPages.add(page);
      return optionPages;
   }

   public static OptionBlock[] getOptions() {
      return new OptionBlock[]{
         new OptionBlock("", new Option[]{new SwitchOption(Component.translatable("beryl.options.toggle"), value -> {
            config.shadersOn = value;
            RenderingPipeline.setUseShaderPipeline(config.shadersOn);
         }, () -> config.shadersOn)}),
         new OptionBlock("", new Option[]{new SwitchOption(Component.translatable("beryl.options.textured_sun"), value -> {
            config.texturedSun = value;
            RenderingPipeline.setUseTexturedSun(config.texturedSun);
         }, () -> config.texturedSun).setImpact(PerformanceImpact.LOW), new SwitchOption(Component.translatable("beryl.options.waving_water"), value -> {
            config.waterWaving = value;
            RenderingPipeline.setWaterWaving(config.waterWaving);
         }, () -> config.waterWaving).setImpact(PerformanceImpact.LOW), new SwitchOption(Component.translatable("beryl.options.ssr"), value -> {
            config.ssr = value;
            RenderingPipeline.setSSR(config.ssr);
         }, () -> config.ssr).setImpact(PerformanceImpact.HIGH)}),
         new OptionBlock(
            "",
            new Option[]{
               new SwitchOption(Component.translatable("beryl.options.shadow.colored"), value -> {
                  config.coloredShadows = value;
                  RenderingPipeline.setColoredShadows(config.coloredShadows);
               }, () -> config.coloredShadows).setImpact(PerformanceImpact.HIGH),
               new RangeOption(
                     Component.translatable("beryl.options.shadow.rd"),
                     2,
                     32,
                     1,
                     value -> config.shadowRenderDistance = value,
                     () -> config.shadowRenderDistance
                  )
                  .setImpact(PerformanceImpact.HIGH),
               new CyclingOption<>(Component.translatable("beryl.options.shadow.res"), new Integer[]{1024, 1536, 2048, 3072, 4096}, value -> {
                  config.shadowResolution = value;
                  RenderingPipeline.scheduleReload();
               }, () -> config.shadowResolution).setTranslator(value -> Component.literal(String.valueOf(value))).setImpact(PerformanceImpact.HIGH)
            }
         ),
         new OptionBlock(
            "",
            new Option[]{
               new RangeOption(
                  Component.translatable("beryl.options.atmospheric_fog_int"),
                  0,
                  200,
                  5,
                  value -> config.atmFogIntensity = value.intValue() / 100.0F,
                  () -> (int)(config.atmFogIntensity * 100.0F)
               ),
               new RangeOption(
                  Component.translatable("beryl.options.bloom_int"),
                  0,
                  200,
                  5,
                  value -> config.bloomIntensity = value.intValue() / 100.0F,
                  () -> (int)(config.bloomIntensity * 100.0F)
               ),
               new RangeOption(
                  Component.translatable("beryl.options.water_absorption"),
                  0,
                  50,
                  1,
                  value -> config.waterAbsorption = value.intValue() / 100.0F,
                  () -> (int)(config.waterAbsorption * 100.0F)
               ).setImpact(PerformanceImpact.LOW)
            }
         )
      };
   }
}
