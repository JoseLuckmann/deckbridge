# ADR-0003: controles locais e operação offline como modo padrão

**Status:** ACCEPTED | **Data:** 2026-10-21

## Contexto

O produto deve operar durante apresentações sem notebook, celular ou rede; a superfície da DDJ-400 é suficiente para controle musical. Serviços extras podem criar interferência no áudio e vulnerabilidades.

## Decisão

DeckBridge roda offline-first com user-space local; não exige login. PWA vem só após G2, desligada inicialmente, pareamento e sem comandos de mixer. Análises são processadas fora da thread de áudio com throttling/desativação em hardware com poucos recursos.

## Consequências

- Prós: confiabilidade e privacidade, menos recursos consumidos.
- Contras: sincronização de conteúdo, IA remota e atualização automática não são recursos iniciais.

## Fontes

https://github.com/ghztomash/StandaloneMixxx ; https://github.com/fayaaz/mixxx-pi-gen
