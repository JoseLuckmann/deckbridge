# ADR-0001: motor upstream Mixxx stable, em vez de reescrever engine

**Status:** ACCEPTED (baseline inicial) | **Data:** 2026-10-21

## Contexto

A DDJ-400 é USB MIDI/audio class compliant; Mixxx já oferece mapping DDJ-400, áudio 4ch, interface e leitura USB rekordbox. Reimplementação do áudio criaria risco/latência e custos sem valor incremental.

## Decisão

Base de produção é `mixxxdj/mixxx` release 2.5.6 fixado por tag+SHA. Primeiro executar pacote sem mudanças no core, com mapping/skin próprios. Usar StandaloneMixxx/mixxx-pi-gen como referências de appliance, NauticMixxx de UX e TriMixxx de estratégias, copiando código apenas com licença verificada e port deliberado.

## Consequências

- Prós: menos risco em áudio, compatibilidade madura, possibilidade de sincronização upstream.
- Contras: APIs de skin e importação limitam customização; patches futuros podem ser necessários.
- Gatilho para rever: requisito impossível via mapping/skin e POC do patch demonstra ausência de regressão.

## Fontes

https://mixxx.org/download/ ; https://manual.mixxx.org/2.7/en/hardware/controllers/pioneer_ddj_400 ; https://github.com/mixxxdj/mixxx
