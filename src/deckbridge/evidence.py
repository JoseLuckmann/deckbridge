"""O que uma simulação pode e não pode concluir.

SIMULATED nunca vira PASS de tarefa que exige a controladora ou o Raspberry,
e nunca fecha o gate G0 ou G1.
"""

from __future__ import annotations

HIL_TASKS = frozenset(
    {
        "T005",
        "T009",
        "T010",
        "T011",
        "T012",
        "T014",
        "T016",
        "T018",
        "T031",
        "T033",
        "T034",
        "T035",
        "T038",
        "T039",
    }
)
GATES = frozenset({"G0", "G1"})


def admits_pass(task_id: str, evidence_kind: str) -> bool:
    if evidence_kind == "SIMULATED":
        return False
    if task_id in HIL_TASKS and evidence_kind != "HIL":
        return False
    if evidence_kind == "HIL":
        return True
    return evidence_kind == "SOFTWARE" and task_id not in HIL_TASKS


def gate_closed(gate: str, evidence_kind: str) -> bool:
    if gate not in GATES:
        raise ValueError(f"gate desconhecido: {gate}")
    return False if evidence_kind == "SIMULATED" else evidence_kind == "HIL"
