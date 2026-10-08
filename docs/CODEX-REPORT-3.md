# Task 3: real-game GL/Metal comparison

## Reference and reproducibility

The primary reference is official Iris CI build **5990**, branch `26.3`, commit `770155cc9`, run `37201035022`, supplied as `/tmp/iris-research/iris-ci-latest-26.3.jar`. Its mod version is `1.11.6-snapshot+mc26.3-build.5990`; SHA-256 is `a39da8607a3d7f7dab09c9318193da2b6fed79492627d06a7f7b56c76f7d5515`. The runner installs this jar into `IrisHarness-gl`, with Fabric API and Sodium, without mcopt. The older 1.11.7 captures remain secondary references only.

See [harness instructions](../tools/game-harness/README.md) for builds, tours, live control, buffer formats, and comparison commands. Runs, private launch text, world data, and shader-pack files are ignored. The driver is a separate Fabric mod usable with unmodified official Iris; reflective hooks provide Metal and optional GL diagnostics.

Both instances use the same immutable world template (seed `-5068762119017331336`), Complementary Reimagined r5.9.3 default HIGH, render distance 8, simulation distance 5, FOV 70, 1280×720 captures, hidden HUD, and **master volume 0.0**. Gamerules stop world time, weather, and new mob spawning. Existing entities can still move. Cameras are locked to the teleport pose, including render extraction, and mouse turn input is canceled in harness runs. Iris shader animation time is fixed at 60 seconds and Minecraft atlas animation at its initial frame on both backends. These diagnostic controls do not ship into normal gameplay: only the separate harness mod implements them. No shader effect is disabled or shader-pack file modified.

Each viewpoint warms for 240 frames and samples 300 frame intervals. The AFK input timestamp is refreshed to prevent Minecraft's idle FPS cap; each sample must report throttle `NONE`. Timing is CPU frame interval including pacing/GPU waits, not GPU-only timing. Dump work occurs after screenshots and timing. Only one game runs at a time; Gradle is stopped before launch. The watchdog records and terminates a game that makes no frame progress for 120 seconds.

## Scenes

| Scene | Position command | Yaw / pitch | Time |
|---|---|---|---:|
| Noon terrain | -1040 90 224 | 0 / 25 | 6000 |
| Water reflections | -1000 64 260 | 90 / 10 | 6000 |
| Forest shade | -1040 68 250 | 0 / 0 | 6000 |
| Sunset sky | -1000 70 260 | 90 / 0 | 12000 |
| Night sky | -1000 70 260 | 0 / -30 | 18000 |
| Cave low light | -1070 41 250 | 90 / 5 | 18000 |
| Underwater | -1000 59 260 | 90 / 0 | 6000 |

Weather is clear throughout. Minecraft centers integer X/Z teleport coordinates; the driver records the resulting actual pose.

## Root causes and fixes

### Shadow depth selected the wrong casters

The prior implementation inferred that upstream GL cleared shadow depth to zero and used reversed-depth comparison. Actual GL shadow readback disproved that: at the noon viewpoint its range was `0.48204842..1.0`; Metal had clear zero and a maximum around `0.5992189`. Metal selected farther shadow casters instead of the nearest ones. The fix clears shadow depth to **1**, converts the game's reversed-depth comparison to the shadow pass's forward-depth comparison, retains LEQUAL shadow sampling, and passes `key.isShadow()` to Sodium's shader transform. The corrected Metal shadow range was `0.48204574..1.0`. Tree shadows and sunset light shafts reappeared.

This supersedes the earlier clear-0/GEQUAL explanation. Main world reversed depth remains separate and unchanged.

### Water/underwater streak investigation

The user-reported differences in `runs/shadow-fixed/ci-comparison` were real differences between those captures, but those runs did not synchronize animation phases. Shader clock synchronization alone improved water SSIM to **0.9833**, while underwater remained **0.9489** with a visible caustic difference.

The diagnostic GL driver captured the official CI pipeline before deferred, after deferred, and after composite. The streak pattern was already present in opaque `colortex0`, before any deferred/composite screen-space pass, and could also be seen in the GL opaque capture. Complementary's `shaders/program/shadow.glsl` water-caustics branch for `WATER_CAUSTIC_STYLE < 3` derives caustic color from the animated water atlas sample. Minecraft atlas animation ticks were still advancing independently of Iris's frozen `frameTimeCounter`. Unequal launch/render timing therefore changed the projected water-caustic pattern, including vertical streaks on submerged sand. Freezing the atlas at the same frame in both harnesses removed this mismatch; underwater SSIM became **0.9954**, with matching side-by-side terrain lighting.

The depth investigation found matching opaque terrain geometry and row order in GL/Metal depth copies. `IrisSamplers` uses nearest sampling for depthtex0/1/2, matching Metal. Both pipelines copy opaque depth before deferred/translucents and pre-hand depth at the hand boundary. Composite target flips select physical main/alt textures; mip sampling follows the texture whose chain was generated. There was no evidence supporting a depth-coordinate, clamp/repeat, or depth-copy correction for these captures, so none was invented. Diagnostic depth PNGs use per-image min/max; tiny moving particles can change the extrema substantially, so raw PNG brightness differences alone are not proof of wrong depth.

