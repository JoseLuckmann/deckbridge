"""Gera tons sintéticos. Sem música comercial. MP3 e WAV são a prioridade."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import struct
import subprocess
import wave
from pathlib import Path

AMPLITUDE = 0.2
FREQUENCY_HZ = 440.0
SECONDS = 1.0


def sine(rate: int, seconds: float = SECONDS) -> list[int]:
    total = int(rate * seconds)
    samples = []
    for index in range(total):
        value = AMPLITUDE * math.sin(2.0 * math.pi * FREQUENCY_HZ * index / rate)
        samples.append(int(round(value * 32767)))
    return samples


def write_wav(path: Path, rate: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    samples = sine(rate)
    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(rate)
        handle.writeframes(b"".join(struct.pack("<h", sample) for sample in samples))


def write_aiff(path: Path, rate: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    samples = sine(rate)
    frames = b"".join(struct.pack(">h", sample) for sample in samples)
    # COMM: channels, frames, sample size, 80-bit sample rate.
    comm = struct.pack(">hIH", 1, len(samples), 16) + _extended_rate(rate)
    ssnd = struct.pack(">II", 0, 0) + frames
    form = b"COMM" + struct.pack(">I", len(comm)) + comm + b"SSND" + struct.pack(">I", len(ssnd)) + ssnd
    blob = b"FORM" + struct.pack(">I", 4 + len(form)) + b"AIFF" + form
    path.write_bytes(blob)


def _extended_rate(rate: int) -> bytes:
    known = {
        44100: bytes.fromhex("400eac44000000000000"),
        48000: bytes.fromhex("400ebc00000000000000"),
    }
    if rate not in known:
        raise ValueError(f"taxa sem representação fixa: {rate}")
    return known[rate]


def encode_with_ffmpeg(source: Path, dest: Path, codec: list[str]) -> str:
    dest.parent.mkdir(parents=True, exist_ok=True)
    done = subprocess.run(
        ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", str(source), *codec, str(dest)],
        check=False,
        capture_output=True,
        text=True,
    )
    if done.returncode != 0:
        raise RuntimeError(done.stderr.strip() or "ffmpeg falhou")
    version = subprocess.run(
        ["ffmpeg", "-version"],
        check=False,
        capture_output=True,
        text=True,
    )
    return (version.stdout or "").splitlines()[0] if version.stdout else "UNKNOWN"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def generate(audio_dir: Path) -> dict[str, object]:
    wav_441 = audio_dir / "tone-440hz-1s-44100.wav"
    wav_480 = audio_dir / "tone-440hz-1s-48000.wav"
    aiff = audio_dir / "tone-440hz-1s-44100.aiff"
    mp3 = audio_dir / "tone-440hz-1s-44100.mp3"
    flac = audio_dir / "tone-440hz-1s-44100.flac"
    write_wav(wav_441, 44100)
    write_wav(wav_480, 48000)
    write_aiff(aiff, 44100)
    ffmpeg_line = encode_with_ffmpeg(wav_441, mp3, ["-codec:a", "libmp3lame", "-b:a", "128k"])
    encode_with_ffmpeg(wav_441, flac, ["-codec:a", "flac"])
    files = [wav_441, wav_480, aiff, mp3, flac]
    return {
        "priority": ["mp3", "wav"],
        "secondary": ["flac", "aiff"],
        "license": "CC0-1.0",
        "content": "tom senoidal sintetizado, 440 Hz, 1 s, amplitude 0.2, mono",
        "ffmpeg": ffmpeg_line,
        "files": {path.name: sha256(path) for path in files},
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Gera fixtures de áudio sintéticas")
    parser.add_argument("--dir", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args(argv)
    report = generate(args.dir)
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
