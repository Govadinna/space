package dev.seamlessspace.world;

import dev.seamlessspace.universe.*;

/** Metric, body-specific terrain. Coordinates are sampled in 3D to avoid map seams. */
public final class SolarTerrain {
    private SolarTerrain() { }
    public static double height(Planet planet,DVec3 unit) {
        if(PlanetSurface.profile(planet)>=7)return PlanetGeology.height(planet,unit);
        int seed=Universe.terrainSeed(planet);var p=unit.multiply(planet.radius());
        double fine=n(p,24,seed+11)*5+n(p,9,seed+17)*1.5;
        double result=switch(planet.body()) {
            case MOON -> 115+n(p,260,seed+31)*22+craters(p,180,seed+101)*.9+craters(p,650,seed+199)*.65+fine;
            case MERCURY -> 125+n(p,180,seed+37)*38+craters(p,120,seed+211)+craters(p,480,seed+307)*.55+fine*1.7;
            case MARS -> {
                if(PlanetSurface.profile(planet)>=6) yield mars(p,seed,fine);
                double cut=Math.abs(n(new DVec3(p.x(),p.y()*.4,p.z()*2),310,seed+401));
                double mesas=Math.floor((n(p,450,seed+419)+1)*8)*12;
                yield 110+mesas-150*Math.exp(-Math.pow(cut/.11,2))
                        +craters(p,340,seed+431)*.6+fine*2+n(p,65,seed+443)*14;
            }
            case VENUS -> 125+Math.pow(Math.max(0,n(p,480,seed+503)),2)*420
                    +n(p,90,seed+509)*38+fine*3;
            case JUPITER -> 160+n(p,110,seed+601)*30+n(new DVec3(p.x(),p.y(),p.z()*4),550,seed+607)*60+fine;
            case SATURN -> 145+n(p,150,seed+701)*26+n(p,50,seed+709)*13+fine;
            case URANUS -> 135+n(p,250,seed+809)*45+Math.abs(n(p,70,seed+811))*40+fine;
            case NEPTUNE -> 150+Math.pow(1-Math.abs(n(p,190,seed+907)),5)*140+n(p,45,seed+911)*12+fine;
            case SUN -> 100+n(p,34,seed+1009)*5+n(p,8,seed+1013)*2;
            default -> 120+n(p,180,seed)*45+fine;
        };
        return Math.clamp(result,-52,900);
    }
    public static double mars(DVec3 p,int seed,double fine) {
        double warp=n(p,480,seed+1201)*65;
        var q=new DVec3(p.x()+warp,p.y(),p.z()-warp*.6);
        double terrain=120+n(q,1200,seed+1207)*55+n(q,210,seed+1213)*19;
        double mesa=1-Math.abs(n(q,360,seed+1223));
        double plateau=Math.pow(Math.max(0,(mesa-.40)/.60),2)*130;
        terrain+=Math.floor(plateau/3)*3;
        double channel=Math.abs(n(new DVec3(q.x(),q.y()*.6,q.z()*1.4),540,seed+1231));
        terrain-=75*Math.exp(-Math.pow(channel/.075,2));
        terrain+=craters(q,260,seed+1249)*.9+craters(q,85,seed+1259)*.6;
        return terrain+n(q,48,seed+1277)*9+fine*1.4;
    }
    public static double n(DVec3 p,double wavelength,int seed) {
        return Universe.valueNoise(p.x()/wavelength,p.y()/wavelength,p.z()/wavelength,seed);
    }
    public static double craters(DVec3 point,double cell,int seed) {
        double px=point.x()/cell,py=point.y()/cell,pz=point.z()/cell;
        int ix=(int)Math.floor(px-.5),iy=(int)Math.floor(py-.5),iz=(int)Math.floor(pz-.5);
        double result=0;
        for(int x=ix;x<=ix+1;x++) for(int y=iy;y<=iy+1;y++) for(int z=iz;z<=iz+1;z++) {
            int h=mix(seed^x*0x8da6b343^y*0xd8163841^z*0xcb1ab31f);
            double cx=x+.5+random(h)*.28-.14;h=mix(h+1973);
            double cy=y+.5+random(h)*.28-.14;h=mix(h+9277);
            double cz=z+.5+random(h)*.28-.14;
            double radius=.21+random(mix(h+3911))*.16;
            double d=Math.sqrt((px-cx)*(px-cx)+(py-cy)*(py-cy)+(pz-cz)*(pz-cz))/radius;
            if(d>=1.35) continue;
            double bowl=-.30*cell*radius*Math.pow(Math.max(0,1-d*d),1.4);
            double rim=.09*cell*radius*Math.exp(-Math.pow((d-.98)/.13,2));
            double t=Math.clamp((d-1.15)/.2,0,1);
            result+=(bowl+rim)*(1-t*t*(3-2*t));
        }
        return result;
    }
    private static double random(int h) {return(h>>>8)/16777215.0;}
    private static int mix(int h) {h^=h>>>16;h*=0x7feb352d;h^=h>>>15;h*=0x846ca68b;return h^(h>>>16);}
}
