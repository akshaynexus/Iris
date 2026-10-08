# Iris on mcopt Metal

Current design, 2026-10-09: **native redirects backed by mcopt's shared shader-program API**.
See [research](METAL-RESEARCH.md) and [task report](CODEX-REPORT-1.md).

## Ownership

| Component | Owner |
|---|---|
| Patched GLSL adaptation to Vulkan GLSL, shared uniform declarations and locations | mcopt `PackGlsl` |
| GLSL → SPIR-V, reflection, paired-stage resource slots | mcopt `PackCompiler` |
| SPIRV-Cross context, MSL options, bindings and compilation | mcopt `MetalShaderTranslation`, shared with `MetalPipeline` |
| Metal libraries, immutable descriptor cache, pipeline states and release | mcopt `PackProgram` |
| std140 writes and native uniform upload | mcopt `PackUniforms` |
| Pack directives, program fallback, uniform value producers and update schedule | Iris `MetalProgram` adapter and `MetalUniforms` |
| Target history, composite/deferred/final scheduling, semantic texture binding | Iris `MetalPackPipeline`, `MetalTargets`, `MetalCustomTextures` |
| Game/Sodium/shadow routing | Iris `MetalGbuffers`, mcopt `MetalHooks`/`PassDelegate` |

Iris keeps a compileOnly dependency on the local mcopt metal jar. It does not bundle mcopt or contain shaderc/
SPIRV-Cross calls in its Metal package. `DeviceGlsl`, `MslCompiler` and `UniformSink` have moved out of Iris.
This is option (b) from CODEX-TASK-1. It is **not** a portable RenderPipeline/ShaderSource implementation.
The earlier document described option (a) as if it already existed; that was a plan, not the running code.

The shared translation layer also invalidates mcopt's optional MSL cache when its implementation changes.
The imported shader-pack files retain LGPL-3.0 attribution and a license copy in mcopt artifacts.

## Raster behavior

- Composite passes apply integer viewport scale/offset and reject mismatched MRT dimensions like GL.
- HorizonRenderer uses the same sky-disc/dimension condition as IrisRenderingPipeline.
- Center depth uses the existing shader resources, half-life formula, and pre-hand sampling point. The first
  history sample is explicitly initialized to current depth, avoiding undefined initial texture contents.
- Mip sampling is enabled only on the physical ping-pong texture whose mip chain was generated, and resets each frame.
- A missing final program copies the current colortex0 through a format-converting draw.
- Missing pack sources use ShaderSynthesizer fallbacks; unknown game programs restore the original attachments and
  device program instead of losing draws. mcopt restores vertex/index/scissor/push-constant state after encoder restart.
- Gbuffer color writes preserve the game's channel mask. Pack blend overrides remain mapped by logical target.
- Shadow aliases follow IrisSamplers' watershadow rule. Runtime comparison samplers honor nearest/linear filtering
  and use the bound GL sampler's LEQUAL operation, rather than a hard-coded constexpr GEQUAL sampler.
- World reverse-Z and zero-to-one transforms remain paired. Shadows use forward depth: clear 1, invert the game depth comparison, and retain LEQUAL sampling. Live GL readback established this; the earlier inferred clear-0/GEQUAL description was incorrect. Sodium shadow transforms receive the shadow flag.

## Verification and limitations

Use Java 26 at `/opt/homebrew/Cellar/openjdk/26.0.2.1/libexec/openjdk.jdk/Contents/Home`.
Build mcopt with `tools/release`, stop its daemon, build Iris with
`./gradlew :fabric:build -x test --console=plain -q`, and stop that daemon too. Never leave both builds running.

Run `spike/harness/run` from this repo. It uses `cp.txt`, tests all 99 patched Complementary programs through
mcopt's public API, then performs native GPU readback tests. A Metal device must be accessible (the filesystem
sandbox can prevent that). The shader pack remains Complementary Reimagined r5.9.3 with the default profile.

Task 3 adds the real Minecraft harness in `tools/game-harness/`; see its README and [report](CODEX-REPORT-3.md). The reference is official Iris CI build 5990, not the older release. Compilation and small GPU tests alone do not prove image parity; the harness records unmodified screenshots, frame times, and intermediate buffer dumps. Camera poses, shader time, and atlas animation phase are synchronized only in harness runs. Normal gameplay retains animation.

Remaining constraints: no geometry/tessellation, compute/SSBO/images or shadowcomp execution; no DH integration;
custom raw/3D/array textures and resource-pack PBR tracking are incomplete. Shadow depth mipmaps need a shader
reduction path and are explicitly rejected. Gbuffer attachment unions above 8 are rejected rather than silently
aliasing output 7. RGB formats expand to RGBA and some low-bit packed formats are approximated. Missing vertex
attributes still use constants. These limits are not claims of full GL parity.

## Historical spike

The original 2026-10-06 experiment compiled 198 stages from 99 patched programs through glslc, SPIRV-Cross and Metal.
It established the loose-uniform block rewrite and GLSL 450 compatibility for the test pack. The current harness
uses the production mcopt API, superseding that standalone compiler experiment for verification.
