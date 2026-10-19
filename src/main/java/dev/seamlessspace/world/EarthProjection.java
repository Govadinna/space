package dev.seamlessspace.world;

import dev.seamlessspace.universe.DVec3;

/** Six bounded-distortion cube charts. No longitude columns collapse into a pole. */
public final class EarthProjection {
    public static final double WIDTH=100000.0/3, HALF=WIDTH/2;
    private static final int[] X={0,1,-1,-1,0,1}, Z={0,0,1,0,1,1};
    private static final DVec3[] N={DVec3.UP,new DVec3(1,0,0),new DVec3(0,-1,0),new DVec3(-1,0,0),new DVec3(0,0,1),new DVec3(0,0,-1)};
    private static final DVec3[] E={new DVec3(1,0,0),new DVec3(0,-1,0),new DVec3(-1,0,0),DVec3.UP,new DVec3(1,0,0),new DVec3(1,0,0)};
    private static final DVec3[] T={new DVec3(0,0,1),new DVec3(0,0,1),new DVec3(0,0,1),new DVec3(0,0,1),new DVec3(0,-1,0),DVec3.UP};
    private EarthProjection() { }
    public static boolean contains(double x,double z){return x>=-50000 && x<50000 && z>=-HALF && z<50000;}
    public static int face(double x,double z){
        int col=(int)Math.floor(x/WIDTH+.5),row=(int)Math.floor(z/WIDTH+.5);
        for(int i=0;i<6;i++)if(X[i]==col && Z[i]==row)return i;
        // Ordinary Overworld beyond the represented atlas remains playable.
        return 0;
    }
    public static PlanetSurface.Frame frame(double x,double z) {
        int f=face(x,z);double a=(x-X[f]*WIDTH)/HALF,b=(z-Z[f]*WIDTH)/HALF;
        var radial=N[f].add(E[f].multiply(a)).add(T[f].multiply(b)).normalized();
        var east=E[f].subtract(radial.multiply(E[f].dot(radial))).normalized();
        return new PlanetSurface.Frame(radial,east,east.cross(radial).normalized());
    }
    public static PlanetSurface.SurfaceCoordinates coordinates(DVec3 direction) {
        var u=direction.normalized();int f=0;double best=-Double.MAX_VALUE;
        for(int i=0;i<6;i++){double d=u.dot(N[i]);if(d>best){best=d;f=i;}}
        return new PlanetSurface.SurfaceCoordinates(X[f]*WIDTH+u.dot(E[f])/best*HALF,Z[f]*WIDTH+u.dot(T[f])/best*HALF);
    }
}
