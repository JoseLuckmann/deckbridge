import json
from pathlib import Path

from deckbridge.fixtures_audio import sha256, write_aiff, write_wav

ROOT = Path(__file__).resolve().parents[1]


def test_wav_and_aiff_are_deterministic(tmp_path: Path) -> None:
    first = tmp_path / "a.wav"
    second = tmp_path / "b.wav"
    write_wav(first, 44100)
    write_wav(second, 44100)
    assert sha256(first) == sha256(second)
    assert first.read_bytes()[:4] == b"RIFF"
    aiff_a = tmp_path / "a.aiff"
    aiff_b = tmp_path / "b.aiff"
    write_aiff(aiff_a, 44100)
    write_aiff(aiff_b, 44100)
    assert sha256(aiff_a) == sha256(aiff_b)
    assert aiff_a.read_bytes()[8:12] == b"AIFF"


def test_committed_priority_files_match_manifest() -> None:
    manifest = json.loads((ROOT / "fixtures/audio/MANIFEST.json").read_text(encoding="utf-8"))
    assert manifest["priority"] == ["mp3", "wav"]
    for name in (
        "tone-440hz-1s-44100.wav",
        "tone-440hz-1s-48000.wav",
        "tone-440hz-1s-44100.mp3",
    ):
        path = ROOT / "fixtures/audio" / name
        assert sha256(path) == manifest["files"][name]
    attribution = (ROOT / "fixtures/usb-audio-only/ATTRIBUTION.txt").read_text(encoding="utf-8")
    assert "CC0" in attribution
    assert not (ROOT / "fixtures/usb-audio-only/PIONEER").exists()
