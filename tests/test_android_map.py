import json
from pathlib import Path

from deckbridge.mapping_extract import android_map_document

ROOT = Path(__file__).resolve().parents[1]


def test_android_map_matches_the_pinned_xml() -> None:
    saved = json.loads((ROOT / "android/app/src/main/resources/ddj400-map.json").read_text(encoding="utf-8"))
    fresh = android_map_document()
    assert saved == fresh
    load = next(
        item
        for item in saved["controls"]
        if item["group"] == "[Channel1]" and item["key"] == "LoadSelectedTrack"
    )
    assert load["status"] == 0x96
    assert load["midino"] == 0x46
    assert saved["hardware_capture"] == "BLOCKED_HARDWARE"
