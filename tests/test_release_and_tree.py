import json
from pathlib import Path

from deckbridge.release_bundle import build
from deckbridge.tree_audit import missing

ROOT = Path(__file__).resolve().parents[1]


def test_required_tree_is_present() -> None:
    assert missing(ROOT) == []


def test_bundle_hashes_match_and_do_not_claim_mixxx(tmp_path: Path) -> None:
    first = build(tmp_path / "one")
    second = build(tmp_path / "two")
    assert first["contains_machine_code"] is False
    assert first["mixxx_binary_included"] is False
    assert first["mixxx_arm64_status"] == "BLOCKED_HARDWARE"
    assert first["debian_architecture"] == "all"
    names = {item["path"]: item["sha256"] for item in first["files"]}
    deb_name = next(name for name in names if name.endswith(".deb"))
    assert (tmp_path / "one" / deb_name).read_bytes()
    import hashlib

    digest = hashlib.sha256((tmp_path / "one" / deb_name).read_bytes()).hexdigest()
    assert digest == names[deb_name]
    again = {item["path"]: item["sha256"] for item in second["files"]}
    assert again[deb_name] == names[deb_name]


def test_rekordbox_matrix_cites_the_pinned_commit_and_does_not_claim_a_run() -> None:
    text = (ROOT / "docs/compatibility/rekordbox-format-matrix.md").read_text(encoding="utf-8")
    pin = json.loads((ROOT / "third_party/mixxx/2.5.6/PIN.json").read_text(encoding="utf-8"))
    assert pin["commit_sha"] in text
    assert "export.pdb" in text
    assert "BLOCKED_HARDWARE" in text
    assert "comprovado" not in text.lower()
