# Real game harness

Run from the Iris repository. Requires the private, ignored `launch-command.txt`, the user's existing
`MyCustommods` instance, official Iris CI build at `/tmp/iris-research/iris-ci-latest-26.3.jar` (build 5990), local fork and mcopt jars, Java 26 for builds, and the
launcher-provided Java runtime for the game. No launcher login or CurseForge UI is required.
The runner corrects the captured command's duplicate vanilla/Fabric entry point, removes quick play,
replaces credentials with offline values, and starts Fabric directly. It never invokes a shell on launch text.

Build sequentially, with no game running:

```sh
export JAVA_HOME=/opt/homebrew/Cellar/openjdk/26.0.2.1/libexec/openjdk.jdk/Contents/Home
./gradlew -p tools/game-harness/driver build --console=plain -q
./gradlew --stop
(cd ../mcopt && tools/release; ./gradlew --stop)
./gradlew :fabric:build -x test --console=plain -q
./gradlew --stop
```

The driver compiles to Java 25 bytecode because the captured game runtime is Java 25. It has its own
Loom 1.17.20 / Minecraft 26.3 build and depends only on Fabric and Minecraft. Metal dump hooks use reflection.
The driver is installed only into the harness directories, never the user's normal instance.

## Tours

```sh
tools/game-harness/run.sh gl --run tools/game-harness/runs/ci-5990-deterministic/gl
tools/game-harness/run.sh metal --dump --run tools/game-harness/runs/iteration-1/metal
UV_CACHE_DIR=/tmp/iris-research/uv-cache uv run tools/game-harness/compare.py \
  tools/game-harness/runs/ci-5990-deterministic/gl tools/game-harness/runs/iteration-1/metal \
  --out tools/game-harness/runs/iteration-1/comparison
```

Run paths must be fresh. Omit `--run` to use a timestamp. `run.sh` stops Gradle daemons in both repos.
Only one runner may hold the process lock. It also refuses to launch if any outside Minecraft process
exists, or if process inventory is unavailable. macOS GPU/window/process access may require running
outside the tool sandbox. A watchdog kills only its own process group if the frame heartbeat stops for
120 seconds. Every run saves `status.json`, `console.log`, `latest.log`, and a mod hash manifest.

`IrisHarness-gl` and `IrisHarness-metal` are disposable harness-owned directories next to `MyCustommods`.
The first run copies `MyCustommods/saves/New World` into ignored `runs/world-template`; each later run copies
that immutable template afresh. Its seed is -5068762119017331336. Neither the template nor pack is committed.
Delete only `runs/world-template` to choose a new template; existing viewpoints depend on its seed.

Both configurations use Complementary Reimagined r5.9.3's default HIGH profile, no option overrides,
8 chunk render distance, 5 simulation distance, FOV 70, GUI scale 2, VSync off, and hidden HUD.
The driver sets a 640×360 logical window on this Retina Mac for 1280×720 native captures. Use `position`
to verify framebuffer dimensions if moving to another display; comparisons reject unequal dimensions.

`scenes.json` contains the seven verified viewpoints: elevated coast at noon, coastal water reflections,
forest shade, sunset, night sky, an open underground chamber, and underwater. Each teleports, sets world
clock/weather, warms for 240 rendered frames, then measures 300 frames. A screenshot is acknowledged only
after Minecraft's screenshot API finishes writing. Frame statistics are CPU frame intervals, including
pacing and GPU waits; they are not GPU-only timestamps. Buffer dumping occurs after timing and capture.
The driver refreshes Minecraft’s input timestamp to prevent the 60-second AFK FPS cap, and each timing result records the throttle reason. The runner pins the Iris animation-time uniform to 60 seconds in both backends so waves and foliage have the same phase; Minecraft texture animation ticks are also frozen at the initial atlas frame so water caustics match. These controls apply only to the harness; real frame time and the frame counter remain live. The camera is held at the last `tp` pose and mouse input cannot move it. Existing entities and world-age-driven effects can still vary; scores are not masked or aligned.

## Live exploration

```sh
tools/game-harness/run.sh metal --live
python3 tools/game-harness/ctl.py tp -1000 64 260 90 10
python3 tools/game-harness/ctl.py time 6000
python3 tools/game-harness/ctl.py weather clear
python3 tools/game-harness/ctl.py wait frames 240
python3 tools/game-harness/ctl.py screenshot water
python3 tools/game-harness/ctl.py dump water
python3 tools/game-harness/ctl.py dumpstatus
python3 tools/game-harness/ctl.py position
python3 tools/game-harness/ctl.py resetstats
python3 tools/game-harness/ctl.py stats 300
python3 tools/game-harness/ctl.py hud on
python3 tools/game-harness/ctl.py quit
```

The socket listens only on `127.0.0.1:47821`. Commands and replies are newline-delimited; replies are JSON.
Names permit only letters, digits, `_`, `-`. `weather rain` and `hud off` are also supported.
Commands execute on the Minecraft client/server threads. `wait frames N` waits for actual render frames.
Do not manually move the camera during a tour. Read resulting PNGs directly with an image viewer/tool.

Metal dumps record main/alt colortex0–15, suffixing the texture read by the next stage with `-read`, main
depth/depthtex1/2, shadowtex0/1 and shadowcolor0/1. `after-gbuffers-opaque` is immediately before deferred;
`after-deferred` follows deferred; `after-composite` follows composite including translucent gbuffers.
The three stages are captured on successive frames to bound native staging memory. Readbacks complete
on the backend's normal GPU completion callback, then PNGs encode on a single worker. `dumpstatus`
reports completion; the tour waits before continuing. Dump work is excluded from measured samples.
Each PNG has a `.txt` companion with Metal format, min/max, nonfinite count, and normalization. LDR values
are preserved; depth/out-of-range floats use global min/max normalization. Alpha is excluded from the
RGB visualization. Packed RGB10A2, R11G11B10F and RGB9E5 formats are decoded before visualization. Data has native orientation.

`compare.py` uses uv-managed Pillow, NumPy and scikit-image. It computes RGB SSIM with data range 255 and
normalized mean absolute difference, requires all seven scenes, and produces full-resolution side-by-side
images, absolute differences amplified four times, `scores.json`, `report.md`, and `report.html`.

For additional upstream reference diagnostics, `ctl.py gldump NAME` saves the GL shadow depth at the current viewpoint (GL only). `ctl.py glstages NAME` queues color/depth snapshots before deferred, after deferred, and after composite on the official GL pipeline; wait for a few frames before reading them. Native dump rows follow the backend texture convention, so these diagnostic images appear upside down relative to screenshots.
