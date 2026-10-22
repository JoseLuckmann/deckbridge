# ADR-0006: Constituição 1.1.0 — alvo 1 no tablet

**Status:** ACCEPTED | **Data:** 2026-10-22

## Contexto

A constituição 1.0.0 (21/10/2026) tratava o produto como Raspberry com Mixxx e chamava qualquer app móvel de secundário. No mesmo dia a OD-11 passou o app Android próprio para alvo 1. Até a 0.14.1 o código que existe é esse app. A frase antiga fazia um leitor novo recomeçar pelo Pi.

## Decisão

A constituição passa a 1.1.0.

- Alvo 1: app Android, tablet Galaxy Tab S6 Lite, ligado direto na DDJ-400. OD-11.
- Alvo 2: Raspberry Pi, Mixxx 2.5.6, quando o aparelho existir. OD-01.
- Companion (PWA ou telefone) continua opcional. Não é o host.
- USB do rekordbox somente leitura nos dois alvos. ADR-0002 e OD-23.
- Prova antes de promessa não muda. Simulação não fecha o roteiro de hardware.

## Consequências

`AGENTS.md`, `spec.md`, `plan.md` e `README.md` passam a dizer o mesmo. A trilha T001–T074 do Pi não é apagada e não está feita no aparelho. Os gates G0 e G1 continuam abertos.
