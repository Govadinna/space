package dev.seamlessspace.client;

import dev.seamlessspace.universe.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.renderpearl.api.*;
import com.mojang.renderpearl.api.buffers.*;
import com.mojang.renderpearl.api.commands.*;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.nio.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;

/** Only the nearby part of a ring becomes individual, rotated, textured cubes. */
final class RingRenderer implements AutoCloseable {
    private RenderPipeline pipeline;private GpuBuffer instances,uniforms;
    private Planet body;private int centerX=Integer.MIN_VALUE,centerZ,count;
    private final ByteBuffer bytes=ByteBuffer.allocateDirect(20*10000).order(ByteOrder.nativeOrder());
    void render(CommandEncoder encoder,GpuTextureView color,GpuTextureView depth,GpuBuffer frame,DVec3 camera,List<Planet> planets,Universe universe) {
        for(var planet:planets) {
            if(!planet.hasRings())continue;
            var n=planet.ringNormal();var east=new DVec3(0,0,1).cross(n).normalized();var north=n.cross(east);
            var offset=camera.subtract(planet.center());double h=offset.dot(n),x=offset.dot(east),z=offset.dot(north),r=Math.hypot(x,z);
            if(Math.abs(h)>250 || r<planet.radius()*1.3-400 || r>planet.radius()*2.5+400)continue;
            initialize();int cx=(int)Math.floor(x/64)*64,cz=(int)Math.floor(z/64)*64;
            if(body!=planet || cx!=centerX || cz!=centerZ) {
                body=planet;centerX=cx;centerZ=cz;bytes.clear();count=0;
                for(int dz=-40;dz<=40;dz++)for(int dx=-40;dx<=40;dx++) {
                    int gx=cx/8+dx,gz=cz/8+dz,seed=mix(gx*0x8da6b343^gz*0xd8163841^Universe.terrainSeed(planet));
                    double radius=Math.hypot(gx*8.0,gz*8.0)/planet.radius();
                    if(radius<1.35 || radius>2.45 || radius>1.93 && radius<2.03 || (seed&255)>135)continue;
                    double jx=((seed>>>8)&255)/255.0*6,jz=((seed>>>16)&255)/255.0*6;
                    float height=((mix(seed+371)>>>8)/16777215f-.5f)*20;
                    float size=.8f+(mix(seed+991)>>>8)/16777215f*2.8f;
                    bytes.putFloat((float)(gx*8.0+jx-cx)).putFloat(height).putFloat((float)(gz*8.0+jz-cz)).putFloat(size).putInt(seed);count++;
                }
                bytes.flip();if(count>0)encoder.writeToBuffer(instances.slice(0,bytes.remaining()),bytes);
            }
            if(count==0)continue;
            var atlas=Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
            try(var stack=MemoryStack.stackPush()) {
                var data=Std140Builder.onStack(stack,6*16);
                vec(data,east,0);vec(data,n,0);vec(data,north,0);
                vec(data,planet.center().add(east.multiply(cx)).add(north.multiply(cz)).subtract(camera),0);
                for(var texture:new String[]{planet.body()==Planet.Body.SATURN?"calcite":"packed_ice",planet.body()==Planet.Body.SATURN?"sandstone_top":"blue_ice"}) {
                    var sprite=atlas.getSprite(Identifier.withDefaultNamespace("block/"+texture));
                    data.putVec4(sprite.getU0(),sprite.getV0(),sprite.getU1(),sprite.getV1());
                }
                encoder.writeToBuffer(uniforms.slice(),data.get());
            }
            try(var pass=encoder.createRenderPass(()->"Saturn ring block fragments",color,Optional.empty(),depth,OptionalDouble.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));pass.setUniform("SpaceFrame",frame);pass.setUniform("DebrisFrame",uniforms);
                pass.setUniform("BlockAtlas",atlas.getTextureView(),RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,FilterMode.LINEAR,FilterMode.NEAREST,true));pass.setVertexBuffer(0,instances.slice());pass.draw(36,count,0,0);
            }
        }
    }
    int count(){return count;}
    private void initialize() {
        if(pipeline!=null)return;
        var format=VertexFormat.builder(1).addAttribute("CubeOrigin",GpuFormat.RGB32_FLOAT)
                .addAttribute("CubeSize",GpuFormat.R32_FLOAT).addAttribute("CubeSeed",GpuFormat.R32_UINT).build();
        pipeline=RenderPipeline.builder().withLocation(SeamlessSpaceClient.id("pipeline/ring_blocks"))
                .withVertexShader(SeamlessSpaceClient.id("core/debris")).withFragmentShader(SeamlessSpaceClient.id("core/debris"))
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("SpaceFrame",UniformType.UNIFORM_BUFFER)
                        .withUniform("DebrisFrame",UniformType.UNIFORM_BUFFER).withUniform("BlockAtlas",UniformType.COMBINED_IMAGE_SAMPLER).build())
                .withVertexBinding(0,format).withColorTargetState(ColorTargetState.DEFAULT)
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL,true)).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
        instances=RenderSystem.getDevice().createBuffer(()->"Nearby ring cube instances",GpuBuffer.USAGE_VERTEX|GpuBuffer.USAGE_COPY_DST,20*10000);
        uniforms=RenderSystem.getDevice().createBuffer(()->"Ring local frame",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,96);
    }
    private static int mix(int h){h^=h>>>16;h*=0x7feb352d;h^=h>>>15;h*=0x846ca68b;return h^(h>>>16);}
    private static void vec(Std140Builder b,DVec3 v,float w){b.putVec4((float)v.x(),(float)v.y(),(float)v.z(),w);}
    public void close(){if(instances!=null)instances.close();if(uniforms!=null)uniforms.close();pipeline=null;instances=null;uniforms=null;body=null;}
}
