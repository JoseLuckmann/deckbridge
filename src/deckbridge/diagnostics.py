"""Registro local. Não chama shell e não guarda título nem caminho por padrão."""

from __future__ import annotations

SHELL_CHARS = set(";|&$`<>\n")
PRIVATE_FIELDS = {"path", "filepath", "track_path", "title", "track_title"}
COMMAND_FIELDS = {"command", "shell", "argv"}


class DiagnosticsError(ValueError):
    pass


def record(
    event: dict[str, object],
    *,
    temperature: str | None = None,
    undervoltage: str | None = None,
    xruns: str | int | None = None,
) -> dict[str, object]:
    clean: dict[str, object] = {}
    for key, value in event.items():
        if key in PRIVATE_FIELDS:
            continue
        if key in COMMAND_FIELDS:
            if isinstance(value, str):
                text = value
            elif isinstance(value, list):
                text = " ".join(str(part) for part in value)
            else:
                text = str(value)
            if any(char in text for char in SHELL_CHARS):
                raise DiagnosticsError("metacaractere recusado")
        clean[key] = value
    clean["temperature"] = "UNKNOWN" if temperature is None else temperature
    clean["undervoltage"] = "UNKNOWN" if undervoltage is None else undervoltage
    clean["xruns"] = "UNKNOWN" if xruns is None else xruns
    clean["auto_play"] = False
    return clean
