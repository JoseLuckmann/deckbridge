"""Configuração versionada. Escrita no pendrive continua proibida."""

from __future__ import annotations

import tomllib
from dataclasses import dataclass
from pathlib import Path

SCHEMA_VERSION = "1.0.0"
REFERENCE_RESOLUTIONS = ((800, 480), (1024, 600))


class ConfigError(ValueError):
    pass


@dataclass(frozen=True)
class DeviceProfile:
    schema_version: str
    profile_id: str
    display_name: str
    midi_name_hint: str
    audio_name_hint: str
    master_channels: tuple[int, int]
    headphone_channels: tuple[int, int]

    def as_dict(self) -> dict[str, object]:
        return {
            "schema_version": self.schema_version,
            "profile_id": self.profile_id,
            "display_name": self.display_name,
            "midi_name_hint": self.midi_name_hint,
            "audio_name_hint": self.audio_name_hint,
            "master_channels": list(self.master_channels),
            "headphone_channels": list(self.headphone_channels),
        }


def ddj400_profile() -> DeviceProfile:
    return DeviceProfile(
        schema_version=SCHEMA_VERSION,
        profile_id="pioneer-ddj400",
        display_name="DDJ-400",
        midi_name_hint="DDJ-400",
        audio_name_hint="DDJ-400",
        master_channels=(1, 2),
        headphone_channels=(3, 4),
    )


@dataclass(frozen=True)
class AppConfig:
    schema_version: str
    profile: str
    locale: str
    boot_into_mixxx: bool
    allow_internet: bool
    device: DeviceProfile
    audio_backend: str
    sample_rate: int
    buffer_frames: int
    mount_root: str
    read_only: bool
    allow_write: bool
    probe_timeout_seconds: int
    max_file_scan_bytes: int
    ui_mode: str
    orientation: str
    preferred_width: int
    preferred_height: int
    skin: str
    health_enabled: bool
    sample_interval_seconds: int
    companion_enabled: bool
    bind_host: str
    audio_values_are_placeholders: bool = True

    def as_dict(self) -> dict[str, object]:
        return {
            "schema_version": self.schema_version,
            "profile": self.profile,
            "locale": self.locale,
            "boot_into_mixxx": self.boot_into_mixxx,
            "allow_internet": self.allow_internet,
            "device": self.device.as_dict(),
            "audio": {
                "preferred_backend": self.audio_backend,
                "sample_rate": self.sample_rate,
                "buffer_frames": self.buffer_frames,
                "values_are_placeholders": self.audio_values_are_placeholders,
                "master_channels": list(self.device.master_channels),
                "headphone_channels": list(self.device.headphone_channels),
            },
            "usb": {
                "mount_root": self.mount_root,
                "read_only": self.read_only,
                "allow_write": self.allow_write,
                "probe_timeout_seconds": self.probe_timeout_seconds,
                "max_file_scan_bytes": self.max_file_scan_bytes,
            },
            "ui": {
                "mode": self.ui_mode,
                "orientation": self.orientation,
                "preferred_width": self.preferred_width,
                "preferred_height": self.preferred_height,
                "skin": self.skin,
                "reference_resolutions": [list(item) for item in REFERENCE_RESOLUTIONS],
            },
            "health": {
                "enabled": self.health_enabled,
                "sample_interval_seconds": self.sample_interval_seconds,
            },
            "companion": {
                "enabled": self.companion_enabled,
                "bind_host": self.bind_host,
            },
        }


def default_config() -> AppConfig:
    return AppConfig(
        schema_version=SCHEMA_VERSION,
        profile="runtime",
        locale="pt-BR",
        boot_into_mixxx=True,
        allow_internet=False,
        device=ddj400_profile(),
        audio_backend="ALSA",
        sample_rate=48000,
        buffer_frames=512,
        mount_root="/media/deckbridge",
        read_only=True,
        allow_write=False,
        probe_timeout_seconds=10,
        max_file_scan_bytes=33_554_432,
        ui_mode="PERFORMANCE",
        orientation="landscape",
        preferred_width=1024,
        preferred_height=600,
        skin="DeckBridgeCompact",
        health_enabled=True,
        sample_interval_seconds=5,
        companion_enabled=False,
        bind_host="127.0.0.1",
    )


def _need(table: dict[str, object], key: str) -> object:
    if key not in table:
        raise ConfigError(f"campo ausente: {key}")
    return table[key]


def _as_bool(value: object, key: str) -> bool:
    if not isinstance(value, bool):
        raise ConfigError(f"{key} precisa ser booleano")
    return value


def _as_int(value: object, key: str, minimum: int, maximum: int) -> int:
    if not isinstance(value, int) or isinstance(value, bool):
        raise ConfigError(f"{key} precisa ser inteiro")
    if value < minimum or value > maximum:
        raise ConfigError(f"{key} fora do intervalo")
    return value


