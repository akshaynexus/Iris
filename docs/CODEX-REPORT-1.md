# Task 1 report — 2026-10-09

## Result

Implemented option (b): Iris's native redirects now use mcopt's public shader runtime. Both requested builds pass,
all 99 patched Complementary programs create Metal pipeline states through that API, GPU readback checks pass,
and both jars are installed. No commits, pushes or resets. Minecraft and CurseForge were not started.

This is not a claim of complete GL image parity. The task's later user-run game comparison remains necessary;
feature limits and remaining semantic gaps are listed below.

## Research and decision

Full findings: [METAL-RESEARCH.md](METAL-RESEARCH.md).

- [iris4vulkan, 424e967](https://github.com/fangbm/iris4vulkan/tree/424e96796d21d0810aadbc609db698c3c3a8b342)
  uses Mojang's GLSL compiler, RenderPipeline and RenderPass concepts, but calls VulkanRenderPipeline directly
  through VulkanDevice accessors. Its capability table leaves several world passes and real shadows unimplemented.
  It is not a drop-in backend-independent port for mcopt's 26.3 API.
- [Iris-Vulkan-Port, 47babb4](https://github.com/aaaapai/Iris-Vulkan-Port/tree/47babb4aac66b16084fb419e8d85c2e14e70d41e)
  targets VulkanMod on 1.21.1, with its own renderer integration. Its README identifies volumetric lighting and
  color-grading artifacts. It does not supply a Mojang 26.3 device API implementation.
- Branch enumeration and PR searches found no upstream Iris Vulkan/Aperture implementation to transplant.
  [Aperture's example pack](https://github.com/IrisShaders/Aperture-Example-Pack) is not such an implementation.

Option (a) remains attractive for portability, but neither studied fork offers the complete portable path assumed
in the original plan. Option (b) retains the working Metal draw integration and consolidates the duplicated backend
work. Device and pack pipelines now share SPIRV-Cross context/options/binding/compile code inside mcopt.
Iris retains pack semantics and its compileOnly mcopt dependency. It does not bundle mcopt.

Both research clones remain under `/tmp/iris-research/`, unmodified. Both retain LGPL-3.0 notices, and their
README/LICENSE-DEPENDENCIES identify glsl-transformer as AGPL-3.0. No code from either research fork was imported.
Moved local Iris code retains LGPL attribution and license text in mcopt artifacts.

## Files changed by this task

These lists describe this task's changes, not every pre-existing uncommitted file in either repository.

### mcopt

Under `metal/src/main/java/mcopt/metal/`:

- Added `PackGlsl.java`: shared GLSL adaptation and uniform declarations.
- Added `PackCompiler.java`: GLSL compilation, reflection and paired-stage slot assignment.
- Added `MetalShaderTranslation.java`: common SPIRV-Cross implementation used by both `PackCompiler` and
  `MetalPipeline`.
- Added `PackProgram.java`: Metal libraries, PSO creation/cache, immutable copied cache keys, resource ownership.
- Added `PackUniforms.java`: std140 storage, typed scalar/vector/matrix writes and encoder upload.
- Changed `MetalPipeline.java`: delegates translation and includes shared implementation bytes in MSL cache salt.
- Changed `MetalBridge.java`, `Native.java`: viewport, comparison samplers and original device-pipeline binding.
- Changed `PassDelegate.java`, `MetalRenderPass.java`: encoder-restart notification and geometry-state replay.
- Changed `MetalHooks.java`: corrected stale ownership comment.

Also changed `metal/src/main/native/mcmetal.m`, `metal/build.gradle`, `tools/release`, `NOTICE`, and
`IRIS-METAL-HANDOFF.md`; added `LICENSE-IRIS-LGPL-3.0`. Jars and release zip include the extra license.

### Iris

Under `common/src/main/java/net/irisshaders/iris/`:

- Removed production `metal/DeviceGlsl.java`, `metal/MslCompiler.java`, `metal/UniformSink.java` after moving
  their behavior into mcopt. Their previous copies were untracked, so git does not show tracked-file deletions.
- Changed `metal/MetalProgram.java` into an Iris adapter for mcopt program ownership and upload.
- Changed `metal/MetalUniforms.java` and `gl/IrisRenderSystem.java` to use mcopt's reflected layout/uniform storage.
- Changed `metal/MetalPackPipeline.java` and `metal/MetalGbuffers.java` for the raster fixes below.

Updated `spike/harness/MetalSpike.java`; added `MetalRuntimeCheck.java` and executable `spike/harness/run`.
Updated `docs/METAL-PORT.md`; added `docs/METAL-RESEARCH.md` and this report. The existing compileOnly declaration
in `common/build.gradle.kts` remains intact. Historical standalone spike files remain as research, not runtime code.

## GL mismatches fixed

1. Composite viewport scale and offsets now use GL's integer truncation and a native viewport. MRT size mismatches
   now fail explicitly, as in CompositeRenderer.
2. HorizonRenderer now runs under IrisRenderingPipeline's sky-disc and dimension conditions, with fog alpha 1.
3. `iris_centerDepthSmooth` now binds an R32F history texture sampled before the hand. It uses the production
   center-depth shaders and the same half-life formula. Initial history is explicitly current depth rather than
   undefined memory; subsequent samples follow the GL smoothing equation.
4. Mip filtering starts only after generation for the actual main/alt texture and resets per frame. Allocating a
   mip chain no longer enables uninitialized mip sampling on both textures from frame start.
5. Packs without a final program receive a fullscreen colortex0 copy, including format conversion.
6. `shadow` selects the pre-translucent depth texture when `watershadow` exists, matching IrisSamplers.
7. Comparison samplers are runtime mcopt objects, with pack nearest/linear filtering. Their LEQUAL comparison
   matches the bound GL sampler, which overrides ShadowRenderer's texture-level GEQUAL setting. Shadow color
   mip chains requested by directives are allocated and generated.
8. Gbuffer attachment color writes now preserve the game's per-channel mask, alongside existing blend overrides.
9. Missing pack sources use ShaderSynthesizer fallbacks with alpha/fog values. Unknown game pipelines restore
   their original attachments and device program instead of dropping draws. Vertex/index/scissor/push constants
   are restored when this reopens the native encoder.
10. An attachment union exceeding eight now fails explicitly instead of mapping discarded outputs onto slot 7.
    This is a diagnostic correction, not support for more than eight simultaneous attachments.

The world reverse-Z/zero-to-one transform path remains paired; no isolated activation of UndoReverseZ mixins.
Depth image equivalence, scene coverage of fallback routing and the full extended-attribute path still need the game test.

## Verification

Java: `/opt/homebrew/Cellar/openjdk/26.0.2.1/libexec/openjdk.jdk/Contents/Home`.
Every Gradle build attempt was followed by `./gradlew --stop` in that repository. Builds were sequential.

mcopt command: `tools/release`. Final log lines:

```text
1 warning
dist/mcopt-metal-0.2.0-alpha.2.zip
dist/mcopt-0.2.0-alpha.2.jar
  nested: mcopt-fps 0.2.0-alpha.2 (Apache-2.0)
Stopping Daemon(s)
1 Daemon stopped
mcopt build exit=0
```

Iris command: `./gradlew :fabric:build -x test --console=plain -q`. Final log lines:

```text
Note: Some input files use or override a deprecated API.
Note: Recompile with -Xlint:deprecation for details.
Stopping Daemon(s)
1 Daemon stopped
Iris build exit=0
```

Harness command: `spike/harness/run`. Result lines (LWJGL Java Unsafe deprecation warning omitted between checks):

```text
ok   099_mekanism_flame_shadow                buffers=[iris_Uniforms, iris_DynamicTransforms, iris_Projection] textures=2 compare=[] uniforms=1388B
ok=99 fail=0
center-depth-check: PASS (production shader resources, first sample, depth inversion, R32F target)
runtime-check: PASS (viewport, std140 matrix/scalar upload, LEQUAL sampler, immutable PSO cache)
```

The initial sandboxed native attempt could not create a Metal device. The authorized GPU-access run passed.
No game process was launched. Native readback validates a 2×2 viewport inside a 4×4 target, reflected matrix/scalar
writes, a comparison texture lookup, PSO cache-key immutability, and the production center-depth shader's first sample.
It does not exercise a Minecraft world or certify all pack appearance.

Artifact inspection confirmed mcopt contains PackProgram and the LGPL notice, Iris contains no mcopt classes or
removed compiler classes, and `git diff --check` passes in both repos. Builds reported non-fatal FSEvents and Java
incubator/deprecation notices. A final process-list query was unavailable in the sandbox; successful Gradle stop
output is the daemon-shutdown evidence.

Full final logs: `/tmp/iris-research/mcopt-build-final.log`, `iris-build-final.log`, `harness-final.log`.

## Installation

Copied both requested jars to `~/Documents/curseforge/minecraft/Instances/MyCustommods/mods/`.
Build output and installed copy have matching SHA-256 values:

```text
mcopt-0.2.0-alpha.2.jar
43384bfcb0bef691a16b4f8d3c51f32e85d2926b21ad94e88a5847ba7c2464de
iris-fabric-1.11.6-snapshot+mc26.3-local.jar
af154d69d30a96be8b6129e00f5a25e6c3993b09238c6182be0d14141e89b4ee
```

ComplementaryReimagined_r5.9.3.zip remains unchanged at the requested shaderpacks path.

## What remains

- User-run comparison with GL: sky/horizon, shadow edges, water/translucency, depth effects, PBR, fallback draws,
  and temporal stability. No screenshots, FPS improvement or complete GL parity can be claimed from these checks.
- No compute, images, SSBOs or shadowcomp execution. Complementary POTATO through HIGH disable colored-light
  compute; VERYHIGH/ULTRA are outside the verified baseline.
- Geometry/tessellation and more than eight simultaneous color attachments require unavailable stages or a new
  pass-splitting implementation. The current union approach can reject a pack even when each individual program
  writes eight or fewer attachments.
- Shadow depth mipmaps need a shader reduction implementation and are explicitly rejected, rather than sending
  an unsupported depth format to Metal's color mip generator.
- Raw/3D/array custom texture support, complete resource-pack PBR tracking and DH remain incomplete.
- Missing vertex attributes still use zero constants; some low-bit packed formats are approximated and RGB uses
  RGBA storage. These are unresolved semantic differences outside the tested baseline, not parity fixes.
- Existing compiler adaptation is tuned to Iris's printed shader form and literal uniform defaults. It is not a
  general GLSL parser. Arbitrary pack syntax/array initializers need broader coverage.
