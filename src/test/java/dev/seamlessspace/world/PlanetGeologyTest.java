package dev.seamlessspace.world;
import dev.seamlessspace.universe.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PlanetGeologyTest {
    private final Universe universe=new Universe(7042026,1,true,true);
    private Planet planet(Planet.Body body){return universe.homeSystem().stream().filter(p->p.body()==body).findFirst().orElseThrow();}
    private double h(Planet p,double x,double z){return PlanetSurface.worldHeight(p,x,z);}
    @Test void savedVersionsRemainSelectableWithoutMaskingTheNewTerrain() {
        var moon=planet(Planet.Body.MOON);var old=new Planet("solar-v2/moon",moon.name(),moon.center(),moon.radius(),moon.kind(),moon.seed());
        universe.restoreSavedPlanets(java.util.List.of(old));
        var position=moon.center().add(DVec3.UP.multiply(moon.radius()+500));
        assertEquals(moon,universe.nearby(position,1).getFirst());
        universe.selectPlanet(old);assertEquals(old,universe.nearby(position,1).getFirst());
        assertEquals(1,universe.nearby(position,16).stream().filter(p->p.center().equals(moon.center())).count());
        universe.selectPlanet(moon);assertEquals(moon,universe.nearby(position,1).getFirst());
        assertTrue(universe.targets().contains(old));
        var sun=planet(Planet.Body.SUN);var oldSun=new Planet("solar-v2/sun",sun.name(),sun.center(),sun.radius(),sun.kind(),sun.seed());
        universe.restoreSavedPlanets(java.util.List.of(old,oldSun));universe.selectPlanet(oldSun);
        assertEquals(oldSun,universe.sun());
        assertEquals(1,universe.renderBodies(sun.center().add(DVec3.UP.multiply(sun.radius()*2)),16).stream().filter(p->p.center().equals(sun.center())).count());
    }
    @Test void lunarCraterHasCentralPeakRaisedRimRaysAndPolarIce() {
        var moon=planet(Planet.Body.MOON);
        assertTrue(h(moon,0,0)>h(moon,60,0)+12,"central peak must stand above bowl");
        assertTrue(h(moon,180,0)>h(moon,240,0)+5,"rim must rise above surrounding plain");
        var ray=PlanetSurface.direction(moon,350,0);
        assertTrue(PlanetGeology.rays(moon,ray)>.2);
        assertEquals(PlanetMaterial.CALCITE,PlanetGeology.material(moon,ray,120));
        for(int sign:new int[]{-1,1})assertEquals(PlanetMaterial.PACKED_ICE,PlanetGeology.material(moon,PlanetSurface.direction(moon,0,sign*moon.radius()*1.45),70));
    }
    @Test void mercuryScarpAndVenusCalderaHaveDistinctProfiles() {
        var mercury=planet(Planet.Body.MERCURY);
        double cliff=h(mercury,30,0)-h(mercury,-30,0);
        assertTrue(cliff>20 && cliff<45,"scarp must be roughly 20-40 blocks");
        var venus=planet(Planet.Body.VENUS);
        assertTrue(h(venus,100,0)>h(venus,0,0)+25,"caldera sits below volcanic rim");
        assertTrue(h(venus,1400,400)>h(venus,1610,400)+30,"pancake dome has a steep edge");
    }
    @Test void quarriesStormBasinsAndPolarHexagonHaveRealRelief() {
        var mars=planet(Planet.Body.MARS);assertTrue(h(mars,0,0)<h(mars,210,0)-40);
        assertTrue(java.util.Set.of(PlanetMaterial.SNOW,PlanetMaterial.PACKED_ICE).contains(PlanetGeology.material(mars,PlanetSurface.direction(mars,0,mars.radius()*1.5),120)));
        var jupiter=planet(Planet.Body.JUPITER);assertTrue(h(jupiter,0,0)<h(jupiter,1050,0)-50);
        var saturn=planet(Planet.Body.SATURN);assertTrue(h(saturn,500,0)>h(saturn,0,0)+25);
        assertTrue(PlanetSurface.direction(saturn,0,0).distance(saturn.ringNormal())<.000001);
        var original=new DVec3(125,300,-340);
        assertTrue(PlanetSurface.worldFeet(saturn,PlanetSurface.orbitalEye(saturn,original,1.62),1.62).distance(original)<.000001);
    }
    @Test void neptuneHasTwentyToFortyBlockNeedlesAndSunHasDifferentGranules() {
        var neptune=planet(Planet.Body.NEPTUNE);double max=0;
        for(int z=0;z<128;z++)for(int x=0;x<128;x++)max=Math.max(max,PlanetGeology.spike(neptune,x,z).height());
        assertTrue(max>=20 && max<=40);
        var sun=planet(Planet.Body.SUN);assertEquals(PlanetMaterial.BLACKSTONE,PlanetMaterials.top(sun,0,0,80));
        var materials=new java.util.HashSet<PlanetMaterial>();
        for(int z=200;z<600;z+=11)for(int x=200;x<600;x+=13)materials.add(PlanetMaterials.top(sun,x,z,(int)h(sun,x,z)));
        assertTrue(materials.contains(PlanetMaterial.SUN_PLASMA) && materials.contains(PlanetMaterial.MAGMA));
    }
    @Test void clusteredCrystalsHaveBroadShouldersAndPreserveOldWorlds() {
        var p=planet(Planet.Body.NEPTUNE);
        var old=new Planet("solar-v3/neptune",p.name(),p.center(),p.radius(),p.kind(),p.seed());
        int broad=0,thin=0;boolean snow=false,blue=false;
        for(int z=0;z<192;z++)for(int x=0;x<192;x++) {
            double a=PlanetGeology.spike(p,x,z).height(),b=PlanetGeology.spike(old,x,z).height();
            if(a>10)broad++;if(b>10)thin++;
            var mat=PlanetMaterials.top(p,x,z,PlanetSurface.height(p,x,z));
            snow|=mat==PlanetMaterial.SNOW;blue|=mat==PlanetMaterial.BLUE_ICE;
        }
        assertTrue(broad>thin*3,"clusters must have broad, visible shoulders");
        assertTrue(snow && blue,"crystals need snow caps and exposed blue ice");
        assertEquals(7,PlanetSurface.profile(old));assertEquals(8,PlanetSurface.profile(p));
    }
    @Test void marsHasFlatPlainsAndSteepMesaEdges() {
        var p=planet(Planet.Body.MARS);int flat=0,cliffs=0;
        for(int z=2000;z<4000;z+=8)for(int x=2000;x<4000;x+=8){
            double delta=Math.abs(h(p,x,z)-h(p,x+8,z));
            if(delta<1.5)flat++;if(delta>12)cliffs++;
        }
        assertTrue(flat>15000,"plains should dominate between isolated mesas");
        assertTrue(cliffs>100,"mesa boundaries must contain steep voxel cliffs");
    }
    @Test void lavaTubeHasARoofAndASeparateOpenShaft() {
        var moon=planet(Planet.Body.MOON);
        var c=PlanetGeology.cavity(moon,80,225,(int)h(moon,80,225));
        assertTrue(c.maxY()-c.minY()>=8);assertTrue(c.maxY()<h(moon,80,225)-6);
        assertTrue(h(moon,96,225)<h(moon,80,225)-10);
        var old=new Planet("solar-v2/moon",moon.name(),moon.center(),moon.radius(),moon.kind(),moon.seed());
        assertEquals(6,PlanetSurface.profile(old));assertTrue(PlanetGeology.cavity(old,80,225,100).maxY()<PlanetGeology.cavity(old,80,225,100).minY());
    }
}
