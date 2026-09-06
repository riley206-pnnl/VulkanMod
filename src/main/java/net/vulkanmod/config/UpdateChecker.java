package net.vulkanmod.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.vulkanmod.Initializer;
import org.apache.maven.artifact.versioning.ComparableVersion;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CompletableFuture;

public abstract class UpdateChecker {
    private static boolean updateAvailable = false;

    public static void checkForUpdates() {
        CompletableFuture.supplyAsync(() -> {
            try {
                String req = "https://api.modrinth.com/v2/project/vulkanmod/version?include_changelog=false";
                String mcVersion = SharedConstants.getCurrentVersion().name();
                req += "&game_versions=%s".formatted(mcVersion);

                URL url = new URL(req);
                HttpURLConnection http = (HttpURLConnection)url.openConnection();
                var inputStream = http.getInputStream();

                JsonObject data = JsonParser.parseString("{ versions: " + new String(inputStream.readAllBytes()) + "}").getAsJsonObject();
                JsonArray versions = data.getAsJsonArray("versions");
                http.disconnect();

                if (versions != null && !versions.isEmpty()) {
                    String version = String.valueOf(versions.get(0).getAsJsonObject().get("version_number")).replace("\"", "");

                    String currentVerStr = Initializer.getVersion();
                    if (currentVerStr != null && currentVerStr.contains("-dev")) {
                        Initializer.LOGGER.info("Pre-release version, skipping update check.");
                        return null;
                    }

                    if (currentVerStr != null) {
                        ComparableVersion currentVersion = new ComparableVersion(currentVerStr);
                        ComparableVersion remoteVersion = new ComparableVersion(version);

                        updateAvailable = currentVersion.compareTo(remoteVersion) < 0;

                        if (updateAvailable) {
                            Initializer.LOGGER.info("Update available!");
                        }
                    }
                }
            }
            catch (IOException e) {
                Initializer.LOGGER.info("Error occurred, skipping update check.");
            }
            catch (Exception e) {
                Initializer.LOGGER.info("Unable to parse version, skipping update check.");
            }

            return null;
        });
    }

    public static boolean isUpdateAvailable() {
        return updateAvailable;
    }
}
