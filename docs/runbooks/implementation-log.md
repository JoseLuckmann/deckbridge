# Implementation Log — registro de trabalho do agente

**Situação em 2026-10-21:** OD-01, OD-02 e OD-03 decididas no commit `717e488`. Fase 0 de software executada em seguida. Nenhuma prova com DDJ-400 ou Raspberry. A palavra comprovado não se aplica a áudio, boot ou compatibilidade de pendrive.

| Data UTC | Task | Estado | Commit/PR | Testes/Evidências | Bloqueios | Próxima ação |
|---|---|---|---|---|---|---|
| 2026-10-21 | SPEC | DOCUMENTED | n/a | conjunto de arquivos entregue | Pi/USB reais desconhecidos | iniciar T001 |
| 2026-10-21 | OD-01 OD-02 OD-03 | DECIDE | `717e488` | spec, plan, tasks, ui, testes, riscos | nenhuma | Fase 0 sem hardware |
| 2026-10-21 | T001 | PASS | `c984331` | árvore, CI, licença provisória, 18 testes | runner remoto de CI não observado | — |
| 2026-10-21 | T002 | PASS | `c984331` | pin em ADR-0001 e `PIN.json` | assinatura PGP não verificada | importar chave se for exigido |
| 2026-10-21 | T003 | PASS | `c984331` | `NOTICE.md`, `sbom.json`, `LICENSE` | licença do StandaloneMixxx é UNKNOWN; codecs não auditados arquivo a arquivo | não copiar esses códigos |
| 2026-10-21 | T004 | PASS | `c984331` | testes de UNKNOWN; `docs/benchmarks/T004-live-host.json` | coleta no aparelho é T005 | dono roda o script |
| 2026-10-21 | T005 | BLOCKED_HARDWARE | — | checklist `docs/hil/checklist-fase0.md` | sem Raspberry e sem DDJ | dono liga o aparelho |
| 2026-10-21 | T006 | BLOCKED | `c984331` | guia `docs/setup/desktop-x86_64.md`; smoke JSON | binário Mixxx ausente; áudio não tocado | instalar a tag 2.5.6 e repetir o smoke |
| 2026-10-21 | T007 | parcial | `c984331` | ADR-0004; `docs/releases/deckbridge-tools-manifest.json` | binário arm64 do Mixxx não gerado | build no aparelho ou runner arm64 |
| 2026-10-21 | T008 | PASS | `c984331` | matriz 201 controles, 112 saídas, 12 LEDs de script, 45 funções | captura física é T009 | — |
| 2026-10-21 | T009 T010 T011 T012 | BLOCKED_HARDWARE | — | checklist | sem controladora e sem placa | ver checklist |
| 2026-10-21 | T013 | PASS | `c984331` | `fixtures/audio/MANIFEST.json`; MP3 e WAV prioritários | não é export rekordbox | T014 com o dono |
| 2026-10-21 | T014 T016 T018 | BLOCKED_HARDWARE | — | — | pendrive rekordbox e aparelho | OD-04 ainda aberta para a versão do rekordbox |
| 2026-10-21 | T015 | PASS | `c984331` | `docs/compatibility/rekordbox-format-matrix.md` | leitura de fonte, não de pendrive | T016 |
| 2026-10-21 | T017 | parcial | `c984331` | 900 s, 180 amostras, CPU média 0,14%, RAM livre mínima 4985 MB, temperatura UNKNOWN, x-runs UNKNOWN | sem Mixxx e sem Raspberry; nada extrapolado | repetir 15 min no aparelho com áudio |

### T001 — esqueleto
- Estado: PASS
- Ambiente: Linux x86_64, Python 3.12
- Código: `LICENSE` provisória GPL-2.0-or-later (OD-06 segue aberta), `docs/branch-policy.md`, `.github/workflows/ci.yml`, `pyproject.toml`
- Testes: `python3 -m pytest` e `python3 -m ruff check src tests tools`
- HIL executado? não
- Próximo passo: deixar o CI remoto rodar quando houver runner

### T002 — pin do Mixxx
- Estado: PASS
- Commit upstream: `3ebac449e7e5fe2a0186596657696e87ce8b0e56`, tag `2.5.6`, não é beta
- Assinatura PGP presente e não verificada
- SHA-256 do arquivo gerado pelo GitHub anotado em `third_party/mixxx/2.5.6/PIN.json`; o release não publicou checksum próprio
- HIL executado? não

### T003 — licenças
- Estado: PASS da auditoria inicial
- Código de terceiros copiado: só o mapping DDJ-400, GPL-2.0-or-later
- StandaloneMixxx: LICENSE não encontrada, código não copiado
- HIL executado? não

### T004 — coleta
- Estado: PASS do programa
- Teste: árvore vazia produz UNKNOWN, inclusive firmware
- Coleta desta máquina: x86_64, sem modelo de Raspberry, sem firmware de DDJ
- HIL executado? não

### T006 — desktop
- Estado: BLOCKED para executar o Mixxx; guia escrito
- Smoke: `docs/benchmarks/T006-mixxx-smoke.json`
- HIL executado? não

### T007 — pacote
- Estado: ferramentas empacotadas; Mixxx arm64 não
- ADR: `docs/adr/0004-build-pipeline.md`
- Manifesto: `docs/releases/deckbridge-tools-manifest.json`
- HIL executado? não

### T008 — matriz da controladora
- Estado: PASS da extração
- 201 entradas, 112 saídas XML, 12 LEDs no script, 45 funções
- HIL executado? não

### T013 — fixtures
- Estado: PASS
- MP3, WAV 44,1 kHz, WAV 48 kHz, FLAC e AIFF. Prioridade MP3 e WAV
- Pasta `fixtures/usb-audio-only/` sem `export.pdb`
- HIL executado? não

### T015 — rekordbox no fonte
- Estado: PASS da leitura
- Importação real: BLOCKED_HARDWARE
- HIL executado? não

### T017 — benchmark
- Estado: parcial
- Amostra: `docs/benchmarks/T017-desktop-15min.json`
- Duração real: 900 s, 180 amostras, neste computador x86_64
- CPU ocupada média: 0,14%. RAM livre mínima: 4985 MB. Temperatura: UNKNOWN. X-runs: UNKNOWN
- Mixxx não estava rodando e nenhum áudio foi tocado
- Raspberry 15 min: BLOCKED_HARDWARE
- HIL executado? não
- Próximo passo: o dono segue `docs/hil/checklist-fase0.md`

## Fase 1 e trilha de simulação — 2026-10-21

O equipamento físico fica para o fim. Simulação não foi montada (T075–T077 seguem TODO). O roteiro único de hardware está escrito. Código e documentos: `a4e1c98`.

