# DeckBridge OS — Spec Kit para um sistema DJ standalone

**Estado:** app Android 0.14.1 em simulação. A escuta na DDJ-400 continua `BLOCKED_HARDWARE`. A página única é `STATUS.md`.  
**Data:** 2026-10-22  
**Idioma:** pt-BR  
**Alvo 1:** Galaxy Tab S6 Lite ligado direto na DDJ-400 (OD-11). Tela de referência 800×480, 1024×600 e 2000×1200 (OD-02, OD-17).  
**Alvo 2:** Raspberry Pi de vários modelos e Linux x86/ARM genérico, com Mixxx 2.5.6 (OD-01). Ainda não é um aparelho.  
**Áudio prioritário:** MP3 e WAV (OD-03). USB do rekordbox somente leitura (OD-23).  
**Responsável pela visão:** proprietário do projeto (decisões em `spec.md` §14)

## Objetivo

Transformar uma DDJ-400 em um equipamento **standalone**. No alvo 1 o tablet inicializa o app, reproduz e pré-escuta dois decks pela interface de áudio da controladora e navega em USBs exportados pelo rekordbox. Offline primeiro. O sistema **não é** o app rekordbox para Android. O alvo 2, Raspberry com Mixxx, continua especificado e não foi montado.

## Como continuar o projeto

O histórico inteiro vai no ficheiro `deckbridge-os.bundle` (todos os ramos, todas as tags).

```bash
git clone deckbridge-os.bundle deckbridge-os
cd deckbridge-os
```

O clone fica em `main`. Nesta entrega só existe esse ramo, sem tags.

Para o app Android:

- JDK 17 ou mais recente. O bytecode do app é 17. A build da 0.14.1 usou OpenJDK 21.
- Android SDK com a plataforma `android-35`, build-tools `35.0.0`, NDK `27.2.12479018` e CMake `3.22.1`. A variável `ANDROID_HOME` aponta para esse SDK.
- Gradle `8.11.1` pelo wrapper `android/gradlew` (o jar está no repositório). Android Gradle Plugin `8.7.3`. Kotlin `2.0.21`.
- `g++` com C++17, para os testes do motor no host.
- Python 3, para a trilha `pytest`.

```bash
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
cd android
./gradlew :app:testMultiDebugUnitTest :app:assembleArm64Release :app:assembleMultiDebug
```

`--offline` só serve se o cache do Gradle e as dependências do SDK já estiverem na máquina. O APK do tablet sai em `app/build/outputs/apk/arm64/release/`. O de três ABIs sai em `app/build/outputs/apk/multi/debug/`. Os dois levam assinatura de debug.

Onde está o resto:

- `STATUS.md` — o que a 0.14.1 faz, o que falta e as decisões.
- `specs/001-ddj400-standalone/spec.md` — requisitos.
- `specs/001-ddj400-standalone/plan.md` — plano. O diagrama do Mixxx é o alvo 2.
- `specs/001-ddj400-standalone/tasks.md` — cada tarefa com estado.
- `docs/hil/roteiro-validacao-final.md` — roteiro no hardware. Os passos 13 a 24 continuam por executar. Simulação não os fecha.
- `docs/hil/checklist-fase0.md` e `docs/hil/checklist-fase1.md` — as listas das duas fases.

O USB do rekordbox é só leitura. Busca por voz (A053) e quatro decks (A054) continuam adiados.

## Leia nesta ordem (agentes)

1. `STATUS.md` — versão, o que funciona, o que falta, decisões e como instalar.
2. `AGENTS.md` — instruções operacionais obrigatórias para os agentes.
3. `.specify/memory/constitution.md` — princípios/limites invioláveis.
4. `specs/001-ddj400-standalone/spec.md` — requisitos, user stories, fluxos e gates.
5. `specs/001-ddj400-standalone/research.md` — fontes, pressupostos e investigações obrigatórias.
6. `specs/001-ddj400-standalone/plan.md` — arquitetura, interfaces e estratégia de implementação.
7. `specs/001-ddj400-standalone/data-model.md` — entidades e contratos.
8. `specs/001-ddj400-standalone/contracts/openapi.yaml` — API futura local (fase 0.3, não exigida para POC).
9. `specs/001-ddj400-standalone/tasks.md` — plano de execução com dependências e evidências.
10. `specs/001-ddj400-standalone/quickstart.md` — POC, builds, testes e rollout.
11. `specs/001-ddj400-standalone/checklists/requirements.md` — verificação de qualidade da especificação.

`ESPECIFICACAO_COMPLETA.md` concatena os documentos acima como **arquivo único para fornecer a um agente**. A edição 1.1.0 foi montada em 22/10/2026. Em caso de divergência, os arquivos separados acima prevalecem. O script gerador continua fora do repositório.

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

A tela 0.12.1 mede um corpus CC BY 3.0 fora do git (`tools/fetch_real_corpus.py`, `docs/testing/real-music-corpus.md`) e lê uma pasta de exportação sem gravar nela (`tools/inspect_usb.py`). O export em `/opt/cursor/artifacts/real-music-usb` é deste app, não do rekordbox. A tela 0.12.2 mede BPM e tom contra o GiantSteps (`tools/fetch_ground_truth.py`, `docs/testing/ground-truth-corpus.md`) e analisa uma pasta de MP3/WAV sem export do rekordbox. A tela 0.13.0 acrescenta frases, cues automáticos, MATCHING, SUGGEST, medidor de fase e auto gain. A tela 0.13.1 troca o tom: CQT e o perfil EDMA publicado, MIREX 0,586 nas 58 faixas GiantSteps (0,519 noutras 190). O mix de demonstração dos efeitos, feito no motor do host e não no tablet, está em `docs/testing/demo-mix.md`. A tela 0.14.0 suaviza o DSP e o de/para com os manuais está em `docs/research/fx-de-para.md`; a mix musical é `docs/testing/demo-mix-v2.md`. A tela 0.14.1 troca o drum build sintetizado por one-shots CC0 na grade do deck (`docs/licenses/drum-samples.md`, `docs/research/drum-grid.md`, `docs/testing/demo-mix-drums.md`). A escuta continua `BLOCKED_HARDWARE`. Busca por voz e quatro decks continuam por fazer.

## Ferramentas da fase 0

```bash
python3 -m pytest
python3 -m deckbridge.hil_collect
python3 -m deckbridge.mixxx_smoke
python3 -m deckbridge.release_bundle --out dist
```

O que exige a DDJ-400 ou o Raspberry está em `docs/hil/checklist-fase0.md`. Nada disso foi marcado como comprovado.
