package dev.seamlessspace.flight;

import dev.seamlessspace.universe.DVec3;
import dev.seamlessspace.universe.Planet;
import dev.seamlessspace.universe.Universe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpaceFlightTest {
    private static final SpaceFlight.Input FORWARD = new SpaceFlight.Input(1, 0, 0, false, false, false);

    @Test
    void creativeAxesFollowCameraAndDoNotNeedNativePositionChanges() {
        var f=new SpaceFlight(new Universe(73));
        var start=new DVec3(0,100064,0);f.engage(start,new DVec3(0,0,-1));
        var right=f.right();f.advanceCreative(0,1,0,false,1,.05);
        assertTrue(f.position().subtract(start).dot(right)>0,"D must move camera-right");
        f.engage(start,new DVec3(0,0,-1));f.mouseLook(90,0);
        var heading=f.forward();f.advanceCreative(1,0,0,false,1,.05);
        assertTrue(f.position().subtract(start).dot(heading)>0,"W must follow turned camera");
        var before=f.position();f.advanceCreative(0,0,1,false,1,.05);
        assertTrue(f.position().distance(start)>0 && f.position().y()>before.y(),"Space must ascend without native chunk movement");
    }

    @Test
    void boostedCruiseExceedsOneMillionKilometresPerSecondAndBrakesForApproach() {
        Universe universe = new Universe(17, 1);
        SpaceFlight flight = new SpaceFlight(universe, 200_000_000);
        flight.engage(universe.home().center().add(0, -universe.home().radius() - 300_000_000, 0), new DVec3(0,-1,0));
        var input = new SpaceFlight.Input(1,0,0,true,false,false);
        for (int frame = 0; frame < 90; frame++) flight.tick(1.0/60,input);
        assertTrue(flight.speed() > 1_000_000_000, "blocks are metres: required speed > 1,000,000 km/s");
        assertTrue(flight.speed() <= 2_000_000_001);
        flight.engage(universe.home().center().add(0,-universe.home().radius()-300_000_000,0),DVec3.UP);
        for(int frame=0; frame<1800 && flight.altitude()>40_000; frame++) flight.tick(1.0/60,input);
        assertTrue(flight.altitude() > 30_000, "fast travel must first enter the chunk preparation band");
        assertTrue(flight.speed() <= 14_000);
    }

    @Test
    void mouseSignsAndCameraBasisMatchMinecraft() {
        SpaceFlight flight = new SpaceFlight(new Universe(73));
        flight.engage(new DVec3(0, 10_064, 0), new DVec3(0, 0, -1));
        flight.mouseLook(90, 30);
        assertTrue(flight.forward().x() > 0.8, "positive yaw must turn right");
        assertEquals(-0.5, flight.forward().y(), 1.0e-10, "positive pitch must look down");
        assertBasis(flight);
    }

    @Test
    void highSpeedDescentCannotTunnelThroughASmallPlanet() {
        Universe universe = new Universe(11, 0.0001);
        Planet home = universe.home();
        SpaceFlight flight = new SpaceFlight(universe, 200_000);
        // Approach from below, away from the other planets in the home system.
        flight.engage(home.center().add(0, -home.radius() - 200_000, 0), new DVec3(0, 1, 0));
        SpaceFlight.Input input = new SpaceFlight.Input(0, 0, -1, true, false, false);
        double maximumSpeed = 0;
        for (int tick = 0; tick < 4_000 && !flight.landed(); tick++) {
            flight.tick(1.0 / 30, input);
            maximumSpeed = Math.max(maximumSpeed, flight.speed());
            double radius = flight.position().distance(home.center());
            DVec3 direction = flight.position().subtract(home.center()).normalized();
            assertTrue(radius >= universe.surfaceRadius(home, direction) + SpaceFlight.EYE_HEIGHT - 0.005);
        }
        assertTrue(maximumSpeed > home.radius() * 50, "test must reach a speed that would skip the planet");
        assertTrue(flight.landed(), "descent must stop on the surface");
        assertTrue(flight.altitude() >= 0 && flight.altitude() < 0.1);
    }

    @Test
    void frameRateDoesNotMateriallyChangeFlight() {
        SpaceFlight slow = new SpaceFlight(new Universe(8));
        SpaceFlight fast = new SpaceFlight(new Universe(8));
        DVec3 start = new DVec3(0, 100_064, 0);
        slow.engage(start, new DVec3(0, 0, -1));
        fast.engage(start, new DVec3(0, 0, -1));
        for (int i = 0; i < 80; i++) slow.tick(1.0 / 20, FORWARD);
        for (int i = 0; i < 960; i++) fast.tick(1.0 / 240, FORWARD);
        assertTrue(slow.position().distance(fast.position()) < 2, "20 vs 240 FPS position drift");
        assertTrue(slow.velocity().distance(fast.velocity()) < 1, "20 vs 240 FPS velocity drift");
    }

    @Test
    void walkingFollowsCurvedTerrainAndKeepsAStableFrame() {
        Universe universe = new Universe(91, 0.0001);
        Planet home = universe.home();
        DVec3 direction = new DVec3(1, 0, 0);
        DVec3 initial = home.center().add(direction.multiply(universe.surfaceRadius(home, direction)
                + SpaceFlight.EYE_HEIGHT + 0.035));
        SpaceFlight flight = new SpaceFlight(universe);
        flight.engage(initial, new DVec3(0, 0, 1));
        SpaceFlight.Input walk = new SpaceFlight.Input(1, 0, 0, false, false, true);
        DVec3 previousForward = flight.forward();
        for (int i = 0; i < 1_200; i++) {
            flight.tick(1.0 / 120, walk);
            assertTrue(flight.landed());
            assertEquals(0.035, flight.altitude(), 1.0e-8);
            assertTrue(previousForward.dot(flight.forward()) > 0.99999, "radial frame must not snap");
            assertBasis(flight);
            previousForward = flight.forward();
        }
        assertTrue(flight.position().distance(initial) > 35);
        DVec3 radial = flight.position().subtract(home.center()).normalized();
        assertTrue(radial.dot(flight.radialUp()) > 0.99999);
    }

    @Test
    void walkingGravityReturnsToTheSameSurfaceAndHeldJumpDoesNotRepeat() {
        Universe universe = new Universe(3);
        SpaceFlight flight = new SpaceFlight(universe);
        flight.engage(new DVec3(0, 65.655, 0), new DVec3(0, 0, -1));
        SpaceFlight.Input jumping = new SpaceFlight.Input(0, 0, 1, false, false, true);
        double peak = 0;
        for (int i = 0; i < 240; i++) {
            flight.tick(1.0 / 120, jumping);
            peak = Math.max(peak, flight.altitude());
        }
        assertTrue(peak > 1, "jump must leave the surface");
        assertTrue(flight.landed());
        assertEquals(0.035, flight.altitude(), 1.0e-7);
        assertEquals(0, flight.speed(), 1.0e-7);
    }

    @Test
    void invalidFramesCannotPoisonPositionAndReturnHomeIsSafe() {
        SpaceFlight flight = new SpaceFlight(new Universe(4));
        flight.returnHome();
        DVec3 position = flight.position();
        flight.tick(Double.NaN, FORWARD);
        flight.mouseLook(Double.NaN, Double.POSITIVE_INFINITY);
        assertEquals(position, flight.position());
        assertBasis(flight);
        assertThrows(IllegalArgumentException.class, () -> flight.engage(DVec3.ZERO, DVec3.ZERO));
    }

    @Test void compactDescentFromEightKilometresReachesTheChunkHandoffWithinFourteenSeconds() {
        var u=new Universe(17,1,true);var moon=u.homeSystem().get(1);
        var f=new SpaceFlight(u,200000000);
        f.engage(moon.center().add(0,u.surfaceRadius(moon,DVec3.UP)+8000+SpaceFlight.EYE_HEIGHT,0),DVec3.UP.negate());
        double seconds=0;
        while(f.altitude()>64 && seconds<14) {
            f.tick(1.0/60,new SpaceFlight.Input(0,0,-1,false,false,false));seconds+=1.0/60;
        }
        assertTrue(f.altitude()<=64,"descent took too long: "+f.altitude());
        assertTrue(f.altitude()>=0,"descent must not pass through the surface");
    }

    private static void assertBasis(SpaceFlight flight) {
        assertEquals(1, flight.forward().length(), 1.0e-12);
        assertEquals(1, flight.right().length(), 1.0e-12);
        assertEquals(1, flight.up().length(), 1.0e-12);
        assertEquals(0, flight.forward().dot(flight.right()), 1.0e-12);
        assertEquals(0, flight.forward().dot(flight.up()), 1.0e-12);
        assertEquals(0, flight.right().dot(flight.up()), 1.0e-12);
    }
}
