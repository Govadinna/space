package dev.seamlessspace.world;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.tags.BiomeTags;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.core.BlockPos;

/** Seed-correct base-height/biome preview, without creating or saving actual chunks. */
public final class EarthPreview {
    public static final int SPAN=100000;
    public static final int MAX_SIZE=2048;
    public record Detail(int x,int z,int span,Image image) { }
    private record Request(int x,int z,int span) { }
    public record Image(int size,int[] argb,long seed) { }
    private static final int MAGIC=0x53455035;
    private static final Map<MinecraftServer,State> STATES=new ConcurrentHashMap<>();
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"Earth seed preview");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;});
    private static final ExecutorService DETAIL_WORKER=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"Earth local detail");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;});
    private EarthPreview() { }
    public static void initialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(EarthPreview::request);
        ServerLifecycleEvents.SERVER_STOPPED.register(s->{var state=STATES.remove(s);if(state!=null)state.cancelled=true;});
    }
    public static Image image(MinecraftServer server) {var s=server==null?null:STATES.get(server);return s==null?null:s.image;}
    public static Detail detail(MinecraftServer server) {var s=server==null?null:STATES.get(server);return s==null?null:s.detail;}
    /** A small, high-density map under the camera complements the global atlas. */
    public static void requestDetail(MinecraftServer server,double x,double z,double altitude) {
        var state=server==null?null:STATES.get(server);if(state==null || state.cancelled || altitude>40000)return;
        int span=1024;while(span<65536 && span<altitude*3)span*=2;
        int quantum=span/4;
        var request=new Request((int)Math.floor(x/quantum+.5)*quantum,(int)Math.floor(z/quantum+.5)*quantum,span);
        var old=state.desired;
        if(old!=null && old.span==span && Math.abs(x-old.x)<quantum*.75 && Math.abs(z-old.z)<quantum*.75)request=old;
        state.desired=request;
        if(request.equals(state.processed) || !state.detailBusy.compareAndSet(false,true))return;
        DETAIL_WORKER.execute(()->{
            try {
                var sampler=new Sampler(server.overworld());
                while(!state.cancelled) {
                    var target=state.desired;if(target==null || target.equals(state.processed))break;
                    var cached=state.details.get(target);
                    if(cached!=null){state.detail=cached;state.processed=target;continue;}
                    boolean completed=true;Image previous=null;
                    int exactLimit=target.span<=2048?256:128;
                    for(int n:new int[]{32,64,128,256,512}) {
                        int[] pixels=new int[n*n];
                        for(int row=0;row<n;row++) {
                            if(state.cancelled || !target.equals(state.desired)){completed=false;break;}
                            for(int col=0;col<n;col++){
                                int wx=target.x+(int)((col+.5)*target.span/n-target.span*.5),wz=target.z+(int)((row+.5)*target.span/n-target.span*.5);
                                pixels[row*n+col]=n>exactLimit && previous!=null?sampler.sampleFineBiome(wx,wz,previous,target.x,target.z,target.span):sampler.sample(wx,wz);
                            }
                        }
                        if(!completed)break;
                        previous=new Image(n,pixels,server.overworld().getSeed());
                        state.detail=new Detail(target.x,target.z,target.span,previous);
                    }
                    if(completed) {
                        state.processed=target;state.details.put(target,state.detail);
                        if(state.details.size()>8)state.details.remove(state.details.keySet().iterator().next());
                    }
                }
            } catch(Exception error){org.slf4j.LoggerFactory.getLogger("seamlessspace").warn("Earth detail sampling stopped",error);}
            finally{state.detailBusy.set(false);}
        });
    }
    public static double heightOffset(MinecraftServer server,dev.seamlessspace.universe.Planet earth,dev.seamlessspace.universe.DVec3 direction) {
        if(server==null)return 0;
        var c=PlanetSurface.coordinates(earth,direction);
        var exact=OrbitalSurfaceCache.heightAt(server,earth,(int)Math.floor(c.x()),(int)Math.floor(c.z()));
        if(exact!=null)return exact-64;
        return previewHeight(server,c.x(),c.z())-64;
    }

    /** A local chart fades into the global chart before its boundary, including height. */
    public static double previewHeight(MinecraftServer server,double x,double z) {
        var global=image(server);var patch=detail(server);
        double coarse=global==null?64:sampleHeight(global,x/SPAN+.5,z/SPAN+.5);
        if(patch==null)return coarse;
        double edge=Math.max(Math.abs(x-patch.x),Math.abs(z-patch.z))/patch.span;
        if(edge>=.46)return coarse;
        double t=Math.clamp((.46-edge)/.12,0,1);t=t*t*(3-2*t);
        return coarse+(sampleHeight(patch.image,(x-patch.x)/patch.span+.5,(z-patch.z)/patch.span+.5)-coarse)*t;
    }
    public static int previewPixel(MinecraftServer server,double x,double z) {
        return previewSample(server,x,z);
    }
    private static int previewSample(MinecraftServer server,double x,double z) {
        var global=image(server);var patch=detail(server);
        int coarse=global==null?0x40416c8d:sample(global,x/SPAN+.5,z/SPAN+.5);
        if(patch==null)return coarse;
        double edge=Math.max(Math.abs(x-patch.x),Math.abs(z-patch.z))/patch.span;
        if(edge>=.46)return coarse;
        int fine=sample(patch.image,(x-patch.x)/patch.span+.5,(z-patch.z)/patch.span+.5);
        double t=Math.clamp((.46-edge)/.12,0,1);t=t*t*(3-2*t);
        int result=0;
        for(int shift=0;shift<=24;shift+=8)result|=(int)Math.round(((coarse>>>shift)&255)*(1-t)+((fine>>>shift)&255)*t)<<shift;
        return result;
    }
    private static double sampleHeight(Image map,double sx,double sz) {
        double px=sx*map.size-.5,pz=sz*map.size-.5;
        int bx=(int)Math.floor(px),bz=(int)Math.floor(pz);double fx=px-bx,fz=pz-bz,h=0;
        for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++)
            h+=(map.argb[Math.clamp(bz+dz,0,map.size-1)*map.size+Math.clamp(bx+dx,0,map.size-1)]>>>24)*(dx==0?1-fx:fx)*(dz==0?1-fz:fz);
        return h*512.0/255-64;
    }
    private static int sample(Image map,double sx,double sz) {
        double px=sx*map.size-.5,pz=sz*map.size-.5;
        int bx=(int)Math.floor(px),bz=(int)Math.floor(pz);double fx=px-bx,fz=pz-bz;
        double alpha=0,red=0,green=0,blue=0;
        for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++) {
            int pixel=map.argb[Math.clamp(bz+dz,0,map.size-1)*map.size+Math.clamp(bx+dx,0,map.size-1)];
            double weight=(dx==0?1-fx:fx)*(dz==0?1-fz:fz);
            alpha+=(pixel>>>24)*weight;red+=((pixel>>16)&255)*weight;green+=((pixel>>8)&255)*weight;blue+=(pixel&255)*weight;
        }
        return (int)Math.round(alpha)<<24|(int)Math.round(red)<<16|(int)Math.round(green)<<8|(int)Math.round(blue);
    }
    private static void request(MinecraftServer server) {
        var state=new State();if(STATES.putIfAbsent(server,state)!=null)return;
        var level=server.overworld();var generator=level.getChunkSource().getGenerator();
        int signature=ChunkGenerator.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE,server.registryAccess()),generator).getOrThrow().toString().hashCode();
        Path file=server.getWorldPath(LevelResource.DATA).resolve("seamlessspace-earth-preview-v2.bin");
        WORKER.execute(()->{
            try {
                if(Files.isRegularFile(file)) try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
                    if(in.readInt()==MAGIC && in.readLong()==level.getSeed() && in.readInt()==signature) {
                        int n=in.readInt();if(n==32 || n==64 || n==128 || n==256 || n==512 || n==1024 || n==MAX_SIZE) {
                            int[] pixels=new int[n*n];for(int i=0;i<pixels.length;i++)pixels[i]=in.readInt();
                            state.image=new Image(n,pixels,level.getSeed());if(n==MAX_SIZE)return;
                        }
                    }
                }
            } catch(IOException ignored) { }
            try {
                var sampler=new Sampler(level);
                for(int n:new int[]{32,64,128,256,512,1024,MAX_SIZE}) {
                    if(state.image!=null && state.image.size>=n)continue;
                    int[] pixels=new int[n*n];
                    var previous=state.image;
                    for(int z=0;z<n;z++) {
                        if(state.cancelled)return;
                        for(int x=0;x<n;x++) {
                            int wx=(int)((x+.5)*SPAN/n-SPAN*.5),wz=(int)((z+.5)*SPAN/n-SPAN*.5);
                            pixels[z*n+x]=n>64 && previous!=null ? sampler.sampleFineBiome(wx,wz,previous) : sampler.sample(wx,wz);
                        }
                        Thread.yield();
                    }
                    if(state.cancelled)return;
                    state.image=new Image(n,pixels,level.getSeed());
                    if(n<=64)save(file,state.image,signature);
                }
                // Publish coastlines first. A separate height pass must not delay the readable globe.
                int[] exact=new int[512*512];
                for(int z=0;z<512;z++){
                    if(state.cancelled)return;
                    for(int x=0;x<512;x++)exact[z*512+x]=sampler.sample((int)((x+.5)*SPAN/512-SPAN*.5),(int)((z+.5)*SPAN/512-SPAN*.5));
                    Thread.yield();
                }
                var coarse=new Image(512,exact,level.getSeed());var colors=state.image;int[] refined=colors.argb.clone();
                for(int z=0;z<colors.size;z++)for(int x=0;x<colors.size;x++) {
                    int wx=(int)((x+.5)*SPAN/colors.size-SPAN*.5),wz=(int)((z+.5)*SPAN/colors.size-SPAN*.5);
                    int alpha=(int)Math.round(sampler.interpolatedAlpha(wx,wz,coarse,0,0,SPAN));
                    refined[z*colors.size+x]=alpha<<24|(refined[z*colors.size+x]&0xffffff);
                }
                if(state.cancelled)return;
                state.image=new Image(colors.size,refined,level.getSeed());save(file,state.image,signature);
            } catch(Throwable error) {org.slf4j.LoggerFactory.getLogger("seamlessspace").warn("Earth preview sampling stopped",error);}
        });
    }
    private static void save(Path file,Image image,int signature) throws IOException {
        Files.createDirectories(file.getParent());var tmp=file.resolveSibling(file.getFileName()+".tmp");
        try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeInt(MAGIC);out.writeLong(image.seed);out.writeInt(signature);out.writeInt(image.size);
            for(int pixel:image.argb)out.writeInt(pixel);
        }
        Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);
    }
    public static int sample(ServerLevel level,int x,int z) {
        return new Sampler(level).sample(x,z);
    }
    private static final class Sampler {
        private final ServerLevel level;
        private final ChunkGenerator generator;
        private final net.minecraft.world.level.levelgen.RandomState random;
        private final net.minecraft.world.level.biome.BiomeResolver biomes;
        Sampler(ServerLevel level) {
            this.level=level;generator=level.getChunkSource().getGenerator();random=level.getChunkSource().randomState();
            biomes=generator.getBiomeSource().createUncachedResolver(random);
        }
        int sample(int x,int z) {
        int h=generator.getBaseHeight(x,z,Heightmap.Types.WORLD_SURFACE_WG,level,random);
        return color(x,z,h,true);
        }
        int sampleFineBiome(int x,int z,Image coarse){return sampleFineBiome(x,z,coarse,0,0,SPAN);}
        int sampleFineBiome(int x,int z,Image coarse,int centerX,int centerZ,int span) {
            return color(x,z,(int)Math.round(interpolatedAlpha(x,z,coarse,centerX,centerZ,span)*512/255-64),false);
        }
        double interpolatedAlpha(int x,int z,Image coarse,int centerX,int centerZ,int span) {
            double sx=((x-centerX)/(double)span+.5)*coarse.size-.5,sz=((z-centerZ)/(double)span+.5)*coarse.size-.5;
            int ix=(int)Math.floor(sx),iz=(int)Math.floor(sz);double fx=sx-ix,fz=sz-iz,height=0;
            for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++)height+=(coarse.argb[Math.clamp(iz+dz,0,coarse.size-1)*coarse.size+Math.clamp(ix+dx,0,coarse.size-1)]>>>24)*(dx==0?1-fx:fx)*(dz==0?1-fz:fz);
            return height;
        }
        int color(int x,int z,int h,boolean exactHeight) {
        var biome=biomes.getNoiseBiome(x>>2,h>>2,z>>2);
        int color=biome.value().getGrassColor(x,z);
        if(color==0)color=0x71944b;
        boolean water=biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER)
                || exactHeight && h<=level.getSeaLevel()+1 && generator instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
        if(water) {
            // The biome water tint is for shallow translucent blocks. An orbital
            // ocean needs a darker opaque average while keeping the biome hue.
            int tint=biome.value().getWaterColor();
            color=((int)(((tint>>16)&255)*.50)<<16)|((int)(((tint>>8)&255)*.60)<<8)|(int)((tint&255)*.58);
        }
        else if(biome.is(BiomeTags.IS_BADLANDS))color=0xb56b3e;
        else if(biome.unwrapKey().map(k->k.identifier().getPath().contains("desert") || k.identifier().getPath().contains("beach")).orElse(false))color=0xd9c78a;
        else if(biome.value().coldEnoughToSnow(new BlockPos(x,h,z),level.getSeaLevel()))color=0xdde3e8;
        else if(biome.is(BiomeTags.IS_MOUNTAIN) && h>130)color=0x8f9290;
        int a=(int)Math.round(Math.clamp((h+64)*255.0/512,0,255));
        return a<<24 | color&0xffffff;
        }
    }
    private static final class State {
        volatile Image image;volatile boolean cancelled;volatile Detail detail;volatile Request desired,processed;
        final java.util.concurrent.atomic.AtomicBoolean detailBusy=new java.util.concurrent.atomic.AtomicBoolean();
        final Map<Request,Detail> details=new LinkedHashMap<>();
    }
}
