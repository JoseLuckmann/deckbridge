#!/usr/bin/env python3
"""Baixa mais prévias GiantSteps que têm tom anotado.

Não mexe em manifest.tsv. O corte das 58 faixas continua comparável.
O áudio fica em /opt/cursor/artifacts/ground-truth e não entra no git.
"""

from __future__ import annotations

import hashlib
import socket
import urllib.request
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import subprocess

KEY_ROOT = Path("/tmp/giantsteps-key-dataset")
OUT = Path("/opt/cursor/artifacts/ground-truth")
AUDIO = OUT / "audio"
MONO = OUT / "mono"
BASE = "https://www.cp.jku.at/datasets/giantsteps/backup/"

HOUSE = {"house", "deep-house", "tech-house", "electro-house", "progressive-house"}
TRANCE = {"trance", "psy-trance"}
TECHNO = {"techno", "minimal", "hardcore-hard-techno"}
DNB = {"drum-and-bass"}
HIPHOP = {"glitch-hop"}

# Cotas fechadas antes de ver qualquer previsão. O extra não entra na escolha do detetor.
QUOTA = {
    "house": 40,
    "techno": 30,
    "trance": 24,
    "drum-and-bass": 28,
    "dubstep": 17,
    "electronica": 13,
    "breaks": 14,
    "indie-dance-nu-disco": 14,
    "chill-out": 9,
    "glitch-hop": 6,
}


def family(genre: str) -> str:
    name = genre.strip().lower().replace(" ", "-").replace("_", "-")
    if name in HOUSE:
        return "house"
    if name in TRANCE:
        return "trance"
    if name in TECHNO:
        return "techno"
    if name in DNB:
        return "drum-and-bass"
    if name in HIPHOP:
        return "glitch-hop"
    return name


def load_rows() -> list[dict]:
    have = {path.stem for path in MONO.glob("*.wav")}
    rows = []
    for path in sorted((KEY_ROOT / "annotations" / "key").glob("*.key"), key=lambda item: int(item.name.split(".")[0])):
        track_id = path.name.split(".")[0]
        if track_id in have:
            continue
        genre_path = KEY_ROOT / "annotations" / "genre" / f"{track_id}.LOFI.genre"
        genre = genre_path.read_text(encoding="utf-8").strip() if genre_path.is_file() else "unknown"
        digest_path = KEY_ROOT / "md5" / f"{track_id}.LOFI.md5"
        digest = digest_path.read_text(encoding="utf-8").strip().split()[0] if digest_path.is_file() else ""
        key = path.read_text(encoding="utf-8").strip().replace("\t", " ")
        rows.append({"id": track_id, "genre": genre, "family": family(genre), "key": key, "md5": digest})
    return rows


def choose(rows: list[dict]) -> list[dict]:
    buckets: dict[str, list[dict]] = defaultdict(list)
    for row in rows:
        buckets[row["family"]].append(row)
    chosen = []
    for name, limit in QUOTA.items():
        chosen.extend(buckets.get(name, [])[:limit])
    return chosen


def download_one(row: dict) -> str:
    AUDIO.mkdir(parents=True, exist_ok=True)
    MONO.mkdir(parents=True, exist_ok=True)
    dest = AUDIO / f"{row['id']}.mp3"
    digest = row["md5"]
    if not (dest.is_file() and digest and hashlib.md5(dest.read_bytes()).hexdigest() == digest):
        url = f"{BASE}{row['id']}.LOFI.mp3"
        last = "falhou"
        for _ in range(3):
            try:
                urllib.request.urlretrieve(url, dest)
                got = hashlib.md5(dest.read_bytes()).hexdigest()
                if digest and got != digest:
                    dest.unlink(missing_ok=True)
                    last = f"md5 {got}"
                    continue
                break
            except Exception as exc:  # noqa: BLE001 — rede instável; tenta de novo
                dest.unlink(missing_ok=True)
                last = str(exc)
        else:
            return f"erro {row['id']} {last}"
    wav = MONO / f"{row['id']}.wav"
    if not (wav.is_file() and wav.stat().st_size > 1000):
        subprocess.run(
            ["ffmpeg", "-y", "-v", "error", "-i", str(dest), "-ac", "1", "-ar", "22050", str(wav)],
            check=True,
        )
    return f"ok {row['id']} {row['family']}"


def main() -> None:
    if not KEY_ROOT.is_dir():
        raise SystemExit("falta /tmp/giantsteps-key-dataset")
    socket.setdefaulttimeout(40)
    chosen = choose(load_rows())
    print(f"pedido {len(chosen)}", flush=True)
    lines = []
    done = 0
    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = [pool.submit(download_one, row) for row in chosen]
        for future in as_completed(futures):
            done += 1
            text = future.result()
            if done % 20 == 0 or text.startswith("erro"):
                print(f"{done}/{len(chosen)} {text}", flush=True)
    ok_ids = {row["id"] for row in chosen if (MONO / f"{row['id']}.wav").is_file()}
    for row in chosen:
        if row["id"] not in ok_ids:
            continue
        lines.append(f"{row['id']}\t{row['family']}\t\t{row['key']}")
    manifest = OUT / "manifest-key-extra.tsv"
    manifest.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")
    print(f"gravado {len(lines)} em {manifest}", flush=True)


if __name__ == "__main__":
    main()
