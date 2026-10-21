from deckbridge.mixxx_smoke import probe


def test_missing_binary_is_blocked() -> None:
    report = probe(path_lookup="")
    assert report["status"] == "BLOCKED"
    assert report["version_text"] == "UNKNOWN"
    assert report["matches_pin"] is False
    assert report["playback_tested"] is False
