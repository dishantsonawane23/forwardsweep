"""
Soundtrack for the ad: synthesised music + sound effects (so there is nothing to license) and the
voiceover clips from voiceover.py (Kokoro), mixed with ffmpeg.

    python audio.py <voiceover dir> <out.wav>
"""
import os, subprocess, sys, wave
import numpy as np

VO_DIR, OUT = sys.argv[1], sys.argv[2]
SR, DURATION = 44100, 38.5
T = [0.0, 4.8, 10.2, 15.0, 20.9, 26.7, 32.4]          # scene starts (same as make_ad.py)
VO_AT = {"01": 0.3, "02": 5.0, "03": 10.4, "04": 15.2, "05": 21.1, "06": 26.9, "07": 32.6}
rng = np.random.default_rng(7)
n = int(SR * DURATION)
t = np.arange(n) / SR

def note(midi): return 440.0 * 2 ** ((midi - 69) / 12)

# ── music: 100 BPM, D major, I–V–vi–IV, one chord per bar ───────────────────────────────
BPM = 100; beat = 60 / BPM; bar = 4 * beat
CHORDS = [[62, 66, 69], [69, 73, 76], [71, 74, 78], [67, 71, 74]]   # D, A, Bm, G
music = np.zeros(n)

def env(length, a=0.01, r=0.3):
    e = np.ones(length); ai = int(a * SR); ri = min(int(r * SR), length)
    e[:ai] = np.linspace(0, 1, ai); e[-ri:] *= np.linspace(1, 0, ri); return e

def add(buf, start, sig):
    i = int(start * SR)
    if i >= len(buf): return
    j = min(len(buf), i + len(sig)); buf[i:j] += sig[: j - i]

def pluck(freq, length=0.9, decay=0.996):
    """Karplus–Strong string: a warm, sitar/guitar-ish pluck."""
    period = max(2, int(SR / freq)); buf = rng.uniform(-1, 1, period); out = np.empty(int(length * SR))
    for k in range(len(out)):
        out[k] = buf[k % period]; buf[k % period] = decay * 0.5 * (buf[k % period] + buf[(k + 1) % period])
    return out * env(len(out), 0.002, 0.25)

for b in range(int(DURATION / bar) + 1):
    chord = CHORDS[b % 4]; start = b * bar
    # Pad: soft detuned saws through a crude low-pass (moving average).
    length = int(bar * SR)
    tt = np.arange(length) / SR
    pad = sum(((tt * note(m - 12) * d) % 1.0 - 0.5) for m in chord for d in (0.997, 1.003))
    pad = np.convolve(pad, np.ones(40) / 40, mode="same") * env(length, 0.3, 0.4) * 0.05
    add(music, start, pad)
    # Arpeggio of plucks on eighth notes, up and back.
    arp = chord + [chord[0] + 12] + chord[::-1][1:]
    for k in range(8):
        add(music, start + k * beat / 2, pluck(note(arp[k % len(arp)] + 12), 0.6) * 0.11)
    # Bass on beats 1 and 3.
    for k in (0, 2):
        bl = int(beat * 1.6 * SR); tb = np.arange(bl) / SR
        add(music, start + k * beat, np.sin(2 * np.pi * note(chord[0] - 24) * tb) * env(bl, 0.01, 0.4) * 0.16)
    # Kick on every beat after the hook builds up; shaker on off-beats.
    for k in range(4):
        if start + k * beat < T[1]: continue
        kl = int(0.25 * SR); tk = np.arange(kl) / SR
        kick = np.sin(2 * np.pi * (50 + 90 * np.exp(-tk * 30)) * tk) * np.exp(-tk * 14) * 0.35
        add(music, start + k * beat, kick)
        sh = rng.normal(0, 1, int(0.05 * SR)) * np.exp(-np.arange(int(0.05 * SR)) / SR * 70) * 0.03
        add(music, start + k * beat + beat / 2, np.diff(sh, prepend=0))
# Fade music in and out.
music *= np.clip(t / 1.0, 0, 1) * np.clip((DURATION - t) / 0.8, 0, 1)   # 0.8 s fade-out

# ── sound effects ─────────────────────────────────────────────────────────────────────────
sfx = np.zeros(n)
def whoosh(length=0.45):
    l = int(length * SR); x = rng.normal(0, 1, l); tt = np.arange(l) / l
    width = (2 + 60 * tt).astype(int)                     # rising "filter" by shrinking smoothing
    y = np.array([x[max(0, i - w):i + 1].mean() for i, w in enumerate(width[::-1])])
    return y * np.sin(np.pi * tt) ** 2 * 0.5
def pop(freq=900, length=0.08):
    l = int(length * SR); tt = np.arange(l) / SR
    return np.sin(2 * np.pi * freq * tt * (1 - tt * 4)) * np.exp(-tt * 60) * 0.25
def chime(freqs=(1318.5, 1760.0, 2637.0), length=1.2):
    l = int(length * SR); tt = np.arange(l) / SR
    return sum(np.sin(2 * np.pi * f * tt) * np.exp(-tt * (3 + i)) for i, f in enumerate(freqs)) * 0.12

for s in T[2:]: add(sfx, s - 0.2, whoosh())
for i in range(12): add(sfx, 0.15 + i * 0.16 + 0.5, pop(700 + 40 * (i % 5)) * 0.6)  # first greetings landing
add(sfx, T[5] + 0.8, chime())                                    # "moved to trash"
add(sfx, T[6] + 0.3, chime((1760.0, 2217.5, 2637.0, 3520.0), 1.8) * 0.8)  # end card shimmer

def write(path, x):
    x = np.clip(x, -1, 1); st = np.stack([x, x], axis=1)
    with wave.open(path, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes((st * 32767).astype(np.int16).tobytes())

tmp = os.path.dirname(os.path.abspath(OUT))
write(os.path.join(tmp, "_music.wav"), music); write(os.path.join(tmp, "_sfx.wav"), sfx)

# ── mix: voiceover placed on cue, music ducked under it, loudness-normalised ───────────────
inputs, delays = [], []
for k, at in VO_AT.items():
    inputs += ["-i", os.path.join(VO_DIR, f"vo_{k}.wav")]
    delays.append(f"[{len(delays) + 2}:a]aresample={SR},pan=stereo|c0=c0|c1=c0,adelay={int(at * 1000)}|{int(at * 1000)}[v{k}]")
vo_mix = "".join(f"[v{k}]" for k in VO_AT) + f"amix=inputs={len(VO_AT)}:normalize=0,volume=1.6[vo]"
graph = ";".join(delays) + ";" + vo_mix + ";[vo]asplit=2[vo1][vo2];" \
        "[0:a][vo1]sidechaincompress=threshold=0.03:ratio=6:attack=20:release=300[ducked];" \
        "[ducked][1:a][vo2]amix=inputs=3:normalize=0,loudnorm=I=-14:TP=-1.5:LRA=11[out]"
subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", os.path.join(tmp, "_music.wav"), "-i", os.path.join(tmp, "_sfx.wav"), *inputs,
                "-filter_complex", graph, "-map", "[out]", "-ar", str(SR), "-t", str(DURATION), OUT], check=True)
print("wrote", OUT)
