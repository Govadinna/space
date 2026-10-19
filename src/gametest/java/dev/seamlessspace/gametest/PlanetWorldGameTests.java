package dev.seamlessspace.gametest;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import dev.seamlessspace.universe.DVec3;
import dev.seamlessspace.universe.Planet;
import dev.seamlessspace.world.PlanetChunkGenerator;
import dev.seamlessspace.world.PlanetSurface;
import dev.seamlessspace.world.PlanetWorlds;
import dev.seamlessspace.world.OrbitalSurfaceCache;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;

/** Runs on Fabric's real headless GameTest server, without a dedicated-server EULA file. */
public final class PlanetWorldGameTests {
    @GameTest(maxTicks=400)
    public void lunarTubeHasPhysicalAirRoofFloorAndOpenEntrance(GameTestHelper helper) {
        var server=helper.getLevel().getServer();var moon=new dev.seamlessspace.universe.Universe(7042026,1,true,true).homeSystem().get(1);
        var level=PlanetWorlds.ensureLevel(server,moon);level.getChunk(5,14);level.getChunk(6,14);
        int ground=PlanetSurface.height(moon,80,225);
        var cave=dev.seamlessspace.world.PlanetGeology.cavity(moon,80,225,ground);
        for(int y=cave.minY();y<=cave.maxY();y++)helper.assertTrue(level.getBlockState(new BlockPos(80,y,225)).isAir(),"tube interior must be actual air blocks");
        helper.assertTrue(!level.getBlockState(new BlockPos(80,cave.minY()-1,225)).isAir(),"tube must have a solid floor");
        helper.assertTrue(!level.getBlockState(new BlockPos(80,cave.maxY()+1,225)).isAir(),"tube must have a solid roof");
        int bottom=PlanetSurface.height(moon,96,225);
        helper.assertTrue(level.getBlockState(new BlockPos(96,bottom+10,225)).isAir(),"sinkhole must be open from the sky");
        helper.assertTrue(level.getHeight(Heightmap.Types.WORLD_SURFACE,96,225)==bottom+1,"orbital entrance height must match native chunk height");
        helper.succeed();
    }
    @GameTest(maxTicks=400)
    public void namedGeologyUsesActualIceMagmaCalciteAndSunspots(GameTestHelper helper) {
        var server=helper.getLevel().getServer();var u=new dev.seamlessspace.universe.Universe(7042026,1,true,true);
        for(var body:new Planet.Body[]{Planet.Body.MOON,Planet.Body.MERCURY,Planet.Body.VENUS,Planet.Body.MARS,Planet.Body.SUN}) {
            var p=u.homeSystem().stream().filter(q->q.body()==body).findFirst().orElseThrow();
            int x=body==Planet.Body.MERCURY?400:body==Planet.Body.VENUS?250:0;
            int z=body==Planet.Body.MOON?(int)(p.radius()*1.45):body==Planet.Body.MARS?(int)(p.radius()*1.5):0;
            var level=PlanetWorlds.ensureLevel(server,p);var pos=new BlockPos(x,PlanetSurface.height(p,x,z),z);level.getChunkAt(pos);
            var material=dev.seamlessspace.world.PlanetBlocks.material(level.getBlockState(pos).getBlock());
            boolean good=switch(body) {
                case MOON -> material==dev.seamlessspace.world.PlanetMaterial.PACKED_ICE;
                case MERCURY -> material==dev.seamlessspace.world.PlanetMaterial.CALCITE;
                case VENUS -> material==dev.seamlessspace.world.PlanetMaterial.MAGMA;
                case MARS -> material==dev.seamlessspace.world.PlanetMaterial.SNOW || material==dev.seamlessspace.world.PlanetMaterial.PACKED_ICE;
                case SUN -> material==dev.seamlessspace.world.PlanetMaterial.BLACKSTONE;
                default -> false;
            };
            helper.assertTrue(good,body+" must use physical feature materials");
        }
        var ice=u.homeSystem().stream().filter(p->p.body()==Planet.Body.NEPTUNE).findFirst().orElseThrow();
        BlockPos crystal=null;
        search:for(int z=0;z<128;z++)for(int x=0;x<128;x++) {
            int ground=PlanetSurface.height(ice,x,z);
            if(dev.seamlessspace.world.PlanetGeology.spike(ice,x,z).height()>15
                    && dev.seamlessspace.world.PlanetMaterials.top(ice,x,z,ground)==dev.seamlessspace.world.PlanetMaterial.SNOW) {
                crystal=new BlockPos(x,ground,z);break search;
            }
        }
        helper.assertTrue(crystal!=null,"a broad snow-capped crystal must exist");
        var iceLevel=PlanetWorlds.ensureLevel(server,ice);iceLevel.getChunkAt(crystal);
        helper.assertTrue(dev.seamlessspace.world.PlanetBlocks.material(iceLevel.getBlockState(crystal).getBlock())==dev.seamlessspace.world.PlanetMaterial.SNOW,"crystal shoulder must be actual snow");
        helper.assertTrue(iceLevel.getBlockState(crystal.below(3)).is(Blocks.BLUE_ICE),"crystal sides must contain actual blue ice");
        helper.succeed();
    }
    @GameTest(maxTicks=1200)
    public void earthAtlasReachesHighResolutionAndPersists(GameTestHelper helper) {
        helper.succeedWhen(()->{
            var server=helper.getLevel().getServer();var image=dev.seamlessspace.world.EarthPreview.image(server);
            helper.assertTrue(image!=null && image.size()==2048,"global atlas must refine beyond 256 to 2048");
            helper.assertTrue(image.seed()==server.overworld().getSeed(),"atlas must use save seed");
            var file=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.DATA).resolve("seamlessspace-earth-preview-v2.bin");
            try(var in=new java.io.DataInputStream(java.nio.file.Files.newInputStream(file))) {
                in.readInt();in.readLong();in.readInt();helper.assertTrue(in.readInt()==2048,"high-resolution atlas must be persisted");
            } catch(java.io.IOException error){helper.assertTrue(false,"atlas file not ready");}
        });
    }
    @GameTest(maxTicks=300)
    public void actualEntityGravityChangesOnMoonMarsAndJupiter(GameTestHelper helper) {
        var server=helper.getLevel().getServer();var u=new dev.seamlessspace.universe.Universe(7042026,1,true,true);
        double[] speeds=new double[3];int i=0;
        for(var body:new Planet.Body[]{Planet.Body.MOON,Planet.Body.MARS,Planet.Body.JUPITER}) {
            var planet=u.homeSystem().stream().filter(p->p.body()==body).findFirst().orElseThrow();
            var level=PlanetWorlds.ensureLevel(server,planet);level.getChunk(0,0);
            var item=new net.minecraft.world.entity.item.ItemEntity(level,0,1000,0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE));
            item.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);item.tick();speeds[i++]=-item.getDeltaMovement().y;
            helper.assertTrue(Math.abs(-item.getDeltaMovement().y-.04*dev.seamlessspace.world.PlanetPhysics.gravity(planet)*.98)<.00001,"native item gravity must match the planet multiplier");
            item.discard();
        }
        helper.assertTrue(speeds[0]<speeds[1] && speeds[1]<speeds[2],"Moon falls slower than Mars, Jupiter faster");helper.succeed();
    }
    @GameTest(maxTicks=300)
    public void solarWorldsUseDifferentTerrainAndSunContainsLuminousBlocks(GameTestHelper helper) {
        var server=helper.getLevel().getServer();var universe=new dev.seamlessspace.universe.Universe(7042026,1,true,true);
        for(var planet:universe.homeSystem())if(!planet.isHome()) {
            var level=PlanetWorlds.ensureLevel(server,planet);level.getChunk(0,0);
            int ground=PlanetSurface.height(planet,3,5);
            var state=level.getBlockState(new BlockPos(3,ground,5));
            helper.assertTrue(!state.isAir(),planet.name()+" must contain solid terrain");
            if(planet.body()==Planet.Body.SUN) {
                helper.assertTrue(!state.is(Blocks.GLOWSTONE),"Sun now uses granules and dark sunspot blocks");
                helper.assertTrue(level.getBlockState(new BlockPos(3,ground-2,5)).getLightEmission()==15,"Solar layer below the sunspot must be luminous plasma");
            }
        }
        helper.succeed();
    }
    @GameTest(maxTicks=300)
    public void earthPreviewUsesWorldGeneratorSeedAndDoesNotGenerateRemoteChunks(GameTestHelper helper) {
        var level=helper.getLevel().getServer().overworld();var chunk=level.getChunkSource();
        int x=37501,z=-41633;
        helper.assertTrue(chunk.getChunkNow(x>>4,z>>4)==null,"remote preview fixture must be unloaded");
        int first=dev.seamlessspace.world.EarthPreview.sample(level,x,z);
        int second=dev.seamlessspace.world.EarthPreview.sample(level,x,z);
        helper.assertTrue(first==second,"the same generator and seed must produce the same map sample");
        int height=chunk.getGenerator().getBaseHeight(x,z,Heightmap.Types.WORLD_SURFACE_WG,level,chunk.randomState());
        int encoded=(int)Math.round(Math.clamp((height+64)*255.0/512,0,255));
        helper.assertTrue((first>>>24)==encoded,"map height must come from the exact world generator");
        helper.assertTrue(chunk.getChunkNow(x>>4,z>>4)==null,"preview must not generate a chunk");
        helper.succeed();
    }

    @GameTest(maxTicks=200)
    public void buildAboveTwoThousandOnHomeAndPlanetAndPersistTheTopBlock(GameTestHelper helper) throws Exception {
        var server=helper.getLevel().getServer();var planet=planet(Planet.Kind.ROCKY);
        for(var level:java.util.List.of(server.overworld(),PlanetWorlds.ensureLevel(server,planet))) {
            helper.assertTrue(level.getMaxY()==2031,"build ceiling must be Y=2031, including the Overworld");
            var roof=new BlockPos(500,2031,500);level.getChunkAt(roof);
            level.setBlock(roof,Blocks.AIR.defaultBlockState(),3);
            helper.assertTrue(level.setBlock(roof,Blocks.DIAMOND_BLOCK.defaultBlockState(),3),"top build layer must accept blocks");
            helper.assertTrue(level.getBlockState(roof).is(Blocks.DIAMOND_BLOCK),"top block must exist");
            var upper=new BlockPos(501,2000,500);
            level.setBlock(upper,Blocks.GOLD_BLOCK.defaultBlockState(),3);
            helper.assertTrue(level.getBlockState(upper).is(Blocks.GOLD_BLOCK),"building at Y=2000 must work");
            if(level.dimension().equals(PlanetWorlds.dimensionKey(planet))) {
                OrbitalSurfaceCache.capture(server,level,roof.getX()>>4,roof.getZ()>>4);
                var tile=OrbitalSurfaceCache.tiles(server,planet).stream().filter(t->t.chunkX==(roof.getX()>>4) && t.chunkZ==(roof.getZ()>>4)).findFirst().orElseThrow();
                helper.assertTrue(tile.height(roof.getX()&15,roof.getZ()&15)==2032,"orbital cache must retain high buildings");
            }
            var encoded=SerializableChunkData.copyOf(level,level.getChunkAt(roof));
            var file=Files.createTempFile(Path.of("."),"high-planet-building-",".nbt");
            try {
                NbtIo.writeCompressed(encoded.write(),file);
                var decoded=SerializableChunkData.parse(level,level.palettedContainerFactory(),NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap()));
                helper.assertTrue(savedBlock(decoded,roof).is(Blocks.DIAMOND_BLOCK),"Y=2031 must survive chunk serialization");
            } finally {Files.deleteIfExists(file);}
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 200)
    public void orbitalLodUsesRealRoofHeightAndColorWithoutGeneratingUnloadedChunks(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var planet = planet(Planet.Kind.ROCKY);
        var level = PlanetWorlds.ensureLevel(server, planet);
        BlockPos roof = new BlockPos(136, 300, -120);
        level.getChunkAt(roof);
        level.setBlock(roof, Blocks.DIAMOND_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
        OrbitalSurfaceCache.capture(server, level, roof.getX()>>4, roof.getZ()>>4);
        var tile = OrbitalSurfaceCache.tiles(server,planet).stream()
                .filter(t -> t.chunkX==(roof.getX()>>4) && t.chunkZ==(roof.getZ()>>4)).findFirst().orElseThrow();
        helper.assertTrue(tile.height(roof.getX()&15,roof.getZ()&15)==301, "Orbital geometry must include the player's roof");
        helper.assertTrue(tile.color(roof.getX()&15,roof.getZ()&15)==Blocks.DIAMOND_BLOCK.defaultBlockState().getMapColor(level,roof).col,
                "Orbital appearance must use real block colors");
        OrbitalSurfaceCache.capture(server,level,100_000,100_000);
        helper.assertTrue(level.getChunkSource().getChunkNow(100_000,100_000)==null,"LOD capture must not force generation");
        helper.succeed();
    }
    @GameTest(maxTicks = 200)
    public void everyPlanetKindGeneratesRealChunks(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Set<ServerLevel> worlds = new HashSet<>();
        for (Planet.Kind kind : Planet.Kind.values()) {
            Planet planet = planet(kind);
            ServerLevel level = PlanetWorlds.ensureLevel(server, planet);
            helper.assertTrue(worlds.add(level), "Each planet must have its own ServerLevel");
            helper.assertTrue(server.getLevel(level.dimension()) == level,
                    "Planet world must be registered in the server's dimension map");
            helper.assertTrue(planet.equals(PlanetWorlds.planetForLevel(server, level.dimension())),
                    "Planet descriptor must be recoverable from the dimension");
            LevelChunk chunk = level.getChunk(-2, 3);
            int x = -27;
            int z = 53;
            int ground = PlanetSurface.height(planet, x, z);
            helper.assertTrue(chunk.getBlockState(new BlockPos(x, PlanetSurface.MIN_Y, z)).is(Blocks.BEDROCK),
                    kind + " needs a solid bottom");
            helper.assertTrue(!level.getBlockState(new BlockPos(x, ground, z)).isAir(),
                    kind + " terrain must consist of real block states");
            helper.assertTrue(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z)
                            == PlanetSurface.standingHeight(planet, x, z),
                    kind + " chunk heightmap must agree with landing height");
            helper.assertTrue(level.getBlockState(new BlockPos(x, ground - 8, z)).isSolid(),
                    kind + " terrain must have mineable depth");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 200)
    public void editableBlocksAreIsolatedAndSurviveChunkSerialization(GameTestHelper helper) throws Exception {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel first = PlanetWorlds.ensureLevel(server, planet(Planet.Kind.ROCKY));
        ServerLevel second = PlanetWorlds.ensureLevel(server, planet(Planet.Kind.DESERT));
        BlockPos build = new BlockPos(136, 300, -120);
        first.getChunkAt(build);
        second.getChunkAt(build);
        first.setBlock(build, Blocks.DIAMOND_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
        second.setBlock(build, Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
        helper.assertTrue(first.getBlockState(build).is(Blocks.DIAMOND_BLOCK), "Planet one must accept placed blocks");
        helper.assertTrue(second.getBlockState(build).is(Blocks.GOLD_BLOCK), "Planet two must keep independent blocks");
        int ground = PlanetSurface.height(planet(Planet.Kind.ROCKY), build.getX(), build.getZ());
        BlockPos mine = new BlockPos(build.getX(), ground, build.getZ());
        // The same save is deliberately reused by the restart test; restore this mining fixture.
        first.setBlock(mine, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        helper.assertTrue(first.destroyBlock(mine, false), "Generated planet blocks must be breakable");
        helper.assertTrue(first.getBlockState(mine).isAir(), "Mining must change the real world");
        first.getChunkSource().save(true);
        second.getChunkSource().save(true);

        // Use Minecraft's exact save codec, gzip bytes and load codec, including palette and biome data.
        LevelChunk chunk = first.getChunkAt(build);
        SerializableChunkData saved = SerializableChunkData.copyOf(first, chunk);
        Path serialized = Files.createTempFile(Path.of("."), "planet-chunk-roundtrip-", ".nbt");
        try {
            NbtIo.writeCompressed(saved.write(), serialized);
            SerializableChunkData restored = SerializableChunkData.parse(first, first.palettedContainerFactory(),
                    NbtIo.readCompressed(serialized, NbtAccounter.unlimitedHeap()));
            helper.assertTrue(restored != null, "Saved voxel chunk must be readable");
            helper.assertTrue(savedBlock(restored, build).is(Blocks.DIAMOND_BLOCK),
                    "Placed block must survive save/load codecs");
            helper.assertTrue(savedBlock(restored, mine).isAir(), "Mined block must survive save/load codecs");
            helper.assertTrue(PlanetWorlds.ensureLevel(server, planet(Planet.Kind.ROCKY)) == first,
                    "Returning to a planet must reuse its existing persisted world");
        } finally {
            Files.deleteIfExists(serialized);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 200)
    public void generatorCodecKeepsPlanetIdentity(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Planet planet = planet(Planet.Kind.ICE);
        ServerLevel level = PlanetWorlds.ensureLevel(server, planet);
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        JsonElement encoded = ChunkGenerator.CODEC.encodeStart(ops, generator).getOrThrow();
        ChunkGenerator decoded = ChunkGenerator.CODEC.parse(ops, encoded).getOrThrow();
        helper.assertTrue(decoded instanceof PlanetChunkGenerator, "Saved generator must retain its registered type");
        helper.assertTrue(((PlanetChunkGenerator) decoded).planet().equals(planet),
                "Generator save/load must retain radius, center, kind and seed");
        helper.succeed();
    }

    @GameTest(maxTicks = 200)
    public void savedPlanetAndBuildingSurviveServerRestart(GameTestHelper helper) throws Exception {
        MinecraftServer server = helper.getLevel().getServer();
        Planet planet = new Planet("gametest/restart", "Restart verification", new DVec3(8_000_000, 5_000_000, 0),
                90_000, Planet.Kind.ROCKY, 78391);
        Path marker = Path.of("planet-restart-verification.txt");
        boolean previousRun = Files.exists(marker);
        if (previousRun) {
            helper.assertTrue(server.getLevel(PlanetWorlds.dimensionKey(planet)) != null,
                    "Saved dimensions must be restored when the server starts");
        }
        ServerLevel level = PlanetWorlds.ensureLevel(server, planet);
        BlockPos build = new BlockPos(248, 298, 248);
        level.getChunkAt(build);
        if (previousRun) {
            helper.assertTrue(level.getBlockState(build).is(Blocks.NETHERITE_BLOCK),
                    "Building must remain in the planet's chunk after a complete server restart");
        }
        level.setBlock(build, Blocks.NETHERITE_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.getChunkSource().save(true);
        Files.writeString(marker, previousRun ? "Verified: saved planet and building survived a complete server restart.\n"
                : "Run runGameTest again in the same directory to verify restart persistence.\n");
        helper.succeed();
    }

    @GameTest(maxTicks = 4000)
    public void landingPreloadsChunksAndRestoresInteractiveCreativeMode(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SPECTATOR);
        player.noPhysics = true;
        player.setNoGravity(true);
        player.getAbilities().flying = true;
        Planet planet = planet(Planet.Kind.ICE);
        ServerLevel destination = PlanetWorlds.ensureLevel(server, planet);
        destination.getChunk(0, 0);
        destination.setBlock(new BlockPos(0, destination.getMaxY(), 0), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        var landing = PlanetWorlds.land(server, player.getUUID(), planet, DVec3.UP);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(landing.isDone(), "Landing is still loading chunks"))
                .thenExecute(() -> {
                    try {
                        PlanetWorlds.LandingResult result = landing.join();
                        helper.assertTrue(player.level().dimension().equals(result.dimensionKey()),
                                "Landing must teleport the real server player into the planet dimension");
                        // The vanilla mock overrides gameMode(); inspect the real server game mode object.
                        helper.assertTrue(player.gameMode.getGameModeForPlayer() == GameType.CREATIVE,
                                "Landing must restore actual creative block interaction");
                        helper.assertTrue(!player.noPhysics, "Landing must restore block collision");
                        helper.assertTrue(!player.isNoGravity(), "Landing must restore ordinary gravity");
                        helper.assertTrue(!player.getAbilities().flying && player.getAbilities().mayfly,
                                "Landing must end space flight while keeping creative flight available");
                        helper.assertTrue(!player.level().getBlockState(player.blockPosition().below()).isAir(),
                                "Player must arrive above an already generated solid voxel surface");
                        helper.assertTrue(player.getY() >= destination.getMaxY() + 1,
                                "Landing must stay above saved buildings at the build limit");
                        helper.assertTrue(player.level().getChunkSource().getChunkNow(0, 0) != null,
                                "Landing chunk must be fully loaded before teleport completes");
                    } finally {
                        server.getPlayerList().remove(player);
                    }
                }).thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void creatingAPlanetDoesNotInvalidateAnActiveWorldTickIterator(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        var tickingWorlds = server.getAllLevels().iterator();
        helper.assertTrue(tickingWorlds.hasNext(), "Server must contain a world");
        tickingWorlds.next();
        // A unique descriptor ensures this really mutates the dimension map on every test run.
        Planet planet = new Planet("gametest/iteration/" + java.util.UUID.randomUUID(), "Iterator verification",
                new DVec3(19_000_000, 5_000_000, 0), 85_000, Planet.Kind.ROCKY, 871723);
        PlanetWorlds.ensureLevel(server, planet);
        tickingWorlds.forEachRemaining(level -> helper.assertTrue(level != null, "World iteration must stay valid"));
        helper.succeed();
    }

    private static net.minecraft.world.level.block.state.BlockState savedBlock(SerializableChunkData chunk, BlockPos pos) {
        return chunk.sectionData().stream().filter(section -> section.y() == (pos.getY() >> 4))
                .map(SerializableChunkData.SectionData::chunkSection).filter(section -> section != null)
                .findFirst().orElseThrow().getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    private static Planet planet(Planet.Kind kind) {
        return new Planet("gametest/" + kind.name().toLowerCase(java.util.Locale.ROOT), "Test " + kind,
                new DVec3(kind.ordinal() * 1_000_000.0, 500_000, -300_000),
                80_000, kind, 19371L + kind.ordinal() * 7907L);
    }
}
