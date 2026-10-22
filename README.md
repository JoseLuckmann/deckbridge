# DeckBridge OS — Spec Kit para um sistema DJ standalone

**Estado:** especificação de engenharia inicial (não é software implementado)  
**Data:** 2026-10-21  
**Idioma:** pt-BR  
**Hardware-alvo:** Pioneer DJ DDJ-400 + Raspberry Pi de vários modelos (inclusive antigos) e, de preferência, Linux x86/ARM genérico. Nenhum modelo é obrigatório (OD-01, 21/10/2026). Tela de referência: 7 polegadas, 800x480 ou 1024x600, layout adaptável (OD-02). Áudio prioritário: MP3 e WAV (OD-03).  
**Motor:** Mixxx 2.5.6 estável (baseline a validar no repositório)  
**Responsável pela visão:** proprietário do projeto (decisões pendentes em `spec.md`)

## Objetivo

Transformar uma DDJ-400 em um equipamento **standalone**, que inicializa diretamente em uma interface para DJ, reproduz e pré-escuta dois decks via interface de áudio da própria controladora, navega em USBs exportados pelo rekordbox e permite desenvolvimento futuro de recomendações, registro de sets e DJ Trainer. Offline primeiro. O sistema **não é** uma tentativa de fazer o app rekordbox Android reconhecer a DDJ-400.

## Leia nesta ordem (agentes)

1. `AGENTS.md` — instruções operacionais obrigatórias para os agentes.
2. `.specify/memory/constitution.md` — princípios/limites invioláveis.
3. `specs/001-ddj400-standalone/spec.md` — requisitos, user stories, fluxos e gates.
4. `specs/001-ddj400-standalone/research.md` — fontes, pressupostos e investigações obrigatórias.
5. `specs/001-ddj400-standalone/plan.md` — arquitetura, interfaces e estratégia de implementação.
6. `specs/001-ddj400-standalone/data-model.md` — entidades e contratos.
7. `specs/001-ddj400-standalone/contracts/openapi.yaml` — API futura local (fase 0.3, não exigida para POC).
8. `specs/001-ddj400-standalone/tasks.md` — plano de execução com dependências e evidências.
9. `specs/001-ddj400-standalone/quickstart.md` — POC, builds, testes e rollout.
10. `specs/001-ddj400-standalone/checklists/requirements.md` — verificação de qualidade da especificação.

`ESPECIFICACAO_COMPLETA.md` concatena os documentos acima como **arquivo único para fornecer a um agente**. Em caso de divergência, os arquivos separados acima prevalecem; a versão consolidada é um artefato gerado.

## GitHub Spec Kit

O repositório está **estruturado no estilo** GitHub Spec Kit (`.specify/memory/constitution.md`, `specs/001-ddj400-standalone/spec.md`, `plan.md`, `tasks.md`). Não se afirma que o CLI `specify` tenha sido executado nem que esta pasta contenha todos os scripts oficiais. Para instalar o workflow oficial:

```bash
uv tool install specify-cli
specify init --here --integration codex
```

Verifique opções disponíveis com `specify init --help` na versão instalada antes de executar; inicialize em repositório/cópia limpa e preserve esta especificação. O nome `--integration` e as opções suportadas podem evoluir. O CLI oficial fornece comandos como `/speckit.constitution`, `/speckit.specify`, `/speckit.plan`, `/speckit.tasks`, `/speckit.implement`; a sintaxe varia por agente. **Não gere de novo esta especificação de modo a sobrescrever decisões, requisitos ou evidências.**

## Referências centrais (consultadas em 2026-10-21)

