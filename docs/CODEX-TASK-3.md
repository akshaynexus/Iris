# Codex task 3: in-game test harness, then fix Metal until it matches OpenGL Iris

Repos (read `docs/METAL-PORT.md`, `docs/CODEX-REPORT-1.md`, `docs/CODEX-REPORT-2.md`, mcopt's `IRIS-METAL-HANDOFF.md`):
- `~/Documents/GitRepos/iris-mcopt`, branch `mcopt-metal` (Iris fork; Metal code in `common/src/main/java/net/irisshaders/iris/metal/`)
- `~/Documents/GitRepos/mcopt`, branch `iris-metal`

## Problem

Complementary Reimagined r5.9.3 runs on the Metal path without errors, but in game it looks like "slightly enhanced
vanilla": the shader is effectively not working. Offline checks pass, so the remaining bugs are runtime ones (wrong
targets/flips, uniforms, matrices, depth, textures, redirect coverage, blending, ...). The fix loop needs eyes: a
harness that drives the real game, captures images, and compares Metal against OpenGL Iris.

## Part 1: build the harness (`tools/game-harness/` in iris-mcopt, plus a small driver inside the fork)

Requirements from the user: it must move around the world, take screenshots and render directly, so that AI agents
can look at the images and find problems.

1. **Launching without CurseForge.** `tools/game-harness/launch-command.txt` (gitignored) is the exact java command
   CurseForge used, with secrets replaced by `REDACTED` (offline singleplayer works). Write `run.sh` that builds a
   command from it: replace `--gameDir` with a harness game dir, drop `--quickPlayPath`/quick-play args, add the
   harness system properties, optional `--width/--height` (fixed, e.g. 1280x720). Never touch the user's instance
   `~/Documents/curseforge/minecraft/Instances/MyCustommods` except to copy from it (shaderpacks, options).
2. **Two harness game dirs**, created by the runner, e.g. `~/Documents/curseforge/minecraft/Instances/IrisHarness-gl`
   and `IrisHarness-metal`: same options (render distance, fixed GUI scale, fov), same `config/iris.properties`
   (Complementary, default profile), same world.
   - GL reference: mods = fabric-api, sodium, upstream Iris
     (`MyCustommods/iris-upstream-backup/iris-fabric-1.11.7+mc26.3.jar`), **no mcopt** (with mcopt installed the fork
     blocks packs off Metal by design).
   - Metal: mods = fabric-api, sodium, mcopt (`mcopt/dist/mcopt-0.2.0-alpha.2.jar`), the fork jar.
   - The upstream Iris jar has no harness driver, so the driver must work for both: put it in a tiny separate Fabric
     mod `tools/game-harness/driver/` (its own Gradle build, client-only, depends only on Minecraft/Fabric API, built
     with the same Loom/MC versions as the fork) and install it in both game dirs. Metal-only hooks (buffer dumps) are
     called reflectively when the fork's classes are present.
3. **World.** A fixed-seed world created once by the driver (or copied from a template) with gamerules: no daylight
   cycle, no weather cycle, no mob spawning; player in creative/spectator, HUD hidden (F1) for captures.
4. **Scripted tour** (`scenes.json`): named viewpoints (position, yaw, pitch, time of day, weather): open terrain at
   noon, water surface with reflections, forest shade (shadows), sunset sky, night sky, a cave/low light, underwater.
   For each: teleport, wait for chunks and shader warm-up (frames, not just seconds), capture screenshot via the game's
   screenshot API, record frame time stats (avg/p95 over N frames).
5. **Buffer dumps (Metal).** At each viewpoint, optionally write PNGs of the pack's internals: colortex0..15 (main/alt
   as read by composite), depth (main, depthtex1/2), shadow depth and shadowcolor0/1, after gbuffers, after deferred,
   after composite. Add a small public readback in mcopt (`MetalBridge` already has `readTexture`/blit-to-buffer) and a
   dump hook in `MetalPackPipeline`. Float formats need tone mapping/normalization; label each file.
6. **Live control for agents.** While the game runs, the driver listens on `127.0.0.1` only (fixed port, e.g. 47821)
   for line commands: `tp x y z yaw pitch`, `time N`, `weather clear|rain`, `wait frames N`, `screenshot NAME`,
   `dump NAME`, `stats`, `hud on|off`, `quit`. Each replies with JSON (file paths, stats). Provide
   `tools/game-harness/ctl.py` to send commands. Document it so another agent can explore freely.
7. **Compare.** `compare.py` (deps via `uv`, e.g. `uv run --with pillow --with numpy --with scikit-image`): per scene
   SSIM and mean abs diff vs the GL reference, side-by-side and diff images, and a `report.md` + `report.html` in
   `tools/game-harness/runs/<timestamp>/` (gitignored) listing scenes, scores, fps for both backends.
8. **Safety.** Only one game process at a time. Run `./gradlew --stop` before launching the game. Kill the game on
   timeout (no frame progress for 120 s) and record it. 16 GB Mac: it crashed once from memory pressure.

## Part 2: fix loop

1. Run the GL reference tour once; keep it as the baseline.
2. Run the Metal tour, compare, and look at the screenshots and buffer dumps yourself to find the first broken stage
   (e.g. are gbuffer targets filled? is depth sane? is the shadow map filled? is the final pass reading the right
   flip?). Compare against the GL code paths (`IrisRenderingPipeline`, `CompositeRenderer`, `FinalPassRenderer`,
   `ExtendedShader`, `IrisSamplers`, `ShadowRenderer`, `RenderTargets`).
3. Fix, rebuild (mcopt `tools/release`, Iris `./gradlew :fabric:build -x test --console=plain -q`, `./gradlew --stop`
   after each), keep `spike/harness/run` passing, rerun. Repeat.
4. Target: every scene SSIM ≥ 0.90 against GL and visually the same effects (shadows, reflections, sky, fog, water),
   and Metal fps ≥ GL fps. If a scene can't reach it, document the root cause precisely.
5. Commit checkpoints locally on the current branches when a fix is verified (clear messages, no attribution lines,
   no push). Never commit `launch-command.txt`, run outputs, or Complementary's files.

## Environment

- `export JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.2.1/libexec/openjdk.jdk/Contents/Home` for builds; the game
  itself uses the java path inside `launch-command.txt`.
- Python deps through `uv`, not pip.
- When done, install the final jars into `MyCustommods/mods/` as before.

## Report

`docs/CODEX-REPORT-3.md`: how to run the harness (tour + live control), scene list, root causes found and fixed with
before/after scores, final scores and fps per scene for GL and Metal, commits made, and what still differs.

## Added by the user during the run

- Harness games must be silent: `soundCategory_master:0.0` in the harness options.txt (already added to runner.py; keep it).
