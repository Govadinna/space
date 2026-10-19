package dev.seamlessspace.client;
import dev.seamlessspace.universe.*;
import dev.seamlessspace.world.*;
import java.nio.ByteBuffer;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.util.*;
import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.lwjgl.system.MemoryUtil;
/** Bounded, animated ribbon arches rooted in the Sun's procedural block surface. */
final class SolarFlareRenderer implements AutoCloseable {
    private RenderPipeline pipeline;private GpuBuffer vertices;private int count,cx=Integer.MIN_VALUE,cz;private Planet body;
    void render(CommandEncoder encoder,GpuTextureView color,GpuTextureView depth,GpuBuffer frame,DVec3 camera,Universe universe) {
        var sun=universe.sun();if(sun==null || sun.distanceToSurface(camera)>12000)return;
        var coord=PlanetSurface.coordinates(sun,camera.subtract(sun.center()));
        int x=(int)Math.floor(coord.x()/1600)*1600,z=(int)Math.floor(coord.z()/1600)*1600;
        initialize();
        if(vertices==null || body!=sun || x!=cx || z!=cz) {body=sun;cx=x;cz=z;build(sun);}
        if(count==0)return;
        try(var pass=encoder.createRenderPass(()->"Solar magnetic plasma arches",color,Optional.empty(),depth,OptionalDouble.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));pass.setUniform("SpaceFrame",frame);
            pass.setVertexBuffer(0,vertices.slice());pass.draw(count,1,0,0);
        }
    }
    private void build(Planet sun) {
        int maximum=25*3*40*6;var bytes=MemoryUtil.memAlloc(maximum*36);count=0;
        try {
            for(int gz=-2;gz<=2;gz++)for(int gx=-2;gx<=2;gx++) {
                var random=new SplittableRandom((long)(cx/800+gx)*73856093L^(long)(cz/800+gz)*19349663L^sun.seed());
                double ax=cx+gx*800+random.nextDouble(-200,200),az=cz+gz*800+random.nextDouble(-200,200);
                double rotation=random.nextDouble(Math.PI*2),width=random.nextDouble(280,650),rise=random.nextDouble(220,650);
                for(int strand=0;strand<3;strand++) {
                    float phase=(float)random.nextDouble(Math.PI*2)+strand*10;
                    for(int segment=0;segment<40;segment++) {
                        double t0=segment*Math.PI/40,t1=(segment+1)*Math.PI/40;
                        DVec3 a=point(sun,ax,az,rotation,width,rise,t0,strand),b=point(sun,ax,az,rotation,width,rise,t1,strand);
                        var ta=point(sun,ax,az,rotation,width,rise,t0+.001,strand).subtract(point(sun,ax,az,rotation,width,rise,t0-.001,strand)).normalized();
                        var tb=point(sun,ax,az,rotation,width,rise,t1+.001,strand).subtract(point(sun,ax,az,rotation,width,rise,t1-.001,strand)).normalized();
                        put(bytes,a,ta,-1,phase,(float)t0);put(bytes,b,tb,-1,phase,(float)t1);put(bytes,b,tb,1,phase,(float)t1);
                        put(bytes,a,ta,-1,phase,(float)t0);put(bytes,b,tb,1,phase,(float)t1);put(bytes,a,ta,1,phase,(float)t0);
                    }
                }
            }
            bytes.flip();if(vertices!=null)vertices.close();
            vertices=RenderSystem.getDevice().createBuffer(()->"Local solar ribbon geometry",GpuBuffer.USAGE_VERTEX,bytes);
        } finally {MemoryUtil.memFree(bytes);}
    }
    private static DVec3 point(Planet sun,double x,double z,double rotation,double width,double rise,double t,int strand) {
        double along=Math.cos(t)*width;
        var radial=PlanetSurface.direction(sun,x+Math.cos(rotation)*along,z+Math.sin(rotation)*along);
        double h=SolarTerrain.height(sun,radial)+1-96+Math.sin(t)*(rise+strand*12);
        return radial.multiply(sun.radius()+h);
    }
    private void put(ByteBuffer b,DVec3 p,DVec3 tangent,float side,float phase,float along) {
        b.putFloat((float)p.x()).putFloat((float)p.y()).putFloat((float)p.z());
        b.putFloat((float)tangent.x()).putFloat((float)tangent.y()).putFloat((float)tangent.z());b.putFloat(side).putFloat(phase).putFloat(along);count++;
    }
    private void initialize() {
        if(pipeline!=null)return;
        var format=VertexFormat.builder(0).addAttribute("FlarePoint",GpuFormat.RGB32_FLOAT).addAttribute("FlareTangent",GpuFormat.RGB32_FLOAT)
            .addAttribute("RibbonSide",GpuFormat.R32_FLOAT).addAttribute("FlarePhase",GpuFormat.R32_FLOAT).addAttribute("FlareAlong",GpuFormat.R32_FLOAT).build();
        pipeline=RenderPipeline.builder().withLocation(SeamlessSpaceClient.id("pipeline/solar_flares"))
            .withVertexShader(SeamlessSpaceClient.id("core/solar_flare")).withFragmentShader(SeamlessSpaceClient.id("core/solar_flare"))
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("SpaceFrame",UniformType.UNIFORM_BUFFER).build())
            .withVertexBinding(0,format).withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL,false))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
    }
    int count(){return count;}
    public void close(){if(vertices!=null)vertices.close();vertices=null;pipeline=null;body=null;count=0;}
}
