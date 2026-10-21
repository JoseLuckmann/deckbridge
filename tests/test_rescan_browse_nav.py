from deckbridge.library_browse import LibraryBrowser, display_source, synthetic_tracks
from deckbridge.navigation import Navigator, load_bindings, performance_controls_kept, screen_assignments
from deckbridge.rescan import AudioSnapshot, request_rescan


def test_rescan_does_not_restart_audio_or_invent_a_control() -> None:
    audio = AudioSnapshot(playing=True, deck_a="Faixa 001", deck_b="Faixa 002", sample_rate=48000)
    request, after = request_rescan("usb-sintetico", audio)
    assert after is audio
    assert request.restarts_audio is False
    assert request.patch_cpp is False
    assert request.mixxx_control == ""
    assert request.hardware_confirmed is False
    assert request.method == "notify_only_until_in_process_hook"


def test_hundred_tracks_debounce_and_short_source_code() -> None:
    tracks = synthetic_tracks(100)
    assert len(tracks) == 100
    assert {item.format_kind for item in tracks} == {"mp3", "wav"}
    assert "path" not in tracks[0].as_dict()
    browser = LibraryBrowser(tracks)
    for step in range(10):
        browser.move(1)
        browser.request_load("A", step * 10)
        browser.settle(step * 10)
    assert browser.loaded["A"] is None
    browser.settle(9 * 10 + 200)
    assert browser.loaded["A"] == tracks[10]
    assert browser.load_count["A"] == 1
    shown = display_source("RB", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", colliding=True)
    assert shown == "RB AAAA"
    assert "eeeeeeeeeeee" not in shown


def test_button_map_comes_from_the_pinned_xml_and_leaves_play_alone() -> None:
    bindings = load_bindings()
    by_action = {item.action: item for item in bindings}
    assert by_action["LOAD_A"].status == "0x96"
    assert by_action["LOAD_A"].midino == "0x46"
    assert by_action["LOAD_B"].midino == "0x47"
    assert by_action["BROWSE_MOVE"].status == "0xB6"
    assert by_action["BROWSE_MOVE"].midino == "0x40"
    assert by_action["BROWSE_ENTER"].midino == "0x41"
    assert "não abre pasta" in by_action["BROWSE_ENTER"].note
    assert all(item.hardware_confirmed is False for item in bindings)
    assert all(value is None for value in screen_assignments().values())
    kept = performance_controls_kept()
    play = [item for item in kept if item["key"] == "play" and item["group"] == "[Channel1]"]
    assert play[0]["status"] == "0x90"
    assert play[0]["midino"] == "0x0B"
    navigator = Navigator(bindings)
    assert navigator.binding_for("0x90", "0x0B") is None
    assert navigator.screen == "PERFORMANCE"
    navigator.apply_logical("OPEN_BROWSE")
    assert navigator.screen == "BROWSE"
    navigator.apply_logical("BACK")
    assert navigator.screen == "PERFORMANCE"
