package dev.seamlessspace.world;

import dev.seamlessspace.mixin.MinecraftServerAccessor;
import dev.seamlessspace.universe.DVec3;
import dev.seamlessspace.universe.Planet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/** Real dimensions, not rendered surfaces: vanilla chunks handle terrain, edits and saving. */
public final class PlanetWorlds {
    private static final ResourceKey<DimensionType> PLANET_TYPE = ResourceKey.create(
            Registries.DIMENSION_TYPE, Identifier.fromNamespaceAndPath("seamlessspace", "planet"));
    private static final Map<MinecraftServer, WorldIndex> INDEXES = new ConcurrentHashMap<>();
    public static final ResourceKey<Level> SPACE = ResourceKey.create(Registries.DIMENSION,Identifier.fromNamespaceAndPath("seamlessspace","space"));
    private static final Planet SPACE_DESCRIPTOR=new Planet("native-space","Космос",DVec3.ZERO,1,Planet.Kind.ROCKY,0);
    public static ServerLevel ensureSpace(MinecraftServer server){requireServerThread(server);return createLevel(server,SPACE_DESCRIPTOR,SPACE);}
    private static final int LANDING_RADIUS = 2;
    private static boolean initialized;
    private record MotionSample(ResourceKey<Level> dimension,Vec3 position){}
    private static final Map<UUID,MotionSample> MOTION=new ConcurrentHashMap<>();
    private static final Map<UUID, PreparedApproach> STREAMING = new ConcurrentHashMap<>();

    private PlanetWorlds() { }

