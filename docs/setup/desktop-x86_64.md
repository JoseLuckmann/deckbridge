# Ambiente desktop x86_64

Isto prepara um Linux comum para desenvolver sem Raspberry. Não prova a DDJ-400.

## O que este ambiente já faz

```bash
python3 -m pytest
python3 -m deckbridge.mixxx_smoke
python3 -m deckbridge.hil_collect --out /tmp/hil.json
```

O smoke procura o comando `mixxx`. Se não existir, o resultado é `BLOCKED` e a versão fica `UNKNOWN`.

## Mixxx 2.5.6

A baseline é o commit `3ebac449e7e5fe2a0186596657696e87ce8b0e56`. Um pacote da distribuição com outro número de versão não substitui essa baseline.

1. Baixar `https://github.com/mixxxdj/mixxx/archive/refs/tags/2.5.6.tar.gz`.
2. Conferir o SHA-256 com o valor de `third_party/mixxx/2.5.6/PIN.json`. Se não bater, parar. Esse hash foi observado em 21/10/2026; o identificador estável é o commit.
3. Seguir o README de build do próprio Mixxx dessa tag. Não usar instalação por `curl | sh`.
4. Rodar de novo `python3 -m deckbridge.mixxx_smoke`. `matches_pin` só fica verdadeiro se o texto da versão contiver `2.5.6`.
5. Tocar um arquivo de `fixtures/audio/` é um teste manual. Nesta rodada isso não foi executado.

OD-01: este desktop é um alvo de verdade, não só um lugar de compilação. OD-03: quando for medir desempenho, usar primeiro o MP3 e o WAV das fixtures.
