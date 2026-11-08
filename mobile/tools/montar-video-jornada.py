#!/usr/bin/env python3
"""Monta o vídeo da jornada a partir do que o JourneyVideoTest gravou.

Uso: montar-video-jornada.py <pasta passada em -PjourneyVideo> [saida.mp4]

Lê out/frames (PNG a 10 quadros/s), out/taps.tsv (quadro, x, y: o toque aparece como um círculo nos quadros
seguintes), out/steps.tsv (quadro, legenda: a faixa de cima) e out/info.txt (onde começa o áudio do teste de mix,
out/mix.wav, até audio_fim_quadro). Precisa de Pillow e ffmpeg.
"""
import os
import shutil
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFont

base = sys.argv[1]
dest = sys.argv[2] if len(sys.argv) > 2 else os.path.join(base, "jornada.mp4")
out = os.path.join(base, "out")
frames = sorted(f for f in os.listdir(os.path.join(out, "frames")) if f.endswith(".png"))
info = dict(line.split("\t", 1) for line in open(os.path.join(out, "info.txt")).read().splitlines() if "\t" in line)
ms = int(info["ms_por_quadro"].split("\t")[0])
taps = [tuple(int(v) for v in line.split("\t")) for line in open(os.path.join(out, "taps.tsv")).read().splitlines() if line]
steps = [(int(a), b) for a, b in (line.split("\t", 1) for line in open(os.path.join(out, "steps.tsv")).read().splitlines() if line)]

font_dir = "/usr/share/fonts/truetype/dejavu"
title_font = ImageFont.truetype(os.path.join(font_dir, "DejaVuSans-Bold.ttf"), 38)
BAND = 96
BG = (0, 0, 0)
BLUE = (142, 190, 255)
LINE = (58, 61, 68)

# Trechos de espera acelerados (out/thin.tsv: quadro inicial, final, fica 1 a cada N), opcional.
thin = []
thin_file = os.path.join(out, "thin.tsv")
if os.path.isfile(thin_file):
    thin = [tuple(int(v) for v in line.split("\t")) for line in open(thin_file).read().splitlines() if line]

def kept(i):
    for a, b, every in thin:
        if a <= i < b:
            return (i - a) % every == 0
    return True

work = os.path.join(base, "montagem")
os.makedirs(work, exist_ok=True)
tap_len = 6  # quadros com o círculo: os de antes do toque (o teste segura a tela ali) até o toque
shutil.rmtree(work)
os.makedirs(work)
written = 0
audio_frame_src = int(info["audio_quadro"].split("\t")[0])
audio_frame = None
# Onde a reprodução parou (o som some ali; sem isso o resto da prévia tocaria por cima das telas seguintes).
audio_end_src = int(info["audio_fim_quadro"].split("\t")[0]) if "audio_fim_quadro" in info else None
audio_end = None
for i, name in enumerate(frames):
    if not kept(i):
        continue
    if audio_frame is None and i >= audio_frame_src:
        audio_frame = written
    if audio_end is None and audio_end_src is not None and i >= audio_end_src:
        audio_end = written
    im = Image.open(os.path.join(out, "frames", name)).convert("RGB")
    w, h = im.size
    canvas = Image.new("RGB", (w, h + BAND), BG)
    canvas.paste(im, (0, BAND))
    d = ImageDraw.Draw(canvas)
    caption = ""
    for start, text in steps:
        if i >= start:
            caption = text
    d.rectangle([0, 0, w, BAND - 1], fill=(12, 12, 14))
    d.line([0, BAND - 1, w, BAND - 1], fill=LINE, width=2)
    if caption:
        tw = d.textlength(caption, font=title_font)
        d.text(((w - tw) / 2, (BAND - 46) / 2), caption, font=title_font, fill=BLUE)
    for start, x, y in taps:
        # O toque: o teste segurou a tela 0,5–0,6 s antes de clicar; o círculo cresce nesses quadros.
        if start <= i < start + tap_len:
            k = (i - start + 1) / tap_len
            r = int(26 + 22 * k)
            cy = y + BAND
            d.ellipse([x - r, cy - r, x + r, cy + r], outline=(255, 255, 255), width=5)
            d.ellipse([x - 14, cy - 14, x + 14, cy + 14], fill=(255, 154, 46))
    canvas.save(os.path.join(work, "%05d.png" % written))
    written += 1

audio_start = int(info["audio_inicio_ms"].split("\t")[0]) / 1000.0
delay = audio_frame * ms
fps = 1000.0 / ms
# Só o trecho tocado: do começo até a parada, com 60 ms de saída suave.
trim = "" if audio_end is None else "atrim=0:%.3f,afade=t=out:st=%.3f:d=0.06," % ((audio_end - audio_frame) * ms / 1000.0, max(0.0, (audio_end - audio_frame) * ms / 1000.0 - 0.06))
subprocess.run([
    "ffmpeg", "-v", "error", "-y", "-framerate", str(fps), "-i", os.path.join(work, "%05d.png"),
    "-ss", "%.3f" % audio_start, "-i", os.path.join(out, "mix.wav"),
    "-filter_complex", "[0:v]scale=720:-2,format=yuv420p[v];[1:a]%sadelay=%d|%d,apad[a]" % (trim, delay, delay),
    "-map", "[v]", "-map", "[a]", "-t", "%.2f" % (written / fps),
    "-c:v", "libx264", "-crf", "20", "-preset", "medium", "-r", "30", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", dest,
], check=True)
print(dest)
