"""Perfil de execução a partir de RAM e núcleos.

Os limiares são política (OD-01, 21/10/2026), não medida de desempenho.
O nome do modelo não entra na conta. Um tablet de 4 GB e 8 núcleos,
como a classe do Galaxy Tab S6 Lite, cai no tier alto por esses números.
Isso não mede o aparelho e não confirma o modelo.
"""

from __future__ import annotations

from dataclasses import dataclass

UNKNOWN = "UNKNOWN"

RAM_LOW_MB = 1024
RAM_MID_MB = 2048
CORES_LOW = 2
CORES_MID = 4


@dataclass(frozen=True)
class HostFacts:
    arch: str
    cpu_cores: int | None
    ram_mb: int | None
    model: str


@dataclass(frozen=True)
class RuntimeProfile:
    tier: str
    waveforms: str
    heavy_effects: str
    reason: str

    def as_dict(self) -> dict[str, str]:
        return {
            "tier": self.tier,
            "waveforms": self.waveforms,
            "heavy_effects": self.heavy_effects,
            "reason": self.reason,
        }


def classify(facts: HostFacts) -> RuntimeProfile:
    if facts.cpu_cores is None or facts.ram_mb is None:
        return RuntimeProfile(
            tier=UNKNOWN,
            waveforms="simple",
            heavy_effects="off",
            reason="RAM ou núcleos ausentes; degradação segura, sem assumir máquina forte",
        )
    if facts.ram_mb < RAM_LOW_MB or facts.cpu_cores < CORES_LOW:
        return RuntimeProfile(
            tier="low",
            waveforms="off",
            heavy_effects="off",
            reason="RAM abaixo de 1024 MB ou menos de 2 núcleos",
        )
    if facts.ram_mb < RAM_MID_MB or facts.cpu_cores < CORES_MID:
        return RuntimeProfile(
            tier="mid",
            waveforms="simple",
            heavy_effects="off",
            reason="RAM abaixo de 2048 MB ou menos de 4 núcleos",
        )
    return RuntimeProfile(
        tier="high",
        waveforms="full",
        heavy_effects="on",
        reason="RAM e núcleos acima dos limiares de política; desempenho ainda não medido",
    )
