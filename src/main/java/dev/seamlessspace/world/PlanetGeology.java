package dev.seamlessspace.world;

import dev.seamlessspace.universe.*;
import java.util.List;

/** Versioned metric landforms. All surface stamps use spherical directions. */
public final class PlanetGeology {
    private PlanetGeology() { }
    public record Landmark(String name,double x,double z) { }
    public record Cavity(int minY,int maxY) {public boolean contains(int y){return y>=minY && y<=maxY;}}
    public record Spike(double height,double distance) { }
    public static double distance(Planet planet,DVec3 unit,double x,double z) {
        return unit.distance(PlanetSurface.direction(planet,x,z))*planet.radius();
    }
    private static double n(DVec3 p,double wave,int seed){return SolarTerrain.n(p,wave,seed);}
    private static double smooth(double a,double b,double x){double t=Math.clamp((x-a)/(b-a),0,1);return t*t*(3-2*t);}
    private static double bell(double x){return Math.exp(-x*x);}
    public static double crater(double distance,double radius,double depth,boolean peak) {
        double d=distance/radius;
        return -depth*Math.pow(Math.max(0,1-d*d),1.4)+depth*.32*bell((d-1)/.10)
            +(peak?depth*.65*bell(d/.16):0);
    }
    public static double shield(double distance,double radius,double height) {
        return height*bell(distance/radius)-height*.32*bell(distance/(radius*.13));
    }
    public static double height(Planet planet,DVec3 u) {
        var p=u.multiply(planet.radius());int s=Universe.terrainSeed(planet);
        double fine=n(p,24,s+11)*3+n(p,9,s+17);
        double center=distance(planet,u,0,0);
        double h=switch(planet.body()) {
            case MOON -> {
                double polar=Math.min(distance(planet,u,0,planet.radius()*1.45),distance(planet,u,0,-planet.radius()*1.45));
                double rille=Math.abs(p.x()-450+25*Math.sin(p.z()/180));
                var c=PlanetSurface.coordinates(planet,u);
                yield 118+n(p,420,s+31)*9+fine+SolarTerrain.craters(p,180,s+101)*1.3
                    +crater(center,180,60,true)+crater(distance(planet,u,1200,900),350,95,true)
                    +crater(polar,170,55,false)-14*bell(rille/6)-24*hole(c.x(),c.z());
            }
            case MERCURY -> {
                double scarp=16*Math.tanh(35*Math.sin(p.x()/650+.15*Math.sin(p.z()/700)));
                double hollow=distance(planet,u,400,0);
                yield 125+n(p,700,s+211)*12+fine+scarp+SolarTerrain.craters(p,90,s+223)*1.5
                    +SolarTerrain.craters(p,270,s+227)*.8-12*bell(hollow/35);
            }
            case VENUS -> {
                double dome=distance(planet,u,1400,400),fissure=Math.abs(n(p,180,s+307));
                yield 120+n(p,1100,s+301)*25+fine+shield(center,560,230)
                    +65*(1-smooth(135,175,dome))+Math.pow(1-Math.abs(n(p,160,s+311)),8)*25
                    -12*bell(fissure/.035);
            }
            case MARS -> {
                if(PlanetSurface.profile(planet)>=8) yield mars(planet,u,p,s,fine,center);
                double mesa=1-Math.abs(n(p,360,s+401));
                double river=Math.abs(n(new DVec3(p.x(),p.y()*.5,p.z()*1.8),370,s+409));
                double quarry=center/180;
                double pit=quarry<1?-Math.floor((1-quarry*quarry)*96/8)*8:0;
                double dunes=Math.pow(.5+.5*Math.sin(p.x()/14+n(p,90,s+419)*3),2)*7*smooth(-.2,.2,n(p,800,s+421));
                yield 125+n(p,1000,s+431)*35+n(p,170,s+433)*14+fine
                    +80*smooth(.57,.69,mesa)-26*bell(river/.045)+pit+dunes
                    +shield(distance(planet,u,-1800,-1400),850,200)+SolarTerrain.craters(p,230,s+439)*.7;
            }
            case JUPITER -> {
                double d=center/950,angle=Math.atan2(p.z(),p.x());
                double spiral=30*bell((d-1)/.09)*(.6+.4*Math.sin(angle*5+d*16));
                double vortex=Math.abs(n(p,430,s+509));
                yield 165+32*Math.sin(p.z()/140+n(p,300,s+503)*2)+n(p,90,s+511)*15+fine
                    -110*Math.pow(Math.max(0,1-d*d),1.4)+spiral-25*bell(vortex/.055);
            }
            case SATURN -> {
                var basis=PlanetSurface.frame(planet,0,0);double a=p.dot(basis.east()),b=p.dot(basis.north());
                double hex=Math.max(Math.abs(a),Math.max(Math.abs(a*.5+b*.8660254),Math.abs(a*.5-b*.8660254)));
                double wall=u.dot(basis.radial())>.8?48*bell((hex-500)/24):0;
                var c=PlanetSurface.coordinates(planet,u);double crystal=spike(planet,c.x(),c.z()).height*.35;
                yield 145+18*Math.sin(p.z()/180+n(p,400,s+601))+n(p,85,s+607)*10+fine+wall+crystal;
            }
            case URANUS -> {
                var q=new DVec3(p.x(),p.y()*.25,p.z()*2);
                double ridge=Math.pow(1-Math.abs(n(q,420,s+701)),12)*55;
                double fracture=Math.abs(n(q,750,s+709));
                yield 135+n(p,900,s+719)*8+fine*.35+ridge-45*bell(fracture/.035);
            }
            case NEPTUNE -> {
                var c=PlanetSurface.coordinates(planet,u);double d=center/650,angle=Math.atan2(p.z(),p.x());
                double vent=distance(planet,u,600,-250);
                yield 150+n(p,300,s+809)*14+fine*.5+spike(planet,c.x(),c.z()).height
                    -60*Math.pow(Math.max(0,1-d*d),1.4)+15*bell((d-1)/.08)*Math.sin(angle*6+d*14)
                    +12*bell((vent-9)/5)-8*bell(vent/5);
            }
            case SUN -> 105+n(p,45,s+901)*6+fine*.3-27*bell(center/140)
                -12*smooth(.30,.52,n(p,260,s+907));
            default -> 120+fine;
        };
        return Math.clamp(h,-52,900);
    }
    public static double rays(Planet planet,DVec3 u) {
        var p=u.multiply(planet.radius());double d=distance(planet,u,0,0)/180;
        double beams=Math.pow(Math.max(0,Math.cos(Math.atan2(p.z(),p.x())*14+n(p,80,Universe.terrainSeed(planet)+1001)*.4)),18);
        return beams*smooth(1,1.15,d)*(1-smooth(1.3,3,d));
    }
    private static double tubeZ(double x,double z) {
        return Math.floor(z/512)*512+220+5*Math.sin(x/70);
    }
    public static double hole(double x,double z) {
        double dx=x-(Math.floor(x/768)*768+96),dz=z-tubeZ(x,z);
        return Math.hypot(dx,dz)<9?1:0;
    }
    public static Cavity cavity(Planet planet,int x,int z,int ground) {
        if(planet.body()!=Planet.Body.MOON || PlanetSurface.profile(planet)<7)return new Cavity(1,0);
        double dz=z-tubeZ(x,z);if(Math.abs(dz)>=7)return new Cavity(1,0);
        double original=ground+24*hole(x,z),center=original-16,half=5*Math.sqrt(1-dz*dz/49);
        return new Cavity((int)Math.ceil(center-half),(int)Math.floor(center+half));
    }
    public static Spike spike(Planet planet,double x,double z) {
        int cell=planet.body()==Planet.Body.SATURN?120:64;
        int gx=(int)Math.floor(x/cell),gz=(int)Math.floor(z/cell);
        int h=mix(gx*0x8da6b343^gz*0xd8163841^Universe.terrainSeed(planet));
        if((h&255)>140)return new Spike(0,999);
        int cx=gx*cell+cell/4+(int)(((h>>>8)&255)/255.0*(cell/2));
        int cz=gz*cell+cell/4+(int)(((h>>>16)&255)/255.0*(cell/2));
        double d=Math.hypot(x-cx,z-cz),height=20+((mix(h+371)>>>8)/16777215.0)*20;
        if(PlanetSurface.profile(planet)<8)return new Spike(height*Math.pow(Math.max(0,1-d/3.5),.65),d);
        // Three intergrown, faceted towers per site. No structure entities or extra voxel passes.
        double dx=x-cx,dz=z-cz,angle=(h&7)*Math.PI/4;
        double a=dx*Math.cos(angle)+dz*Math.sin(angle),b=-dx*Math.sin(angle)+dz*Math.cos(angle);
        double main=crystal(a,b,8+((h>>>24)&3),height);
        double left=crystal(a-7,b+3,5.5,height*.70);
        double right=crystal(a+5,b-5,4.5,height*.48);
        return new Spike(Math.max(main,Math.max(left,right)),d);
    }
    private static double crystal(double x,double z,double width,double height) {
        double facet=Math.max(Math.abs(x),Math.max(Math.abs(z),(.65*Math.abs(x)+.65*Math.abs(z))));
        double t=Math.max(0,1-facet/width);
        // Broad shoulders taper to a narrow tip; integral ledges carry snow.
        return t<=0?0:Math.floor(height*Math.pow(t,.58)/2)*2;
    }
    private static double mars(Planet planet,DVec3 u,DVec3 p,int s,double fine,double center) {
        double field=n(p,390,s+1401)+n(p,115,s+1403)*.12;
        double shelf=72*smooth(.18,.235,field)+26*smooth(.48,.53,field);
        double channel=Math.abs(n(new DVec3(p.x(),p.y()*.5,p.z()*1.8),370,s+409));
        double q=center/180,pit=q<1?-Math.floor((1-q*q)*96/8)*8:0;
        double sand=1-smooth(.10,.25,field);
        double dunes=Math.pow(.5+.5*Math.sin(p.x()/18+n(p,90,s+419)*2),2)*3*sand;
        return 125+n(p,1100,s+431)*13+n(p,90,s+433)*2+fine*.32+shelf
            -22*bell(channel/.035)+pit+dunes+shield(distance(planet,u,-1800,-1400),850,200)
            +SolarTerrain.craters(p,230,s+439)*.25;
    }
    public static PlanetMaterial material(Planet planet,DVec3 u,int ground) {
        var p=u.multiply(planet.radius());int s=Universe.terrainSeed(planet);double center=distance(planet,u,0,0);
        double patch=n(p,55,s+1103);
        return switch(planet.body()) {
            case MOON -> Math.abs(u.z())>.94 && Math.min(distance(planet,u,0,planet.radius()*1.45),distance(planet,u,0,-planet.radius()*1.45))<120 ? PlanetMaterial.PACKED_ICE
                : rays(planet,u)>.22 ? PlanetMaterial.CALCITE : patch<-.15?PlanetMaterial.ANDESITE:patch>.4?PlanetMaterial.CALCITE:PlanetMaterial.STONE;
            case MERCURY -> distance(planet,u,400,0)<55?PlanetMaterial.CALCITE:patch>.15?PlanetMaterial.GRAVEL:PlanetMaterial.BASALT;
            case VENUS -> {
                double angle=Math.atan2(p.z(),p.x());
                boolean flow=center<650 && center>55 && Math.sin(angle*7+center/180)>.7;
                yield flow || Math.abs(n(p,180,s+307))<.035 ? PlanetMaterial.MAGMA : patch>.1?PlanetMaterial.BASALT:PlanetMaterial.TERRACOTTA;
            }
            case MARS -> Math.abs(u.z())>.90 ? (patch>.1?PlanetMaterial.PACKED_ICE:PlanetMaterial.SNOW)
                : PlanetSurface.profile(planet)>=9?(patch>.35 && ground>170?PlanetMaterial.ORANGE_TERRACOTTA:patch<-.42?PlanetMaterial.RED_SANDSTONE:PlanetMaterial.RED_SAND):center<180?PlanetMaterial.RED_SANDSTONE:ground>180?PlanetMaterial.ORANGE_TERRACOTTA:patch<-.3?PlanetMaterial.TERRACOTTA:PlanetMaterial.RED_SAND;
            case JUPITER -> center<950 ? (patch>.2?PlanetMaterial.ORANGE_TERRACOTTA:PlanetMaterial.TERRACOTTA)
                : patch>.2?PlanetMaterial.WHITE_CONCRETE:patch<-.2?PlanetMaterial.TERRACOTTA:PlanetMaterial.SAND;
            case SATURN -> {
                var c=PlanetSurface.coordinates(planet,u);var basis=PlanetSurface.frame(planet,0,0);
                double a=p.dot(basis.east()),b=p.dot(basis.north());
                double hex=Math.max(Math.abs(a),Math.max(Math.abs(a*.5+b*.8660254),Math.abs(a*.5-b*.8660254)));
                if(u.dot(basis.radial())>.8 && Math.abs(hex-500)<32)yield PlanetMaterial.PACKED_ICE;
                if(u.dot(basis.radial())>.8 && hex<465)yield PlanetMaterial.SAND;
                yield spike(planet,c.x(),c.z()).height>2?(PlanetSurface.profile(planet)>=8?PlanetMaterial.SNOW:PlanetMaterial.PACKED_ICE):patch>.2?PlanetMaterial.CALCITE:patch<-.25?PlanetMaterial.TERRACOTTA:PlanetMaterial.SAND;
            }
            case URANUS -> patch>.25?PlanetMaterial.BLUE_ICE:PlanetMaterial.PACKED_ICE;
            case NEPTUNE -> {
                if(PlanetSurface.profile(planet)>=8) {
                    var c=PlanetSurface.coordinates(planet,u);double ice=spike(planet,c.x(),c.z()).height();
                    yield ice>2 ? (patch>-.16?PlanetMaterial.SNOW:PlanetMaterial.BLUE_ICE)
                        : center<650?PlanetMaterial.BLUE_ICE:patch>-.3?PlanetMaterial.SNOW:PlanetMaterial.PACKED_ICE;
                }
                yield center<650?PlanetMaterial.BLUE_ICE:patch>.2?PlanetMaterial.PACKED_ICE:PlanetMaterial.BLUE_ICE;
            }
            case SUN -> center<145 || n(p,260,s+907)>.44 ? PlanetMaterial.BLACKSTONE
                : n(p,45,s+901)<-.15?PlanetMaterial.MAGMA:PlanetMaterial.SUN_PLASMA;
            default -> PlanetMaterial.STONE;
        };
    }
    public static List<Landmark> landmarks(Planet planet) {
        return switch(planet.body()) {
            case MOON -> List.of(new Landmark("Кратер с центральной горкой",0,0),new Landmark("Лавовая трубка: вход",96,225),new Landmark("Полярный ледяной кратер",0,planet.radius()*1.45),new Landmark("Борозда",450,0));
            case MERCURY -> List.of(new Landmark("Уступ",0,0),new Landmark("Светлые холлоу",400,0));
            case VENUS -> List.of(new Landmark("Вулкан и кальдера",0,0),new Landmark("Круглый купол",1400,400));
            case MARS -> List.of(new Landmark("Ступенчатый карьер",0,0),new Landmark("Щитовой вулкан",-1800,-1400),new Landmark("Полярная шапка",0,planet.radius()*1.48));
            case JUPITER -> List.of(new Landmark("Красное пятно",0,0));
            case SATURN -> List.of(new Landmark("Северный шестиугольник",0,0));
            case URANUS -> List.of(new Landmark("Ледяные хребты и разломы",0,0));
            case NEPTUNE -> List.of(new Landmark("Тёмный вихрь",0,0),new Landmark("Ледяные кристаллы",900,900),new Landmark("Замёрзший гейзер",600,-250));
            case SUN -> List.of(new Landmark("Солнечное пятно",0,0),new Landmark("Гранулы",300,0));
            default -> List.of();
        };
    }
    private static int mix(int h){h^=h>>>16;h*=0x7feb352d;h^=h>>>15;h*=0x846ca68b;return h^(h>>>16);}
}
