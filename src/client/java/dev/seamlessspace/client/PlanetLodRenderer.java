package dev.seamlessspace.client;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.seamlessspace.render.VoxelSurfaceMesher;
import dev.seamlessspace.client.mixin.SpaceSpriteAccessor;
import dev.seamlessspace.universe.*;
import dev.seamlessspace.world.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.lwjgl.system.MemoryStack;

/** Instanced greedy voxel surfaces, built on a bounded worker without reducing screen resolution. */
final class PlanetLodRenderer implements AutoCloseable {
    private static final PlanetMaterial[] MATERIALS=PlanetMaterial.values();
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor(r -> {
        Thread thread=new Thread(r,"Seamless Space voxel LOD");thread.setDaemon(true);return thread;
    });
    private static final VertexFormat FACES=VertexFormat.builder(1)
            .addAttribute("FaceOrigin",GpuFormat.RGB32_FLOAT).addAttribute("FaceSize",GpuFormat.RG32_FLOAT)
            .addAttribute("FaceType",GpuFormat.R32_UINT).addAttribute("FaceColor",GpuFormat.RGBA8_UNORM).build();
    private final Map<Planet,Mesh> meshes=new LinkedHashMap<>();
    private RenderPipeline pipeline;
    private GpuBuffer surfaceUniform,materialUniform;
    private Object materialAtlas;
    private MinecraftServer currentServer;

