#!/usr/bin/env python3
"""
Generate TapRhythm charts from mp3s.

Charts are built OFFLINE, on a desktop, and committed. Onset detection means an
FFT over the whole song; doing that on the glasses would stall for seconds
before every track on a device that is already thermally tight. Committing the
output also means a chart can be hand-corrected without touching the app.

Requires: ffmpeg on PATH, numpy.  Usage:  python3 tools/make_charts.py
"""
import subprocess, numpy as np, os, json, sys

SR, N, HOP = 22050, 1024, 512
FPS = SR / HOP
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DST = os.path.join(HERE, "app/src/main/assets")

# level, source mp3, display name, target notes per second
LEVELS = [
    (1, "Neon Pulse-3.mp3",         "PULSE",      1.5),
    (2, "Bamboo Blip Rodeo.mp3",    "BLIP RODEO", 1.9),
    (3, "Astro Vinyl.mp3",          "ASTRO",      2.3),
    (4, "Neon Pulse-4.mp3",         "OVERDRIVE",  2.7),
    (5, "03 - IO Tower (Cover).mp3","IO TOWER",   3.1),
]

MIN_GAP = 0.16      # seconds; below this a temple pad cannot be played cleanly
GRID_TOL = 0.42     # fraction of a grid step an onset may sit off and still count


def pcm(path):
    r = subprocess.run(["ffmpeg", "-v", "quiet", "-i", path, "-ac", "1",
                        "-ar", str(SR), "-f", "s16le", "-"], capture_output=True)
    if r.returncode or not r.stdout:
        raise RuntimeError(f"ffmpeg failed for {path}")
    return np.frombuffer(r.stdout, dtype=np.int16).astype(np.float32) / 32768.0


def spectrum(x):
    n = 1 + (len(x) - N) // HOP
    w = np.hanning(N).astype(np.float32)
    F = np.lib.stride_tricks.sliding_window_view(x, N)[::HOP][:n] * w
    return np.abs(np.fft.rfft(F, axis=1))


def analyse(path, target_nps):
    x = pcm(path)
    dur = len(x) / SR
    S = spectrum(x)

    # Onset envelope: only ENERGY THAT APPEARS counts. Including decay smears
    # every transient into the one after it.
    env = np.maximum(np.diff(np.log1p(S * 8), axis=0), 0).sum(axis=1)
    env /= env.max() + 1e-9

    # Tempo, by autocorrelating the envelope.
    e = env - env.mean()
    ac = np.correlate(e, e, mode="full")[len(e) - 1:]
    bpm, bv = 0.0, 0.0
    for cand in np.arange(70, 190, 0.25):
        lag = int(round(60 / cand * FPS))
        if 2 <= lag < len(ac) and ac[lag] > bv:
            bv, bpm = ac[lag], cand
    period = 60.0 / bpm

    # PHASE. Without this the grid has the right spacing in the wrong place and
    # every note sits a fraction of a beat off the music.
    off, best = 0.0, -1.0
    for cand in np.arange(0, period, 0.01):
        idx = (np.arange(cand, dur, period) * FPS).astype(int)
        idx = idx[idx < len(env)]
        v = env[idx].sum()
        if v > best:
            best, off = v, cand

    thr = env.mean() + 0.6 * env.std()
    pk = np.where((env[1:-1] > env[:-2]) & (env[1:-1] >= env[2:]) & (env[1:-1] > thr))[0] + 1
    times, strength = pk / FPS, env[pk]

    # Quantise to eighth notes; drop anything that is not near a grid line,
    # which is mostly reverb tails and grace notes no human taps.
    grid = period / 2.0
    keep = {}
    for t, s in zip(times, strength):
        q = round((t - off) / grid) * grid + off
        if abs(t - q) > grid * GRID_TOL:
            continue
        if q not in keep or s > keep[q]:
            keep[q] = s
    notes = sorted(keep.items())

    # Lane from timbre: a kick and a hi-hat feel different and should not ask
    # for the same gesture.
    freqs = np.fft.rfftfreq(N, 1 / SR)
    laned = []
    for t, s in notes:
        f = min(int(t * FPS), len(S) - 1)
        mag = S[f] + 1e-9
        c = float((freqs * mag).sum() / mag.sum())
        laned.append((t, 0 if c < 900 else (1 if c < 2600 else 2), float(s)))

    # Thin to the target density, strongest first, respecting the minimum gap.
    chosen, taken = [], []
    for t, lane, s in sorted(laned, key=lambda r: -r[2]):
        if any(abs(t - u) < MIN_GAP for u in taken):
            continue
        taken.append(t)
        chosen.append((t, lane))
        if len(chosen) >= int(target_nps * dur):
            break
    chosen.sort()
    return dict(bpm=round(float(bpm), 1), offset=round(float(off), 3),
                dur=round(dur, 2), notes=[[round(t, 3), l] for t, l in chosen])


def main(src_dir):
    os.makedirs(f"{DST}/charts", exist_ok=True)
    os.makedirs(f"{DST}/music", exist_ok=True)
    for lvl, src, name, nps in LEVELS:
        p = os.path.join(src_dir, src)
        if not os.path.isfile(p):
            print(f"  !! missing {p}")
            continue
        d = analyse(p, nps)
        d["name"], d["level"] = name, lvl
        json.dump(d, open(f"{DST}/charts/{lvl:02d}.json", "w"))
        subprocess.run(["cp", p, f"{DST}/music/{lvl:02d}.mp3"], check=True)
        print(f"  {lvl} {name:<12} {d['bpm']:6.1f} bpm  {len(d['notes']):4d} notes  "
              f"{len(d['notes'])/d['dur']:.2f}/s")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1
         else os.path.expanduser("~/Projects/x3breakout/app/src/main/assets/music"))
