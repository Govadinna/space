#version 330
#extension GL_ARB_separate_shader_objects : require
layout(location=0) in vec3 FaceOrigin;
layout(location=1) in vec2 FaceSize;
layout(location=2) in uint FaceType;
layout(location=3) in vec4 FaceColor;
layout(std140) uniform SpaceFrame {
    vec4 CameraForward; vec4 CameraRight; vec4 CameraUp; vec4 Scene;
    vec4 Environment; vec4 Atmosphere; vec4 SunOriginRadius; vec4 Timing;vec4 EarthRegion;
    vec4 PlanetOriginRadius[16]; vec4 PlanetTerrain[16]; ivec4 PlanetInfo[16]; vec4 PlanetExtra[16];
};
layout(std140) uniform SurfaceOrigin {
    vec4 East; vec4 Radial; vec4 North; vec4 ChartOffset; vec4 Surface; vec4 SurfaceDetail;
};
layout(location=0) out vec2 blockUv;
layout(location=1) out vec3 cameraRelative;
layout(location=2) out vec3 tint;
layout(location=3) flat out uint material;
layout(location=4) flat out float faceShade;
layout(location=5) flat out float terrainLight;
layout(location=6) out float ambientOcclusion;
layout(location=7) out float contactHeight;
layout(location=8) flat out float patchOpacity;
vec3 earthDirection(vec2 world){
 vec2 cell=floor(world/(100000.0/3.0)+.5),q=(world-cell*(100000.0/3.0))/(50000.0/3.0);
 if(cell==vec2(0,0))return normalize(vec3(q.x,1,q.y));
 if(cell==vec2(1,0))return normalize(vec3(1,-q.x,q.y));
 if(cell==vec2(-1,1))return normalize(vec3(-q.x,-1,q.y));
 if(cell==vec2(-1,0))return normalize(vec3(-1,q.x,q.y));
 if(cell==vec2(0,1))return normalize(vec3(q.x,-q.y,1));
 if(cell==vec2(1,1))return normalize(vec3(q.x,q.y,-1));
 return normalize(vec3(world.x/(50000.0/3.0),1,world.y/(50000.0/3.0)));
}
void main() {
    patchOpacity=SurfaceDetail.w;
    int index=gl_VertexIndex%6;
    vec2 corner=index==0 || index==3 ? vec2(0,0) : index==1 ? vec2(1,0)
            : index==2 || index==4 ? vec2(1,1) : vec2(0,1);
    uint side=FaceType&7u;
    material=(FaceType>>3u)&31u;
    uint cornerIndex=corner.x<0.5 ? (corner.y<0.5 ? 0u : 3u) : (corner.y<0.5 ? 1u : 2u);
    ambientOcclusion=.55+.15*float((FaceType>>(8u+2u*cornerIndex))&3u);
    contactHeight=side==0u ? 1000000.0 : corner.y*FaceSize.y;
    vec3 vertex=FaceOrigin;
    if(side==0u) {
        vertex.xz+=corner*FaceSize; blockUv=vertex.xz+Surface.zw; faceShade=1.0;
    } else if(side==1u || side==2u) {
        vertex.zy+=corner*FaceSize; blockUv=vertex.zy+vec2(Surface.w,0); faceShade=0.6;
    } else {
        vertex.xy+=corner*FaceSize; blockUv=vertex.xy+vec2(Surface.z,0); faceShade=0.8;
    }
    float x=vertex.x+ChartOffset.x,z=vertex.z+ChartOffset.z;
    float h=vertex.y-Surface.x,r=North.w;
    float dl=x/r,dp=z/(r*SurfaceDetail.x);
    float cosLat=Radial.w*cos(dp)-East.w*sin(dp);
    float oneMinusCosL=2.0*pow(sin(dl*0.5),2.0);
    float oneMinusCosP=2.0*pow(sin(dp*0.5),2.0);
    vec3 curved=vec3(cosLat*sin(dl)*(r+h),
            h-(r+h)*(oneMinusCosP+Radial.w*cosLat*oneMinusCosL),
            (sin(dp)+East.w*cosLat*oneMinusCosL)*(r+h));
    if(SurfaceDetail.z>.5){
        vec3 u=earthDirection(vertex.xz+Surface.zw);
        curved=vec3(dot(u,East.xyz),dot(u,Radial.xyz),dot(u,North.xyz))*(r+h)-vec3(0,r,0);
    } else {
        // A tangent patch has no shrinking longitude columns at either pole.
        vec3 tangent=normalize(vec3(x,r,z))*(r+h)-vec3(0,r,0);
        curved=mix(curved,tangent,smoothstep(.65,.88,abs(East.w)));
    }
    // The central kilometre uses exactly the same flat metre grid as vanilla
    // chunks. Curvature appears gradually beyond it, without moving block steps.
    float curvature=smoothstep(512.0,min(16384.0,max(1024.0,r*.3)),length(vec2(x,z)));
    curvature=mix(curvature,1.0,smoothstep(256.0,1024.0,ChartOffset.y));
    vec3 local=mix(vec3(x,h,z),curved,curvature);
    local.y-=ChartOffset.y;
    vec3 p=East.xyz*local.x+Radial.xyz*local.y+North.xyz*local.z;
    float depth=dot(p,CameraForward.xyz);
    gl_Position=vec4(dot(p,CameraRight.xyz)/(CameraForward.w*CameraRight.w),
            dot(p,CameraUp.xyz)/CameraForward.w,0.1,depth);
    cameraRelative=p;tint=FaceColor.rgb;
    terrainLight=mix(0.9,Surface.y,smoothstep(1024.0,16000.0,ChartOffset.y));
}
