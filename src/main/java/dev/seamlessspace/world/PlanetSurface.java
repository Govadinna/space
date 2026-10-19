package dev.seamlessspace.world;

import dev.seamlessspace.universe.DVec3;
import dev.seamlessspace.universe.Planet;
import dev.seamlessspace.universe.Universe;

/**
 * Stable projection and height field shared by landing, takeoff and chunk generation.
 * The chart is centred on +Y, so the same orientation is used as the home world.
 * Heights are compressed into Minecraft's build range; horizontal distances stay in blocks.
 */
public final class PlanetSurface {
    public static final int MIN_Y = -64;
    public static final int HEIGHT = 2096;
    public static final int TERRAIN_MAX_Y = 288;
    public static final int SEA_LEVEL = 96;
    private static final java.util.Map<Planet, Integer> PROFILES = new java.util.concurrent.ConcurrentHashMap<>();

    /** Older visited worlds retain their exact generator, including newly explored chunks. */
    public static int profile(Planet planet) { return PROFILES.getOrDefault(planet, planet.id().startsWith("solar-v5/")?9:planet.id().startsWith("solar-v4/")?8:planet.id().startsWith("solar-v3/")?7:planet.id().startsWith("solar-v2/")?6:planet.isSolar()?5:4); }
    public static void setProfile(Planet planet, int version) {
        if (version < 2 || version > 9) throw new IllegalArgumentException("Unknown terrain profile " + version);
        PROFILES.put(planet, version);
    }
    public static void forgetProfile(Planet planet) { PROFILES.remove(planet); }

    public static boolean cube(Planet planet){return planet.body()==Planet.Body.EARTH;}

    private PlanetSurface() { }

    public record SurfaceCoordinates(double x, double z) { }
    public record Frame(DVec3 radial, DVec3 east, DVec3 north) { }

    public static DVec3 orbitalEye(Planet planet,DVec3 worldFeet,double eyeHeight) {
        return planet.center().add(direction(planet,worldFeet.x(),worldFeet.z()).multiply(
                planet.radius()+worldFeet.y()+eyeHeight-(planet.isHome()?64:SEA_LEVEL)));
    }

    public static DVec3 worldFeet(Planet planet,DVec3 orbitalEye,double eyeHeight) {
        var radial=orbitalEye.subtract(planet.center());
        var coordinates=coordinates(planet,radial);
        return new DVec3(coordinates.x(),radial.length()-planet.radius()+(planet.isHome()?64:SEA_LEVEL)-eyeHeight,coordinates.z());
    }

    public static DVec3 direction(Planet planet, double x, double z) {
        return frame(planet, x, z).radial();
    }

    public static Frame frame(Planet planet, double x, double z) {
        requireFinite(x, z);
        if(cube(planet))return EarthProjection.frame(x,z);
        double longitude = x / planet.radius();
        double latitude = z / (planet.radius()*planet.northScale());
        double sinLongitude = Math.sin(longitude);
        double cosLongitude = Math.cos(longitude);
        double sinLatitude = Math.sin(latitude);
        double cosLatitude = Math.cos(latitude);
        return oriented(planet,new Frame(new DVec3(sinLongitude * cosLatitude, cosLongitude * cosLatitude, sinLatitude),
                new DVec3(cosLongitude, -sinLongitude, 0),
                new DVec3(-sinLongitude * sinLatitude, -cosLongitude * sinLatitude, cosLatitude)));
    }

    private static boolean polarChart(Planet planet){return planet.body()==Planet.Body.SATURN && profile(planet)>=7;}
    private static Frame polarBasis(Planet planet) {
        var axis=planet.ringNormal();var east=new DVec3(0,0,1).cross(axis).normalized();
        return new Frame(axis,east,east.cross(axis));
    }
    private static Frame oriented(Planet planet,Frame frame) {
        if(!polarChart(planet))return frame;
        var b=polarBasis(planet);
        return new Frame(orient(b,frame.radial()),orient(b,frame.east()),orient(b,frame.north()));
    }
    private static DVec3 orient(Frame b,DVec3 p){return b.east().multiply(p.x()).add(b.radial().multiply(p.y())).add(b.north().multiply(p.z()));}

    public static SurfaceCoordinates coordinates(Planet planet, DVec3 direction) {
        if (!direction.isFinite() || direction.lengthSquared() < 1.0e-30) {
            throw new IllegalArgumentException("Surface direction must be finite and nonzero");
        }
        DVec3 unit = direction.normalized();
        if(cube(planet))return EarthProjection.coordinates(unit);
        if(polarChart(planet)) {
            var basis=polarBasis(planet);unit=new DVec3(unit.dot(basis.east()),unit.dot(basis.radial()),unit.dot(basis.north()));
        }
        return new SurfaceCoordinates(Math.atan2(unit.x(), unit.y()) * planet.radius(),
                Math.asin(Math.clamp(unit.z(), -1, 1)) * planet.radius()*planet.northScale());
    }

