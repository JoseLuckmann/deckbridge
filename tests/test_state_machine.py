import random

from deckbridge.state_machine import Machine

EVENTS = (
    "controller_connected",
    "controller_lost",
    "audio_ready_4ch",
    "audio_missing_4ch",
    "audio_error",
    "source_mounted_ro",
    "source_readonly_unverified",
    "source_removed",
    "source_legacy_missing",
    "engine_crashed",
    "engine_recovered",
)


def test_same_event_twice_is_stable_and_never_autoplays() -> None:
    machine = Machine()
    rng = random.Random(20261021)
    for _ in range(40):
        event = rng.choice(EVENTS)
        first = machine.apply(event)
        second = machine.apply(event)
        assert first == second
        assert second["auto_play"] is False


def test_ready_requires_audio_and_controller_and_blocks_unverified_usb() -> None:
    machine = Machine()
    machine.apply("controller_connected")
    assert machine.overall() == "DEGRADED"
    machine.apply("audio_ready_4ch")
    assert machine.overall() == "READY"
    machine.apply("source_readonly_unverified")
    snap = machine.snapshot()
    assert snap["source_offered"] is False
    assert "E_USB_READONLY_UNVERIFIED" in snap["errors"]
    assert snap["overall"] == "READY"
    machine.apply("audio_missing_4ch")
    assert machine.overall() == "DEGRADED"
    machine.apply("engine_crashed")
    assert machine.overall() == "ERROR"
    machine.apply("engine_recovered")
    assert machine.auto_play is False
    assert machine.crashed is False


def test_unknown_event_is_rejected() -> None:
    machine = Machine()
    try:
        machine.apply("invented")
    except ValueError:
        return
    raise AssertionError("evento inventado foi aceito")
