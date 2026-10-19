package dev.seamlessspace.world;

import dev.seamlessspace.universe.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlanetAtmosphereTest {
    @Test void earthReachesSpaceAtOneHundredKilometresWithNoTenKilometreCut() {
        var home=new Universe(42,1).home();
        assertEquals(100000,PlanetAtmosphere.height(home));
        assertEquals(0,PlanetAtmosphere.spaceFraction(home,0));
        assertEquals(1,PlanetAtmosphere.spaceFraction(home,100000));
        assertTrue(PlanetAtmosphere.spaceFraction(home,10000)<.5);
        assertEquals(PlanetAtmosphere.spaceFraction(home,10000-.001),PlanetAtmosphere.spaceFraction(home,10000+.001),.000001);
    }
    @Test void thinningIsMonotonicAndAirlessMoonIsAlreadyInSpace() {
        var universe=new Universe(42,1);double previous=-1;
        for(int h=-100;h<=120000;h+=100) {
            double progress=PlanetAtmosphere.spaceFraction(universe.home(),h);
            assertTrue(progress>=previous && progress>=0 && progress<=1);previous=progress;
        }
        assertEquals(1,PlanetAtmosphere.spaceFraction(universe.homeSystem().get(1),0));
    }
    @Test void orbitalAndVanillaCameraCoordinatesRoundTripWithoutHeightSnap() {
        var universe=new Universe(42,1);
        for(var planet:universe.homeSystem()) for(double latitude:new double[]{-1.2,0,.8}) {
            var position=new DVec3(planet.radius()*.7,243.123,planet.radius()*latitude);
            var eye=PlanetSurface.orbitalEye(planet,position,1.62);
            var restored=PlanetSurface.worldFeet(planet,eye,1.62);
            assertTrue(position.distance(restored)<.000002,position+" != "+restored);
        }
    }
}
