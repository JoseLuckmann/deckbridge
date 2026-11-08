"""Auditoria visual das capturas: colorido (Hasler & Süsstrunk 2003) e complexidade (densidade de bordas).

Reinecke et al. (CHI 2013) mostraram que colorido e complexidade visual são o que mais pesa na primeira impressão
(500 ms). Este script mede os dois nas capturas de docs/ui para comparar telas e versões.
Uso: python3 -I tools/visual_audit.py docs/ui/*.png
"""
import sys
from PIL import Image, ImageFilter, ImageStat


def colorfulness(im):
    px = im.convert("RGB").resize((300, int(300 * im.height / im.width))).getdata()
    rg = [r - g for r, g, b in px]
    yb = [0.5 * (r + g) - b for r, g, b in px]
    def ms(v):
        m = sum(v) / len(v)
        return m, (sum((x - m) ** 2 for x in v) / len(v)) ** 0.5
    mrg, srg = ms(rg)
    myb, syb = ms(yb)
    return (srg ** 2 + syb ** 2) ** 0.5 + 0.3 * (mrg ** 2 + myb ** 2) ** 0.5


def edges(im):
    g = im.convert("L").resize((300, int(300 * im.height / im.width))).filter(ImageFilter.FIND_EDGES)
    return sum(1 for v in g.getdata() if v > 40) / (g.width * g.height)


for path in sys.argv[1:]:
    im = Image.open(path)
    print(f"{path.split('/')[-1]}\t{colorfulness(im):.1f}\t{edges(im) * 100:.1f}%")