    void render(CommandEncoder encoder,GpuTextureView color,GpuTextureView depth,GpuBuffer frame,
                DVec3 camera,DVec3 forward,double tanHalfFov,int screenHeight,List<Planet> planets,Universe universe) {
        var client=Minecraft.getInstance();var server=client.getSingleplayerServer();
        if(server==null) return;
        if(currentServer!=server) {close();currentServer=server;}
        initialize();
        var atlas=client.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
        updateMaterials(encoder,atlas);
        long now=System.nanoTime();
        for(Planet planet:planets) {
            var tiles=OrbitalSurfaceCache.tiles(server,planet);
            double altitude=planet.distanceToSurface(camera);
            boolean near=altitude<Math.min(200000,Math.max(2000,planet.radius()*.35));
            if(tiles.isEmpty() && !near || planet.isHome() && altitude>=1200) continue;
            var c=PlanetSurface.coordinates(planet,camera.subtract(planet.center()));
            double footprint=Math.max(1,altitude)*2*tanHalfFov/Math.max(1,screenHeight);
            int step=1;
            while(step<256 && step*2<footprint*1.6) step*=2;
            int quantum=64*step;
            int cx=(int)Math.floor(c.x()/quantum)*quantum,cz=(int)Math.floor(c.z()/quantum)*quantum;
            Mesh mesh=meshes.computeIfAbsent(planet,p -> new Mesh());
            if(mesh.build!=null && mesh.build.isDone()) {
                try {
                    Data data=mesh.build.join();
                    if(mesh.faces!=null) mesh.faces.close();
                    mesh.faces=data.bytes.hasRemaining()?RenderSystem.getDevice().createBuffer(() -> "Greedy voxel face instances",GpuBuffer.USAGE_VERTEX,data.bytes):null;
                    mesh.faceCount=data.bytes.limit()/VoxelSurfaceMesher.STRIDE;
                    mesh.originX=data.x;mesh.originZ=data.z;mesh.builtNear=data.near;
                } catch(RuntimeException error) {SeamlessSpaceClient.LOGGER.warn("Voxel LOD build failed",error);}
                mesh.build=null;
            }
            Object preview=planet.isHome()?(EarthPreview.detail(server)!=null?EarthPreview.detail(server):EarthPreview.image(server)):null;
            boolean change=mesh.preview!=preview || mesh.faces==null || mesh.tilesSnapshot!=tiles || mesh.near!=near || mesh.step!=step || mesh.requestX!=cx || mesh.requestZ!=cz;
            if(mesh.build==null && change && now-mesh.lastBuild>500_000_000L) {
                mesh.preview=preview;mesh.tilesSnapshot=tiles;mesh.near=near;mesh.step=step;mesh.requestX=cx;mesh.requestZ=cz;mesh.lastBuild=now;
                int selectedStep=step;
                double pixelFactor=2*tanHalfFov/Math.max(1,screenHeight);
                mesh.build=CompletableFuture.supplyAsync(() -> build(server,planet,tiles,near,cx,cz,selectedStep,altitude,pixelFactor,mesh),WORKER);
            }
            if(mesh.faces==null || mesh.faceCount==0 || !near && mesh.builtNear) continue;
            var local=PlanetSurface.frame(planet,c.x(),c.z());
            double eye=camera.distance(planet.center())-planet.radius();
            try(var stack=MemoryStack.stackPush()) {
                var data=Std140Builder.onStack(stack,6*16);
                vec(data,local.east(),(float)Math.sin(c.z()/(planet.radius()*planet.northScale())));
                vec(data,local.radial(),(float)Math.cos(c.z()/(planet.radius()*planet.northScale())));
                vec(data,local.north(),(float)planet.radius());
                data.putVec4((float)(mesh.originX-c.x()),(float)eye,(float)(mesh.originZ-c.z()),0);
                DVec3 sunDirection=universe.sun()==null ? new DVec3(-.451420,.321011,.832619) : universe.sun().center().subtract(planet.center().add(local.radial().multiply(planet.radius()))).normalized();
                float light=planet.body()==Planet.Body.SUN ? 1 : (float)Math.pow(0.025+Math.max(0,local.radial().dot(sunDirection))*.96,1.0/2.2);
                data.putVec4(planet.isHome()?64:96,light,mesh.originX,mesh.originZ);
                float opacity=planet.isHome()?(float)Math.clamp((1200-altitude)/800,0,1):1;
                data.putVec4((float)planet.northScale(),planet.body().ordinal(),PlanetSurface.cube(planet)?1:0,opacity);
                encoder.writeToBuffer(surfaceUniform.slice(),data.get());
            }
            try(var pass=encoder.createRenderPass(() -> "Instanced block terrain LOD",color,Optional.empty(),depth,OptionalDouble.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
                pass.setUniform("SpaceFrame",frame);pass.setUniform("SurfaceOrigin",surfaceUniform);
                pass.setUniform("SurfaceMaterials",materialUniform);
                pass.setUniform("BlockAtlas",atlas.getTextureView(),RenderSystem.getSamplerCache().getSampler(
                        com.mojang.renderpearl.api.textures.AddressMode.CLAMP_TO_EDGE,com.mojang.renderpearl.api.textures.AddressMode.CLAMP_TO_EDGE,
                        com.mojang.renderpearl.api.textures.FilterMode.LINEAR,com.mojang.renderpearl.api.textures.FilterMode.NEAREST,true));
                pass.setVertexBuffer(0,mesh.faces.slice());pass.draw(6,mesh.faceCount,0,0);
            }
        }
        if(meshes.size()>10) {
            var it=meshes.entrySet().iterator();
            while(meshes.size()>10 && it.hasNext()) {
                var entry=it.next();if(!planets.contains(entry.getKey())) {entry.getValue().close();it.remove();}
            }
        }
    }
    private void initialize() {
        if(pipeline!=null) return;
        pipeline=RenderPipeline.builder().withLocation(SeamlessSpaceClient.id("pipeline/surface_lod"))
                .withVertexShader(SeamlessSpaceClient.id("core/surface_lod")).withFragmentShader(SeamlessSpaceClient.id("core/surface_lod"))
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("SpaceFrame",UniformType.UNIFORM_BUFFER)
                        .withUniform("SurfaceOrigin",UniformType.UNIFORM_BUFFER).withUniform("SurfaceMaterials",UniformType.UNIFORM_BUFFER)
                        .withUniform("BlockAtlas",UniformType.COMBINED_IMAGE_SAMPLER).build())
                .withVertexBinding(0,FACES).withColorTargetState(ColorTargetState.DEFAULT)
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL,true))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
        surfaceUniform=RenderSystem.getDevice().createBuffer(() -> "Surface camera chart",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,96);
        materialUniform=RenderSystem.getDevice().createBuffer(() -> "Block material sprites and filtered colors",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,64*16);
    }
    private void updateMaterials(CommandEncoder encoder,TextureAtlas atlas) {
        if(materialAtlas==atlas.getTexture()) return;
        try(var stack=MemoryStack.stackPush()) {
            var data=Std140Builder.onStack(stack,64*16);
            for(int i=0;i<32;i++) {
                var mat=MATERIALS[Math.min(i,MATERIALS.length-1)];
                var sprite=atlas.getSprite(mat.texture.contains(":") ? Identifier.fromNamespaceAndPath("seamlessspace","block/"+mat.texture.split(":")[1]) : Identifier.withDefaultNamespace("block/"+mat.texture));
                data.putVec4(sprite.getU0(),sprite.getV0(),sprite.getU1(),sprite.getV1());
            }
            for(int i=0;i<32;i++) {
                var mat=MATERIALS[Math.min(i,MATERIALS.length-1)];
                var contents=atlas.getSprite(mat.texture.contains(":") ? Identifier.fromNamespaceAndPath("seamlessspace","block/"+mat.texture.split(":")[1]) : Identifier.withDefaultNamespace("block/"+mat.texture)).contents();
                var image=((SpaceSpriteAccessor)(Object)contents).seamlessspace$image();
                double r=0,g=0,b=0,total=0;
                for(int y=0;y<contents.height();y+=Math.max(1,contents.height()/16))
                    for(int x=0;x<contents.width();x+=Math.max(1,contents.width()/16)) {
                        int pixel=image.getPixel(x,y);
                        double alpha=((pixel>>>24)&255)/255.0;
                        r+=Math.pow(((pixel>>>16)&255)/255.0,2.2)*alpha;
                        g+=Math.pow(((pixel>>>8)&255)/255.0,2.2)*alpha;
                        b+=Math.pow((pixel&255)/255.0,2.2)*alpha;total+=alpha;
                    }
                total=Math.max(total,1e-6);
                data.putVec4((float)Math.pow(r/total,1/2.2),(float)Math.pow(g/total,1/2.2),(float)Math.pow(b/total,1/2.2),1);
            }
            encoder.writeToBuffer(materialUniform.slice(),data.get());
            materialAtlas=atlas.getTexture();
        }
    }
    private static Data build(MinecraftServer server,Planet planet,List<OrbitalSurfaceCache.Tile> tiles,boolean near,int cx,int cz,int baseStep,
                              double altitude,double pixelFactor,Mesh token) {
        Map<Long,OrbitalSurfaceCache.Tile> actual=new HashMap<>();
        for(var tile:tiles) actual.put(key(tile.chunkX,tile.chunkZ),tile);
        VoxelSurfaceMesher.Buffer output=new VoxelSurfaceMesher.Buffer();
        int coverage=0;
        if(near) {
            int inner=0,step=baseStep;
            int extent=(int)Math.min(131072,Math.max(8192,planet.radius()*.35));
            while(true) {
                if(token.closed) throw new CancellationException();
                int outer=64*step;
                int x0=(int)Math.floor((double)cx/step)*step-outer,z0=(int)Math.floor((double)cz/step)*step-outer;
                Grid grid=new Grid(server,planet,actual,x0,z0,128,step,cx,cz,inner);
                VoxelSurfaceMesher.emit(grid,128,step,x0-cx,z0-cz,PlanetSurface.MIN_Y-16,output);
                if(outer>=extent) {coverage=outer;break;}
                inner=outer;step*=2;
            }
        }
        // Cached edits outside the procedural patch remain visible from other planets.
        for(var tile:tiles) {
            if(token.closed) throw new CancellationException();
            boolean covered=coverage>0 && tile.chunkX*16>=cx-coverage && tile.chunkX*16+16<=cx+coverage
                    && tile.chunkZ*16>=cz-coverage && tile.chunkZ*16+16<=cz+coverage;
            if(covered) continue;
            // A cached region on another planet must not retain a metre mesh
            // when its entire chunk occupies a fraction of a pixel. Telescope
            // FOV feeds this same metric and restores detail when zooming in.
            double distance=Math.hypot(altitude,Math.hypot(tile.chunkX*16.0+8-cx,tile.chunkZ*16.0+8-cz));
            int step=1;
            while(step<16 && step*2<distance*pixelFactor*1.6) step*=2;
            Grid grid=new Grid(server,planet,actual,tile.chunkX*16,tile.chunkZ*16,16/step,step,cx,cz,0);
            VoxelSurfaceMesher.emit(grid,16/step,step,tile.chunkX*16-cx,tile.chunkZ*16-cz,PlanetSurface.MIN_Y-16,output);
        }
        return new Data(output.finish(),cx,cz,near);
    }
    private static final class Grid implements VoxelSurfaceMesher.Grid {
        final int size,stride,step,x0,z0,cx,cz,inner;
        final double latitudeLimit;
        final Planet planet;
        final boolean polar;
        final PlanetSurface.Frame basis;
        final int[] heights,materials,colors;
        Grid(MinecraftServer server,Planet planet,Map<Long,OrbitalSurfaceCache.Tile> actual,int x0,int z0,int size,int step,int cx,int cz,int inner) {
            this.planet=planet;this.size=size;this.stride=size+2;this.step=step;this.x0=x0;this.z0=z0;this.cx=cx;this.cz=cz;this.inner=inner;
            latitudeLimit=planet.radius()*Math.PI*.5*planet.northScale();
            polar=!PlanetSurface.cube(planet) && Math.abs(Math.sin(cz/(planet.radius()*planet.northScale())))>.88;
            basis=PlanetSurface.frame(planet,cx,cz);
            heights=new int[stride*stride];materials=new int[heights.length];colors=new int[heights.length];
            for(int z=-1;z<=size;z++) for(int x=-1;x<=size;x++) {
                int wx=x0+x*step,wz=z0+z*step,i=(z+1)*stride+x+1;
                if(polar){
                    var u=basis.radial().add(basis.east().multiply((wx-cx)/planet.radius())).add(basis.north().multiply((wz-cz)/planet.radius())).normalized();
                    var chart=PlanetSurface.coordinates(planet,u);wx=(int)Math.floor(chart.x());wz=(int)Math.floor(chart.z());
                }
                var tile=actual.get(key(wx>>4,wz>>4));
                if(tile!=null) {
                    heights[i]=tile.height(wx&15,wz&15);materials[i]=tile.material(wx&15,wz&15);colors[i]=tile.color(wx&15,wz&15);
                    // Legacy cached colors are upgraded from the actual chunk on the next visit.
                } else if(!planet.isHome()) {
                    int ground=PlanetSurface.height(planet,wx,wz);
                    heights[i]=(PlanetSurface.hasSurfaceFluid(planet) ? Math.max(ground,96):ground)+1;
                    var material=PlanetMaterials.visibleGround(planet,wx,wz,ground);materials[i]=material.ordinal();colors[i]=material.rgb;
                } else {
                    int pixel=EarthPreview.previewPixel(server,wx,wz);
                    heights[i]=(int)Math.round(EarthPreview.previewHeight(server,wx,wz));
                    materials[i]=PlanetMaterial.COLOR.ordinal();colors[i]=pixel&0xffffff;
                    if((pixel&255)>((pixel>>8)&255)*1.12) {
                        heights[i]=server.overworld().getSeaLevel()+1;materials[i]=PlanetMaterial.WATER.ordinal();
                        int red=Math.min(255,(int)(((pixel>>16)&255)/.5)),green=Math.min(255,(int)(((pixel>>8)&255)/.6)),blue=Math.min(255,(int)((pixel&255)/.58));
                        colors[i]=red<<16|green<<8|blue;
                    }
                }
            }
        }
        private int index(int x,int z) {return(z+1)*stride+x+1;}
        public int height(int x,int z) {return heights[index(x,z)];}
        public int material(int x,int z) {return materials[index(x,z)];}
        public int color(int x,int z) {return colors[index(x,z)];}
        public boolean active(int x,int z) {
            return (PlanetSurface.cube(planet)?EarthProjection.face(x0+x*step+step*.5,z0+z*step+step*.5)==EarthProjection.face(cx,cz):polar || Math.abs(z0+z*step+step*.5)<latitudeLimit) && colors[index(x,z)]!=0
                    && (inner==0 || Math.max(Math.abs(x0+x*step+step/2-cx),Math.abs(z0+z*step+step/2-cz))>=inner);
        }
    }
    private static void vec(Std140Builder data,DVec3 v,float w) {data.putVec4((float)v.x(),(float)v.y(),(float)v.z(),w);}
    private static long key(int x,int z) {return((long)x<<32)^(z&0xffffffffL);}
    @Override public void close() {
        meshes.values().forEach(Mesh::close);meshes.clear();
        if(surfaceUniform!=null) {surfaceUniform.close();surfaceUniform=null;}
        if(materialUniform!=null) {materialUniform.close();materialUniform=null;}
        pipeline=null;currentServer=null;materialAtlas=null;
    }
    private record Data(ByteBuffer bytes,int x,int z,boolean near) { }
    private static final class Mesh implements AutoCloseable {
        GpuBuffer faces;int faceCount,originX,originZ,requestX,requestZ,step;long lastBuild;boolean near,builtNear;
        Object preview;
        List<OrbitalSurfaceCache.Tile> tilesSnapshot;
        volatile boolean closed;
        CompletableFuture<Data> build;
        public void close() {closed=true;if(faces!=null) faces.close();if(build!=null) build.cancel(false);}
    }
}
