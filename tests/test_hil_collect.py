import json
from pathlib import Path

from deckbridge.hil_collect import collect_from_root, collect_live


def _write(root: Path, rel: str, text: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def test_empty_tree_is_unknown(tmp_path: Path) -> None:
    report = collect_from_root(tmp_path, collected_at="2026-10-21T00:00:00Z")
    assert report["model"] == "UNKNOWN"
    assert report["ram_mb"] == "UNKNOWN"
    assert report["cpu_cores"] == "UNKNOWN"
    assert report["mixxx_version"] == "UNKNOWN"
    assert report["ddj_firmware"] == "UNKNOWN"
    assert report["power"]["throttled_raw"] == "UNKNOWN"
    assert report["hil_executed"] is False
    assert report["values_invented"] is False
    assert "Raspberry" not in json.dumps(report)


def test_files_are_read_and_throttled_bit_is_decoded(tmp_path: Path) -> None:
    _write(tmp_path, "proc/meminfo", "MemTotal:       2048000 kB\n")
    _write(tmp_path, "proc/cpuinfo", "processor : 0\nprocessor : 1\n")
    _write(tmp_path, "proc/device-tree/model", "Raspberry Pi 3 Model B\x00")
    _write(tmp_path, "facts/uname_machine", "aarch64")
    _write(tmp_path, "facts/uname_release", "6.1.0")
    _write(tmp_path, "facts/uname_sysname", "Linux")
    _write(tmp_path, "commands/vcgencmd_get_throttled.txt", "throttled=0x1\n")
    report = collect_from_root(tmp_path, collected_at="2026-10-21T00:00:00Z")
    assert report["ram_mb"] == 2000
    assert report["cpu_cores"] == 2
    assert report["model"] == "Raspberry Pi 3 Model B"
    assert report["power"]["under_voltage_now"] is True
    assert report["runtime_profile"]["tier"] == "mid"
    assert report["ddj_firmware"] == "UNKNOWN"


def test_live_collection_does_not_invent_firmware() -> None:
    report = collect_live(collected_at="2026-10-21T00:00:00Z")
    assert report["ddj_firmware"] == "UNKNOWN"
    assert report["hil_executed"] is False
    assert report["arch"] in {"x86_64", "aarch64", "armv7l", "UNKNOWN"}
    assert report["schema"] == "deckbridge.hil/1"
