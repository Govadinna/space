package dev.seamlessspace.world;

import dev.seamlessspace.universe.DVec3;
import dev.seamlessspace.universe.Planet;
import dev.seamlessspace.universe.Universe;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlanetSurfaceTest {
    @Test
    void landingAndTakeoffProjectionRoundTripAtAllLongitudes() {
        Planet planet = new Universe(19).homeSystem().get(1);
        for (double longitude : new double[] {-3.1, -1.2, 0, 0.6, 3.1}) {
            for (double latitude : new double[] {-1.4, -0.7, 0, 0.9, 1.4}) {
                double x = longitude * planet.radius();
                double z = latitude * planet.radius();
                PlanetSurface.Frame frame = PlanetSurface.frame(planet, x, z);
                PlanetSurface.SurfaceCoordinates roundTrip = PlanetSurface.coordinates(planet, frame.radial());
                assertEquals(x, roundTrip.x(), 1.0e-8);
                assertEquals(z, roundTrip.z(), 1.0e-8);
                assertEquals(1, frame.radial().length(), 1.0e-12);
                assertEquals(1, frame.east().length(), 1.0e-12);
                assertEquals(1, frame.north().length(), 1.0e-12);
                assertEquals(0, frame.radial().dot(frame.east()), 1.0e-12);
                assertEquals(0, frame.radial().dot(frame.north()), 1.0e-12);
                assertEquals(0, frame.east().dot(frame.north()), 1.0e-12);
            }
        }
        assertEquals(DVec3.UP, PlanetSurface.direction(planet, 0, 0));
    }

    @Test
    void heightFieldDoesNotIntroduceChunkBoundarySteps() {
        for (Planet planet : new Universe(42).homeSystem()) {
            for (int chunk = -8; chunk <= 8; chunk++) {
                int edge = chunk * 16;
                double before = PlanetSurface.worldHeight(planet, edge - 0.00001, 130);
                double after = PlanetSurface.worldHeight(planet, edge + 0.00001, 130);
                assertEquals(before, after, 0.0001);
            }
            double wrapBefore = PlanetSurface.worldHeight(planet, Math.PI * planet.radius() - 0.00001, 300);
            double wrapAfter = PlanetSurface.worldHeight(planet, -Math.PI * planet.radius() + 0.00001, 300);
            assertEquals(wrapBefore, wrapAfter, 0.0001);
        }
    }

    @Test
    void allKindsHaveReproducibleBoundedReliefAndSafeFluidHeight() {
        Universe first = new Universe(973);
        Universe second = new Universe(973);
        for (int index = 0; index < first.homeSystem().size(); index++) {
            Planet planet = first.homeSystem().get(index);
            int minimum = Integer.MAX_VALUE;
            int maximum = Integer.MIN_VALUE;
            for (int sample = -30; sample < 30; sample++) {
                int x = sample * 7919;
                int z = sample * 1223;
                int ground = PlanetSurface.height(planet, x, z);
                assertEquals(ground, PlanetSurface.height(second.homeSystem().get(index), x, z));
                assertTrue(ground >= PlanetSurface.MIN_Y + 12);
                assertTrue(ground < PlanetSurface.MIN_Y + PlanetSurface.HEIGHT - 30);
                assertTrue(PlanetSurface.standingHeight(planet, x, z) > ground);
                if (planet.kind() == Planet.Kind.OCEAN || planet.kind() == Planet.Kind.LAVA) {
                    assertTrue(PlanetSurface.standingHeight(planet, x, z) > PlanetSurface.SEA_LEVEL);
                }
                minimum = Math.min(minimum, ground);
                maximum = Math.max(maximum, ground);
            }
            assertTrue(maximum - minimum > 10, planet.name() + " should have relief");
        }
    }

    @Test
    void invalidSurfaceCoordinatesAreRejected() {
        Planet planet = new Universe(9).homeSystem().get(1);
        assertThrows(IllegalArgumentException.class, () -> PlanetSurface.direction(planet, Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> PlanetSurface.coordinates(planet, DVec3.ZERO));
    }
}
