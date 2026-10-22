#!/usr/bin/env python3
"""Régua externa de BPM, fase e tom. Não entra no APK.

librosa  (licença ISC) fornece o beat tracker e o CQT.
Os perfis de tom são Krumhansl e o EDMA publicado por Faraldo e outros.
Nenhum dos dois é empacotado no aplicativo.
"""

import sys
from pathlib import Path

import numpy as np
from scipy.signal import butter, sosfiltfilt

try:
    import librosa
except ImportError:
    sys.exit("librosa não está instalado neste ambiente")

ROOT = Path("/opt/cursor/artifacts/real-corpus/mono")
TRACKS = [
    ("01-chromosome-boutique.wav", "Chromosome", "Bm"),
    ("02-ecosystem.wav", "Ecosystem", "Ebm"),
    ("07-silver.wav", "Silver", "Em"),
    ("morning-original-mix.wav", "Morning", ""),
    ("ocean-original-mix.wav", "Ocean", ""),
    ("wrist-watch.wav", "wrist", ""),
    ("toodaze.wav", "toodaze", ""),
    ("caught-in-the-beat.wav", "Caught", ""),
    ("at-the-count.wav", "Count", ""),
]

MAJOR = np.array([6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88])
MINOR = np.array([6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17])
EDMA_MAJOR = np.array([1.00, 0.00, 0.22, 0.00, 0.39, 0.16, 0.00, 0.44, 0.00, 0.24, 0.00, 0.18])
EDMA_MINOR = np.array([1.00, 0.00, 0.21, 0.32, 0.00, 0.22, 0.00, 0.43, 0.18, 0.00, 0.14, 0.20])
NAMES = ["C", "Db", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"]
MINOR_NAMES = [name + "m" for name in NAMES]


def correlate(chroma, profile):
    scores = []
    for shift in range(12):
        rotated = np.roll(profile, shift)
        scores.append(np.dot(chroma, rotated) / (np.linalg.norm(chroma) * np.linalg.norm(rotated) + 1e-12))
    return scores


def best_key(chroma, major, minor, names, minor_names):
    scores = []
    for shift, (maj, mino) in enumerate(zip(correlate(chroma, major), correlate(chroma, minor))):
        scores.append((maj, names[shift]))
        scores.append((mino, minor_names[shift]))
    scores.sort(reverse=True)
    return scores[0][1], scores[0][0] - scores[1][0]


def main():
    print(f"librosa {librosa.__version__} licença ISC. Régua, não é o detector do app.")
    for name, title, _tkey in TRACKS:
        path = ROOT / name
        if not path.is_file():
            print(f"{title} AUSENTE")
            continue
        y, sr = librosa.load(path, sr=22050, mono=True)
        onset = librosa.onset.onset_strength(y=y, sr=sr)
        tempo, beats = librosa.beat.beat_track(onset_envelope=onset, sr=sr, units="time")
        tempo = float(np.atleast_1d(tempo)[0])
        beats = np.asarray(beats, dtype=float)
        sos = butter(4, [40, 180], btype="band", fs=sr, output="sos")
        band = sosfiltfilt(sos, y)
        low_onset = librosa.onset.onset_strength(y=band, sr=sr)
        low_tempo, low_beats = librosa.beat.beat_track(onset_envelope=low_onset, sr=sr, units="time")
        low_tempo = float(np.atleast_1d(low_tempo)[0])
        chroma = librosa.feature.chroma_cqt(y=y, sr=sr).mean(axis=1)
        kk, _ = best_key(chroma, MAJOR, MINOR, NAMES, MINOR_NAMES)
        ed, _ = best_key(chroma, EDMA_MAJOR, EDMA_MINOR, NAMES, MINOR_NAMES)
        print(
            f"{title} librosa {tempo:.2f} bpm beats {len(beats)} "
            f"grave {low_tempo:.2f} beats {len(np.asarray(low_beats))} "
            f"krumhansl {kk} edma {ed}"
        )


if __name__ == "__main__":
    main()
