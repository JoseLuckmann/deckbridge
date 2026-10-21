# ADR-0004: pipeline arm64 por pacote apt e ferramentas em Python puro

**Status:** ACCEPTED | **Data:** 2026-10-21

## Contexto

A tarefa T007 pede um pipeline arm64 de Raspberry Pi OS e um artefato com hash. Neste ambiente só há Linux x86_64. Compilar o Mixxx inteiro aqui e chamar o resultado de binário arm64 seria uma afirmação sem prova.

## Decisão

- O Mixxx da release é o da tag `2.5.6`. No Raspberry Pi OS, instalar o pacote da distribuição **somente se a versão for exatamente 2.5.6**. Versão diferente não é a baseline.
- Se o pacote da distribuição não for 2.5.6, a build parte do commit pinado. Essa compilação não foi executada nesta rodada.
- `pi-gen` fica para a imagem do aparelho (T041), não para este pacote de ferramentas.
- As ferramentas DeckBridge desta fase são Python puro, pacote Debian `Architecture: all`, instalável em arm64 e em x86. O manifesto diz que não há código de máquina e que o binário arm64 do Mixxx não está incluído.
- OD-01: o mesmo pacote de ferramentas também serve em Linux x86/ARM genérico. Não há um modelo de Raspberry na receita.

## Alternativa descartada

Gerar um `.deb` arm64 do Mixxx por cross-compile neste x86 e publicar o hash como se o programa tivesse sido provado no Raspberry. Isso produziria um arquivo sem teste de boot nem de áudio.

## Consequências

- O artefato atual é reproduzível e honesto, e não substitui a imagem do aparelho.
- A prova de que o Mixxx 2.5.6 instala e toca em arm64 continua `BLOCKED_HARDWARE`.