| Data UTC | Task | Estado | Commit/PR | Testes/Evidências | Bloqueios | Próxima ação |
|---|---|---|---|---|---|---|
| 2026-10-21 | T020 | PASS | `a4e1c98` | `config/defaults.toml`, testes de recusa de escrita | sample rate e buffer são placeholder | medir no aparelho, sem tratar o número atual como fato |
| 2026-10-21 | T021 | PASS | `a4e1c98` | fila simulada de add/remove | daemon udev não instalado no aparelho | T033 |
| 2026-10-21 | T022 | parcial | `a4e1c98` | runner falso; imagem FAT não montou | kernel sem tipo `vfat` | repetir montagem onde o sistema de arquivos existir |
| 2026-10-21 | T023 | PASS | `a4e1c98` | semente 20261021, auto-play sempre falso | sem controladora | — |
| 2026-10-21 | T024 | PASS | `a4e1c98` | PDB sintético, OneLibrary não aberto, timeout | não é export rekordbox; cues não carregados | T016 e OD-04 |
| 2026-10-21 | T025 | parcial | `a4e1c98` | ADR-0005; áudio devolvido igual | Mixxx não executado; sem patch C++ | gancho interno quando houver binário |
| 2026-10-21 | T026 T027 | PASS | `a4e1c98` | 13 SVG em `docs/ui/screenshots/` | Mixxx não carregou pele; waveform é bloco | T035 na UI real |
| 2026-10-21 | T028 | PASS | `a4e1c98` | 100 faixas sintéticas, espera de 200 ms | sem encoder | T029 no hardware |
| 2026-10-21 | T029 | parcial | `a4e1c98` | bytes do XML pinado; troca de tela sem botão | confirmação física pendente | roteiro final, passo 6 |
| 2026-10-21 | T030 | PASS | `a4e1c98` | 12 códigos em pt-BR e en-US | sem tela ligada | — |
| 2026-10-21 | T031 T033 T034 T035 T038 T039 | BLOCKED_HARDWARE | — | `docs/benchmarks/G1-evidence.md` não fecha o gate | sem DDJ-400 e sem Raspberry até o fim | `docs/hil/roteiro-validacao-final.md` |
| 2026-10-21 | T032 | PASS | `a4e1c98` | temperatura, subtensão e x-runs `UNKNOWN` se vazios | sem medida no aparelho | hil-collect no fim |
| 2026-10-21 | T036 T037 | PASS | `a4e1c98` | processo separado não cria arquivo; suíte local | runner remoto de CI não observado | — |
| 2026-10-21 | T075 T076 T077 | TODO | — | plano em `plan.md` §15 e `docs/simulation/README.md` | ambiente não construído | implementar só como simulação, sem fechar gate |
| 2026-10-21 | T078 | BLOCKED_HARDWARE | `a4e1c98` | roteiro numerado escrito | execução no fim | dono segue os 12 passos |
| 2026-10-21 | T079 | PASS | `a4e1c98` | `evidence.py` recusa `SIMULATED` para HIL e para G0/G1 | — | manter a regra quando T075 existir |
| 2026-10-21 | ADR Android | proposta | `0a912d5` | `docs/adr/0004-android-viabilidade.md` | não aceita; não altera ADR-0001, ADR-0002 nem o plano | dono responde OD-11 a OD-16 |
| 2026-10-21 | OD-11 e A001–A013 | aceito / parcial | `fa9b413` | APK debug; testes JVM; 42 testes Python | escuta no tablet `BLOCKED_HARDWARE`; OD-12..OD-16 provisórias | dono instala e manda o relatório |
| 2026-10-21 | A015–A022 | parcial | `fa9b413` | parser, fixture, 8 testes JVM, 42 testes Python | pendrive real e escuta `BLOCKED_HARDWARE`; prints do emulador não saíram; OneLibrary não lida | dono autoriza o USB e manda o relatório |
| 2026-10-21 | A023–A029 | parcial | `c28f677` | prints JVM, loop, estéreo, pitch 14 bits, exportExt, OneLibrary, MIDI virtual | escuta e pendrive real `BLOCKED_HARDWARE`; emulador A022 aberto | dono instala o APK e manda o relatório |
| 2026-10-21 | A030–A031 | parcial | `edfe33b` | tema de palco, seis prints JVM, APK arm64 | escuta e pendrive real `BLOCKED_HARDWARE`; emulador A022 aberto | dono instala o APK arm64 e manda o relatório |
| 2026-10-22 | A032–A034 | parcial | `cafec07` | player CDJ, browser por cima, zoom, grade, Beat FX, APK 0.6.0 | escuta e pendrive real `BLOCKED_HARDWARE`; efeito de áudio não existe; emulador A022 aberto | dono instala o APK arm64 e manda o relatório |
| 2026-10-22 | A035–A038 | parcial | `28e5466` | tela 0.7.0, browser denso, 24 faixas, PWB3, echo/delay/flanger, APK arm64 | escuta e pendrive real `BLOCKED_HARDWARE`; frase simulada; emulador A022 aberto | dono instala o APK arm64 e manda o relatório |
| 2026-10-22 | A039–A046 | parcial | `b3ef63e` | tela 0.8.0, menu, salto, loop, Beat FX, RMX, APK arm64 | escuta, pendrive e mapeamento RMX `BLOCKED_HARDWARE`; A022 aberto | dono instala o APK arm64 e confirma o item 8 do checklist |
| 2026-10-22 | A047–A051 | parcial | `dbe9b3d` | tela 0.9.0, menu, RMX, histórico, gravação, variantes USB, APK arm64 | escuta, pendrive real, RMX na controladora e FX de ouvido `BLOCKED_HARDWARE`; A022 aberto | dono instala o APK arm64 e segue os passos 13 a 16 do roteiro |
| 2026-10-22 | A052 | parcial | `4d730fc` | tela 0.9.1, BPM/tom do histórico, alvos do menu, RMX e colunas do histórico | escuta `BLOCKED_HARDWARE`; A022 aberto | dono instala o APK arm64 0.9.1 |
| 2026-10-22 | A053–A059 | parcial | `2c4124e` | tela 0.10.0, capa, plano do leitor, key shift, filtro, slip, emergency loop | escuta `BLOCKED_HARDWARE`; A022 aberto; voz e 4 decks `DEFERRED` | dono instala o APK arm64 0.10.0 |
| 2026-10-22 | A060–A062 | parcial | `f0ebf46` | tela 0.11.0, onda RGB do export, DEVSETTING, faixas longas sintéticas | escuta e pendrive real `BLOCKED_HARDWARE`; A022 aberto | dono instala o APK arm64 0.11.0 |
| 2026-10-22 | A063–A065 | parcial | `ca075ab` | corpus CC BY 3.0, export deste app, cinco itens de ecrã, inspeção só leitura, APK 0.12.0 | escuta, MediaCodec e pendrive do dono `BLOCKED_HARDWARE`; grade real perto de 100 ms; tom ≠ TKEY | dono ouve o trecho e, quando a pasta chegar, roda `tools/inspect_usb.py` |
| 2026-10-22 | A066 | parcial | `9453e82` | tela 0.12.1, grade no kick, tom MIREX 0,50, RGB aditivo, emenda de loop, APK arm64 | escuta e pendrive do dono `BLOCKED_HARDWARE`; cobertura do kick 57–93%; Ecosystem Fm ≠ TKEY Ebm | dono instala o APK arm64 0.12.1 e ouve o trecho |
| 2026-10-22 | A067 | parcial | `efbd367` | tela 0.12.2, corpus GiantSteps, Acc1 0,655, Acc2 0,724, MIREX 0,476, pasta sem export, APK arm64 | alvos 0,85 / 0,95 / 0,70 não atingidos; escuta `BLOCKED_HARDWARE`; Chromosome 154,2 e tom Am, TKEY é Bm | dono instala o APK arm64 0.12.2 e abre uma pasta sem export |
| 2026-10-22 | A068 | parcial | `78bd7c9` | tela 0.13.0, slip, frases 16/26 a 1 compasso, MATCHING, SUGGEST, auto gain, APK arm64 | escuta `BLOCKED_HARDWARE`; sem auto gain lido do export; voz e 4 decks por fazer | dono instala o APK arm64 0.13.0 |
| 2026-10-22 | A069 | parcial | `9d592d9` | tela 0.13.1, tom CQT + EDMA, MIREX 0,586 nas 58 e 0,519 em 190, APK arm64 | 0,65 não atingido; holdout 0,456; escuta `BLOCKED_HARDWARE`; Chromosome F ≠ Bm | dono instala o APK arm64 0.13.1 |
| 2026-10-22 | mix | parcial | `52f1929` | MP3 3:42 dos efeitos no motor do host, 320 kbps, true peak −1,44 dBTP | não é o tablet; Color FX só tem o filtro; escuta `BLOCKED_HARDWARE` | dono ouve `/opt/cursor/artifacts/demo-mix.mp3` |

