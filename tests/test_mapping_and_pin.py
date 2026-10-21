import json
from pathlib import Path

from deckbridge.mapping_extract import (
    JS_PATH,
    PIN_PATH,
    XML_PATH,
    build_document,
    parse_lights,
    parse_xml,
)

ROOT = Path(__file__).resolve().parents[1]


def test_pin_matches_vendored_mapping_bytes() -> None:
    pin = json.loads(PIN_PATH.read_text(encoding="utf-8"))
    data, _markdown = build_document()
    assert pin["tag"] == "2.5.6"
    assert pin["prerelease"] is False
    assert pin["tag_pgp_signature_verified"] is False
    assert data["xml_sha256"] == pin["controllers"]["Pioneer-DDJ-400.midi.xml"]["sha256"]
    assert data["js_sha256"] == pin["controllers"]["Pioneer-DDJ-400-script.js"]["sha256"]
    assert len(pin["commit_sha"]) == 40


def test_parser_counts_every_control_and_does_not_drop_status() -> None:
    parsed = parse_xml(XML_PATH)
    raw = XML_PATH.read_text(encoding="utf-8")
    assert len(parsed["controls"]) == raw.count("<control>")
    assert len(parsed["outputs"]) == raw.count("<output>")
    browse = next(item for item in parsed["controls"] if item["key"] == "MoveVertical")
    assert browse["status"] == "0xB6"
    assert browse["midino"] == "0x40"
    assert browse["group"] == "[Library]"


def test_lights_come_from_the_script() -> None:
    lights = parse_lights(JS_PATH.read_text(encoding="utf-8"))
    play = next(item for item in lights if item["name"] == "lights.deck1.playPause")
    assert play["status"] == "0x90"
    assert play["data1"] == "0x0B"


def test_matrix_markdown_matches_the_parser() -> None:
    data, markdown = build_document()
    committed = (ROOT / "docs/compatibility/ddj400-control-matrix.md").read_text(encoding="utf-8")
    assert markdown == committed
    assert str(data["control_count"]) in markdown
    assert "BLOCKED_HARDWARE" in markdown
    assert "comprovado" not in markdown.lower()
