"""Amostras de CPU, RAM e temperatura. Não extrapola e não inventa x-runs."""

from __future__ import annotations

import argparse
import json
import time
from collections.abc import Callable
from pathlib import Path

UNKNOWN = "UNKNOWN"


def _mean(values: list[float]) -> float | None:
    if not values:
        return None
    return sum(values) / len(values)


def summarize(samples: list[dict[str, object]], *, requested_s: float, actual_s: float) -> dict[str, object]:
    cpu: list[float] = []
    ram: list[float] = []
    temp: list[float] = []
    xruns: list[int] = []
    for sample in samples:
        if isinstance(sample.get("cpu_busy_percent"), (int, float)):
            cpu.append(float(sample["cpu_busy_percent"]))
        if isinstance(sample.get("ram_available_mb"), (int, float)):
            ram.append(float(sample["ram_available_mb"]))
        if isinstance(sample.get("temperature_c"), (int, float)):
            temp.append(float(sample["temperature_c"]))
        if isinstance(sample.get("xruns_total"), int):
            xruns.append(sample["xruns_total"])
    cpu_mean = _mean(cpu)
    ram_min = min(ram) if ram else None
    temp_max = max(temp) if temp else None
    xrun_value: object = UNKNOWN
    if xruns and len(xruns) == len(samples):
        xrun_value = xruns[-1]
    return {
        "schema": "deckbridge.benchmark/1",
        "duration_requested_s": requested_s,
        "duration_actual_s": round(actual_s, 3),
        "sample_count": len(samples),
        "extrapolated": False,
        "mixxx_running": False,
        "audio_exercised": False,
        "hil_executed": False,
        "cpu_busy_percent_mean": round(cpu_mean, 2) if cpu_mean is not None else UNKNOWN,
        "ram_available_mb_min": round(ram_min, 1) if ram_min is not None else UNKNOWN,
        "temperature_c_max": round(temp_max, 1) if temp_max is not None else UNKNOWN,
        "xruns_total": xrun_value,
        "note": "Números valem só para esta amostra. Não projetar para outra duração, outro aparelho ou para o Mixxx tocando.",
    }


def run_benchmark(
    *,
    duration_s: float,
    interval_s: float,
    sample_fn: Callable[[], dict[str, object]],
    sleep_fn: Callable[[float], None],
    monotonic_fn: Callable[[], float],
) -> dict[str, object]:
    if duration_s <= 0:
        raise ValueError("duration_s deve ser positivo")
    start = monotonic_fn()
    samples: list[dict[str, object]] = []
    while monotonic_fn() - start < duration_s:
        samples.append(sample_fn())
        elapsed = monotonic_fn() - start
        if elapsed >= duration_s:
            break
        sleep_fn(min(interval_s, duration_s - elapsed))
    return summarize(samples, requested_s=duration_s, actual_s=monotonic_fn() - start)


def _read(path: Path) -> str | None:
    if not path.is_file():
        return None
    return path.read_text(encoding="utf-8", errors="replace").strip() or None


def sample_host() -> dict[str, object]:
    cpu = _cpu_busy()
    ram = _ram_available_mb()
    temp = _temperature_c()
    sample: dict[str, object] = {"xruns_total": UNKNOWN}
    sample["cpu_busy_percent"] = cpu if cpu is not None else UNKNOWN
    sample["ram_available_mb"] = ram if ram is not None else UNKNOWN
    sample["temperature_c"] = temp if temp is not None else UNKNOWN
    return sample


_previous_cpu: tuple[int, int] | None = None


def _cpu_busy() -> float | None:
    global _previous_cpu
    text = _read(Path("/proc/stat"))
    if not text:
        return None
    line = next((item for item in text.splitlines() if item.startswith("cpu ")), None)
    if not line:
        return None
    parts = [int(item) for item in line.split()[1:] if item.isdigit()]
    if len(parts) < 4:
        return None
    idle = parts[3]
    total = sum(parts)
    previous = _previous_cpu
    _previous_cpu = (idle, total)
    if previous is None:
        return None
    idle_d = idle - previous[0]
    total_d = total - previous[1]
    if total_d <= 0:
        return None
    busy = 100.0 * (1.0 - (idle_d / total_d))
    return max(0.0, min(100.0, busy))


def _ram_available_mb() -> float | None:
    text = _read(Path("/proc/meminfo"))
    if not text:
        return None
    match = None
    for line in text.splitlines():
        if line.startswith("MemAvailable:"):
            match = line
            break
    if not match:
        return None
    parts = match.split()
    if len(parts) < 2 or not parts[1].isdigit():
        return None
    return int(parts[1]) / 1024.0


def _temperature_c() -> float | None:
    text = _read(Path("/sys/class/thermal/thermal_zone0/temp"))
    if not text or not text.isdigit():
        return None
    return int(text) / 1000.0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Amostra curta ou longa, sem extrapolar")
    parser.add_argument("--duration", type=float, default=5.0)
    parser.add_argument("--interval", type=float, default=1.0)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args(argv)
    report = run_benchmark(
        duration_s=args.duration,
        interval_s=args.interval,
        sample_fn=sample_host,
        sleep_fn=time.sleep,
        monotonic_fn=time.monotonic,
    )
    report["host_sample"] = True
    report["pi_15min_status"] = "BLOCKED_HARDWARE"
    report["mixxx_15min_status"] = "BLOCKED_HARDWARE"
    text = json.dumps(report, indent=2, ensure_ascii=False) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(text, encoding="utf-8")
    else:
        print(text, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