The fix here is reproducible capture state, not disabling caustics in normal gameplay. Water, reflections, refraction, shadows, and fog remain enabled.

### Camera drift and misleading timing

Earlier sunset/night captures were contaminated by mouse-driven pose changes. The driver now locks local position/rotation before rendering and camera extraction, rejects unexpected rotation at capture, and prevents mouse turn input. Earlier idle GL samples also hit Minecraft's AFK cap; the harness now prevents and checks that limiter. Those measurements are not used as the final reference.

## Validation and artifacts

- Driver, Iris Fabric jar, and mcopt release builds succeeded; Gradle daemons were stopped after builds.
- `spike/harness/run`: **99 programs passed, 0 failed**; center-depth and native runtime GPU checks passed after the shadow fix.
- Comparison self-check: identical pairs produced SSIM 1 and zero mean absolute difference.
- Python syntax and `git diff --check` passed.
- `runs/ci-5990-deterministic/gl`: primary synchronized CI baseline.
- `runs/ci-5990-deterministic/metal`: complete Metal diagnostic tour with all seven scenes and three stages of buffer dumps.
- `runs/water-gl-probe/gl`: official GL stage probes that exposed the animated caustic pattern already in opaque terrain.
- `runs/water-gl-synchronized/gl`: final synchronized GL stage probes; opaque underwater caustics visually match the Metal opaque dump, confirming the mismatch disappears at its first visible stage.
- `runs/reference/gl`: retained older release reference, not the primary baseline.

Images are compared as unmodified full-frame RGB, without registration, resizing, masking, or deleting moving entities. Remaining tiny differences include entity/particle motion, temporal sampling, and backend rounding. This is a tested result for these seven views and this pack/profile, not a claim that every shader pack or unsupported feature works.

## Local checkpoints

- mcopt `714128a`: asynchronous texture readback for the game harness.
- Iris `fc9083fb9`: corrected shadow depth and Sodium shadow transform.
- Iris `33ab7d853`: deterministic GL/Metal harness, live control, stage dumps, comparison tools.

Pre-existing task-2 working-tree changes were preserved and excluded from task-3 checkpoints. No push was performed.

## Final measured and visual result

All seven scenes meet SSIM ≥ 0.90 and Metal FPS ≥ GL in the final tour. Each final side-by-side image was opened and visually examined: tree shadows and shoreline lighting, clear sand refraction and bright water reflections, underwater caustics, noon fog, sunset shafts/sky, night stars, and cave lighting match the reference effects. Small moving turtles and particles are retained in comparisons. The result is visual effect parity within the stated target, not pixel identity.

Final artifacts: [comparison report with side-by-side and difference images](../tools/game-harness/runs/ci-5990-final/comparison/report.html), [Markdown comparison](../tools/game-harness/runs/ci-5990-final/comparison/report.md).

| Scene | SSIM | GL FPS | Metal FPS | Result |
|---|---:|---:|---:|---|
| noon-terrain | 0.9956 | 73.61 | 123.45 | PASS |
| water-reflections | 0.9892 | 71.27 | 141.17 | PASS |
| forest-shade | 0.9926 | 95.57 | 162.18 | PASS |
| sunset-sky | 0.9658 | 89.06 | 138.10 | PASS |
| night-sky | 0.9989 | 122.93 | 214.38 | PASS |
| cave-low-light | 0.9983 | 89.52 | 146.93 | PASS |
| underwater | 0.9945 | 97.95 | 168.75 | PASS |

The diagnostic tour with three-stage dumps measured night sky at 119.96 FPS versus GL 122.93, despite excluding dump calls from the timed window. A complete fresh tour without dumps measured 214.38 FPS there and exceeded GL in every scene. Both runs are retained; the source of the transient slowdown was not isolated, so it is not claimed as a proven readback-cost regression or fix. The final table uses the whole no-dump tour, without selecting individual best samples.

For historical context, the original Metal captures versus the older secondary release had SSIM 0.7922 noon, 0.8167 water, 0.8448 forest, 0.7949 sunset, 0.9941 night, 0.9696 cave, and 0.8760 underwater. Those runs predate camera/animation locking and the CI-reference correction, so their score differences cannot be attributed solely to the shadow fix. The more controlled water investigation progressed from shader-clock-only water/underwater 0.9833/0.9489 to atlas-synchronized 0.9888/0.9954; final no-dump captures are in the table above.

## Installed artifacts

The exact tested artifacts were copied into `MyCustommods/mods/` and SHA-256 verified against the final run manifest. Previous installed jars were preserved under `/tmp/iris-research/task3-installed-backup/`. The separate harness mod was not installed into the user's instance.

- `iris-fabric-1.11.6-snapshot+mc26.3-local.jar`: `98c91f01d2a2ce825cfd65a617961dd1954647a6646f30f2f127abe822357c40`
- `mcopt-0.2.0-alpha.2.jar`: `60f83e467aa74fd8ef97ef82934fd87c669acbbcb84f8a3e434d380b95e9726b`

All harness games exited successfully after the final checks. No source-instance options or world files were changed.
