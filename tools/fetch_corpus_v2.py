#!/usr/bin/env python3
"""Corpus GiantSteps completo e determinístico (todas as faixas com BPM ou tom publicado), com divisão fixa.

Anotações: github.com/GiantSteps/giantsteps-tempo-dataset e giantsteps-key-dataset (CC BY-SA 4.0, segundo o
espelho do mirdata). Áudio: prévias de 2 min da JKU (Beatport), só em /opt/cursor/artifacts/ground-truth-v2, fora do git.
Divisão: um quinto das faixas (sha1 do id módulo 5 igual a 0) é HOLDOUT: não entra na escolha de parâmetros.
Pré-requisito: os dois repositórios clonados em /tmp.
"""
from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from fetch_ground_truth import BASE, KEY_ROOT, TEMPO_ROOT, family, load_side  # noqa: E402

OUT = Path("/opt/cursor/artifacts/ground-truth-v2")
AUDIO = OUT / "audio"
MONO = OUT / "mono"


def holdout(track_id: str) -> bool:
    return int(hashlib.sha1(track_id.encode()).hexdigest(), 16) % 5 == 0


def fetch(item: tuple[str, str]) -> tuple[str, bool]:
    track_id, digest = item
    dest = AUDIO / f"{track_id}.mp3"
    wav = MONO / f"{track_id}.wav"
    try:
        if not (dest.is_file() and (not digest or hashlib.md5(dest.read_bytes()).hexdigest() == digest)):
            urllib.request.urlretrieve(f"{BASE}{track_id}.LOFI.mp3", dest)
            if digest and hashlib.md5(dest.read_bytes()).hexdigest() != digest:
                dest.unlink(missing_ok=True)
                return track_id, False
        if not (wav.is_file() and wav.stat().st_size > 1000):
            subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", str(dest), "-ac", "1", "-ar", "22050", str(wav)], check=True)
        return track_id, True
    except Exception as error:  # noqa: BLE001
        print("falha", track_id, error, flush=True)
        return track_id, False


def main() -> None:
    AUDIO.mkdir(parents=True, exist_ok=True)
    MONO.mkdir(parents=True, exist_ok=True)
    tempo = load_side(TEMPO_ROOT, "tempo")
    key = load_side(KEY_ROOT, "key")
    ids = sorted(set(tempo) | set(key))
    digests = {i: (tempo.get(i) or key.get(i))["md5"] for i in ids}
    with ThreadPoolExecutor(max_workers=8) as pool:
        ok = dict(pool.map(fetch, [(i, digests[i]) for i in ids]))
    rows = []
    for i in ids:
        if not ok.get(i):
            continue
        meta = tempo.get(i) or key[i]
        rows.append(f"{i}\t{family(meta['genre'])}\t{tempo[i]['tempo'] if i in tempo else ''}\t{key[i]['key'].replace(chr(9), ' ') if i in key else ''}\t{'holdout' if holdout(i) else 'train'}")
    (OUT / "manifest.tsv").write_text("\n".join(rows) + "\n", encoding="utf-8")
    print(f"faixas {len(rows)} de {len(ids)} em {OUT}")


if __name__ == "__main__":
    main()