- Mixxx: https://github.com/mixxxdj/mixxx — motor upstream.
- Mixxx 2.5.6 stable: https://mixxx.org/download/ — baseline estável de produção; 2.6 beta/2.7 dev não são referência de performance em palco.
- DDJ-400 manual Mixxx: https://manual.mixxx.org/2.7/en/hardware/controllers/pioneer_ddj_400 — USB Audio/MIDI class-compliant; master 1–2, headphones 3–4; firmware requer verificação.
- Mixxx rekordbox manual: https://manual.mixxx.org/2.5/en_gb/chapters/library — importação a partir do USB exportado; limitações de mapeamento de cues.
- StandaloneMixxx: https://github.com/ghztomash/StandaloneMixxx — provisionamento, autostart, Pi 4B/5, skin; configuração original focaliza DDJ-FLX2/FLX4, **não garante DDJ-400**.
- TriMixxx: https://github.com/usr-ein/TriMixxx — estudo de UX, hotplug, patched Mixxx e hardware CDJ custom; **não é um fork pronto para DDJ-400**.
- NauticMixxx: https://github.com/nauticsoftware/NauticMixxx — referência de UI RX3; releases principais macOS/Windows, suporte DDJ-400 depende de preset; Linux/Pi experimental.
- mixxx-pi-gen: https://github.com/fayaaz/mixxx-pi-gen — imagem Pi com Sway/Mixxx.
- pyrekordbox: https://github.com/dylanljones/pyrekordbox — pesquisa e testes ANLZ/Device Library Plus; MIT.
- libdjinterop: https://github.com/xsco/libdjinterop — leitura OneLibrary, com limitações (SQLCipher/passphrase, side-effects SQLite WAL, ANLZ fora do DB).
- MIDI scripting Mixxx: https://github.com/mixxxdj/mixxx/wiki/midi-scripting
- Spec Kit: https://github.com/github/spec-kit
- Limites de alimentação Pi: https://www.raspberrypi.com/documentation/hardware/raspberrypi/power.html

## Política de afirmações

Termos como **comprovado** só podem constar em relatórios do projeto com logs, fotos ou testes executados no hardware-alvo. Recurso mencionado em projeto externo é **referência**, não prova de compatibilidade no nosso sistema.

## Licenças

Mixxx é GPL-2.0-or-later; o pacote composto deve manter os respectivos avisos de licença de todo código incorporado. TriMixxx e NauticMixxx publicam GPL-3.0 para seus componentes; pyrekordbox MIT; libdjinterop LGPL-3.0-or-later. **Antes de copiar qualquer código**, revisar licenças e compatibilidade de todos os arquivos e dependências. O nome provisório DeckBridge OS é independente de Pioneer/AlphaTheta e rekordbox.

### Regenerar o arquivo único após alterações

```bash
python3 tools/build_spec_bundle.py
```

O comando revalida IDs T001–T074, FR/NFR e gera `ESPECIFICACAO_COMPLETA.md`, `manifest.json` e o ZIP de entrega. Ele não executa código de aplicação. O script ainda não está neste repositório. Os IDs T075–T079 (simulação) foram acrescentados depois e ficam fora desse gerador ausente.

Simulação não é prova no hardware. O roteiro único para a DDJ-400 e o Raspberry, no fim do projeto, está em `docs/hil/roteiro-validacao-final.md`.

O alvo 1, desde 21/10/2026, é o app Android em `android/`. O Raspberry é o alvo 2. Como instalar a prova A0 no tablet está em `docs/android/prova-a0.md`. A leitura do pendrive rekordbox e os dois decks estão em `docs/android/biblioteca-rekordbox.md`. Ouvir master e fone nesse app ainda não foi feito aqui. Prints do emulador não substituem o tablet.

A tela 0.12.1 mede um corpus CC BY 3.0 fora do git (`tools/fetch_real_corpus.py`, `docs/testing/real-music-corpus.md`) e lê uma pasta de exportação sem gravar nela (`tools/inspect_usb.py`). O export em `/opt/cursor/artifacts/real-music-usb` é deste app, não do rekordbox. A escuta continua `BLOCKED_HARDWARE`.

## Ferramentas da fase 0

```bash
python3 -m pytest
python3 -m deckbridge.hil_collect
python3 -m deckbridge.mixxx_smoke
python3 -m deckbridge.release_bundle --out dist
```

O que exige a DDJ-400 ou o Raspberry está em `docs/hil/checklist-fase0.md`. Nada disso foi marcado como comprovado.
