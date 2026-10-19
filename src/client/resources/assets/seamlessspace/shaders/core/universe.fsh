#version 330
#extension GL_ARB_separate_shader_objects : require

// Orbital appearance only: playable terrain is made of ordinary Minecraft chunks.
// Every body needs one analytic ray/sphere intersection, never a terrain march.
layout(std140) uniform SpaceFrame {
    vec4 CameraForward;
    vec4 CameraRight;
    vec4 CameraUp;
    vec4 Scene;
    vec4 Environment;
    vec4 Atmosphere;
    vec4 SunOriginRadius;
    vec4 Timing;vec4 EarthRegion;
    vec4 PlanetOriginRadius[16];
    vec4 PlanetTerrain[16];
    ivec4 PlanetInfo[16]; vec4 PlanetExtra[16];
};

uniform sampler2D EarthMap;
uniform sampler2D EarthDetail;
vec2 earthUv(vec3 u){
 vec3 a=abs(u);float f;vec2 q,center;
 if(a.y>=a.x && a.y>=a.z){if(u.y>=0.0){q=u.xz/a.y;center=vec2(0);}else{q=vec2(-u.x,u.z)/a.y;center=vec2(-1,1);}}
 else if(a.x>=a.z){if(u.x>=0.0){q=vec2(-u.y,u.z)/a.x;center=vec2(1,0);}else{q=u.yz/a.x;center=vec2(-1,0);}}
 else {if(u.z>=0.0){q=vec2(u.x,-u.y)/a.z;center=vec2(0,1);}else{q=u.xy/a.z;center=vec2(1,1);}}
 return (center+q*.5)/3.0+.5;
}
vec4 earthSample(vec2 uv){
 vec4 sampleColor=texture(EarthMap,uv);vec2 local=((uv-.5)*100000.0-EarthRegion.xy)/EarthRegion.z+.5;
 float edge=min(min(local.x,local.y),min(1.0-local.x,1.0-local.y));
 if(EarthRegion.w>.5 && edge>0.0)sampleColor=mix(sampleColor,texture(EarthDetail,local),smoothstep(0.0,.08,edge));
 return sampleColor;
}
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const float FAR = 1.0e30;
const vec3 SUN_DIRECTION = vec3(-0.451420, 0.321011, 0.832619);

uint mixBits(uint h) {
    h ^= h >> 16;
    h *= 0x7feb352du;
    h ^= h >> 15;
    h *= 0x846ca68bu;
    return h ^ (h >> 16);
}

float lattice(ivec3 p, uint seed) {
    uint h = mixBits(seed ^ (uint(p.x) * 0x8da6b343u)
            ^ (uint(p.y) * 0xd8163841u) ^ (uint(p.z) * 0xcb1ab31fu));
    return float(h >> 8) / 16777215.0 * 2.0 - 1.0;
}

float valueNoise(vec3 p, uint seed) {
    ivec3 i = ivec3(floor(p));
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = mix(mix(lattice(i, seed), lattice(i + ivec3(1, 0, 0), seed), f.x),
            mix(lattice(i + ivec3(0, 1, 0), seed), lattice(i + ivec3(1, 1, 0), seed), f.x), f.y);
    float b = mix(mix(lattice(i + ivec3(0, 0, 1), seed), lattice(i + ivec3(1, 0, 1), seed), f.x),
            mix(lattice(i + ivec3(0, 1, 1), seed), lattice(i + ivec3(1, 1, 1), seed), f.x), f.y);
    return mix(a, b, f.z);
}