### Proposta Android
- Estado: proposta, não aceita
- Arquivo: `docs/adr/0004-android-viabilidade.md`
- O número 0004 também nomeia `docs/adr/0004-build-pipeline.md`, que continua valendo para o pacote Linux. Os dois arquivos convivem; este novo não substitui o outro.
- OD-11 a OD-16 entraram em `spec.md` como abertas. ADR-0001, ADR-0002 e `plan.md` não foram alterados.
- HIL executado? não

### Alvo Android e prova A0
- Estado: app gerado; escuta BLOCKED_HARDWARE
- OD-11 decidida: Android alvo 1, Raspberry alvo 2
- OD-12 a OD-16: provisório, confirmar com o dono
- Tablet citado: Galaxy Tab S6 Lite, modelo exato não confirmado, 4 GB, Android 10 a 14
- Relato do djay (master e fone separados) registrado como referência, não como medição
- Testes JVM da lógica: o parser, a fixture e o mapa. Ver `./gradlew testDebugUnitTest`.
- APK de debug desta rodada: `/opt/cursor/artifacts/deckbridge-debug.apk`, 13615739 bytes, SHA-256 `35266d8f38afe23822d9e50ec4996b9e1eb0bba7918af0fdded3632737716e8d`. ABIs arm64-v8a, armeabi-v7a e x86_64.
- HIL executado? não

### Biblioteca rekordbox e decks
- Estado: software parcial; escuta e pendrive real BLOCKED_HARDWARE
- Leitor próprio de export.pdb e ANLZ. Licenças conferidas em `android/FORMATO.md`. Código de terceiros não copiado.
- OneLibrary detectada e não aberta nesta rodada. A rodada seguinte tenta a chave pública.
- Fixture sintética no próprio app. Sem música comercial.
- MIDI simulado usa bytes do mapa oficial e não é a DDJ-400.
- Emulador: imagem Android 14 x86_64. KVM acusou `kvm_spurious_fault`. Com CPU em software o sistema ficou em "Application Not Responding" e o print não é a tela do app. Nenhum PNG foi guardado como se fosse o DeckBridge.
- HIL executado? não

### Prints na JVM e player
- Estado: renderização JVM gravada; escuta e pendrive real BLOCKED_HARDWARE
- Telas reais do Compose, com o pendrive simulado, via Roborazzi 1.44 e Robolectric em gráficos nativos.
- Prints: `/opt/cursor/artifacts/prints/01-diagnostico.png` (1600x900), `02-biblioteca-playlists.png`, `03-biblioteca-faixas.png`, `04-decks.png`, `05-decks-1024x600.png`, `06-decks-2000x1200.png`.
- Cada imagem traz a faixa "Renderização em JVM. Não é o tablet nem a DDJ-400."
- APK desta rodada: `/opt/cursor/artifacts/deckbridge-debug.apk`, 29804000 bytes, SHA-256 `2877778e65ced7fa7dad6da60ed1bc7646e82e18c420becddac22382f86d14ea`, versão 0.4.0-prints. Substitui o artefato da rodada A0.
- HIL executado? não

### Palco e APK arm64
- Estado: interface refeita na JVM; escuta e pendrive real BLOCKED_HARDWARE
- Tema escuro com texto claro. Contraste dos pares de cor do palco coberto por teste JVM (WCAG AA, 4,5:1).
- Dois decks lado a lado, mixer e crossfader no centro, waveform maior, hot cues com a cor gravada na faixa ou a cor padrão do pad.
- Navegação Diagnóstico / Biblioteca / Decks. Faixa curta: "SIMULAÇÃO · Renderização em JVM. Não é o tablet nem a DDJ-400."
- Prints regenerados, mesmos nomes, em `/opt/cursor/artifacts/prints/`.
- APK do tablet: `/opt/cursor/artifacts/deckbridge-arm64.apk`, 3873001 bytes, SHA-256 `0f460bf848f4810e443034fc6a744aa3e04d49a7b1298898b063a08d34d24122`, versão 0.5.0-palco, só arm64-v8a, minify e shrink. Assinatura de debug.
- APK com três ABIs: `/opt/cursor/artifacts/deckbridge-debug.apk`, 18370544 bytes, SHA-256 `7be1ebb38d19caefc58e5c231647fa6ee0f09c24a2275474136bb7bfc4cbb4c5`. Substitui o artefato da secção anterior.
- HIL executado? não

### Player CDJ e APK 0.6.0
- Estado: interface refeita na JVM; escuta e pendrive real BLOCKED_HARDWARE
- A tela abre nos dois decks. SOURCE e BROWSE abrem a biblioteca por cima. LOAD DECK 1 e LOAD DECK 2 voltam ao player.
- Zoom de 2 compassos até a faixa inteira, compartilhado, com botões e gesto. Não há controle livre na DDJ-400; Beat esquerda/direita continuam no Beat FX.
- A grade fica no tempo da faixa: batida, compasso e frase de 16. O playhead anda com o pitch; os traços não esticam sozinhos.
- Beat FX mostra nome, fração, canal, ON e nível a partir do MIDI. Não há processamento de echo, reverb ou dos outros nomes.
- KEY é só um badge. O mapa oficial não tem key lock.
- O prato empurra o áudio por uma escala provisória de segundos. Não é medida da controladora.
- As três cores da onda são um desenho sobre o PWAV curto e os beats. Não é FFT nem PWV3/PWV4/PWV5.
- O quantize começa ligado na tela. Isso não é o estado lido da controladora.
- Prints em `/opt/cursor/artifacts/prints/`: `player-2000x1200.png`, `player-1024x600.png`, `browse-categories.png`, `browse-tracks.png`, `zoom-in.png`, `zoom-out.png`, `beatfx.png`, `diagnostico.png`. Faixa curta: "SIMULAÇÃO · Renderização em JVM. Não é o tablet nem a DDJ-400."
- APK do tablet: `/opt/cursor/artifacts/deckbridge-arm64.apk`, 4005281 bytes, SHA-256 `0f0f1b7a049f2913eb72d0115c558501d801145ab9829e8b1288b32019b9e1a1`, versão 0.6.0, só arm64-v8a, minify e shrink. Assinatura de debug. Substitui o artefato 0.5.0-palco.
- APK com três ABIs: `/opt/cursor/artifacts/deckbridge-debug.apk`, 18464300 bytes, SHA-256 `e48c685cafacf4a7a7ecf4e5f07bff6bbc4dd6cecec0d034ddc1ffe4835a420c`.
- HIL executado? não

