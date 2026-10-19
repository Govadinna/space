package dev.seamlessspace.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.seamlessspace.universe.DVec3;
import dev.seamlessspace.universe.Planet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;

/** The descriptor index is per save; blocks/entities remain in ordinary region files. */
public final class PlanetWorldRegistry {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    private PlanetWorldRegistry() { }

    /** Do not embed arbitrary planet IDs in filesystem paths. */
    public static String dimensionPath(Planet planet) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(planet.id().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Long.toString(planet.seed()).getBytes(StandardCharsets.UTF_8));
            return "planet/" + HexFormat.of().formatHex(digest.digest(), 0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java does not provide SHA-256", impossible);
        }
    }

    public static List<Planet> read(Path path) throws IOException {
        if (!Files.exists(path)) {
            return List.of();
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (root.get("version").getAsInt() != 1) {
                throw new IllegalArgumentException("Unsupported planet index version");
            }
            List<Planet> result = new ArrayList<>();
            for (var element : root.getAsJsonArray("planets")) {
                JsonObject entry = element.getAsJsonObject();
                JsonArray center = entry.getAsJsonArray("center");
                Planet planet = new Planet(entry.get("id").getAsString(), entry.get("name").getAsString(),
                        new DVec3(center.get(0).getAsDouble(), center.get(1).getAsDouble(), center.get(2).getAsDouble()),
                        entry.get("radius").getAsDouble(), Planet.Kind.valueOf(entry.get("kind").getAsString()),
                        entry.get("seed").getAsLong());
                PlanetSurface.setProfile(planet, entry.has("terrainVersion") ? entry.get("terrainVersion").getAsInt() : 2);
                result.add(planet);
            }
            return List.copyOf(result);
        } catch (RuntimeException malformed) {
            throw new IOException("Cannot read planet index " + path, malformed);
        }
    }

    /** Commit the full index atomically, so stopping during a save cannot truncate it. */
    public static void write(Path path, Collection<Planet> planets) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonArray entries = new JsonArray();
        planets.stream().sorted(java.util.Comparator.comparing(PlanetWorldRegistry::dimensionPath)).forEach(planet -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", planet.id());
            entry.addProperty("name", planet.name());
            JsonArray center = new JsonArray();
            center.add(planet.center().x());
            center.add(planet.center().y());
            center.add(planet.center().z());
            entry.add("center", center);
            entry.addProperty("radius", planet.radius());
            entry.addProperty("kind", planet.kind().name());
            entry.addProperty("seed", planet.seed());
            entry.addProperty("terrainVersion", PlanetSurface.profile(planet));
            entries.add(entry);
        });
        root.add("planets", entries);
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, JSON.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
