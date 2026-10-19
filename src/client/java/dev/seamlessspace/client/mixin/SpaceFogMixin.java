package dev.seamlessspace.client.mixin;

import dev.seamlessspace.client.SeamlessSpaceClient;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Native chunks fade into the distant terrain, instead of a black view-distance wall. */
@Mixin(FogRenderer.class)
public abstract class SpaceFogMixin {
    @Inject(method="setupFog",at=@At("RETURN"))
    private void seamlessspace$continuousFog(Camera camera,int renderDistance,DeltaTracker delta,float darken,
            ClientLevel level,CallbackInfoReturnable<FogData> ci) {
        var planet=SeamlessSpaceClient.currentSurfacePlanet();
        if(planet==null || camera.getFluidInCamera()!=FogType.NONE && camera.getFluidInCamera()!=FogType.ATMOSPHERIC) return;
        if(planet.isHome() && camera.position().y()<320) return;
        FogData fog=ci.getReturnValue();
        if(planet.body()==dev.seamlessspace.universe.Planet.Body.VENUS && dev.seamlessspace.world.PlanetSurface.profile(planet)>=7 && camera.position().y()<170) {
            float depth=(float)Math.clamp((170-camera.position().y())/100,0,1);
            fog.color.set(.773f+(.60f-.773f)*depth,.604f+(.57f-.604f)*depth,.314f+(.22f-.314f)*depth,1);fog.renderDistanceStart=80;
            fog.renderDistanceEnd=depth<.001f?2_000_000:Math.min(2_000_000,1200/depth);fog.environmentalStart=80;fog.environmentalEnd=fog.renderDistanceEnd;return;
        }
        if(planet.body()==dev.seamlessspace.universe.Planet.Body.MARS) {
            // Thin dust only at distance, fading continuously above the atmosphere.
            float density=(float)Math.clamp(1-(camera.position().y()-200)/3000,0,1);
            if(density>.001f) {
                fog.color.set(.52f,.24f,.13f,1);
                fog.renderDistanceStart=384/density;fog.renderDistanceEnd=6000/density;
                fog.environmentalStart=fog.renderDistanceStart;fog.environmentalEnd=fog.renderDistanceEnd;return;
            }
        }
        if(dev.seamlessspace.world.PlanetPhysics.gas(planet)) {
            int tint=dev.seamlessspace.world.PlanetAtmosphere.skyRgb(planet);
            fog.color.set(((tint>>16)&255)/255f,((tint>>8)&255)/255f,(tint&255)/255f,1);
            float density=(float)Math.clamp(1-(camera.position().y()-300)/4700,0.02,1);
            float distance=planet.body()==dev.seamlessspace.universe.Planet.Body.JUPITER?1600:planet.body()==dev.seamlessspace.universe.Planet.Body.SATURN?2000:2400;
            fog.renderDistanceStart=48;fog.renderDistanceEnd=distance/density;
            fog.environmentalStart=48;fog.environmentalEnd=fog.renderDistanceEnd;return;
        }
        fog.renderDistanceStart=1_000_000;fog.renderDistanceEnd=2_000_000;
        fog.environmentalStart=1_000_000;fog.environmentalEnd=2_000_000;
    }
}
