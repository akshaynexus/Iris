# Codex task 5: Iris on upstream mcopt 0.3 (no Sodium) — experimental branches

Start only after tasks 3 and 4 are finished. Same rules as `docs/CODEX-TASK-3.md` (muted games, one game at a time,
`./gradlew --stop` after builds, local commits only, no push, never commit `launch-command.txt` or run outputs).

## Branches and folders

- mcopt, saved working state: branch `iris-metal-sodium` (= `iris-metal` at 714128a; Sodium-based Iris port). Don't
  change it. Task 3/4 commits that land on `iris-metal` later should also be merged into the exp branch.
- mcopt experiment: worktree `~/Documents/GitRepos/mcopt-exp`, branch `iris-metal-exp`. A merge of `upstream/main`
  (noahdunnagan/mcopt, 0.3.0-alpha.2) is **in progress** there with 6 conflicts: `NOTICE`, `README.md`,
  `metal/build.gradle`, `tools/release`, `metal/src/main/java/mcopt/metal/MetalBridge.java`,
  `metal/src/main/java/mcopt/metal/Profile.java`.
- Iris: create a matching experiment branch `mcopt-metal-exp` from `mcopt-metal` in a worktree
  `~/Documents/GitRepos/iris-mcopt-exp`. Point its `compileOnly` mcopt jar at the exp build
  (`../mcopt-exp/metal/build/libs/...`).

## What upstream changed

mcopt 0.3 draws the world with its own Metal renderer and no longer uses Sodium; it refuses to start next to Sodium.
Its README says mods that require Sodium (Iris) can't be used. Our Iris fork depends on Sodium for terrain (Sodium
compat mixins, `SODIUM_TERRAIN_*` shader keys, Iris's Sodium vertex format via `WorldRenderingSettings`).
Distant Horizons now also draws on Metal in mcopt.

## Goal

1. Resolve the merge: keep upstream's renderer and features, keep our Iris-port API (`PackGlsl`, `PackCompiler`,
   `PackProgram`, `PackUniforms`, `MetalShaderTranslation`, MetalBridge additions, redirect/delegate fixes, texture
   readback). Build with `tools/release` and commit the merge on `iris-metal-exp`.
2. Make Iris + Complementary work on mcopt 0.3 without Sodium, on the Iris exp branch: make Iris load without Sodium
   when mcopt is present (dependency, mixins, config screens that use Sodium's options API), and feed Iris's terrain
   gbuffer/shadow programs from mcopt's own terrain renderer: its vertex format, draw path and passes. Design the hook
   in mcopt (a terrain equivalent of `PassDelegate`, or exposing the terrain pass to the redirect) so mcopt keeps its
   speed when no pack is active. Material ids (`mc_Entity`, `mc_midTexCoord`, `at_tangent`, `at_midBlock`) must reach
   the pack: extend mcopt's terrain mesh format when a pack is active.
3. Extend the harness with a third backend config `metal-exp` (mods: mcopt exp jar + Iris exp jar, no Sodium) and
   compare against the GL reference (CI build 5990) the same way: side-by-side images must match, fps reported.

## Report

`docs/CODEX-REPORT-5.md` (on the Iris exp branch): merge decisions, the terrain hook design, what works, scene
scores and fps vs GL and vs the Sodium-based Metal port, and what is left.
