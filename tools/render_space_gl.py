#!/usr/bin/env python3
"""Offscreen macOS GPU smoke test and timing of the actual two-pass space shaders.

The reported milliseconds cover only these shaders, not Minecraft's full frame.
Use --compare-old /path/to/old/universe.fsh for the retired 360p raymarch baseline.
No third-party Python packages or Minecraft window are required.
"""
import argparse
import ctypes as C
import json
import math
import re
from pathlib import Path
import struct
import time
import zlib

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--width', type=int, default=1920)
parser.add_argument('--height', type=int, default=1080)
parser.add_argument('--planet-height', type=int, default=1080)
parser.add_argument('--frames', type=int, default=12)
parser.add_argument('--compare-old', type=Path)
args = parser.parse_args()
output = ROOT / 'build/render-check'
output.mkdir(parents=True, exist_ok=True)
G = C.CDLL('/System/Library/Frameworks/OpenGL.framework/OpenGL')
P, I, U, F = C.c_void_p, C.c_int, C.c_uint, C.c_float

def fn(name, result, parameters):
    function = getattr(G, name)
    function.restype, function.argtypes = result, parameters
    return function

pixel_format, number, context = P(), I(), P()
attributes = (I * 4)(99, 0x3200, 73, 0)
error = fn('CGLChoosePixelFormat', I, [C.POINTER(I), C.POINTER(P), C.POINTER(I)])(
    attributes, C.byref(pixel_format), C.byref(number))
if error:
    message = fn('CGLErrorString', C.c_char_p, [I])(error).decode()
    raise SystemExit(f'Cannot create offscreen GPU context: {message}. A desktop GPU session is required.')
assert fn('CGLCreateContext', I, [P, P, C.POINTER(P)])(pixel_format, None, C.byref(context)) == 0
assert fn('CGLSetCurrentContext', I, [P])(context) == 0
get_string = fn('glGetString', C.c_char_p, [U])
renderer = get_string(0x1F01).decode()
print('OpenGL', get_string(0x1F02).decode(), renderer, flush=True)

create_shader = fn('glCreateShader', U, [U])
shader_source = fn('glShaderSource', None, [U, I, C.POINTER(C.c_char_p), C.POINTER(I)])
compile_shader = fn('glCompileShader', None, [U])
shader_status = fn('glGetShaderiv', None, [U, U, C.POINTER(I)])

def shader(kind, source):
    handle = create_shader(kind)
    source = source.replace('#version 330', '#version 410 core')
    # "patch" became a tessellation keyword after the game's GLSL 330 target.
    source = re.sub(r'\bpatch\b', 'surfacePatch', source)
    source = source.replace('#extension GL_ARB_separate_shader_objects : require', '')
    source = source.replace('layout(location = 0) in vec2 texCoord;', 'in vec2 texCoord;')
    text = (C.c_char_p * 1)(source.encode())
    shader_source(handle, 1, text, None)
    compile_shader(handle)
    status = I()
    shader_status(handle, 0x8B81, C.byref(status))
    if not status.value:
        log = C.create_string_buffer(20000)
        fn('glGetShaderInfoLog', None, [U, I, C.POINTER(I), P])(handle, len(log), None, log)
        raise RuntimeError(log.value.decode())
    return handle

vertex = shader(0x8B31, '''#version 410 core
out vec2 texCoord;
void main() {
    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(uv * 2.0 - 1.0, 0, 1);
    texCoord = uv;
}''')
use = fn('glUseProgram', None, [U])

def program(path):
    handle = fn('glCreateProgram', U, [])()
    attach = fn('glAttachShader', None, [U, U])
    attach(handle, vertex)
    attach(handle, shader(0x8B30, path.read_text()))
    fn('glLinkProgram', None, [U])(handle)
    status = I()
    fn('glGetProgramiv', None, [U, U, C.POINTER(I)])(handle, 0x8B82, C.byref(status))
    if not status.value:
        log = C.create_string_buffer(20000)
        fn('glGetProgramInfoLog', None, [U, I, C.POINTER(I), P])(handle, len(log), None, log)
        raise RuntimeError(log.value.decode())
    block = fn('glGetUniformBlockIndex', U, [U, C.c_char_p])(handle, b'SpaceFrame')
    fn('glUniformBlockBinding', None, [U, U, U])(handle, block, 0)
    return handle

shader_root = ROOT / 'src/client/resources/assets/seamlessspace/shaders/core'
planet_program = program(shader_root / 'universe.fsh')
composite_program = program(shader_root / 'composite.fsh')
old_program = program(args.compare_old) if args.compare_old else None

