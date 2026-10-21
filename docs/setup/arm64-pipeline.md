# Pipeline arm64

Decisão em `docs/adr/0004-build-pipeline.md`.

## Ferramentas DeckBridge

No checkout:

```bash
python3 -m deckbridge.release_bundle --out dist
```

Saem um `.deb` de arquitetura `all` e um `.tar.gz` sem código de máquina. O `dist/manifest.json` traz o SHA-256 de cada um. Os dois instalam em Raspberry Pi OS arm64 e em Linux x86. Nenhum dos dois contém o Mixxx.

## Mixxx no Raspberry Pi OS

1. No aparelho, ver a versão do pacote `mixxx` da distribuição.
2. Se for exatamente `2.5.6`, ela pode ser a baseline dessa máquina. Anotar o comando e a saída.
3. Se for outra versão, não usar como release. A build tem de partir do commit pinado. Essa compilação não foi feita aqui.
4. `pi-gen` não entra nesta fase. A imagem do cartão é a tarefa T041.

O arquivo arm64 do Mixxx, o boot no cartão e o som na DDJ-400 estão `BLOCKED_HARDWARE`.