    /** Topmost solid block, rather than the first air block used by vanilla heightmaps. */
    public static int height(Planet planet, int x, int z) {
        return (int) Math.floor(worldHeight(planet, x, z));
    }

    public static double worldHeight(Planet planet, double x, double z) {
        DVec3 unit = direction(planet, x, z);
        if(profile(planet)>=5 && planet.isSolar()) return SolarTerrain.height(planet,unit);
        int seed = Universe.terrainSeed(planet);
        double continents = 0;
        double frequency = 2.7;
        double amplitude = 0.5;
        double weight = 0;
        for (int octave = 0; octave < 7; octave++) {
            continents += noise(unit, frequency, seed + octave * 1013) * amplitude;
            weight += amplitude;
            amplitude *= 0.5;
            frequency *= 2;
        }
        continents /= weight;
        // Metric frequencies keep hills and ridges visible on physically large planets.
        double hills = noise(unit, planet.radius() / 1300, seed + 17011) * 34;
        double ridges = noise(unit, planet.radius() / 190, seed + 29009) * 11;
        double detail = noise(unit, planet.radius() / 46, seed + 40117) * 2.5;
        double terrain = continents * 74 + hills + ridges + detail;
        if (profile(planet) >= 3) {
            // Continuous ridged fields produce sharp mountain chains and steep
            // canyon walls. The same metric scales drive the orbital renderer.
            double ridge = 1 - Math.abs(noise(unit, planet.radius() / 850, seed + 53017));
            double canyon = noise(unit, planet.radius() / 2400, seed + 67003);
            double rugged = Math.pow(ridge, 10) * 128 - 42 + Math.tanh(canyon * 7) * 30;
            terrain += rugged * (planet.kind() == Planet.Kind.OCEAN ? 0.35 : 1);
        }
        if(profile(planet)>=4 && planet.kind()!=Planet.Kind.OCEAN && planet.kind()!=Planet.Kind.LAVA)
            terrain+=craterRelief(planet,unit);
        double result = switch (planet.kind()) {
            case ROCKY -> SEA_LEVEL + terrain * 1.15;
            case DESERT -> SEA_LEVEL + 12 + terrain * 0.85;
            case ICE -> SEA_LEVEL + 8 + terrain;
            case LAVA -> SEA_LEVEL + terrain * 1.35;
            case OCEAN -> SEA_LEVEL - 15 + terrain * 0.65;
        };
        return Math.clamp(result, MIN_Y + 12, TERRAIN_MAX_Y);
    }

    /** Safe spawn above generated fluid as well as rock. Saved edits use the world's heightmap. */
    public static int standingHeight(Planet planet, int x, int z) {
        int surface = height(planet, x, z);
        return (hasSurfaceFluid(planet)
                ? Math.max(surface, SEA_LEVEL) : surface) + 1;
    }

    public static boolean hasSurfaceFluid(Planet planet) {
        return planet.body()!=Planet.Body.SUN && (planet.kind()==Planet.Kind.OCEAN || planet.kind()==Planet.Kind.LAVA);
    }

    private static double noise(DVec3 unit, double frequency, int seed) {
        return Universe.valueNoise(unit.x() * frequency, unit.y() * frequency, unit.z() * frequency, seed);
    }

    /** Compact crater bowls vanish before their 3D cell boundaries: no UV or tile seams. */
    public static double craterRelief(Planet planet,DVec3 unit) {
        double scale=planet.radius()/4200;
        double x=unit.x()*scale,y=unit.y()*scale,z=unit.z()*scale;
        int hash=hash((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z),Universe.terrainSeed(planet)+103171);
        double dx=x-Math.floor(x)-(.46+((hash>>>8)/16777215.0)*.08);
        hash=mix(hash+1973);
        double dy=y-Math.floor(y)-(.46+((hash>>>8)/16777215.0)*.08);
        hash=mix(hash+9277);
        double dz=z-Math.floor(z)-(.46+((hash>>>8)/16777215.0)*.08);
        double d=Math.sqrt(dx*dx+dy*dy+dz*dz);
        double bowl=-55*(1-smooth(0,.36,d));
        double rim=14*Math.exp(-Math.pow((d-.36)/.038,2));
        return (bowl+rim)*(1-smooth(.41,.45,d));
    }
    private static int hash(int x,int y,int z,int seed) {return mix(seed^x*0x8da6b343^y*0xd8163841^z*0xcb1ab31f);}
    private static int mix(int hash) {hash^=hash>>>16;hash*=0x7feb352d;hash^=hash>>>15;hash*=0x846ca68b;return hash^(hash>>>16);}
    private static double smooth(double a,double b,double x) {double t=Math.clamp((x-a)/(b-a),0,1);return t*t*(3-2*t);}

    private static void requireFinite(double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Surface coordinates must be finite");
        }
    }
}
