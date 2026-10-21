"""Pedido de rescan que não mexe no áudio.

No Mixxx 2.5.6, commit 3ebac449, librarycontrol.cpp não publica um controle
[Library],Rescan. LibraryScanner::scan() existe só dentro do processo.
RekordboxFeature tem onRekordboxDevicesFound e refreshLibraryModels, também
internos. Não há patch C++ nesta fase.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class AudioSnapshot:
    playing: bool
    deck_a: str
    deck_b: str
    sample_rate: int


@dataclass(frozen=True)
class RescanRequest:
    source_id: str
    restarts_audio: bool
    method: str
    mixxx_control: str
    patch_cpp: bool
    hardware_confirmed: bool

    def as_dict(self) -> dict[str, object]:
        return {
            "source_id": self.source_id,
            "restarts_audio": self.restarts_audio,
            "method": self.method,
            "mixxx_control": self.mixxx_control,
            "patch_cpp": self.patch_cpp,
            "hardware_confirmed": self.hardware_confirmed,
        }


def request_rescan(source_id: str, audio: AudioSnapshot) -> tuple[RescanRequest, AudioSnapshot]:
    request = RescanRequest(
        source_id=source_id,
        restarts_audio=False,
        method="notify_only_until_in_process_hook",
        mixxx_control="",
        patch_cpp=False,
        hardware_confirmed=False,
    )
    return request, audio
