#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D SpaceImage;
layout(std140) uniform SpaceFrame {
    vec4 CameraForward;
    vec4 CameraRight;
    vec4 CameraUp;
    // x: star brightness, yz: native framebuffer size, w: star pixel radius.
    vec4 Scene;
    vec4 Environment;
    vec4 Atmosphere;
    vec4 SunOriginRadius;
    vec4 Timing;vec4 EarthRegion;
    vec4 PlanetOriginRadius[16];
    vec4 PlanetTerrain[16];
    ivec4 PlanetInfo[16]; vec4 PlanetExtra[16];
};
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const vec3 SUN_DIRECTION = vec3(-0.451420, 0.321011, 0.832619);

uint mixBits(uint h) {
    h ^= h >> 16;
    h *= 0x7feb352du;
    h ^= h >> 15;
    h *= 0x846ca68bu;
    return h ^ (h >> 16);
}

vec3 stars(vec3 ray) {
    // A cube-projected catalogue needs no atan/asin and has no polar stretching.
    // Positions are fixed in world directions, sizes are measured in native pixels.
    vec3 a = abs(ray);
    vec2 face;
    uint side;
    if (a.x >= a.y && a.x >= a.z) {
        face = ray.yz / a.x;
        side = ray.x > 0.0 ? 0u : 1u;
    } else if (a.y >= a.z) {
        face = ray.xz / a.y;
        side = ray.y > 0.0 ? 2u : 3u;
    } else {
        face = ray.xy / a.z;
        side = ray.z > 0.0 ? 4u : 5u;
    }
    vec2 sky = (face * 0.5 + 0.5) * 1024.0;
    vec2 pixelFootprint = max(fwidth(sky), vec2(0.0001));
    ivec2 cell = ivec2(floor(sky));
    uint h = mixBits(uint(cell.x) * 0x8da6b343u ^ uint(cell.y) * 0xd8163841u
            ^ side * 0xcb1ab31fu ^ 0x729ae51du);
    vec3 color = vec3(0.00002, 0.000025, 0.00004);
    if ((h & 65535u) > 65490u) {
        uint h2 = mixBits(h);
        vec2 point = vec2(float((h >> 16) & 255u), float((h2 >> 8) & 255u)) / 255.0;
        vec2 deltaPixels = (fract(sky) - (0.15 + point * 0.70)) / pixelFootprint;
        float radius = Scene.w;
        float disk = 1.0 - smoothstep(radius * 0.18, radius + 0.38, length(deltaPixels));
        float magnitude = 0.035 + point.x * point.x * 0.18;
        vec3 tint = mix(vec3(0.67, 0.79, 1.0), vec3(1.0, 0.88, 0.72), point.y);
        color += tint * disk * magnitude * Scene.x;
    }
    // Tiny solar disk, no glow, so navigation targets stay visually distinct.
    float sun = dot(ray, SUN_DIRECTION);
    float sunWidth = max(fwidth(sun), 0.0000001);
    if(SunOriginRadius.w<=0.0) color += vec3(1.0, 0.79, 0.46) * smoothstep(0.999982 - sunWidth, 0.999982 + sunWidth, sun) * 1.3;
    return color;
}

void main() {
    vec2 ndc = texCoord * 2.0 - 1.0;
    vec3 ray = normalize(CameraForward.xyz + CameraRight.xyz * ndc.x * CameraForward.w * CameraRight.w
            + CameraUp.xyz * ndc.y * CameraForward.w);
    vec4 planet = texture(SpaceImage, texCoord);
    float elevation=dot(ray,Environment.xyz);
    float skyDensity=1.0-Environment.w;
    vec3 sky=Atmosphere.rgb*(0.38+0.62*pow(1.0-clamp(elevation,0.0,1.0),2.0))*skyDensity;
    vec3 background=sky+stars(ray)*pow(Environment.w,2.0);
    // Subpixel planetary disks remain distinguishable as tiny steady points.
    if(planet.a<.5)for(int i=0;i<16;i++){
        int body=int(PlanetExtra[i].x);if(body<1 || body>9)continue;
        vec3 o=PlanetOriginRadius[i].xyz;float distance=length(o);
        float pixels=PlanetOriginRadius[i].w/distance*Scene.z/(2.0*CameraForward.w);
        if(pixels>.85)continue;
        float delta=length(ray+o/distance)*Scene.z/(2.0*CameraForward.w);
        vec3 tint=body==5?vec3(.78,.34,.16):body==8||body==9?vec3(.28,.51,.9):vec3(.78,.75,.64);
        background+=tint*(1.0-smoothstep(.1,1.25,delta))*clamp(pixels,.13,.8)*pow(Environment.w,.7)*.35;
    }
    vec3 color=planet.rgb+background*(1.0-planet.a);
    color = max(color, vec3(0.0));
    color = color / (1.0 + color * 0.35);
    fragColor = vec4(pow(color, vec3(1.0 / 2.2)), CameraUp.w);
}
