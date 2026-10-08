# Codex task 1: research, then make the Iris Metal port reuse mcopt

You work on two local repos:
- `~/Documents/GitRepos/mcopt` (branch `main`): a Fabric mod that implements Mojang's 26.3 render device API
  (`com.mojang.renderpearl.*`) on Apple Metal. Read `IRIS-METAL-HANDOFF.md` there first.
- `~/Documents/GitRepos/iris-mcopt` (branch `mcopt-metal`): a fork of Iris 26.3 being ported to mcopt's Metal backend.
  Read `docs/METAL-PORT.md` there. The Metal code is in `common/src/main/java/net/irisshaders/iris/metal/`.

Nothing is committed in either repo. Do not commit, push or reset anything. Do not delete the existing work: change it.

## Current state (works, but looks much worse than OpenGL Iris)

Iris on Metal currently has its own copy of the shader path: `DeviceGlsl` (GLSL rewrite), `MslCompiler` (shaderc to
SPIR-V, SPIRV-Cross to MSL, slot assignment), `MetalProgram` (pipeline states via `mcopt.metal.MetalBridge`),
`MetalUniforms`/`UniformSink`, `MetalTargets`, `MetalCustomTextures`, `MetalPackPipeline` (replaces
`IrisRenderingPipeline` on Metal), `MetalGbuffers` (redirects world passes through mcopt's `MetalHooks` +
`PassDelegate`). mcopt already does SPIR-V to MSL in `metal/src/main/java/mcopt/metal/MetalPipeline.java`, and Mojang's
frontend already compiles GLSL to SPIR-V (`com.mojang.renderpearl.frontend.shaders.GlslCompiler`, `PipelineBuilder`).
So code is duplicated, and the hand-written parts drift from both Iris's GL behaviour and mcopt.

## Part 1: research (write findings to `iris-mcopt/docs/METAL-RESEARCH.md`)

1. Study existing ports of Iris to non-GL backends. Clone read-only into `/tmp/iris-research/`:
   - https://github.com/fangbm/iris4vulkan (Iris for Minecraft 26.2's native Vulkan backend: check whether it ports
     Iris onto Mojang's device API — `GpuDevice`, `RenderPass`, `RenderPipeline`, `ShaderSource`, `compilePipeline` —
     because mcopt implements that same API on Metal, so such a port could run on mcopt almost unchanged).
   - https://github.com/aaaapai/Iris-Vulkan-Port (Iris on VulkanMod, 1.21.1).
   - Any IrisShaders/Iris upstream branch or PR about Vulkan/Aperture for 26.x, if one exists.
   For each: how they compile pack GLSL (uniform blocks, bindings, locations), how they handle render targets,
   composite/deferred, gbuffer program swapping, shadows, depth conventions (reversed Z, zero-to-one), blending,
   mipmaps, custom textures, and what they say still looks wrong. Note licenses (Iris is LGPL-3.0; glsl-transformer is
   AGPL-3.0).
2. Compare with this fork's Metal code and list every behaviour that differs from GL Iris
   (`IrisRenderingPipeline`, `CompositeRenderer`, `FinalPassRenderer`, `ShaderCreator`, `ExtendedShader`,
   `IrisSamplers`, `ShadowRenderer`, `RenderTargets`, mixins `MixinGlRenderPipeline`, `MixinGlCommandEncoder`,
   `UndoReverseZ*`). Known open gaps are listed in `IRIS-METAL-HANDOFF.md` ("Next" section).
3. Recommend one design. The goal set by the user: Iris must use mcopt's code instead of duplicating it. Two options
   to weigh: (a) build pack programs as Mojang `RenderPipeline`s with a custom `ShaderSource` and draw them through the
   device API (`RenderPass`), so Mojang's GlslCompiler + mcopt's MetalPipeline do all compiling — the same code would
   also run on Mojang's Vulkan backend; (b) keep the native redirect path but move shader translation, slot
   assignment, pipeline-state creation and uniform upload into mcopt as a small public API, and delete the Iris copies.
   Pick based on what the research shows works (for example if iris4vulkan already does (a)).

## Part 2: implement the recommended design

- Remove the duplicated code from the Iris fork; the shared logic lives in mcopt (public API in `mcopt.metal`, or the
  device API). Keep the Iris fork's mcopt dependency `compileOnly` (see `common/build.gradle.kts`).
- Fix every GL/Metal behaviour difference you listed in Part 1 that is in scope. Match GL Iris exactly; when unsure,
  read the GL code path and copy its semantics.
- Keep Complementary Reimagined r5.9.3 as the test pack:
  `~/Documents/curseforge/minecraft/Instances/MyCustommods/shaderpacks/ComplementaryReimagined_r5.9.3.zip`.

## Verify (offline only)

- Build mcopt: `cd ~/Documents/GitRepos/mcopt && tools/release`. Build Iris:
  `cd ~/Documents/GitRepos/iris-mcopt && ./gradlew :fabric:build -x test --console=plain -q`.
  Use `export JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.2.1/libexec/openjdk.jdk/Contents/Home`.
- After every Gradle build run `./gradlew --stop` in that repo. The Mac has 16 GB; idle Gradle daemons plus the game
  crashed it once.
- Offline shader check: `iris-mcopt/spike/harness/MetalSpike.java` (see its classpath in `spike/harness/cp.txt`)
  builds every program in `iris-mcopt/spike/patched/` into a Metal pipeline state. Keep an equivalent check working
  for the new design and run it: all 99 programs must pass.
- Install for a later in-game test: copy `mcopt/dist/mcopt-0.2.0-alpha.2.jar` and
  `iris-mcopt/build/libs/iris-fabric-1.11.6-snapshot+mc26.3-local.jar` into
  `~/Documents/curseforge/minecraft/Instances/MyCustommods/mods/`.
- Do NOT start Minecraft or CurseForge. The game test happens later, with the user.

## Report

Write `iris-mcopt/docs/CODEX-REPORT-1.md`: research summary with links, chosen design and why, files changed per repo,
what was deleted, GL mismatches fixed, build and harness results (paste the final lines), and what remains.
Update `docs/METAL-PORT.md` and mcopt's `IRIS-METAL-HANDOFF.md` to match the new design.
