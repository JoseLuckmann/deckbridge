"""Procura o binário do Mixxx. Ausência não vira versão inventada."""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
from pathlib import Path

UNKNOWN = "UNKNOWN"
PIN = Path(__file__).resolve().parents[2] / "third_party/mixxx/2.5.6/PIN.json"


def probe(path_lookup: str | None = None) -> dict[str, object]:
    pin = json.loads(PIN.read_text(encoding="utf-8"))
    binary = path_lookup if path_lookup is not None else shutil.which("mixxx")
    if not binary:
        return {
            "status": "BLOCKED",
            "reason": "binário mixxx ausente neste ambiente",
            "binary": UNKNOWN,
            "version_text": UNKNOWN,
            "matches_pin": False,
            "pinned_tag": pin["tag"],
            "playback_tested": False,
            "hil_executed": False,
        }
    try:
        done = subprocess.run(
            [binary, "--version"],
            check=False,
            capture_output=True,
            text=True,
            timeout=15,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        return {
            "status": "BLOCKED",
            "reason": f"não foi possível executar o binário: {exc.__class__.__name__}",
            "binary": binary,
            "version_text": UNKNOWN,
            "matches_pin": False,
            "pinned_tag": pin["tag"],
            "playback_tested": False,
            "hil_executed": False,
        }
    text = ((done.stdout or "") + (done.stderr or "")).strip()
    first = text.splitlines()[0] if text else UNKNOWN
    matches = pin["tag"] in text
    return {
        "status": "VERSION_SEEN" if text else "BLOCKED",
        "reason": "versão lida do binário; reprodução de áudio não foi executada",
        "binary": binary,
        "version_text": first[:300],
        "matches_pin": matches,
        "pinned_tag": pin["tag"],
        "playback_tested": False,
        "hil_executed": False,
        "exit_code": done.returncode,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Smoke do binário Mixxx, sem fingir áudio")
    parser.add_argument("--out", type=Path)
    args = parser.parse_args(argv)
    report = probe()
    text = json.dumps(report, indent=2, ensure_ascii=False) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(text, encoding="utf-8")
    else:
        print(text, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
