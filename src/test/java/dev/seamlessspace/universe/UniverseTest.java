package dev.seamlessspace.universe;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UniverseTest {
    @Test
    void realScaleUsesMetresAndAnchorsTheHomeSurface() {
        Universe universe = new Universe(42, 1);
        assertEquals(6_371_064, universe.home().radius());
        assertEquals(new DVec3(0, -6_371_000, 0), universe.home().center());
        assertEquals(64, universe.home().center().y()
                + universe.surfaceRadius(universe.home(), DVec3.UP), 1.0e-9);
        Planet moon = universe.homeSystem().get(1);
        assertEquals(1_737_400, moon.radius());
        assertTrue(moon.center().distance(universe.home().center()) > 350_000_000);
        assertTrue(moon.center().distance(universe.home().center()) < 420_000_000);
        assertEquals(universe.home(), universe.nearby(new DVec3(0, 64, 0), 1).getFirst());
    }

    @Test
    void scaleChangesGeometryButKeepsLocalGroundHeight() {
        Universe real = new Universe(123, 1);
        Universe playable = new Universe(123, Universe.DEFAULT_SCALE);
        for (int index = 0; index < real.homeSystem().size(); index++) {
            Planet a = real.homeSystem().get(index);
            Planet b = playable.homeSystem().get(index);
            assertEquals(a.radius() * Universe.DEFAULT_SCALE, b.radius(), 1.0e-9);
            assertEquals(a.center().x() * Universe.DEFAULT_SCALE, b.center().x(), 1.0e-9);
            assertEquals((a.center().y() - 64) * Universe.DEFAULT_SCALE, b.center().y() - 64, 1.0e-8);
        }
        assertEquals(64, playable.home().center().y()
                + playable.surfaceRadius(playable.home(), DVec3.UP), 1.0e-9);
    }

    @Test
    void catalogIsDeterministicEvenAfterOtherSectorsAreVisited() {
        Universe first = new Universe(912734);
        Universe second = new Universe(912734);
        DVec3 position = new DVec3(first.sectorSize() * 3.4, first.sectorSize() * -2.7, first.sectorSize() * 8.1);
        List<Planet> expected = first.nearby(position, 32);
        for (int index = 0; index < 20; index++) {
            second.nearby(new DVec3(index * second.sectorSize(), 0, -index * second.sectorSize()), 8);
        }
        assertEquals(expected, second.nearby(position, 32));
        assertNotEquals(expected, new Universe(912735).nearby(position, 32));
    }

    @Test
    void crossingSectorEdgesKeepsTheSameNearbyBodiesWithoutDuplicateIds() {
        Universe universe = new Universe(67239);
        double boundary = universe.sectorSize();
        for (DVec3 location : List.of(new DVec3(boundary, boundary * 0.5, boundary * 0.5),
                new DVec3(boundary * 0.5, boundary + 64, boundary * 0.5),
                new DVec3(boundary * 0.5, boundary * 0.5, boundary))) {
            DVec3 step = location.x() == boundary ? new DVec3(0.001, 0, 0)
                    : location.y() == boundary + 64 ? new DVec3(0, 0.001, 0) : new DVec3(0, 0, 0.001);
            List<Planet> before = universe.nearby(location.subtract(step), 12);
            List<Planet> after = universe.nearby(location.add(step), 12);
            assertEquals(new HashSet<>(before), new HashSet<>(after));
            assertEquals(before.size(), before.stream().map(Planet::id).distinct().count());
        }
    }

    @Test
    void generatedPlanetsHavePhysicalSizesAndNeverOverlap() {
        Universe universe = new Universe(176);
        Map<String, Planet> all = new HashMap<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                DVec3 position = new DVec3((x + 0.5) * universe.sectorSize(),
                        0.5 * universe.sectorSize(), (z + 0.5) * universe.sectorSize());
                for (Planet planet : universe.nearby(position, 32)) {
                    Planet previous = all.put(planet.id(), planet);
                    if (previous != null) {
                        assertEquals(previous, planet);
                    }
                    assertTrue(planet.radius() >= 1_000_000 * universe.scale());
                    assertTrue(planet.radius() <= 7_000_000 * universe.scale());
                }
            }
        }
        List<Planet> planets = List.copyOf(all.values());
        assertTrue(planets.size() > 30);
        for (int a = 0; a < planets.size(); a++) {
            for (int b = a + 1; b < planets.size(); b++) {
                Planet first = planets.get(a);
                Planet second = planets.get(b);
                assertTrue(first.center().distance(second.center()) > first.radius() + second.radius(),
                        first.id() + " overlaps " + second.id());
            }
        }
    }

    @Test
    void nearbyIsOrderedByDistanceToSurfaceAndContainsNoDuplicates() {
        Universe universe = new Universe(99);
        DVec3 query = new DVec3(5_000_000, 2_000_000, 6_000_000);
        List<Planet> planets = universe.nearby(query, 40);
        assertEquals(40, planets.size());
        assertEquals(40, planets.stream().map(Planet::id).distinct().count());
        for (int index = 1; index < planets.size(); index++) {
            assertTrue(planets.get(index - 1).distanceToSurface(query)
                    <= planets.get(index).distanceToSurface(query));
        }
        assertEquals(List.of(), universe.nearby(query, 0));
        assertThrows(IllegalArgumentException.class, () -> universe.nearby(query, -1));
    }

    @Test
    void terrainIsContinuousAtPolesAndAtTheLongitudeWrap() {
        Universe universe = new Universe(729);
        for (Planet planet : universe.homeSystem()) {
            for (DVec3 direction : List.of(DVec3.UP, DVec3.UP.negate(), new DVec3(0, 0.35, -1))) {
                double first = universe.terrainHeight(planet, direction.add(0.0000001, 0, 0));
                double second = universe.terrainHeight(planet, direction.add(-0.0000001, 0, 0));
                assertEquals(first, second, 0.01);
                DVec3 normal = universe.surfaceNormal(planet, direction);
                assertTrue(normal.isFinite());
                assertEquals(1, normal.length(), 1.0e-9);
                assertTrue(normal.dot(direction.normalized()) > 0.5);
            }
            DVec3 direction = new DVec3(0.31, -0.62, 0.93);
            assertEquals(universe.terrainHeight(planet, direction),
                    universe.terrainHeight(planet, direction.multiply(100)), 1.0e-8);
        }
    }

    @Test
    void terrainAndNormalsAreReproducibleAndVaryAcrossTheSurface() {
        Universe first = new Universe(857);
        Universe second = new Universe(857);
        Planet moon = first.homeSystem().get(1);
        Planet sameMoon = second.homeSystem().get(1);
        DVec3 direction = new DVec3(1, 2, 3);
        assertEquals(first.terrainHeight(moon, direction), second.terrainHeight(sameMoon, direction));
        assertEquals(first.surfaceNormal(moon, direction), second.surfaceNormal(sameMoon, direction));
        assertNotEquals(first.terrainHeight(moon, DVec3.UP), first.terrainHeight(moon, direction));
        assertTrue(Math.abs(first.terrainHeight(moon, direction)) <= first.terrainAmplitudeBound(moon));
    }

    @Test
    void realScaleGroundRetainsLocalReliefWithinAHundredMetres() {
        Universe universe = new Universe(857, 1);
        Planet moon = universe.homeSystem().get(1);
        double maximumCurvature = 0;
        for (int index = 0; index < 12; index++) {
            double offset = index * 100.0;
            double before = universe.terrainHeight(moon, new DVec3(offset / moon.radius(), 1, 0));
            double middle = universe.terrainHeight(moon, new DVec3((offset + 100) / moon.radius(), 1, 0));
            double after = universe.terrainHeight(moon, new DVec3((offset + 200) / moon.radius(), 1, 0));
            maximumCurvature = Math.max(maximumCurvature, Math.abs(before - 2 * middle + after));
        }
        assertTrue(maximumCurvature > 0.5, "Earth-scale rendering must retain terrain features near the player");
    }

    @Test
    void rayIntersectionHandlesOutsideInsideTangentMissAndLargeCoordinates() {
        assertEquals(8, Universe.raySphere(new DVec3(0, 0, 10), new DVec3(0, 0, -2), DVec3.ZERO, 2));
        assertEquals(2, Universe.raySphere(DVec3.ZERO, DVec3.UP, DVec3.ZERO, 2));
        assertEquals(4, Universe.raySphere(new DVec3(-4, 1, 0), new DVec3(3, 0, 0), DVec3.ZERO, 1));
        assertEquals(Double.POSITIVE_INFINITY,
                Universe.raySphere(new DVec3(-4, 2, 0), new DVec3(1, 0, 0), DVec3.ZERO, 1));
        assertEquals(Double.POSITIVE_INFINITY,
                Universe.raySphere(new DVec3(0, 0, 10), DVec3.UP, DVec3.ZERO, 2));
        assertEquals(Double.POSITIVE_INFINITY, Universe.raySphere(DVec3.ZERO, DVec3.ZERO, DVec3.ZERO, 2));
        assertEquals(99_000_000, Universe.raySphere(new DVec3(1.0e12, 0, 0),
                new DVec3(1, 0, 0), new DVec3(1.0e12 + 100_000_000, 0, 0), 1_000_000));
    }
    @Test void compactNeighboursAreTensOfThousandsOfBlocksAwayAndNeverIntersect() {
        var u=new Universe(42,1,true);
        for(var p:u.homeSystem()) {
            if(p.isHome()) continue;
            double gap=p.center().distance(u.home().center())-p.radius()-u.home().radius();
            assertTrue(gap>=25000 && gap<40000,"surface gap: "+gap);
        }
        for(var a:u.homeSystem()) for(var b:u.homeSystem()) if(a!=b)
            assertTrue(a.center().distance(b.center())>a.radius()+b.radius()+20000);
        assertEquals(30000,u.homeSystem().get(1).distanceToSurface(new DVec3(0,64,0)),.00001);
        assertEquals(u.homeSystem(),new Universe(42,1,true).homeSystem());
        var generated=new java.util.HashMap<String,Planet>();
        for(int x=-1;x<=1;x++) for(int z=-1;z<=1;z++)
            for(var p:u.nearby(new DVec3((x+.5)*u.sectorSize(),u.sectorSize()*.5,(z+.5)*u.sectorSize()),32))
                generated.put(p.id(),p);
        var bodies=List.copyOf(generated.values());
        for(int a=0;a<bodies.size();a++) for(int b=a+1;b<bodies.size();b++) {
            var one=bodies.get(a);var two=bodies.get(b);
            assertTrue(one.center().distance(two.center())>one.radius()+two.radius(),"generated compact planets overlap");
        }

    }
    @Test void compactLayoutRetainsLegacyWorldsWithDistinctIdsAndStableDescriptors() {
        var old=new Universe(42,1);
        var compact=new Universe(42,1,true);
        var moon=old.homeSystem().get(1);
        compact.restoreSavedPlanets(List.of(moon,compact.homeSystem().get(1)));
        assertEquals(7,compact.targets().size());
        assertEquals(moon,compact.targets().getLast());
        assertTrue(compact.nearby(moon.center().add(0,moon.radius()+100,0),8).contains(moon));
        assertNotEquals(dev.seamlessspace.world.PlanetWorldRegistry.dimensionPath(moon),
                dev.seamlessspace.world.PlanetWorldRegistry.dimensionPath(compact.homeSystem().get(1)));
    }

}