float solarNoise(vec3 p,float wavelength,uint seed){return valueNoise(p/wavelength,seed);}
float solarCraters(vec3 point,float cell,uint seed){
 vec3 p=point/cell;ivec3 base=ivec3(floor(p-.5));float result=0.0;
 for(int x=0;x<2;x++)for(int y=0;y<2;y++)for(int z=0;z<2;z++){
  ivec3 c=base+ivec3(x,y,z);uint h=mixBits(seed^uint(c.x)*0x8da6b343u^uint(c.y)*0xd8163841u^uint(c.z)*0xcb1ab31fu);
  vec3 center;center.x=float(c.x)+.36+float(h>>8u)/16777215.0*.28;h=mixBits(h+1973u);
  center.y=float(c.y)+.36+float(h>>8u)/16777215.0*.28;h=mixBits(h+9277u);
  center.z=float(c.z)+.36+float(h>>8u)/16777215.0*.28;
  float radius=.21+float(mixBits(h+3911u)>>8u)/16777215.0*.16;
  float d=length(p-center)/radius;
  if(d<1.35)result+=(-.30*cell*radius*pow(max(0.0,1.0-d*d),1.4)+.09*cell*radius*exp(-pow((d-.98)/.13,2.0)))*(1.0-smoothstep(1.15,1.35,d));
 }
 return result;
}
// Version 7 landforms mirror PlanetGeology. Subsurface tubes remain native chunks.
float geoBell(float x){return exp(-x*x);}
vec3 geoDirection(float x,float z,float r){float a=x/r,b=z/r;return vec3(sin(a)*cos(b),cos(a)*cos(b),sin(b));}
float geoDistance(vec3 u,float x,float z,float r){return length(u-geoDirection(x,z,r))*r;}
float geoCrater(float distance,float radius,float depth,bool peak){float d=distance/radius;return -depth*pow(max(0.0,1.0-d*d),1.4)+depth*.32*geoBell((d-1.0)/.10)+(peak?depth*.65*geoBell(d/.16):0.0);}
float geoShield(float d,float radius,float height){return height*geoBell(d/radius)-height*.32*geoBell(d/(radius*.13));}
float geoCrystal(vec2 p,float width,float height){
 float facet=max(max(abs(p.x),abs(p.y)),.65*(abs(p.x)+abs(p.y)));
 float t=max(0.0,1.0-facet/width);return t<=0.0?0.0:floor(height*pow(t,.58)/2.0)*2.0;
}
float geoSpike(vec2 coords,int body,uint seed,bool clustered){
 float cell=body==7?120.0:64.0;ivec2 g=ivec2(floor(coords/cell));
 uint h=mixBits(uint(g.x)*0x8da6b343u^uint(g.y)*0xd8163841u^seed);if((h&255u)>140u)return 0.0;
 vec2 center=vec2(g)*cell+floor(cell/4.0)+floor(vec2(float((h>>8u)&255u),float((h>>16u)&255u))/255.0*floor(cell/2.0));
 float d=length(coords-center),height=20.0+float(mixBits(h+371u)>>8u)/16777215.0*20.0;
 if(!clustered)return height*pow(max(0.0,1.0-d/3.5),.65);
 float angle=float(h&7u)*.7853981634;vec2 p=coords-center;
 p=vec2(p.x*cos(angle)+p.y*sin(angle),-p.x*sin(angle)+p.y*cos(angle));
 return max(geoCrystal(p,8.0+float((h>>24u)&3u),height),max(geoCrystal(p-vec2(7,-3),5.5,height*.70),geoCrystal(p-vec2(-5,5),4.5,height*.48)));
}
float geologyHeight(vec3 u,int index,float footprint){
 float r=PlanetOriginRadius[index].w;vec3 p=u*r;int body=int(PlanetExtra[index].x);uint seed=uint(PlanetInfo[index].x);
 float fine=(solarNoise(p,24.0,seed+11u)*3.0+solarNoise(p,9.0,seed+17u))*(1.0-smoothstep(8.0,60.0,footprint));
 float c=geoDistance(u,0.0,0.0,r),h=120.0;
 float small=1.0-smoothstep(40.0,220.0,footprint);
 vec3 axis=normalize(vec3(.18,.92,.34)),east=normalize(cross(vec3(0,0,1),axis)),north=cross(east,axis);
 vec3 chart=body==7?vec3(dot(u,east),dot(u,axis),dot(u,north)):u;
 vec2 coords=vec2(atan(chart.x,chart.y),asin(clamp(chart.z,-1.0,1.0)))*r;
 if(body==4){
  float polar=min(geoDistance(u,0.0,r*1.45,r),geoDistance(u,0.0,-r*1.45,r));
  float rille=abs(p.x-450.0+25.0*sin(p.z/180.0));
  vec2 tube=vec2(floor(coords.x/768.0)*768.0+96.0,floor(coords.y/512.0)*512.0+220.0+5.0*sin(coords.x/70.0));
  float hole=length(coords-tube)<9.0?1.0:0.0;
  h=118.0+solarNoise(p,420.0,seed+31u)*9.0+fine+solarCraters(p,180.0,seed+101u)*1.3*small
   +geoCrater(c,180.0,60.0,true)+geoCrater(geoDistance(u,1200.0,900.0,r),350.0,95.0,true)
   +geoCrater(polar,170.0,55.0,false)-(14.0*geoBell(rille/6.0)+24.0*hole)*small;
 }
 if(body==1){float scarp=16.0*tanh(35.0*sin(p.x/650.0+.15*sin(p.z/700.0)));float hollow=geoDistance(u,400.0,0.0,r);
  h=125.0+solarNoise(p,700.0,seed+211u)*12.0+fine+scarp+(solarCraters(p,90.0,seed+223u)*1.5+solarCraters(p,270.0,seed+227u)*.8)*small-12.0*geoBell(hollow/35.0);}
 if(body==2){float dome=geoDistance(u,1400.0,400.0,r),fissure=abs(solarNoise(p,180.0,seed+307u));
  h=120.0+solarNoise(p,1100.0,seed+301u)*25.0+fine+geoShield(c,560.0,230.0)+65.0*(1.0-smoothstep(135.0,175.0,dome))
   +pow(1.0-abs(solarNoise(p,160.0,seed+311u)),8.0)*25.0-12.0*geoBell(fissure/.035)*small;}
 if(body==5 && PlanetExtra[index].z<8.0){float mesa=1.0-abs(solarNoise(p,360.0,seed+401u)),river=abs(solarNoise(p*vec3(1,.5,1.8),370.0,seed+409u));float q=c/180.0;
  float pit=q<1.0?-floor((1.0-q*q)*96.0/8.0)*8.0:0.0;
  float dunes=pow(.5+.5*sin(p.x/14.0+solarNoise(p,90.0,seed+419u)*3.0),2.0)*7.0*smoothstep(-.2,.2,solarNoise(p,800.0,seed+421u));
  h=125.0+solarNoise(p,1000.0,seed+431u)*35.0+solarNoise(p,170.0,seed+433u)*14.0+fine+80.0*smoothstep(.57,.69,mesa)
   -26.0*geoBell(river/.045)+pit+dunes*small+geoShield(geoDistance(u,-1800.0,-1400.0,r),850.0,200.0)+solarCraters(p,230.0,seed+439u)*.7*small;}
 if(body==5 && PlanetExtra[index].z>=8.0){
 float field=solarNoise(p,390.0,seed+1401u)+solarNoise(p,115.0,seed+1403u)*.12;
 float shelf=72.0*smoothstep(.18,.235,field)+26.0*smoothstep(.48,.53,field);
 float channel=abs(solarNoise(p*vec3(1,.5,1.8),370.0,seed+409u)),q=c/180.0;
 float pit=q<1.0?-floor((1.0-q*q)*96.0/8.0)*8.0:0.0;
 float dunes=pow(.5+.5*sin(p.x/18.0+solarNoise(p,90.0,seed+419u)*2.0),2.0)*3.0*(1.0-smoothstep(.10,.25,field));
 h=125.0+solarNoise(p,1100.0,seed+431u)*13.0+solarNoise(p,90.0,seed+433u)*2.0+fine*.32+shelf
 -22.0*geoBell(channel/.035)+pit+dunes*small+geoShield(geoDistance(u,-1800.0,-1400.0,r),850.0,200.0)+solarCraters(p,230.0,seed+439u)*.25*small;
 }
 if(body==6){float d=c/950.0,angle=atan(p.z,p.x),vortex=abs(solarNoise(p,430.0,seed+509u));
  h=165.0+32.0*sin(p.z/140.0+solarNoise(p,300.0,seed+503u)*2.0)+solarNoise(p,90.0,seed+511u)*15.0+fine
   -110.0*pow(max(0.0,1.0-d*d),1.4)+30.0*geoBell((d-1.0)/.09)*(.6+.4*sin(angle*5.0+d*16.0))-25.0*geoBell(vortex/.055);}
 if(body==7){float a=dot(p,east),b=dot(p,north);float hex=max(abs(a),max(abs(a*.5+b*.8660254),abs(a*.5-b*.8660254)));
  h=145.0+18.0*sin(p.z/180.0+solarNoise(p,400.0,seed+601u))+solarNoise(p,85.0,seed+607u)*10.0+fine
   +(dot(u,axis)>.8?48.0*geoBell((hex-500.0)/24.0):0.0)+geoSpike(coords,body,seed,PlanetExtra[index].z>=8.0)*.35*(1.0-smoothstep(4.0,16.0,footprint));}
 if(body==8){vec3 q=p*vec3(1,.25,2);float ridge=pow(1.0-abs(solarNoise(q,420.0,seed+701u)),12.0)*55.0;float fracture=abs(solarNoise(q,750.0,seed+709u));
  h=135.0+solarNoise(p,900.0,seed+719u)*8.0+fine*.35+ridge-45.0*geoBell(fracture/.035);}
 if(body==9){float d=c/650.0,angle=atan(p.z,p.x),vent=geoDistance(u,600.0,-250.0,r);
  h=150.0+solarNoise(p,300.0,seed+809u)*14.0+fine*.5+geoSpike(coords,body,seed,PlanetExtra[index].z>=8.0)*(1.0-smoothstep(4.0,16.0,footprint))
   -60.0*pow(max(0.0,1.0-d*d),1.4)+15.0*geoBell((d-1.0)/.08)*sin(angle*6.0+d*14.0)+12.0*geoBell((vent-9.0)/5.0)-8.0*geoBell(vent/5.0);}
 if(body==10)h=105.0+solarNoise(p,45.0,seed+901u)*6.0+fine*.3-27.0*geoBell(c/140.0)-12.0*smoothstep(.30,.52,solarNoise(p,260.0,seed+907u));
 return clamp(h,-52.0,900.0)-96.0;
}
float solarHeight(vec3 radial,int index,float footprint){
 if(PlanetExtra[index].z>=7.0)return geologyHeight(radial,index,footprint);
 uint seed=uint(PlanetInfo[index].x);int body=int(PlanetExtra[index].x);vec3 p=radial*PlanetOriginRadius[index].w;
 float fine=solarNoise(p,24.0,seed+11u)*5.0+solarNoise(p,9.0,seed+17u)*1.5;
 fine*=1.0-smoothstep(8.0,60.0,footprint);float h=120.0;
 float craterFilter=1.0-smoothstep(40.0,220.0,footprint);
 if(body==4)h=115.0+solarNoise(p,260.0,seed+31u)*22.0+(solarCraters(p,180.0,seed+101u)*.9+solarCraters(p,650.0,seed+199u)*.65)*craterFilter+fine;
 if(body==1)h=125.0+solarNoise(p,180.0,seed+37u)*38.0+(solarCraters(p,120.0,seed+211u)+solarCraters(p,480.0,seed+307u)*.55)*craterFilter+fine*1.7;
 if(body==5){float cut=abs(solarNoise(p*vec3(1,.4,2),310.0,seed+401u));float mesa=floor((solarNoise(p,450.0,seed+419u)+1.0)*8.0)*12.0;
 h=110.0+mesa-150.0*exp(-pow(cut/.11,2.0))+solarCraters(p,340.0,seed+431u)*.6*craterFilter+fine*2.0+solarNoise(p,65.0,seed+443u)*14.0;
 if(PlanetExtra[index].z>=6.0){float warp=solarNoise(p,480.0,seed+1201u)*65.0;vec3 q=p+vec3(warp,0,-warp*.6);
 float mesa2=1.0-abs(solarNoise(q,360.0,seed+1223u));float plateau=pow(max(0.0,(mesa2-.40)/.60),2.0)*130.0;
 float channel=abs(solarNoise(q*vec3(1,.6,1.4),540.0,seed+1231u));
 h=120.0+solarNoise(q,1200.0,seed+1207u)*55.0+solarNoise(q,210.0,seed+1213u)*19.0+floor(plateau/3.0)*3.0
 -75.0*exp(-pow(channel/.075,2.0))+(solarCraters(q,260.0,seed+1249u)*.9+solarCraters(q,85.0,seed+1259u)*.6)*craterFilter+solarNoise(q,48.0,seed+1277u)*9.0+fine*1.4;}}

 if(body==2)h=125.0+pow(max(0.0,solarNoise(p,480.0,seed+503u)),2.0)*420.0+solarNoise(p,90.0,seed+509u)*38.0+fine*3.0;
 if(body==6)h=160.0+solarNoise(p,110.0,seed+601u)*30.0+solarNoise(p*vec3(1,1,4),550.0,seed+607u)*60.0+fine;
 if(body==7)h=145.0+solarNoise(p,150.0,seed+701u)*26.0+solarNoise(p,50.0,seed+709u)*13.0+fine;
 if(body==8)h=135.0+solarNoise(p,250.0,seed+809u)*45.0+abs(solarNoise(p,70.0,seed+811u))*40.0+fine;
 if(body==9)h=150.0+pow(1.0-abs(solarNoise(p,190.0,seed+907u)),5.0)*140.0+solarNoise(p,45.0,seed+911u)*12.0+fine;
 if(body==10)h=100.0+solarNoise(p,34.0,seed+1009u)*5.0+solarNoise(p,8.0,seed+1013u)*2.0;
 return clamp(h,-52.0,900.0)-96.0;
}
vec3 solarMaterial(vec3 radial,int index,float height,float footprint){
 int body=int(PlanetExtra[index].x);uint seed=uint(PlanetInfo[index].x);vec3 p=radial*PlanetOriginRadius[index].w;
 float patch=solarNoise(p,70.0,seed+173u);vec3 c=vec3(.5);
 if(body==3){
  vec2 uv=earthUv(radial);
  vec3 land=earthSample(uv).rgb;
  float cloud=valueNoise(radial*13.0,seed+731u)*.55+valueNoise(radial*31.0,seed+1731u)*.28;
  cloud+=valueNoise(radial*71.0,seed+2731u)*.12*(1.0-smoothstep(80.0,260.0,footprint));
  c=mix(land,vec3(.93,.95,.98),smoothstep(.15,.38,cloud)*.32);
 }
 if(body==4){float maria=solarNoise(p,900.0,seed+179u);vec3 ground=patch<-.15?vec3(.49):patch>.35?vec3(.78):vec3(.55);
  ground=mix(vec3(.57),ground,1.0-smoothstep(12.0,100.0,footprint));c=maria<-.2?vec3(.31):ground;}
 if(body==1)c=mix(vec3(.37),patch>.1?vec3(.44,.42,.40):vec3(.31),1.0-smoothstep(12.0,100.0,footprint));
 if(body==2)c=mix(vec3(.49,.32,.11),vec3(.85,.69,.36),.5+.4*valueNoise(radial*23.0,seed+41u));
 if(body==5)c=height< -6.0?vec3(.63,.32,.14):patch>.1?vec3(.68,.34,.16):vec3(.78,.39,.17);
 if(body==5 && PlanetExtra[index].z>=9.0){
  float sand=solarNoise(p,55.0,seed+1103u);
  c=sand>.35 && height+96.0>170.0?vec3(.624,.322,.141):sand<-.42?vec3(.729,.388,.176):vec3(.847,.498,.2);
 }
 if(body>=6 && body<=9){
  vec3 axis=body==7?normalize(vec3(.18,.92,.34)):body==8?normalize(vec3(.94,.18,.29)):vec3(0,0,1);
  float latitude=dot(radial,axis);
  float flow=valueNoise(radial*vec3(7,7,19),seed+53u)*.65+valueNoise(radial*35.0,seed+59u)*.12;
  float band=.5+.5*sin(latitude*(body==6?55.0:body==7?38.0:body==8?24.0:30.0)+flow);
  if(body==6){c=mix(vec3(.43,.24,.14),vec3(.91,.79,.60),smoothstep(.08,.92,band));
   float lon=atan(radial.x,radial.y);float spot=exp(-pow((lon-.7)/.19,2.0)-pow((radial.z-.2)/.07,2.0));c=mix(c,vec3(.64,.23,.10),spot*.85);}
  if(body==7)c=mix(vec3(.53,.43,.26),vec3(.91,.82,.61),band*.7+.15);
  if(body==8)c=mix(vec3(.25,.57,.64),vec3(.61,.83,.85),band*.3+.4);
  if(body==9)c=mix(vec3(.045,.14,.51),vec3(.19,.43,.86),band*.6+.2);
  c*=.97+.035*valueNoise(radial*65.0,seed+61u);
 }
 if(PlanetExtra[index].z>=7.0){
  float r=PlanetOriginRadius[index].w,center=geoDistance(radial,0.0,0.0,r);
  if(body==4){float d=center/180.0;float beams=pow(max(0.0,cos(atan(p.z,p.x)*14.0+solarNoise(p,80.0,seed+1001u)*.4)),18.0)*smoothstep(1.0,1.15,d)*(1.0-smoothstep(1.3,3.0,d));
   c=mix(vec3(.51),vec3(.85),smoothstep(.15,.25,beams));if(abs(radial.z)>.94 && min(geoDistance(radial,0.0,r*1.45,r),geoDistance(radial,0.0,-r*1.45,r))<120.0)c=vec3(.64,.74,.88);}
  if(body==1 && geoDistance(radial,400.0,0.0,r)<55.0)c=vec3(.82,.81,.76);
  if(body==2){float a=atan(p.z,p.x);bool flow=center<650.0 && center>55.0 && sin(a*7.0+center/180.0)>.7;
   c=flow || abs(solarNoise(p,180.0,seed+307u))<.035?vec3(.7,.24,.04):vec3(.37,.32,.26);}
  if(body==5 && abs(radial.z)>.9)c=vec3(.87,.91,.96);
  if(body==7){vec3 axis=normalize(vec3(.18,.92,.34)),east=normalize(cross(vec3(0,0,1),axis)),north=cross(east,axis);
   float a=dot(p,east),b=dot(p,north),hex=max(abs(a),max(abs(a*.5+b*.8660254),abs(a*.5-b*.8660254)));
   if(dot(radial,axis)>.8 && abs(hex-500.0)<32.0)c=vec3(.56,.65,.85);}

  if(body==6)c=mix(c,vec3(.62,.23,.12),1.0-smoothstep(900.0,980.0,center));
  if(body==9)c=mix(c,vec3(.055,.11,.30),1.0-smoothstep(620.0,680.0,center));
  if(body==10 && (center<145.0 || solarNoise(p,260.0,seed+907u)>.44))return vec3(.015,.006,.003);
  if(body==10 && solarNoise(p,45.0,seed+901u)<-.15)return vec3(.7,.07,.001);
 }
 if(body==10){float g=valueNoise(p/max(45.0,footprint*1.2)+vec3(0,Timing.x*.14,0),seed+67u)*.75+valueNoise(p/240.0,seed+71u)*.25;return mix(vec3(2.7,.28,.006),vec3(5.0,2.0,.10),smoothstep(-.5,.7,g));}
 return pow(c,vec3(2.2));
}
vec3 lightDirection(int index){return SunOriginRadius.w>0.0?normalize(PlanetOriginRadius[index].xyz-SunOriginRadius.xyz):SUN_DIRECTION;}

