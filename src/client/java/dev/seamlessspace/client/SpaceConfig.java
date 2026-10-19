package dev.seamlessspace.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Small, deliberately bounded configuration; invalid values cannot poison the renderer. */
public final class SpaceConfig {
    public int configVersion = 6;
    public boolean compactSystem = true;
    public boolean solarSystem = true;
    public long seed = 7042026;
    public double scale = 1.0;
    public double entryAltitude = 100000;
    public double transitionBlocks = 80000;
    public double maxCruiseSpeed = 200000000;
    public boolean terrainDetail = true;
    public int renderHeight = 1080;
    public double starBrightness = 0.25;
    public double starSize = 0.65;
    public double landingAltitude = 64;
    public boolean automaticLanding = true;
    public boolean showHud = true;
    public boolean automaticEntry = true;

    public static SpaceConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("seamlessspace.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        SpaceConfig config = new SpaceConfig();
        try {
            if (Files.isRegularFile(path)) {
                var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                SpaceConfig loaded = gson.fromJson(json, SpaceConfig.class);
                if (loaded != null) config = loaded;
                if (!json.has("configVersion") || json.get("configVersion").getAsInt() < 2) {
                    config.renderHeight = Math.max(1080, config.renderHeight);
                    config.configVersion = 2;
                }
                if (!json.has("configVersion") || json.get("configVersion").getAsInt() < 3) {
                    config.maxCruiseSpeed = Math.max(config.maxCruiseSpeed, 200000000);
                    config.landingAltitude = 160;
                    config.configVersion = 4;
                }
                if (!json.has("configVersion") || json.get("configVersion").getAsInt() < 4) {
                    if(config.entryAltitude<=10000) config.entryAltitude=100000;
                    config.transitionBlocks=80000;
                    config.configVersion=4;
                }
                if(!json.has("configVersion") || json.get("configVersion").getAsInt()<5) {
                    config.compactSystem=true;
                    if(config.landingAltitude==160) config.landingAltitude=64;
                    config.configVersion=5;
                }
            }
            if(config.configVersion<6) {config.solarSystem=true;config.configVersion=6;}
            config.validate();
            Files.createDirectories(path.getParent());
            Files.writeString(path, gson.toJson(config) + "\n");
        } catch (IOException | RuntimeException exception) {
            SeamlessSpaceClient.LOGGER.warn("Cannot read/write space config; using safe values", exception);
            config.validate();
        }
        return config;
    }

    private void validate() {
        scale = finiteClamp(scale, 0.0001, 1, 1.0);
        entryAltitude = finiteClamp(entryAltitude, 2000, 1000000, 100000);
        transitionBlocks = finiteClamp(transitionBlocks, 1000, entryAltitude, 80000);
        maxCruiseSpeed = finiteClamp(maxCruiseSpeed, 1000, 5000000000.0, 200000000);
        renderHeight = Math.clamp(renderHeight, 720, 2160);
        starBrightness = finiteClamp(starBrightness, 0, 1, 0.25);
        starSize = finiteClamp(starSize, 0.3, 2, 0.65);
        landingAltitude = finiteClamp(landingAltitude, 32, 1024, 64);
    }

    private static double finiteClamp(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }
}
