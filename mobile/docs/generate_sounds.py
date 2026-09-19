"""Generates the app's two sounds into assets/audio/.

    python docs/generate_sounds.py        (run from the mobile/ directory)

Synthesised rather than sourced. Two reasons: there is no licence to account for in a
submission, and the alarm can be tuned by editing numbers instead of hunting for a
different file. Standard library only - no numpy, no dependencies.
"""

import math
import os
import struct
import wave

SR = 44100
OUT_DIR = os.path.join("assets", "audio")


def write_wav(path: str, samples) -> None:
    with wave.open(path, "w") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(
            b"".join(
                struct.pack("<h", int(max(-1.0, min(1.0, s)) * 32000)) for s in samples
            )
        )


def emergency_alarm():
    """A personal-attack-alarm warble, not a gentle chime.

    Three things make it read as "someone needs help" rather than "your timer is done":

    * The pitch sits at 2.2-2.9 kHz, where small phone speakers are loudest and human
      hearing is most sensitive. A low tone would be lost in traffic noise.
    * It warbles about eight times a second. A steady tone is easy to ignore and hard
      to locate; a fast sweep is neither.
    * A third harmonic gives it an edge that cuts through ambient noise.

    The length is chosen so the waveform returns to zero phase at the end, so the loop
    has no click in it.
    """
    warble_hz = 8.0
    low, high = 2200.0, 2900.0
    pulse_hz = 2.0
    duration = 2.0  # 16 warbles, 4 throbs: both divide evenly

    out = []
    phase = 0.0
    for i in range(int(SR * duration)):
        t = i / SR
        sweep = abs(((t * warble_hz) % 1.0) * 2.0 - 1.0)
        freq = low + (high - low) * sweep
        # Integrate frequency into phase so pitch changes without discontinuities.
        phase += 2.0 * math.pi * freq / SR
        tone = 0.82 * math.sin(phase) + 0.18 * math.sin(3.0 * phase)
        throb = 0.72 + 0.28 * (0.5 - 0.5 * math.cos(2.0 * math.pi * pulse_hz * t))
        edge = min(1.0, t / 0.006, (duration - t) / 0.006)
        out.append(tone * throb * edge * 0.85)
    return out


def alert_sent():
    """Two short rising notes: "it went".

    Played only where sound is safe - never on a silent incident. Deliberately nothing
    like the alarm, so somebody hearing them across a room could not confuse the two.
    """
    out = []
    for idx, f in enumerate((880.0, 1320.0)):
        n = int(SR * 0.09)
        for i in range(n):
            t = i / SR
            env = math.sin(math.pi * (i / n)) ** 1.5
            out.append(0.5 * env * math.sin(2.0 * math.pi * f * t))
        if idx == 0:
            out.extend([0.0] * int(SR * 0.04))
    return out


if __name__ == "__main__":
    os.makedirs(OUT_DIR, exist_ok=True)
    for name, builder in (
        ("emergency_alarm.wav", emergency_alarm),
        ("alert_sent.wav", alert_sent),
    ):
        path = os.path.join(OUT_DIR, name)
        write_wav(path, builder())
        print(f"{path}: {os.path.getsize(path):,} bytes")
