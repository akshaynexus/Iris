#!/usr/bin/env python3
"""Phase 2 shader spike: Iris patched GLSL -> Vulkan GLSL -> SPIR-V (glslc) -> MSL 3.0 (spirv-cross).

Usage: spike.py <patched_dir> <out_dir>
Writes <out_dir>/<program>.{vsh,fsh}.vk.glsl, .spv, .metal and report.txt with every failure.
"""
import re
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

OPAQUE = re.compile(r"\b(\w*sampler\w*|\w*image\w*)\b")
# one loose uniform per line, as glsl-transformer prints them
LOOSE = re.compile(r"^\s*uniform\s+(\w+)\s+(\w+)\s*(\[[^\]]*\])?\s*(=\s*(.*?))?\s*;\s*$")
BLOCK = re.compile(r"^\s*layout\s*\(\s*std140\s*\)\s*uniform\s+(\w+)")
VERSION = re.compile(r"^\s*#version\s+.*$", re.M)

STAGES = {"vsh": "vert", "fsh": "frag", "csh": "comp", "gsh": "geom"}


def convert(sources):
    """sources: {ext: text}. Returns {ext: text} with one shared uniform block and explicit bindings."""
    loose = {}  # name -> (type, array, default)
    samplers = []  # names, in first-seen order
    blocks = []
    for text in sources.values():
        for line in text.splitlines():
            m = LOOSE.match(line)
            if m:
                ty, name, arr, _, default = m.groups()
                if OPAQUE.fullmatch(ty):
                    if name not in samplers:
                        samplers.append(name)
                else:
                    loose.setdefault(name, (ty, arr or "", default))
                continue
            b = BLOCK.match(line)
            if b and b.group(1) not in blocks:
                blocks.append(b.group(1))
    binding = {"iris_Uniforms": 0}
    for name in blocks:
        binding[name] = len(binding)
    for name in samplers:
        binding[name] = len(binding)

    members = "".join(f"\t{ty} {name}{arr};\n" for name, (ty, arr, _) in sorted(loose.items()))
    block = f"layout(std140, set = 0, binding = 0) uniform iris_Uniforms {{\n{members}}};\n" if loose else ""

    out = {}
    for ext, text in sources.items():
        lines = []
        for line in text.splitlines():
            m = LOOSE.match(line)
            if m:
                ty, name = m.group(1), m.group(2)
                if OPAQUE.fullmatch(ty):
                    lines.append(f"layout(set = 0, binding = {binding[name]}) uniform {ty} {name}{m.group(3) or ''};")
                continue  # loose value uniforms move into the block
            b = BLOCK.match(line)
            if b:
                line = line.replace("layout(std140)", f"layout(std140, set = 0, binding = {binding[b.group(1)]})", 1)
            lines.append(line)
        body = "\n".join(lines)
        body = re.sub(r"\bgl_VertexID\b", "gl_VertexIndex", body)
        body = re.sub(r"\bgl_InstanceID\b", "gl_InstanceIndex", body)
        body, n = VERSION.subn("#version 450 core\n" + block.replace("\\", "\\\\"), body, count=1)
        assert n == 1
        out[ext] = body + "\n"
    return out, binding


def run(cmd):
    p = subprocess.run(cmd, capture_output=True, text=True)
    return p.returncode, (p.stdout + p.stderr).strip()


def main():
    src, dst = Path(sys.argv[1]), Path(sys.argv[2])
    dst.mkdir(parents=True, exist_ok=True)
    programs = defaultdict(dict)
    for f in sorted(src.iterdir()):
        if f.suffix[1:] in STAGES:
            programs[f.stem][f.suffix[1:]] = f.read_text()
    report, stats = [], defaultdict(int)
    for prog, sources in programs.items():
        converted, _ = convert(sources)
        for ext, text in converted.items():
            stage = STAGES[ext]
            base = dst / f"{prog}.{ext}"
            glsl = base.with_suffix(f".{ext}.vk.glsl")
            glsl.write_text(text)
            spv = base.with_suffix(f".{ext}.spv")
            rc, msg = run(["glslc", f"-fshader-stage={stage}", "--target-env=vulkan1.2", "-fauto-map-locations",
                           "-O0", "-o", str(spv), str(glsl)])
            if rc:
                stats["glslc_fail"] += 1
                report.append(f"== {prog}.{ext}: glslc\n{msg}\n")
                continue
            rc, msg = run(["spirv-cross", "--msl", "--msl-version", "30000", str(spv), "--output",
                           str(base.with_suffix(f".{ext}.metal"))] + (["--msl-decoration-binding"] if False else []))
            if rc:
                stats["spvc_fail"] += 1
                report.append(f"== {prog}.{ext}: spirv-cross\n{msg}\n")
                continue
            stats["msl_ok"] += 1
    summary = " ".join(f"{k}={v}" for k, v in sorted(stats.items()))
    (dst / "report.txt").write_text(summary + "\n\n" + "\n".join(report))
    print(summary)


if __name__ == "__main__":
    main()
