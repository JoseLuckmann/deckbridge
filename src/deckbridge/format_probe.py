"""Detecta o tipo de fonte sem escrever e sem abrir banco SQLite."""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass, field
from pathlib import Path

AUDIO_PRIORITY = (".mp3", ".wav")
AUDIO_SECONDARY = (".flac", ".aiff", ".aif")
PDB_RELATIVE = Path("PIONEER/rekordbox/export.pdb")
MIN_PDB_BYTES = 64


@dataclass
class ProbeResult:
    format_kind: str
    status: str
    error_code: str
    cues_loaded: bool
    rekordbox_version: str
    onelibrary_present: bool
    onelibrary_readable: bool
    priority_audio: list[str]
    secondary_audio: list[str]
    files_seen: int
    rejected: list[str] = field(default_factory=list)
    synthetic_structure_only: bool = True

    def as_dict(self) -> dict[str, object]:
        return {
            "format_kind": self.format_kind,
            "status": self.status,
            "error_code": self.error_code,
            "cues_loaded": self.cues_loaded,
            "rekordbox_version": self.rekordbox_version,
            "onelibrary_present": self.onelibrary_present,
            "onelibrary_readable": self.onelibrary_readable,
            "priority_audio": self.priority_audio,
            "secondary_audio": self.secondary_audio,
            "files_seen": self.files_seen,
            "rejected": self.rejected,
            "synthetic_structure_only": self.synthetic_structure_only,
            "hil_executed": False,
        }


def _contained(root: Path, candidate: Path) -> bool:
    try:
        candidate.resolve().relative_to(root.resolve())
    except (OSError, ValueError):
        return False
    return True


def probe(
    root: Path,
    *,
    timeout_s: float = 10,
    clock=None,
    max_files: int = 1000,
    max_path: int = 180,
    max_bytes: int = 33_554_432,
) -> ProbeResult:
    now = clock or (lambda: 0.0)
    start = now()
    rejected: list[str] = []
    priority: list[str] = []
    secondary: list[str] = []
    onelibrary = False
    pdb_state = "absent"
    seen = 0
    if not root.is_dir():
        return _finish("UNKNOWN", "FAILED", "", False, priority, secondary, seen, rejected)

    for path in sorted(root.rglob("*")):
        if now() - start > timeout_s:
            return _finish(
                "UNKNOWN", "TIMEOUT", "E_PARSER_TIMEOUT", onelibrary, priority, secondary, seen, rejected
            )
        if seen >= max_files:
            rejected.append("max_files")
            break
        relative = path.relative_to(root).as_posix()
        if len(relative) > max_path or ".." in path.parts:
            rejected.append(relative[:80])
            continue
        if not _contained(root, path):
            rejected.append("escape")
            continue
        if path.is_symlink() or not path.is_file():
            if path.is_symlink():
                rejected.append("symlink")
            continue
        seen += 1
        size = path.stat().st_size
        if size > max_bytes:
            rejected.append("oversize")
            continue
        name = path.name.lower()
        suffix = path.suffix.lower()
        if path.relative_to(root) == PDB_RELATIVE:
            pdb_state = "truncated" if size < MIN_PDB_BYTES else "present"
            continue
        if name == "exportlibrary.db":
            onelibrary = True
            continue
        if suffix in AUDIO_PRIORITY and size > 0:
            priority.append(suffix)
        elif suffix in AUDIO_SECONDARY and size > 0:
            secondary.append(suffix)

    if pdb_state == "present":
        return _finish(
            "REKORDBOX_LEGACY", "READY", "", onelibrary, priority, secondary, seen, rejected
        )
    if pdb_state == "truncated":
        return _finish(
            "UNKNOWN",
            "FAILED",
            "E_RB_UNSUPPORTED_VARIANT",
            onelibrary,
            priority,
            secondary,
            seen,
            rejected,
        )
    if onelibrary:
        return _finish(
            "ONELIBRARY",
            "DETECTED",
            "E_RB_EXPORT_NOT_FOUND",
            True,
            priority,
            secondary,
            seen,
            rejected,
        )
    if priority or secondary:
        return _finish("AUDIO_FILES", "READY", "", False, priority, secondary, seen, rejected)
    if rejected:
        return _finish("UNSUPPORTED", "FAILED", "E_RB_UNSUPPORTED_VARIANT", False, priority, secondary, seen, rejected)
    return _finish("UNKNOWN", "EMPTY", "", False, priority, secondary, seen, rejected)


def _finish(
    kind: str,
    status: str,
    code: str,
    onelibrary: bool,
    priority: list[str],
    secondary: list[str],
    seen: int,
    rejected: list[str],
) -> ProbeResult:
    return ProbeResult(
        format_kind=kind,
        status=status,
        error_code=code,
        cues_loaded=False,
        rekordbox_version="UNKNOWN",
        onelibrary_present=onelibrary,
        onelibrary_readable=False,
        priority_audio=sorted(set(priority)),
        secondary_audio=sorted(set(secondary)),
        files_seen=seen,
        rejected=rejected,
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Sonda uma pasta sem escrever")
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args(argv)
    print(json.dumps(probe(args.root).as_dict(), ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
