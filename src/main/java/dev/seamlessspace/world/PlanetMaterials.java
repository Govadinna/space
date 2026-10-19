package dev.seamlessspace.world;

import dev.seamlessspace.universe.*;

/** The procedural LOD and voxel generator choose the same surface materials. */
public final class PlanetMaterials {
    private PlanetMaterials() { }
    public static PlanetMaterial top(Planet planet,int x,int z,int ground) {
        if(PlanetSurface.profile(planet)>=7 && planet.isSolar())return PlanetGeology.material(planet,PlanetSurface.direction(planet,x,z),ground);
        if(PlanetSurface.profile(planet)>=5 && planet.isSolar()) {
            var p=PlanetSurface.direction(planet,x,z).multiply(planet.radius());
            double patch=SolarTerrain.n(p,70,Universe.terrainSeed(planet)+173);
            if(PlanetSurface.profile(planet)>=6) {
                double grain=SolarTerrain.n(p,12,Universe.terrainSeed(planet)+193);
                switch(planet.body()) {
                    case MARS: return ground>190 ? (grain>.3?PlanetMaterial.TERRACOTTA:PlanetMaterial.RED_SANDSTONE)
                        : patch<-.2 ? (grain>.2?PlanetMaterial.GRAVEL:PlanetMaterial.TERRACOTTA)
                        : grain>.36 ? PlanetMaterial.ORANGE_TERRACOTTA : grain<-.40?PlanetMaterial.RED_SANDSTONE:PlanetMaterial.RED_SAND;
                    case MERCURY: return grain>.3?PlanetMaterial.TUFF:patch>.1?PlanetMaterial.GRAVEL:PlanetMaterial.BASALT;
                    case VENUS: return grain>.3?PlanetMaterial.MAGMA:patch>.15?PlanetMaterial.BASALT:PlanetMaterial.TERRACOTTA;
                    case MOON: if(grain>.43)return PlanetMaterial.CALCITE;if(grain<-.4)return PlanetMaterial.GRAVEL;break;
                    case JUPITER: if(grain>.35)return PlanetMaterial.CALCITE;break;
                    case SATURN: if(grain>.35)return PlanetMaterial.CALCITE;if(grain<-.35)return PlanetMaterial.TERRACOTTA;break;
                    case URANUS,NEPTUNE: if(grain>.4)return PlanetMaterial.SNOW;break;
                    default: break;
                }
            }
            return switch(planet.body()) {
                case SUN -> PlanetMaterial.SUN_PLASMA;
                case MOON -> SolarTerrain.n(p,900,Universe.terrainSeed(planet)+179)<-.2 ? PlanetMaterial.BASALT
                        : patch<-.15 ? PlanetMaterial.ANDESITE : patch>.35 ? PlanetMaterial.DIORITE : PlanetMaterial.STONE;
                case MERCURY -> patch>.1 ? PlanetMaterial.GRAVEL : PlanetMaterial.BASALT;
                case VENUS -> patch>.15 ? PlanetMaterial.BASALT : PlanetMaterial.TERRACOTTA;
                case MARS -> ground<90 ? PlanetMaterial.RED_SANDSTONE : patch>.1 ? PlanetMaterial.ORANGE_TERRACOTTA : PlanetMaterial.RED_SAND;
                case JUPITER -> patch>.15 ? PlanetMaterial.WHITE_CONCRETE : patch<-.2 ? PlanetMaterial.TERRACOTTA : PlanetMaterial.SAND;
                case SATURN -> patch>.2 ? PlanetMaterial.WHITE_CONCRETE : PlanetMaterial.SAND;
                case URANUS -> PlanetMaterial.PACKED_ICE;
                case NEPTUNE -> patch>.25 ? PlanetMaterial.PACKED_ICE : PlanetMaterial.BLUE_ICE;
                default -> PlanetMaterial.GRASS;
            };
        }
        if (planet.kind()==Planet.Kind.OCEAN && ground<PlanetSurface.SEA_LEVEL) return PlanetMaterial.SAND;
        if (PlanetSurface.profile(planet)<4) return switch(planet.kind()) {
            case ROCKY -> PlanetMaterial.STONE; case DESERT -> PlanetMaterial.RED_SAND;
            case ICE -> PlanetMaterial.SNOW; case LAVA -> PlanetMaterial.BASALT; case OCEAN -> PlanetMaterial.GRASS;
        };
        DVec3 n=PlanetSurface.direction(planet,x,z); int seed=Universe.terrainSeed(planet);
        double province=noise(n,planet.radius()/40000,seed+81173);
        double local=noise(n,planet.radius()/1800,seed+92011);
        return switch(planet.kind()) {
            case ROCKY -> province<-.18 ? PlanetMaterial.GRAVEL : province>.24 ? PlanetMaterial.DIORITE
                    : local>.22 ? PlanetMaterial.ANDESITE : local<-.25 ? PlanetMaterial.GRAVEL : PlanetMaterial.STONE;
            case DESERT -> ground>170 || local>.28 ? PlanetMaterial.ORANGE_TERRACOTTA
                    : province<-.18 ? PlanetMaterial.TERRACOTTA : PlanetMaterial.RED_SAND;
            case ICE -> local<-.22 ? PlanetMaterial.BLUE_ICE : province<-.1 ? PlanetMaterial.PACKED_ICE : PlanetMaterial.SNOW;
            case LAVA -> province<-.12 ? PlanetMaterial.BLACKSTONE : PlanetMaterial.BASALT;
            case OCEAN -> ground>180 ? PlanetMaterial.SNOW : ground<102 ? PlanetMaterial.SAND : PlanetMaterial.GRASS;
        };
    }
    public static PlanetMaterial visible(Planet planet,int x,int z,int standingHeight) {
        return visibleGround(planet,x,z,PlanetSurface.height(planet,x,z));
    }
    public static PlanetMaterial visibleGround(Planet planet,int x,int z,int ground) {
        if(planet.body()==Planet.Body.SUN) return PlanetSurface.profile(planet)>=7?top(planet,x,z,ground):PlanetMaterial.SUN_PLASMA;
        if(ground<PlanetSurface.SEA_LEVEL) {
            if(planet.kind()==Planet.Kind.OCEAN) return PlanetMaterial.WATER;
            if(planet.kind()==Planet.Kind.LAVA) return PlanetMaterial.LAVA;
        }
        return top(planet,x,z,ground);
    }
    private static double noise(DVec3 n,double frequency,int seed) {
        return Universe.valueNoise(n.x()*frequency,n.y()*frequency,n.z()*frequency,seed);
    }
}