float craterRelief(vec3 radial,float radius,uint seed) {
    vec3 p=radial*(radius/4200.0);
    ivec3 cell=ivec3(floor(p));
    uint h=mixBits((seed+103171u)^(uint(cell.x)*0x8da6b343u)^(uint(cell.y)*0xd8163841u)^(uint(cell.z)*0xcb1ab31fu));
    vec3 center;
    center.x=.46+float(h>>8u)/16777215.0*.08;h=mixBits(h+1973u);
    center.y=.46+float(h>>8u)/16777215.0*.08;h=mixBits(h+9277u);
    center.z=.46+float(h>>8u)/16777215.0*.08;
    float d=length(fract(p)-center);
    return (-55.0*(1.0-smoothstep(0.0,.36,d))+14.0*exp(-pow((d-.36)/.038,2.0)))
            *(1.0-smoothstep(.41,.45,d));
}

// Low octaves define continents; tiny distant disks skip the detail octaves.
// Sampling 3D directions avoids seams at texture meridians and the poles.
float continents(vec3 direction, uint seed, bool detail) {
    float shape = valueNoise(direction * 2.7, seed) * 0.5714286;
    shape += valueNoise(direction * 5.4, seed + 1013u) * 0.2857143;
    if (detail) {
        shape += valueNoise(direction * 10.8, seed + 2026u) * 0.1428571;
        shape += valueNoise(direction * 21.6, seed + 3039u) * 0.0714286;
        shape += valueNoise(direction * 43.2, seed + 4052u) * 0.0357143;
        shape += valueNoise(direction * 86.4, seed + 5065u) * 0.0178571;
    }
    return shape;
}

