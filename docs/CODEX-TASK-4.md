# Codex task 4: optimize the Metal renderer (mcopt) and the Iris-on-Metal path

Start only after task 3's current fixes are verified (side-by-side images match the GL reference, CI build 5990).
Same repos, branches, harness and rules as `docs/CODEX-TASK-3.md` (muted games, one game at a time,
`./gradlew --stop` after builds, local commits only, no push, never commit `launch-command.txt` or run outputs).

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
