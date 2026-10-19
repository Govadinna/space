#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D BlockAtlas;
layout(std140) uniform SurfaceMaterials { vec4 Sprite[32]; vec4 Average[32]; };
layout(std140) uniform SpaceFrame {
    vec4 CameraForward; vec4 CameraRight; vec4 CameraUp; vec4 Scene;
    vec4 Environment; vec4 Atmosphere; vec4 SunOriginRadius; vec4 Timing;vec4 EarthRegion;
    vec4 PlanetOriginRadius[16]; vec4 PlanetTerrain[16]; ivec4 PlanetInfo[16]; vec4 PlanetExtra[16];
};
layout(location=0) in vec2 blockUv;
layout(location=1) in vec3 cameraRelative;
layout(location=2) in vec3 tint;
layout(location=3) flat in uint material;
layout(location=4) flat in float faceShade;
layout(location=5) flat in float terrainLight;
layout(location=6) in float ambientOcclusion;
layout(location=7) in float contactHeight;
layout(location=8) flat in float patchOpacity;
layout(location=0) out vec4 fragColor;
void main() {
    float stipple=fract(dot(mod(floor(gl_FragCoord.xy),4.0),vec2(.25,.5625)));
    if(patchOpacity<stipple)discard;
    float distanceToPatch=length(cameraRelative);
    vec3 ray=cameraRelative/max(distanceToPatch,0.0001);
    for(int i=0;i<16;i++) {
        if(PlanetInfo[i].w==0) continue;
        vec3 origin=PlanetOriginRadius[i].xyz;
        float b=dot(origin,ray);
        vec3 perpendicular=origin-ray*b;
        float radius=max(0.0,PlanetOriginRadius[i].w-512.0);
        float disc=radius*radius-dot(perpendicular,perpendicular);
        if(disc>0.0 && -b-sqrt(disc)>0.0 && -b-sqrt(disc)<distanceToPatch-2.0) discard;
    }
    vec4 sprite=Sprite[min(material,31u)];
    vec2 extent=sprite.zw-sprite.xy;
    ivec2 tile=ivec2(floor(blockUv));
    uint h=uint(tile.x)*0x8da6b343u^uint(tile.y)*0xd8163841u;
    h^=h>>16u;h*=0x7feb352du;h^=h>>15u;
    vec2 part=fract(blockUv);vec2 gradX=dFdx(blockUv),gradY=dFdy(blockUv);
    if((h&1u)!=0u){part=part.yx;gradX=gradX.yx;gradY=gradY.yx;}
    if((h&2u)!=0u){part.x=1.0-part.x;gradX.x=-gradX.x;gradY.x=-gradY.x;}
    if((h&4u)!=0u){part.y=1.0-part.y;gradX.y=-gradX.y;gradY.y=-gradY.y;}
    vec2 uv=sprite.xy+part*extent;
    vec2 dx=dFdx(blockUv),dy=dFdy(blockUv);
    float footprint=max(length(dx),length(dy));
    // Integrate only texels smaller than a screen pixel. Nearby block textures
    // stay crisp; far repeating tiles cannot alias into moving black stripes.
    vec3 albedo=Average[min(material,31u)].rgb;
    if(footprint<2.0) albedo=mix(textureGrad(BlockAtlas,uv,gradX*extent,gradY*extent).rgb,
            albedo,smoothstep(0.35,2.0,footprint));
    if(material==0u) albedo=tint;
    if(material==14u) albedo*=tint;
    if(material==17u) albedo=tint*vec3(.5,.6,.58);
    float light=material==18u || material==27u ? 1.0 : terrainLight*faceShade;
    vec3 color=pow(clamp(albedo*light,vec3(0),vec3(1)),vec3(2.2));
    if(material!=17u && material!=18u && material!=27u)
        color*=ambientOcclusion*mix(.68,1.0,smoothstep(0.0,2.0,contactHeight));
    float haze=(1.0-Environment.w)*smoothstep(8192.0,90000.0,distanceToPatch);
    float gasHaze=Atmosphere.w>.4 ? (1.0-Environment.w)*smoothstep(48.0,2000.0,distanceToPatch) : Atmosphere.w*(1.0-exp(-distanceToPatch/2200.0));
    if(Atmosphere.w<0.0) gasHaze=clamp((distanceToPatch*(-Atmosphere.w)-384.0)/(6000.0-384.0),0.0,1.0);
    color=mix(color,Atmosphere.rgb,max(haze*0.7,gasHaze));
    fragColor=vec4(color,1);
}
