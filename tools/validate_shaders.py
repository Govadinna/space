#!/usr/bin/env python3
"""Compile shipped shaders with Minecraft's own shaderc options, without a game window."""
import argparse
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--java", default=str(Path(os.environ["JAVA_HOME"]) / "bin/java")
                    if os.environ.get("JAVA_HOME") else shutil.which("java"))
parser.add_argument("--native-dir", type=Path, default=ROOT / "build/shader-check-libs")
parser.add_argument("--download-natives", action="store_true",
                    help="Download the two matching LWJGL native jars from libraries.minecraft.net")
args = parser.parse_args()
caches = [ROOT / ".gradle-home/caches", Path.home() / ".gradle/caches"]
jars = []
for cache in caches:
    for artifact in ("lwjgl", "lwjgl-shaderc"):
        matches = list(cache.glob(f"modules-2/files-2.1/org.lwjgl/{artifact}/*/*/{artifact}-*.jar"))
        plain = [path for path in matches if "natives-" not in path.name and "sources" not in path.name]
        if plain and not any(path.name.startswith(artifact + "-") and "natives" not in path.name
                             for path in jars):
            jars.append(sorted(plain)[-1])
if len(jars) != 2:
    sys.exit("Run ./gradlew compileClientJava first so the Minecraft LWJGL jars are cached.")
version = next(path for path in jars if path.name.startswith("lwjgl-shaderc-")).name.removeprefix("lwjgl-shaderc-").removesuffix(".jar")
native = {"Darwin": "macos", "Linux": "linux", "Windows": "windows"}[platform.system()]
if platform.machine().lower() in ("arm64", "aarch64"):
    native += "-arm64"
for artifact in ("lwjgl", "lwjgl-shaderc"):
    filename = f"{artifact}-{version}-natives-{native}.jar"
    destination = args.native_dir / filename
    cached = next((path for cache in caches for path in cache.glob(f"modules-2/files-2.1/org.lwjgl/{artifact}/{version}/*/{filename}")), None)
    if cached:
        jars.append(cached)
    elif destination.exists():
        jars.append(destination)
    elif args.download_natives:
        destination.parent.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(f"https://libraries.minecraft.net/org/lwjgl/{artifact}/{version}/{filename}", destination)
        jars.append(destination)
    else:
        sys.exit(f"Missing {filename}; add --download-natives, or point --native-dir at existing native jars.")

with tempfile.TemporaryDirectory(prefix="space-shader-check-") as temporary:
    vertex = Path(temporary) / "screenquad.vsh"
    for cache in caches:
        for jar in cache.glob("fabric-loom/minecraftMaven/net/minecraft/*/*/*.jar"):
            if "client" not in jar.name.lower() or "sources" in jar.name:
                continue
            with zipfile.ZipFile(jar) as archive:
                try:
                    vertex.write_bytes(archive.read("assets/minecraft/shaders/core/screenquad.vsh"))
                except KeyError:
                    continue
            break
        if vertex.exists():
            break
    if not vertex.exists():
        sys.exit("Minecraft client jar was not found in the Gradle cache.")
    shaders = sorted(path for path in (ROOT / "src/client/resources/assets/seamlessspace/shaders/core").iterdir()
                     if path.suffix in {".fsh", ".vsh"})
    command = [args.java, "--enable-native-access=ALL-UNNAMED", "-cp", os.pathsep.join(map(str, jars)),
               str(ROOT / "tools/ValidateSpaceShader.java"), *map(str, shaders), str(vertex)]
    raise SystemExit(subprocess.call(command, cwd=ROOT))