### Tela 0.7.0 e APK
- Estado: interface refeita na JVM; escuta e pendrive real BLOCKED_HARDWARE
- Duas ondas empilhadas, playhead perto de 30% da esquerda, traços brancos por batida e vermelhos no início do compasso, números 4, 8…, hot cues 2×4 com o comentário, cartão com REMAIN, ON AIR, BPM e visão geral.
- A barra de cores debaixo da visão geral segue o índice do compasso (roxo, verde, laranja, vermelho). Não é análise de frase lida de um arquivo.
- PWB3 é uma seção deste projeto: energia grave/média/aguda por coluna, cada banda normalizada pelo próprio pico. Um export real não traz PWB3; a tela cai no PWAV mono.
- Echo, Delay e Flanger entram no mixer da rota 3. Reverb, Phaser, Roll, Trans e Filter continuam só no nome.
- AUTO no Beat FX segue o BPM do deck atribuído vezes o pitch. Não há detector de BPM à parte.
- QUANTIZE 1/4 é o rótulo da tela para o snap na batida. O quantize começa ligado. Isso não é leitura da controladora.
- As faixas sintéticas duram 18 s ou 32 s. O relógio grande mostra esse tempo, não uma faixa de quatro minutos.
- KEY não altera o áudio. O zoom não tem MIDI. O sentido do encoder BROWSE não foi medido. O jog usa segundos provisórios.
- Prints em `/opt/cursor/artifacts/prints/`: `player-2000x1200.png`, `player-1024x600.png`, `browse-list.png`, `beatfx-zoom.png`, `diagnostico.png`. Faixa pequena: "SIMULAÇÃO · Renderização em JVM".
- APK do tablet: `/opt/cursor/artifacts/deckbridge-arm64.apk`, 4025021 bytes, SHA-256 `946213adb5ef58efd2295fb86c4351334b091da7e12038e0bd855465df273a02`, versão 0.7.0, só arm64-v8a, minify e shrink. Assinatura de debug.
- APK com três ABIs: `/opt/cursor/artifacts/deckbridge-debug.apk`, 18523296 bytes, SHA-256 `d2fc5d98090df20762933af21ebf9df8b81ab6245082c623371c897c487469c6`.
- HIL executado? não

### Tela 0.8.0 e APK
- Estado: interface e motor na JVM e no binário hospedeiro; escuta, pendrive real e mapeamento RMX na controladora BLOCKED_HARDWARE
- Barra alta com seis botões iguais. Faixa fina com simulação, relógio e REMAIN. MENU grava preferências. TAG LIST, PLAYLIST e SEARCH abrem as suas listas.
- Faixa de toque: salto −32…+32 e loop 4 / IN / OUT / metade / dobro / sair / 1/4…32. O mapa oficial de BEAT JUMP e BEAT LOOP faz o mesmo caminho no motor.
- Beat FX da lista oficial entra no mixer da rota 3. Spiral, Helix, Mobius, Pitch e o reverb são aproximações originais. O knob FILTER é o único Sound Color FX deste mapa.
- RMX: Scene FX, X-Pad, isolador na tela e release. Com o painel ligado, os pads SAMPLER (notas 0x30–0x37) disparam a cena e o X-Pad. SHIFT+SAMPLER não é tomado. Provisório até o item 8 de `docs/hil/checklist-fase1.md`.
- Master Tempo é um deslocador de tom por duas linhas de atraso. Não é o algoritmo comercial.
- As faixas sintéticas duram 18 s ou 32 s. A barra de frase continua simulada. PWB3 continua a ser a seção deste projeto.
- O jog e o sentido do encoder continuam provisórios, mesmo com o ajuste no menu.
- Prints em `/opt/cursor/artifacts/prints/`: `player-2000x1200.png`, `player-1024x600.png`, `browse-list.png`, `beatfx-zoom.png`, `diagnostico.png`, `menu.png`, `search.png`, `tag-list.png`, `beat-jump-loop.png`, `beatfx-list.png`, `rmx-panel.png`, `rmx-full.png`.
- APK do tablet: `/opt/cursor/artifacts/deckbridge-arm64.apk`, 4172961 bytes, SHA-256 `2af0ee14fde7b54c5866b6b914c9abaecdab5055f82b1b599a283e824e32d880`, versão 0.8.0 (versionCode 7), só arm64-v8a, minify e shrink. Assinatura de debug. Contém `libdeckbridge-engine.so` e `libsqlcipher.so`.
- APK com três ABIs: `/opt/cursor/artifacts/deckbridge-debug.apk`, 19327975 bytes, SHA-256 `63cb27d7c4cc9c1bac2a5dbadc763aa7873dbedb08688b2d68a3662fd469db40`.
- HIL executado? não

### Tela 0.9.0 e APK
- Estado: interface, histórico, gravação e parser na JVM e no binário hospedeiro; escuta, pendrive real, RMX na controladora e FX de ouvido BLOCKED_HARDWARE
- MENU: coluna com Player, Áudio, Controladora, Biblioteca, Gravação, Histórico, Diagnóstico e Sobre. Linha com rótulo à esquerda e valor à direita. Seleção em azul-escuro com texto branco.
- RMX em vista cheia: dial circular (laranja em build-up, azul em break-down), células escuras, X-Pad, isolador com gradiente e RELEASE. O mapeamento MIDI continua o da 0.8.0, provisório.
- Histórico: a faixa no ar (a tocar, fader acima de 0,2, pelo tempo do menu, padrão 10 s) entra numa base SQLite do app, com hora, deck, BPM e tom. CSV e M3U8 pedem um documento ao utilizador. O M3U8 usa `deckbridge://set/…`. O USB do rekordbox não é escrito.
- Gravação: REC na faixa fina, cronómetro e medidor. WAV estéreo 16 bits do master, depois dos efeitos, na pasta do app. Anel sem mutex e sem alocação no `push` da thread de áudio, no teste hospedeiro. AAC opcional pelo MediaCodec. LAME não entra no APK. Abaixo de 64 MB livres a gravação não começa.
- Parser: páginas 2048/4096/8192, título unicode, linha apagada, ANLZ ausente, `.EXT`/`.2EX`, PWV7 acima de PWV5/PWV3 acima de PWB3, cues PCO2, tag desconhecida e fuzz sem exceção. As 5.000 faixas desta JVM ficaram abaixo de 2 s (o print do diagnóstico desta rodada mostrou 35 ms, já com a geração da fixture no mesmo trecho medido do parse). Não é um export rekordbox real. “5/6/7” é o conjunto de tags, não um campo de versão. Caminhos ímpares são texto no PDB, não um volume exFAT montado.
- As faixas sintéticas duram 18 s ou 32 s. A barra de frase continua simulada. O jog, o encoder e o RMX MIDI continuam provisórios.
- Prints em `/opt/cursor/artifacts/prints/`: `menu.png`, `rmx-full.png`, `history.png`, `rec-active.png`, `diagnostico.png`, `player-2000x1200.png`, `player-1024x600.png`.
- APK do tablet: `/opt/cursor/artifacts/deckbridge-arm64.apk`, 4196629 bytes, SHA-256 `0ce64ac72e209cd2ac514df9b3f936e154cc2cf93d8d49435f8c167fd3e7b40e`, versão 0.9.0 (versionCode 8), só arm64-v8a, minify e shrink. Assinatura de debug. Contém `libdeckbridge-engine.so` e `libsqlcipher.so`. O artefato foi substituído pela 0.9.1; estes números são os da 0.9.0.
- APK com três ABIs: `/opt/cursor/artifacts/deckbridge-debug.apk`, 19421083 bytes, SHA-256 `5ac9369c23dc3de8317ab402d0647ce5cded246fb29e8b8f279e0b8af0f10b5b`. Também substituído no disco pela 0.9.1.
- HIL executado? não