def generated(name):
    handle = U()
    fn(name, None, [I, C.POINTER(U)])(1, C.byref(handle))
    return handle.value

fn('glBindVertexArray', None, [U])(generated('glGenVertexArrays'))
ubo = generated('glGenBuffers')
fn('glBindBuffer', None, [U, U])(0x8A11, ubo)
fn('glBindBufferBase', None, [U, U, U])(0x8A11, 0, ubo)
bind_framebuffer = fn('glBindFramebuffer', None, [U, U])
bind_texture = fn('glBindTexture', None, [U, U])
viewport = fn('glViewport', None, [I, I, I, I])
draw = fn('glDrawArrays', None, [U, I, I])
finish = fn('glFinish', None, [])

def target(width, height):
    texture, framebuffer = generated('glGenTextures'), generated('glGenFramebuffers')
    bind_texture(0x0DE1, texture)
    fn('glTexImage2D', None, [U, I, I, I, I, I, U, U, P])(
        0x0DE1, 0, 0x8058, width, height, 0, 0x1908, 0x1401, None)
    parameter = fn('glTexParameteri', None, [U, U, I])
    parameter(0x0DE1, 0x2801, 0x2601)
    parameter(0x0DE1, 0x2800, 0x2601)
    parameter(0x0DE1, 0x2802, 0x812F)
    parameter(0x0DE1, 0x2803, 0x812F)
    bind_framebuffer(0x8D40, framebuffer)
    fn('glFramebufferTexture2D', None, [U, U, U, U, I])(0x8D40, 0x8CE0, 0x0DE1, texture, 0)
    assert fn('glCheckFramebufferStatus', U, [U])(0x8D40) == 0x8CD5
    return texture, framebuffer, width, height

height = min(args.height, args.planet_height)
planet_target = target(round(args.width * height / args.height), height)
main_target = target(args.width, args.height)
old_target = target(640, 360) if old_program else None
use(composite_program)
location = fn('glGetUniformLocation', I, [U, C.c_char_p])(composite_program, b'SpaceImage')
fn('glUniform1i', None, [I, I])(location, 0)
earth_texture = generated('glGenTextures')
fn('glActiveTexture', None, [U])(0x84C1)
bind_texture(0x0DE1, earth_texture)
earth_pixel = (C.c_ubyte * 4)(50, 95, 160, 64)
fn('glTexImage2D', None, [U, I, I, I, I, I, U, U, P])(
    0x0DE1, 0, 0x8058, 1, 1, 0, 0x1908, 0x1401, earth_pixel)
for parameter in [0x2801, 0x2800]:
    fn('glTexParameteri', None, [U, U, I])(0x0DE1, parameter, 0x2600)
use(planet_program)
location = fn('glGetUniformLocation', I, [U, C.c_char_p])(planet_program, b'EarthMap')
fn('glUniform1i', None, [I, I])(location, 1)
location = fn('glGetUniformLocation', I, [U, C.c_char_p])(planet_program, b'EarthDetail')
fn('glUniform1i', None, [I, I])(location, 1)
fn('glActiveTexture', None, [U])(0x84C0)


def normalized(vector):
    length = math.sqrt(sum(value * value for value in vector))
    return tuple(value / length for value in vector)

def cross(a, b):
    return a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]

