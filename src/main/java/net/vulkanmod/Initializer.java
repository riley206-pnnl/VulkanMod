package net.vulkanmod;

import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.vulkanmod.config.Config;
import net.vulkanmod.config.Platform;
import net.vulkanmod.config.UpdateChecker;
import net.vulkanmod.config.gui.VOptionScreen;
import net.vulkanmod.shaders.ShaderPackManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

@Mod("vulkanmod")
public class Initializer {
	public static final Logger LOGGER = LogManager.getLogger("VulkanMod");

	private static String VERSION = "0.6.8";
	public static Config CONFIG;

	public Initializer(IEventBus modEventBus, ModContainer modContainer) {
		if (modContainer != null && modContainer.getModInfo() != null) {
			VERSION = modContainer.getModInfo().getVersion().toString();
			modContainer.registerExtensionPoint(IConfigScreenFactory.class,
					(container, parent) -> new VOptionScreen(Component.literal("VulkanMod Settings"), parent));
		}

		LOGGER.info("== VulkanMod ==");

		Path configPath = FMLPaths.CONFIGDIR.get().resolve("vulkanmod_settings.json");
		CONFIG = loadConfig(configPath);
		Platform.init();
		// Renderer initialization can happen before FMLClientSetupEvent. Load
		// shader packs here so PipelineManager sees the selected pack when it
		// constructs Vulkan pipelines.
		ShaderPackManager.init(FMLPaths.GAMEDIR.get());

		modEventBus.addListener(this::onInitializeClient);
	}

	private void onInitializeClient(FMLClientSetupEvent event) {
		UpdateChecker.checkForUpdates();
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
}
