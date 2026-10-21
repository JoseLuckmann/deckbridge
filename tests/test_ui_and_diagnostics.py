from deckbridge.diagnostics import DiagnosticsError, record
from deckbridge.messages import CODES, message
from deckbridge.ui_layout import RESOLUTIONS, SCREENS, layout, layout_problems, render_svg


def test_four_screens_fit_three_resolutions_and_drop_waveform() -> None:
    for screen in SCREENS:
        for width, height in RESOLUTIONS:
            rects = layout(screen, width, height, "full")
            assert layout_problems(rects, width, height) == []
            svg = render_svg(screen, width, height, "full")
            assert "<script" not in svg.lower()
            assert "webview" not in svg.lower()
    performance = layout("PERFORMANCE", 800, 480, "full")
    names = {item.name for item in performance}
    assert {"bpm_a", "time_a", "play_a", "waveform_a", "bpm_b", "play_b"} <= names
    low = layout("PERFORMANCE", 800, 480, "off")
    assert not any(item.name.startswith("waveform") for item in low)
    assert "bpm_a" in {item.name for item in low}
    error = render_svg("ERROR", 1024, 600, "off")
    assert "Nada volta a tocar sozinho" in error


def test_messages_exist_in_both_locales_without_traces() -> None:
    for code in CODES:
        portuguese = message(code, "pt-BR")
        english = message(code, "en-US")
        assert portuguese != english
        assert "Traceback" not in portuguese
        assert "Traceback" not in english
    assert "STATUS" in message("nao-existe", "fr")


def test_diagnostics_hide_titles_and_refuse_shell() -> None:
    logged = record(
        {"event": "load", "title": "Faixa secreta", "path": "/media/secreto/faixa.mp3", "deck": "A"}
    )
    assert "title" not in logged
    assert "path" not in logged
    assert logged["temperature"] == "UNKNOWN"
    assert logged["undervoltage"] == "UNKNOWN"
    assert logged["xruns"] == "UNKNOWN"
    assert logged["xruns"] != 0
    assert logged["auto_play"] is False
    measured = record({"event": "health"}, temperature="48C", undervoltage="no", xruns=2)
    assert measured["temperature"] == "48C"
    assert measured["xruns"] == 2
    try:
        record({"command": "mount /dev/sdb1;rm -rf /"})
    except DiagnosticsError:
        return
    raise AssertionError("comando com metacaractere foi aceito")
