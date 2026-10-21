"""Fila de eventos de bloco. O worker do udev só enfileira."""

from __future__ import annotations

from dataclasses import dataclass

SHELL_CHARS = set(";|&$`<>\n")


class UdevError(ValueError):
    pass


@dataclass(frozen=True)
class VolumeEvent:
    kind: str
    devname: str
    fs_uuid: str
    fs_type: str

    def as_dict(self) -> dict[str, str]:
        return {
            "kind": self.kind,
            "devname": self.devname,
            "fs_uuid": self.fs_uuid,
            "fs_type": self.fs_type,
        }


def _parse_env(raw: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in raw.splitlines():
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise UdevError("linha sem chave")
        key, value = line.split("=", 1)
        if not key.isidentifier():
            raise UdevError("chave recusada")
        if any(char in value for char in SHELL_CHARS):
            raise UdevError("valor recusado")
        values[key] = value
    return values


class UdevAdapter:
    def __init__(self) -> None:
        self.runs_in_udev_worker = False
        self.queue: list[dict[str, str]] = []
        self.devices: dict[str, VolumeEvent] = {}

    def accept_worker_event(self, raw: str) -> None:
        """Chamado no limite do worker: só guarda o texto já separado. Não monta."""
        parsed = _parse_env(raw)
        action = parsed.get("ACTION", "")
        subsystem = parsed.get("SUBSYSTEM", "")
        if action not in {"add", "remove", "change"}:
            raise UdevError("ação desconhecida")
        if subsystem != "block":
            return
        devname = parsed.get("DEVNAME", "")
        fs_uuid = parsed.get("ID_FS_UUID", "")
        if not devname.startswith("/dev/") or not fs_uuid:
            raise UdevError("dispositivo incompleto")
        self.queue.append(
            {
                "action": action,
                "devname": devname,
                "fs_uuid": fs_uuid,
                "fs_type": parsed.get("ID_FS_TYPE", "UNKNOWN"),
            }
        )

    def process_queue(self) -> list[VolumeEvent]:
        """Fora do worker. Atualiza o inventário do daemon."""
        self.runs_in_udev_worker = False
        emitted: list[VolumeEvent] = []
        pending = self.queue
        self.queue = []
        for item in pending:
            if item["action"] == "remove":
                self.devices.pop(item["fs_uuid"], None)
                emitted.append(
                    VolumeEvent("VolumeRemoved", item["devname"], item["fs_uuid"], item["fs_type"])
                )
                continue
            event = VolumeEvent(
                "VolumeDetected", item["devname"], item["fs_uuid"], item["fs_type"]
            )
            self.devices[item["fs_uuid"]] = event
            emitted.append(event)
        return emitted
