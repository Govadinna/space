#!/usr/bin/env python3
"""Deterministic, tileable plasma animation and rotated native planet block models."""
from pathlib import Path
import math,re,json,struct,zlib
root=Path(__file__).resolve().parent.parent
assets=root/'src/client/resources/assets/seamlessspace'
data=root/'src/main/resources/data/seamlessspace'
def write(path,obj):
 path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(obj,indent=2)+'\n')
materials=re.findall(r'(\w+)\(0x[0-9a-fA-F]+,"([^"]+)"\)',(root/'src/main/java/dev/seamlessspace/world/PlanetMaterial.java').read_text())
lang={}
for i,(name,texture) in enumerate(materials):
 if name in ('COLOR','WATER','LAVA') or 19<=i<=25:continue
 block='solar_plasma' if name=='SUN_PLASMA' else 'regolith_'+name.lower()
 tex='seamlessspace:block/solar_plasma' if name=='SUN_PLASMA' else 'minecraft:block/'+texture
 write(assets/f'models/block/{block}.json',{'parent':'minecraft:block/cube_all','ambientocclusion':name!='SUN_PLASMA','textures':{'all':tex}})
 write(assets/f'blockstates/{block}.json',{'variants':{'':[{'model':'seamlessspace:block/'+block,'x':x,'y':y,'uvlock':False} for x in (0,180) for y in (0,90,180,270)]}})
 write(assets/f'items/{block}.json',{'model':{'type':'minecraft:model','model':'seamlessspace:block/'+block}})
 write(data/f'loot_table/blocks/{block}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'seamlessspace:'+block}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
 lang['block.seamlessspace.'+block]='Солнечная плазма' if name=='SUN_PLASMA' else 'Планетный грунт: '+name.lower()
write(assets/'lang/ru_ru.json',lang)
# Periodic cellular granulation, eight slowly evolving frames. No external assets.
N=64;frames=8
centers=[(x+.5+.3*math.sin(x*31+y*11),y+.5+.3*math.cos(x*17-y*29)) for x in range(8) for y in range(8)]
raw=bytearray()
for frame in range(frames):
 t=frame*math.tau/frames
 for y in range(N):
  raw.append(0)
  for x in range(N):
   px=x/8+.16*math.sin(y/8*math.tau/8+t);py=y/8+.16*math.cos(x/8*math.tau/8+t)
   distances=sorted(min(abs(px-cx),8-abs(px-cx))**2+min(abs(py-cy),8-abs(py-cy))**2 for cx,cy in centers)
   edge=min(1,(math.sqrt(distances[1])-math.sqrt(distances[0]))*2)
   grain=.5+.5*math.sin(x*1.7+y*2.3+.2*math.sin(t))
   heat=max(0,min(1,.12+.64*edge+.12*grain))
   palette=[(40,2,0),(170,17,0),(248,68,0),(255,170,13),(255,236,123)]
   f=heat*4;k=min(3,int(f));a=f-k
   raw.extend([int(palette[k][c]*(1-a)+palette[k+1][c]*a) for c in range(3)]+[255])
def chunk(kind,b):return struct.pack('>I',len(b))+kind+b+struct.pack('>I',zlib.crc32(kind+b)&0xffffffff)
p=assets/'textures/block/solar_plasma.png';p.parent.mkdir(parents=True,exist_ok=True)
p.write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>2I5B',N,N*frames,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(raw))+chunk(b'IEND',b''))
write(p.with_suffix('.png.mcmeta'),{'animation':{'frametime':4,'interpolate':True}})
print('Generated',len(lang),'rotated block models and plasma animation')
