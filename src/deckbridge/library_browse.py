"""Lista sintética e carga com espera curta. Não guarda caminho de arquivo."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Track:
    title: str
    artist: str
    bpm: str
    duration: str
    format_kind: str

    def as_dict(self) -> dict[str, str]:
        return {
            "title": self.title,
            "artist": self.artist,
            "bpm": self.bpm,
            "duration": self.duration,
            "format_kind": self.format_kind,
        }


def synthetic_tracks(count: int = 100) -> list[Track]:
    tracks: list[Track] = []
    for index in range(count):
        kind = "mp3" if index % 2 == 0 else "wav"
        tracks.append(
            Track(
                title=f"Faixa {index + 1:03d}",
                artist="Sintética",
                bpm=f"{100 + (index % 40):.2f}",
                duration="00:01",
                format_kind=kind,
            )
        )
    return tracks


def short_source_code(source_id: str) -> str:
    cleaned = "".join(char for char in source_id if char.isalnum())
    if len(cleaned) < 4:
        raise ValueError("identificador curto demais")
    return cleaned[:4].upper()


def display_source(label: str, source_id: str, colliding: bool) -> str:
    if not colliding:
        return label
    code = short_source_code(source_id)
    shown = f"{label} {code}"
    if source_id in shown:
        raise ValueError("rótulo não pode repetir o identificador inteiro")
    return shown


class LibraryBrowser:
    def __init__(self, tracks: list[Track], debounce_ms: int = 200) -> None:
        if not tracks:
            raise ValueError("lista vazia")
        self.tracks = list(tracks)
        self.debounce_ms = debounce_ms
        self.index = 0
        self.loaded: dict[str, Track | None] = {"A": None, "B": None}
        self.load_count = {"A": 0, "B": 0}
        self._pending_index = {"A": None, "B": None}
        self._pending_at: dict[str, int | None] = {"A": None, "B": None}

    def move(self, steps: int) -> Track:
        self.index = max(0, min(len(self.tracks) - 1, self.index + steps))
        return self.tracks[self.index]

    def request_load(self, deck: str, now_ms: int) -> None:
        if deck not in self.loaded:
            raise ValueError("deck desconhecido")
        self._pending_index[deck] = self.index
        self._pending_at[deck] = now_ms

    def settle(self, now_ms: int) -> None:
        for deck in ("A", "B"):
            started = self._pending_at[deck]
            if started is None or now_ms - started < self.debounce_ms:
                continue
            chosen = self._pending_index[deck]
            if chosen is None:
                continue
            self.loaded[deck] = self.tracks[chosen]
            self.load_count[deck] += 1
            self._pending_at[deck] = None
            self._pending_index[deck] = None