def _pair(value: object, key: str) -> tuple[int, int]:
    if not isinstance(value, list) or len(value) != 2:
        raise ConfigError(f"{key} precisa ter dois canais")
    left = _as_int(value[0], key, 1, 32)
    right = _as_int(value[1], key, 1, 32)
    return left, right


def _mount_root(value: object) -> str:
    if not isinstance(value, str) or not value.startswith("/"):
        raise ConfigError("usb.mount_root precisa ser caminho absoluto")
    if any(token in value for token in ("..", ";", "|", "`", "$", "\n", " ")):
        raise ConfigError("usb.mount_root recusado")
    return value


def parse_toml(text: str) -> AppConfig:
    try:
        raw = tomllib.loads(text)
    except tomllib.TOMLDecodeError as exc:
        raise ConfigError("toml inválido") from exc
    if not isinstance(raw, dict):
        raise ConfigError("toml inválido")
    schema = _need(raw, "schema_version")
    if schema != SCHEMA_VERSION:
        raise ConfigError("schema_version não suportada")
    profile = _need(raw, "profile")
    if not isinstance(profile, str) or not profile:
        raise ConfigError("profile inválido")
    locale = _need(raw, "locale")
    if locale not in {"pt-BR", "en-US"}:
        raise ConfigError("locale inválido")
    audio = raw.get("audio")
    usb = raw.get("usb")
    ui = raw.get("ui")
    controller = raw.get("controller")
    if not isinstance(audio, dict) or not isinstance(usb, dict) or not isinstance(ui, dict):
        raise ConfigError("seções audio, usb e ui são obrigatórias")
    if not isinstance(controller, dict):
        raise ConfigError("seção controller é obrigatória")
    allow_write = _as_bool(_need(usb, "allow_write"), "usb.allow_write")
    read_only = _as_bool(_need(usb, "read_only"), "usb.read_only")
    if allow_write or not read_only:
        raise ConfigError("usb.allow_write=true é recusado; o pendrive fica somente leitura")
    master = _pair(_need(audio, "master_channels"), "audio.master_channels")
    phones = _pair(_need(audio, "headphone_channels"), "audio.headphone_channels")
    if master != (1, 2) or phones != (3, 4):
        raise ConfigError("DDJ-400 usa master 1–2 e fone 3–4")
    device = ddj400_profile()
    if controller.get("profile") not in {None, device.profile_id}:
        raise ConfigError("controller.profile desta fase é pioneer-ddj400")
    width = _as_int(_need(ui, "preferred_width"), "ui.preferred_width", 320, 7680)
    height = _as_int(_need(ui, "preferred_height"), "ui.preferred_height", 240, 4320)
    orientation = _need(ui, "orientation")
    if orientation != "landscape":
        raise ConfigError("orientação desta fase é landscape")
    return AppConfig(
        schema_version=SCHEMA_VERSION,
        profile=profile,
        locale=locale,
        boot_into_mixxx=_as_bool(raw.get("boot_into_mixxx", True), "boot_into_mixxx"),
        allow_internet=_as_bool(raw.get("allow_internet", False), "allow_internet"),
        device=device,
        audio_backend=str(_need(audio, "preferred_backend")),
        sample_rate=_as_int(_need(audio, "sample_rate"), "audio.sample_rate", 8000, 192000),
        buffer_frames=_as_int(_need(audio, "buffer_frames"), "audio.buffer_frames", 32, 8192),
        mount_root=_mount_root(_need(usb, "mount_root")),
        read_only=True,
        allow_write=False,
        probe_timeout_seconds=_as_int(
            _need(usb, "probe_timeout_seconds"), "usb.probe_timeout_seconds", 1, 120
        ),
        max_file_scan_bytes=_as_int(
            _need(usb, "max_file_scan_bytes"), "usb.max_file_scan_bytes", 1024, 512_000_000
        ),
        ui_mode=str(_need(ui, "mode")),
        orientation="landscape",
        preferred_width=width,
        preferred_height=height,
        skin=str(_need(ui, "skin")),
        health_enabled=_as_bool(raw.get("health", {}).get("enabled", True) if isinstance(raw.get("health"), dict) else True, "health.enabled"),
        sample_interval_seconds=_as_int(
            raw.get("health", {}).get("sample_interval_seconds", 5) if isinstance(raw.get("health"), dict) else 5,
            "health.sample_interval_seconds",
            1,
            60,
        ),
        companion_enabled=_as_bool(
            raw.get("companion", {}).get("enabled", False) if isinstance(raw.get("companion"), dict) else False,
            "companion.enabled",
        ),
        bind_host=str(raw.get("companion", {}).get("bind_host", "127.0.0.1") if isinstance(raw.get("companion"), dict) else "127.0.0.1"),
    )


def load_path(path: Path) -> AppConfig:
    return parse_toml(path.read_text(encoding="utf-8"))
