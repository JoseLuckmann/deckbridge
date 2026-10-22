#!/usr/bin/env python3
"""Lê uma pasta de exportação sem gravar nada dentro dela.

A leitura completa (faixas, playlists, cues, tags, DEVSETTING) usa o parser Kotlin
deste repositório. Esta passagem em Python lista a árvore, os FourCC e os bytes
de DEVSETTING. Com --jvm, chama o teste que imprime o relatório do parser.
O relatório JVM sai em /tmp/deckbridge-inspect.txt, nunca na pasta lida.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

WAVE_TAGS = {b"PWAV", b"PWV3", b"PWV4", b"PWV5", b"PWV6", b"PWV7", b"PWVC", b"PQTZ", b"PCOB", b"PCO2"}
COLORS = {0x01: "BLUE", 0x03: "RGB", 0x04: "3BAND"}
HEADS = {0x01: "centro", 0x02: "esquerda"}


def find_pdb(root: Path) -> Path | None:
    for relative in ("PIONEER/rekordbox/export.pdb", "rekordbox/export.pdb", "export.pdb"):
        candidate = root / relative
        if candidate.is_file():
            return candidate
    return None


def fourcc_counts(path: Path) -> dict[str, int]:
    data = path.read_bytes()
    counts: dict[str, int] = {}
    for tag in WAVE_TAGS:
        counts[tag.decode("ascii")] = data.count(tag)
    return {key: value for key, value in counts.items() if value}


def devsetting(root: Path) -> str:
    path = root / "PIONEER" / "DEVSETTING.DAT"
    if not path.is_file():
        return "DEVSETTING.DAT ausente"
    data = path.read_bytes()
    if len(data) < 104 + 14:
        return f"DEVSETTING.DAT curto ({len(data)} bytes)"
    color = data[104 + 10]
    head = data[104 + 13]
    color_name = COLORS.get(color, f"byte {color:#x}")
    head_name = HEADS.get(head, f"byte {head:#x}")
    return f"DEVSETTING.DAT {len(data)} bytes cor={color_name} playhead={head_name}"


def structural(root: Path) -> str:
    lines = [f"pasta: {root}", "modo: leitura", "gravação na pasta: não"]
    pdb = find_pdb(root)
    lines.append(f"export.pdb: {pdb if pdb else 'ausente'}")
    if pdb:
        lines.append(f"export.pdb bytes: {pdb.stat().st_size}")
    lines.append(devsetting(root))
    anlz = list(root.rglob("*.DAT")) + list(root.rglob("*.EXT")) + list(root.rglob("*.2EX"))
    anlz = [path for path in anlz if "USBANLZ" in path.parts or path.suffix.upper() in {".DAT", ".EXT", ".2EX"}]
    # Não seguir links para fora.
    lines.append(f"arquivos de análise vistos: {len(anlz)}")
    tag_total: dict[str, int] = {}
    errors = 0
    for path in anlz:
        try:
            found = fourcc_counts(path)
        except OSError as error:
            lines.append(f"erro ao ler {path.name}: {error}")
            errors += 1
            continue
        for key, value in found.items():
            tag_total[key] = tag_total.get(key, 0) + value
    if tag_total:
        lines.append("tags: " + ", ".join(f"{key}={value}" for key, value in sorted(tag_total.items())))
    else:
        lines.append("tags: nenhuma das conhecidas")
    audio = [path for path in root.rglob("*") if path.suffix.lower() in {".mp3", ".wav", ".flac", ".aac", ".m4a", ".aiff"}]
    lines.append(f"áudios: {len(audio)}")
    lines.append(f"erros de leitura: {errors}")
    lines.append("Nada foi escrito dentro da pasta.")
    return "\n".join(lines)


def run_jvm(root: Path, android: Path) -> int:
    report = Path("/tmp/deckbridge-inspect.txt")
    if report.exists():
        report.unlink()
    command = [
        str(android / "gradlew"),
        ":app:testMultiDebugUnitTest",
        "--offline",
        "--tests",
        "os.deckbridge.logic.UsbInspectTest.externalFolder",
        f"-Dusb.folder={root}",
        f"-Dusb.report={report}",
    ]
    print("parser JVM…")
    completed = subprocess.run(command, cwd=android, check=False)
    if report.is_file():
        print(report.read_text())
    else:
        print("o parser JVM não escreveu o relatório")
    return completed.returncode


def main() -> int:
    parser = argparse.ArgumentParser(description="Inspeciona uma pasta PIONEER sem gravar nela.")
    parser.add_argument("folder", type=Path)
    parser.add_argument("--jvm", action="store_true", help="Roda o parser Kotlin e imprime faixas, playlists, cues e tags.")
    args = parser.parse_args()
    root = args.folder.expanduser().resolve()
    if not root.is_dir():
        print(f"não é pasta: {root}", file=sys.stderr)
        return 2
    print(structural(root))
    if not args.jvm:
        print("Para o relatório completo: python3 tools/inspect_usb.py PASTA --jvm")
        return 0
    android = Path(__file__).resolve().parents[1] / "android"
    return run_jvm(root, android)


if __name__ == "__main__":
    sys.exit(main())
