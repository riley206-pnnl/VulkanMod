package net.beryl;

import com.mojang.blaze3d.platform.InputConstants.Type;
import java.nio.file.Path;
import net.beryl.option.BerylOptions;
import net.beryl.option.Config;
import net.beryl.render.RenderingPipeline;
import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;



import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.KeyMapping.Category;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.vulkanmod.config.gui.ModSettingsEntry;
import net.vulkanmod.config.gui.ModSettingsRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod("beryl")
public class BerylMod {
   public static final Logger LOGGER = LogManager.getLogger("Beryl");
   private static String VERSION;
   public static Config CONFIG;
   private static final Category berylKeybindCategory = new Category(Identifier.fromNamespaceAndPath("beryl", "keybinds"));
   private static KeyMapping toggleKey;

   public BerylMod(IEventBus modEventBus, ModContainer container) {
      VERSION = container.getModInfo().getVersion().toString();
      LOGGER.info("== Beryl ==");
      Path configPath = FMLPaths.CONFIGDIR.get().resolve("beryl_settings.json");
      CONFIG = loadConfig(configPath);
      registerSettingsModEntry(container);
      toggleKey = new KeyMapping("beryl.keybind.toggle", Type.KEYSYM, 82, berylKeybindCategory);
      modEventBus.addListener((RegisterKeyMappingsEvent event) -> event.register(toggleKey));
      net.beryl.render.ShaderRendererResources.initBlockIdMap();
   }

   private static Config loadConfig(Path path) {
      Config config = Config.load(path);
      if (config == null) {
         config = new Config();
         config.write();
      }

      return config;
   }

   public static String getVersion() {
      return VERSION;
   }

   private static void registerSettingsModEntry(ModContainer container) {
      ModSettingsEntry berylSettings = new ModSettingsEntry(
         Component.literal("Beryl Shaders").withStyle(ChatFormatting.GREEN),
         () -> Identifier.fromNamespaceAndPath("beryl", "beryl_icon_transparent.png"),
         BerylOptions::getOptionPages,
         () -> CONFIG.write()
      );
      ModSettingsRegistry.INSTANCE.addModEntryFirst(berylSettings);
      container.registerExtensionPoint(net.neoforged.neoforge.client.gui.IConfigScreenFactory.class,
         (mod, parent) -> new net.vulkanmod.config.gui.VOptionScreen(Component.literal("Beryl Shaders"), parent, berylSettings));
   }

   public static void handleKeys() {
      if (toggleKey.consumeClick()) {
         RenderingPipeline.toggleShaderPipeline();
      }
   }
}
