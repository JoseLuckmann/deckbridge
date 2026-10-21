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

## Pin verificado em 2026-10-21

Não é uma decisão nova. É o identificador que esta decisão passou a carregar.

- Tag `2.5.6`, release estável, `prerelease=false`. A nota do release diz que a 2.5.5 foi pulada. Não é beta.
- Commit `3ebac449e7e5fe2a0186596657696e87ce8b0e56` (`Release 2.5.6`, 2026-03-25).
- Objeto da tag `94ccc34f91d37dec62d0d70c16fac38dc8af677f`, com assinatura PGP presente. A chave não foi importada aqui, então a assinatura **não** foi verificada.
- Arquivo gerado pelo GitHub `archive/refs/tags/2.5.6.tar.gz`, 40343561 bytes, SHA-256 observado `9cfc9025d50d2511767ee52a07b8854f75c581f0585197d043bc219fcf9f9050`. O release não publicou checksum próprio. O identificador estável é o commit. Detalhes em `third_party/mixxx/2.5.6/PIN.json`.
- 2.6 beta e 2.7 dev continuam fora da release.

## Política de fork

- A linha principal não é um fork do engine inteiro.
- Patch local só com ADR, em cima deste commit, isolável e com teste.
- StandaloneMixxx, TriMixxx, NauticMixxx e mixxx-pi-gen são referência. Código só entra com licença verificada e port deliberado.
- O caminho `docs/adr/001-upstream-base.md` citado pela T002 aponta para esta ADR.

## Fontes

https://mixxx.org/download/ ; https://manual.mixxx.org/2.7/en/hardware/controllers/pioneer_ddj_400 ; https://github.com/mixxxdj/mixxx ; https://github.com/mixxxdj/mixxx/releases/tag/2.5.6