### Tela 0.9.1
- Estado: correção na JVM; escuta BLOCKED_HARDWARE
- O print do histórico da 0.9.0 não saía do logger. A linha estava escrita à mão: Night Bus com 120.0 / 8A (o Click 120) e Glasshouse com 126.0 / 4A. O logger agora copia a faixa carregada: título, artista, BPM da biblioteca, tom na mesma notação do browser, e o BPM no ar (biblioteca × pitch). Trocar de faixa recomeça a contagem, para não misturar as duas.
- MENU: linhas de 60 dp, segmentos de 48 dp por pelo menos 64 dp, navegação de 64 dp com ícones, cartões em duas colunas em 2000×1200.
- RMX: o dial deixa de esticar a linha; os pads trazem Echo UP, Noise UP, Spiral UP, Reverb UP, Reverb DOWN, Echo DOWN, Tape STOP e Dub ECHO. O isolador mostra o dB, a linha de 0 dB e o kill a vermelho.
- HISTÓRICO: cabeçalho da sessão, colunas HORA / DECK / FAIXA / BPM / TOM, linhas alternadas.
- APK do tablet: `/opt/cursor/artifacts/deckbridge-arm64.apk`, 4205025 bytes, SHA-256 `02c7fd0cb34e37dd54d4a91fdfbf38076fd6d0961cd1cf5c9c4c204e2f9752f0`, versão 0.9.1 (versionCode 9), só arm64-v8a, minify e shrink. Assinatura de debug. Contém `libdeckbridge-engine.so` e `libsqlcipher.so`. O ficheiro no disco foi substituído pela 0.10.0; estes números são os da 0.9.1.
- APK com três ABIs: `/opt/cursor/artifacts/deckbridge-debug.apk`, 19439031 bytes, SHA-256 `2ea2aa7f34fddb2e97bed21ebdaa37c709dfef09f64eb3628bb14c5057e1a7de`. Também substituído no disco pela 0.10.0.
- HIL executado? não

### Tela 0.10.0
- Estado: JVM e binário hospedeiro; escuta BLOCKED_HARDWARE
- Capa lida da tabela de artwork e de `PIONEER/Artwork`. A miniatura fica no cache do app. Aparece no cartão do deck, na linha do browser e no painel INFO. Sem imagem, o lugar mostra ♪. O USB não é escrito. A biblioteca sintética usa PNG gerado aqui.
- Plano em `docs/research/cdj3000-coverage.md`. Busca por voz e quatro decks ficam `DEFERRED` (2026-10-22).
- No ecrã: Key Shift, Key Sync aproximado, filtro BPM/tom, contagem até o cue, bloco de 16 compassos, agulha na visão geral, slip com regresso, emergency loop, tempo com duas casas, banco de hot cue na memória do app.
- O Key Sync não é o algoritmo comercial. O slip não cobre pausa, scratch nem a alavanca. A frase não é PSSI. O fader não tem passo de 0,02%.
- Prints novos em `/opt/cursor/artifacts/prints/`: `key-shift.png`, `slip.png`, `emergency-loop.png`, `cue-bank.png`, `info-art.png`, `track-filter.png`, além de `player-2000x1200.png` e `browse-list.png`.
- APK do tablet: `/opt/cursor/artifacts/deckbridge-arm64.apk`, 4218565 bytes, SHA-256 `3f434fd7a00f6f2319d2b20c2e4e8937d23c08c970244ffd70fd1f8e3f5263ab`, versão 0.10.0 (versionCode 10), só arm64-v8a, minify e shrink. Assinatura de debug. Contém `libdeckbridge-engine.so` e `libsqlcipher.so`.
- APK com três ABIs: `/opt/cursor/artifacts/deckbridge-debug.apk`, 19470523 bytes, SHA-256 `3938a07ce3fef58ad71d1ab6c309df86adae8f86167e65357c1a8f3fb824803e`. O arquivo no disco foi substituído pela 0.11.0; estes números são os da 0.10.0.
- HIL executado? não

