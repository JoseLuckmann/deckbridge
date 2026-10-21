import hashlib
import os
import subprocess
import sys
from pathlib import Path

from deckbridge.format_probe import probe

ROOT = Path(__file__).resolve().parents[1]


def _hashes(folder: Path) -> dict[str, str]:
    found: dict[str, str] = {}
    for path in sorted(folder.rglob("*")):
        if path.is_file() and not path.is_symlink():
            found[path.relative_to(folder).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    return found


def test_legacy_truncated_and_audio_priority(tmp_path: Path) -> None:
    usb = tmp_path / "usb"
    pdb = usb / "PIONEER/rekordbox"
    pdb.mkdir(parents=True)
    (pdb / "export.pdb").write_bytes(b"x" * 64)
    (usb / "house.mp3").write_bytes(b"mp3")
    (usb / "house.wav").write_bytes(b"wav")
    (usb / "extra.flac").write_bytes(b"flac")
    before = _hashes(usb)
    result = probe(usb)
    assert result.format_kind == "REKORDBOX_LEGACY"
    assert result.cues_loaded is False
    assert result.rekordbox_version == "UNKNOWN"
    assert result.synthetic_structure_only is True
    assert result.priority_audio == [".mp3", ".wav"]
    assert result.secondary_audio == [".flac"]
    assert _hashes(usb) == before

    (pdb / "export.pdb").write_bytes(b"short")
    truncated = probe(usb)
    assert truncated.error_code == "E_RB_UNSUPPORTED_VARIANT"
    assert truncated.cues_loaded is False


def test_onelibrary_is_detected_and_not_opened(tmp_path: Path) -> None:
    usb = tmp_path / "usb"
    nested = usb / "rekordbox"
    nested.mkdir(parents=True)
    (nested / "exportLibrary.db").write_bytes(b"not-a-database")
    before = set(usb.rglob("*"))
    result = probe(usb)
    assert result.format_kind == "ONELIBRARY"
    assert result.onelibrary_readable is False
    assert result.error_code == "E_RB_EXPORT_NOT_FOUND"
    assert result.cues_loaded is False
    after = set(usb.rglob("*"))
    assert before == after
    assert not list(usb.rglob("*.db-wal"))
    source = (ROOT / "src/deckbridge/format_probe.py").read_text(encoding="utf-8")
    assert "import sqlite3" not in source


def test_timeout_huge_path_and_symlink_escape(tmp_path: Path) -> None:
    usb = tmp_path / "usb"
    usb.mkdir()
    (usb / "a.mp3").write_bytes(b"abc")
    ticks = iter((0.0, 11.0))
    timed = probe(usb, timeout_s=10, clock=lambda: next(ticks))
    assert timed.status == "TIMEOUT"
    assert timed.error_code == "E_PARSER_TIMEOUT"

    long_name = "n" * 190 + ".wav"
    (usb / long_name).write_bytes(b"wav")
    huge = probe(usb)
    assert any(item.startswith("n") for item in huge.rejected)

    secret = tmp_path / "secret.mp3"
    secret.write_bytes(b"outside")
    (usb / "link.mp3").symlink_to(secret)
    escaped = probe(usb)
    assert "escape" in escaped.rejected
    assert secret.read_bytes() == b"outside"


def test_probe_subprocess_creates_nothing_outside(tmp_path: Path) -> None:
    usb = tmp_path / "usb"
    usb.mkdir()
    (usb / "faixa café.mp3").write_bytes(b"x")
    (usb / "a;rm.wav").write_bytes(b"y")
    marker = tmp_path / "touched-by-probe"
    env = os.environ.copy()
    env["PYTHONPATH"] = str(ROOT / "src")
    before = {path.relative_to(tmp_path).as_posix() for path in tmp_path.rglob("*")}
    completed = subprocess.run(
        [sys.executable, "-m", "deckbridge.format_probe", "--root", str(usb)],
        check=True,
        capture_output=True,
        text=True,
        env=env,
        cwd=ROOT,
    )
    after = {path.relative_to(tmp_path).as_posix() for path in tmp_path.rglob("*")}
    assert before == after
    assert not marker.exists()
    assert "AUDIO_FILES" in completed.stdout
    assert "cues_loaded" in completed.stdout
