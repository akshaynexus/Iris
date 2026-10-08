#!/usr/bin/env -S uv run --script
# /// script
# dependencies = ["pillow", "numpy", "scikit-image"]
# ///
"""Compare unmodified RGB captures; no registration, resizing or score masking."""
import argparse
import html
import json
from pathlib import Path
import numpy as np
from PIL import Image
from skimage.metrics import structural_similarity

p = argparse.ArgumentParser()
p.add_argument('gl', type=Path)
p.add_argument('metal', type=Path)
p.add_argument('--out', required=True, type=Path)
a = p.parse_args()
a.out.mkdir(parents=True, exist_ok=True)
gl = json.loads((a.gl / 'results.json').read_text())
metal = json.loads((a.metal / 'results.json').read_text())
gl_manifest = json.loads((a.gl / 'manifest.json').read_text())
metal_manifest = json.loads((a.metal / 'manifest.json').read_text())
if gl_manifest.get('shader_animation_time') != metal_manifest.get('shader_animation_time'):
    raise SystemExit('Shader animation phases differ; rerun with matching harness settings')
if gl_manifest.get('texture_animation_frozen') != metal_manifest.get('texture_animation_frozen'):
    raise SystemExit('Atlas animation settings differ; rerun with matching harness settings')
if gl_manifest.get('camera_lock') != metal_manifest.get('camera_lock'):
    raise SystemExit('Camera locking differs; rerun with matching harness settings')
for key in ('framebuffer','render_distance','simulation_distance','shadow_distance'):
    if gl_manifest.get(key) != metal_manifest.get(key):
        raise SystemExit(f'Mismatched {key}; refusing misleading comparison')
if (gl_manifest.get('mode','shaders') == 'shaders') != (metal_manifest.get('mode','shaders') == 'shaders'):
    raise SystemExit('Shader-enabled state differs')
if gl_manifest['scenes'] != metal_manifest['scenes']:
    raise SystemExit('Scene definitions differ; refusing comparison')
expected = [s['name'] for s in gl_manifest['scenes']]
if set(gl) != set(expected) or set(metal) != set(expected):
    raise SystemExit('Incomplete scene sets; refusing a partial parity report')
rows = []
sections = []
scores = {}
for name in expected:
    left = Image.open(a.gl / 'screenshots' / (name + '.png')).convert('RGB')
    right = Image.open(a.metal / 'screenshots' / (name + '.png')).convert('RGB')
    if left.size != right.size:
        raise SystemExit(f'{name}: unequal capture dimensions')
    x, y = np.asarray(left), np.asarray(right)
    score = float(structural_similarity(x, y, channel_axis=2, data_range=255))
    diff = np.abs(x.astype(np.float32) - y.astype(np.float32))
    mad = float(diff.mean()) / 255
    side = Image.new('RGB', (left.width * 2, left.height))
    side.paste(left); side.paste(right, (left.width, 0))
    side.save(a.out / f'{name}-side.png')
    Image.fromarray(np.clip(diff * 4, 0, 255).astype('uint8')).save(a.out / f'{name}-diff.png')
    gf, mf = gl[name]['stats']['fps'], metal[name]['stats']['fps']
    passed = score >= .9 and mf >= gf
    scores[name] = dict(ssim=score, mean_abs_diff=mad, gl_fps=gf, metal_fps=mf, passed=passed)
    rows.append(f'| {name} | {score:.4f} | {mad:.4f} | {gf:.2f} | {mf:.2f} | {"PASS" if passed else "FAIL"} |')
    sections.append(f'<h2>{html.escape(name)}</h2><p>SSIM {score:.4f}; mean absolute difference {mad:.4f}; GL {gf:.2f} fps; Metal {mf:.2f} fps.</p><p>GL left, Metal right. Difference below amplified 4×.</p><a href="{name}-side.png"><img alt="GL and Metal comparison" src="{name}-side.png"></a><a href="{name}-diff.png"><img alt="Absolute RGB difference amplified four times" src="{name}-diff.png"></a>')
(a.out / 'scores.json').write_text(json.dumps(scores, indent=2))
(a.out / 'report.md').write_text('# GL versus Metal\n\nUnmodified RGB images; SSIM data range 255. Mean absolute difference normalized to 0–1. FPS uses mean frame interval, excludes dump work. Targets: SSIM ≥ 0.90 and Metal FPS ≥ GL FPS.\n\n| Scene | SSIM | MAD | GL FPS | Metal FPS | Target |\n|---|---:|---:|---:|---:|---|\n' + '\n'.join(rows) + '\n\n' + '\n\n'.join(f'![{n}: GL left, Metal right]({n}-side.png)\n\n![{n}: difference ×4]({n}-diff.png)' for n in expected))
(a.out / 'report.html').write_text('<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>GL versus Metal</title><style>body{font:16px system-ui;max-width:1600px;margin:32px auto;padding:0 20px;color:#1c1917;background:#fafaf9}img{max-width:100%;height:auto}h2{margin-top:48px}</style><h1>GL versus Metal</h1>' + ''.join(sections) + '</html>')
print(json.dumps(scores, indent=2))
