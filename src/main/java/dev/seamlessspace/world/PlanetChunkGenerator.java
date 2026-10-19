package dev.seamlessspace.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.seamlessspace.universe.DVec3;
import dev.seamlessspace.universe.Planet;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/** Ordinary persisted voxel chunks, generated only around players in a planet dimension. */
public final class PlanetChunkGenerator extends ChunkGenerator {
    private static final Codec<DVec3> VECTOR_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.DOUBLE.fieldOf("x").forGetter(DVec3::x),
            Codec.DOUBLE.fieldOf("y").forGetter(DVec3::y),
            Codec.DOUBLE.fieldOf("z").forGetter(DVec3::z)).apply(instance, DVec3::new));
    public static final Codec<Planet> PLANET_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(Planet::id),
            Codec.STRING.fieldOf("name").forGetter(Planet::name),
            VECTOR_CODEC.fieldOf("center").forGetter(Planet::center),
            Codec.DOUBLE.fieldOf("radius").forGetter(Planet::radius),
            Codec.STRING.xmap(Planet.Kind::valueOf, Planet.Kind::name).fieldOf("kind").forGetter(Planet::kind),
            Codec.LONG.fieldOf("seed").forGetter(Planet::seed)).apply(instance, Planet::new));
    public static final MapCodec<PlanetChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            PLANET_CODEC.fieldOf("planet").forGetter(PlanetChunkGenerator::planet),
            Biome.CODEC.fieldOf("biome").forGetter(generator -> generator.biome),
            Codec.intRange(2, 9).optionalFieldOf("terrain_version", 2).forGetter(generator -> generator.terrainVersion))
            .apply(instance, PlanetChunkGenerator::new));

    private final Planet planet;
    private final Holder<Biome> biome;
    private final int terrainVersion;
    private final BlockState rock;
    private final BlockState subsoil;
    private final BlockState top;
    private final BlockState fluid;

    public PlanetChunkGenerator(Planet planet, Holder<Biome> biome) {
        this(planet, biome, PlanetSurface.profile(planet));
    }

    public PlanetChunkGenerator(Planet planet, Holder<Biome> biome, int terrainVersion) {
        super(new FixedBiomeSource(biome), ignored -> BiomeGenerationSettings.EMPTY);
        PlanetSurface.setProfile(planet, terrainVersion);
        this.terrainVersion = terrainVersion;
        this.planet = planet;
        this.biome = biome;
        this.rock = switch (planet.kind()) {
            case ROCKY, OCEAN -> Blocks.STONE.defaultBlockState();
            case DESERT -> Blocks.TERRACOTTA.defaultBlockState();
            case ICE -> (terrainVersion>=8 && planet.body()==Planet.Body.NEPTUNE?Blocks.BLUE_ICE:Blocks.PACKED_ICE).defaultBlockState();
            case LAVA -> Blocks.BLACKSTONE.defaultBlockState();
        };
        this.subsoil = switch (planet.kind()) {
            case ROCKY -> Blocks.ANDESITE.defaultBlockState();
            case DESERT -> Blocks.RED_SANDSTONE.defaultBlockState();
            case ICE -> (terrainVersion>=8 && planet.body()==Planet.Body.NEPTUNE?Blocks.BLUE_ICE:Blocks.PACKED_ICE).defaultBlockState();
            case LAVA -> Blocks.BASALT.defaultBlockState();
            case OCEAN -> Blocks.DIRT.defaultBlockState();
        };
        this.top = switch (planet.kind()) {
            case ROCKY -> Blocks.STONE.defaultBlockState();
            case DESERT -> Blocks.RED_SAND.defaultBlockState();
            case ICE -> Blocks.SNOW_BLOCK.defaultBlockState();
            case LAVA -> Blocks.BASALT.defaultBlockState();
            case OCEAN -> Blocks.GRASS_BLOCK.defaultBlockState();
        };
        this.fluid = planet.body()==Planet.Body.SUN ? Blocks.AIR.defaultBlockState() : switch (planet.kind()) {
            case OCEAN -> Blocks.WATER.defaultBlockState();
            case LAVA -> Blocks.LAVA.defaultBlockState();
            default -> Blocks.AIR.defaultBlockState();
        };
    }

    public Planet planet() {
        return planet;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> structures,
            RandomState randomState, long levelSeed) {
        return ChunkGeneratorStructureState.createForFlat(randomState, levelSeed,
                getOrigin(randomState), biomeSource, Stream.empty());
    }

    @Override
    public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender,
            RandomState randomState, StructureManager structures, BiomeManager biomeManager,
            WorldGenRegion carverBiomeRegion, Set<Holder<Biome>> possibleBiomes) {
        if(planet.id().equals("native-space")) {
            Heightmap.primeHeightmaps(chunk,EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG,Heightmap.Types.WORLD_SURFACE_WG));
            return CompletableFuture.completedFuture(chunk);
        }
        int minimum = Math.max(getMinY(), chunk.getMinY());
        int maximum = Math.min(getMinY() + getGenDepth() - 1, chunk.getMaxY());
        int worldX = chunk.getPos().getMinBlockX();
        int worldZ = chunk.getPos().getMinBlockZ();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int ground = PlanetSurface.height(planet, worldX + x, worldZ + z);
                var cavity=PlanetGeology.cavity(planet,worldX+x,worldZ+z,ground);
                int highest = fluid.isAir() ? ground : Math.max(ground, getSeaLevel());
                for (int y = minimum; y <= Math.min(highest, maximum); y++) {
                    BlockState state = stateAt(y, ground, worldX+x, worldZ+z,cavity);
                    LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
                    // Worldgen owns this chunk; avoid a palette lock for every block.
                    section.setBlockState(x, y & 15, z, state, false);
                }
            }
        }
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG,
                Heightmap.Types.WORLD_SURFACE_WG));
        return CompletableFuture.completedFuture(chunk);
    }

    private BlockState stateAt(int y, int ground, int x, int z,PlanetGeology.Cavity cavity) {
        if(planet.id().equals("native-space"))return Blocks.AIR.defaultBlockState();
        if (y < getMinY() || y >= getMinY() + getGenDepth()) return Blocks.AIR.defaultBlockState();
        if (y == getMinY()) return Blocks.BEDROCK.defaultBlockState();
        if (y > ground) return y <= getSeaLevel() ? fluid : Blocks.AIR.defaultBlockState();
        if(cavity.contains(y))return Blocks.AIR.defaultBlockState();
        int depth = ground - y;
        if(planet.body()==Planet.Body.SUN && depth<4 && (terrainVersion<7 || depth>0)) return (terrainVersion>=6?PlanetBlocks.plasma():Blocks.GLOWSTONE).defaultBlockState();
        if (planet.kind() == Planet.Kind.OCEAN && ground < getSeaLevel()) {
            return depth < 4 ? Blocks.SAND.defaultBlockState() : rock;
        }
        if (depth == 0) {
            var material=PlanetMaterials.top(planet,x,z,ground);
            return terrainVersion>=6 ? PlanetBlocks.surface(material).defaultBlockState() : terrainVersion < 4 ? top : materialState(material);
        }
        if (depth < (planet.kind() == Planet.Kind.DESERT ? 5 : 4)) return subsoil;
        return rock;
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor height,
            RandomState randomState) {
        int ground = planet.id().equals("native-space")?getMinY()-1:PlanetSurface.height(planet, x, z);
        var cavity=PlanetGeology.cavity(planet,x,z,ground);
        int maximum = Math.min(Math.max(ground, getSeaLevel()), height.getMaxY());
        for (int y = maximum; y >= height.getMinY(); y--) {
            if (type.isOpaque().test(stateAt(y, ground,x,z,cavity))) return y + 1;
        }
        return height.getMinY();
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor height, RandomState randomState) {
        BlockState[] states = new BlockState[height.getHeight()];
        int ground = planet.id().equals("native-space")?getMinY()-1:PlanetSurface.height(planet, x, z);
        var cavity=PlanetGeology.cavity(planet,x,z,ground);
        for (int index = 0; index < states.length; index++) {
            states[index] = stateAt(height.getMinY() + index, ground,x,z,cavity);
        }
        return new NoiseColumn(height.getMinY(), states);
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structures) { }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) { }

    @Override
    public void addDebugScreenInfo(List<String> result, RandomState randomState, BlockPos pos,
            SamplerContext samplerContext) {
        result.add("Planet: " + planet.name() + " (" + planet.kind().name() + ")");
    }

    @Override
    public int getSpawnHeight(LevelHeightAccessor height) {
        return PlanetSurface.standingHeight(planet, 0, 0);
    }

    @Override
    public int getSeaLevel() {
        return PlanetSurface.SEA_LEVEL;
    }

    @Override
    public int getMinY() {
        return PlanetSurface.MIN_Y;
    }

    @Override
    public int getGenDepth() {
        return PlanetSurface.HEIGHT;
    }

    public static BlockState materialState(PlanetMaterial material) {
        return (switch(material) {
            case ANDESITE -> Blocks.ANDESITE; case DIORITE -> Blocks.DIORITE; case GRAVEL -> Blocks.GRAVEL;
            case RED_SAND -> Blocks.RED_SAND; case RED_SANDSTONE -> Blocks.RED_SANDSTONE; case TERRACOTTA -> Blocks.TERRACOTTA;
            case ORANGE_TERRACOTTA -> Blocks.DYED_TERRACOTTA.orange(); case SNOW -> Blocks.SNOW_BLOCK;
            case PACKED_ICE -> Blocks.PACKED_ICE; case BLUE_ICE -> Blocks.BLUE_ICE; case BASALT -> Blocks.BASALT;
            case BLACKSTONE -> Blocks.BLACKSTONE; case GRASS -> Blocks.GRASS_BLOCK; case DIRT -> Blocks.DIRT;
            case SAND -> Blocks.SAND; case WATER -> Blocks.WATER; case LAVA -> Blocks.LAVA;
            case DIAMOND -> Blocks.DIAMOND_BLOCK; case GOLD -> Blocks.GOLD_BLOCK; case IRON -> Blocks.IRON_BLOCK;
            case NETHERITE -> Blocks.NETHERITE_BLOCK; case PLANKS -> Blocks.OAK_PLANKS; case COBBLESTONE -> Blocks.COBBLESTONE;
            case TUFF -> Blocks.TUFF; case CALCITE -> Blocks.CALCITE; case MUD -> Blocks.MUD; case MAGMA -> Blocks.MAGMA_BLOCK;
            case BRICKS -> Blocks.BRICKS; case SUN_PLASMA -> Blocks.GLOWSTONE; case WHITE_CONCRETE -> Blocks.CONCRETE.white(); default -> Blocks.STONE;
        }).defaultBlockState();
    }
}