    public static void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        // These levels must exist before player NBT is read, otherwise vanilla sends a returning
        // player to the Overworld and loses the dimension of their last saved position.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ensureSpace(server);
            WorldIndex index = index(server);
            for (Planet planet : List.copyOf(index.planets.values())) {
                createLevel(server, planet);
            }
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            if(server.getTickCount()%10!=0)return;
            for(var player:server.getPlayerList().getPlayers()) {
                PreparedApproach old=STREAMING.get(player.getUUID());
                var previous=MOTION.put(player.getUUID(),new MotionSample(player.level().dimension(),player.position()));
                var velocity=previous==null || !previous.dimension.equals(player.level().dimension()) ? Vec3.ZERO : player.position().subtract(previous.position).scale(.1);
                if(velocity.lengthSqr()>10000)velocity=Vec3.ZERO; // Dimension changes and teleports are not flight.
                boolean active=player.isCreative() && player.getAbilities().flying && !player.level().dimension().equals(SPACE)
                        && (player.level()==server.overworld() || planetForLevel(server,player.level().dimension())!=null)
                        && velocity.horizontalDistanceSqr()>.04;
                if(!active){if(old!=null){old.release();STREAMING.remove(player.getUUID());}continue;}
                // Half a second ahead, bounded to eight chunks: never generate a flight corridor.
                double length=Math.sqrt(velocity.horizontalDistanceSqr());
                double lead=Math.min(128,length*10);
                ChunkPos center=new ChunkPos(((int)Math.floor(player.getX()+velocity.x()/length*lead))>>4,
                        ((int)Math.floor(player.getZ()+velocity.z()/length*lead))>>4);
                if(old!=null && old.level()==player.level() && old.center().equals(center)){old.refresh();continue;}
                if(old!=null)old.release();
                var next=new PreparedApproach(player.level(),center,3);
                STREAMING.put(player.getUUID(),next);next.refresh();
            }
            STREAMING.entrySet().removeIf(entry->{if(server.getPlayerList().getPlayer(entry.getKey())!=null)return false;entry.getValue().release();MOTION.remove(entry.getKey());return true;});
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            STREAMING.entrySet().removeIf(entry->entry.getValue().level().getServer()==server);
            MOTION.keySet().removeIf(id->server.getPlayerList().getPlayer(id)!=null);
            WorldIndex saved = INDEXES.remove(server);
            if (saved != null) saved.planets.values().forEach(PlanetSurface::forgetProfile);
        });
    }

    public static ResourceKey<Level> dimensionKey(Planet planet) {
        if (planet.isHome()) {
            return Level.OVERWORLD;
        }
        return ResourceKey.create(Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("seamlessspace", PlanetWorldRegistry.dimensionPath(planet)));
    }

    /** Safe for the integrated client to read; all index mutations happen on the server thread. */
    public static Planet planetForLevel(MinecraftServer server, ResourceKey<Level> dimension) {
        WorldIndex index = INDEXES.get(server);
        return index == null ? null : index.planets.get(dimension);
    }
    public static List<Planet> savedPlanets(MinecraftServer server) {
        var index=INDEXES.get(server);
        return index==null ? List.of() : List.copyOf(index.planets.values());
    }

    public static Planet planetForDimension(ResourceKey<Level> dimension) {
        for(var index:INDEXES.values()) {var planet=index.planets.get(dimension);if(planet!=null)return planet;}
        return null;
    }

    /** Call on the server thread. Newly visited planets are indexed before a player can enter. */
    public static ServerLevel ensureLevel(MinecraftServer server, Planet planet) {
        requireServerThread(server);
        if (planet.isHome()) {
            return server.overworld();
        }
        WorldIndex index = index(server);
        ResourceKey<Level> key = dimensionKey(planet);
        Planet saved = index.planets.get(key);
        if (saved != null && !saved.equals(planet)) {
            throw new IllegalStateException("The saved planet " + planet.id()
                    + " has another descriptor. Restore the universe seed/scale used for this save.");
        }
        ServerLevel existing = server.getLevel(key);
        if (existing != null) {
            return existing;
        }
        if (saved == null) {
            index.planets.put(key, planet);
            try {
                PlanetWorldRegistry.write(index.file, index.planets.values());
            } catch (IOException failure) {
                index.planets.remove(key);
                throw new IllegalStateException("Cannot persist planet descriptor before landing", failure);
            }
        }
        return createLevel(server, planet);
    }

    /**
     * Generate only a small landing area, asynchronously. The caller may keep the space frame
     * visible until this future completes and the client has received its normal chunk packets.
     */
    public static CompletableFuture<LandingResult> land(MinecraftServer server, UUID playerId,
                                                         Planet planet, DVec3 radialDirection) {
        return arrive(server, playerId, planet, radialDirection, 0, Float.NaN, 0);
    }

    /** Prepare voxel chunks while the player is still free to fly in orbital space. */
    public static CompletableFuture<PreparedApproach> prepare(MinecraftServer server, Planet planet, DVec3 radial) {
        requireServerThread(server);
        ServerLevel level = ensureLevel(server, planet);
        var c = PlanetSurface.coordinates(planet, radial);
        double limit = server.getAbsoluteMaxWorldSize() - 32;
        ChunkPos center = new ChunkPos(((int) Math.floor(Math.clamp(c.x(), -limit, limit))) >> 4,
                ((int) Math.floor(Math.clamp(c.z(), -limit, limit))) >> 4);
        return level.getChunkSource().addTicketAndLoadWithRadius(TicketType.PORTAL, center, 6)
                .thenApplyAsync(ignored -> {
                    OrbitalSurfaceCache.captureRegion(server, level, center.x(), center.z(), 2);
                    OrbitalSurfaceCache.queueRegion(server, level, center.x(), center.z(), 6);
                    return new PreparedApproach(level, center);
                }, server);
    }

    public record PreparedApproach(ServerLevel level, ChunkPos center, int radius) {
        public PreparedApproach(ServerLevel level, ChunkPos center){this(level,center,6); }
        /** Refresh the expiring ticket on the server thread, or release it after departure. */
        public void refresh() { level.getChunkSource().addTicketWithRadius(TicketType.PORTAL, center, radius); }
        public void release() { level.getChunkSource().removeTicketWithRadius(TicketType.PORTAL, center, radius); }
    }

    /** Enter at the same altitude and local viewing direction, instead of snapping to the ground. */
    public static CompletableFuture<LandingResult> arrive(MinecraftServer server, UUID playerId,
            Planet planet, DVec3 radialDirection, double altitude, float yaw, float pitch) {
        requireServerThread(server);
        if (!Double.isFinite(altitude) || altitude < 0 || altitude > 8192)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid arrival altitude"));
        if (!radialDirection.isFinite() || radialDirection.lengthSquared() < 1.0e-12) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid landing direction"));
        }
        final ServerLevel level;
        try {
            level = ensureLevel(server, planet);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        PlanetSurface.SurfaceCoordinates coordinates = PlanetSurface.coordinates(planet, radialDirection.normalized());
        // Minecraft's horizontal build limit is finite even when the orbital coordinate system
        // uses doubles. At ordinary/default scale the projection is well inside this limit.
        double worldLimit = server.getAbsoluteMaxWorldSize() - 32.0;
        double preciseX=Math.clamp(coordinates.x(),-worldLimit,worldLimit);
        double preciseZ=Math.clamp(coordinates.z(),-worldLimit,worldLimit);
        int x = (int) Math.floor(preciseX);
        int z = (int) Math.floor(preciseZ);
        ChunkPos center = new ChunkPos(x >> 4, z >> 4);
        CompletableFuture<LandingResult> result = new CompletableFuture<>();
        final CompletableFuture<?> chunks;
        try {
            chunks = level.getChunkSource().addTicketAndLoadWithRadius(TicketType.PORTAL, center, LANDING_RADIUS);
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
            return result;
        }
        chunks.whenCompleteAsync((loaded, failure) -> {
            try {
                if (failure != null) {
                    result.completeExceptionally(failure);
                    return;
                }
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null) {
                    result.completeExceptionally(new IllegalStateException("Player disconnected before landing"));
                    return;
                }
                if (level.getChunkSource().getChunkNow(center.x(), center.z()) == null) {
                    result.completeExceptionally(new IllegalStateException("Landing chunk did not finish loading"));
                    return;
                }
                // Use the saved heightmap, including previously placed/removed blocks, rather
                // than a fresh procedural estimate of a surface the player might have edited.
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                // Players may stand above the build limit. Clamping below the top
                // block would put a returning visitor inside a saved tower/roof.
                y = Math.clamp(y, level.getMinY() + 1, level.getMaxY() + 1);
                player.stopRiding();
                player.setGameMode(GameType.CREATIVE);
                player.noPhysics = false;
                player.setNoGravity(false);
                player.setDeltaMovement(Vec3.ZERO);
                player.resetFallDistance();
                double base = planet.isHome() ? 64 : PlanetSurface.standingHeight(planet, x, z);
                double arrivalY = Math.max(y + 0.05, base + altitude + 0.05);
                boolean teleported = player.teleportTo(level, preciseX, arrivalY, preciseZ,
                        Set.of(), Float.isFinite(yaw) ? yaw : player.getYRot(), pitch, true);
                if (!teleported) {
                    result.completeExceptionally(new IllegalStateException("Planet teleport was rejected"));
                    return;
                }
                player.getAbilities().flying = altitude > 2;
                player.getAbilities().mayfly = true;
                player.onUpdateAbilities();
                OrbitalSurfaceCache.captureRegion(server, level, center.x(), center.z(), LANDING_RADIUS);
                result.complete(new LandingResult(level.dimension(), new DVec3(preciseX, arrivalY, preciseZ), planet));
            } catch (RuntimeException landingFailure) {
                result.completeExceptionally(landingFailure);
            } finally {
                level.getChunkSource().removeTicketWithRadius(TicketType.PORTAL, center, LANDING_RADIUS);
            }
        }, server);
        return result;
    }

    private static ServerLevel createLevel(MinecraftServer server, Planet planet) {
        requireServerThread(server);
        return createLevel(server,planet,dimensionKey(planet));
    }
    private static ServerLevel createLevel(MinecraftServer server,Planet planet,ResourceKey<Level> key) {
        requireServerThread(server);
        ServerLevel existing=server.getLevel(key);if(existing!=null)return existing;
        MinecraftServerAccessor access = (MinecraftServerAccessor) server;
        var registryAccess = server.registryAccess();
        var type = registryAccess.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(PLANET_TYPE);
        var biome = registryAccess.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.THE_VOID);
        var data = new DerivedLevelData(server.getWorldData(), server.getWorldData().overworldData());
        var stem = new LevelStem(type, new PlanetChunkGenerator(planet, biome));
        ServerLevel level = new ServerLevel(server, access.seamlessspace$executor(), access.seamlessspace$storageSource(),
                data, key, stem, false, BiomeManager.obfuscateSeed(planet.seed()), List.of(), false);
        level.getWorldBorder().setAbsoluteMaxSize(server.getAbsoluteMaxWorldSize());
        access.seamlessspace$levels().put(key, level);
        server.getPlayerList().addWorldborderListener(level);
        ServerLevelEvents.LOAD.invoker().onLevelLoad(server, level);
        return level;
    }

    private static WorldIndex index(MinecraftServer server) {
        return INDEXES.computeIfAbsent(server, value -> {
            Path file = server.getWorldPath(LevelResource.DATA).resolve("seamlessspace-planets.json");
            WorldIndex index = new WorldIndex(file);
            try {
                for (Planet planet : PlanetWorldRegistry.read(file)) {
                    if (planet.isHome() || index.planets.putIfAbsent(dimensionKey(planet), planet) != null) {
                        throw new IOException("Duplicate or home planet in " + file);
                    }
                }
            } catch (IOException failure) {
                // Silently dropping the index would strand saved players and orphan their builds.
                throw new IllegalStateException("Cannot restore saved planet dimensions", failure);
            }
            return index;
        });
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Planet world changes must run on the Minecraft server thread");
        }
    }

    public record LandingResult(ResourceKey<Level> dimensionKey, DVec3 worldPosition, Planet planet) { }

    private static final class WorldIndex {
        final Path file;
        final Map<ResourceKey<Level>, Planet> planets = new ConcurrentHashMap<>();

        WorldIndex(Path file) {
            this.file = file;
        }
    }
}
