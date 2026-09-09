package net.beryl.option;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

public class Config {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().excludeFieldsWithModifiers(new int[]{2}).create();
   private static Path CONFIG_PATH;
   public boolean shadersOn = true;
   public boolean texturedSun = true;
   public boolean waterWaving = true;
   public boolean ssr = true;
   public boolean coloredShadows = true;
   public int shadowRenderDistance = 12;
   public int shadowResolution = 2048;
   public float atmFogIntensity = 1.0F;
   public float bloomIntensity = 1.0F;
   public float waterAbsorption = 0.15F;

   public void write() {
      if (!Files.exists(CONFIG_PATH.getParent())) {
         try {
            Files.createDirectories(CONFIG_PATH.getParent());
         } catch (IOException var3) {
            var3.printStackTrace();
         }
      }

      try {
         Files.write(CONFIG_PATH, Collections.singleton(GSON.toJson(this)));
      } catch (IOException var2) {
         var2.printStackTrace();
      }
   }

   public static Config load(Path path) {
      CONFIG_PATH = path;
      Config config;
      if (Files.exists(path)) {
         try (FileReader fileReader = new FileReader(path.toFile())) {
            config = (Config)GSON.fromJson(fileReader, Config.class);
         } catch (IOException var7) {
            throw new RuntimeException(var7.getMessage());
         }
      } else {
         config = null;
      }

      return config;
   }
}
