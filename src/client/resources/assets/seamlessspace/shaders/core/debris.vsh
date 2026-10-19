#version 330
#extension GL_ARB_separate_shader_objects : require
layout(location=0) in vec3 CubeOrigin;
layout(location=1) in float CubeSize;
layout(location=2) in uint CubeSeed;
layout(std140) uniform SpaceFrame {
 vec4 CameraForward;vec4 CameraRight;vec4 CameraUp;vec4 Scene;vec4 Environment;vec4 Atmosphere;vec4 SunOriginRadius;vec4 Timing;vec4 EarthRegion;
 vec4 PlanetOriginRadius[16];vec4 PlanetTerrain[16];ivec4 PlanetInfo[16];vec4 PlanetExtra[16];
};
layout(std140) uniform DebrisFrame {vec4 East;vec4 Normal;vec4 North;vec4 Origin;vec4 Sprite[2];};
layout(location=0) out vec2 cubeUv;
layout(location=1) out vec3 cubeNormal;
layout(location=2) out vec3 cameraRelative;
layout(location=3) flat out int cubeMaterial;
void main(){
 int face=gl_VertexIndex/6,i=gl_VertexIndex%6;
 vec2 c=i==0||i==3?vec2(0,0):i==1?vec2(1,0):i==2||i==4?vec2(1,1):vec2(0,1);
 vec3 p,n;
 if(face<2){p=vec3(c.x,float(face),c.y);n=vec3(0,face==0?-1:1,0);}
 else if(face<4){p=vec3(float(face-2),c.x,c.y);n=vec3(face==2?-1:1,0,0);}
 else{p=vec3(c.x,c.y,float(face-4));n=vec3(0,0,face==4?-1:1);}
 vec3 a=vec3(float(CubeSeed&1023u),float((CubeSeed>>10u)&1023u),float((CubeSeed>>20u)&1023u))*.00614;
 vec3 s=sin(a),co=cos(a);
 mat3 rx=mat3(1,0,0,0,co.x,s.x,0,-s.x,co.x);
 mat3 ry=mat3(co.y,0,-s.y,0,1,0,s.y,0,co.y);
 mat3 rz=mat3(co.z,s.z,0,-s.z,co.z,0,0,0,1);
 mat3 rotation=rz*ry*rx;mat3 basis=mat3(East.xyz,Normal.xyz,North.xyz);
 vec3 point=Origin.xyz+basis*(CubeOrigin+rotation*((p-.5)*CubeSize));
 cubeNormal=basis*rotation*n;cameraRelative=point;cubeUv=c;cubeMaterial=int(CubeSeed&1u);
 float depth=dot(point,CameraForward.xyz);
 gl_Position=vec4(dot(point,CameraRight.xyz)/(CameraForward.w*CameraRight.w),dot(point,CameraUp.xyz)/CameraForward.w,.1,depth);
}