// The perpendicular-distance form keeps distant small disks numerically stable.
vec2 sphereInterval(vec3 origin, vec3 ray, float radius) {
    float b = dot(origin, ray);
    vec3 closest = origin - ray * b;
    float discriminant = radius * radius - dot(closest, closest);
    if (discriminant < 0.0) return vec2(FAR, -FAR);
    float halfChord = sqrt(discriminant);
    return vec2(-b - halfChord, -b + halfChord);
}

vec3 atmosphereColor(int kind) {
    if (kind == 1) return vec3(0.56, 0.23, 0.095);
    if (kind == 2) return vec3(0.19, 0.42, 0.61);
    if (kind == 3) return vec3(0.57, 0.15, 0.035);
    return vec3(0.085, 0.31, 0.68);
}

// The same height field as PlanetSurface.worldHeight / the voxel generator.
// Metric octaves are filtered at a distance to avoid noisy subpixel terrain.
float surfaceHeight(vec3 radial, int index, float footprint) {
    if(PlanetExtra[index].x>0.5)return solarHeight(radial,index,footprint);
    uint seed = uint(PlanetInfo[index].x);
    float shape = 0.0, amplitude = 0.5, frequency = 2.7;
    for (int octave=0; octave<7; octave++) {
        shape += valueNoise(radial*frequency, seed+uint(octave)*1013u)*amplitude;
        frequency *= 2.0; amplitude *= 0.5;
    }
    float radius = PlanetOriginRadius[index].w;
    float terrain = shape/0.9921875*74.0;
    terrain += valueNoise(radial*(radius/1300.0),seed+17011u)*34.0*(1.0-smoothstep(250.0,1500.0,footprint));
    terrain += valueNoise(radial*(radius/190.0),seed+29009u)*11.0*(1.0-smoothstep(35.0,200.0,footprint));
    terrain += valueNoise(radial*(radius/46.0),seed+40117u)*2.5*(1.0-smoothstep(8.0,50.0,footprint));
    if (PlanetInfo[index].w >= 3) {
        float ridge = 1.0-abs(valueNoise(radial*(radius/850.0),seed+53017u));
        float canyon = valueNoise(radial*(radius/2400.0),seed+67003u);
        float rugged = pow(ridge,10.0)*128.0-42.0+tanh(canyon*7.0)*30.0;
        terrain += rugged*(PlanetInfo[index].y==4 ? 0.35 : 1.0)*(1.0-smoothstep(80.0,600.0,footprint));
    }
    int kind = PlanetInfo[index].y;
    if(PlanetInfo[index].w>=4 && kind!=3 && kind!=4)
        terrain+=craterRelief(radial,radius,seed)*(1.0-smoothstep(400.0,3000.0,footprint));
    float result = kind==0 ? terrain*1.15 : kind==1 ? 12.0+terrain*0.85
            : kind==2 ? 8.0+terrain : kind==3 ? terrain*1.35 : -15.0+terrain*0.65;
    return clamp(result,-148.0,192.0);
}

