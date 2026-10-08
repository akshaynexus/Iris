# Codex task 4: optimize the Metal renderer (mcopt) and the Iris-on-Metal path

Start only after task 3's current fixes are verified (side-by-side images match the GL reference, CI build 5990).
Same repos, branches, harness and rules as `docs/CODEX-TASK-3.md` (muted games, one game at a time,
`./gradlew --stop` after builds, local commits only, no push, never commit `launch-command.txt` or run outputs).

## Top priority (user report, 2026-10-09)

In the user's own game (CurseForge, MacBook Air M4 10-core GPU, 16 GB, fullscreen on the built-in 2880x1864 display,
renderDistance 16, simulationDistance 12, Iris maxShadowRenderDistance=32, Complementary default profile) Metal gives
**about 25 fps**. Stock OpenGL Iris gave about 30-31 fps there. The user needs **60 fps or more** on Metal at those
settings. The harness so far only measured 1280x720 with render distance 8 and shadow distance 8, so it missed this.

1. Add a harness profile with exactly the user's settings: window or fullscreen at 2880x1864 (match the framebuffer
   size the game really uses fullscreen on that display), renderDistance 16, simulationDistance 12, shadow distance
   32, GUI hidden. Measure GL Iris (CI build 5990) and Metal on the same scenes.
2. Find out precisely why Metal is slow there: CPU (render thread time, draws, per-draw uniform/texture work in the
   redirect/delegate, shadow pass draw count, chunk work) or GPU (per-pass GPU times with mcopt's trace/gpuTimes:
   gbuffers, shadow pass, each deferred/composite pass, final; bandwidth from load/store, copies, mips).
3. Fix the biggest costs first, then the next, until Metal reaches 60+ fps at those settings with images still
   matching GL. If 60 is impossible on this GPU for this pack at native resolution, show the numbers and offer the
   best option that keeps the look (for example rendering the pack at a lower internal resolution and upscaling with
   MetalFX, which mcopt already has in `MetalFx.java`) and measure it.

### Live background bench (user request)

Build a bench mode that stays running and measures continuously, so each change is judged quickly:
- Render at the user's real framebuffer: 2880x1864 pixels. True fullscreen on another Space gets throttled when
  hidden, so use a window of 1440x932 points on the Retina display (= 2880x1864 framebuffer), or fullscreen when the
  measurement shows no throttling. Verify the framebuffer size from the game, don't assume it.
- No throttling may count: set Minecraft's inactivity fps limit off, pauseOnLostFocus false, VSync off, maxFps
  unlimited; record the throttle state per sample (the harness already reports `throttle`) and discard throttled
  samples. Check macOS occlusion/App Nap effects on the window too.
- Bench loop: fixed flight paths and turns through the scenes at the user's settings (render 16, shadow 32), for a
  fixed time; record avg fps, 1% low, p95 frame time, CPU render-thread time and GPU time per pass.
- Realism check on every iteration: the side-by-side images against GL (CI 5990) at the same settings must keep
  shadows, depth effects, water, sky and fog. No visual loss is allowed to buy fps.
- Keep the game muted. Work until Metal holds 60+ fps (1% low reported too) at these settings with no visual loss,
  or document precisely what blocks it and the best visually lossless option.

## Goal

More fps and less heat on the user's MacBook Air M4 (16 GB, fanless, thermal throttling), with **no visual change**:
every scene's side-by-side image must still match the GL reference as well as before, and the scores must not drop.

## Scope: both modes

Optimize Metal rendering **with shaders** (Iris + Complementary on Metal) **and without shaders** (plain mcopt Metal:
shaders disabled in Iris, and also mcopt without the Iris jar). Measure both modes before and after.

- Add a harness mode with `enableShaders=false` (same scenes, same camera poses) so vanilla Metal can be measured and
  its images compared before/after (vanilla images must not change either).
- Report frame times for: Metal with shaders, Metal without shaders, and for reference GL Iris and vanilla GL.

## Method

1. **Measure first.** Use the harness tour: avg and p95 frame time per scene, and GPU time. mcopt has tools:
   `-Dmcopt.metal.gpuTimes=true`, `-Dmcopt.metal.trace=N` (per-encoder GPU times for submit N),
   `-Dmcopt.metal.stats=true` (CPU wait on GPU per frame). Find out whether each scene is GPU-bound or CPU-bound and
   which passes cost the most (gbuffers, shadow pass, each deferred/composite pass, final).
2. **Optimize where it pays**, for example:
   - CPU: per-draw work in `MetalGbuffers` and `PackUniforms` (uniform updates, setBytes size, texture rebinding on
     every pipeline switch, map lookups, allocations), redirect/delegate overhead, pipeline-state cache hits.
   - GPU: render encoder count (merge passes with the same attachments, avoid needless load/store: use
     dont-care/memoryless where Iris semantics allow), redundant clears and copies (depth copies, swap blits),
     mipmap generation only when needed, shadow map work (culling, resolution as the pack asks, no extra passes),
     full-screen passes that could share an encoder.
   - Startup/hitches: compile pack pipelines ahead of first use or cache MSL/pipelines on disk (mcopt has an MSL cache),
     so walking into new scenes doesn't stutter.
   - mcopt's own Metal backend (vanilla paths) where the profile shows cost.
   - Optional: an fps cap / frame pacing option that lowers heat without hurting feel; default off unless the user
     agrees.
3. **Verify every change** with the harness: same images (side-by-side check plus scores), better or equal frame
   times. Revert anything that changes the image.

## Report

`docs/CODEX-REPORT-4.md`: baseline vs final frame times (avg/p95) and GPU times per scene, for both modes (with and
without shaders), what each optimization
saved, what was tried and reverted, commits made, and remaining hot spots.
