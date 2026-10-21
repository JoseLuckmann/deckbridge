"""Estados de controladora, áudio e fonte. O mesmo evento duas vezes não muda o resultado."""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class Machine:
    controller: str = "ABSENT"
    audio: str = "STARTING"
    master_available: bool = False
    headphones_available: bool = False
    source: str = "ABSENT"
    source_offered: bool = False
    crashed: bool = False
    auto_play: bool = False
    errors: list[str] = field(default_factory=list)

    def apply(self, event: str) -> dict[str, object]:
        if event == "controller_connected":
            self.controller = "CONNECTED"
            self._drop("E_DDJ_NOT_FOUND")
        elif event == "controller_lost":
            self.controller = "ABSENT"
            self._add("E_DDJ_NOT_FOUND")
        elif event == "audio_ready_4ch":
            self.audio = "READY"
            self.master_available = True
            self.headphones_available = True
            self._drop("E_AUDIO_4CH_MISSING")
        elif event == "audio_missing_4ch":
            self.audio = "DEGRADED"
            self.master_available = True
            self.headphones_available = False
            self._add("E_AUDIO_4CH_MISSING")
        elif event == "audio_error":
            self.audio = "ERROR"
            self.master_available = False
            self.headphones_available = False
            self._add("E_AUDIO_4CH_MISSING")
        elif event == "source_mounted_ro":
            self.source = "READY"
            self.source_offered = True
            self._drop("E_USB_REMOVED")
            self._drop("E_USB_READONLY_UNVERIFIED")
        elif event == "source_readonly_unverified":
            self.source = "BLOCKED"
            self.source_offered = False
            self._add("E_USB_READONLY_UNVERIFIED")
        elif event == "source_removed":
            self.source = "REMOVED"
            self.source_offered = False
            self._add("E_USB_REMOVED")
        elif event == "source_legacy_missing":
            self.source = "UNSUPPORTED"
            self.source_offered = False
            self._add("E_RB_EXPORT_NOT_FOUND")
        elif event == "engine_crashed":
            self.crashed = True
            self.auto_play = False
            self._add("E_ENGINE_CRASH")
        elif event == "engine_recovered":
            self.crashed = False
            self.auto_play = False
            self._drop("E_ENGINE_CRASH")
        else:
            raise ValueError(f"evento desconhecido: {event}")
        self.auto_play = False
        return self.snapshot()

    def overall(self) -> str:
        if self.crashed or self.audio == "ERROR":
            return "ERROR"
        four_ch = self.audio == "READY" and self.master_available and self.headphones_available
        if not four_ch or self.controller != "CONNECTED":
            return "DEGRADED"
        return "READY"

    def snapshot(self) -> dict[str, object]:
        return {
            "overall": self.overall(),
            "controller": self.controller,
            "audio": self.audio,
            "master_available": self.master_available,
            "headphones_available": self.headphones_available,
            "source": self.source,
            "source_offered": self.source_offered,
            "crashed": self.crashed,
            "auto_play": self.auto_play,
            "errors": list(self.errors),
        }

    def _add(self, code: str) -> None:
        if code not in self.errors:
            self.errors.append(code)

    def _drop(self, code: str) -> None:
        self.errors = [item for item in self.errors if item != code]