vec3 material(vec3 radial, int index, out float emission) {
    uint seed = uint(PlanetInfo[index].x);
    int kind = PlanetInfo[index].y;
    float angularRadius = PlanetOriginRadius[index].w / length(PlanetOriginRadius[index].xyz);
    bool detail = angularRadius > 0.012 && PlanetTerrain[index].w > 0.5;
    float shape = continents(radial, seed, detail);
    float broad = shape * 0.5 + 0.5;
    emission = 0.0;
    if (kind == 1) {
        float ridges = detail ? valueNoise(radial * 120.0, seed + 191u) * 0.12 : 0.0;
        return mix(vec3(0.31, 0.105, 0.035), vec3(0.77, 0.40, 0.16), broad + ridges);
    }
    if (kind == 2) {
        return mix(vec3(0.19, 0.32, 0.40), vec3(0.82, 0.90, 0.94), smoothstep(-0.23, 0.15, shape));
    }
    if (kind == 3) {
        emission = 1.0 - smoothstep(0.012, 0.035, abs(shape + 0.14));
        return mix(vec3(0.095, 0.082, 0.085), vec3(0.24, 0.12, 0.065), broad);
    }
    if (kind == 4) {
        float land = smoothstep(-0.035, 0.035, shape);
        vec3 ocean = mix(vec3(0.009, 0.045, 0.13), vec3(0.02, 0.21, 0.30),
                smoothstep(-0.27, 0.0, shape));
        float aridity=valueNoise(radial*25.0,seed+60713u)*0.5+0.5;
        vec3 continent = mix(vec3(0.035, 0.14, 0.025), vec3(0.43, 0.30, 0.12), smoothstep(0.25,0.75,aridity));
        if(detail) {
            float pixel=max(length(dFdx(radial)),length(dFdy(radial)));
            float regional=valueNoise(radial*240.0,seed+61117u);
            float fine=valueNoise(radial*1350.0,seed+62119u)*(1.0-smoothstep(.4,1.2,pixel*1350.0));
            float ridges=pow(1.0-abs(valueNoise(radial*480.0,seed+63211u)),7.0);
            continent*=clamp(.86+regional*.35+fine*.16+ridges*.28,.55,1.4);
            continent=mix(continent,vec3(.29,.26,.19),ridges*smoothstep(.42,.72,aridity)*.45);
        }
        continent = mix(continent, vec3(0.80, 0.86, 0.90),
                smoothstep(0.68, 0.93, abs(radial.z) + shape * 0.15));
        vec3 color = mix(ocean, continent, land);
        if (detail) {
            vec3 cloudPoint=vec3(radial.x*80.0+radial.y*21.0,radial.y*52.0,radial.z*135.0);
            cloudPoint+=vec3(valueNoise(radial*34.0,seed+719u),0,valueNoise(radial*34.0,seed+727u))*3.0;
            float cloudShape=0.0,weight=.5,frequency=1.0;
            float pixel=max(length(dFdx(cloudPoint)),length(dFdy(cloudPoint)));
            for(int octave=0;octave<6;octave++) {
                cloudShape+=valueNoise(cloudPoint*frequency,seed+731u+uint(octave)*1000u)
                        *weight*(1.0-smoothstep(.4,1.2,pixel*frequency));
                frequency*=2.03;weight*=.5;
            }
            float clouds=smoothstep(.015,.32,cloudShape);
            float wisps=smoothstep(-.06,.14,cloudShape)*.16;
            color=mix(color,color*.85,smoothstep(-.025,.12,cloudShape));
            color=mix(color,vec3(.86,.90,.93),clamp(clouds*.88+wisps,0.0,.94));
        }
        return color;
    }
    float craters = detail ? valueNoise(radial * 170.0, seed + 8513u) * 0.20 : 0.0;
    return mix(vec3(0.24, 0.25, 0.27), vec3(0.57, 0.54, 0.48), broad + craters);
}

