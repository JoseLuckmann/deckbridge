"""Planeja montagem somente leitura e só publica a fonte se o findmnt confirmar."""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

REQUIRED_OPTIONS = ("ro", "nodev", "nosuid", "noexec")
UUID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")


class MountError(ValueError):
    pass


@dataclass(frozen=True)
class MountResult:
    offered: bool
    mountpoint: str
    options: tuple[str, ...]
    error_code: str
    argv: tuple[str, ...]

    def as_dict(self) -> dict[str, object]:
        return {
            "offered": self.offered,
            "mountpoint": self.mountpoint,
            "options": list(self.options),
            "error_code": self.error_code,
            "argv": list(self.argv),
        }


def sanitize_uuid(uuid: str) -> str:
    if not UUID_RE.fullmatch(uuid):
        raise MountError("uuid recusado")
    return uuid


def mountpoint_for(mount_root: str, uuid: str) -> Path:
    safe = sanitize_uuid(uuid)
    root = Path(mount_root)
    if not root.is_absolute():
        raise MountError("raiz inválida")
    point = (root / safe).resolve()
    try:
        point.relative_to(root.resolve())
    except ValueError as exc:
        raise MountError("ponto de montagem escapa da raiz") from exc
    return point


def plan_mount_argv(device: str, mountpoint: Path) -> list[str]:
    if not device.startswith("/dev/") or any(char in device for char in " \t;|&$`<>\n"):
        raise MountError("dispositivo recusado")
    options = ",".join(REQUIRED_OPTIONS)
    if "rw" in options.split(","):
        raise MountError("rw proibido")
    return ["mount", "-o", options, "--", device, str(mountpoint)]


def parse_findmnt_options(text: str) -> set[str]:
    for line in text.splitlines():
        if "OPTIONS=" in line:
            raw = line.split("OPTIONS=", 1)[1].strip()
            return {item for item in raw.split(",") if item}
        if line.startswith("ro") or ",ro" in line or line.startswith("rw"):
            return {item for item in line.strip().split(",") if item}
    return set()


def options_are_read_only(options: set[str]) -> bool:
    if "rw" in options or "ro" not in options:
        return False
    return all(flag in options for flag in REQUIRED_OPTIONS)


class MountGuard:
    def __init__(self, mount_root: str = "/media/deckbridge") -> None:
        self.mount_root = mount_root

    def mount(
        self,
        device: str,
        uuid: str,
        runner,
        findmnt,
        timeout_s: float = 5,
    ) -> MountResult:
        point = mountpoint_for(self.mount_root, uuid)
        argv = tuple(plan_mount_argv(device, point))
        try:
            runner(list(argv), timeout_s)
            report = findmnt(str(point), timeout_s)
        except TimeoutError:
            return MountResult(False, str(point), (), "E_USB_READONLY_UNVERIFIED", argv)
        except MountError:
            raise
        options = parse_findmnt_options(report)
        if not options_are_read_only(options):
            return MountResult(False, str(point), tuple(sorted(options)), "E_USB_READONLY_UNVERIFIED", argv)
        return MountResult(True, str(point), tuple(sorted(options)), "", argv)
