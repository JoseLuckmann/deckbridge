"""Ações lógicas da UI ligadas só ao que o mapa pinado realmente declara."""

from __future__ import annotations

from dataclasses import dataclass

from deckbridge.mapping_extract import XML_PATH, parse_xml

LIBRARY_KEYS = {
    ("[Library]", "MoveVertical"): "BROWSE_MOVE",
    ("[Library]", "MoveFocusForward"): "BROWSE_ENTER",
    ("[Library]", "MoveFocusBackward"): "BROWSE_BACK",
    ("[Channel1]", "LoadSelectedTrack"): "LOAD_A",
    ("[Channel2]", "LoadSelectedTrack"): "LOAD_B",
}

SCREEN_ACTIONS = ("OPEN_BROWSE", "OPEN_STATUS", "BACK", "SAFE_SHUTDOWN")
PERFORMANCE_KEYS = {"play", "cue_default", "start_play", "reverseroll"}


@dataclass(frozen=True)
class Binding:
    action: str
    status: str
    midino: str
    group: str
    key: str
    description: str
    hardware_confirmed: bool
    note: str


def load_bindings(path=XML_PATH) -> list[Binding]:
    parsed = parse_xml(path)
    found: list[Binding] = []
    for control in parsed["controls"]:
        action = LIBRARY_KEYS.get((control["group"], control["key"]))
        if action is None:
            continue
        note = "bytes copiados do mapping pinado; a controladora física não confirmou"
        if control["key"] == "MoveVertical":
            note = "giro escolhe a faixa seguinte ou anterior; o sentido do byte fica para o hardware"
        if control["key"] in {"MoveFocusForward", "MoveFocusBackward"}:
            note = "move o cursor entre a lista e a árvore; a descrição do mapa não abre pasta"
        found.append(
            Binding(
                action=action,
                status=control["status"],
                midino=control["midino"],
                group=control["group"],
                key=control["key"],
                description=control["description"],
                hardware_confirmed=False,
                note=note,
            )
        )
    missing = set(LIBRARY_KEYS.values()) - {item.action for item in found}
    if missing:
        raise RuntimeError(f"mapa pinado sem ações esperadas: {sorted(missing)}")
    return found


def screen_assignments() -> dict[str, None]:
    return {action: None for action in SCREEN_ACTIONS}


def performance_controls_kept(path=XML_PATH) -> list[dict[str, str]]:
    parsed = parse_xml(path)
    kept = []
    for control in parsed["controls"]:
        if control["key"] in PERFORMANCE_KEYS:
            kept.append(control)
    if len(kept) < 4:
        raise RuntimeError("mapa pinado sem os controles de play/cue esperados")
    return kept


class Navigator:
    def __init__(self, bindings: list[Binding] | None = None) -> None:
        self.bindings = bindings if bindings is not None else load_bindings()
        self.screen = "PERFORMANCE"
        self.hardware_confirmed = False

    def binding_for(self, status: str, midino: str) -> Binding | None:
        for item in self.bindings:
            if item.status.lower() == status.lower() and item.midino.lower() == midino.lower():
                return item
        return None

    def apply_logical(self, action: str) -> str:
        if action == "OPEN_BROWSE":
            self.screen = "BROWSE"
        elif action == "OPEN_STATUS":
            self.screen = "STATUS"
        elif action == "BACK":
            self.screen = "PERFORMANCE"
        elif action == "SAFE_SHUTDOWN":
            self.screen = "STATUS"
        elif action in {"BROWSE_NEXT", "BROWSE_PREVIOUS", "BROWSE_ENTER", "BROWSE_BACK", "LOAD_A", "LOAD_B"}:
            return action
        else:
            raise ValueError(f"ação desconhecida: {action}")
        return self.screen
