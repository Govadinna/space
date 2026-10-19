package dev.seamlessspace.world;
import dev.seamlessspace.universe.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EarthProjectionTest {
 @Test void sixFacesRoundTripWithoutPolarCollapse(){
  for(var u:new DVec3[]{DVec3.UP,new DVec3(1,0,0),new DVec3(0,-1,0),new DVec3(-1,0,0),new DVec3(0,0,1),new DVec3(0,0,-1)}){
   var c=EarthProjection.coordinates(u);var f=EarthProjection.frame(c.x(),c.z());
   assertTrue(f.radial().distance(u)<1e-10);
   double dx=f.radial().distance(EarthProjection.frame(c.x()+1,c.z()).radial());
   double dz=f.radial().distance(EarthProjection.frame(c.x(),c.z()+1).radial());
   assertTrue(dx>0.00005 && dz>0.00005,"metre cells must retain area at every cube pole");
   assertEquals(0,f.radial().dot(f.east()),1e-10);assertEquals(0,f.east().dot(f.north()),1e-10);
  }
 }
 @Test void adjacentChartsMeetGeometrically(){
  assertTrue(EarthProjection.contains(0,0));assertFalse(EarthProjection.contains(1000000,1000000));
  double edge=EarthProjection.HALF;
  for(int z=-10000;z<=10000;z+=1000)assertTrue(EarthProjection.frame(edge-.001,z).radial().distance(EarthProjection.frame(edge+.001,z).radial())<1e-6);
 }
 @Test void actualEarthPositionsSurviveLandingProjection(){
  var earth=new Universe(7042026,1,true,true).home();
  for(var u:new DVec3[]{DVec3.UP,new DVec3(1,.4,.2).normalized(),new DVec3(0,0,-1)}){
   var c=EarthProjection.coordinates(u);var feet=new DVec3(c.x(),523.25,c.z());
   assertTrue(feet.distance(PlanetSurface.worldFeet(earth,PlanetSurface.orbitalEye(earth,feet,1.62),1.62))<1e-7);
  }
 }
}