// Geological provinces use the same 3D seed, thresholds and metric scales as
// PlanetMaterials.top. This keeps large distant markings tied to actual blocks.
vec3 geology(vec3 radial,int index,float height,float footprint,vec3 fallback) {
    int kind=PlanetInfo[index].y;
    uint seed=uint(PlanetInfo[index].x);
    float radius=PlanetOriginRadius[index].w;
    float province=valueNoise(radial*(radius/40000.0),seed+81173u);
    float local=valueNoise(radial*(radius/1800.0),seed+92011u);
    vec3 color=fallback;
    if(kind==0) color=province<-.18 ? vec3(.44,.42,.40) : province>.24 ? vec3(.78)
            : local>.22 ? vec3(.51) : local<-.25 ? vec3(.46,.44,.42) : vec3(.49);
    if(kind==1) color=height>74.0 || local>.28 ? vec3(.63,.32,.14)
            : province<-.18 ? vec3(.60,.37,.26) : vec3(.78,.39,.17);
    if(kind==2) color=local<-.22 ? vec3(.49,.67,.97) : province<-.1 ? vec3(.56,.65,.85) : vec3(.92);
    if(kind==3) color=province<-.12 ? vec3(.18,.16,.20) : vec3(.31);
    if(kind==4) color=height>84.0 ? vec3(.92) : height<6.0 ? vec3(.84,.80,.62) : vec3(.35,.50,.16);
    if((kind==3 || kind==4) && height<0.0) return fallback;
    return pow(color,vec3(2.2));
}

