package dev.seamlessspace.client;
import dev.seamlessspace.universe.*;
import dev.seamlessspace.world.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;

/** Local, bounded visual effects; no lightning entities, damage or global particle clouds. */
final class PlanetEffects {
    private PlanetEffects() { }
    static void tick(Minecraft client,Planet planet) {
        if(PlanetSurface.profile(planet)<7 || client.level==null || client.player==null)return;
        var level=client.level;long tick=level.getGameTime();if(tick%3!=0)return;
        var random=level.getRandom();double px=client.player.getX(),pz=client.player.getZ();
        for(int i=0;i<4;i++) {
            double x=px+random.nextDouble()*64-32,z=pz+random.nextDouble()*64-32;
            int y=PlanetSurface.standingHeight(planet,(int)x,(int)z);
            var p=PlanetSurface.direction(planet,x,z).multiply(planet.radius());int seed=Universe.terrainSeed(planet);
            switch(planet.body()) {
                case VENUS -> {
                    if(Math.abs(SolarTerrain.n(p,180,seed+307))<.055) {
                        level.addParticle(ParticleTypes.FLAME,x,y+.2,z,0,.08,0);
                        level.addParticle(ParticleTypes.SMOKE,x,y+.4,z,0,.05,0);
                    }
                }
                case JUPITER,SATURN -> {
                    if(tick%24==0 && (planet.body()==Planet.Body.JUPITER || SolarTerrain.n(p,400,seed+607)>.2)) {
                        // A tiny segmented spark flash rather than a damaging lightning bolt.
                        for(int j=0;j<12;j++)level.addParticle(ParticleTypes.ELECTRIC_SPARK,x+Math.sin(j)*.3,y+j*2,z,0,.01,0);
                        if(i==0)level.setSkyFlashTime(2);
                    }
                }
                case NEPTUNE -> {
                    double vent=Math.hypot(x-600,z+250);
                    if(vent<18 || PlanetGeology.spike(planet,x,z).distance()<3) {
                        level.addParticle(ParticleTypes.CLOUD,x,y+.5,z,0,.08,0);
                        level.addParticle(ParticleTypes.SNOWFLAKE,x,y+1,z,0,.03,0);
                    }
                }
                case SUN -> {
                    level.addParticle(ParticleTypes.LAVA,x,y+.2,z,0,.1,0);
                    // Smaller particle strands accompany the large GPU plasma arches.
                    double cx=Math.rint(px/128)*128,cz=Math.rint(pz/128)*128;
                    double angle=(tick*.07+i*.8)%(Math.PI),ax=cx+Math.cos(angle)*38;
                    int base=PlanetSurface.standingHeight(planet,(int)ax,(int)cz);
                    level.addParticle(ParticleTypes.FLAME,ax,base+Math.sin(angle)*45,cz,0,.04,0);
                }
                default -> { }
            }
        }
    }
}
