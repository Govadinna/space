package dev.seamlessspace.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.seamlessspace.universe.DVec3;
import dev.seamlessspace.universe.Planet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanetWorldRegistryTest {
    @TempDir Path save;

    @Test
    void roundTripKeepsFullDescriptorsAndEditsCanFindTheSameWorldAfterRestart() throws IOException {
        Planet rocky = planet("a", Long.MAX_VALUE, Planet.Kind.ROCKY);
        Planet ice = planet("b", Long.MIN_VALUE, Planet.Kind.ICE);
        Path index = save.resolve("data/seamlessspace-planets.json");
        PlanetWorldRegistry.write(index, List.of(rocky, ice));

        List<Planet> restored = PlanetWorldRegistry.read(index);
        assertEquals(2, restored.size());
        assertTrue(restored.contains(rocky));
        assertTrue(restored.contains(ice));
        assertEquals(PlanetWorldRegistry.dimensionPath(rocky), PlanetWorldRegistry.dimensionPath(
                restored.stream().filter(planet -> planet.id().equals("a")).findFirst().orElseThrow()));
        assertFalse(Files.exists(index.resolveSibling(index.getFileName() + ".tmp")));

        PlanetWorldRegistry.write(index, List.of(rocky));
        assertEquals(List.of(rocky), PlanetWorldRegistry.read(index));
    }

    @Test
    void dimensionPathsAreSafeAndDistinctForPlanetAndUniverseSeed() {
        Planet original = planet("../../overworld \\:\u0000", 12, Planet.Kind.LAVA);
        String path = PlanetWorldRegistry.dimensionPath(original);
        assertTrue(path.matches("planet/[0-9a-f]{32}"));
        assertEquals(path, PlanetWorldRegistry.dimensionPath(original));
        assertNotEquals(path, PlanetWorldRegistry.dimensionPath(planet(original.id(), 13, original.kind())));
        assertNotEquals(path, PlanetWorldRegistry.dimensionPath(planet("other", 12, original.kind())));
    }

    @Test
    void legacyWorldsKeepTheirOriginalTerrainAndNewWorldsKeepTheNewProfile() throws IOException {
        Planet legacy = planet("legacy-generation", 985, Planet.Kind.ROCKY);
        Path index = save.resolve("data/legacy.json");
        PlanetWorldRegistry.write(index, List.of(legacy));
        double rugged = PlanetSurface.worldHeight(legacy, 130, 480);
        assertEquals(4, PlanetSurface.profile(legacy));
        var root = com.google.gson.JsonParser.parseString(Files.readString(index)).getAsJsonObject();
        root.getAsJsonArray("planets").get(0).getAsJsonObject().remove("terrainVersion");
        Files.writeString(index,root.toString());
        assertEquals(List.of(legacy),PlanetWorldRegistry.read(index));
        assertEquals(2,PlanetSurface.profile(legacy));
        double original = PlanetSurface.worldHeight(legacy,130,480);
        assertNotEquals(rugged,original);
        PlanetWorldRegistry.write(index,List.of(legacy));
        PlanetSurface.forgetProfile(legacy);
        PlanetWorldRegistry.read(index);
        assertEquals(2,PlanetSurface.profile(legacy));
        assertEquals(original,PlanetSurface.worldHeight(legacy,130,480));
        PlanetSurface.forgetProfile(legacy);
    }

    @Test
    void malformedIndexIsReportedInsteadOfSilentlyOrphaningSavedWorlds() throws IOException {
        Path index = save.resolve("seamlessspace-planets.json");
        assertEquals(List.of(), PlanetWorldRegistry.read(index));
        Files.writeString(index, "{\"version\": 99, \"planets\": []}");
        assertThrows(IOException.class, () -> PlanetWorldRegistry.read(index));
        Files.writeString(index, "{\"version\": 1, \"planets\": [{}]}");
        assertThrows(IOException.class, () -> PlanetWorldRegistry.read(index));
    }

    private static Planet planet(String id, long seed, Planet.Kind kind) {
        return new Planet(id, "Planet " + id, new DVec3(9.123456789e14, -8.7654321e13, 6.777e15),
                6_371_064, kind, seed);
    }
}
