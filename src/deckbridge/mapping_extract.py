"""Extrai controles do mapping DDJ-400 pinado. Não completa buracos."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
XML_PATH = REPO / "third_party/mixxx/2.5.6/res/controllers/Pioneer-DDJ-400.midi.xml"
JS_PATH = REPO / "third_party/mixxx/2.5.6/res/controllers/Pioneer-DDJ-400-script.js"
PIN_PATH = REPO / "third_party/mixxx/2.5.6/PIN.json"


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _text(node: ET.Element, tag: str) -> str:
    child = node.find(tag)
    if child is None or child.text is None:
        return ""
    return child.text.strip()


def parse_xml(path: Path) -> dict[str, list[dict[str, str]]]:
    root = ET.parse(path).getroot()
    controls: list[dict[str, str]] = []
    for node in root.findall("./controller/controls/control"):
        options = node.find("options")
        option_names = [] if options is None else [item.tag for item in list(options)]
        controls.append(
            {
                "description": _text(node, "description"),
                "group": _text(node, "group"),
                "key": _text(node, "key"),
                "status": _text(node, "status"),
                "midino": _text(node, "midino"),
                "options": ",".join(option_names),
            }
        )
    outputs: list[dict[str, str]] = []
    for node in root.findall("./controller/outputs/output"):
        outputs.append(
            {
                "group": _text(node, "group"),
                "key": _text(node, "key"),
                "status": _text(node, "status"),
                "midino": _text(node, "midino"),
                "on": _text(node, "on"),
                "minimum": _text(node, "minimum"),
            }
        )
    return {"controls": controls, "outputs": outputs}


def parse_lights(script: str) -> list[dict[str, str]]:
    match = re.search(r"PioneerDDJ400\.lights\s*=\s*\{", script)
    if not match:
        return []
    start = match.end()
    depth = 1
    end = None
    for index, char in enumerate(script[start:], start):
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                end = index
                break
    if end is None:
        return []
    body = script[start:end]
    lights: list[dict[str, str]] = []
    path: list[str] = ["lights"]
    ident = ""
    index = 0
    while index < len(body):
        char = body[index]
        if char.isalpha() or char == "_":
            begin = index
            while index < len(body) and (body[index].isalnum() or body[index] == "_"):
                index += 1
            ident = body[begin:index]
            continue
        if char == "{":
            path.append(ident or "?")
            ident = ""
            index += 1
            continue
        if char == "}":
            if path:
                path.pop()
            ident = ""
            index += 1
            continue
        if char == ":" and ident in {"status", "data1"}:
            hex_match = re.match(r"\s*(0x[0-9a-fA-F]+)", body[index + 1 :])
            if hex_match and path:
                key = ".".join(path)
                current = next((item for item in lights if item["name"] == key), None)
                if current is None:
                    current = {"name": key, "status": "", "data1": ""}
                    lights.append(current)
                current[ident] = hex_match.group(1)
            ident = ""
        index += 1
    return [item for item in lights if item["status"] and item["data1"]]


def parse_hooks(script: str) -> list[str]:
    names = sorted(set(re.findall(r"PioneerDDJ400\.(\w+)\s*=\s*function", script)))
    return names


def build_document() -> tuple[dict[str, object], str]:
    pin = json.loads(PIN_PATH.read_text(encoding="utf-8"))
    parsed = parse_xml(XML_PATH)
    script = JS_PATH.read_text(encoding="utf-8")
    lights = parse_lights(script)
    hooks = parse_hooks(script)
    data: dict[str, object] = {
        "commit_sha": pin["commit_sha"],
        "tag": pin["tag"],
        "xml_sha256": _sha256(XML_PATH),
        "js_sha256": _sha256(JS_PATH),
        "control_count": len(parsed["controls"]),
        "output_count": len(parsed["outputs"]),
        "light_count": len(lights),
        "hook_count": len(hooks),
        "controls": parsed["controls"],
        "outputs": parsed["outputs"],
        "lights": lights,
        "hooks": hooks,
        "hardware_capture": "BLOCKED_HARDWARE",
    }
    lines = [
        "# Matriz de controles DDJ-400 — Mixxx 2.5.6",
        "",
        "Extraída dos arquivos pinados. Não é captura da controladora.",
        "A correlação com o aparelho real é a tarefa T009 e está `BLOCKED_HARDWARE`.",
        "",
        f"- Tag: `{pin['tag']}`",
        f"- Commit: `{pin['commit_sha']}`",
        f"- XML sha256: `{data['xml_sha256']}`",
        f"- JS sha256: `{data['js_sha256']}`",
        f"- Controles de entrada no XML: {data['control_count']}",
        f"- Saídas LED no XML: {data['output_count']}",
        f"- LEDs declarados em `PioneerDDJ400.lights`: {data['light_count']}",
        f"- Funções JS: {data['hook_count']}",
        "",
        "Bytes abaixo são os que estão no arquivo. Onde o arquivo não traz status ou nota, a célula fica vazia. Nada foi completado por semelhança.",
        "",
        "## Entradas (XML `<control>`)",
        "",
        "| Descrição | Grupo | Key | Status | Nota | Opções |",
        "|---|---|---|---|---|---|",
    ]
    for item in parsed["controls"]:
        lines.append(
            "| {description} | {group} | {key} | {status} | {midino} | {options} |".format(
                **{key: str(value).replace("|", "/") for key, value in item.items()}
            )
        )
    lines.extend(
        [
            "",
            "## Saídas LED (XML `<output>`)",
            "",
            "| Grupo | Key | Status | Nota | On | Mínimo |",
            "|---|---|---|---|---|---|",
        ]
    )
    for item in parsed["outputs"]:
        lines.append(
            "| {group} | {key} | {status} | {midino} | {on} | {minimum} |".format(**item)
        )
    lines.extend(
        [
            "",
            "## LEDs no script (`PioneerDDJ400.lights`)",
            "",
            "Iguais ou não aos `<output>` do XML: comparação de arquivo, não de hardware.",
            "",
            "| Nome | Status | Data1 |",
            "|---|---|---|",
        ]
    )
    for item in lights:
        lines.append(f"| {item['name']} | {item['status']} | {item['data1']} |")
    lines.extend(["", "## Funções JS", ""])
    for name in hooks:
        lines.append(f"- `PioneerDDJ400.{name}`")
    lines.append("")
    return data, "\n".join(lines)


def android_map_document() -> dict[str, object]:
    """Tabela de entrada para o app Android. Só bytes que o XML pinado traz."""
    pin = json.loads(PIN_PATH.read_text(encoding="utf-8"))
    parsed = parse_xml(XML_PATH)
    controls = []
    for item in parsed["controls"]:
        status = item["status"].strip()
        midino = item["midino"].strip()
        if not status or not midino:
            continue
        controls.append(
            {
                "status": int(status, 16),
                "midino": int(midino, 16),
                "group": item["group"],
                "key": item["key"],
                "description": item["description"],
            }
        )
    return {
        "tag": pin["tag"],
        "commit": pin["commit_sha"],
        "hardware_capture": "BLOCKED_HARDWARE",
        "control_count": len(controls),
        "controls": controls,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Gera a matriz DDJ-400 a partir do mapping pinado")
    parser.add_argument("--json-out", type=Path)
    parser.add_argument("--md-out", type=Path)
    args = parser.parse_args(argv)
    data, markdown = build_document()
    if args.json_out:
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    if args.md_out:
        args.md_out.parent.mkdir(parents=True, exist_ok=True)
        args.md_out.write_text(markdown, encoding="utf-8")
    if not args.json_out and not args.md_out:
        print(markdown)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