void main() {
    vec2 ndc = texCoord * 2.0 - 1.0;
    vec3 ray = normalize(CameraForward.xyz + CameraRight.xyz * ndc.x * CameraForward.w * CameraRight.w
            + CameraUp.xyz * ndc.y * CameraForward.w);
    float closest = FAR;
    int hitIndex = -1;
    for (int index = 0; index < 16; index++) {
        if (PlanetInfo[index].w == 0 || PlanetTerrain[index].y > 0.5) continue;
        vec2 hit = sphereInterval(PlanetOriginRadius[index].xyz, ray, PlanetOriginRadius[index].w);
        if (hit.y <= 0.0 || hit.x > hit.y) continue;
        float distanceToHit = hit.x >= 0.0 ? hit.x : hit.y;
        if (distanceToHit < closest) {
            closest = distanceToHit;
            hitIndex = index;
        }
    }

    vec3 color = vec3(0.0);
    float coverage = 0.0;
    if (hitIndex >= 0) {
        vec3 radial = normalize(PlanetOriginRadius[hitIndex].xyz + ray * closest);
        float emission;
        vec3 base = material(radial, hitIndex, emission);
        float footprint = closest * 2.0 * CameraForward.w / max(Scene.z, 1.0);
        // Unvisited overworld uses a procedural color map, not this mod's
        // compressed chunk generator. Avoid evaluating that unrelated field.
        float height = PlanetInfo[hitIndex].w==2 ? 0.0 : surfaceHeight(radial, hitIndex, footprint);
        int body=int(PlanetExtra[hitIndex].x);
        if(body==3 && Timing.y>.5){vec2 uv=earthUv(radial);height=earthSample(uv).a*512.0-128.0;}
        // Geometry derivatives give mountain shading without an expensive ray march.
        int kind = PlanetInfo[hitIndex].y;
        float geometricHeight = (kind==3 && body!=10) || kind==4 ? max(height,0.0) : height;
        vec3 surface = radial * (PlanetOriginRadius[hitIndex].w + geometricHeight);
        vec3 normal = normalize(cross(dFdx(surface), dFdy(surface)));
        if (dot(normal, radial) < 0.0) normal = -normal;
        // Cloud decks have a smooth distant silhouette. Local voxel relief must
        // not turn into high-contrast subpixel stripes across the entire globe.
        if(body>=6 && body<=9)normal=normalize(mix(normal,radial,smoothstep(6.0,40.0,footprint)));
        vec3 sun=lightDirection(hitIndex);
        float daylight = max(dot(normal, sun), 0.0);
        if (kind == 0) base = mix(base, mix(vec3(0.15),vec3(0.45),clamp(0.5+height/140.0,0.0,1.0)),0.55);
        if (kind == 1) base = mix(vec3(0.30,0.085,0.025),vec3(0.63,0.28,0.10),clamp(0.5+height/150.0,0.0,1.0));
        if (kind == 2) base = mix(vec3(0.20,0.33,0.43),vec3(0.82,0.89,0.95),smoothstep(-20.0,8.0,height));
        if (kind == 3) emission = 1.0 - smoothstep(-2.0,2.0,height);
        if (kind == 4 && PlanetInfo[hitIndex].w != 2) base = mix(vec3(0.02,0.07,0.22),vec3(0.15,0.29,0.045),smoothstep(-1.0,1.0,height));
        if (PlanetInfo[hitIndex].w==4 && body==0) base=geology(radial,hitIndex,height,footprint,base);
        if(body>0 && (body!=3 || Timing.y>.5))base=solarMaterial(radial,hitIndex,height,footprint);
        if(body==10)emission=0.0;
        // A dim night side leaves planets legible without making every star glow.
        color = base * (0.025 + daylight * 0.96);
        if(body==10)color=base;
        if (PlanetInfo[hitIndex].y == 4) {
            float glint = pow(max(dot(reflect(-sun, radial), -ray), 0.0), 85.0);
            color += vec3(0.55, 0.68, 0.76) * glint * 0.20;
        }
        color += vec3(1.0, 0.11, 0.004) * emission * 0.9;
        float rim = pow(1.0 - max(dot(radial, -ray), 0.0), 3.0);
        color += atmosphereColor(PlanetInfo[hitIndex].y) * rim * (0.09 + daylight)
                * PlanetTerrain[hitIndex].z * 0.40;
        coverage = 1.0;
    }

    // Annular geometry is a single plane from far away; its local area clears
    // gradually as the independent rotated cube instances become visible.
    for(int index=0;index<16;index++){
        int body=int(PlanetExtra[index].x);if(body!=7 && body!=8)continue;
        vec3 n=normalize(body==7?vec3(.18,.92,.34):vec3(.94,.18,.29));
        vec3 o=PlanetOriginRadius[index].xyz;float denominator=dot(ray,n);
        if(abs(denominator)<.000001)continue;
        float t=-dot(o,n)/denominator;if(t<=0.0 || t>=closest)continue;
        vec3 point=o+ray*t;float r=length(point)/PlanetOriginRadius[index].w;
        if(r<1.35 || r>2.45)continue;
        float bands=.65+.25*sin(r*180.0)+.10*sin(r*670.0);
        float gap=1.0-smoothstep(1.925,1.94,r)+smoothstep(2.02,2.035,r);
        float edge=smoothstep(1.35,1.39,r)*(1.0-smoothstep(2.39,2.45,r));
        float alpha=clamp(bands*gap*edge,0.0,.94)*smoothstep(80.0,350.0,t);
        vec3 sun=lightDirection(index);vec2 shadow=sphereInterval(point,sun,PlanetOriginRadius[index].w);
        float illumination=shadow.x>0.0 && shadow.x<1e20 ? .10 : .25+.75*abs(dot(n,sun));
        vec3 ringColor=pow(body==7?vec3(.80,.74,.61):vec3(.55,.75,.83),vec3(2.2))*illumination;
        color=mix(color,ringColor,alpha);coverage=coverage+alpha*(1.0-coverage);
        if(alpha>.4)closest=t;
    }
    // The stellar body supplies both the visible disk and the light direction.
    // Animated hot arches and thin jets extend beyond its limb, never a fixed sky sprite.
    for(int index=0;index<16;index++){
        if(int(PlanetExtra[index].x)!=10)continue;
        vec3 o=PlanetOriginRadius[index].xyz;float radius=PlanetOriginRadius[index].w;
        float along=-dot(o,ray);if(along<=0.0 || along>closest+radius)continue;
        vec3 q=o+ray*along;float d=length(q)/radius;
        if(d>1.0 && d<1.38){
            vec3 direction=normalize(q);float angle=atan(direction.z,direction.y);
            float arc=pow(max(0.0,sin(angle*7.0+valueNoise(direction*4.0,713u)*3.0+Timing.x*.12)),16.0);
            float eruption=.04+.05*(1.0+valueNoise(direction*6.0+vec3(Timing.x*.05),919u));
            float loop=arc*exp(-pow((d-1.025-eruption)/.018,2.0));
            float jet=pow(max(0.0,valueNoise(direction*23.0+vec3(Timing.x*.04),117u)),3.0)*exp(-(d-1.0)*11.0);
            float glow=exp(-(d-1.0)*37.0)*.30+loop*1.8+jet*3.0;
            color+=vec3(2.0,.22,.007)*glow;coverage=max(coverage,clamp(glow,.0,.9));
        }
    }

    // Thin limb outside the disk, with a maximum of eight analytic checks.
    // It is deliberately restrained: no bloom and no large hazy planet halo.
    for (int index = 0; index < 16; index++) {
        if (PlanetInfo[index].w == 0 || PlanetTerrain[index].z <= 0.0) continue;
        vec3 origin = PlanetOriginRadius[index].xyz;
        float radius = PlanetOriginRadius[index].w;
        float atmosphereHeight = clamp(radius * 0.012, 250.0, 3500.0);
        if (length(origin) <= radius + atmosphereHeight) continue;
        float projection = -dot(origin, ray);
        if (projection <= 0.0 || projection > closest) continue;
        vec3 perpendicular = origin + ray * projection;
        float impact = length(perpendicular);
        if (impact < radius || impact > radius + atmosphereHeight) continue;
        float limb = 1.0 - smoothstep(radius, radius + atmosphereHeight, impact);
        float sunlight = clamp(dot(perpendicular / max(impact, 0.001), lightDirection(index)) + 0.13, 0.0, 1.0);
        float density = limb * limb * PlanetTerrain[index].z * sunlight;
        color += atmosphereColor(PlanetInfo[index].y) * density * 0.30;
        coverage = max(coverage, density * 0.20);
    }
    // Premultiplied linear planet color; native-resolution compositing follows.
    fragColor = vec4(max(color, vec3(0.0)), coverage);
}
