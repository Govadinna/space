package dev.seamlessspace.world;
import dev.seamlessspace.universe.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PlanetPhysicsTest {
    @Test void nativeGravityScalesAndOrdinaryWorldRemainsUnchanged() {
        var u=new Universe(7042026,1,true,true);
        assertEquals(1,PlanetPhysics.gravity(null));assertEquals(1,PlanetPhysics.gravity(u.home()));
        for(var p:u.homeSystem()) {
            assertTrue(Double.isFinite(PlanetPhysics.gravity(p)) && PlanetPhysics.gravity(p)>0);
            if(p.body()==Planet.Body.MOON)assertEquals(.165,PlanetPhysics.gravity(p));
            if(p.body()==Planet.Body.MARS)assertEquals(.38,PlanetPhysics.gravity(p));
            if(p.body()==Planet.Body.JUPITER)assertTrue(PlanetPhysics.gravity(p)>2);
            assertEquals(p.body().ordinal()>=6 && p.body().ordinal()<=9,PlanetPhysics.gas(p));
        }
    }
    @Test void newMarsChangesTerrainWithoutChangingTheOldWorldProfile() {
        var mars=new Universe(7042026,1,true,true).homeSystem().stream().filter(p->p.body()==Planet.Body.MARS).findFirst().orElseThrow();
        var old=new Planet("solar-v1/mars",mars.name(),mars.center(),mars.radius(),mars.kind(),mars.seed());
        assertEquals(5,PlanetSurface.profile(old));assertEquals(9,PlanetSurface.profile(mars));
        int differences=0;double min=10000,max=-10000;
        for(int z=-1000;z<=1000;z+=37)for(int x=-1000;x<=1000;x+=43) {
            double h=PlanetSurface.worldHeight(mars,x,z);min=Math.min(h,min);max=Math.max(h,max);
            if(Math.abs(h-PlanetSurface.worldHeight(old,x,z))>10)differences++;
        }
        assertTrue(differences>500);assertTrue(max-min>100);
    }
}
