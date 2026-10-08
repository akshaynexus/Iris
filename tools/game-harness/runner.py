#!/usr/bin/env python3
"""Isolated, serial real-game runner. Never executes launch text through a shell."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import signal
import subprocess
import threading
import time
from ctl import command

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
INSTANCES = Path.home() / 'Documents/curseforge/minecraft/Instances'
SOURCE = INSTANCES / 'MyCustommods'

def copy_one(pattern, dest):
    files = list(SOURCE.glob(pattern))
    if len(files) != 1:
        raise RuntimeError(f'Expected one {pattern}, found {len(files)}')
    shutil.copy2(files[0], dest)

def prepare(backend, run):
    game = INSTANCES / f'IrisHarness-{backend}'
    game.mkdir(exist_ok=True)
    # This directory is owned by the harness; source instance is read-only here.
    for folder in ('mods', 'config', 'shaderpacks'):
        (game / folder).mkdir(exist_ok=True)
    for jar in (game / 'mods').glob('*.jar'):
        jar.unlink()
    copy_one('mods/fabric-api-*.jar', game / 'mods')
    copy_one('mods/sodium-*.jar', game / 'mods')
    if backend == 'gl':
        shutil.copy2(Path('/tmp/iris-research/iris-ci-latest-26.3.jar'), game / 'mods')
    else:
        shutil.copy2(REPO.parent / 'mcopt/dist/mcopt-0.2.0-alpha.2.jar', game / 'mods')
        shutil.copy2(REPO / 'build/libs/iris-fabric-1.11.6-snapshot+mc26.3-local.jar', game / 'mods')
    shutil.copy2(HERE / 'driver/build/libs/iris-game-driver-1.0.0.jar', game / 'mods')
    copy_one('shaderpacks/ComplementaryReimagined_r5.9.3.zip', game / 'shaderpacks')
    # Do not copy pack .txt overrides: both use the pack's default profile.
    for p in (game / 'shaderpacks').glob('*.txt'):
        p.unlink()
    (game / 'config/iris.properties').write_text('shaderPack=ComplementaryReimagined_r5.9.3.zip\nenableShaders=true\nenableDebugOptions=false\nmaxShadowRenderDistance=8\n')
    (game / 'config/mcopt.properties').write_text(f'mcopt.metal={str(backend == "metal").lower()}\n')
    opts = dict(line.split(':', 1) for line in (SOURCE / 'options.txt').read_text().splitlines() if ':' in line)
    opts.update(renderDistance='8', simulationDistance='5', guiScale='2', fov='0.0', fullscreen='false', enableVsync='false', maxFps='260', pauseOnLostFocus='false', preferredGraphicsBackend='"opengl"' if backend == 'gl' else '"default"', onboardingAccessibilityFinished='true', joinedFirstServer='true',
                soundCategory_master='0.0')  # muted: harness runs must not play over the user's music
    (game / 'options.txt').write_text(''.join(f'{k}:{v}\n' for k, v in opts.items()))
    template = HERE / 'runs/world-template'
    if not template.exists():
        template.parent.mkdir(exist_ok=True)
        shutil.copytree(SOURCE / 'saves/New World', template, ignore=shutil.ignore_patterns('session.lock'))
    world = game / 'saves/Harness'
    if world.exists():
        shutil.rmtree(world)
    shutil.copytree(template, world)
    original = shlex.split((HERE / 'launch-command.txt').read_text())
    result = []
    replacements = {'--gameDir': str(game), '--width': '1280', '--height': '720', '--username': 'Harness', '--uuid': '00000000000000000000000000000001', '--accessToken': '0', '--clientId': '0', '--xuid': '0'}
    i = 0
    while i < len(original):
        item = original[i]
        if item == 'net.minecraft.client.main.Main' or item.startswith(('-DFabricMcEmu=', '-Dlog4j.configurationFile=')):
            i += 1
            continue
        if item.startswith('--quickPlay'):
            i += 2
            continue
        if item in replacements:
            result.extend([item, replacements[item]])
            i += 2
            continue
        if item.startswith(('-Xmx', '-Xms', '-Dmcopt.metal=')):
            i += 1
            continue
        result.append(item)
        i += 1
    result[1:1] = ['-Xms512m', '-Xmx4G', '-Dharness.enabled=true', '-Dharness.animationTime=60.0', '-Dharness.freezeTextures=true', f'-Dharness.output={run}', '-Dharness.port=47821', f'-Dmcopt.metal={str(backend == "metal").lower()}']
    manifest = {'backend': backend, 'shader_animation_time': 60.0, 'camera_lock': True, 'texture_animation_frozen': True, 'game_dir': str(game), 'mods': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in (game / 'mods').glob('*.jar')}, 'scenes': json.loads((HERE / 'scenes.json').read_text())}
    (run / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    return game, result

def checked(text):
    result = command(text)
    if not result.get('ok'):
        raise RuntimeError(f'{text}: {result}')
    return result

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('backend', choices=['gl', 'metal'])
    parser.add_argument('--live', action='store_true')
    parser.add_argument('--dump', action='store_true')
    parser.add_argument('--run', type=Path)
    args = parser.parse_args()
    HERE.joinpath('runs').mkdir(exist_ok=True)
    with (HERE / 'runs/game.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        # Catch games started outside this runner, too. Never kill an unrelated process.
        processes = subprocess.run(['pgrep', '-fl', 'net.fabricmc.loader.impl.launch.knot.KnotClient|net.minecraft.client.main.Main'], capture_output=True, text=True)
        if processes.returncode == 0:
            raise RuntimeError('Another Minecraft process exists; refusing concurrent launch')
        if processes.returncode not in (0, 1):
            raise RuntimeError('Cannot verify game process inventory: ' + processes.stderr)
        run = (args.run or HERE / 'runs' / time.strftime('%Y%m%d-%H%M%S') / args.backend).resolve()
        run.mkdir(parents=True, exist_ok=True)
        if (run / 'manifest.json').exists(): raise RuntimeError('Run directory already used; choose a fresh path')
        game, argv = prepare(args.backend, run)
        print(f'Launching {args.backend}: {run}', flush=True)
        status = {'backend': args.backend, 'ok': False}
        with (run / 'console.log').open('w') as log:
            proc = subprocess.Popen(argv, cwd=game, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            stop = threading.Event()
            def watchdog():
                last_progress = time.monotonic()
                last_frame = -1
                while not stop.wait(2):
                    try:
                        frame = json.loads((run / 'heartbeat.json').read_text())['frames']
                        if frame != last_frame:
                            last_frame = frame
                            last_progress = time.monotonic()
                    except (OSError, ValueError, KeyError):
                        pass
                    if time.monotonic() - last_progress > 120:
                        status['timeout'] = 'No frame progress for 120 seconds'
                        os.killpg(proc.pid, signal.SIGTERM)
                        try: proc.wait(timeout=10)
                        except subprocess.TimeoutExpired: os.killpg(proc.pid, signal.SIGKILL)
                        return
            thread = threading.Thread(target=watchdog, daemon=True)
            thread.start()
            try:
                deadline = time.monotonic() + 120
                while time.monotonic() < deadline:
                    if proc.poll() is not None:
                        raise RuntimeError(f'Game exited with {proc.returncode}; see console.log')
                    try:
                        if command('stats').get('world'): break
                    except (OSError, ValueError): pass
                    time.sleep(1)
                else: raise RuntimeError('World did not load within 120 seconds')
                checked('setup'); checked('hud off')
                if args.live:
                    print('Live control ready on 127.0.0.1:47821', flush=True)
                    proc.wait()
                else:
                    results = {}
                    for scene in json.loads((HERE / 'scenes.json').read_text()):
                        checked('tp ' + ' '.join(map(str, scene['position'] + [scene['yaw'], scene['pitch']])))
                        checked(f"time {scene['time']}"); checked(f"weather {scene['weather']}")
                        checked(f"wait frames {scene['warmup_frames']}")
                        checked('resetstats'); checked(f"wait frames {scene['sample_frames']}")
                        stats = checked(f"stats {scene['sample_frames']}")
                        if stats['throttle'] != 'NONE': raise RuntimeError('Frame limiter active: ' + stats['throttle'])
                        capture = checked('screenshot ' + scene['name'])
                        pose = checked('position')
                        if abs(pose['yaw'] - scene['yaw']) > .01 or abs(pose['pitch'] - scene['pitch']) > .01:
                            raise RuntimeError('Camera pose changed during capture: ' + str(pose))
                        results[scene['name']] = {'stats': stats, 'screenshot': capture, 'pose': pose}
                        if args.dump and args.backend == 'metal':
                            results[scene['name']]['dump'] = checked('dump ' + scene['name'])
                            deadline = time.monotonic() + 120
                            while not checked('dumpstatus')['complete']:
                                if time.monotonic() > deadline: raise RuntimeError('Dump did not finish within 120s')
                                time.sleep(.5)
                        (run / 'results.json').write_text(json.dumps(results, indent=2))
                        print(scene['name'], stats, flush=True)
                    checked('quit'); proc.wait(timeout=30)
                status['ok'] = proc.returncode == 0
            except BaseException as e:
                status['error'] = str(e)
                raise
            finally:
                stop.set()
                if proc.poll() is None:
                    os.killpg(proc.pid, signal.SIGTERM)
                    try: proc.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        os.killpg(proc.pid, signal.SIGKILL); proc.wait()
                status['exit_code'] = proc.returncode
                (run / 'status.json').write_text(json.dumps(status, indent=2))
                if (game / 'logs/latest.log').exists(): shutil.copy2(game / 'logs/latest.log', run / 'latest.log')

if __name__ == '__main__': main()
