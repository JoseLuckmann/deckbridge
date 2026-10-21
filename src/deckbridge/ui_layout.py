"""Layout das quatro telas. Não abre o Mixxx e não usa webview."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

SCREENS = ("PERFORMANCE", "BROWSE", "STATUS", "ERROR")
RESOLUTIONS = ((800, 480), (1024, 600), (1366, 768), (2000, 1200))


@dataclass(frozen=True)
class Rect:
    name: str
    x: int
    y: int
    w: int
    h: int


def layout(screen: str, width: int, height: int, waveforms: str = "full") -> list[Rect]:
    if screen not in SCREENS:
        raise ValueError(f"tela desconhecida: {screen}")
    if width < 320 or height < 240:
        raise ValueError("resolução pequena demais")
    margin = 8
    banner_h = max(28, height // 12)
    footer_h = max(24, height // 14)
    body_top = margin + banner_h + 4
    body_h = height - body_top - footer_h - margin - 4
    rects = [Rect("banner", margin, margin, width - 2 * margin, banner_h)]
    if screen == "PERFORMANCE":
        rects.extend(_decks(margin, body_top, body_h, width, waveforms))
    elif screen == "BROWSE":
        tree_w = max(140, width // 4)
        rects.append(Rect("tree", margin, body_top, tree_w, body_h))
        rects.append(
            Rect("tracks", margin + tree_w + 8, body_top, width - 2 * margin - tree_w - 8, body_h)
        )
    elif screen == "STATUS":
        rects.append(Rect("status_panel", margin, body_top, width - 2 * margin, body_h))
    else:
        rects.append(Rect("recovery", margin, body_top, width - 2 * margin, body_h))
    rects.append(Rect("footer", margin, height - margin - footer_h, width - 2 * margin, footer_h))
    return rects


def _decks(margin: int, body_top: int, body_h: int, width: int, waveforms: str) -> list[Rect]:
    gap = 8
    deck_w = (width - 2 * margin - gap) // 2
    rects = [
        Rect("deck_a", margin, body_top, deck_w, body_h),
        Rect("deck_b", margin + deck_w + gap, body_top, deck_w, body_h),
    ]
    line = 22
    inner_y = body_top + 8
    for prefix, origin in (("a", margin + 8), ("b", margin + deck_w + gap + 8)):
        inner_w = deck_w - 16
        rects.append(Rect(f"title_{prefix}", origin, inner_y, inner_w, line))
        rects.append(Rect(f"bpm_{prefix}", origin, inner_y + line, inner_w // 2, line))
        rects.append(
            Rect(f"time_{prefix}", origin + inner_w // 2, inner_y + line, inner_w - inner_w // 2, line)
        )
        rects.append(Rect(f"play_{prefix}", origin, inner_y + 2 * line, inner_w, line))
        cues_y = body_top + body_h - 28
        if waveforms != "off":
            wave_top = inner_y + 3 * line
            wave_h = cues_y - wave_top - 4
            if wave_h >= 16:
                rects.append(Rect(f"waveform_{prefix}", origin, wave_top, inner_w, wave_h))
        rects.append(Rect(f"cues_{prefix}", origin, cues_y, inner_w, 20))
    return rects


def layout_problems(rects: list[Rect], width: int, height: int) -> list[str]:
    issues: list[str] = []
    for rect in rects:
        if rect.w <= 0 or rect.h <= 0:
            issues.append(rect.name)
        if rect.x < 0 or rect.y < 0 or rect.x + rect.w > width or rect.y + rect.h > height:
            issues.append(rect.name)
    names = {rect.name: rect for rect in rects}
    if "deck_a" in names and "deck_b" in names and _overlaps(names["deck_a"], names["deck_b"]):
        issues.append("deck_overlap")
    return issues


def _overlaps(left: Rect, right: Rect) -> bool:
    return (
        left.x < right.x + right.w
        and right.x < left.x + left.w
        and left.y < right.y + right.h
        and right.y < left.y + left.h
    )


def render_svg(screen: str, width: int, height: int, waveforms: str = "full") -> str:
    rects = layout(screen, width, height, waveforms)
    font = 13 if width < 1000 else 18 if width < 1600 else 26
    labels = {
        "banner": "Fonte sintética  ·  áudio por confirmar  ·  DDJ por confirmar",
        "footer": "BROWSE gira a lista  ·  LOAD A/B carrega  ·  gravação desligada",
        "title_a": "Deck A  ·  Faixa sintética",
        "title_b": "Deck B  ·  Faixa sintética",
        "bpm_a": "BPM 120.00",
        "bpm_b": "BPM 120.00",
        "time_a": "00:01 / 00:01",
        "time_b": "00:00 / 00:01",
        "play_a": "PLAY",
        "play_b": "PAUSE",
        "waveform_a": "waveform",
        "waveform_b": "waveform",
        "cues_a": "cue  ·  loop",
        "cues_b": "cue  ·  loop",
        "tree": "Fonte",
        "tracks": "Faixa / artista / BPM",
        "status_panel": "STATUS  ·  temperatura UNKNOWN  ·  x-runs UNKNOWN",
        "recovery": "O áudio parou. Nada volta a tocar sozinho.",
        "deck_a": "",
        "deck_b": "",
    }
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        "<rect width=\"100%\" height=\"100%\" fill=\"#121418\"/>",
    ]
    for rect in rects:
        parts.append(
            f'<rect x="{rect.x}" y="{rect.y}" width="{rect.w}" height="{rect.h}" '
            'fill="#1c2430" stroke="#8aa0b8" stroke-width="1"/>'
        )
        text = labels.get(rect.name, "")
        if text:
            baseline = min(rect.y + font + 4, rect.y + rect.h - 4)
            parts.append(
                f'<text x="{rect.x + 8}" y="{baseline}" fill="#e8eef6" '
                f'font-family="sans-serif" font-size="{font}">{_xml(text)}</text>'
            )
    parts.append("</svg>")
    svg = "\n".join(parts)
    if "<script" in svg.lower():
        raise RuntimeError("svg não pode levar script")
    return svg


def _xml(text: str) -> str:
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def write_screenshots(dest: Path) -> list[Path]:
    dest.mkdir(parents=True, exist_ok=True)
    written: list[Path] = []
    for screen in SCREENS:
        for width, height in RESOLUTIONS:
            name = f"{screen.lower()}-{width}x{height}.svg"
            path = dest / name
            path.write_text(render_svg(screen, width, height, "full"), encoding="utf-8")
            written.append(path)
    low = dest / "performance-800x480-waveform-off.svg"
    low.write_text(render_svg("PERFORMANCE", 800, 480, "off"), encoding="utf-8")
    written.append(low)
    return written
