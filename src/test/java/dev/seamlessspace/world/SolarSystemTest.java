package dev.seamlessspace.world;
import dev.seamlessspace.universe.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
class SolarSystemTest {
    private final Universe universe=new Universe(7042026,1,true,true);
    @Test void catalogContainsEightPlanetsTheMoonAndARealSunWithDifferentDirections() {
        assertEquals(10,universe.homeSystem().size());
        assertEquals(10,universe.homeSystem().stream().map(Planet::body).distinct().count());
        assertNotNull(universe.sun());
        var bodies=universe.homeSystem();
        for(var a:bodies)for(var b:bodies)if(a!=b) assertTrue(a.center().distance(b.center())>a.radius()+b.radius()*2);
        for(var p:bodies)if(!p.isHome() && p.body()!=Planet.Body.MOON && p.body()!=Planet.Body.SUN)
            assertTrue(p.radius()/p.center().distance(universe.home().center())<.002,"planet must be point sized from Earth");
        assertTrue(bodies.stream().filter(p->p.center().x()<0).count()>1);
    }
    @Test void earthCoversOneHundredThousandBlocksOnBothMapAxes() {
        var earth=universe.home();
        assertEquals(100000,earth.radius()*Math.PI*2,.00001);
        assertEquals(100000,earth.radius()*Math.PI*earth.northScale(),.00001);
        var p=new DVec3(31234.125,250.73,41234.25);
        var restored=PlanetSurface.worldFeet(earth,PlanetSurface.orbitalEye(earth,p,1.62),1.62);
        assertTrue(restored.distance(p)<.000001);
        universe.setHomeHeightSampler(direction->176);
        assertEquals(earth.radius()+176,universe.surfaceRadius(earth,DVec3.UP),.00001);
    }
    @Test void bodyTerrainIsFineDistinctAndPreservesOldProfiles() {
        var signatures=new HashSet<Integer>();
        for(var planet:universe.homeSystem())if(!planet.isHome()) {
            assertEquals(planet.body()==Planet.Body.MARS?9:java.util.Set.of(Planet.Body.SATURN,Planet.Body.NEPTUNE).contains(planet.body())?8:7,PlanetSurface.profile(planet));
            int hash=1,min=9999,max=-9999;
            for(int z=-500;z<=500;z+=27)for(int x=-500;x<=500;x+=31){
                int h=PlanetSurface.height(planet,x,z);hash=31*hash+h;min=Math.min(min,h);max=Math.max(max,h);
                assertTrue(h>=-52 && h<=900);
                if(planet.body()==Planet.Body.SUN)assertEquals(h+1,PlanetSurface.standingHeight(planet,x,z));
            }
            assertTrue(max-min>8,planet.name()+" must have local relief");signatures.add(hash);
        }
        assertEquals(9,signatures.size());
        var old=new Universe(7042026,1,true).homeSystem().get(1);
        double height=PlanetSurface.worldHeight(old,44,97);PlanetSurface.setProfile(old,4);
        assertEquals(height,PlanetSurface.worldHeight(old,44,97));
    }
    @Test void ringNormalsAndPlanetSkiesAreDifferentAndSolarCellsDoNotUseOldWorldIds() {
        var saturn=universe.homeSystem().stream().filter(p->p.body()==Planet.Body.SATURN).findFirst().orElseThrow();
        assertTrue(saturn.hasRings());assertEquals(1,saturn.ringNormal().length(),.000001);
        var mars=universe.homeSystem().stream().filter(p->p.body()==Planet.Body.MARS).findFirst().orElseThrow();
        assertEquals(0xc34e32,PlanetAtmosphere.skyRgb(mars));
        assertEquals(0,PlanetAtmosphere.height(universe.homeSystem().get(1)));
        assertNotEquals(PlanetWorldRegistry.dimensionPath(universe.homeSystem().get(1)),PlanetWorldRegistry.dimensionPath(new Universe(7042026,1,true).homeSystem().get(1)));
    }
}
