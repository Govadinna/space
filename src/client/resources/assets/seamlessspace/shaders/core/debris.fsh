#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D BlockAtlas;
layout(std140) uniform SpaceFrame {
 vec4 CameraForward;vec4 CameraRight;vec4 CameraUp;vec4 Scene;vec4 Environment;vec4 Atmosphere;vec4 SunOriginRadius;vec4 Timing;vec4 EarthRegion;
 vec4 PlanetOriginRadius[16];vec4 PlanetTerrain[16];ivec4 PlanetInfo[16];vec4 PlanetExtra[16];
};
layout(std140) uniform DebrisFrame {vec4 East;vec4 Normal;vec4 North;vec4 Origin;vec4 Sprite[2];};
layout(location=0) in vec2 cubeUv;
layout(location=1) in vec3 cubeNormal;
layout(location=2) in vec3 cameraRelative;
layout(location=3) flat in int cubeMaterial;
layout(location=0) out vec4 fragColor;
void main(){
 float distance=length(cameraRelative);vec3 ray=cameraRelative/distance;
 for(int i=0;i<16;i++){
  if(PlanetInfo[i].w==0)continue;
  vec3 o=PlanetOriginRadius[i].xyz;float b=dot(o,ray);float r=PlanetOriginRadius[i].w;
  vec3 v=o-ray*b;float d=r*r-dot(v,v);
  if(d>0.0 && -b-sqrt(d)>0.0 && -b-sqrt(d)<distance-2.0)discard;
 }
 vec4 rect=Sprite[cubeMaterial];vec3 color=texture(BlockAtlas,mix(rect.xy,rect.zw,cubeUv)).rgb;
 vec3 sun=SunOriginRadius.w>0.0?normalize(-SunOriginRadius.xyz-cameraRelative):normalize(vec3(-.45,.32,.83));
 float light=.18+.82*max(dot(normalize(cubeNormal),sun),0.0);
 fragColor=vec4(pow(color,vec3(2.2))*light,1);
}
