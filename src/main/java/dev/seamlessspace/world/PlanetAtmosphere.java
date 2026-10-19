package dev.seamlessspace.world;

import dev.seamlessspace.universe.Planet;

/** Continuous visual atmosphere; Earth uses the conventional 100 km space boundary. */
public final class PlanetAtmosphere {
    private PlanetAtmosphere() { }
    public static double height(Planet planet) {
        if(planet.isSolar()) return switch(planet.body()) {
            case MOON,MERCURY,SUN -> 0;case EARTH -> 2000;case VENUS -> 6000;case MARS -> 1400;default -> 5000;
        };
        if(planet.id().startsWith("compact/")) return switch(planet.kind()) {
            case ROCKY -> 0;case OCEAN -> 2000;case DESERT -> 1400;case ICE -> 1200;case LAVA -> 1800;
        };
        return planet.isHome() ? 100_000 : switch(planet.kind()) {
            case ROCKY -> 0; case OCEAN -> 100_000; case DESERT -> 65_000; case ICE -> 45_000; case LAVA -> 90_000;
        };
    }
    public static double spaceFraction(Planet planet,double altitude) {
        double height=height(planet);
        if(height==0) return 1;
        double t=Math.clamp(altitude/height,0,1);
        return (1-Math.exp(-t/0.22))/(1-Math.exp(-1/0.22));
    }
    public static int skyRgb(Planet planet) {
        if(planet.isSolar()) return switch(planet.body()) {
            case MARS -> 0xc34e32;case VENUS -> 0xc59a50;case JUPITER -> 0xb78968;case SATURN -> 0xd2b877;
            case URANUS -> 0x6ebfc8;case NEPTUNE -> 0x244cba;case SUN -> 0xff5d12;case MOON,MERCURY -> 0;default -> 0x528fd1;
        };
        return switch(planet.kind()) {
            case DESERT -> 0x9c5731; case ICE -> 0x759fc1; case LAVA -> 0xa7461a;
            case OCEAN -> 0x528fd1; case ROCKY -> 0;
        };
    }
}