def uniforms(radius, altitude, forward, kind=4, count=8, old=False, body=0):
    forward = normalized(forward)
    right = normalized(cross(forward, (0, 1, 0)))
    up = cross(right, forward)
    scene = (1, 640, 360, 12) if old else (.25, args.width, args.height, .65)
    data = struct.pack('16f', *forward, math.tan(math.radians(70) / 2),
                       *right, args.width / args.height, *up, 1, *scene)
    if not old:
        # Camera radial up, fully outside the visual atmosphere in this GPU benchmark.
        data += struct.pack('8f',0,1,0,1,0.08,0.24,0.55,0)
        # Current 0.6 layout: Sun, animation/seed-map flags, and 16 body extras.
        sun = (0, radius + altitude, 0, radius) if body==10 else (-1000000, 250000, -800000, 10000)
        data += struct.pack('8f', *sun, 42, 0, 100000, 0)
        data += struct.pack('4f', 0, 0, 1, 0)
    origins, terrains, infos, extras = [], [], [], []
    for index in range(count if old else 16):
        if index == 0 and radius:
            origin = (0, radius + altitude, 0, radius)
            relief = min(3500, max(100, radius * .0025)) * [1, .65, .7, 1.1, .45][kind]
            terrain = (relief, 0, [0, .42, .28, .22, .9][kind], 1)
            info = (12345, kind, struct.unpack('i', struct.pack('f', altitude))[0], 1 if old else (2 if kind==4 else 4))
        elif index < 8:
            # Small remote bodies retain realistic angular sizes, not billboard sprites.
            origin = ((index - 3.7) * 11000000, -5500000 - index * 3800000,
                      -45000000 - index * 15000000, 35000 + index * 8000)
            terrain, info = (100, 0, .3, 1), (index * 31123, index % 5, 0, 1)
        else:
            origin, terrain, info = (0, 0, 0, 0), (0, 0, 0, 0), (0, 0, 0, 0)
        origins.append(struct.pack('4f', *origin))
        terrains.append(struct.pack('4f', *terrain))
        infos.append(struct.pack('4i', *info))
        extras.append(struct.pack('4f', body if index==0 else 0, 1, 0, 0))
    data += b''.join(origins + terrains + infos)
    if not old:
        data += b''.join(extras)
        assert len(data)==1168
    buffer = C.create_string_buffer(data)
    fn('glBufferData', None, [U, C.c_ssize_t, P, U])(0x8A11, len(data), buffer, 0x88E8)


def render_pass(handle, destination):
    _, framebuffer, width, height = destination
    bind_framebuffer(0x8D40, framebuffer)
    viewport(0, 0, width, height)
    use(handle)
    draw(0x0004, 0, 3)


def save_image(name, destination):
    _, framebuffer, width, height = destination
    bind_framebuffer(0x8D40, framebuffer)
    pixels = C.create_string_buffer(width * height * 4)
    fn('glReadPixels', None, [I, I, I, I, U, U, P])(0, 0, width, height, 0x1908, 0x1401, pixels)
    def chunk(kind, blob):
        return struct.pack('>I', len(blob)) + kind + blob + struct.pack('>I', zlib.crc32(kind + blob) & 0xffffffff)
    pixel_bytes = pixels.raw
    raw = b''.join(b'\0' + pixel_bytes[y * width * 4:(y + 1) * width * 4] for y in range(height - 1, -1, -1))
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>2I5B', width, height, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b'')
    path = output / (name + '.png')
    path.write_bytes(png)
    print(path, flush=True)

results = []

def check(name, radius, altitude, forward, kind=4, body=0):
    uniforms(radius, altitude, forward, kind, body=body)
    def frame():
        render_pass(planet_program, planet_target)
        fn('glActiveTexture', None, [U])(0x84C0)
        bind_texture(0x0DE1, planet_target[0])
        render_pass(composite_program, main_target)
    for _ in range(3):
        frame()
    finish()
    start = time.perf_counter()
    for _ in range(args.frames):
        frame()
    finish()
    milliseconds = (time.perf_counter() - start) * 1000 / args.frames
    error = fn('glGetError', U, [])()
    assert error == 0, f'GPU error: {hex(error)}'
    print(f'{name}: {milliseconds:.3f} ms/frame, two passes at {args.width}x{args.height}', flush=True)
    results.append({'scene': name, 'shader_ms': round(milliseconds, 3),
                    'width': args.width, 'height': args.height, 'planet_height': height})
    save_image(name, main_target)
    if old_program and radius and altitude <= 10000:
        uniforms(radius, altitude, forward, kind, count=16, old=True)
        render_pass(old_program, old_target)
        finish()
        start = time.perf_counter()
        for _ in range(3):
            render_pass(old_program, old_target)
        finish()
        old_ms = (time.perf_counter() - start) * 1000 / 3
        print(f'{name} old 640x360 terrain raymarch: {old_ms:.3f} ms/frame', flush=True)
        results.append({'scene': name + '-old', 'shader_ms': round(old_ms, 3), 'width': 640, 'height': 360})
        save_image(name + '-old', old_target)

check('v08-empty-space', 0, 0, (0, .08, 1))
check('v08-legacy-home', 12000, 2500, (0, -.7, 1))
check('v08-moon', 3500, 700, (0, -.7, 1), 0, 4)
check('v08-saturn', 11000, 24200, (0, -1, .01), 1, 7)
check('v08-sun', 10000, 22000, (0, -1, .01), 3, 10)
(output / 'gpu-timings.json').write_text(json.dumps({'gpu': renderer, 'measurements': results}, indent=2) + '\n')
