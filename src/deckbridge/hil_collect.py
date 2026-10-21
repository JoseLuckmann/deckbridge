"""Coleta de fatos da máquina. Dado ausente vira UNKNOWN. Nada é inventado."""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import shutil
import subprocess
from datetime import datetime, timezone
from pathlib import Path

from deckbridge.capability import HostFacts, classify

UNKNOWN = "UNKNOWN"
SCHEMA = "deckbridge.hil/1"


def _now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _read(path: Path) -> str | None:
    if not path.is_file():
        return None
    data = path.read_bytes().replace(b"\x00", b" ").decode("utf-8", errors="replace")
    text = data.strip()
    return text or None


def _mem_mb(meminfo: str | None) -> int | None:
    if not meminfo:
        return None
    match = re.search(r"^MemTotal:\s+(\d+)\s+kB", meminfo, re.MULTILINE)
    if not match:
        return None
    return int(match.group(1)) // 1024


def _cpu_cores(cpuinfo: str | None) -> int | None:
    if not cpuinfo:
        return None
    count = len(re.findall(r"^processor\s*:", cpuinfo, re.MULTILINE))
    return count or None


def _decode_throttled(raw: str | None) -> dict[str, object]:
    if not raw:
        return {
            "source": UNKNOWN,
            "throttled_raw": UNKNOWN,
            "under_voltage_now": UNKNOWN,
        }
    match = re.search(r"throttled=(0x[0-9a-fA-F]+)", raw)
    if not match:
        return {
            "source": "unparsed",
            "throttled_raw": raw.splitlines()[0][:200],
            "under_voltage_now": UNKNOWN,
        }
    value = int(match.group(1), 16)
    return {
        "source": "vcgencmd get_throttled",
        "throttled_raw": match.group(1),
        "under_voltage_now": bool(value & 0x1),
    }


def collect_from_root(root: Path, collected_at: str | None = None) -> dict[str, object]:
    """Lê só arquivos dentro de root. Não chama a máquina real."""
    meminfo = _read(root / "proc/meminfo")
    cpuinfo = _read(root / "proc/cpuinfo")
    model = _read(root / "proc/device-tree/model")
    machine = _read(root / "facts/uname_machine")
    kernel = _read(root / "facts/uname_release")
    system = _read(root / "facts/uname_sysname")
    mixxx = _read(root / "commands/mixxx_version.txt")
    throttled = _read(root / "commands/vcgencmd_get_throttled.txt")
    firmware = _read(root / "commands/ddj_firmware.txt")
    ram = _mem_mb(meminfo)
    cores = _cpu_cores(cpuinfo)
    facts = HostFacts(
        arch=machine or UNKNOWN,
        cpu_cores=cores,
        ram_mb=ram,
        model=model or UNKNOWN,
    )
    profile = classify(facts)
    return {
        "schema": SCHEMA,
        "collected_at_utc": collected_at or UNKNOWN,
        "hil_executed": False,
        "values_invented": False,
        "uname": {
            "sysname": system or UNKNOWN,
            "release": kernel or UNKNOWN,
            "machine": machine or UNKNOWN,
        },
        "model": facts.model,
        "arch": facts.arch,
        "cpu_cores": cores if cores is not None else UNKNOWN,
        "ram_mb": ram if ram is not None else UNKNOWN,
        "kernel": kernel or UNKNOWN,
        "power": _decode_throttled(throttled),
        "mixxx_version": mixxx.splitlines()[0][:300] if mixxx else UNKNOWN,
        "ddj_firmware": firmware.splitlines()[0][:200] if firmware else UNKNOWN,
        "runtime_profile": profile.as_dict(),
        "runtime_profile_note": "Limiares de política, não benchmark. O modelo não escolhe o perfil.",
    }


def _run(cmd: list[str], timeout: float = 8) -> str | None:
    try:
        done = subprocess.run(
            cmd,
            check=False,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    text = (done.stdout or done.stderr or "").strip()
    return text or None


def collect_live(collected_at: str | None = None) -> dict[str, object]:
    uname = os.uname()
    meminfo = _read(Path("/proc/meminfo"))
    cpuinfo = _read(Path("/proc/cpuinfo"))
    model = _read(Path("/proc/device-tree/model")) or _read(
        Path("/sys/firmware/devicetree/base/model")
    )
    ram = _mem_mb(meminfo)
    cores = _cpu_cores(cpuinfo)
    if cores is None:
        cores = os.cpu_count()
    facts = HostFacts(
        arch=uname.machine or UNKNOWN,
        cpu_cores=cores,
        ram_mb=ram,
        model=model or UNKNOWN,
    )
    throttled = None
    if shutil.which("vcgencmd"):
        throttled = _run(["vcgencmd", "get_throttled"])
    mixxx = None
    mixxx_bin = shutil.which("mixxx")
    if mixxx_bin:
        mixxx = _run([mixxx_bin, "--version"])
    profile = classify(facts)
    return {
        "schema": SCHEMA,
        "collected_at_utc": collected_at or _now_iso(),
        "hil_executed": False,
        "values_invented": False,
        "host_role": "coleta ao vivo; não é prova de áudio nem de DDJ-400",
        "uname": {
            "sysname": uname.sysname or UNKNOWN,
            "release": uname.release or UNKNOWN,
            "machine": uname.machine or UNKNOWN,
        },
        "model": facts.model,
        "arch": facts.arch,
        "cpu_cores": cores if cores is not None else UNKNOWN,
        "ram_mb": ram if ram is not None else UNKNOWN,
        "kernel": uname.release or UNKNOWN,
        "python": platform.python_version(),
        "power": _decode_throttled(throttled),
        "mixxx_version": mixxx.splitlines()[0][:300] if mixxx else UNKNOWN,
        "ddj_firmware": UNKNOWN,
        "runtime_profile": profile.as_dict(),
        "runtime_profile_note": "Limiares de política, não benchmark. O modelo não escolhe o perfil.",
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Coleta fatos HIL sem inventar valores")
    parser.add_argument("--root", type=Path, help="árvore falsa só com arquivos; não usa o host")
    parser.add_argument("--out", type=Path, help="grava JSON neste caminho")
    parser.add_argument("--now", help="carimbo ISO para testes")
    args = parser.parse_args(argv)
    if args.root:
        report = collect_from_root(args.root, collected_at=args.now)
    else:
        report = collect_live(collected_at=args.now)
    text = json.dumps(report, indent=2, ensure_ascii=False) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(text, encoding="utf-8")
    else:
        print(text, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
