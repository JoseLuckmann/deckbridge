from pathlib import Path

import pytest

from deckbridge.capability import HostFacts, classify
from deckbridge.config import ConfigError, default_config, load_path, parse_toml

ROOT = Path(__file__).resolve().parents[1]


def _text(old: str, new: str) -> str:
    base = (ROOT / "config/defaults.toml").read_text(encoding="utf-8")
    if old not in base:
        raise AssertionError(old)
    return base.replace(old, new, 1)


def test_defaults_follow_owner_decisions() -> None:
    cfg = load_path(ROOT / "config/defaults.toml")
    assert cfg == default_config()
    assert cfg.profile == "runtime"
    assert cfg.preferred_width == 1024
    assert cfg.preferred_height == 600
    assert cfg.allow_write is False
    assert cfg.read_only is True
    assert cfg.device.master_channels == (1, 2)
    assert cfg.device.headphone_channels == (3, 4)
    assert cfg.audio_values_are_placeholders is True
    assert cfg.locale == "pt-BR"


def test_allow_write_and_bad_schema_are_refused() -> None:
    with pytest.raises(ConfigError):
        parse_toml(_text("allow_write = false", "allow_write = true"))
    with pytest.raises(ConfigError):
        parse_toml(_text("read_only = true", "read_only = false"))
    with pytest.raises(ConfigError):
        parse_toml(_text('schema_version = "1.0.0"', 'schema_version = "9.9.9"'))
    with pytest.raises(ConfigError):
        parse_toml(_text('orientation = "landscape"', 'orientation = "portrait"'))
    with pytest.raises(ConfigError):
        parse_toml(_text("master_channels = [1, 2]", "master_channels = [1, 3]"))


def test_profile_name_does_not_change_channels_or_capability() -> None:
    cfg = parse_toml(_text('profile = "runtime"', 'profile = "pi4"'))
    assert cfg.profile == "pi4"
    assert cfg.device.master_channels == (1, 2)
    weak = classify(HostFacts(arch="aarch64", cpu_cores=1, ram_mb=512, model="pi4"))
    assert weak.tier == "low"
    assert weak.waveforms == "off"