### Tela 0.11.0
- Estado: JVM e binário hospedeiro; escuta BLOCKED_HARDWARE
- A onda grande em RGB lê PWV5 (cerca de 150 colunas por segundo). O cartão lê PWV4 (em geral 1200 colunas). 3 BANDAS lê PWV7 na onda grande e PWV6 no cartão. AZUL lê PWV3 e, se faltar, PWAV. A leitura é deste projeto, a partir do texto público, sem copiar parser. Um pendrive real ainda não foi lido.
- PWV5 é um u16 big-endian: 3 bits de vermelho, 3 de verde, 3 de azul, 5 de altura, 2 bits baixos sem uso. A escala é `n * 255 / 7` e `n * 255 / 31`. O desenho mistura a coluna, espelha e clareia a crista. Uma medição posterior troca o que chama de médio e agudo; este app segue o texto citado e o pedido (R, G, B), não essa troca.
- PWV4 usa os bytes 3, 4 e 5 da coluna como grave, médio e agudo. A altura é o máximo. A cor é uma mistura nossa (grave laranja, médio verde, agudo branco-azul), não o desenho de um parser de terceiros.
- `MYSETTING.DAT`, `MYSETTING2.DAT` e `DJMMYSETTING.DAT` não documentam a cor da onda. A fonte usada é só `PIONEER/DEVSETTING.DAT` ou `PIONEER/rekordbox/DEVSETTING.DAT`: byte 104+10 vale `0x01` azul, `0x03` RGB, `0x04` 3 bandas; byte 104+13 vale `0x01` centro (50% nesta tela) ou `0x02` esquerda (20%). Outro byte não vira modo. O menu tem RGB, 3 BANDAS, AZUL e “Usar configuração do pendrive”. Escolher a cor ou o playhead desliga o pendrive. Fontes no `FORMATO.md`.
- Sem a tag daquele modo, o WAV mono vira três bandas aqui (passa-baixas em 180 Hz e 4000 Hz, ida e volta) e o INFO mostra “analisado no app”. O USB não é escrito.
- A biblioteca sintética grava PWV4, PWV5 e PWV3 no `.EXT`, e PWV6, PWV7 e PWVC no `.2EX`, a partir do áudio gerado aqui. Entraram “Quarto Escuro” (200 s) e “Linha Alta” (210 s), com intro, build, drop, break e outro. O `DEVSETTING.DAT` sintético pede RGB e playhead ao centro.
- A escala desenhada das 3 bandas não é uma cópia medida do programa comercial. A frase debaixo do cartão continua sendo o número do compasso, não PSSI. Spiral, Helix, Mobius, Pitch, o reverb, o key lock e o RMX continuam aproximações. O knob FILTER é o único Sound Color FX deste mapa.
- Prints em `/opt/cursor/artifacts/prints/`: `player-2000x1200.png` e `player-1024x600.png` em RGB com a faixa inteira (intro verde, build branco-azul, drop laranja, break verde, outro branco; o cartão repete essa ordem), `wave-3band.png` e `wave-blue.png`. O playhead desses prints fica em 30%: a captura não passa pela tela que aplica o DEVSETTING.
- JVM: 51 testes, 0 falhas. DSP hospedeiro e gravação hospedeira: ALL PASS. Python: 42 passed.
- APK do tablet: `/opt/cursor/artifacts/deckbridge-arm64.apk`, 4230285 bytes, SHA-256 `53eda41caaed465f1f8ec0a3c7fbfa00fb8e77776aa007c16f9d6334ffdf76f4`, versão 0.11.0 (versionCode 11), só arm64-v8a, minify e shrink. Assinatura de debug. Contém `libdeckbridge-engine.so` e `libsqlcipher.so`.
- APK com três ABIs: `/opt/cursor/artifacts/deckbridge-debug.apk`, 19496939 bytes, SHA-256 `3d1a288c121e82b2720f52524ecae03c07c4634986e7aac465f9e15c8236649f`.
- HIL executado? não

### Tela 0.12.0
- Estado: JVM e binário hospedeiro; escuta BLOCKED_HARDWARE
- Nove faixas CC BY 3.0 em `/opt/cursor/artifacts/real-corpus`. Não entram no git. `tools/fetch_real_corpus.py` confere o SHA-256. Tabela, licença e medidas em `docs/testing/real-music-corpus.md`.
- O export em `/opt/cursor/artifacts/real-music-usb` é deste app (PWV4, PWV5, PWV6, PWV7, cues de estrutura). Não é rekordbox. Relido: 9 faixas, 27 hot cues, 18 memory cues, 9 loops, DEVSETTING RGB, erro nenhum.
- Decodificação neste host: ffmpeg MP3 359 ms, FLAC 196 ms, AAC/M4A 302 ms, todos com saída 0. WAV Silver: `decodeWav` 154 ms contra ffmpeg 386 ms, correlação 1,00000 nos primeiros 5 s. O MediaCodec do app não rodou aqui.
- BPM detectado de 117,90 a 152,23. Nenhuma faixa traz BPM publicado. Tom detectado ≠ TKEY nas três que têm o tag (Am/Bm, Fm/Ebm, Bm/Em). Erro mediano onset–grade: 93,4 a 117,5 ms. No clique sintético o mesmo detector fica em até 30 ms.
- Ocean não separa drop e break (os dois em 316 s). Análise 122–227 ms. Heap JVM de 74 MB a 426 MB. Não é o tablet.
- Mix de 120 s, Chromosome Boutique + Ecosystem: pico 0,62856, NaN 0, eco em 20580 amostras contra batida de 20580,11. Energia da emenda seca 0,00020585; com crossfade 0,00058152 (a emenda molhada ficou maior). Trecho de 45 s em `/opt/cursor/artifacts/real-mix-excerpt.wav` e `.mp3`.
- Ecrã: cabeçalho ordena título, BPM, tom e tempo; Active Loop só ao cruzar o início; REV no motor; G−/G+/S−/S+/G0 na memória do app, sem escrever o USB; contagem até hot cue ou memory cue.
- `tools/inspect_usb.py` lê uma pasta sem gravar nela. Com `--jvm`, o relatório sai em `/tmp/deckbridge-inspect.txt`. A pasta do dono ainda não chegou.
- Prints novos em `/opt/cursor/artifacts/prints/`: `real-browse.png` e drop/break de Chromosome Boutique, Ecosystem, Silver e Morning.
- JVM: 63 testes, 0 falhas. DSP hospedeiro e gravação hospedeira: ALL PASS. Python: 42 passed.
- APK do tablet: `/opt/cursor/artifacts/deckbridge-arm64.apk`, 4234741 bytes, SHA-256 `92bce87304dd638a3fe1d3c64baecd24be81b520d9c1bebc85fe8cdf2352d43f`, versão 0.12.0 (versionCode 12), só arm64-v8a, minify e shrink. Assinatura de debug. Contém `libdeckbridge-engine.so` e `libsqlcipher.so`.
- APK com três ABIs: `/opt/cursor/artifacts/deckbridge-debug.apk`, 19529671 bytes, SHA-256 `42e0d015f707c1c589eaf5ec26f72a690865db31a2029a055c243df6763f97a7`.
- HIL executado? não

### Tela 0.12.1
- Estado: JVM e binário hospedeiro; escuta BLOCKED_HARDWARE
- Grade: o erro de ~100 ms era onset de banda cheia no contratempo, mais um seguidor que alongava o período. O atraso do LAME (~13 ms) desloca o arquivo inteiro e não abre esse vão. O BPM é o que mais batidas põe a menos de 18 ms de um pico grave. O período fica fixo. O instante publicado encosta no pico se ele está a menos de 20 ms. Mediana e máximo do resíduo: 0,2–0,3 ms e 0,5 ms. Cobertura: 57% a 93%. A mediana de banda cheia continua 70–124 ms.
- BPM mantidos perto da 0.12.0: Morning 118, Ocean 118, At The Count 152, Silver 141. Ecosystem 122. Chromosome 162 (cobertura do kick; 1,5× de ~108). Caught In The Beat 120, não o ~160 da autocorrelação sem tecto.
- Tom: média MIREX nas três faixas com TKEY de 0,17 para 0,50 (Chromosome Bm = Bm, Silver Bm contra Em é quinta, Ecosystem Fm contra Ebm é 0). O librosa (ISC, script `tools/measure_reference.py`, fora do APK) também diz Fm em Ecosystem. Não se forçou o tag.
- RGB: soma por coluna. Grave vermelho, médio verde, agudo azul, cortes 200 Hz e 2,2 kHz, percentil da faixa (p58 / p94 / p55), peso depois do percentil, gama 1,25, altura pelo RMS. PWV5 em 3 bits. Drops de Chromosome, Ecosystem e Silver ficam rosa. O build de Silver fica azul. O drop de Morning fica laranja (grave e melodia, pouco agudo nessa janela). A legenda no INFO e em Sobre é desenho deste app.
- Loop: crossfade de potência igual. Na última amostra o valor é o que entra. 81 emendas reais, 0 piores que o seco. No mix, energia seca 0,00125770 e molhada 0,00000000. Pico 0,66616. NaN 0.
- JVM: 67 testes, 0 falhas. DSP hospedeiro e gravação hospedeira: ALL PASS. Python: 42 passed.
- APK do tablet: `/opt/cursor/artifacts/deckbridge-arm64.apk`, 4236217 bytes, SHA-256 `e4a160ec8e19da4b1f8f0196ec34c4c77df9272d6c07ce71bc2ba1348cbefad5`, versão 0.12.1 (versionCode 13), só arm64-v8a, minify e shrink. Assinatura de debug. Contém `libdeckbridge-engine.so` e `libsqlcipher.so`. O ficheiro no disco foi substituído pela 0.12.2; estes números são os da 0.12.1.
- APK com três ABIs: `/opt/cursor/artifacts/deckbridge-debug.apk`, 19539107 bytes, SHA-256 `ce08efa653ddbe62b18e9e1d87f5bd7f383665ff4aee9837e32a8b975026f424`. Também substituído no disco pela 0.12.2.
- HIL executado? não

