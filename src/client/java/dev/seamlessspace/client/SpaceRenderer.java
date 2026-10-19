package dev.seamlessspace.client;

import java.util.List;
import java.util.Optional;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;

import dev.seamlessspace.universe.DVec3;
import dev.seamlessspace.universe.Planet;
import dev.seamlessspace.universe.Universe;

/**
 * Analytic orbital planets plus a separate native-resolution star field. Surface
 * gameplay is drawn by Minecraft's chunk renderer, never by a terrain ray march.
 * All world-to-camera subtraction happens in doubles before reaching the GPU.
 */
public final class SpaceRenderer implements AutoCloseable {
    public static final int MAX_PLANETS = 16;
    private static final int UNIFORM_BYTES = (9 + MAX_PLANETS * 4) * 16;
    private static final Logger LOGGER = LogUtils.getLogger();

    private RenderPipeline pipeline;
    private RenderPipeline compositePipeline;
    private GpuBuffer frameUniforms;
    private TextureTarget universeTarget;
    private final PlanetLodRenderer surfaceLod = new PlanetLodRenderer();
    private Universe universe;
    private int resolutionHeight = 1080;
    private double starBrightness = 0.25;
    private double starSize = 0.65;
    private boolean failed;
    private final RingRenderer rings=new RingRenderer();
    private final SolarFlareRenderer flares=new SolarFlareRenderer();
    private net.minecraft.client.renderer.texture.DynamicTexture earthTexture;
    private dev.seamlessspace.world.EarthPreview.Image earthImage,detailImage;
    private net.minecraft.client.renderer.texture.DynamicTexture detailTexture;
    private dev.seamlessspace.world.EarthPreview.Detail detailMap;
    private static final long START=System.nanoTime();

    public void setUniverse(Universe universe) { this.universe = universe; }

    /** Orbital planet resolution; stars, HUD and vanilla blocks stay native. */
    public void setResolutionHeight(int height) {
        // Existing 0.1 configurations used 360p to contain terrain-raymarch cost.
        // Analytic spheres are cheap enough to retire that visibly blurry mode.
        resolutionHeight = Math.clamp(height, 720, 2160);
    }

    public void setStarBrightness(double brightness) {
        starBrightness = Double.isFinite(brightness) ? Math.clamp(brightness, 0, 2) : 0.25;
    }

    /** Approximate native framebuffer pixel radius, including subpixel stars. */
    public void setStarSize(double size) {
        starSize = Double.isFinite(size) ? Math.clamp(size, 0.2, 2) : 0.65;
    }

