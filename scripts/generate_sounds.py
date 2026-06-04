#!/usr/bin/env python3
"""Generates the recording start/stop cue sounds in app/src/main/res/raw/.

Design: a single soft sine "dot" per cue — raised-cosine attack so there is no
click, exponential decay so it feels organic, and a quiet second harmonic for
warmth. Start cue is a higher pitch (E5) than the stop cue (A4), the universal
"begin/end" convention. 44.1 kHz / 16-bit / mono WAV.

Run from the repo root:  python3 scripts/generate_sounds.py
"""

import math
import struct
import wave
from pathlib import Path

SAMPLE_RATE = 44_100
OUT_DIR = Path(__file__).resolve().parent.parent / "app/src/main/res/raw"


def synth_dot(freq_hz: float, duration_s: float, decay_tau_s: float, peak: float) -> bytes:
    attack_s = 0.006
    n_samples = int(SAMPLE_RATE * duration_s)
    frames = bytearray()
    for i in range(n_samples):
        t = i / SAMPLE_RATE
        # Raised-cosine attack, exponential decay.
        if t < attack_s:
            envelope = 0.5 - 0.5 * math.cos(math.pi * t / attack_s)
        else:
            envelope = math.exp(-(t - attack_s) / decay_tau_s)
        # Fundamental plus a quiet octave harmonic for warmth.
        sample = math.sin(2 * math.pi * freq_hz * t) + 0.18 * math.sin(2 * math.pi * freq_hz * 2 * t)
        # Final 10 ms linear fade guarantees a click-free ending.
        remaining = duration_s - t
        if remaining < 0.010:
            envelope *= remaining / 0.010
        value = int(max(-1.0, min(1.0, sample * envelope * peak)) * 32767)
        frames += struct.pack("<h", value)
    return bytes(frames)


def write_wav(path: Path, frames: bytes) -> None:
    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(SAMPLE_RATE)
        wav.writeframes(frames)
    print(f"wrote {path} ({path.stat().st_size} bytes)")


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    # Start: E5, bright and quick — "go ahead".
    write_wav(OUT_DIR / "record_start.wav", synth_dot(659.25, 0.18, 0.040, 0.50))
    # Stop: A4, slightly longer tail — "done".
    write_wav(OUT_DIR / "record_stop.wav", synth_dot(440.00, 0.20, 0.050, 0.50))


if __name__ == "__main__":
    main()
