#!/usr/bin/env python3
"""Baixa um corpus com BPM e tom publicados. O áudio não entra no git.

GiantSteps (Knees e outros, ISMIR 2015): anotações em
https://github.com/GiantSteps/giantsteps-tempo-dataset e
https://github.com/GiantSteps/giantsteps-key-dataset
(CC BY-SA 4.0, segundo o espelho do mirdata).
Os previews de 2 min vêm do backup de pesquisa da JKU:
https://www.cp.jku.at/datasets/giantsteps/backup/
São prévias do Beatport, não faixas CC. Ficam só em
/opt/cursor/artifacts/ground-truth.
"""

from __future__ import annotations

import hashlib
import json
import subprocess
import urllib.request
from collections import defaultdict
from pathlib import Path

TEMPO_ROOT = Path("/tmp/giantsteps-tempo-dataset")
KEY_ROOT = Path("/tmp/giantsteps-key-dataset")
OUT = Path("/opt/cursor/artifacts/ground-truth")
AUDIO = OUT / "audio"
MONO = OUT / "mono"
BASE = "https://www.cp.jku.at/datasets/giantsteps/backup/"

HOUSE = {
    "house",
    "deep-house",
    "tech-house",
    "electro-house",
    "progressive-house",
}
TRANCE = {"trance", "psy-trance"}
TECHNO = {"techno", "minimal", "hardcore-hard-techno"}
DNB = {"drum-and-bass"}
HIPHOP = {"glitch-hop"}


def family(genre: str) -> str:
    if genre in HOUSE:
        return "house"
    if genre in TRANCE:
        return "trance"
    if genre in TECHNO:
        return "techno"
    if genre in DNB:
        return "drum-and-bass"
    if genre in HIPHOP:
        return "glitch-hop"
    return genre


def read_map(folder: Path, suffix: str) -> dict[str, str]:
    out = {}
    for path in folder.glob(f"*.{suffix}"):
        track_id = path.name.split(".")[0]
        text = path.read_text(encoding="utf-8", errors="replace").strip().split()
        if text:
            out[track_id] = text[0] if suffix != "key" else path.read_text(encoding="utf-8").strip()
    if suffix == "key":
        out = {}
        for path in folder.glob("*.key"):
            out[path.name.split(".")[0]] = path.read_text(encoding="utf-8").strip()
    return out


def load_side(root: Path, kind: str) -> dict[str, dict]:
    if kind == "tempo":
        values = {}
        for path in (root / "annotations_v2" / "tempo").glob("*.bpm"):
            raw = path.read_text(encoding="utf-8").strip().split()
            if not raw:
                continue
            bpm = float(raw[0])
            if bpm < 1:
                continue
            values[path.name.split(".")[0]] = bpm
        genre_dir = root / "annotations" / "genre"
    else:
        values = {p.name.split(".")[0]: p.read_text(encoding="utf-8").strip() for p in (root / "annotations" / "key").glob("*.key")}
        genre_dir = root / "annotations" / "genre"
    rows = {}
    for track_id, value in values.items():
        genre_path = genre_dir / f"{track_id}.LOFI.genre"
        genre = genre_path.read_text(encoding="utf-8").strip() if genre_path.is_file() else "unknown"
        md5_path = root / "md5" / f"{track_id}.LOFI.md5"
        digest = md5_path.read_text(encoding="utf-8").strip().split()[0] if md5_path.is_file() else ""
        rows[track_id] = {"genre": genre, "family": family(genre), "md5": digest, kind: value}
    return rows


def take(pool: list[str], meta: dict, limit_per_family: int, already: set[str]) -> list[str]:
    buckets: dict[str, list[str]] = defaultdict(list)
    for track_id in pool:
        if track_id in already:
            continue
        buckets[meta[track_id]["family"]].append(track_id)
    chosen = []
    for name in ("house", "techno", "trance", "drum-and-bass", "glitch-hop", "dubstep", "electronica"):
        for track_id in buckets.get(name, [])[:limit_per_family]:
            chosen.append(track_id)
            already.add(track_id)
    return chosen


