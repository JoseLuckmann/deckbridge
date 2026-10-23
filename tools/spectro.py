#!/usr/bin/env python3
"""Espectrograma (log em frequência e em amplitude) de um WAV 16 bit, só numpy + PIL.

    python3 -I tools/spectro.py entrada.wav saida.png INICIO_S DURACAO_S [FMIN FMAX]

O showspectrumpic do ffmpeg espelha varreduras puras em escala log; este não.
"""
import sys
import wave

import numpy as np
from PIL import Image, ImageDraw


def load(path, start, dur):
    with wave.open(path, "rb") as wav:
        rate = wav.getframerate()
        channels = wav.getnchannels()
        wav.setpos(int(start * rate))
        raw = wav.readframes(int(dur * rate))
    data = np.frombuffer(raw, dtype=np.int16).astype(np.float64) / 32768.0
    if channels > 1:
        data = data.reshape(-1, channels).mean(axis=1)
    return data, rate


def main():
    src, dst = sys.argv[1], sys.argv[2]
    start, dur = float(sys.argv[3]), float(sys.argv[4])
    fmin = float(sys.argv[5]) if len(sys.argv) > 5 else 40.0
    fmax = float(sys.argv[6]) if len(sys.argv) > 6 else 16000.0
    x, rate = load(src, start, dur)
    win = 4096
    hop = max(1, int(len(x) / 1400))
    window = np.hanning(win)
    cols = []
    for at in range(0, len(x) - win, hop):
        spec = np.abs(np.fft.rfft(x[at:at + win] * window))
        cols.append(spec)
    spec = np.array(cols).T
    freqs = np.fft.rfftfreq(win, 1.0 / rate)
    height = 420
    rows = np.geomspace(fmin, fmax, height)
    idx = np.clip(np.searchsorted(freqs, rows), 0, len(freqs) - 1)
    img = spec[idx][::-1]
    db = 20.0 * np.log10(img / (win / 4.0) + 1e-9)
    db = np.clip((db + 100.0) / 80.0, 0.0, 1.0)
    # Mapa quente simples: preto, roxo, vermelho, amarelo, branco.
    stops = np.array([0.0, 0.25, 0.5, 0.75, 1.0])
    colors = np.array([[0, 0, 0], [70, 0, 110], [200, 30, 40], [255, 190, 30], [255, 255, 240]], dtype=np.float64)
    rgb = np.stack([np.interp(db, stops, colors[:, c]) for c in range(3)], axis=-1).astype(np.uint8)
    pic = Image.fromarray(rgb)
    canvas = Image.new("RGB", (pic.width + 70, pic.height + 30), (12, 12, 16))
    canvas.paste(pic, (60, 5))
    draw = ImageDraw.Draw(canvas)
    for hz in (60, 100, 200, 500, 1000, 2000, 5000, 10000):
        if hz < fmin or hz > fmax:
            continue
        y = 5 + int((1.0 - np.log(hz / fmin) / np.log(fmax / fmin)) * (height - 1))
        draw.line([(56, y), (60, y)], fill=(200, 200, 200))
        draw.text((4, y - 5), f"{hz}", fill=(200, 200, 200))
    seconds = pic.width / 1400.0 * dur
    for second in range(0, int(dur) + 1, max(1, int(dur / 10))):
        x0 = 60 + int(second / max(dur, 1e-6) * pic.width)
        draw.line([(x0, height + 5), (x0, height + 9)], fill=(200, 200, 200))
        draw.text((x0 - 6, height + 12), f"{start + second:.0f}s", fill=(200, 200, 200))
    canvas.save(dst)
    print(dst, int(seconds))


if __name__ == "__main__":
    main()
