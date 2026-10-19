package dev.seamlessspace.world;

import dev.seamlessspace.universe.Planet;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.LoggerFactory;

/** Bounded, disposable orbital LOD of actual saved blocks. Never generates a chunk. */
public final class OrbitalSurfaceCache {
    private static final int MAGIC = 0x53534c34, MAX_TILES = 4096;
    private static final int MATERIAL_COUNT=PlanetMaterial.values().length;
    private static final Map<MinecraftServer, Cache> CACHES = new ConcurrentHashMap<>();
    private static boolean initialized;
    private record CaptureRequest(ServerLevel level,int x,int z){}
    private static final Map<MinecraftServer, java.util.LinkedHashSet<CaptureRequest>> QUEUED=new ConcurrentHashMap<>();
    private OrbitalSurfaceCache() { }

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            var requests=QUEUED.get(server);
            if(requests!=null)for(int budget=0;budget<2 && !requests.isEmpty();budget++){
                var it=requests.iterator();var request=it.next();it.remove();
                capture(server,request.level,request.x,request.z);
            }
            // One loaded chunk per player/tick, distributed across a 9x9 area.
            // Edits refresh within four seconds without synchronous world generation.
            int cursor = Math.floorMod(server.getTickCount(), 81);
            for (var player : server.getPlayerList().getPlayers()) {
                if (!player.isSpectator() && player.getY() < player.level().getMaxY()+128) {
                    int cx = player.blockPosition().getX() >> 4, cz = player.blockPosition().getZ() >> 4;
                    capture(server, player.level(), cx + cursor % 9 - 4, cz + cursor / 9 - 4);
                }
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            QUEUED.remove(server);
            Cache cache = CACHES.remove(server);
            if (cache != null) save(cache);
        });
    }

    /** Bounded server-tick work: snapshots never block one frame for a whole landing area. */
    public static void queueRegion(MinecraftServer server,ServerLevel level,int cx,int cz,int radius){
        if(!server.isSameThread())throw new IllegalStateException("Capture queue requires server thread");
        var queue=QUEUED.computeIfAbsent(server,key->new java.util.LinkedHashSet<>());
        for(int ring=0;ring<=radius;ring++)for(int dz=-ring;dz<=ring;dz++)for(int dx=-ring;dx<=ring;dx++){
            if(Math.max(Math.abs(dx),Math.abs(dz))!=ring)continue;
            if(queue.size()>=512)queue.remove(queue.iterator().next());
            queue.add(new CaptureRequest(level,cx+dx,cz+dz));
        }
    }

    public static void captureRegion(MinecraftServer server, ServerLevel level, int cx, int cz, int radius) {
        for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++)
            capture(server, level, cx + dx, cz + dz);
    }

    public static void capture(MinecraftServer server, ServerLevel level, int cx, int cz) {
        if (!server.isSameThread()) throw new IllegalStateException("LOD capture requires the server thread");
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) return;
        short[] heights = new short[256];
        int[] colors = new int[256];
        byte[] materials = new byte[256];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int i = z * 16 + x;
            int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            heights[i] = (short) (y + 1);
            pos.set(cx * 16 + x, y, cz * 16 + z);
            var state = chunk.getBlockState(pos);
            colors[i] = state.getMapColor(level, pos).col;
            var material=classify(state);materials[i]=(byte)material.ordinal();
            if(material==PlanetMaterial.WATER)colors[i]=level.getBiome(pos).value().getWaterColor();
            if(material==PlanetMaterial.GRASS)colors[i]=level.getBiome(pos).value().getGrassColor(pos.getX(),pos.getZ());
            if (colors[i] == 0 && !state.isAir()) colors[i] = 0x68676a;
        }
        Cache cache = cache(server);
        Key key = new Key(level.dimension().identifier().toString(), cx, cz);
        Tile old = cache.tiles.get(key);
        if (old != null && Arrays.equals(old.heights, heights) && Arrays.equals(old.colors, colors) && Arrays.equals(old.materials,materials)) return;
        long revision = cache.revision+1;
        if (old == null && cache.tiles.size() >= MAX_TILES) {
            cache.tiles.entrySet().stream().min(Comparator.comparingLong(e -> e.getValue().revision))
                    .ifPresent(e -> cache.tiles.remove(e.getKey()));
        }
        cache.tiles.put(key, new Tile(cx, cz, heights, colors, materials, revision));
        cache.revision=revision;
    }

    public static Integer heightAt(MinecraftServer server,Planet planet,int x,int z) {
        var cache=CACHES.get(server);if(cache==null)return null;
        var tile=cache.tiles.get(new Key(PlanetWorlds.dimensionKey(planet).identifier().toString(),x>>4,z>>4));
        return tile==null ? null : tile.height(x&15,z&15);
    }

    /** Immutable tile objects; the client never reads live server chunks. */
    public static List<Tile> tiles(MinecraftServer server, Planet planet) {
        Cache cache = CACHES.get(server);
        if (cache == null) return List.of();
        String dimension = PlanetWorlds.dimensionKey(planet).identifier().toString();
        long revision=cache.revision;
        Snapshot previous=cache.views.get(dimension);
        if(previous!=null && previous.revision==revision) return previous.tiles;
        var tiles=cache.tiles.entrySet().stream().filter(e -> e.getKey().dimension.equals(dimension))
                .map(Map.Entry::getValue).sorted(Comparator.comparingLong(Tile::revision).reversed()).limit(MAX_TILES).toList();
        if(previous!=null && previous.tiles.equals(tiles))tiles=previous.tiles;
        cache.views.put(dimension,new Snapshot(revision,tiles));
        return tiles;
    }

    private static final Map<net.minecraft.world.level.block.Block,PlanetMaterial> BLOCK_MATERIALS=new java.util.IdentityHashMap<>();
    static {
        for(var material:PlanetMaterial.values()) if(material!=PlanetMaterial.COLOR)
            BLOCK_MATERIALS.put(PlanetChunkGenerator.materialState(material).getBlock(),material);
    }
    private static PlanetMaterial classify(net.minecraft.world.level.block.state.BlockState state) {
        var custom=PlanetBlocks.material(state.getBlock());
        return custom!=null ? custom : BLOCK_MATERIALS.getOrDefault(state.getBlock(),PlanetMaterial.COLOR);
    }

    private static Cache cache(MinecraftServer server) {
        return CACHES.computeIfAbsent(server, value -> {
            Cache result = new Cache(server.getWorldPath(LevelResource.DATA).resolve("seamlessspace-orbital-lod.bin"));
            if (Files.isRegularFile(result.file)) try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(result.file)))) {
                int magic=input.readInt();
                if(magic!=MAGIC && magic!=0x53534c33) return result;
                int count = input.readInt();
                if (count < 0 || count > MAX_TILES) return result;
                for (int tile = 0; tile < count; tile++) {
                    String dimension = input.readUTF();
                    int x = input.readInt(), z = input.readInt();
                    short[] heights = new short[256]; int[] colors = new int[256]; byte[] materials=new byte[256];
                    for (int i = 0; i < 256; i++) { heights[i] = input.readShort(); colors[i] = input.readInt();
                        materials[i]=magic==MAGIC ? input.readByte() : 0;
                        if(Byte.toUnsignedInt(materials[i])>=MATERIAL_COUNT) materials[i]=0;
                    }
                    result.tiles.put(new Key(dimension, x, z), new Tile(x, z, heights, colors, materials, ++result.revision));
                }
            } catch (IOException failure) {
                // This is a disposable render cache, never the authoritative world save.
                result.tiles.clear();
                LoggerFactory.getLogger("seamlessspace").warn("Rebuilding orbital LOD cache", failure);
            }
            return result;
        });
    }

    private static void save(Cache cache) {
        Path temporary = cache.file.resolveSibling(cache.file.getFileName() + ".tmp");
        try {
            Files.createDirectories(cache.file.getParent());
            try (var output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeInt(MAGIC); output.writeInt(cache.tiles.size());
                for (var entry : cache.tiles.entrySet()) {
                    output.writeUTF(entry.getKey().dimension); Tile tile = entry.getValue();
                    output.writeInt(tile.chunkX); output.writeInt(tile.chunkZ);
                    for (int i = 0; i < 256; i++) { output.writeShort(tile.heights[i]); output.writeInt(tile.colors[i]); output.writeByte(tile.materials[i]); }
                }
            }
            try { Files.move(temporary, cache.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, cache.file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException failure) { LoggerFactory.getLogger("seamlessspace").warn("Could not save orbital LOD", failure); }
    }

    public static final class Tile {
        public final int chunkX, chunkZ;
        private final short[] heights;
        private final int[] colors;
        private final byte[] materials;
        private final long revision;
        private Tile(int x, int z, short[] heights, int[] colors, byte[] materials, long revision) {
            this.chunkX = x; this.chunkZ = z; this.heights = heights; this.colors = colors; this.materials=materials; this.revision = revision;
        }
        public int height(int x, int z) { return heights[z * 16 + x]; }
        public int color(int x, int z) { return colors[z * 16 + x]; }
        public int material(int x,int z) { return Byte.toUnsignedInt(materials[z*16+x]); }
        public long revision() { return revision; }
    }
    private record Snapshot(long revision,List<Tile> tiles) { }
    private record Key(String dimension, int x, int z) { }
    private static final class Cache {
        final Path file;
        final Map<Key, Tile> tiles = new ConcurrentHashMap<>();
        volatile long revision;
        final Map<String,Snapshot> views=new ConcurrentHashMap<>();
        Cache(Path file) { this.file = file; }
    }
}