def download(track_id: str, digest: str) -> Path:
    dest = AUDIO / f"{track_id}.mp3"
    if dest.is_file() and hashlib.md5(dest.read_bytes()).hexdigest() == digest:
        return dest
    url = f"{BASE}{track_id}.LOFI.mp3"
    urllib.request.urlretrieve(url, dest)
    got = hashlib.md5(dest.read_bytes()).hexdigest()
    if digest and got != digest:
        dest.unlink(missing_ok=True)
        raise SystemExit(f"md5 {track_id}: {got} != {digest}")
    return dest


def to_mono(track_id: str, mp3: Path) -> None:
    wav = MONO / f"{track_id}.wav"
    if wav.is_file() and wav.stat().st_size > 1000:
        return
    subprocess.run(
        ["ffmpeg", "-y", "-v", "error", "-i", str(mp3), "-ac", "1", "-ar", "22050", str(wav)],
        check=True,
    )


def main() -> None:
    if not TEMPO_ROOT.is_dir() or not KEY_ROOT.is_dir():
        raise SystemExit("clone os dois repositórios GiantSteps em /tmp antes de correr este script")
    tempo = load_side(TEMPO_ROOT, "tempo")
    key = load_side(KEY_ROOT, "key")
    both_ids = sorted(set(tempo) & set(key))
    chosen: list[str] = []
    seen: set[str] = set()
    # Até seis faixas por família com BPM e tom no mesmo id.
    by_family: dict[str, list[str]] = defaultdict(list)
    for track_id in both_ids:
        by_family[tempo[track_id]["family"]].append(track_id)
    for name in ("house", "techno", "trance", "drum-and-bass", "glitch-hop", "dubstep", "electronica", "breaks", "hard-dance", "chill-out", "dj-tools", "pop-rock"):
        for track_id in by_family.get(name, [])[:6]:
            chosen.append(track_id)
            seen.add(track_id)
    def fill(meta: dict, want: int) -> None:
        counts: dict[str, int] = defaultdict(int)
        for track_id in chosen:
            if track_id in meta:
                counts[meta[track_id]["family"]] += 1
        for name in ("house", "techno", "trance", "drum-and-bass", "glitch-hop"):
            have = counts[name]
            if have >= want:
                continue
            for track_id, row in meta.items():
                if have >= want:
                    break
                if track_id in seen or row["family"] != name:
                    continue
                chosen.append(track_id)
                seen.add(track_id)
                have += 1

    fill(tempo, 6)
    fill(key, 6)

    AUDIO.mkdir(parents=True, exist_ok=True)
    MONO.mkdir(parents=True, exist_ok=True)
    rows = []
    for track_id in chosen:
        has_tempo = track_id in tempo
        has_key = track_id in key
        meta = tempo.get(track_id) or key[track_id]
        digest = (tempo.get(track_id) or {}).get("md5") or (key.get(track_id) or {}).get("md5") or ""
        mp3 = download(track_id, digest)
        to_mono(track_id, mp3)
        genre = tempo[track_id]["genre"] if has_tempo else key[track_id]["genre"]
        rows.append(
            {
                "id": track_id,
                "genre": genre,
                "family": family(genre),
                "bpm": tempo[track_id]["tempo"] if has_tempo else None,
                "key": key[track_id]["key"] if has_key else None,
                "md5": digest,
                "bytes": mp3.stat().st_size,
                "source": "GiantSteps / backup JKU",
                "license_note": "anotações CC BY-SA 4.0; áudio é prévia Beatport, fora do git",
            }
        )
        print(f"{track_id} {genre} bpm={rows[-1]['bpm']} key={rows[-1]['key']}", flush=True)
    manifest = {
        "citation": "Knees et al., ISMIR 2015. Two data sets for tempo estimation and key detection in EDM.",
        "audio_url": BASE,
        "do_not_commit_audio": True,
        "tracks": rows,
    }
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    tsv = []
    for row in rows:
        bpm = "" if row["bpm"] is None else str(row["bpm"])
        key = "" if row["key"] is None else str(row["key"]).replace("\t", " ")
        tsv.append(f"{row['id']}\t{row['family']}\t{bpm}\t{key}")
    (OUT / "manifest.tsv").write_text("\n".join(tsv) + "\n", encoding="utf-8")
    print(f"faixas {len(rows)} em {OUT}")


if __name__ == "__main__":
    main()
