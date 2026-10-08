# Codex task 2: shader packs run on Metal only, and Iris offers "Switch to Metal"

Repos (read `docs/METAL-PORT.md`, `docs/CODEX-REPORT-1.md` and mcopt's `IRIS-METAL-HANDOFF.md` first):
- `~/Documents/GitRepos/iris-mcopt`, branch `mcopt-metal` (Iris fork; Metal code in `common/src/main/java/net/irisshaders/iris/metal/`)
- `~/Documents/GitRepos/mcopt`, branch `iris-metal` (mcopt: Mojang's 26.3 device API on Metal)

## Problem seen by the user

On Metal, opening Iris's shader menu (keybind or Sodium options → Iris) shows `ShaderPackScreenPlaceholder`:
"Iris cannot run when using Vulkan. Would you like to switch to OpenGL? This will close your game." with Switch and
Return. That text is wrong on Metal, and "Switch" moves the user off Metal. The gate for it is
`boolean vk = IrisMixinPlugin.usingVulkan || IrisMixinPlugin.usingMetal;` in `compat/sodium/config/IrisConfig.java`
(the `VKOnly` keybind path in `IrisVKOnly` uses the same placeholder; on Metal the normal keybind mixin runs).

## Required behaviour

1. **On Metal:** Iris's real shader-pack screen (`ShaderPackScreen`, its option pages, pack list, apply/reload,
   Iris settings pages in Sodium's options) works. Audit everything that screen and pack reload reach for GL calls
   (for example `Iris.reload()`, `PipelineManager.destroyPipeline()` / `resetTextureState()`, pack preview/options
   code, `IrisConfig` settings pages) and make them Metal-safe: guard with `IrisRenderSystem.METAL` or use the Metal
   path. Reloading or switching packs on Metal must destroy and rebuild `MetalPackPipeline` cleanly (redirector removed
   and reinstalled, no leaked Metal objects). Disabling shaders must give vanilla rendering on Metal.
2. **On OpenGL or Vulkan with mcopt installed** (mod id `mcopt-metal` present, see `mixin/MetalSupport.java`): shader
   packs must not run there. No `IrisRenderingPipeline` on GL, no Vulkan attempt. Keep vanilla rendering and show,
   instead of the shader menu, a screen: "Shader packs in this build run on Metal. Switch to Metal? This will close
   your game." with Switch / Return. Switch must make the next start use Metal: find how mcopt decides
   (`mcopt.metal` in `<game dir>/config/mcopt.properties` or `-Dmcopt.metal`; `metal/.../Profile.java`
   `openGlOnlyMods()`; `mixin/PreferredGraphicsApiMixin.java`; vanilla `options.txt` `preferredGraphicsBackend`) and
   set every one of them that could keep the game off Metal, then close the game the way upstream Iris's placeholder
   Switch does. If the machine can't run Metal (`PlatformCheck`), say so instead of offering Switch.
3. **Without mcopt:** keep upstream Iris behaviour unchanged.
4. **Visible proof:** the F3 debug text (`addDebugText`) and one log line at pipeline creation state the backend, e.g.
   "[Iris] Shader pack on Metal (mcopt)". Add translation keys to Iris's `en_us.json` for all new text.

## Constraints

- Match the existing code style. Keep changes small and in the right layer (Iris UI/gating in the fork; anything
  about selecting mcopt's backend may need a small public hook in `mcopt.metal`, e.g. on `MetalBridge`/`Profile`).
- Don't commit, push, reset or delete work. Don't start Minecraft or CurseForge.

## Verify

- `export JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.2.1/libexec/openjdk.jdk/Contents/Home`
- mcopt: `cd ~/Documents/GitRepos/mcopt && tools/release`, then `./gradlew --stop`.
- Iris: `cd ~/Documents/GitRepos/iris-mcopt && ./gradlew :fabric:build -x test --console=plain -q`, then `./gradlew --stop`.
  Always stop Gradle after a build (16 GB Mac; it crashed once from memory pressure).
- `spike/harness/run` must still pass (99 programs, runtime checks).
- Install: copy `mcopt/dist/mcopt-0.2.0-alpha.2.jar` and `iris-mcopt/build/libs/iris-fabric-1.11.6-snapshot+mc26.3-local.jar`
  to `~/Documents/curseforge/minecraft/Instances/MyCustommods/mods/`.

## Report

Write `docs/CODEX-REPORT-2.md`: what changed per repo, how the Metal switch works (which settings it writes), the GL
audit of the shader screen and reload path, build and harness results, and what the user should test in game.
