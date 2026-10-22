#!/usr/bin/env python3
"""Baixa o corpus CC BY 3.0 e confere o SHA-256. Não grava os áudios no git."""

from __future__ import annotations

import hashlib
import sys
import urllib.request
from pathlib import Path

DEST = Path("/opt/cursor/artifacts/real-corpus")

TRACKS = [
    ("01-chromosome-boutique.mp3", "https://archive.org/download/FNet018/01.Chromosome_Boutique.mp3", "82305c96e34512f62f064dd7c8658234810aacc604025ad4b5afb392da99638a"),
    ("02-ecosystem.mp3", "https://archive.org/download/FNet018/02.Ecosystem.mp3", "88c3e94f4b61769c9ade87b7bd0e90f4dd250a79fa048997310307253fd0c5bc"),
    ("07-silver.mp3", "https://archive.org/download/FNet018/07.Silver.mp3", "bdc6445a1c6fffd264cdc0af7253264f7f38d59528cae0a5b02fa54e37843d79"),
    ("morning-original-mix.mp3", "https://archive.org/download/KPL033MiroslavWilde-OceanEP/Miroslav%20Wilde%20-%20Morning%20%28Original%20Mix%29.mp3", "38b3f83532a1364a699704316b985014c60506220b14c452d8b0d1318592eb53"),
    ("ocean-original-mix.mp3", "https://archive.org/download/KPL033MiroslavWilde-OceanEP/Miroslav%20Wilde%20-%20Ocean%20%28Original%20Mix%29.mp3", "609b775f27417f340b5493a35ed98532d0fd53ac657e86db2c47fdd6f74c1074"),
    ("wrist-watch.flac", "https://archive.org/download/unfound53/unfound53_01_-_raymundo_mendoza_-_wrist_watch.flac", "9dc453fb7a780fde3ff98cfa7f8766753135bdf104d14805f3fa8666e9ca23b1"),
    ("toodaze.mp3", "https://archive.org/download/unfound53/unfound53_02_-_raymundo_mendoza_-_toodaze.mp3", "124b76df2cf5df9c1b58eb75d45b711bb78919fa40607e7ffb214118a6e9b01b"),
    ("caught-in-the-beat.mp3", "https://archive.org/download/Slam_Funk-7603/Broke_For_Free_-_04_-_Caught_In_The_Beat.mp3", "9536238d78ae016c9d84e81e00e33d77929cec05d23dfbe6e4ce2066cedd8b6e"),
    ("at-the-count.mp3", "https://archive.org/download/Slam_Funk-7603/Broke_For_Free_-_07_-_At_The_Count.mp3", "ff577b890df192acdfca4733967610e1c04a3c7636e100da4ee799b9ac4222d3"),
]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    DEST.mkdir(parents=True, exist_ok=True)
    failed = 0
    for name, url, expected in TRACKS:
        dest = DEST / name
        if not dest.is_file() or dest.stat().st_size < 100_000:
            print(f"baixando {name}")
            urllib.request.urlretrieve(url, dest)
        got = sha256(dest)
        if got != expected:
            print(f"HASH {name} {got} esperado {expected}")
            failed += 1
        else:
            print(f"ok {name} {dest.stat().st_size} {got}")
    print("AAC e WAV derivados, se existirem, são transcodes deste app e não fazem parte desta lista.")
    return failed


if __name__ == "__main__":
    sys.exit(main())
