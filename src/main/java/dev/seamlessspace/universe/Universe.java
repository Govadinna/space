package dev.seamlessspace.universe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * A lazy, deterministic universe. One block represents one metre at scale 1.
 * Only descriptions of nearby planets are created: no planet-sized voxel arrays.
 */
public final class Universe {
    public static final double REAL_HOME_RADIUS = 6_371_064;
    public static final double REAL_SECTOR_SIZE = 10_000_000_000.0;
    public static final double DEFAULT_SCALE = 0.02;
    private static final int MAX_QUERY_COUNT = 64;
    private static final int SECTOR_CACHE_SIZE = 128;
    private static final int OCTAVES = 7;
    private static final double BASE_FREQUENCY = 2.7;

    private final long seed;
    private final double scale;
    private final boolean compact;
    private final boolean solar;
    private java.util.function.ToDoubleFunction<DVec3> homeHeightSampler;
    private record Geometry(DVec3 center,double radius) { }
    private final Map<Geometry,Planet> selectedVersions=new java.util.concurrent.ConcurrentHashMap<>();
    public void selectPlanet(Planet planet){selectedVersions.put(new Geometry(planet.center(),planet.radius()),planet);}
    private volatile List<Planet> savedPlanets=List.of();
    private volatile List<Planet> travelTargets;
    private final double sectorSize;
    private final List<Planet> homeSystem;
    private final Planet sun;
    private final double homeTerrainOffset;
    private final Map<Sector, List<Planet>> sectorCache = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Sector, List<Planet>> eldest) {
            return size() > SECTOR_CACHE_SIZE;
        }
    };

    public Universe(long seed, double scale) {
        this(seed,scale,false);
    }

    public Universe(long seed,double scale,boolean compact) {this(seed,scale,compact,false);}

    public Universe(long seed,double scale,boolean compact,boolean solar) {
        if (!Double.isFinite(scale) || scale < 0.0001 || scale > 100) {
            throw new IllegalArgumentException("Universe scale must be between 0.0001 and 100");
        }
        this.seed = seed;
        this.scale = scale;
        this.compact=compact;this.solar=solar;
        this.sectorSize = solar ? 100000000 : compact ? 600000 : REAL_SECTOR_SIZE * scale;
        this.homeSystem = solar ? createSolarSystem() : compact ? createCompactSystem() : createHomeSystem();
        this.sun=homeSystem.stream().filter(p->p.body()==Planet.Body.SUN).findFirst().orElse(null);
        this.travelTargets=homeSystem;
        this.homeTerrainOffset = unshiftedTerrainHeight(home(), DVec3.UP);
    }

    public Universe(long seed) {
        this(seed, DEFAULT_SCALE);
    }

    public long seed() {
        return seed;
    }

    public double scale() {
        return scale;
    }

    public double sectorSize() {
        return sectorSize;
    }
    public boolean compact() { return compact; }
    public void setHomeHeightSampler(java.util.function.ToDoubleFunction<DVec3> sampler){homeHeightSampler=sampler;}
    public Planet sun() {return sun==null?null:selectedVersions.getOrDefault(new Geometry(sun.center(),sun.radius()),sun);}
    public List<Planet> renderBodies(DVec3 camera,int count) {
        var result=new ArrayList<>(nearby(camera,count));var sun=sun();
        if(sun!=null && !result.contains(sun)) {if(result.size()>=count) result.removeLast();result.add(sun);}
        return List.copyOf(result);
    }
    public double approachBand() { return compact ? 8000 : 32000; }

    /** Keep older worlds addressable without changing their descriptors or chunk seeds. */
    public void restoreSavedPlanets(List<Planet> planets) {
        var ids=homeSystem.stream().map(Planet::id).collect(java.util.stream.Collectors.toSet());
        savedPlanets=planets.stream().filter(p -> !ids.contains(p.id()))
                .sorted(Comparator.comparing(Planet::id)).toList();
        var targets=new ArrayList<>(homeSystem);targets.addAll(savedPlanets);travelTargets=List.copyOf(targets);
    }
    public List<Planet> targets() {
        return travelTargets;
    }

    public Planet home() {
        return homeSystem.getFirst();
    }

    public List<Planet> homeSystem() {
        return homeSystem;
    }

    /**
     * Nearest surfaces first. Querying adjoining sectors never changes a planet's
     * identity, position or relief, and no id appears twice in one result.
     */
    public List<Planet> nearby(DVec3 position, int limit) {
        if (!position.isFinite()) {
            throw new IllegalArgumentException("Query position must be finite");
        }
        if (limit < 0 || limit > MAX_QUERY_COUNT) {
            throw new IllegalArgumentException("Planet limit must be between 0 and " + MAX_QUERY_COUNT);
        }
        if (limit == 0) {
            return List.of();
        }
        Sector current = sectorAt(position);
        List<Planet> candidates = new ArrayList<>(180);
        candidates.addAll(homeSystem);
        candidates.addAll(savedPlanets);
        for (long dx = -1; dx <= 1; dx++) {
            for (long dy = -1; dy <= 1; dy++) {
                for (long dz = -1; dz <= 1; dz++) {
                    Sector sector = new Sector(current.x + dx, current.y + dy, current.z + dz);
                    // The origin sector contains the explicit home system.
                    if (sector.x == 0 && sector.y == 0 && sector.z == 0) {
                        continue;
                    }
                    candidates.addAll(planetsIn(sector));
                }
            }
        }
        if(!savedPlanets.isEmpty()) {
            var unique=new LinkedHashMap<String,Planet>();
            for(var planet:candidates) unique.putIfAbsent(planet.id(),planet);
            var geometry=new LinkedHashMap<Geometry,Planet>();
            for(var planet:unique.values()) {
                var key=new Geometry(planet.center(),planet.radius());
                if(!geometry.containsKey(key) || planet.equals(selectedVersions.get(key)))geometry.put(key,planet);
            }
            candidates=new ArrayList<>(geometry.values());
        }
        candidates.sort(Comparator.comparingDouble((Planet planet) -> planet.distanceToSurface(position))
                .thenComparing(Planet::id));
        return List.copyOf(candidates.subList(0, Math.min(limit, candidates.size())));
    }

    /**
     * Seam-free terrain: noise is sampled in 3D along the normalized sphere
     * direction, never on an equirectangular UV map. There is no pole seam.
     */
    public double terrainHeight(Planet planet, DVec3 direction) {
        DVec3 unit = requireDirection(direction);
        if (!planet.isHome()) {
            var coordinates = dev.seamlessspace.world.PlanetSurface.coordinates(planet, unit);
            double height = dev.seamlessspace.world.PlanetSurface.worldHeight(planet, coordinates.x(), coordinates.z());
            if (dev.seamlessspace.world.PlanetSurface.hasSurfaceFluid(planet)) height = Math.max(height, 96);
            return height + 1 - 96;
        }
        return homeHeightSampler==null ? 0 : homeHeightSampler.applyAsDouble(unit);
    }

    public double surfaceRadius(Planet planet, DVec3 direction) {
        return planet.radius() + terrainHeight(planet, direction);
    }

    public double relief(Planet planet) {
        double amplitude = Math.clamp(planet.radius() * 0.0025, 100, 3500);
        return switch (planet.kind()) {
            case ROCKY -> amplitude;
            case DESERT -> amplitude * 0.65;
            case ICE -> amplitude * 0.7;
            case LAVA -> amplitude * 1.1;
            case OCEAN -> amplitude * 0.45;
        };
    }

    /** Metric terrain remains visible near the ground even on an Earth-sized sphere. */
    public static double detailScale(Planet planet) {
        return Math.min(1, planet.radius() / 10_000) * (planet.kind() == Planet.Kind.OCEAN ? 0.4 : 1);
    }

    /** Absolute bound before the home planet's constant altitude correction. */
    public double terrainAmplitudeBound(Planet planet) {
        if(planet.isSolar()) return planet.isHome()?2100:1100;
        return relief(planet) + 82 * detailScale(planet);
    }

    /** Height removed on Home so that the north pole still meets vanilla ground at y=64. */
    public double terrainBaseOffset(Planet planet) {
        if (!planet.isHome()) return 0;
        return planet.equals(home()) ? homeTerrainOffset : unshiftedTerrainHeight(planet, DVec3.UP);
    }

    /** Finite differences on the actual height field, with an outward normal. */
    public DVec3 surfaceNormal(Planet planet, DVec3 direction) {
        DVec3 unit = requireDirection(direction);
        DVec3 helper = Math.abs(unit.y()) < 0.9 ? DVec3.UP : new DVec3(1, 0, 0);
        DVec3 tangent = helper.cross(unit).normalized();
        DVec3 bitangent = unit.cross(tangent).normalized();
        double step = Math.clamp(1.0 / planet.radius(), 1.0e-7, 0.001);
        DVec3 left = surfacePoint(planet, unit.subtract(tangent.multiply(step)));
        DVec3 right = surfacePoint(planet, unit.add(tangent.multiply(step)));
        DVec3 down = surfacePoint(planet, unit.subtract(bitangent.multiply(step)));
        DVec3 up = surfacePoint(planet, unit.add(bitangent.multiply(step)));
        DVec3 normal = right.subtract(left).cross(up.subtract(down)).normalized();
        return normal.dot(unit) < 0 ? normal.negate() : normal;
    }

    /** Position relative to the planet center, preserving precision far from origin. */
    public DVec3 surfacePoint(Planet planet, DVec3 direction) {
        DVec3 unit = requireDirection(direction);
        return unit.multiply(surfaceRadius(planet, unit));
    }

    /**
     * First non-negative intersection distance, or positive infinity on a miss.
     * The direction need not be normalized; the answer is still a block distance.
     * A ray beginning inside a sphere returns the outward exit intersection.
     */
    public static double raySphere(DVec3 origin, DVec3 direction, DVec3 center, double radius) {
        if (!origin.isFinite() || !direction.isFinite() || !center.isFinite()
                || !Double.isFinite(radius) || radius <= 0 || direction.lengthSquared() <= 1.0e-30) {
            return Double.POSITIVE_INFINITY;
        }
        DVec3 unit = direction.normalized();
        DVec3 offset = origin.subtract(center);
        double projection = offset.dot(unit);
        // Calculate the perpendicular distance instead of subtracting two huge
        // nearly equal squared lengths (important at astronomical distances).
        double perpendicularSquared = offset.subtract(unit.multiply(projection)).lengthSquared();
        double discriminant = radius * radius - perpendicularSquared;
        if (discriminant < 0) {
            return Double.POSITIVE_INFINITY;
        }
        double halfChord = Math.sqrt(discriminant);
        double near = -projection - halfChord;
        double far = -projection + halfChord;
        return near >= 0 ? near : far >= 0 ? far : Double.POSITIVE_INFINITY;
    }

    /** Low 32 bits of the mixed planet seed, also passed to the GLSL renderer. */
    public static int terrainSeed(Planet planet) {
        return (int) (planet.seed() ^ (planet.seed() >>> 32));
    }

    private double unshiftedTerrainHeight(Planet planet, DVec3 unit) {
        int noiseSeed = terrainSeed(planet);
        double broadFrequency = planet.radius() / 1500;
        double fineFrequency = planet.radius() / 200;
        double detail = valueNoise(unit.x() * broadFrequency, unit.y() * broadFrequency,
                unit.z() * broadFrequency, noiseSeed + 17011) * 70
                + valueNoise(unit.x() * fineFrequency, unit.y() * fineFrequency,
                unit.z() * fineFrequency, noiseSeed + 29009) * 12;
        return terrainShape(planet, unit) * relief(planet) + detail * detailScale(planet);
    }

    private double terrainShape(Planet planet, DVec3 unit) {
        int noiseSeed = terrainSeed(planet);
        double frequency = BASE_FREQUENCY;
        double amplitude = 0.5;
        double sum = 0;
        double weight = 0;
        for (int octave = 0; octave < OCTAVES; octave++) {
            sum += valueNoise(unit.x() * frequency, unit.y() * frequency, unit.z() * frequency,
                    noiseSeed + octave * 1013) * amplitude;
            weight += amplitude;
            frequency *= 2;
            amplitude *= 0.5;
        }
        return sum / weight;
    }

    /**
     * GPU parity: lattice hash starts with seed XOR x*0x8da6b343 XOR
     * y*0xd8163841 XOR z*0xcb1ab31f. Then xor>>16, multiply0x7feb352d,
     * xor>>15, multiply0x846ca68b, xor>>16; all operations wrap at 32 bits.
     * Convert the upper24 bits to [-1,1] using /16777215.0*2-1.
     * Eight lattice values are mixed x, y, z using t*t*(3-2*t).
     */
    public static double valueNoise(double x, double y, double z, int noiseSeed) {
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        int iz = (int) Math.floor(z);
        double tx = smooth(x - ix);
        double ty = smooth(y - iy);
        double tz = smooth(z - iz);
        double bottom = mix(mix(lattice(ix, iy, iz, noiseSeed), lattice(ix + 1, iy, iz, noiseSeed), tx),
                mix(lattice(ix, iy + 1, iz, noiseSeed), lattice(ix + 1, iy + 1, iz, noiseSeed), tx), ty);
        double top = mix(mix(lattice(ix, iy, iz + 1, noiseSeed), lattice(ix + 1, iy, iz + 1, noiseSeed), tx),
                mix(lattice(ix, iy + 1, iz + 1, noiseSeed), lattice(ix + 1, iy + 1, iz + 1, noiseSeed), tx), ty);
        return mix(bottom, top, tz);
    }

    private static double lattice(int x, int y, int z, int seed) {
        int hash = seed ^ (x * 0x8da6b343) ^ (y * 0xd8163841) ^ (z * 0xcb1ab31f);
        hash ^= hash >>> 16;
        hash *= 0x7feb352d;
        hash ^= hash >>> 15;
        hash *= 0x846ca68b;
        hash ^= hash >>> 16;
        return (hash >>> 8) / 16777215.0 * 2 - 1;
    }

    private static double smooth(double fraction) {
        return fraction * fraction * (3 - 2 * fraction);
    }

    private static double mix(double from, double to, double fraction) {
        return from + (to - from) * fraction;
    }

    private static DVec3 requireDirection(DVec3 direction) {
        if (!direction.isFinite() || direction.lengthSquared() < 1.0e-30) {
            throw new IllegalArgumentException("Surface direction must be finite and nonzero");
        }
        return direction.normalized();
    }

    private List<Planet> createHomeSystem() {
        double radius = REAL_HOME_RADIUS * scale;
        return List.of(
                new Planet("home", "Home", new DVec3(0, 64 - radius, 0), radius,
                        Planet.Kind.OCEAN, mixSeed(seed ^ 0x193bde47L)),
                localPlanet("moon", "Selene", 180_000_000, 110_000_000, 320_000_000,
                        1_737_400, Planet.Kind.ROCKY, 1),
                localPlanet("red", "Ares", -420_000_000, 340_000_000, 180_000_000,
                        3_389_500, Planet.Kind.DESERT, 2),
                localPlanet("ice", "Nivalis", 630_000_000, 250_000_000, -510_000_000,
                        2_634_100, Planet.Kind.ICE, 3),
                localPlanet("lava", "Pyra", -740_000_000, 620_000_000, -450_000_000,
                        4_800_000, Planet.Kind.LAVA, 4),
                localPlanet("ocean", "Pelagia", 900_000_000, 540_000_000, 860_000_000,
                        6_051_800, Planet.Kind.OCEAN, 5));
    }

    private List<Planet> createSolarSystem() {
        double radius=100000/(Math.PI*2);
        return List.of(new Planet("solar-v1/earth","Земля",new DVec3(0,64-radius,0),radius,Planet.Kind.OCEAN,mixSeed(seed^0x193bde47L)),
                solarPlanet("moon","Луна",180000,110000,320000,3500,Planet.Kind.ROCKY,1),
                solarPlanet("mercury","Меркурий",2500000,-300000,-900000,2200,Planet.Kind.ROCKY,2),
                solarPlanet("venus","Венера",-5000000,1000000,3000000,5200,Planet.Kind.DESERT,3),
                solarPlanet("mars","Марс",4500000,650000,6000000,4000,Planet.Kind.DESERT,4),
                solarPlanet("jupiter","Юпитер",12000000,-2000000,7000000,13000,Planet.Kind.DESERT,5),
                solarPlanet("saturn","Сатурн",18000000,4000000,-11000000,11000,Planet.Kind.DESERT,6),
                solarPlanet("uranus","Уран",-25000000,1500000,16000000,7800,Planet.Kind.ICE,7),
                solarPlanet("neptune","Нептун",28000000,-5000000,-22000000,7600,Planet.Kind.ICE,8),
                solarPlanet("sun","Солнце",1000000,250000,-800000,10000,Planet.Kind.LAVA,9));
    }
    private Planet solarPlanet(String id,String name,double x,double y,double z,double radius,Planet.Kind kind,long salt) {
        return new Planet((id.equals("mars")?"solar-v5/":java.util.Set.of("saturn","neptune").contains(id)?"solar-v4/":"solar-v3/")+id,name,new DVec3(x,64+y,z),radius,kind,mixSeed(seed+salt));
    }

    /** Playable distances are independent of the saved astronomical seed/scale. */
    private List<Planet> createCompactSystem() {
        return List.of(new Planet("compact/home","Home",new DVec3(0,64-12000,0),12000,Planet.Kind.OCEAN,mixSeed(seed^0x193bde47L)),
                compactPlanet("moon","Selene",0,33500,0,3500,Planet.Kind.ROCKY,1),
                compactPlanet("red","Ares",40000,4000,0,4000,Planet.Kind.DESERT,2),
                compactPlanet("ice","Nivalis",-40000,5000,0,5000,Planet.Kind.ICE,3),
                compactPlanet("lava","Pyra",0,6000,48000,6000,Planet.Kind.LAVA,4),
                compactPlanet("ocean","Pelagia",0,7000,-50000,7000,Planet.Kind.OCEAN,5));
    }
    private Planet compactPlanet(String id,String name,double x,double y,double z,double radius,Planet.Kind kind,long salt) {
        return new Planet("compact/"+id,name,new DVec3(x,64+y,z),radius,kind,mixSeed(seed+salt));
    }

    private Planet localPlanet(String id, String name, double x, double y, double z,
                               double radius, Planet.Kind kind, long salt) {
        return new Planet(id, name, new DVec3(x * scale, 64 + y * scale, z * scale),
                radius * scale, kind, mixSeed(seed + salt));
    }

    private Sector sectorAt(DVec3 position) {
        return new Sector((long) Math.floor(position.x() / sectorSize),
                (long) Math.floor((position.y() - 64) / sectorSize),
                (long) Math.floor(position.z() / sectorSize));
    }

    private synchronized List<Planet> planetsIn(Sector sector) {
        return sectorCache.computeIfAbsent(sector, this::createSector);
    }

    private List<Planet> createSector(Sector sector) {
        long systemSeed = mixSeed(seed ^ mixSeed(sector.x * 0x632be59bd9b4e019L)
                ^ mixSeed(sector.y * 0x9e3779b97f4a7c15L) ^ mixSeed(sector.z * 0x94d049bb133111ebL));
        SplittableRandom random = new SplittableRandom(systemSeed);
        DVec3 center = new DVec3((sector.x + 0.35 + random.nextDouble() * 0.3) * sectorSize,
                64 + (sector.y + 0.35 + random.nextDouble() * 0.3) * sectorSize,
                (sector.z + 0.35 + random.nextDouble() * 0.3) * sectorSize);
        int count = 3 + random.nextInt(4);
        List<Planet> planets = new ArrayList<>(count);
        double inclination = random.nextDouble(-0.5, 0.5);
        double sinInclination = Math.sin(inclination);
        double cosInclination = Math.cos(inclination);
        String systemId = sector.x + "/" + sector.y + "/" + sector.z;
        String name = String.format(java.util.Locale.ROOT, "SS-%06X", systemSeed & 0xffffffL);
        for (int i = 0; i < count; i++) {
            // Radial separation exceeds the sum of any two generated radii.
            double orbit = compact ? 30000+i*30000+random.nextDouble()*3000
                    : (90_000_000 + i * 90_000_000 + random.nextDouble() * 20_000_000) * scale;
            double angle = random.nextDouble(0, Math.PI * 2);
            double along = Math.sin(angle) * orbit;
            DVec3 position = center.add(Math.cos(angle) * orbit, along * sinInclination, along * cosInclination);
            double radius = compact ? random.nextDouble(2500,6500) : random.nextDouble(1_000_000, 7_000_000) * scale;
            Planet.Kind kind = Planet.Kind.values()[random.nextInt(Planet.Kind.values().length)];
            planets.add(new Planet((compact ? "compact/" : "")+"sector/" + systemId + "/" + i, name + " " + (i + 1),
                    position, radius, kind, random.nextLong()));
        }
        return List.copyOf(planets);
    }

    private static long mixSeed(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private record Sector(long x, long y, long z) { }
}