    /** Call in a Fabric level drawing event, before the hand and HUD. */
    public void render(DVec3 position, DVec3 forward, DVec3 right, DVec3 up,
                       List<Planet> planets, double transition, double tanHalfFov,
                       double aspect, boolean terrainDetail) {
        if (transition <= 0 || failed) return;
        RenderSystem.assertOnRenderThread();
        try {
            initialize();
            RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            if (target.getColorTextureView() == null) return;
            resizeTarget(target);
            var homeDirection=position.subtract(universe.home().center());
            var coordinates=dev.seamlessspace.world.PlanetSurface.coordinates(universe.home(),homeDirection);
            dev.seamlessspace.world.EarthPreview.requestDetail(Minecraft.getInstance().getSingleplayerServer(),coordinates.x(),coordinates.z(),homeDirection.length()-universe.home().radius());
            updateEarthTexture();
            detailMap=dev.seamlessspace.world.EarthPreview.detail(Minecraft.getInstance().getSingleplayerServer());
            var nextDetail=detailMap==null?null:detailMap.image();
            if(detailTexture==null || detailImage!=nextDetail) {
                if(detailTexture!=null)detailTexture.close();detailImage=nextDetail;
                detailTexture=uploadMap(nextDetail,"Earth local detail");
            }
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                Std140Builder data = Std140Builder.onStack(stack, UNIFORM_BYTES);
                vector(data, forward, (float) tanHalfFov);
                vector(data, right, (float) aspect);
                vector(data, up, (float) Math.clamp(transition, 0, 1));
                data.putVec4((float) starBrightness, target.width, target.height, (float) starSize);
                Planet nearest=universe.nearby(position,1).getFirst();
                DVec3 radial=position.subtract(nearest.center()).normalized();
                double seaAltitude=position.distance(nearest.center())-nearest.radius();
                double progress=dev.seamlessspace.world.PlanetAtmosphere.spaceFraction(nearest,seaAltitude);
                vector(data,radial,(float)progress);
                int tint=dev.seamlessspace.world.PlanetAtmosphere.skyRgb(nearest);
                boolean acid=nearest.body()==Planet.Body.VENUS && dev.seamlessspace.world.PlanetSurface.profile(nearest)>=7 && seaAltitude+96<170;
                float acidDensity=acid?(float)Math.clamp((170-seaAltitude-96)/100,0,1)*.3f:0;
                if(acid){float t=acidDensity/.3f;int acidTint=0x999138;
                    int red=(int)(((tint>>16)&255)*(1-t)+((acidTint>>16)&255)*t);
                    int green=(int)(((tint>>8)&255)*(1-t)+((acidTint>>8)&255)*t);
                    int blue=(int)((tint&255)*(1-t)+(acidTint&255)*t);tint=(red<<16)|(green<<8)|blue;
                }
                boolean dust=nearest.body()==Planet.Body.MARS;
                if(dust)tint=0x853d21;
                float dustDensity=(float)Math.clamp(1-(seaAltitude+96-200)/3000,0,1);
                data.putVec4((float)Math.pow(((tint>>16)&255)/255.0,2.2),
                        (float)Math.pow(((tint>>8)&255)/255.0,2.2),(float)Math.pow((tint&255)/255.0,2.2),dust?-dustDensity:dev.seamlessspace.world.PlanetPhysics.gas(nearest)?.48f:acidDensity);
                Planet sun=universe.sun();
                vector(data,sun==null ? DVec3.ZERO : position.subtract(sun.center()),sun==null ? 0 : (float)sun.radius());
                data.putVec4((float)((System.nanoTime()-START)/1e9),earthImage==null ? 0 : 1,100000,0);
                data.putVec4(detailMap==null?0:detailMap.x(),detailMap==null?0:detailMap.z(),detailMap==null?1:detailMap.span(),detailMap==null?0:1);
                int count = Math.min(MAX_PLANETS, planets.size());
                // Each array is tightly packed as vec4/ivec4, matching std140.
                for (int i = 0; i < MAX_PLANETS; i++) {
                    if (i < count) {
                        Planet planet = planets.get(i);
                        vector(data, position.subtract(planet.center()), (float) planet.radius());
                    } else {
                        data.putVec4(0, 0, 0, 0);
                    }
                }
                for (int i = 0; i < MAX_PLANETS; i++) {
                    if (i < count) {
                        Planet planet = planets.get(i);
                        data.putVec4((float)planet.northScale(), planet.equals(nearest) && seaAltitude<512 ? 1 : 0, atmosphere(planet.kind()), terrainDetail ? 1 : 0);
                    } else {
                        data.putVec4(0, 0, 0, 0);
                    }
                }
                for (int i = 0; i < MAX_PLANETS; i++) {
                    if (i < count) {
                        Planet planet = planets.get(i);
                        data.putIVec4(Universe.terrainSeed(planet), planet.kind().ordinal(),
                                Float.floatToRawIntBits((float) planet.distanceToSurface(position)),
                                planet.isHome() ? 2 : dev.seamlessspace.world.PlanetSurface.profile(planet) >= 4 ? 4 : dev.seamlessspace.world.PlanetSurface.profile(planet) >= 3 ? 3 : 1);
                    } else {
                        data.putIVec4(0, 0, 0, 0);
                    }
                }
                for(int i=0;i<MAX_PLANETS;i++) {
                    if(i<count) data.putVec4(planets.get(i).body().ordinal(),(float)planets.get(i).northScale(),dev.seamlessspace.world.PlanetSurface.profile(planets.get(i)),0);
                    else data.putVec4(0,1,0,0);
                }
                encoder.writeToBuffer(frameUniforms.slice(), data.get());
            }
            // This layer contains planets only. The composite computes tiny stars
            // at the actual framebuffer resolution, avoiding upscale blur/bloom.
            try (RenderPass pass = encoder.createRenderPass(() -> "Seamless Space universe",
                    universeTarget.getColorTextureView(), Optional.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
                pass.setUniform("SpaceFrame", frameUniforms);
                pass.setUniform("EarthMap",earthTexture.getTextureView(),RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                pass.setUniform("EarthDetail",detailTexture.getTextureView(),RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                pass.draw(3, 1, 0, 0);
            }
            if (universe != null) {
                encoder.clearDepthTexture(universeTarget.getDepthTexture(), 0.0);
                surfaceLod.render(encoder, universeTarget.getColorTextureView(), universeTarget.getDepthTextureView(),
                        frameUniforms, position, forward, tanHalfFov, target.height, planets, universe);
            }
            flares.render(encoder,universeTarget.getColorTextureView(),universeTarget.getDepthTextureView(),frameUniforms,position,universe);
            rings.render(encoder,universeTarget.getColorTextureView(),universeTarget.getDepthTextureView(),frameUniforms,position,planets,universe);
            try (RenderPass pass = encoder.createRenderPass(() -> "Seamless Space native stars and planets",
                    target.getColorTextureView(), Optional.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(compositePipeline));
                pass.setUniform("SpaceFrame", frameUniforms);
                pass.setUniform("SpaceImage", universeTarget.getColorTextureView(),
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                pass.draw(3, 1, 0, 0);
            }
        } catch (RuntimeException exception) {
            failed = true;
            LOGGER.error("Seamless Space could not draw its universe shader. "
                    + "See the shader compiler details above; the vanilla renderer remains active.", exception);
        }
    }

    private void updateEarthTexture() {
        var server=Minecraft.getInstance().getSingleplayerServer();
        var preview=server==null ? null : dev.seamlessspace.world.EarthPreview.image(server);
        if(earthTexture!=null && earthImage==preview)return;
        if(earthTexture!=null)earthTexture.close();
        earthImage=preview;int n=preview==null ? 1 : preview.size();
        earthTexture=new net.minecraft.client.renderer.texture.DynamicTexture("Earth generator preview",n,n,false);
        var pixels=earthTexture.getPixels();
        for(int z=0;z<n;z++)for(int x=0;x<n;x++)pixels.setPixel(x,z,preview==null ? 0x40416c8d : preview.argb()[z*n+x]);
        earthTexture.upload();
    }

    private net.minecraft.client.renderer.texture.DynamicTexture uploadMap(dev.seamlessspace.world.EarthPreview.Image preview,String label) {
        int n=preview==null?1:preview.size();
        var texture=new net.minecraft.client.renderer.texture.DynamicTexture(label,n,n,false);
        var pixels=texture.getPixels();
        for(int z=0;z<n;z++)for(int x=0;x<n;x++)pixels.setPixel(x,z,preview==null?0x40416c8d:preview.argb()[z*n+x]);
        texture.upload();return texture;
    }

    public int solarFlareVertices(){return flares.count();}
    public int ringFragments(){return rings.count();}

    public boolean hasFailed() {
        return failed;
    }

    private void initialize() {
        if (pipeline != null) return;
        BindGroupLayout layout = BindGroupLayout.builder()
                .withUniform("SpaceFrame", UniformType.UNIFORM_BUFFER).withUniform("EarthMap",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("EarthDetail",UniformType.COMBINED_IMAGE_SAMPLER).build();
        pipeline = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("seamlessspace", "pipeline/universe"))
                .withVertexShader("core/screenquad")
                .withFragmentShader(Identifier.fromNamespaceAndPath("seamlessspace", "core/universe"))
                .withBindGroupLayout(layout)
                .withColorTargetState(ColorTargetState.DEFAULT)
                .withDepthStencilState(Optional.empty())
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .build();
        compositePipeline = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("seamlessspace", "pipeline/composite"))
                .withVertexShader("core/screenquad")
                .withFragmentShader(Identifier.fromNamespaceAndPath("seamlessspace", "core/composite"))
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withUniform("SpaceFrame", UniformType.UNIFORM_BUFFER)
                        .withUniform("SpaceImage", UniformType.COMBINED_IMAGE_SAMPLER).build())
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withDepthStencilState(Optional.empty())
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false).build();
        frameUniforms = RenderSystem.getDevice().createBuffer(() -> "Seamless Space frame uniforms",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UNIFORM_BYTES);
    }

