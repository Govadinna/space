package dev.seamlessspace.render;

import java.nio.*;
import dev.seamlessspace.world.PlanetMaterial;

/** Greedy axis-aligned faces. Each 28-byte instance replaces six 16-byte vertices. */
public final class VoxelSurfaceMesher {
    public static final int STRIDE=28;
    public static final int TOP=0, EAST=1, WEST=2, SOUTH=3, NORTH=4;
    public record Face(float x,float y,float z,float width,float height,int direction,int material,int rgb,int ao) {
        public Face(float x,float y,float z,float width,float height,int direction,int material,int rgb) {
            this(x,y,z,width,height,direction,material,rgb,255);
        }
    }
    @FunctionalInterface public interface Sink { void face(Face face); }
    public interface Grid {
        int height(int x,int z);
        int material(int x,int z);
        int color(int x,int z);
        boolean active(int x,int z);
    }
    private static final PlanetMaterial[] MATERIALS=PlanetMaterial.values();
    private VoxelSurfaceMesher() { }

    public static void emit(Grid grid,int size,int step,int originX,int originZ,int floor,Sink sink) {
        boolean[] used=new boolean[size*size];
        int[] ao=new int[size*size];
        for(int z=0;z<size;z++) for(int x=0;x<size;x++) ao[z*size+x]=topAo(grid,x,z);
        // Merge coplanar tops, repeating the block texture instead of stretching it.
        for(int z=0;z<size;z++) for(int x=0;x<size;x++) {
            int index=z*size+x;
            if(used[index] || !grid.active(x,z)) continue;
            int y=grid.height(x,z), material=grid.material(x,z),rgb=grid.color(x,z),width=1,depth=1;
            int ambient=ao[index];
            while(ambient==255 && x+width<size && ao[z*size+x+width]==255 && same(grid,used,size,x+width,z,y,material,rgb)) width++;
            outer: while(z+depth<size) {
                if(ambient!=255) break;
                for(int dx=0;dx<width;dx++) if(ao[(z+depth)*size+x+dx]!=255 || !same(grid,used,size,x+dx,z+depth,y,material,rgb)) break outer;
                depth++;
            }
            for(int dz=0;dz<depth;dz++) for(int dx=0;dx<width;dx++) used[(z+dz)*size+x+dx]=true;
            sink.face(new Face(originX+x*step,y,originZ+z*step,width*step,depth*step,TOP,material,rgb,ambient));
        }
        // Each height discontinuity gets a real vertical wall, never a diagonal slope.
        for(int direction=EAST;direction<=NORTH;direction++) {
            boolean alongZ=direction==EAST || direction==WEST;
            int nx=direction==EAST?1:direction==WEST?-1:0;
            int nz=direction==SOUTH?1:direction==NORTH?-1:0;
            for(int row=0;row<size;row++) {
                int column=0;
                while(column<size) {
                    int x=alongZ?row:column,z=alongZ?column:row;
                    if(!grid.active(x,z)) { column++;continue; }
                    int top=grid.height(x,z), bottom=grid.height(x+nx,z+nz);
                    // Skirts seal changes of LOD without slanted stitching triangles.
                    if(x+nx<0 || x+nx>=size || z+nz<0 || z+nz>=size) bottom=Math.min(bottom,floor);
                    if(top<=bottom) {column++;continue;}
                    int mat=MATERIALS[grid.material(x,z)].side().ordinal(), rgb=grid.color(x,z),run=1;
                    while(column+run<size) {
                        int xx=alongZ?row:column+run,zz=alongZ?column+run:row;
                        int low=grid.height(xx+nx,zz+nz);
                        if(xx+nx<0 || xx+nx>=size || zz+nz<0 || zz+nz>=size) low=Math.min(low,floor);
                        if(!grid.active(xx,zz) || grid.height(xx,zz)!=top || low!=bottom
                                || MATERIALS[grid.material(xx,zz)].side().ordinal()!=mat || grid.color(xx,zz)!=rgb) break;
                        run++;
                    }
                    sink.face(new Face(originX+(x+(direction==EAST?1:0))*step,bottom,
                            originZ+(z+(direction==SOUTH?1:0))*step,run*step,top-bottom,direction,mat,rgb));
                    column+=run;
                }
            }
        }
    }
    /** Standard voxel corner AO: two sides and a diagonal sample above a top face. */
    public static int topAo(Grid grid,int x,int z) {
        int y=grid.height(x,z),packed=0;
        for(int corner=0;corner<4;corner++) {
            int dx=corner==1 || corner==2 ? 1 : -1,dz=corner>=2 ? 1 : -1;
            boolean a=grid.height(x+dx,z)>y,b=grid.height(x,z+dz)>y;
            boolean diagonal=grid.height(x+dx,z+dz)>y;
            int level=a && b ? 0 : 3-(a?1:0)-(b?1:0)-(diagonal?1:0);
            packed|=level<<(corner*2);
        }
        return packed;
    }
    private static boolean same(Grid grid,boolean[] used,int size,int x,int z,int y,int material,int rgb) {
        return !used[z*size+x] && grid.active(x,z) && grid.height(x,z)==y && grid.material(x,z)==material && grid.color(x,z)==rgb;
    }
    public static final class Buffer implements Sink {
        private ByteBuffer bytes=ByteBuffer.allocateDirect(256*1024).order(ByteOrder.nativeOrder());
        private int faces;
        @Override public void face(Face f) {
            if(bytes.remaining()<STRIDE) {
                ByteBuffer next=ByteBuffer.allocateDirect(bytes.capacity()*2).order(ByteOrder.nativeOrder());
                bytes.flip();next.put(bytes);bytes=next;
            }
            bytes.putFloat(f.x).putFloat(f.y).putFloat(f.z).putFloat(f.width).putFloat(f.height);
            bytes.putInt(f.direction | f.material<<3 | (f.ao&255)<<8);
            bytes.put((byte)(f.rgb>>16)).put((byte)(f.rgb>>8)).put((byte)f.rgb).put((byte)255); faces++;
        }
        public ByteBuffer finish() {bytes.flip();return bytes;}
        public int faceCount() {return faces;}
    }
}
