These checks do not launch Minecraft or verify Fabric event integration.

`validate_shaders.py` compiles all shipped fragment and vertex shaders and Minecraft's fullscreen vertex shader into SPIR-V with the actual LWJGL shaderc version cached by Gradle. Its target and compiler options match Minecraft 26.3's `GlslCompiler`.

```sh
JAVA_HOME=/path/to/jdk-25 ./gradlew compileClientJava
JAVA_HOME=/path/to/jdk-25 python3 tools/validate_shaders.py --download-natives
```

The optional download fetches only two LWJGL native jars from `libraries.minecraft.net`, into `build/shader-check-libs`. Existing native jars can be supplied with `--native-dir` instead.

On macOS, `python3 tools/render_space_gl.py` creates a hidden CGL context and tests the two fullscreen 0.8 rendering passes at 1920×1080, including the solar Moon, Saturn and Sun. It saves PNGs and `gpu-timings.json` under `build/render-check`, optionally including an old 640×360 terrain-raymarch comparison. It needs access to the local graphics session; no visible window opens. The harness adapts GLSL headers to Apple's OpenGL 4.1 and writes the same 1168-byte, 16-body uniform layout as `SpaceRenderer`. The seed-correct Earth atlas requires an actual server, so this standalone harness uses the legacy home preview instead.

The standalone harness checks shader appearance and GPU execution. Full Minecraft integration is checked separately by `./gradlew runClientGameTest`, which exercises preloading without a world switch, altitude-preserving voxel entry, creative flight, server/client block updates, saved-building LOD, telescope zoom, takeoff and returning home. Its screenshots are under `build/run/clientGameTest/screenshots`.

The fixed render seed is independent of a user's world. Performance numbers describe the shader passes, tested resolution and GPU; they are not whole-game FPS measurements.

The offscreen timing harness does not draw streamed LOD meshes or ring block instances or solar ribbons; those and the seed-correct Earth atlas are exercised by the real client test.

`python3 tools/generate_planet_assets.py` deterministically rebuilds the custom plasma animation and eight native model rotations for each planet-only block. It does not replace vanilla block models.

The real client gallery also covers version-7 geology and an actual lunar tube entrance/interior. `/space landmarks` lists local coordinates for the named landforms.
