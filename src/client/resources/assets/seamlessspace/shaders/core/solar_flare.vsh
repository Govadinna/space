#version 330
#extension GL_ARB_separate_shader_objects : require
layout(location=0) in vec3 FlarePoint;
layout(location=1) in vec3 FlareTangent;
layout(location=2) in float RibbonSide;
layout(location=3) in float FlarePhase;
layout(location=4) in float FlareAlong;
layout(std140) uniform SpaceFrame {
 vec4 CameraForward;vec4 CameraRight;vec4 CameraUp;vec4 Scene;vec4 Environment;vec4 Atmosphere;vec4 SunOriginRadius;vec4 Timing;vec4 EarthRegion;
 vec4 PlanetOriginRadius[16];vec4 PlanetTerrain[16];ivec4 PlanetInfo[16];vec4 PlanetExtra[16];
};
layout(location=0) out float side;
layout(location=1) out float energy;
layout(location=2) out vec3 filament;
void main(){
 vec3 radial=normalize(FlarePoint);vec3 point=FlarePoint-SunOriginRadius.xyz;
 vec3 across=cross(FlareTangent,normalize(point));
 across=length(across)>.001?normalize(across):CameraRight.xyz;
 float tier=floor(FlarePhase/10.0);
 float phase=mod(FlarePhase,10.0);
 float pulse=.82+.18*sin(Timing.x*.7+phase);
 float width= tier<.5?16.0:tier<1.5?36.0:64.0;
 point+=across*RibbonSide*width+radial*sin(Timing.x*.9+phase+FlareAlong*12.0)*2.0;
 side=RibbonSide;energy=pulse*(tier<.5?.48:tier<1.5?.22:.085);
 filament=vec3(FlareAlong,phase,Timing.x);
 float depth=dot(point,CameraForward.xyz);
 gl_Position=vec4(dot(point,CameraRight.xyz)/(CameraForward.w*CameraRight.w),dot(point,CameraUp.xyz)/CameraForward.w,.1,depth);
}
