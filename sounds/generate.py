"""Synthesises MineSkate 3's skate sound effects into the mod's assets.

Original sounds, made from filtered noise and decaying tones; no recordings
or game audio are used. Rerun after changing a recipe:

    python sounds/generate.py

Needs numpy, scipy and soundfile (with Ogg Vorbis support).
"""
from pathlib import Path

import numpy as np
import soundfile as sf
from scipy import signal

SR = 44100
OUT = Path(__file__).resolve().parents[1] / 'src/main/resources/assets/mineskate3/sounds'
rng = np.random.default_rng(3)


def t(seconds):
    return np.arange(int(seconds * SR)) / SR


def noise(seconds):
    return rng.standard_normal(int(seconds * SR))


def band(x, lo, hi, order=4):
    sos = signal.butter(order, [lo, hi], btype='bandpass', fs=SR, output='sos')
    return signal.sosfilt(sos, x)


def low(x, hz, order=4):
    return signal.sosfilt(signal.butter(order, hz, btype='lowpass', fs=SR, output='sos'), x)


def high(x, hz, order=4):
    return signal.sosfilt(signal.butter(order, hz, btype='highpass', fs=SR, output='sos'), x)


def decay(seconds, tau):
    return np.exp(-t(seconds) / tau)


def tone(freq, seconds, tau, phase=0.0):
    # Rendered to a common length so tones of different decays can be summed;
    # `at` trims to the sound's own length.
    seconds = max(seconds, 1.2)
    return np.sin(2 * np.pi * freq * t(seconds) + phase) * decay(seconds, tau)


def pad(x, seconds):
    out = np.zeros(int(seconds * SR))
    out[:min(len(x), len(out))] += x[:len(out)]
    return out


def at(base, x, start):
    i = int(start * SR)
    end = min(len(base), i + len(x))
    base[i:end] += x[:end - i]
    return base


def loop(make, seconds, fade=0.25):
    """A seamless loop: render long, crossfade the tail into the head."""
    x = make(seconds + fade)
    n, f = int(seconds * SR), int(fade * SR)
    ramp = np.linspace(0, 1, f)
    head = x[:f] * ramp + x[n:n + f] * (1 - ramp)
    return np.concatenate([head, x[f:n]])


def save(name, x, peak=0.7):
    x = x / (np.max(np.abs(x)) + 1e-9) * peak
    OUT.mkdir(parents=True, exist_ok=True)
    sf.write(OUT / f'{name}.ogg', x.astype(np.float32), SR, format='OGG', subtype='VORBIS')
    print(f'{name}.ogg {len(x) / SR:.2f}s')


def roll(seconds):
    # Urethane on concrete: a dull rumble with a soft hiss and seam ticks.
    rumble = low(np.cumsum(noise(seconds)) * 0.02, 180)
    rumble = high(rumble, 35)
    hiss = band(noise(seconds), 400, 1800) * 0.25
    ticks = np.zeros(int(seconds * SR))
    for start in np.arange(0.05, seconds, 0.21):
        at(ticks, band(noise(0.02), 150, 900) * decay(0.02, 0.004), start + rng.uniform(-0.03, 0.03))
    wobble = 1 + 0.15 * np.sin(2 * np.pi * 3.1 * t(seconds))
    return (rumble * 1.2 + hiss + ticks * 0.6) * wobble


def grind(seconds):
    # Steel trucks on an edge: bright, gritty, with metallic resonances.
    x = band(noise(seconds), 1500, 7000)
    for f in (1830, 3120, 4680):
        b, a = signal.iirpeak(f, 25, fs=SR)
        x += signal.lfilter(b, a, noise(seconds)) * 0.6
    grit = 0.6 + 0.4 * np.abs(low(noise(seconds), 35))
    crackle = (rng.random(int(seconds * SR)) > 0.9985) * rng.standard_normal(int(seconds * SR)) * 6
    return x * grit + high(crackle, 2000)


def slide(seconds):
    # Wood and wax on concrete: broad, softer scrape.
    x = band(noise(seconds), 250, 2600)
    rough = 0.55 + 0.45 * np.abs(low(noise(seconds), 18))
    return x * rough + band(noise(seconds), 2600, 6000) * 0.15