### Tela 0.12.2
- Estado: JVM deste host; escuta BLOCKED_HARDWARE. Não é o tablet e não é a DDJ-400.
- Corpus: 86 prévias GiantSteps em `/opt/cursor/artifacts/ground-truth`, fora do git. 58 com BPM publicado, 58 com tom publicado. Script `tools/fetch_ground_truth.py`. Notas em `docs/testing/ground-truth-corpus.md`.
- Tempo, antes (cobertura do kick, 90–180): Acc1 0,603, Acc2 0,672, erro de oitava 0,069. Depois (pente × autocorrelação × prior largo em volta de 124, faixa 70–180): Acc1 0,655, Acc2 0,724, erro de oitava 0,069. House, techno e trance ficam em 1,000. O alvo 0,85 / 0,95 não foi atingido.
- Alargar a busca para 60–200 foi medido e descartado: Acc1 caiu para 0,517.
- Tom: MIREX 0,457 para 0,476. Exato 0,379 para 0,397. Camelot 0,517 para 0,552. O alvo 0,70 não foi atingido. No mesmo corte, librosa CQT + EDMA fica em 0,593 e a Essentia (perfil EDMA) em 0,581.
- Referência de tempo, fora do APK: librosa `beat_track` Acc1 0,448; Essentia `RhythmExtractor2013` Acc1 0,569 e Acc2 0,810 (oitava 0,241).
- Chromosome Boutique: sem BPM de artista. Essentia no Internet Archive publica 80,7; a Essentia 2.1b6 neste wav diz 81,0. O app 0.12.0 lia 139,9, o 0.12.1 lia 162, o 0.12.2 lê 154,2 e o tom Am (o TKEY é Bm; o 0.12.1 acertava Bm).
- Caught In The Beat: sem BPM de artista. O 0.12.1 lia 120, que é 3/4 de 160. O 0.12.2 lê 160,0. A Essentia 2.1b6 no mesmo wav lê 161,6.
- Pasta sem export: progresso, cache e miniatura RGB. Prints `plain-browse-progress.png` e `plain-browse.png`.
- JVM: 72 testes, 0 falhas. DSP hospedeiro e gravação hospedeira: ALL PASS. Python: 42 passed.
- APK do tablet: `/opt/cursor/artifacts/deckbridge-arm64.apk`, 4248441 bytes, SHA-256 `d456087ad701eff4595a305098cc65fcef01e315eb9fabb3699869fb75da42e6`, versão 0.12.2 (versionCode 14), só arm64-v8a, minify e shrink. Assinatura de debug. Contém `libdeckbridge-engine.so` e `libsqlcipher.so`.
- APK com três ABIs: `/opt/cursor/artifacts/deckbridge-debug.apk`, 19551643 bytes, SHA-256 `bd84c4e2b766fb52a8790795b8ebcb527a4ca73a5cb3b1d0fcbedb97027732c4`.
- HIL executado? não

### Tela 0.13.0
- Estado: JVM deste host; escuta BLOCKED_HARDWARE. Não é o tablet e não é a DDJ-400.
- Slip: o ponto escondido avança para a frente com o pitch mesmo em pausa, em reverse, com hot cue premido ou no scratch. Ao soltar, a cabeça volta. O teste C++ cobre pausa, regresso, reverse, hot cue e jog. A DDJ-400 não tem alavanca de reverse.
- Fase: o menu troca WAVEFORM por PHASE METER. Cada deck desenha a fase do compasso e o desvio em batidas face ao master.
- MATCHING: BPM a 6% (incluindo dobro e metade) e tom compatível, ordenado pela proximidade. SUGGEST: vizinhos Camelot, salto de energia +1/+2/+7, distância de BPM, energia da análise, género e playlist em comum, sem o que já esteve no ar. A linha é do tipo `8A→9A, 124 BPM, energia +1`.
- PSSI: tag do `.EXT`, com a máscara publicada, humor high/mid/low e os nomes Intro, Up, Down, Chorus, Bridge, Verse, Outro, mais fill-in. A barra usa essas frases e larga o índice de 16 compassos. As cores são deste app.
- Pasta sem export: seções intro, build, drop, break e outro, encaixadas em 8 e, quando cai perto, em 16. Cues automáticos na memória do app (QUEDA: A downbeat, B drop, C break, D outro). O USB não é escrito. Banco de hot cue vindo do export é só leitura. Beat jump inclui ±64.
- Frases no corpus real: 16/26 fronteiras a 1 compasso e 20/26 a 4. Método e tabela em `docs/testing/phrase-annotations.md`. Ecosystem, o segundo break de Caught e o outro de Silver ficam de fora. Quatro acertos de outro são a cauda do ficheiro.
- Auto gain: LUFS integrado, alvo −10 (também −14 e −8), trim ±12 dB no cartão. Um seno de 1 kHz a −20 dBFS fica a 2 LU de −23,7. O limiter segura o master em 0,98 e não mexe num par baixo. Não se leu auto gain do export.
- Tempo e tom do 0.12.2 não mudaram: Acc1 0,655, Acc2 0,724, MIREX 0,476. Abaixo de 0,85 / 0,95 / 0,70.
- Prints: `phrase-cues-gain.png`, `phase-meter.png`, `browse-matching.png`, `browse-suggest.png`.
- JVM: 79 testes, 0 falhas. DSP hospedeiro e gravação hospedeira: ALL PASS. Python: 42 passed.
- APK do tablet: `/opt/cursor/artifacts/deckbridge-arm64.apk`, 4264901 bytes, SHA-256 `204373723cb6aa50e7c5a999ae1aa3b0d396a16d326265ade72dd61dd01a8449`, versão 0.13.0 (versionCode 15), só arm64-v8a, minify e shrink. Assinatura de debug. Contém `libdeckbridge-engine.so` e `libsqlcipher.so`.
- APK com três ABIs: `/opt/cursor/artifacts/deckbridge-debug.apk`, 19591251 bytes, SHA-256 `3124f2ce667fad7827097383fce046e182bfe60ae77fe59fdcb8578e677f6478`.
- HIL executado? não
- Busca por voz (A053) e quatro decks (A054) continuam por fazer.

