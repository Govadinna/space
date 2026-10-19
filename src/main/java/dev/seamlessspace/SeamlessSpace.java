package dev.seamlessspace;

import dev.seamlessspace.world.PlanetChunkGenerator;
import dev.seamlessspace.world.PlanetWorlds;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

public final class SeamlessSpace implements ModInitializer {
    @Override
    public void onInitialize() {
        Registry.register(BuiltInRegistries.CHUNK_GENERATOR,
                Identifier.fromNamespaceAndPath("seamlessspace", "planet"), PlanetChunkGenerator.CODEC);
        dev.seamlessspace.world.PlanetBlocks.initialize();
        PlanetWorlds.initialize();
        dev.seamlessspace.world.EarthPreview.initialize();
        dev.seamlessspace.world.OrbitalSurfaceCache.initialize();
    }
}
