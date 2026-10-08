# Task 2 report — 2026-10-09

## Result

Metal opens the real shader-pack screen and Sodium Iris settings. With mcopt installed, other backends are blocked at both pack loading and pipeline creation and their menu offers Switch to Metal. Without mcopt, the existing screen routes and Vulkan-to-OpenGL switch behavior are preserved.

No commits, pushes, resets, or game/CurseForge launches were performed. Branches remain `mcopt-metal` (Iris) and `iris-metal` (mcopt).

## Changes by repository

### Iris

- `MetalSupport` centralizes mod detection and checks the live backend before permitting packs. A configured Metal preference alone is insufficient if device selection fell back.
- `ShaderPackScreenPlaceholder` retains its upstream behavior without mcopt; with mcopt it displays the requested Metal message, translated Switch/Return buttons, an unsupported-platform message without Switch, or a save-error message without exiting.
- Normal keybind, Sodium shader page/settings button, Iris API, vanilla video-settings integration, and Fabric Mod Menu use the shared screen factory. The Vulkan-only keybind already uses the updated placeholder. Metal now receives Sodium's Iris settings page and option overlays.
- `Iris.loadShaderpack()` and `createPipeline()` independently reject packs on non-Metal backends with mcopt. This also covers reload/toggle/API paths that bypass the menu. The stored pack selection is retained for a later Metal launch.
- Metal reload skips the GL texture-unit reset and the GL SSBO error cleanup. The disabled-shader vanilla pipeline skips `glUseProgram` on Metal.
- Metal construction now cleans up partially allocated resources when compilation or validation fails. Destruction is idempotent and removes the redirector before releasing resources. Composite programs are registered for cleanup before uniform setup; gbuffer programs remain owned even if uniform setup fails. Custom textures are owned before upload, and constructor failures clean up their resources. MetalProgram releases its backend if uniform construction fails.
- Cached dimension pipelines reinstall their own redirector when selected. Reload destroys all cached pipelines; a new successful Metal pipeline installs the new redirector. Disabling shaders leaves it removed.
- F3 uses `iris.backend.metal.active`: `[Iris] Shader pack on Metal (mcopt)`. Successful pipeline creation logs the same backend text plus program count. All newly introduced UI text has `en_us.json` keys.

### mcopt

- `Profile.selectMetal()` is the public persistence hook; it preserves unrelated properties and refuses unsupported platforms.
- `Profile.applyFlags()` recognizes an explicit saved Metal selection before applying flags and `openGlOnlyMods()`.
- `PlatformCheck.isSupported()` exposes the existing OS/architecture/version decision, including the existing fake-platform testing property, without loading the native renderer or opening an error dialog.

## Switch settings and shutdown

Switch writes `<game dir>/config/mcopt.properties`:

```properties
mcopt.metal=true
graphicsBackend=metal
```

The explicit `graphicsBackend=metal` selection makes Profile set the next process's `mcopt.metal=true` even if its launcher supplies `-Dmcopt.metal=false`. Ordinary properties retain the existing JVM-argument precedence when this explicit selection is absent. Remove `graphicsBackend` to restore that precedence. The properties file documents this exception. Launcher files/JVM arguments are not edited, and the running process's backend flags are not changed.

Iris also sets vanilla `preferredGraphicsBackend` to `PreferredGraphicsApi.DEFAULT` and calls `options.save()`, replacing a saved OpenGL/Vulkan preference. Vanilla has no Metal enum member; mcopt's existing `PreferredGraphicsApiMixin` prepends Metal to the resulting backend list. The explicit true property prevents `openGlOnlyMods()` from selecting OpenGL, and this fork already declares `mcopt:metal` metadata.

Iris then follows upstream's sequence: halt an integrated server if present, disconnect with the saving screen, and stop Minecraft. A properties-save failure leaves the game open and shows a translated error. Unsupported platforms receive no Switch button. mcopt's existing prelaunch platform rejection remains unchanged and can reject an unsupported machine before any menu is reached.

## GL audit

