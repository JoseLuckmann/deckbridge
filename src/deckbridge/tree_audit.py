"""Confere se a árvore mínima da fase 0 está presente."""

from __future__ import annotations

import argparse
from pathlib import Path

REQUIRED = (
    "README.md",
    "AGENTS.md",
    "LICENSE",
    "docs/licenses/NOTICE.md",
    "docs/licenses/sbom.json",
    "docs/branch-policy.md",
    ".github/workflows/ci.yml",
    ".specify/memory/constitution.md",
    "specs/001-ddj400-standalone/spec.md",
    "specs/001-ddj400-standalone/tasks.md",
    "third_party/mixxx/2.5.6/PIN.json",
    "docs/adr/0001-mixxx-base.md",
    "docs/adr/0004-build-pipeline.md",
    "docs/compatibility/ddj400-control-matrix.md",
    "docs/compatibility/rekordbox-format-matrix.md",
    "fixtures/audio/MANIFEST.json",
    "docs/setup/desktop-x86_64.md",
    "docs/setup/arm64-pipeline.md",
    "docs/hil/checklist-fase0.md",
)


def missing(root: Path) -> list[str]:
    return [rel for rel in REQUIRED if not (root / rel).is_file()]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Audita a árvore mínima")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args(argv)
    absent = missing(args.root)
    if absent:
        for item in absent:
            print(f"ausente: {item}")
        return 1
    print(f"árvore ok: {len(REQUIRED)} caminhos")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
