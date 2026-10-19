package dev.seamlessspace.world;
import dev.seamlessspace.universe.Planet;
public final class PlanetPhysics {
    private PlanetPhysics() { }
    public static double gravity(Planet p) {
        if(p==null)return 1;
        return switch(p.body()) {
            case MERCURY,MARS -> .38;case VENUS -> .904;case EARTH -> 1;case MOON -> .165;
            case JUPITER -> 2.528;case SATURN -> 1.065;case URANUS -> .886;case NEPTUNE -> 1.137;
            case SUN -> 2.8;
            default -> switch(p.kind()) {case ROCKY -> .3;case ICE -> .5;case DESERT -> .7;case LAVA -> 1.3;default -> 1;};
        };
    }
    public static boolean gas(Planet p) {return p!=null && switch(p.body()) {case JUPITER,SATURN,URANUS,NEPTUNE -> true;default -> false;};}
}