def pop():
    # The tail strikes the ground: a sharp crack, a woody knock, a low thump.
    x = np.zeros(int(0.35 * SR))
    at(x, high(noise(0.01), 2500) * decay(0.01, 0.0015) * 2.5, 0)
    at(x, tone(720, 0.12, 0.025) + 0.6 * tone(1460, 0.08, 0.015) + 0.3 * tone(2310, 0.06, 0.01), 0.001)
    at(x, tone(115, 0.15, 0.04) * 0.9, 0.002)
    at(x, band(noise(0.18), 300, 3000) * decay(0.18, 0.05) * 0.25, 0.01)
    return x


def land(hard=False):
    # Four wheels and the deck meet the ground: thud, deck knock, rattle.
    x = np.zeros(int(0.6 * SR))
    at(x, low(noise(0.2), 220) * decay(0.2, 0.05) * (3.0 if hard else 2.0), 0)
    at(x, tone(68, 0.3, 0.07) * (1.6 if hard else 1.0), 0)
    at(x, tone(520, 0.1, 0.02) * 0.6 + tone(1040, 0.06, 0.012) * 0.3, 0.004)
    at(x, band(noise(0.25), 800, 5000) * decay(0.25, 0.06) * 0.35, 0.01)
    if hard:
        # Trucks bottoming out.
        at(x, (tone(2310, 0.3, 0.06) + 0.5 * tone(3570, 0.25, 0.04)) * 0.35, 0.006)
    return x


def lock():
    # Trucks catching an edge: a short metallic clank.
    x = np.zeros(int(0.4 * SR))
    at(x, high(noise(0.006), 3000) * decay(0.006, 0.001) * 2, 0)
    for f, a, tau in ((1130, 0.8, 0.08), (2710, 0.6, 0.06), (4330, 0.4, 0.04), (6020, 0.2, 0.03)):
        at(x, tone(f, 0.4, tau) * a, 0.0005)
    at(x, tone(140, 0.12, 0.03) * 0.6, 0)
    return x


def bail():
    # A body hitting the ground, then the board clattering away.
    x = np.zeros(int(1.4 * SR))
    at(x, low(noise(0.3), 300) * decay(0.3, 0.07) * 2.2, 0)
    at(x, tone(55, 0.4, 0.09) * 1.4, 0)
    for start, gain in ((0.12, 1.0), (0.34, 0.7), (0.5, 0.45), (0.63, 0.3)):
        at(x, (tone(610, 0.12, 0.02) + 0.5 * tone(1330, 0.08, 0.012)) * gain, start)
        at(x, band(noise(0.08), 1000, 5000) * decay(0.08, 0.02) * gain * 0.5, start)
    scrape = band(noise(0.6), 300, 2500) * np.linspace(1, 0, int(0.6 * SR)) * 0.25
    at(x, scrape, 0.7)
    return x


def chime(freqs, step=0.09, tau=0.18):
    x = np.zeros(int((step * len(freqs) + 0.6) * SR))
    for i, f in enumerate(freqs):
        at(x, (tone(f, 0.6, tau) + 0.3 * tone(f * 2, 0.6, tau * 0.6)) * (1 - 0.15 * i), i * step)
    return x


def whoosh():
    seconds = 0.7
    x = noise(seconds)
    sweep = np.linspace(300, 4000, len(x))
    out = np.zeros_like(x)
    # Stepped band-pass sweep, crossfaded in short blocks.
    block = 1024
    for i in range(0, len(x), block):
        f = sweep[i]
        out[i:i + block] = band(x[i:i + block + 2048], f * 0.7, min(f * 1.4, 20000), 2)[:len(out[i:i + block])]
    env = np.sin(np.linspace(0, np.pi, len(x))) ** 2
    return out * env


if __name__ == '__main__':
    save('roll', loop(roll, 2.0), 0.6)
    save('grind', loop(grind, 1.5), 0.6)
    save('slide', loop(slide, 1.5), 0.6)
    save('pop', pop())
    save('land', land())
    save('land_hard', land(hard=True))
    save('lock', lock())
    save('bail', bail())
    save('marker_place', chime([880, 1320]), 0.5)
    save('marker_return', whoosh() * 0.8 + pad(chime([1320, 880], 0.12), 0.7), 0.5)
