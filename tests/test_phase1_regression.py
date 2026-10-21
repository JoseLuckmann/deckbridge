from pathlib import Path

from deckbridge.config import load_path
from deckbridge.evidence import admits_pass, gate_closed
from deckbridge.format_probe import probe
from deckbridge.library_browse import LibraryBrowser, synthetic_tracks
from deckbridge.messages import message
from deckbridge.navigation import Navigator
from deckbridge.rescan import AudioSnapshot, request_rescan
from deckbridge.state_machine import Machine
from deckbridge.ui_layout import layout, layout_problems

ROOT = Path(__file__).resolve().parents[1]


def test_software_flow_stays_honest_without_hardware(tmp_path: Path) -> None:
    cfg = load_path(ROOT / "config/defaults.toml")
    assert cfg.allow_write is False
    machine = Machine()
    machine.apply("controller_connected")
    machine.apply("audio_ready_4ch")
    machine.apply("source_readonly_unverified")
    assert machine.source_offered is False
    usb = tmp_path / "usb"
    usb.mkdir()
    (usb / "faixa.mp3").write_bytes(b"sintetico")
    (usb / "faixa.wav").write_bytes(b"sintetico")
    found = probe(usb)
    assert found.format_kind == "AUDIO_FILES"
    assert found.cues_loaded is False
    audio = AudioSnapshot(False, "", "", cfg.sample_rate)
    request, same = request_rescan("sintetico", audio)
    assert same is audio
    assert request.restarts_audio is False
    browser = LibraryBrowser(synthetic_tracks())
    browser.move(3)
    browser.request_load("B", 0)
    browser.settle(200)
    assert browser.loaded["B"].format_kind == "wav"
    navigator = Navigator()
    assert navigator.hardware_confirmed is False
    navigator.apply_logical("OPEN_STATUS")
    assert navigator.screen == "STATUS"
    rects = layout("STATUS", cfg.preferred_width, cfg.preferred_height, "simple")
    assert layout_problems(rects, cfg.preferred_width, cfg.preferred_height) == []
    assert "somente leitura" in message("E_USB_READONLY_UNVERIFIED", cfg.locale).lower()
    assert admits_pass("T034", "SIMULATED") is False
    assert admits_pass("T020", "SOFTWARE") is True
    assert gate_closed("G1", "SIMULATED") is False
    assert gate_closed("G1", "SOFTWARE") is False
