#version 330
#extension GL_ARB_separate_shader_objects : require
layout(location=0) in float side;
layout(location=1) in float energy;
layout(location=2) in vec3 filament;
layout(location=0) out vec4 fragColor;
float hash21(vec2 p){vec3 q=fract(vec3(p.xyx)*.1031);q+=dot(q,q.yzx+33.33);return fract((q.x+q.y)*q.z);}
float noise21(vec2 p){vec2 i=floor(p),f=fract(p);f=f*f*(3.0-2.0*f);return mix(mix(hash21(i),hash21(i+vec2(1,0)),f.x),mix(hash21(i+vec2(0,1)),hash21(i+vec2(1)),f.x),f.y);}
void main(){
 float along=filament.x,phase=filament.y,time=filament.z;
 // Analytic, flowing fibres inside three soft ribbons. No texture uploads or particle swarm.
 float warp=side+.055*sin(along*19.0+phase+time*.36)+.025*sin(along*47.0-time*.6);
 // Stretched noise gives irregular bundles, avoiding a periodic wire-mesh pattern.
 vec2 flow=vec2(warp*26.0+phase,along*3.0-time*.22);
 float fibres=pow(noise21(flow),2.5);
 float wisps=noise21(vec2(along*11.0+time*.18,side*7.0+phase));
 float envelope=exp(-side*side*3.5)*(1.0-smoothstep(.65,1.0,abs(side)));
 float ends=smoothstep(0.0,.08,along)*smoothstep(0.0,.08,3.14159265-along);
 float density=(.24*wisps+1.15*fibres)*envelope*ends;
 vec3 color=mix(vec3(1.3,.06,.003),vec3(2.2,.24,.012),fibres);
 fragColor=vec4(color,density*energy);
}