    private void resizeTarget(RenderTarget mainTarget) {
        int height = Math.min(mainTarget.height, resolutionHeight);
        int width = Math.max(1, (int) Math.round((double) mainTarget.width * height / mainTarget.height));
        if (universeTarget == null) {
            universeTarget = new TextureTarget("Seamless Space analytic planets", width, height,
                    GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        } else if (universeTarget.width != width || universeTarget.height != height) {
            universeTarget.resize(width, height);
        }
    }

    private static void vector(Std140Builder data, DVec3 vector, float fourth) {
        data.putVec4((float) vector.x(), (float) vector.y(), (float) vector.z(), fourth);
    }

    private static float atmosphere(Planet.Kind kind) {
        return switch (kind) {
            case ROCKY -> 0;
            case DESERT -> 0.42f;
            case ICE -> 0.28f;
            case LAVA -> 0.22f;
            case OCEAN -> 0.9f;
        };
    }

    @Override
    public void close() {
        surfaceLod.close();rings.close();flares.close();
        if(earthTexture!=null){earthTexture.close();earthTexture=null;earthImage=null;}
        if(detailTexture!=null){detailTexture.close();detailTexture=null;detailImage=null;detailMap=null;}
        if (frameUniforms != null) {
            frameUniforms.close();
            frameUniforms = null;
        }
        pipeline = null;
        compositePipeline = null;
        if (universeTarget != null) {
            universeTarget.destroyBuffers();
            universeTarget = null;
        }
        failed = false;
    }
}
