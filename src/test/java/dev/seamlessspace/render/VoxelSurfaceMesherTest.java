package dev.seamlessspace.render;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VoxelSurfaceMesherTest {
    private VoxelSurfaceMesher.Grid grid(java.util.function.IntBinaryOperator height,java.util.function.IntBinaryOperator material) {
        return new VoxelSurfaceMesher.Grid() {
            public int height(int x,int z) {return height.applyAsInt(x,z);}
            public int material(int x,int z) {return material.applyAsInt(x,z);}
            public int color(int x,int z) {return 0x707070;}
            public boolean active(int x,int z) {return true;}
        };
    }
    @Test void flatRegionBecomesOneRepeatingTopAndFourWalls() {
        List<VoxelSurfaceMesher.Face> faces=new ArrayList<>();
        VoxelSurfaceMesher.emit(grid((x,z)->100,(x,z)->1),64,1,0,0,0,faces::add);
        assertEquals(5,faces.size());
        var top=faces.stream().filter(f->f.direction()==0).findFirst().orElseThrow();
        assertEquals(64,top.width());assertEquals(64,top.height());assertEquals(100,top.y());
    }
    @Test void heightStepIsACompleteVerticalFaceRatherThanASlope() {
        List<VoxelSurfaceMesher.Face> faces=new ArrayList<>();
        VoxelSurfaceMesher.emit(grid((x,z)->x<2?100:107,(x,z)->1),4,1,0,0,0,faces::add);
        var wall=faces.stream().filter(f->f.direction()==VoxelSurfaceMesher.WEST && f.x()==2).findFirst().orElseThrow();
        assertEquals(100,wall.y());assertEquals(7,wall.height());assertEquals(4,wall.width());
        assertTrue(faces.stream().filter(f->f.direction()==0).count()>=2);
        assertTrue(faces.stream().allMatch(f->f.direction()>=0 && f.direction()<=4));
    }
    @Test void materialBoundaryIsPreservedByGreedyMerging() {
        List<VoxelSurfaceMesher.Face> faces=new ArrayList<>();
        VoxelSurfaceMesher.emit(grid((x,z)->100,(x,z)->x<2?1:3),4,1,0,0,0,faces::add);
        var tops=faces.stream().filter(f->f.direction()==0).toList();
        assertEquals(2,tops.size());assertNotEquals(tops.get(0).material(),tops.get(1).material());
    }
    @Test void instanceEncodingUsesLessMemoryWithoutChangingFaceGeometry() {
        var buffer=new VoxelSurfaceMesher.Buffer();
        buffer.face(new VoxelSurfaceMesher.Face(-8,100,16,32,7,VoxelSurfaceMesher.SOUTH,19,0x5cdbd5));
        var bytes=buffer.finish();
        assertEquals(28,bytes.remaining());assertTrue(bytes.remaining()<6*16);
        assertEquals(-8,bytes.getFloat());assertEquals(100,bytes.getFloat());assertEquals(16,bytes.getFloat());
        assertEquals(32,bytes.getFloat());assertEquals(7,bytes.getFloat());
        int packed=bytes.getInt();
        assertEquals(VoxelSurfaceMesher.SOUTH | 19<<3,packed&255);
        assertEquals(255,(packed>>>8)&255);
    }
    @Test void adjacentWallsDarkenTheEnclosedCornerButNotTheOpenCorner() {
        var g=grid((x,z)->x>0 || z>0 ? 110 : 100,(x,z)->1);
        int ao=VoxelSurfaceMesher.topAo(g,0,0);
        assertEquals(3,ao&3,"open corner stays bright");
        assertEquals(0,(ao>>>4)&3,"two walls occlude their shared corner");
    }
    @Test void greedyMergingDoesNotStretchCornerShadowsAcrossAPlain() {
        List<VoxelSurfaceMesher.Face> faces=new ArrayList<>();
        VoxelSurfaceMesher.emit(grid((x,z)->x>=4?110:100,(x,z)->1),8,1,0,0,0,faces::add);
        assertTrue(faces.stream().anyMatch(f->f.direction()==0 && f.ao()!=255));
        assertTrue(faces.stream().filter(f->f.direction()==0 && f.ao()!=255).allMatch(f->f.width()==1 && f.height()==1));
        assertTrue(faces.stream().anyMatch(f->f.direction()==0 && f.ao()==255 && f.width()>1));
    }

}