### Tela 0.13.1
- Estado: JVM deste host; escuta BLOCKED_HARDWARE. Não é o tablet e não é a DDJ-400.
- Tom: o Goertzel até cerca de 1 kHz e o EDMA esparso saíram. Entrou um CQT de 12 bandas por oitava, janela de cerca de 67 ciclos, 32,7 Hz a 5 kHz, salto de 4096, log da magnitude, peso 1,7 abaixo de 180 Hz, Pearson com o EDMA publicado. Núcleos em float. Um seno puro continua maior.
- Nas 58 GiantSteps: MIREX 0,476 para 0,586, exato 0,397 para 0,466, Camelot 0,552 para 0,759. A Essentia EDMA neste corte é 0,581 / 0,466 e o librosa CQT+EDMA refeito aqui é 0,591 / 0,483. O alvo 0,58 cumpre-se neste corte. O 0,65 não apareceu.
- O dev (42) ficou em 0,636 e foi o único corte da escolha. Holdout (16) 0,456. Extra (190, `tools/fetch_key_extra.py`) 0,519. As 248 juntas: 0,535. Techno nas 58 sobe de 0,257 para 0,471, mas no extra fica em 0,363 (n=30). Drum and bass nas 58 sobe de 0,350 para 0,620; no extra, 0,536 (n=28). House e dubstep descem nas 58.
- Erros nas 58, antes → depois: exato 23→27, quinta 7→9, relativo 1→7, paralelo 4→2, outro 23→13. No extra: 83, 18, 9, 20 e 60.
- Foram medidos no dev e revertidos: HPCP, branqueamento, separação harmónico/percussivo, EDMM, bgate, Shaath, Krumhansl, Temperley, viés para menor e busca de afinação.
- Tempo não mudou: Acc1 0,655, Acc2 0,724. Abaixo de 0,85 / 0,95.
- Host: 283 ms por prévia de cerca de 2 min (análise completa). Estimativa no Tab S6 Lite, 2–4 vezes este núcleo: cerca de 0,6 s a 1,1 s. Não foi medido no tablet.
- Chromosome Boutique, TKEY Bm: lê F e 154,20 BPM. Ecosystem, TKEY Ebm: lê Cm e 122,00. Silver, TKEY Em: lê D e 141,00 (o 0.12.1 lia Bm). Nenhuma foi forçada.
- Piso do teste: MIREX ≥ 0,56 nas 58 e ≥ 0,48 no extra. O cache de pasta sem export passa a `0131`. O heap do teste JVM passou a 2g: o padrão estourava no lowpass das faixas longas depois das 248 análises.
- Frases: o piso 16/26 a 1 compasso e 20/26 a 4 continua a passar. O tempo, que alimenta a grade, não mudou.
- JVM: 80 testes. Na suíte junta, o PhraseHitRateTest estourou o heap padrão; isolado, com 2g, passa. DSP hospedeiro e gravação hospedeira: ALL PASS. Python: 42 passed.
- APK do tablet: `/opt/cursor/artifacts/deckbridge-arm64.apk`, 4264469 bytes, SHA-256 `750ca1051930efdad9427913aeeea8aeedde9b746bc7242e9ff8915f19ec2aab`, versão 0.13.1 (versionCode 16), só arm64-v8a, minify e shrink. Assinatura de debug. Contém `libdeckbridge-engine.so` e `libsqlcipher.so`. O APK 0.13.0 (4264901 bytes, `204373723cb6aa50e7c5a999ae1aa3b0d396a16d326265ade72dd61dd01a8449`) fica só neste diário.
- APK com três ABIs: `/opt/cursor/artifacts/deckbridge-debug.apk`, 19591903 bytes, SHA-256 `1b464927afc55b05fd58b441cab2ee94d62b92380a1090fc3693bce49dddc3a3`.
- HIL executado? não. O tom novo não acrescenta passo ao roteiro. Os passos 13 a 24 continuam por executar.
- Busca por voz (A053) e quatro decks (A054) continuam por fazer.

### Mix de demonstração dos efeitos
- Estado: render no host; escuta BLOCKED_HARDWARE. Não é o tablet e não é a DDJ-400.
- Ficheiro: `/opt/cursor/artifacts/demo-mix.mp3`, 3:42, 320 kbps. Roteiro em `docs/testing/demo-mix.md`. Forma de onda em `/opt/cursor/artifacts/demo-mix-wave.png`.
- O PCM passa pelo `PeakLimiter` (tecto 0,98, pico 0,980). O MP3 leva −3 dB. True peak a 192 kHz: 0,847 (−1,44 dBTP), sem amostra acima de 1,0.
- Duas faixas CC BY 3.0 (wrist watch e toodaze), sync a 120, troca de grave e filtro nos dois sentidos. Beat FX da lista, com o eco a mudar de 1/2 para 1/1. Color FX é só o filtro. RMX: as oito cenas, os quatro pads e o isolador em kill e +6 dB. Loop de 4, salto de 8 e slip.
- A voz é espeak-ng pt-br, curta, por baixo da música. O knob de Color FX não tem ruído, pitch nem dub eco; esses gestos estão no Beat FX e no RMX.
- HIL executado? não

### T020 — configuração
- Estado: PASS
- Perfil `runtime`, tela de referência 1024x600, `allow_write` recusado
- HIL executado? não

### T022 — montagem
- Estado: parcial
- Tentativa: `mkfs.vfat` criou imagem; `mount` respondeu `unknown filesystem type 'vfat'`
- Não houve ponto montado e nenhum teste afirma o contrário
- HIL executado? não

### T025 — rescan
- Estado: parcial
- Fonte lido: commit `3ebac449e7e5fe2a0186596657696e87ce8b0e56`
- Sem controle `[Library],Rescan`; `LibraryScanner::scan()` é slot interno
- HIL executado? não

### T078 — roteiro final
- Estado: documento PASS; execução BLOCKED_HARDWARE
- Arquivo: `docs/hil/roteiro-validacao-final.md`
- Simulação não conta como estes passos. A lista passou a 16 em 0.9.0. Em 0.10.0 passou a 19. Em 0.11.0 passou a 20. Em 0.12.0 passou a 21. Em 0.12.1 os passos continuam os mesmos: a grade, o tom, a cor e a emenda do loop não são um gesto novo na controladora. Em 0.12.2 a análise de uma pasta sem export também não é um gesto na controladora. Em 0.13.0 os passos 22 a 24 cobrem slip, medidor de fase, MATCHING, SUGGEST, beat jump 64, frase, cues automáticos e auto gain. Em 0.13.1 o tom novo não é um gesto na controladora. Os passos 13 a 24 continuam por executar

## Template de registro

```md
### Txxx — título
- Estado: TODO | IN_PROGRESS | PASS | FAIL | BLOCKED_HARDWARE | BLOCKED_OWNER
- Ambiente: ...
- Commits / PR: ...
- Código gerado ou alterado: ...
- Testes automatizados: ...
- HIL executado? sim/não — evidência: ...
- FR/NFR atingidos: ...
- Riscos e decisões: ...
- Comandos para reproduzir: ...
- Próximo passo: ...
```
