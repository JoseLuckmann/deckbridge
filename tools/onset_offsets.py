#!/usr/bin/env python3
"""Mede, a cada 8 compassos, quanto os ataques reais caem fora da grade do analisador (o ouvido do DJ).

Uso: onset_offsets.py faixa.wav bpm origemSeg saida.json
O ataque é o instante em que o envelope de 1 ms da faixa de 1 a 5 kHz passa de 25% do pico de cada batida.
Positivo = o ataque real é depois da grade. A saída é {compasso: desvio em segundos}.
"""
import json
import sys
import wave

import numpy as np


def load(path):
    w = wave.open(path)
    data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).reshape(-1, w.getnchannels()).mean(1) / 32768.0
    return data, w.getframerate()


def band(x, sr, lo, hi):
    spectrum = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(len(x), 1 / sr)
    spectrum[(freqs < lo) | (freqs > hi)] = 0
    return np.fft.irfft(spectrum, len(x))


def main():
    path, bpm, origin, out = sys.argv[1], float(sys.argv[2]), float(sys.argv[3]), sys.argv[4]
    x, sr = load(path)
    env = np.convolve(np.abs(band(x, sr, 1000, 5000)), np.ones(max(1, sr // 1000)) / max(1, sr // 1000), "same")
    beat = 60.0 / bpm
    bar = 4 * beat
    bars = int((len(x) / sr - origin) / bar)
    result = {}
    reach = int(0.025 * sr)
    for start in range(0, bars - 1, 8):
        offsets = []
        for index in range(start * 4, min(bars * 4, start * 4 + 32)):
            center = int((origin + index * beat) * sr)
            lo, hi = center - reach, center + reach
            if lo < 0 or hi >= len(env):
                continue
            window = env[lo:hi]
            peak = window.max()
            if peak <= 1e-6:
                continue
            edge = int(np.argmax(window >= 0.25 * peak))
            offsets.append((edge - reach) / sr)
        # Só vale onde os ataques concordam (desvio de 3 ms): em trechos sem bumbo o limiar cai no que houver.
        if len(offsets) >= 16 and float(np.std(offsets)) < 0.003:
            result[str(start)] = float(np.median(offsets))
    json.dump(result, open(out, "w"))
    print(path, "compassos medidos", len(result), "desvio medio %.1f ms" % (1000 * np.mean(list(result.values()) or [0])))


if __name__ == "__main__":
    main()
