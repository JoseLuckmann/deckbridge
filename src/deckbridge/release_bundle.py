"""Pacote das ferramentas. Não inclui binário do Mixxx nem imagem de Raspberry."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import shutil
import subprocess
import tarfile
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
VERSION = "0.1.0"
SOURCE_DATE_EPOCH = "1761000000"


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _stage(dest: Path) -> None:
    if dest.exists():
        shutil.rmtree(dest)
    share = dest / "usr/share/deckbridge"
    bindir = dest / "usr/bin"
    debian = dest / "DEBIAN"
    share.mkdir(parents=True)
    bindir.mkdir(parents=True)
    debian.mkdir(parents=True)
    shutil.copytree(REPO / "src/deckbridge", share / "src/deckbridge")
    pin = REPO / "third_party/mixxx/2.5.6"
    target = share / "third_party/mixxx/2.5.6"
    target.parent.mkdir(parents=True)
    shutil.copytree(pin, target)
    notice = REPO / "docs/licenses/NOTICE.md"
    if notice.is_file():
        shutil.copy2(notice, share / "NOTICE.md")
    wrapper = bindir / "deckbridge-hil-collect"
    wrapper.write_text(
        "#!/bin/sh\n"
        "export PYTHONPATH=/usr/share/deckbridge/src${PYTHONPATH:+:$PYTHONPATH}\n"
        'exec python3 -m deckbridge.hil_collect "$@"\n',
        encoding="utf-8",
    )
    wrapper.chmod(0o755)
    (debian / "control").write_text(
        "\n".join(
            [
                "Package: deckbridge-tools",
                f"Version: {VERSION}",
                "Section: sound",
                "Priority: optional",
                "Architecture: all",
                "Depends: python3 (>= 3.11)",
                "Maintainer: DeckBridge OS <deckbridge@localhost>",
                "Description: ferramentas de diagnóstico da fase 0",
                " Coleta fatos da máquina e não inclui o Mixxx.",
                " Não é uma imagem de aparelho e não toca música sozinha.",
                "",
            ]
        ),
        encoding="utf-8",
    )


def _reset_mtime(root: Path) -> None:
    stamp = int(SOURCE_DATE_EPOCH)
    for path in sorted(root.rglob("*")):
        os.utime(path, (stamp, stamp))
    os.utime(root, (stamp, stamp))


def build(out_dir: Path) -> dict[str, object]:
    out_dir.mkdir(parents=True, exist_ok=True)
    stage = out_dir / "stage"
    _stage(stage)
    _reset_mtime(stage)
    env = os.environ.copy()
    env["SOURCE_DATE_EPOCH"] = SOURCE_DATE_EPOCH
    deb = out_dir / f"deckbridge-tools_{VERSION}_all.deb"
    if deb.exists():
        deb.unlink()
    done = subprocess.run(
        ["dpkg-deb", "--root-owner-group", "--build", str(stage), str(deb)],
        check=False,
        capture_output=True,
        text=True,
        env=env,
    )
    if done.returncode != 0:
        raise RuntimeError(done.stderr.strip() or "dpkg-deb falhou")
    tar_path = out_dir / f"deckbridge-tools_{VERSION}_linux-any.tar.gz"
    if tar_path.exists():
        tar_path.unlink()
    with tarfile.open(tar_path, "w:gz") as archive:
        archive.add(stage / "usr", arcname="deckbridge-tools/usr")
    manifest = {
        "name": "deckbridge-tools",
        "version": VERSION,
        "built_at_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "built_on_arch": platform.machine(),
        "contains_machine_code": False,
        "debian_architecture": "all",
        "installable_on": ["amd64", "arm64", "armhf", "any python3"],
        "mixxx_binary_included": False,
        "mixxx_arm64_status": "BLOCKED_HARDWARE",
        "pi_gen_status": "adiado para a imagem do aparelho (T041)",
        "files": [
            {"path": deb.name, "sha256": _sha256(deb), "bytes": deb.stat().st_size},
            {"path": tar_path.name, "sha256": _sha256(tar_path), "bytes": tar_path.stat().st_size},
        ],
    }
    manifest_path = out_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    manifest["manifest_sha256"] = _sha256(manifest_path)
    return manifest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Gera deb e tar das ferramentas")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    report = build(args.out)
    print(json.dumps(report, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