- Shader screen, pack list, option pages, hover/preview UI, and GUI helpers render via `GuiGraphicsExtractor` and device render pipelines. Three remaining raw calls were found in `OldImageButton` / `IrisButton` (depth test and blending); all are now guarded on Metal. Remaining GL imports in other GUI helpers are unused.
- Apply/toggle/options reset go through Iris's configuration API to `reload()`: initialize configuration, clear captured reload state, destroy cached pipelines, close the pack zip, load pack data, immediately prepare a pipeline when a world is open. Pack parsing and option navigation do not require a GL context.
- Pack macro generation already has Metal guards for GL version/extensions; sampler limits and the existing Metal shader/uniform path were checked. Pack previews/options do not construct an OpenGL rendering pipeline.
- `PipelineManager.resetTextureState()` was an unconditional sequence of GL active-texture/bind calls; it now returns on Metal.
- The shader screen's debug toggle reached `Iris.setDebug(false)`, including a GlDevice cast and GL callback setup. Metal now persists the debug setting without that callback path.
- Pipeline creation failure no longer invokes GL SSBO cleanup on Metal. Partial Metal resources are released before vanilla fallback.
- `VanillaRenderingPipeline.beginLevelRendering()` was an unconditional GL program unbind; it is now guarded so disabling packs stays on vanilla Metal rendering.
- Sodium settings save configuration and query pipeline properties. Its backend-choice overlay inspects backend type but does not invoke GL; color-space and shadow-distance controls use the existing settings bindings. The real settings page is available on Metal.
- Metal destruction releases programs/libraries/PSOs, targets, samplers, depth states, shadow textures, center-depth textures, custom/default textures, quad/gbuffer buffers, and horizon resources. ShadowRenderer's existing destroy method is empty; Metal owns and releases its shadow allocations.

## Verification

Java 26: `/opt/homebrew/Cellar/openjdk/26.0.2.1/libexec/openjdk.jdk/Contents/Home`.

- mcopt `tools/release`: PASS. Followed by `./gradlew --stop` (one daemon stopped).
- Iris `./gradlew :fabric:build -x test --console=plain -q`: PASS, including the final screen-route changes. Every build attempt was followed by `./gradlew --stop` (one daemon stopped). Builds were sequential.
- `spike/harness/run`: PASS with GPU access: `ok=99 fail=0`; center-depth-check PASS; runtime-check PASS (viewport, std140 matrix/scalar upload, LEQUAL sampler, immutable PSO cache). The first sandboxed attempt failed to create a Metal device; the GPU-access run succeeded. This harness does not launch Minecraft.
- Separate JVM checks using the built mcopt classes: selection persistence PASS; next-start override of `-Dmcopt.metal=false` PASS; ordinary JVM precedence without the new selection PASS; unsupported Linux/x86_64/macOS 15 rejection and supported macOS 26/compatibility 16 acceptance PASS. Unrelated settings survived and selection did not change the live backend flag. Test scratch files are under `/tmp/iris-research/BackendSelectionCheck.java` and `task2-backend-check/`.
- `git diff --check`: PASS in both repositories. Build warnings were limited to FSEvents, incubating modules, and deprecation notices.

Logs: `/tmp/iris-research/task2-mcopt-build.log`, `task2-iris-build-final.log`, `task2-harness.log` (sandbox failure), and `task2-harness-gpu.log` (success).

These checks establish compilation and the existing native shader/runtime checks, not interactive GUI behavior or visual parity. Reload/resource ownership was audited in code; a live game reload/leak stress test remains for the user.

## In-game checks for the user

1. On Metal, open Iris from the keybind, Sodium, and Mod Menu. Check the pack list, pack options/profiles/reset, Iris settings, and debug toggle in both directions.
2. Enable Complementary; verify the F3 line and creation log say `[Iris] Shader pack on Metal (mcopt)`.
3. Reload several times, change pack/options, and change dimensions and return. Verify rendering continues, with no stale redirector, crashes, or sustained resource growth.
4. Disable shaders: verify vanilla rendering stays on Metal. Enable again and verify the pipeline rebuilds.
5. With mcopt on OpenGL and Vulkan, verify vanilla rendering even with `enableShaders=true`, and check the Metal prompt through every available entry point. Return must leave the game running.
6. Click Switch, restart using the same launcher (including a deliberately configured `-Dmcopt.metal=false`), and verify Metal is chosen, the real screen opens, and the selected pack works. Verify both config keys and vanilla's default backend preference were saved.
7. Without mcopt, verify ordinary OpenGL Iris behavior and the original Vulkan/OpenGL placeholder remain unchanged.

## Installation

Copied both requested jars into `~/Documents/curseforge/minecraft/Instances/MyCustommods/mods/`. Source and installed SHA-256 hashes match:

```text
mcopt-0.2.0-alpha.2.jar
8c5637c94deb86f1a683b37ffb8dd2f58c7309b7532f0b9077c2c82e171b0f1b
iris-fabric-1.11.6-snapshot+mc26.3-local.jar
186fab6c9e152720366eda5a62c54294fb2e092af38b353e46bbc01a0a60a7a7
```
