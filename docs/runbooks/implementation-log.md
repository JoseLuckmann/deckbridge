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
| 2026-10-22 | 0.14.0 | parcial | `594f21b` | DSP suave, de/para, drum build, mix v2 3:20, true peak −1,17 dBTP, APK versionCode 17 | host, não o tablet; escuta `BLOCKED_HARDWARE`; spiral, helix e o balanço do roll ainda diferem do manual | dono ouve `/opt/cursor/artifacts/demo-mix-v2.mp3` |
| 2026-10-22 | 0.14.1 | parcial | `e3c0e84` | one-shots CC0, grade do deck, mix 1:28, onset 0 ms, APK versionCode 18 | host, não o tablet; escuta `BLOCKED_HARDWARE`; palma é prévia MP3; spiral e helix continuam fora do manual | dono ouve `/opt/cursor/artifacts/demo-mix-drums.mp3` |
| 2026-10-22 | specs | parcial | `b805a64` | STATUS.md, constituição 1.1.0, OD-17 a OD-24, tarefas com estado | gates G0 e G1 abertos; passos 13 a 24 por executar | dono lê `STATUS.md` |

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

### Tela 0.14.0
- Estado: host; escuta BLOCKED_HARDWARE. Não é o tablet e não é a DDJ-400.
- O DSP desliza knobs (22–36 ms) e o liga/desliga (cerca de 18 ms). Eco e reverb deixam cauda e o seco volta. Filtro TPT, Q 0,95, zona morta e corte exponencial. Eco com feedback escurecido. Reverb com pré-atraso: RT60 0,65 s a nível 0,72. Isolador Linkwitz-Riley 24 dB/oitava. Drum build sintetizado, 1/4 a 1/32 de batida, no pad Drum, com o LEVEL/DEPTH a mandar na densidade. O de/para está em `docs/research/fx-de-para.md`.
- Medidas (`tools/fx_measure.cpp`): salto do filtro suave 6e-8 contra 0,299 no biquad instantâneo; NaN 0; CPU 12,6 µs por bloco de 256 (orçamento 5333 µs). Gráficos em `docs/research/plots/`.
- Mix v2: `/opt/cursor/artifacts/demo-mix-v2.mp3`, 3:20, 320 kbps, 8002605 bytes. PCM 0,981. MP3 −4,5 dB, true peak 0,874 (−1,17 dBTP), sem amostra acima de 1,0. Roteiro em `docs/testing/demo-mix-v2.md`. Sem locução. A comparação aos 1:52 é a curva antiga contra a nova.
- DSP hospedeiro e gravação hospedeira: ALL PASS. O tom, o tempo e o cache `0131` não mudaram.
- APK do tablet: `/opt/cursor/artifacts/deckbridge-arm64.apk`, 4271249 bytes, SHA-256 `071b08d672a836e747b04931fc446f294ff79a9d14c1ea104cbebbb03e87e94b`, versão 0.14.0 (versionCode 17), só arm64-v8a, minify e shrink. Assinatura de debug. Contém `libdeckbridge-engine.so` e `libsqlcipher.so`. Falta o ícone, como nas versões anteriores. O APK 0.13.1 (4264469 bytes, `750ca1051930efdad9427913aeeea8aeedde9b746bc7242e9ff8915f19ec2aab`) fica só neste diário.
- APK com três ABIs: `/opt/cursor/artifacts/deckbridge-debug.apk`, 19611083 bytes, SHA-256 `6fd9bbc624014844c3a39d9bf3b25dc0e5667f59bb4479f1819dda6c558a56d5`.
- HIL executado? não. Os passos 13 a 24 continuam por executar.
- Busca por voz (A053) e quatro decks (A054) continuam por fazer.

### Efeitos 0.15.0 — RMX, riser de bumbo, Color FX, mix de avaliação
- Estado: host; escuta BLOCKED_HARDWARE. Kotlin editado sem compilar (sem SDK Android nesta sessão): `COLOR_FX_NAMES`, `PlayerSettings.colorFx`, chip no menu, `NativeEngine.setColorKind`.
- Commit: ver `git log` da branch `claude/cdj-3000-effects-mixing-8adh3b`.
- `dsp.h`: reverb FDN de 8 linhas (`Fdn`); `GrainShift` para Spiral, Pitch e Spiral UP (o `PitchShift` antigo não deslocava o tom: +1 oitava saía em 451 Hz); Helix trava de verdade; filtro de Beat FX anda no ciclo da fração; Pitch cobre −1 a +1 oitava.
- RMX: cenas reescritas (ping-pong pontuado, ruído em faixa com pulso que acelera, spiral com laço que encolhe e sobe, reverb up/down com FDN), grave da música sai nas subidas, soltura na batida da grade e cauda de eco/reverb depois do drop (`SceneFx::tailBeats`, 8 batidas; 0 mantém o comportamento antigo).
- Drum build: bumbo sobe em curva até +8 semitons (PITCH, agora padrão) ou +2; bombeio por golpe; música cede no último meio tempo; ruído e tom subindo; impacto no drop. `XPad::risers=false` desliga as camadas (o teste de onset usa).
- Sound Color FX: SPACE, DUB ECHO, SWEEP, NOISE, CRUSH, FILTER por canal (`ColorFilter::kind`, padrão FILTER).
- Testes: `android/app/src/test/cpp/dsp_test.cpp` ALL PASS; `tools/fx_measure.cpp` FAILS 0; `tools/drum_grid_test.cpp` pior erro 0 ms; `tools/fx_rmx_test.cpp` FAILS 0.
- Mix: `docs/testing/house-mix.md`, `tools/render_house_mix.sh`. Render com a faixa enviada pelo usuário (130 BPM), fora do git.
- HIL executado? não
- Próximo passo: compilar o app (`gradlew testMultiDebugUnitTest`), ouvir no tablet, ajustar níveis das cenas; expor seletor de Color FX na tela do deck; Roll/Slip Roll com balanço seco/roll.

### Mixer, SYNC, Trainer, RMX e vídeo de sessão (0.15.0, continuação)
- Estado: host; escuta BLOCKED_HARDWARE. Android compila aqui (SDK instalado em ~/android-sdk): `testMultiDebugUnitTest` passa e `assembleMultiDebug` compila o C++ nas três ABIs.
- EQ de canal de 3 bandas, TRIM e fones ligados da DDJ-400 ao motor; controles de 14 bits só pelo byte alto (o baixo fazia o fader saltar).
- Tela MIXER (EQ, TRIM, filtro, fader, crossfader, fones, medidores de pico, seletor dos seis Color FX).
- SYNC: alinha a fase da batida (deck líder, quantize ligado), segue o tempo do líder e solta ao apertar de novo.
- Crossfader aditivo (slow fade).
- Tela RMX reorganizada: seções, alvos de 40-48 dp, controles do drum build sempre visíveis.
- `logic/Trainer.kt`: dicas do modo guiado e três exercícios (beatmatch, troca de grave, entrada na frase) com testes. A interface do Trainer ainda não existe.
- `docs/testing/session-video.md`: vídeo de sessão por MIDI. Achado: a grade do analisador cai fora do bumbo em house (222 ms e 123 ms nas duas faixas de teste).
- HIL executado? não
- Próximo passo: interface do Trainer (dicas e exercícios), correção da fase do analisador, escuta no tablet.

### Tela 0.14.1
- Estado: host; escuta BLOCKED_HARDWARE. Não é o tablet e não é a DDJ-400.
- Commit: `e3c0e84`.
- O roll da 0.14.0 contava samples num relógio que começava em zero. O ataque agora sai quando a fase do deck cruza a grade: posição lida, BPM da grade e downbeat. O pitch e o sync já estão no passo da cabeça. O bloco não empurra o hit para o início do buffer. A latência de saída é a mesma da faixa.
- `tools/drum_grid_test.cpp`: pior erro 0 ms (0 samples) a 120, 128, 140 e 174 BPM, pitch 0,92, 1,00 e 1,08, blocos de 1 e de 256. Gráfico em `docs/research/plots/drum-onset.svg`.
- Padrão: 4 compassos são 1+1+1+1; 8 são 2+2+3+1; 16 são 4+4+7+1. O 1/32 é o último compasso. Troca só na fronteira. Começo na próxima semínima. Para no downbeat. Gap opcional no último meio tempo. Volume sobe. Passa-altas de 40 Hz até cerca de 180 Hz, mistura até 40%. Pitch opcional de cerca de 10% no fim.
- Sons: FLAC 48 kHz CC0 em `android/app/src/main/assets/drums/`. Bumbo, caixa e chimbal da Karoryfer Big Rusty Drums. Palma em três prévias HQ do Freesound, com cauda curta. URLs em `docs/licenses/drum-samples.md`. Sem sample da Pioneer. Se o arquivo não abrir, o motor usa modelagem física. A caixa levou soft clip (drive 2,4) para o corpo sentar mais perto do bumbo. A tela e o Shift+SAMPLER escolhem kick, clap, snare ou kick+clap, 4/8/16 compassos e o gap.
- DSP hospedeiro: ALL PASS. `fx_measure`: FAILS 0. O caminho sintetizado da 0.14.0 continua quando o build está em zero, e o `BUILD_HITS` antigo ainda passa. Tom, tempo e cache `0131` não mudaram.
- Mix: `/opt/cursor/artifacts/demo-mix-drums.mp3`, 1:28,6 (88,583 s), 320 kbps, 3545325 bytes. PCM 0,980. MP3 −4,5 dB, true peak a 192 kHz 0,883 (−1,08 dBTP), sem amostra acima de 1,0. NaN 0. Roteiro em `docs/testing/demo-mix-drums.md`. Os quatro primeiros segundos são a síntese antiga, fora da grade. Ninguém ouviu o ficheiro aqui.
- APK do tablet: `/opt/cursor/artifacts/deckbridge-arm64.apk`, 4467924 bytes, SHA-256 `4c17fecc3dea8307c01be6cba30187b801ae36ec174ab6276d3ae82a18e99d70`, versão 0.14.1 (versionCode 18), só arm64-v8a, minify e shrink. Assinatura de debug. Contém `libdeckbridge-engine.so`, `libsqlcipher.so` e os cinco FLAC. Falta o ícone, como nas versões anteriores. O APK 0.14.0 (4271249 bytes, `071b08d672a836e747b04931fc446f294ff79a9d14c1ea104cbebbb03e87e94b`) fica só neste diário.
- APK com três ABIs: `/opt/cursor/artifacts/deckbridge-debug.apk`, 20064590 bytes, SHA-256 `6cfec4bb1367d3a296a90be06514442d4c36652acfb8c4de731a816349ce0f7a`.
- HIL executado? não. Os passos 13 a 24 continuam por executar.
- Busca por voz (A053) e quatro decks (A054) continuam por fazer.

### Specs e pacote 0.14.1
- Estado: documento. A escuta continua BLOCKED_HARDWARE.
- Commit: `b805a64`.
- `STATUS.md` junta o histórico 0.5.0-palco a 0.14.1, o que a simulação faz, os buracos, o backlog e as decisões OD-01 a OD-24.
- A constituição passa a 1.1.0 (ADR-0006): alvo 1 no tablet, alvo 2 no Pi, USB só leitura.
- Cada tarefa de `tasks.md` tem estado. `PASS` é software ou documento. HIL sem aparelho fica `BLOCKED_HARDWARE`. A053 e A054 ficam `DEFERRED`.
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
- Simulação não conta como estes passos. A lista passou a 16 em 0.9.0. Em 0.10.0 passou a 19. Em 0.11.0 passou a 20. Em 0.12.0 passou a 21. Em 0.12.1 os passos continuam os mesmos: a grade, o tom, a cor e a emenda do loop não são um gesto novo na controladora. Em 0.12.2 a análise de uma pasta sem export também não é um gesto na controladora. Em 0.13.0 os passos 22 a 24 cobrem slip, medidor de fase, MATCHING, SUGGEST, beat jump 64, frase, cues automáticos e auto gain. Em 0.13.1 o tom novo não é um gesto na controladora. Em 0.14.0 o DSP dos efeitos também não fecha passo de controladora. Em 0.14.1 os one-shots e a grade do drum build também não fecham passo de controladora. Os passos 13 a 24 continuam por executar

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

## 2026-10-23 — Tela principal mais limpa: atalhos em popups

- Mudança: a faixa de ~40 botões (beat jump, loop, KEY, grade, SLIP, REV...) saiu da tela principal. Ficou uma linha fina com JUMP, LOOP, KEY, GRID e MAIS; cada botão abre um popup sobre as ondas (como a tela de toque do leitor de club). Tocar fora ou em FECHAR fecha.
- Evidência: `docs/ui/compact-player.png`, `compact-popup-loop.png`, `compact-popup-key.png` (Robolectric/Roborazzi, JVM).
- Status: UI verificada só em simulação; uso no tablet `BLOCKED_HARDWARE`.

## 2026-10-23 — Cabeçalho do deck no padrão do CDJ-3000

- Mudança: a linha de atalhos saiu. BEAT LOOP, KEY SHIFT e BEAT JUMP (mais "···" para SLIP/REV/EMERG/banco/grade) ficam no cabeçalho de cada deck, em duas linhas, com sublinhado quando ativos e preenchimento azul quando abertos. O popup abre como faixa de células de borda laranja logo acima dos cartões, com o beat loop ativo preenchido. Key sob o título e MT ao lado do tempo.
- Evidência: `docs/ui/compact-popup-loop.png`, `compact-popup-key.png`, `compact-player.png`. Só simulação; tablet `BLOCKED_HARDWARE`.

## 2026-10-23 — Fase da grade do analisador

- Problema: em house o pico grave mais forte era um baixo no contratempo; BPM certo, grade 123 a 222 ms fora do bumbo, SYNC alinhava errado.
- Mudança: `refineGridPhase` + `onsetCorrection` em `TrackAnalysis.kt`; teste sintético que falha sem a correção; roteiro de sessão sem o ajuste manual de grade.
- Evidência: nas duas faixas reais, primeira batida 1 ms e 0 ms do bumbo; fase entre decks −0,5 a −1,0 ms sem SYNC. Suíte completa e `assembleMultiDebug` passam.
- Risco: só duas faixas reais e um teste sintético; sem corpus com grade de referência. Gêneros sem bumbo marcado seguem o caminho antigo.

## 2026-10-23 — Controles que faltavam, aviso de falha de áudio e cartão do deck

- SHIFT+PLAY (`reverseroll`): reverso em slip enquanto segura (censor); ao soltar, volta onde a faixa estaria. SHIFT+LOOP IN / OUT: o prato move a entrada ou a saída do loop (2,5 ms por unidade de giro, mínimo de 10 ms entre os pontos) até apertar de novo; o cartão mostra "AJUSTE LOOP IN/OUT".
- Aviso de falha de áudio: o motor conta blocos que demoram mais de 90% do tempo que tocam e quedas do stream (`nativeGlitches`); a barra superior mostra "ÁUDIO: FALHAS (n)" por 15 s depois de cada nova falha (`GlitchWatch`).
- Cartão do deck mais próximo do leitor de club: caixas QUANTIZE e BEAT JUMP à esquerda do tempo, "SINGLE", marcas de tempo restante sob a onda geral; JUMP e Q saíram da coluna da esquerda.
- Evidência: `ShowControlsTest` (4 testes), suíte completa e `assembleMultiDebug` (compila o C++) passam; `docs/ui/compact-player.png`.
- Não verificado: reverso, ajuste de loop e contador de falhas no tablet e na DDJ-400 (`BLOCKED_HARDWARE`). O limiar de 90% do contador é nosso, não medido em hardware. O LED do SHIFT+PLAY não foi mapeado.

## 2026-10-24 — Onda ampliada com a resolução do leitor e presa à grade

- Problema relatado: a onda não batia com a referência (rekordbox/CDJ), as batidas pareciam fora da grade e faltava detalhe.
- Causas medidas no código: (1) cada coluna de 1/150 s era a média de 32 ms, então um bumbo se espalhava por cinco colunas; (2) a tela desenhava uma amostra a cada ~7 colunas (largura/3 pontos para 8 compassos) e ainda suavizava, então os ataques caíam entre pontos e a forma virava bolha; (3) o modo de 3 bandas mapeava o tempo pela duração inteira truncada (até meio segundo de desvio no fim); (4) o teto de 80 000 colunas deixava faixas de mais de 8 min 53 s com menos de 150 colunas por segundo, e a onda escorregava; (5) a normalização por percentil baixo saturava o fundo.
- Mudança: 150 colunas por segundo exatas, janela de 10 ms centrada, referência no percentil 97–98,5; coluna que contém o instante (não a mais próxima); `waveStrip`: uma coluna por pixel com o pico das entradas que o pixel cobre (ou interpolação, quando ampliado), sem suavizar; linhas cheias por pixel no RGB; detalhe de 3 bandas mapeado a 150 por segundo. Cache da análise de pasta passou a "0132".
- Evidência: `WaveDetailTest` (cada clique na sua coluna, no máximo 6,7 ms; faixa de 10 min com 150 colunas/s; um pico aparece no pixel certo em janelas de 1 a 60 s); `docs/ui/onda-antes-depois.png` (mesmo instante do set, antes em cima e depois embaixo).
- Não verificado contra a tela de um CDJ de verdade nem no tablet (`BLOCKED_HARDWARE`).

## 2026-10-24 — Scratch no motor e vídeo de mixagem-show com SYNC

- Mudança:
  - **Scratch real:** `ScratchFollower` em `dsp.h`; `scratchTouch`/`scratchMove` no motor e no JNI. O toque no topo do prato (`JOG_TOUCH`) no modo vinil liga o scratch, e o giro move a cabeça.
  - **Roteiro do vídeo:** `MixShowAgent` (loop roll, scratch, cortes de crossfader, SYNC, buildup RMX, backspin), com a base `DjHands` extraída do `SetAgent`.
  - **`ddj_session`:** novas ops `scratch`/`scratchmove`; a saída de loop ("-1 -1") deixava o deck mudo e foi corrigida.
  - **Onda ampliada:** o detalhe de onda que o cache da pasta não guarda é refeito na carga do deck (`lacksDetail`/`withDetail`).
- Evidência:
  - `fx_rmx_test` (scratchFollows) e `ShowControlsTest` (toque no prato liga e desliga o scratch).
  - `PlainFolderTest` (o cache volta sem detalhe; `withDetail` o repõe com as mesmas colunas).
  - `checks.txt`: fase +0,0 ms com SYNC.
  - Vídeo de 3,3 min fora do git, só com as faixas do usuário.
- Risco / não verificado: a sensação do scratch no prato da DDJ-400 e o backspin no tablet (`BLOCKED_HARDWARE`). Os passos de 5 ms por unidade são nossos e não foram medidos.

## 2026-10-24 — Backspin manual pelo prato

- Mudança: `ScratchFollower` ganhou o giro solto (`coasting`). Ao soltar o topo do prato em movimento, a faixa segue com a velocidade da mão. Ela freia 5x/s até o passo normal do deck (zero se parado) e parte a 8x/s. Tocar de novo para o giro na hora, e `nudge` é ignorado durante o giro solto. O mesmo vale em `ddj_session` e no `SetModel` (espelho para a tela). O vídeo de mixagem termina com um backspin manual.
- Evidência: `fx_rmx_test` backspinCoasts (6 checagens: entra no giro a mais de 4x para trás, termina sozinho em 1,03 s, volta mais de 1,5 s da faixa, para em zero com o deck parado, segurar o prato para o giro). Quadros do vídeo: deck 2 de 71,9 a 67,1 compassos durante o giro.
- Não verificado: as constantes de freio e partida são nossas, não medidas num CDJ ou na DDJ-400. Também não foi verificado se o prato solto da DDJ-400 manda mensagens que conflitem (`BLOCKED_HARDWARE`).

## 2026-10-24 — DDJ-400 virtual no vídeo de sessão

- Mudança:
  - `DdjSurface`: estado físico só pelo MIDI cru, com os bytes do mapa pinado.
  - `DdjPanel`: desenho da controladora; os LEDs vêm de `ledTargets` (`SetModel.leds`).
  - Segunda tela separada: janela própria de 1280 × 480 (`frames-ddj/`), desenhada direto num bitmap. A tela do app segue em 1280 × 720, sem mudança de layout, e o vídeo empilha as duas no ffmpeg.
  - `SET_LEVELS` corrige os medidores que liam os picos do set de seis faixas.
- Evidência: `DdjSurfaceTest` (faders, tempo de 14 bits, prato e toque, modos de pad, log); `docs/ui/ddj-virtual-rmx.png`; vídeo de 3,4 min fora do git.
- Risco: o painel não é captura da DDJ-400 real; ele mostra o que o roteiro manda e o que o app acenderia (`BLOCKED_HARDWARE` para conferir com o equipamento).

## 2026-10-24 — Recursos de pista e efeitos do DJM-V10, Touch Preview e frases

- **Console:**
  - Curvas do fader de canal (topo, gradual, linear) e do crossfader (lenta, média, corte).
  - COMP de um knob por canal e isolador do master (−∞ a +9 dB).
  - O TRIM passou para antes do canal, e o CUE do fone para antes do fader. Antes, com o fader embaixo, não se ouvia a próxima faixa no fone.
  - Tudo segue o manual do DJM-V10 (p. 27–33); as curvas e os valores são nossos.
- **Beat FX:**
  - DELAY: uma repetição.
  - SHIMMER: cauda uma oitava acima.
  - FX FREQUENCY LOW/MID/HI: bandas LR4 em 200 Hz e 2 kHz.
  - Spiral e Helix: o atraso desliza em 0,45 s, e o tom das repetições muda com o tempo.
- **Send/Return interno:**
  - Efeitos SHORT DELAY, LONG DELAY, DUB ECHO e REVERB, com SIZE/FEEDBACK, TIME, TONE e MASTER MIX LEVEL.
  - O envio sai depois do fader de canal; essa escolha é nossa.
- **Touch Preview:** tocar na onda da lista toca 30 s só no fone. Só o trecho é decodificado.
- **Frases no estilo rekordbox:** ver `docs/testing/phrase-annotations.md`. Faz 17/26 a 1 compasso e 22/26 a 4 no corpus anotado; antes eram 13/26.
- **Evidência:**
  - `tools/console_test.cpp`: curvas, COMP, isolador, DELAY, SHIMMER, Spiral, FX FREQUENCY, Send e pré-escuta.
  - `ConsoleTest`, `WavSegmentTest`, `PhraseAnalysisTest`, `PhraseHitRateTest`.
  - Suíte completa: 131 testes, 0 falhas.
  - Prints: `docs/ui/mixer-console.png`, `docs/ui/browse-preview.png`.
- **Não verificado:**
  - Nada foi ouvido na DDJ-400 nem no tablet (`BLOCKED_HARDWARE`), inclusive o fone da pré-escuta.
  - As curvas, o COMP e os tempos do Send não foram medidos num DJM.

## 2026-10-24 — Vídeo explorando o RMX e dois bugs do motor

- Mudança:
  - Roteiro `MixRmxAgent`: as oito cenas, X-PAD, GATE, isolador, CH SELECT e releases.
  - DDJ virtual em 1280 × 720.
  - SceneFx: o Tape STOP solta na hora, e a cena não trava com a grade parada.
  - `MotorGain`: o deck parado não manda mais DC.
  - X-PAD Hi-pass em estéreo.
- Evidência:
  - `fx_rmx_test`: `tapeStopReleases` e `motorStops`.
  - Áudio do roteiro: o trecho depois do Tape STOP volta a 0,44 RMS (antes 0,75, com o deck parado). O BRAKE final cai a 0,00 (antes 0,79 constante).
- Risco: nada ouvido no hardware (`BLOCKED_HARDWARE`). O limiar de 15% do `MotorGain` é nosso.
- O painel virtual pegou um erro de LED, que também estava no app (DeckPage). Ao segurar pela tela uma cena sem pad (Reverb DOWN, Echo DOWN, Tape STOP ou Dub ECHO), acendia o pad do X-PAD de mesmo número: o Tape STOP acendia DRUM. A regra agora fica em `rmxSamplerPads` (`DdjLeds.kt`), usada pelo app e pela simulação. Teste: `DdjLedsTest` (`rmxScenesWithoutPadDoNotLightPads`).

## 2026-10-24 — Motor real testado no desktop (sem tablet)

- Mudança:
  - `tools/engine_host_test.cpp` compila o `engine.cpp` de verdade contra stubs do Oboe e do log (`tools/host_stubs/`).
  - O stub `FakeDevice` simula canais, modo exclusivo, falha de abertura e desconexão.
- Endurecimento do motor:
  - Entradas da JNI saneadas: NaN e infinito caem no padrão, os valores fora da faixa são presos.
  - Saída protegida por `safeOut`.
  - Limitador novo no fone.
  - Correção no release do `PeakLimiter`: ele passava do teto, chegando a 0,9857.
- Evidência (`./engine_host_test [basics|blocks|channels|disconnect|fuzz|threads]`), tudo PASS:
  - Blocos de 64 a 4096 dão saída idêntica bit a bit.
  - Funciona com 1, 2, 3, 6 e 8 canais e com o fallback de 2 canais; taxas de 44,1/48/96 kHz contra arquivos de 22,05 a 96 kHz.
  - Desconexão: refaz o stream e desiste depois de 6 tentativas.
  - Fuzz com NaN, infinito e 1e30: saída finita, master e fone ≤ 0,9801.
  - Quatro threads por 10 s: 55 635 blocos sem NaN.
  - ASan e UBSan limpos.
  - TSan sem nenhum aviso; o limiar caiu para 100 blocos porque o TSan roda cerca de 10× mais lento.
  - `console_test`, `fx_rmx_test` e a suíte Gradle passam; `assembleMultiDebug` compila.
- Não verificado: Oboe real, AAudio e USB do tablet (`BLOCKED_HARDWARE`).

## 2026-10-24 — Fuzz do caminho da controladora (MIDI → ações → DeckPage)

- Teste: `MidiFuzzTest` (Robolectric) compõe o `DeckPage` de verdade e manda 12 000 rajadas por ele.
  - Mensagens do mapa oficial com valores extremos, bytes aleatórios, running status, SysEx, clock e system common.
  - Tudo passa por `MidiAssembler` → mapa → `interpretPerformance` → `onPerformance`.
  - O assembler também é testado com os pacotes quebrados em qualquer byte e clock no meio de mensagem.
- Achados e correções:
  1. **Crash no ajuste de loop** (`adjustLoopPoint`): o app caía quando o loop já passava do fim da faixa ou tinha menos de 10 ms (`coerceIn` com faixa vazia). Em show, isso acontece com SHIFT+LOOP OUT e o prato. Corrigido; o caso entrou no `ShowControlsTest`.
  2. **System common virava running status** (`MidiAssembler`): um 0xF2/0xF3/0xF6 fazia os bytes seguintes saírem como mensagens com status 0xF2. Agora cancela o running status, como manda a especificação MIDI.
- Evidência: suíte com 135 testes, 0 falhas.
- Não verificado: tráfego real da DDJ-400 (`BLOCKED_HARDWARE`).

## 2026-10-24 — Fuzz dos parsers da biblioteca e garantia de só leitura

- `LibraryFuzzTest`:
  - Fuzz de 600 rodadas em `parseExportPdb`, `parseExportExt`, `parseAnlz`, `decodeWav`, `decodeWavSegment` e `decodeWavMono`.
  - Mais 150 cargas da biblioteca inteira com vários arquivos estragados ao mesmo tempo.
  - Mutações usadas: bits trocados, cortes, campos de 32 bits com -1, `Int.MAX_VALUE` e 0x7FFF0000, lixo e apêndices.
  - Resultado: nenhuma exceção e nenhum laço preso.
- Só leitura em disco:
  - O volume sintético e uma faixa solta vão para uma pasta sem permissão de escrita.
  - Depois do `loadRekordbox` e do `loadPlainFolder`, o SHA-256, o tamanho e o mtime de todos os arquivos ficam iguais, e nenhum arquivo novo aparece.
  - O cache fica fora do volume.
- **Achado: a exportação da lista do set podia gravar no pendrive.**
  - O seletor do sistema (`CreateDocument`) aceita qualquer destino, inclusive o pendrive da biblioteca.
  - Agora `exportTargetOnLibraryVolume` recusa um destino no mesmo armazenamento de um volume autorizado. O app não grava nada e remove o arquivo vazio que o seletor criou.
  - Teste: `exportToTheLibraryStickIsRefused`.
- Evidência: suíte com 138 testes, 0 falhas.
- Não verificado:
  - Ids de documento do provedor de armazenamento externo num tablet real (`BLOCKED_HARDWARE`). O formato `XXXX-XXXX:caminho` é o do AOSP.
  - Remover o arquivo vazio é a única escrita possível no pendrive, e só acontece depois que o próprio seletor criou o arquivo.

## 2026-10-24 — Bring-up da controladora e remapeamento sem recompilar

- Mudança:
  - `BringUpChecklist`: são 121 mensagens do mapa oficial, sem SHIFT e sem o byte baixo dos controles de 14 bits. Cada uma é marcada quando chega. As mensagens fora do mapa ficam listadas com a contagem.
  - Na tela de diagnóstico: um painel com o progresso e os controles ainda não vistos. Tocar num deles e mexer no controle físico grava o remapeamento (`MidiRemap`, `0x9F:0x10=0x90:0x0B`). O remapeamento fica salvo e pode ser limpo.
  - O relatório compartilhado leva o checklist.
  - Note-off (0x8n) chega como note-on com velocidade 0.
- Desempenho:
  - O monitor MIDI deixou de formatar texto e de mudar o estado na raiz a cada mensagem. Antes, cada passo do prato recompunha a tela dos decks.
  - Agora as mensagens ficam cruas num anel e o texto só sai com o diagnóstico aberto.
- Evidência:
  - `BringUpTest`: checklist, desconhecidas, remapeamento e note-off.
  - Suíte com 141 testes, 0 falhas.
  - Print: `docs/ui/diagnostico-bringup.png`, com dados de exemplo.
- Não verificado: a DDJ-400 real (`BLOCKED_HARDWARE`). O primeiro uso com ela deve ser exatamente esse checklist.

## 2026-10-24 — Otimização, rodadas 1 e 2 (CPU, RAM, picos no áudio)

- Análise: relatório de CPU e RAM feito por um agente Opus numa cópia de trabalho (`docs/performance/relatorio-cpu-ram.md`), com o harness em `tools/perf_harness.cpp`.
- Rodada 1 (P0–P5):
  - FTZ no callback, memo de pow/exp e cache de coeficientes.
  - Linhas de atraso sem `%`.
  - Fdn com LFO em quadratura e linhas em potência de 2.
  - Beat FX que dorme quando está desligado e sem cauda.
  - WSOLA com tabela de deslocamentos.
  - Resultado no x86 deste host: ocioso 168→62 ns/quadro, típica 355→160, pior RMX 2216→1522.
  - A saída só muda pelo LFO do Fdn (−58 dB).
- Rodada 2:
  - Nenhuma alocação no callback: tudo é preparado em `start()`.
  - Limpar uma linha de atraso ficou O(1).
  - O FLAC de bateria é decodificado fora do lock.
  - Faixa e pré-escuta em int16, bit a bit iguais para fontes de 16 bit.
  - Catálogo do app sem as ondas de detalhe.
  - PCM em Java liberado mais cedo.
  - UI sem lixo por quadro (onda e grade) e sem recompor com os decks parados.
  - Números em `docs/performance/README.md`.
- TSan e o motor:
  - Com todos os testes do motor rodando, o TSan achou a thread de nova tentativa feita com `detach()`. Agora ela é guardada e o destrutor espera por ela.
  - Também achou uma corrida no stub de teste, que ficou atômico.
- Proposta ADR-0007 (Pi 1 com o motor próprio): está em estado PROPOSTA e precisa de decisão do dono, porque o AGENTS.md manda começar o alvo 2 pelo Mixxx.
- Evidência:
  - `fx_rmx_test` 88/0, `console_test` 40/0, `fx_measure` 13/0, `dsp_test` ALL PASS e `engine_host_test` FAILS 0.
  - ASan+UBSan: 51 PASS e 0 erros. TSan na suíte inteira do motor, já com a correção da thread: 50 PASS e 0 avisos.
  - Dump das 46 cenas bit a bit igual entre as rodadas 1 e 2.
  - Suíte JVM com 142 testes e 0 falhas. APK compila para arm64, armeabi-v7a e x86_64.
- Não verificado:
  - Nada medido no tablet nem num Pi (`BLOCKED_HARDWARE`). Os números do Pi são estimativas.

## 2026-10-25 — Pi, opção C, fase 1: motor no Linux/ALSA

- Decisão do dono: opção C da ADR-0007, agora aceita (`docs/adr/0007-pi-motor-proprio.md`). O motor próprio vai num Pi Zero 2 W ou Pi 3 com Raspberry Pi OS 64 bit. O Mixxx (opção A) continua sendo a trilha normativa do alvo 2.
- Mudança:
  - `pi/alsa/oboe/Oboe.h`: um Oboe sobre ALSA. Thread SCHED_FIFO; formato float, S32, S24_3LE ou S16; x-runs; desconexão chamando `onErrorAfterClose` fora da thread de áudio.
  - `pi/deckbridge_pi.cpp`: o mesmo `engine.cpp`, sem tela, com `mlockall`, WAV direto para int16 e o estado impresso a cada segundo.
  - `pi/build.sh` para nativo e para aarch64.
  - `pi/deckbridge.service` (systemd com rtprio e memlock) e `pi/README.md`.
  - `engine.cpp`: só um `#ifndef DECKBRIDGE_NO_JNI` em volta do JNI. O APK continua exportando as 41 funções JNI.
- Evidência:
  - x86 com o PCM `null`: 2 decks em tempo real, 0 x-runs, 0 falhas. CPU de 1,4 %, ou 2,5 % com SHIMMER.
  - Captura pelo plugin `file`: master nos canais 1–2 (RMS 0,27, pico 0,98) e fone em silêncio.
  - aarch64 sob qemu: abre e toca com 0 x-runs.
  - `engine_host_test` FAILS 0. APK compila.
- Ferramentas, todas pelo apt assinado: g++-aarch64-linux-gnu 13.3.0, libasound2-dev(:arm64) 1.2.11-1ubuntu0.3 (ports.ubuntu.com para o arm64), qemu-user 8.2.2.
- Não verificado: Pi, DDJ-400 no Pi, latência e temperatura (`BLOCKED_HARDWARE`).
- Próximo: MIDI da DDJ-400 por ALSA e uma ADR da interface com medida.

## 2026-10-25 — Pi: aarch64 igual ao x86 e decisão da interface (ADR-0008)

- Correção numérica no aarch64: o dump das 46 cenas, compilado para Cortex-A53 e rodado sob qemu-aarch64, foi comparado ao x86.
  - Com FMA (padrão do GCC): −93,7 dB, maior diferença 0,0002.
  - Com `-ffp-contract=off`: −110 dB. O resto vem da libm.
  - Inaudível. O código está certo no aarch64.
- Interface do Pi, medida com protótipos da mesma cena (1024×600, 30 qps, Xvfb):
  - SDL2 software: 15 MB de RSS, 7 MB privados, 4,0 % de CPU.
  - Compose Desktop: 151–243 MB de RSS, 128–180 MB privados, 29–39 % de CPU.
  - Decisão (ADR-0008): interface nativa em C++ com SDL2 no Pi, com a lógica de controle portada e verificada por vetores de referência gerados do Kotlin.
- Nota de ambiente: o Gradle, baixando em paralelo pelo proxy deste ambiente, dava "Could not find" em artefatos que existem; `--max-workers=1` resolveu. Nada disso afeta o build do app, que roda `--offline`.

## 2026-10-25 — Pi, fase 2a: DDJ-400 no Linux (lógica de controle em C++ e rawmidi)

- Mudança:
  - `pi/control/controller.h`: porta, regra por regra, de MidiMap, MidiAssembler, TempoAssembler e interpretPerformance. O mapa vira uma tabela O(1) por (status, nota).
  - `pi/control/deck_control.h`: aplica as ações ao motor com a regra do `onPerformance` do DeckPage. Cobre mesa, transporte, prato em modo vinil, pitch e SYNC por BPM. O que ainda não é suportado é contado.
  - `pi/control/midi_in.h`: rawmidi do ALSA, com a porta escolhida por filtro de nome ou `DECKBRIDGE_MIDI_DEVICE`.
  - `deckbridge_pi --midi --map`.
- Verificação:
  - `ControllerGoldenTest` (Kotlin) grava `pi/control/testdata/golden.txt`: 6000 pacotes MIDI com running status, note-off, SysEx, clock, system common e lixo.
  - `pi/control/controller_test.cpp` exige as mesmas ações: 4617 mensagens e 2763 ações, todas iguais.
  - O teste Kotlin falha se o arquivo ficar velho em relação ao Kotlin.
  - O redutor recebe as 2763 ações num motor falso e só passa valores finitos.
  - Custo: 78–99 ns por mensagem de prato no x86.
  - Suíte JVM com 143 testes e 0 falhas. Build aarch64 ok.
- Não verificado: leitura rawmidi de uma DDJ-400 real (este PC não tem porta MIDI) (`BLOCKED_HARDWARE`).
- Ambiente: o Maven Central devolveu 429 (limite de pedidos) depois do download do protótipo Compose. Uma tentativa com `--refresh-dependencies` gravou artefatos como ausentes no cache do Gradle; a resolução em série (`--max-workers=1`), depois de esperar, recuperou o build offline.

## 2026-10-25 — Pi: LEDs, leitor do pendrive, decodificação e sessão

- LEDs (`pi/control/leds.h`): porta do DdjLeds.kt. Contra o `LedGoldenTest` deu 800 estados e 77 877 bytes iguais. Saída por rawmidi a 20 Hz, só com o que mudou.
- Pendrive (`pi/library/rekordbox.h`):
  - Porta de DeviceSql.kt, Anlz.kt e mergeAnalysis.
  - O `RekordboxGoldenTest` grava o volume sintético (só `PIONEER/`, 1,6 MB, gerado por nós) e o resumo do que o Kotlin lê dele.
  - O C++ escreve o mesmo resumo: 26 faixas, campos, cues, CRC das ondas e frases. Leitura em 9,9 ms.
  - 3000 arquivos estragados sob ASan/UBSan: nenhuma leitura fora da faixa.
- Decodificação (`pi/audio/decode.h`): WAV, MP3 (dr_mp3 0.7.4) e FLAC (dr_flac) direto para int16. O teste parte de um WAV sintético; o MP3 e o FLAC são gerados dele pelo `lame` 3.100 e pelo `flac` 1.4.3, só no PC.
  - WAV e FLAC: bit a bit iguais.
  - MP3 a 320 kbps: SNR de 31,5 dB.
  - MP3 V5: SNR de 23,8 dB.
  - MP3: comprimento exato e atraso 0.
  - AAC/M4A ainda não decodifica no Pi.
- Achado do fuzz: o dr_flac tem estouro de inteiro com sinal (UB) com FLAC estragado. `-fwrapv` no build do Pi e no CMake do app torna o estouro definido. Com ele, 180 arquivos estragados passam limpos sob ASan/UBSan.
- Sessão (`pi/app/session.h`):
  - Catálogo do pendrive, só leitura.
  - Carga com BPM, grade e cue principal como o DeckPage, mais as hot cues.
  - `deckbridge_pi --usb PASTA --track N`.
  - Ponta a ponta com o PCM null: o pendrive estava sem permissão de escrita, as faixas carregaram (WAV e MP3) e tocaram com 0 falhas, e os 157 arquivos ficaram com o mesmo SHA-256.
- Licenças: NOTICE e SBOM ganharam dr_flac (que estava sem registro), dr_mp3, alsa-lib, SDL2 e SDL2_ttf.
- Não verificado: pendrive real do rekordbox (`BLOCKED_HARDWARE`). Só o volume sintético foi lido.

## 2026-10-25 — Pi: tela nativa SDL2 (ADR-0008)

- `pi/ui/deckbridge_ui.cpp`: o layout do app em 1024×600 e 800×480.
  - Barra de estado e navegação.
  - Ondas de detalhe com grade, compassos e hot cues.
  - Painel lateral e hot cues A–H.
  - Cartões com título, REMAIN, TEMPO, BPM e visão geral.
  - Lista do pendrive com BROWSE/LOAD da DDJ-400, ou pelo toque. A carga roda noutra thread e a tela volta às ondas, como no leitor.
  - Textos em textura com cache LRU: o quadro estável não aloca.
- Demonstração sem controladora (`--midi-demo`): bytes do mapa oficial pelo mesmo caminho do rawmidi, com BROWSE ×3, LOAD no deck 2 e PLAY nos dois. Funcionou do jeito esperado (print `docs/ui/pi-1024x600-ddj-demo.png`).
- Medido no x86 por Xvfb: CPU de 8,4 % e RSS de 54 MB com 2 decks a 30 qps.
  - A pilha das threads ficou em 512 KB (`pthread_setattr_default_np`). Com o `mlockall`, as pilhas de 8 MB davam 70 MB.
  - O `deckbridge_pi` foi de 30 para 22,5 MB.
- O binário aarch64 renderiza a mesma tela sob qemu (print `pi-800x480-arm64-qemu.png`).
- Concorrência:
  - TSan na tela com a demonstração: 0 avisos.
  - Revisão manual achou `pitch` e `bpm` lidos por outra thread no `DeckControl`. Viraram atômicos.
- Não verificado (`BLOCKED_HARDWARE`): Pi real com KMSDRM, tela de 7", toque e DDJ-400.

## 2026-10-25 — Lógica única: controle e LEDs da DDJ-400 em C++ para o app e o Pi

- Pedido do dono: "a lógica e o motor é um só no projeto né?". Antes desta mudança o motor era um só, mas o controle tinha duas implementações (Kotlin e C++).
- Mudança:
  - `controller.h` e `leds.h` foram para `android/app/src/main/cpp/`.
  - `controller_jni.cpp` entra no `libdeckbridge-engine.so`.
  - `ControllerCore.kt` carrega a biblioteca nativa, ou, nos testes JVM, a biblioteca do host que o Gradle compila (`buildHostControl`, g++). Robolectric com outro classloader: carrega uma cópia.
  - `MidiAssembler`, `TempoAssembler`, `interpretPerformance`, `performanceAction`, `surfaceAction`, `pitchFromTempo14`, `nextTempoRange`, `ledTargets`, `LedDiff` e `beatLoopIndex` viraram fachadas com a mesma API. A regra Kotlin saiu.
  - R8: `-keep` explícito do ControllerCore. O dex da release mantém os nomes.
- Evidência:
  - 145 testes JVM com 0 falhas, já rodando o C++. Inclui o `MidiFuzzTest` no DeckPage real, o golden do controle e o dos LEDs.
  - O APK leva as 8 funções JNI novas, além das 41 do motor.
  - Testes do Pi passam com os arquivos movidos.
- Continua com duas implementações: o leitor do pendrive (Kotlin no app, C++ no Pi). O `RekordboxGoldenTest` e o `rekordbox_test` os prendem ao mesmo resumo.

## 2026-10-25 — Mixagem de demonstração na tela do Pi (2 faixas, Beat FX, RMX, loops)

- Pedido do dono: "Faça uma mixagem de exemplo na tela do pi com duas músicas, efeitos, rmx e tal".
- `DeckControl` (Pi) ganhou o que a demonstração usa. A regra é a mesma do app:
  - Beat FX: SELECT, CH, LEVEL e ON, com os 17 nomes.
  - Painel RMX: cenas nos pads 1–4 com subida enquanto o pad está seguro e queda ao soltar, X-PAD nos pads 5–8 e intensidade no LEVEL. Com o painel aberto o Beat FX desliga.
  - Loops: BEAT LOOP, IN/OUT, RELOOP e ½×/2×. BEAT JUMP.
  - SYNC com fase pela grade.
  - Testes novos em `controller_test`, todos PASS.
- A tela desenha o painel Beat FX/RMX e o loop sobre a onda. Ganhou `--script` (roteiro em compassos) e `--record` (quadros BGRA a 30 qps de relógio).
- `pi/demo/mix_demo.py` gera o roteiro a partir do mapa oficial da DDJ-400, pelo nome do controle.
- Correções no caminho:
  - O roteiro esperava posição. Um loop impedia o deck de chegar ao compasso seguinte e a primeira tomada travou no compasso 9. Agora conta tempo tocado.
  - As oito cenas não cabiam em meia largura ("Reverb DOWN"). Agora é uma cena por linha.
- Evidência, PC x86 com Xvfb e ALSA `file`:
  - 96,6 s de vídeo e 96,6 s de áudio, com 2899 quadros.
  - CPU do processo de 17 % de um núcleo, com motor e tela no mesmo processo.
  - Pico do master em −0,2 dBFS antes do limitador da exportação.
  - O vídeo e as faixas ficaram fora do repositório: são da sessão local do dono.
- Não verificado (`BLOCKED_HARDWARE`): no Pi real, com a DDJ-400 e o som numa placa de verdade. Ninguém ouviu a mixagem; não há julgamento de ouvido.

## 2026-10-25 — Library Manager de desktop: leitura, edição manual e gravação segura do pendrive

- Pedido do dono: uma versão para o computador, fora da performance, para criar playlists, organizar e (no futuro) classificar, renomear e preencher metadados, **com escrita no pendrive** no padrão da Pioneer. Agora a interface e a parte manual; a automação fica para depois.
- Decisão: ADR-0009. Escrita só no programa de desktop, por pedido explícito, com backup local, troca atômica, verificação e restauração. O ADR-0002, o AGENTS.md e a spec (OD-25) foram emendados só para esse programa. Performance continua somente leitura.
- Tecnologia: Compose Desktop em build Gradle separado (`desktop/`), compilando os mesmos fontes de `logic/` do app (puro JVM). Alternativa descartada: SDL2, que foi medido para o Pi e não serve a telas de tabela e formulário.
- Núcleo (`desktop/src/main/kotlin/os/deckbridge/manager/`):
  - `pdb/RawPdb.kt`: leitura crua das tabelas; gravação que só reescreve as tabelas editadas, em páginas novas no fim.
  - `pdb/Rows.kt`, `pdb/Library.kt`: linhas de faixa, nomes e playlists; editor com metadados e playlists.
  - `volume/SafeWriter.kt`, `volume/Verifier.kt`: backup, troca atômica, conferência, restauração.
  - `ManagerModel.kt` e `ui/`: fila de mudanças, telas.
- Referência externa: especificação aberta do Crate Digger (`rekordbox_pdb.ksy`, commit `7c9d535`, EPL-2.0/MPL-2.0/LGPL-3.0), compilada para Python com o compilador Kaitai 0.11.0 (`desktop/tools/setup_kaitai.sh`). Nada disso entra no repositório.
- Erros achados e corrigidos no caminho:
  - Eu lia o id do artista no byte 0 da linha, mas ele fica no byte 4. Os testes de referência quebrada pegaram na hora.
  - O teste de "artista sem faixas sai do banco" escolhia um artista que também era de álbum, que o gravador mantém de propósito.
  - Na interface: cabeçalhos de Título/Artista/Álbum sem largura, painel modal que cobria só o topo e texto de status cortado. Achados olhando as capturas, corrigidos.
- Evidência (PC x86_64): 31 testes, 0 falhas, 0 ignorados (detalhe em `desktop/README.md`). Três leitores independentes (Kotlin, C++ do Pi, Kaitai) veem o que o plano diz. A janela real abriu sob Xvfb. Capturas em `docs/ui/desktop-*.png`.
- Não verificado (`BLOCKED_HARDWARE`): nenhum player nem o rekordbox leu um banco gravado por este programa; nenhum pendrive real foi usado; sem export real do rekordbox para comparar byte a byte. Risco registrado no ADR-0009 e no README.
- Próximo: importar áudio com análise, escrever ANLZ (grade e cues), renomear arquivos, remover faixa, compactar pela tela.

## 2026-10-25 — Library Manager, fase 2: ANLZ, importar, renomear, remover, organizar, arrastar

- Pedido do dono: "faça tudo que falta". Implementado: escrita de ANLZ (grade e cues), editor de cues e grade, importar áudio com análise, renomear arquivos por padrão, remover da biblioteca, compactar o banco, assistente Organizar por regras, análise sob demanda, arrastar e soltar.
- Não implementado, de propósito (detalhe e motivo no ADR-0009 e no `desktop/README.md`): pendrive novo do zero, classificação/pontuação por modelo de aprendizado, etiquetas dentro dos arquivos de áudio.
- Arquitetura:
  - `anlz/`: edição por seções (`AnlzModel`), cues com reaproveitamento das entradas intactas (`CueIo`), grade (`GridIo`), conjunto completo para faixa nova (`AnlzBuild`).
  - `volume/FileOps.kt`, `SafeWriter.kt`: operações Put/CopyIn/Move, conferência prévia, execução com desfazer em ordem inversa, backup que registra criados e renomeados, restauração que nunca apaga áudio, compactação.
  - `importer/`: leitura de WAV e ffmpeg/ffprobe opcionais; análise com o analisador do app.
  - `organize/`: regras e padrões de nome; análise sob demanda.
  - `ui/`: editor de cues, importar, renomear, organizar, arrastar e soltar.
- Achados e correções no caminho:
  - Esta sessão rodava com locale ASCII: o Java não cria arquivo com "Á" no nome. A gravação desfez tudo direito, mas a mensagem era ruim. Agora há uma checagem prévia com a instrução (`LANG=C.UTF-8`) e os testes rodam com UTF-8.
  - **Disco cheio:** meus testes criavam um pendrive sintético de vários MB em `/tmp` por execução e nunca apagavam; dezenas de execuções esgotaram o disco, e uma rodada inteira falhou por isso (o Gradle também ficou com o cache de execução travado). Os testes agora apagam suas pastas ao terminar. Só apaguei as pastas `dbm-*` que eu mesmo criei.
  - O leitor do app numera o hot cue de 0 a 7 (A = 0); eu havia comparado com 1 a 8 num teste.
  - O título "Padrão — campos" em maiúsculas escrevia `{ARTISTA}`; trocado por um texto que preserva a caixa.
  - Ogg Vorbis guarda as etiquetas no fluxo de áudio e não no formato; o programa só lia as do formato. O teste com ffmpeg achou e agora as duas são lidas.
  - O README dizia que WAV estéreo, de 24 bits e float eram lidos sem que nenhum teste cobrisse isso; escrevi o teste (`AudioIoTest`) antes de manter a frase.
- Evidência (PC x86_64, Linux): 65 testes, 0 falhas, 0 ignorados. Quatro leitores independentes veem o que o plano diz (app Kotlin, Pi C++, Kaitai do banco, Kaitai do ANLZ). Uma falha injetada no meio da gravação desfaz tudo. Capturas em `docs/ui/desktop-07…11`.
- Não verificado (`BLOCKED_HARDWARE`): nenhum player nem o rekordbox leu o que o programa grava; sem export real para comparar; o gesto real de arrastar com o mouse e de soltar arquivos do gerenciador não foi exercitado; MP3, FLAC, M4A e OGG só foram testados com arquivos gerados pelo ffmpeg desta máquina a partir de um WAV sintético.

## 2026-10-25 — Biblioteca local, exportar para pendrive e app de celular

- Pedido do dono: salvar também no aparelho; fazer o mesmo no celular (em pé e deitado, Galaxy S25 Ultra como referência): analisar, montar playlists, marcar faixas e exportar para pendrive. ADR-0010.
- Entregue:
  - `ExportFactory`/`ExportTemplate`: `export.pdb` (20 tabelas) e `exportExt.pdb` (9) criados do zero. Conferido contra o export vazio real do rekordbox: **arquivos idênticos byte a byte** (ajustes no `RawPdb.build`: ordem das páginas vazias reservadas, índice de tabela vazia, arquivo termina sem a página zerada final, sequência por tabela).
  - `transfer/`: `Storage` (pasta e SAF), `LocalLibrary`, `UsbExporter` (confere origem e espaço, backup local do banco do destino, copia sem apagar nada, banco por último, relê e confere, devolve o banco antigo se a conferência falhar), `UsbImporter` (só lê o pendrive).
  - `mobile/`: app Compose com layout em pé e deitado, `SafStorage` (troca de arquivo por renomear em ordem segura), `MediaDecoder` (MediaCodec).
  - Desktop: abrir biblioteca local, exportar e trazer.
- Evidência (PC x86_64): core 75 testes, desktop 6, mobile 11, 0 falhas. A biblioteca criada do zero e exportada é lida igual pelo leitor do Pi (C++) e pelo Kaitai. Exportação completa por provedor de documentos de brinquedo (Robolectric). APK de debug montado.
- Achado: o `query` antigo do Robolectric é recusado pelo `DocumentsProvider` real; o teste usa um provedor próprio (`FakeDocumentsProvider`).
- Não verificado (`BLOCKED_HARDWARE`): app no S25 Ultra; MP3/FLAC no MediaCodec; pendrive USB real por SAF; qualquer player lendo o resultado. Detalhe em `mobile/README.md`.
- Próximo: teste no aparelho e em cópia de pendrive; importação em lotes menores; juntar bibliotecas (hoje exportar/trazer substituem).

## 2026-10-25 — Rodada de UX 1: atalhos para o que se repete

- Pedido do dono: processo longo de melhoria da interface, muito intuitiva, com mecanismos para operações repetitivas e comuns. Esta é a primeira rodada (a lista do que sai dela e do que ficou está abaixo).
- Entregue em `core/smart` (compartilhado) e no celular/desktop: filtros rápidos com contagem; busca com filtros escritos; salvar lista como playlist; playlist alvo; ordenar playlist (BPM, nota, harmônica); refazer; "Primeiros passos"; pré-checagem de exportação com correção em um toque; triagem (celular); estrelas na linha; editar em lote; Arrumar tudo; analisar todas; navegar entre faixas; aviso com DESFAZER; importação e análise em lotes de 6.
- Evidência (PC x86_64): core 83 testes, desktop 6, mobile 19 (inclui `UxFlowTest`, com toques simulados), 0 falhas. 12 capturas do celular em `docs/ui/mobile-*.png` (S25 Ultra em pé e deitado).
- Achados vistos nas capturas e corrigidos: guia ocupava metade da tela (agora recolhe), barra de salvar espremia o texto, botões da triagem cortados no celular deitado, cores cortadas no painel lateral, linha de ordenação sobrando (virou um botão).
- Não verificado (`BLOCKED_HARDWARE`): uso real no S25 Ultra; pré-escuta (MediaPlayer). Também não medido: se os atalhos de fato aceleram o trabalho; precisa de uso com a biblioteca real.
- Próximas rodadas candidatas: gestos (deslizar na linha), importar de pasta inteira, mesclar bibliotecas, padrões de nome salvos, ações em lote na triagem por teclado/fones, teste de usabilidade com a biblioteca real.

## 2026-10-25 — Correção: o celular tinha saído do design system do player

- Observação do dono: o app de biblioteca fugiu do design system de referência (rekordbox/player). Procedente: eu havia criado paleta própria (verde, cantos arredondados, componentes Material), e o `Pal` do desktop também tinha valores diferentes do `StagePalette` apesar do comentário dizer o contrário.
- Correção: `Palette` do celular e `Pal` do desktop passaram a derivar do `StagePalette` (tokens novos adicionados a ele: `selectedRow`, `activeFill`, `activeLine`, `keyFill`, `keyInk`, `mutedInk`, `danger`, `dangerBg`). Botões retos com texto azul e ativo azul-escuro com contorno; fileira de botões iguais embaixo e coluna de ícones deitado; lista com disco, miniatura de onda, BPM monoespaçado e tom compatível em verde; cabeçalho que ordena ao toque; hot cue 2×4; faixa âmbar para avisos; MENU em linhas rótulo/valor. Seção 6 do `docs/ui-spec.md` documenta a tabela.
- Guarda contra nova deriva: `DesignSystemTest` (4 testes) falha com cor escrita à mão, `RoundedCornerShape`/`CircleShape`/botões do Material fora do tema, ou valores diferentes do `StagePalette`; confere contraste (texto, botão azul, texto secundário, tom compatível).
- Achado no caminho: um conjunto de capturas falhou por "Compose não ficou ocioso" quando duas execuções do Gradle rodaram ao mesmo tempo; sozinhas, as mesmas 12 capturas passam. Não rodar duas ao mesmo tempo.
- Não verificado: comparação lado a lado com o rekordbox real (só com as telas do player do projeto e com a descrição do `ui-spec.md`); uso no S25 Ultra.

## 2026-10-25 — Celular mais limpo: menos texto, telas leves

- Pedido do dono: experiência clean, sem textos longos e sem telas carregadas.
- Feito: topo de uma linha (área + contagem, busca escondida atrás do ⌕, importar no ＋); uma única fileira de filtros (com a playlist alvo e "＋ Playlist" quando há filtro); "Primeiros passos" virou uma linha "PRÓXIMO …"; linha de faixa de 60 dp (disco, título/artista, estrelas só quando há nota, BPM apagado quando falta análise, tom; ponto laranja para mudança não salva); barra de salvar numa linha (contador, ↶ ↷, SALVAR); detalhe da faixa em três abas (MARCAR · CUES · INFO); playlists com ⋯ no lugar de botões espalhados; pendrive com três linhas e um botão (EXPORTAR também salva antes, se houver fila); avisos de uma ou duas palavras; explicações só nas confirmações.
- Removido da frente (continua no MENU): Arrumar nomes, Analisar todas, Descartar mudanças, ajuda de busca. Estrelas tocáveis na linha saíram (nota pela triagem, pelo detalhe ou pela seleção).
- Evidência: mobile 25 testes, 0 falhas (inclui `UxFlowTest` novo: busca escondida até pedir e ✕ limpa). Capturas `docs/ui/mobile-*.png` (14).
- Não verificado: uso real no S25 Ultra; se os textos curtos continuam claros para quem nunca usou.

## 2026-10-25 — Bug no S25 Ultra: a pré-escuta continuava tocando depois de fechar o app

- Relato do dono (primeiro uso no aparelho): fechou o app e a música ficou tocando para sempre.
- Causa: o `MediaPlayer` da pré-escuta não era parado quando a tela saía (`onStop`/`onDestroy`). Pior: cada recriação da tela criava um `ManagerApp` novo, e o player do antigo ficava órfão, sem nenhum botão que o alcançasse.
- Correção: a tela para a pré-escuta em `onStop` e `onDestroy`; `ManagerApp.get()` dá um só estado por processo (a tela recriada reencontra o mesmo player); a pré-escuta pede foco de áudio e para quando outro app pega o som (ligação, música); fone desconectado (`ACTION_AUDIO_BECOMING_NOISY`) para na hora; fechar a faixa ou passar para outra também para.
- Prova: `PreviewLifecycleTest` (4 testes, player simulado do Robolectric). Com a correção retirada, os dois testes de sair/fechar falham; com ela, passam.
- Achado no caminho: a classe que abre a atividade de verdade deixava estado que fazia as capturas seguintes esperarem para sempre por "ocioso" (explica também a falha intermitente vista antes). Testes agora rodam uma JVM por classe (`forkEvery = 1`); a causa exata dentro do Compose/Robolectric não foi isolada.
- Evidência: mobile 29 testes, 0 falhas. Não verificado no aparelho: precisa confirmar no S25 Ultra que fechar, trocar de app, ligação e desconectar o fone param o som.

## 2026-10-25 — Enxugar 2 e 3: onda colorida, triagem, desktop limpo

- Celular: a aba CUES usa a prévia colorida do player (PWV4, grave/médio/agudo em RGB, espelhada), com a grade só como marcas curtas para não cobrir a onda; sem PWV4, a prévia azul. `TrackFilesView` ganhou `colorHeights`/`colors` (teste no core: faixa importada traz prévia colorida não vazia). Estrelas e as oito cores ocupam a largura toda. Triagem: ✕, contador 4/10, título, artista e uma linha "BPM · tom · tempo", OUVIR, cinco estrelas grandes (tocar a 3ª dá 3 e avança), cores, alvo, ‹ / PULAR ›; sem rótulos nem painel.
- Desktop: topo com BIBLIOTECA, nome, contagem, Importar, Exportar e ⋯ (Organizar, Renomear, Trazer, Cópias, Abrir outra); boas-vindas com três botões (Neste computador, pendrives achados, Outra pasta); filtros com rótulos curtos; inspetor com cabeçalho da faixa (BPM · tom · tempo), nota no topo e tom/selo/ano/nº em "Mais"; barra de baixo "● 3 na fila · ↶ · Descartar · Gravar…"; confirmação de gravar numa linha; botões de playlist só quando há uma escolhida.
- Evidência: mobile 29 testes, desktop 6, core (ManagerModelTest) verde. A demonstração usa faixas de clique sintético: a onda colorida sai de uma cor só; com música real ela varia.
- Não verificado: aparelho e uso real.

## 2026-10-25 — Rodada 4: ouvir pela onda, playlists na faixa, importar pasta, pular repetidas

- Onda colorida pequena na aba MARCAR e na triagem: tocar num ponto ouve dali; enquanto toca, a cabeça anda (posição do MediaPlayer a cada 100 ms, só enquanto toca).
- MARCAR mostra as playlists como botões: tocar coloca ou tira a faixa; "＋" cria uma nova já com ela. Vibração curta ao marcar (playlists e notas da triagem).
- Importar pasta inteira (MENU e tela vazia), com subpastas, em ordem alfabética.
- Importação pula a música que já está na biblioteca (mesmo título e artista, duração a até 2 s; `Smart.findDuplicate`, com teste) e repetidas dentro do mesmo lote; o aviso diz "N importadas · N já estavam · N falharam".
- Evidência: mobile 30 testes (novo: chip de playlist liga e desliga em um toque), core `SmartTest` verde.
- Não verificado: aparelho (vibração, cabeça andando com áudio real, seletor de pasta com pendrive/cartão).

## 2026-10-25 — Rodada 5: juntar a biblioteca do pendrive sem substituir

- Antes, "trazer do pendrive" só substituía a biblioteca local. Agora o padrão é juntar: cada faixa que ainda não existe aqui (mesmo título, artista e duração) vem com áudio, análises (o caminho do áudio dentro do ANLZ é reescrito para o novo lugar), nota, cor, comentário, campos e as playlists de mesmo nome (criadas se faltarem). Nada é apagado de nenhum lado e o pendrive só é lido. Grava em lotes de 20, cada lote com cópia de segurança.
- Núcleo: `Edit.AdoptTrack` e `UsbMerger`. Celular: "TRAZER DO PENDRIVE" pergunta Juntar (primeira opção) ou Substituir. Desktop: no diálogo de trazer, Juntar/Substituir.
- Evidência: core 87 testes (`MergeTest`: faixas, marcações, grade, playlists, PPTH apontando para o áudio local, pendrive intocado, juntar de novo não duplica, caminho ocupado vai para "(2)"); mobile 31 (juntar pelo seletor de documentos de brinquedo); desktop compila e passa.
- Também com origem real: `MergeRealExportTest` junta o export `demo_tracks` do rekordbox (amostra pública do rekordcrate, sem áudio; o áudio é um arquivo de teste) e confere título, artista, BPM, tom e a grade do rekordbox na biblioteca local. Só roda com `REAL_EXPORT`.
- Não verificado: pendrive real com áudio de verdade; faixas sem análise no pendrive vêm sem análise (dá para analisar depois).

## 2026-10-25 — Rodada 6: deslizar, ícone, abertura, atalhos, compartilhar

- Deslizar a linha da faixa para a direita põe (ou tira) na playlist alvo; sem alvo, pergunta qual. A linha volta ao lugar.
- Ícone próprio do app (adaptativo, com versão temática do Android 13+): o disco da lista nas cores do player, preto, anel e ponto brancos, marca azul e arco laranja, dentro da área segura. Nome no lançador: "Biblioteca DJ". Abertura (Android 12+) preta com o mesmo disco, sem flash branco. Versão 0.2.0 (código 2).
- Atalhos ao segurar o ícone: Triagem, Importar, Exportar.
- "Compartilhar" de outro app (arquivos, WhatsApp, Drive) com áudio importa direto; repetidas são puladas.
- Achado pelo teste novo: compartilhar um arquivo sem nome/extensão falhava em silêncio ("Não importou:" vazio). Agora o nome cai para o fim do caminho e a extensão é deduzida do tipo; recusas do banco aparecem no aviso.
- Desktop: lembra os 4 últimos padrões de renomear (preferências do usuário) e a janela tem o mesmo ícone, desenhado em código com as cores do StagePalette.
- Evidência: mobile 36 testes (PlatformTest: ícone desenha dentro da área segura, rótulo do lançador, atalhos Triagem/Importar/Exportar, compartilhar importa e não duplica; UxFlowTest: deslizar põe na alvo e não abre a faixa). Desenhos em `docs/ui/icone/`.
- Achado de teste: o Robolectric não desenha uma segunda atividade Compose no mesmo processo; só um teste por classe abre a atividade, os demais chamam `ManagerApp.handle` direto.
- Não verificado: ícone no lançador da Samsung (One UI), atalhos e compartilhar no aparelho.

## 2026-10-25 — Rodada 7: planejar o set e ouvir as transições antes de tocar

- Pedido: facilidades para o DJ experimentar sets e transições, inclusive "pré-programar uma mixagem para ver como fica".
- Núcleo (`core/.../mix/`): `SetPlanner` calcula, para cada par vizinho da playlist, a transição automática (sai no cue "Outro" da segunda metade da faixa, ou N compassos antes do fim; entra no primeiro início de compasso), sempre em início de compasso da grade; encaixe de tom (roda Camelot, vizinho = ok) e andamento (até 6%); sugere as próximas faixas que combinam com a última; empurra pontos compasso a compasso; estima a duração do set. `MixRenderer` monta a prévia offline em WAV: a faixa que entra no andamento da que sai (reamostrada: muda o tom, como toca-discos sem master tempo), ponto de entrada alinhado ao de saída batida com batida, estilos Troca de grave (graves trocam no meio), Mistura (potência constante), Eco (1 batida, realimentado) e Corte (no compasso, 10 ms de rampa). O set inteiro sai como as transições em sequência (8 s antes e depois de cada), gravado em partes, sem segurar tudo na memória.
- O plano fica em `sets/<playlist>.properties` ao lado da biblioteca local, não no banco do rekordbox (não tem onde guardar) nem no pendrive.
- Celular: botão SET na playlist abre a tela: curva de BPM do set com ponto verde/laranja por troca, faixas com BPM e tom, entre elas a transição (tom→tom, diferença de andamento, estilo, compassos, ponto de saída), ▶ para ouvir montada, ✎ para ajustar (compassos 4/8/16/32, estilo, saída/entrada com ‹ › por compasso, « » por 4, ou num cue; AUTO volta ao automático), ▶ OUVIR no topo para o set inteiro, e PRÓXIMA com 3 sugestões (▶ ouvir, ＋ pôr no fim). `MediaDecoder.segment` decodifica só o trecho (MP3/FLAC/AAC pelo MediaCodec).
- Achado pela captura: uma análise sintética marcou "Outro" aos 5 s e a saída foi para 0:05; agora "Outro" na primeira metade é ignorado (teste).
- Evidência: core 97 testes (`MixTest`: batidas da que entra caem nas da que sai com 120→125 BPM e 48→44,1 kHz, ±2 ms; potência constante; troca de grave; eco; WAV; pontos em compasso; sugestões; guardar e ler o plano); mobile `SetFlowTest` (abrir pelo botão, ajuste guardado ao reabrir, prévia com a duração certa e com som, set inteiro, sugestão entra no fim). Capturas `docs/ui/mobile-15-vertical-set.png` e `mobile-16-vertical-transicao.png`.
- Não verificado: som no aparelho (MediaCodec com MP3/FLAC reais, tempo para montar a prévia no S25 Ultra), qualidade de ouvido das transições. Sem master tempo: a faixa que entra muda de tom junto com o andamento.

## 2026-10-26 — Rodada 8: teste de mixagem com cara de player, Master Tempo, teste de áudio no aparelho

- Pedido: a tela de teste de mixagem mais parecida com o CDJ / modo de performance, e resolver as limitações da rodada 7.
- Tela de teste (toque em MIX numa transição do SET), no layout do player: cartões DECK 1 / DECK 2 (título, REMAIN, TEMPO em %, BPM em caixa, MASTER / MT / SEM MT, tom em verde ou vermelho), duas ondas coloridas ampliadas rolando juntas com a cabeça a 40%, batidas (início de compasso em vermelho), cues como triângulos, o trecho em que as duas tocam sombreado em azul com a linha laranja, e ao lado "SAI EM / ENTRA EM / JUNTAS n Bars". Embaixo: visão geral das duas ondas (tocar pula), mixer com HI/LOW/FADER de cada canal e o crossfader acompanhando a cabeça (grave cortado aparece como KILL, eco como ECHO), transporte (◀◀4 ◀1 PLAY 1▶ 4▶▶, CUE SAÍDA, MEIO, FIM) e as fileiras de ajuste (BARS 4/8/16/32, FX GRAVE/MISTURA/ECHO/CORTE, SAÍDA D1 e ENTRA D2 com -4 -1 +1 +4 e os cues da faixa como pads, MASTER TEMPO, AUTO). Arrastar a onda move a cabeça. Cada ajuste monta a prévia de novo numa thread própria (a tela continua respondendo; a cabeça fica no mesmo lugar relativo à saída). Deitado: decks à esquerda, mixer e controles à direita.
- Limitação resolvida — tom mudando: Master Tempo por WSOLA (`mix/Stretch.kt`, porta do key lock do motor do player: grãos de 2048, salto 1024, busca ±5 ms), ligado por padrão. Sem MT, a tela mostra o tom deslocado (Camelot andando 7 casas por semitom).
- Limitação tratada — o que só o aparelho mostra: MENU → APARELHO → "Teste de áudio" lê 30 s de uma música da biblioteca pelo decodificador do Android e monta uma transição com MT, e mostra formato, taxa e o tempo de cada parte (fica guardado). Assim o DJ confere MP3/FLAC e a velocidade no próprio S25 Ultra, sem cabo.
- Os níveis do mixer saem de uma função só (`MixRenderer.levels`), usada para montar o áudio e para desenhar o mixer: o que a tela mostra é o que se ouve.
- Versão 0.3.0 (código 3).
- Evidência: core 100 testes (novos: batidas com MT caem a ±8 ms com 120→125 BPM e 48→44,1 kHz; seno de 440 Hz esticado a 0,9 continua em 440 Hz e sem MT vai a ~396 Hz; níveis por estilo; tom deslocado). Mobile `SetFlowTest` 4 testes: abrir a tela pelo MIX, ondas dos dois decks, MT ligado por padrão, cabeça dois compassos antes da saída, KILL no grave no meio da troca de grave, CORTE/8/MASTER TEMPO pela fileira montam de novo e ficam no plano, captura deitada, teste de áudio com a biblioteca (WAV). Capturas `docs/ui/mobile-16..18`.
- Não verificado (`BLOCKED_HARDWARE`): o "Teste de áudio" foi feito para rodar no aparelho e só rodou aqui com WAV; MP3/FLAC pelo MediaCodec, tempos reais no S25 Ultra e qualidade de ouvido do WSOLA continuam sem prova. A cabeça segue o MediaPlayer a cada quadro (latência do aparelho não medida).

## 2026-10-26 — Vídeo de teste de mixagem com músicas do usuário

- Pedido: gravar um vídeo do teste de mixagem com duas das músicas de teste enviadas pelo usuário.
- `MixVideoTest` (só roda com `-PmixVideo=<pasta>`; as músicas ficam fora do repositório): importa e analisa as cinco músicas como o app, escolhe o par que melhor combina (Killingzoo "Imagination" remix, 128 BPM, Gm/6A → Wiz Khalifa "See You Again" remix, 126 BPM, Gm/6A: mesmo tom, −1,6%), abre o SET e o teste de mixagem e captura a tela quadro a quadro (66 ms) enquanto toca a prévia montada (troca de grave, 16 compassos, Master Tempo). `mobile/tools/montar-video-mix.sh` junta os quadros com o áudio da própria prévia.
- Achado: o relógio do MediaPlayer simulado do Robolectric andava ~26% mais rápido que os quadros e a primeira gravação parou antes do fim; agora a cabeça vai para o instante exato de cada quadro, o mesmo do áudio.
- Resultado: vídeo de 49 s, 720×1560, entregue ao usuário (não versionado). É a tela renderizada em JVM e o áudio montado pelo núcleo, não uma gravação do aparelho.

## 2026-10-26 — Sync da prévia: medição com as músicas do usuário e sync fino

- Relato: no vídeo, a música soou "bem descompassada". Investigado com medição, não a ouvido (`MixSyncCheckTest`, só com `MIX_CHECK=<pasta com a.wav e b.wav>`, músicas fora do repositório).
- Primeiro achado foi da própria régua: um passo de 2 ms arredondado para 88 amostras (88,2) fazia as duas músicas parecerem escorregar 0,23%. Corrigido (passo de 49 amostras = 1,111 ms exatos) antes de tirar conclusões.
- Andamento: os bumbos das duas têm período constante (128,00 e 126,00 BPM medidos trecho a trecho; análise deu 128,003 e 125,998). Master Tempo e alinhamento não derivam: a distância entre os bumbos é a mesma do começo ao fim da troca, com e sem MT.
- Causa: cada análise põe a grade a uma distância um pouco diferente do ataque do bumbo. Com as grades alinhadas (o que o sync faz), os bumbos de "See You Again" caíam ~20–28 ms antes dos de "Imagination": grave embolado, "flam", que o ouvido de DJ percebe como atravessado.
- Correção: sync fino na prévia (`MixRenderer.phaseLag`): correlação dos ataques graves das duas em janelas de 2 compassos no trecho junto, mediana das janelas com pico claro, até ±60 ms; a que entra é adiantada/atrasada nessa medida, como um toque no jog. Ligado por padrão (`Transition.phaseLock`, guardado no plano). Com as músicas do usuário: medido −28 ms (−27 sem MT); sobra ~6–8 ms pela régua de ataque, contra 20–28 ms antes. Nos últimos compassos da A o bumbo dela muda e a sobra sobe, mas ali ela já está saindo (troca de grave).
- Na tela de teste: fileira SYNC com SYNC FINO (liga/desliga para comparar), quanto os bumbos estavam fora com as grades alinhadas (laranja a partir de 8 ms) e GRADE D2, que corrige a grade da que entra por essa medida (e o ponto de entrada junto), para o sync do player também ficar certo, não só a prévia.
- Evidência: core 102 testes e mobile 41 (novo: grade de B 20 ms fora do bumbo → medido 19,2 ms no andamento de A, batidas a ±2 ms com sync fino, +19 ms sem); antes/depois em áudio com as músicas do usuário entregues a ele (não versionados).
- Não resolvido aqui: a análise de grade em si (usada também pelo player) continua pondo a grade a distâncias diferentes do ataque conforme o bumbo; o sync fino e o GRADE D2 compensam na transição. Mudar o analisador pede a bateria de grade do player — fica como próximo passo.

## 2026-10-26 — Analisador: grade no início do clique do bumbo, na faixa inteira

- Pendência da rodada anterior: a causa do bumbo embolado estava na análise de grade (compartilhada com o player), não no sync. Medido em 14 músicas reais (5 do usuário e as 9 do corpus em `/opt/cursor/artifacts/real-corpus`, fora do repositório; `GridPhaseCheckTest`, só com `GRID_CHECK`): a regra declarada do analisador é a grade no início do clique (1–5 kHz, 25% do pico), mas a grade caía de −16 ms ("See You Again", caminho que recola cada batida no pico do fluxo) a +29 ms ("07-silver") dele. O grave não serve de referência (varia com o tipo de bumbo, +15 a +57 ms).
- Correção (`alignGridToClick`, última passada de `analyzeTrack`): mede o início do clique em todas as batidas com bumbo (corpo de 40–150 Hz na mesma batida), tira a tendência de período (medianas por quarto), e só mexe se a medida for firme (meio das medidas em até 15 ms, deslocamento até 40 ms, 32 batidas). Aí a grade passa a ser regular, no ataque, e o período sai da reta pelos ataques se ficar a até 0,05% do escolhido. Uma batida poucos ms antes do zero fica no zero (tirá-la mudava o primeiro tempo 1 e deslocava as frases — pego pelo `PhraseAnalysisTest`).
- Resultado nas 14: grade a ±1 ms do clique (dispersão 1–2 ms) em a, b, t2, t3, at-the-count; silver de +29 para −2 ms; t1, toodaze, chromosome, ecosystem, caught, morning e ocean ficam como estavam (bumbo sem grade fixa: ±20 a ±60 ms mesmo sem tendência). BPMs ficam redondos como os publicados no GiantSteps (129,99→130,00, 124,98→125,00, 175,05→175,00, 120,04→120,00; silver 141,00→140,99).
- Prova independente: na prévia "Imagination" → "See You Again", o desencontro entre os bumbos medido pelo sync fino caiu de −28 ms para −1,4 ms (MT) e 0,0 ms (sem MT); a grade da A não deriva mais (51,5 ms constantes do ataque grave em 8,5 min, antes 50→62).
- Testes: android logic + frases de sessão 107 (0 falhas, 6 pulados por arquivos externos) depois da mudança; `TrackAnalysisTest` +3 (grade 14 ms fora e período errado → no clique a <2,5 ms e 126,00 BPM; sem bumbo, não mexe; dois bumbos diferentes caem no clique na análise completa). Core 104, mobile 41, desktop verde.
- Efeito no player: faixas analisadas de novo pelo app de performance ou pelo Library Manager ganham a grade nova; faixas já analisadas mantêm a grade gravada até reanalisar (ou GRADE D2 no teste de mixagem). Não verificado em aparelho.

## 2026-10-26 — Refazer grades (núcleo, celular e desktop)

- O analisador é um só (`android/app/.../logic/TrackAnalysis.kt`), compilado também pelo `core/` (`srcDir`): a grade nova vale para o app de performance, o Library Manager do celular e o do desktop. O Pi não analisa (lê a grade gravada no pendrive).
- Núcleo: `Edit.ReplaceGrid` troca só a PQTZ (cues, ondas e tom ficam; a PQT2 antiga sai, como na grade manual) e `organize/GridRefresh` analisa só a grade, compara com a atual e monta a troca: igual (até 1 ms, 0,005 BPM, mesma contagem) não vira edição; quando a nova só desloca a atual (< ¼ de batida), o tempo 1 de cada compasso fica o da grade atual (o DJ pode ter acertado à mão; frases e cues em início de compasso continuam batendo).
- Celular: MENU → "Refazer grades" (todas as faixas analisadas, em lotes de 20 com cópia de segurança; aviso "N refeitas · N já certas · N sem áudio"). Desktop: no Organizar, "Refazer N grade(s)" (seleção ou todas) gera sugestões "Grade refeita" com BPM antes→depois, deslocamento e se os compassos foram mantidos, para revisar antes de pôr na fila.
- App de performance: cache da análise de pastas sem rekordbox passou de "0140" para "0141", para reanalisar com a grade nova.
- Evidência: core 106 (`GridRefreshTest`: recém-importada não muda; grade torta 15 ms e com o compasso uma batida adiante volta ao clique a ≤1 ms mantendo o tempo 1 do DJ, cues intactos, refazer de novo não muda; grade vazia ou fora de ordem recusada); mobile 42 (refazer grades na biblioteca de demonstração: "1 refeitas · 9 já certas", gravado); android logic 108; desktop verde.

## 2026-10-26 — Vídeo da jornada completa (e um defeito achado gravando)

- Pedido: vídeo da jornada: importar músicas, marcar, montar playlist, planejar mixagem, exportar.
- `JourneyVideoTest` (só com `-PjourneyVideo=<pasta>`; as músicas do usuário ficam fora do repositório) dirige o app com toques de verdade (Compose) na tela do S25 Ultra renderizada em JVM: IMPORTAR MÚSICAS (5 MP3), triagem com uma nota por faixa, ⋯ → Usar como alvo e "+" nas faixas na ordem do set, SET, MIX, PLAY (a prévia tocando, com som), PENDRIVE → ESCOLHER → EXPORTAR para um provedor de documentos de brinquedo. `mobile/tools/montar-video-jornada.py` desenha os toques (círculo laranja), as legendas das etapas, acelera a espera da importação e junta o áudio da prévia. Resultado: 1 min 35 s, entregue ao usuário (não versionado).
- Defeito achado gravando: no celular, abrir uma playlist na aba PLAYLISTS deixava a aba FAIXAS restrita a ela ("Nenhuma faixa nesta lista"), porque a lista do núcleo usa a playlist aberta como escopo (o comportamento certo no desktop, pela barra lateral). Agora `ManagerModel.scopeToOpenPlaylist` é falso no celular; teste novo em `UxFlowTest`.
- Limitações do vídeo, ditas: o seletor de arquivos do Android não aparece (o toque em IMPORTAR entrega os arquivos direto); a caixa de nome da playlist não aparece — qualquer campo de texto numa janela de diálogo não fica "ocioso" no Robolectric, inclusive o do Material sem código do app (testado isolado) —, então "Sexta à noite" é criada direto; MP3 passa pelo ffmpeg, não pelo MediaCodec; o player é o simulado e a cabeça segue o tempo de cada quadro.
- Evidência: core 106, mobile 44 (2 pulados: os gravadores de vídeo sem pasta).

## 2026-10-26 — "Saiu da mixagem e a música continuou tocando"

- No vídeo da jornada, o som da prévia seguia depois de fechar o teste de mixagem. Causa do vídeo: o montador colava o áudio da prévia inteiro e não cortava onde a reprodução parou. Agora `JourneyVideoTest` registra o quadro da parada (`audio_fim_quadro`) e sai pelo ✕ de verdade (que é quem para o som, conferido no próprio teste), e `montar-video-jornada.py` corta o áudio ali, com 60 ms de saída suave. O vídeo foi remontado.
- No app, o ✕, o voltar do sistema e o ‹ do SET já paravam o som; teste novo `leavingTheMixTestStopsTheMusic` (tocar e tocar no ✕ → para).
- Achado ao revisar: a prévia do ▶ de uma transição e o OUVIR do set são montados em segundo plano e, se o DJ saísse do SET antes de ficarem prontos, começavam a tocar depois. Agora só tocam se o mesmo SET ainda estiver aberto. Teste `aPreviewStillBeingBuiltDoesNotStartAfterLeavingTheSet` (falha sem a correção, conferido; com o SET aberto a mesma prévia toca).

## 2026-10-26 — Rodada 9: montar, checar, fazer ponte, ordenar e ensaiar sets

- Pedido: mais recursos para montar e testar sets facilmente.
- Núcleo (`mix/SetBuilder.kt`): custo de uma troca (tom: igual 0, vizinho 1, dois passos 4, longe 8+; andamento: até 3% barato, até 6% aceitável, acima disso caro; forma do set: "Subindo" não deixa o BPM cair mais de 1, "Sobe e desce" sobe até ~60% e desce depois). `order` (escolha passo a passo + melhoria por inversão de trechos e mudança de lugar, até 60 faixas; a de abertura fica) e `build` (escolhe faixas até a duração pedida, preferindo nota alta entre trocas parecidas, e ordena). `SetDoctor.check` (tom que não combina, salto de BPM, sem grade, saída sem espaço para os compassos — texto curto) e `bridges` (faixas que combinam com as duas pontas). `Transition.mark` (boa / refazer) guardada no plano; mudar a transição apaga a marca dela.
- Celular: PLAYLISTS → MONTAR SET (de onde: biblioteca, ★4+ ou uma playlist; 30/60/90/120 min; Fluido/Subindo/Sobe e desce) cria a playlist e abre o SET. No SET: ENSAIAR (toca cada transição; ✓ BOA / ✗ REFAZER passa para a próxima; no fim, "N boas · N a refazer"), ORDENAR (pela forma escolhida, desfazível), resumo "⚠ N" ou "✓ trocas ok", avisos em cada troca e PONTE (escolhe uma faixa que combina com as duas e põe no meio; um desfazer tira tudo). Capturas `docs/ui/mobile-19..23`.
- Evidência: core 111 (`SetBuilderTest`: ordenar baixa o custo e é determinística, subir sem queda de mais de 1 BPM, set de 30 min chega a 30–37 min com nota média acima da biblioteca e no máximo 1 troca ruim, checagem e ponte, marcas guardadas); mobile 50 (`SetBuildingTest`: montar pelo diálogo, aviso e ponte pela tela, ordenar mantendo a abertura, ensaio tocando cada transição e guardando as marcas). Desktop verde.
- Não verificado: aparelho; qualidade de ouvido das escolhas automáticas em bibliotecas grandes (o custo é regra de tom e andamento, não gosto).

## 2026-10-26 — Rodada 10: ramos do set e o master manda no BPM

- Pedido: o set ter "ramos", pontos de virada em que dá para seguir outra linha conforme a pista; e lembrar que o BPM é o do master (tocar uma de 116 em 128 é normal), só sem mudanças drásticas.
- Master (`mix/SetPlan.kt`, `SetTempo`): até 10% de esticada é tocar normal, até 16% (o pitch do player) é aceito, acima disso aviso "Esticar −22%: passa do pitch". No modo AUTO o master segue as faixas mas anda no máximo 2,5% por troca (sem saltos); dá para fixar (ex.: 126) e todas tocam nele. `Fit.bpmOk` passou de 6% para 10%; o custo do montador pesa de leve até 10%, mais até 16% e proíbe acima. A prévia toca as duas faixas no master (a que sai também é esticada, com Master Tempo).
- Ramos (`mix/SetBranches.kt`, `SetBuilder.branch`): cada ramo é uma playlist de verdade ("Sábado - ramo B", vai para o pendrive como qualquer playlist); a ligação (de qual faixa sai, onde volta) fica em `sets/branches.properties`, fora do banco do rekordbox. O ramo sai de uma faixa, tem 2/3/4/6 faixas, rumo "Mais energia"/"Manter"/"Mais calmo", e volta à linha principal mais adiante (testa cada ponto de volta) ou vira outro final. A escolha é uma busca em feixe (12 caminhos): o passo a passo puro escolhia uma vizinha boa agora e depois só voltava com troca fora do tom; teste `ramoNaoCaiNaArmadilhaDoPassoAPasso` falha com o passo a passo antigo (conferido) e passa com o feixe.
- Celular: botão de ramo em cada faixa do SET → diálogo (faixas, rumo, volta); a linha "RAMO B · 3 faixas · volta no 3" abaixo da faixa de virada abre o ramo, que mostra a linha inteira (virada → ramo → volta) com transições, prévia e ensaio. Botão MASTER (AUTO ou um BPM fixo), faixa com BPM no master e "nativo ±%" (laranja acima de 10%, vermelho acima de 16%) e a linha do master no topo. Capturas `docs/ui/mobile-24..27`.
- Evidência: core 117 (`SetTempoAndBranchTest`: master sem saltos, 116 em 128 ok, ramo sobe e volta, armadilha do passo a passo, ramos guardados e só os vivos; `MixTest`: as duas tocam no master); mobile `SetBranchTest` (criar ramo pela tela, todas as trocas no tom, volta mais adiante, guardado e reaberto; master fixo em 126). No teste do celular, Concrete Garden passou a Em: com Bbm, a biblioteca de demonstração não tem nenhum caminho de 3 faixas todo no tom.
- Não verificado: aparelho; como o rekordbox/CDJ mostra playlists "- ramo X" (são playlists normais); com biblioteca pequena um ramo pode precisar de uma troca fora do tom, e o aviso aparece.

## 2026-10-26 — Mapa do set: ver os ramos e o andamento

- Pedido: um jeito de ver os ramos do set e ter uma visão do andamento.
- Núcleo (`mix/SetBranches.kt`): `SetRoutes.routes` monta os caminhos inteiros — a linha principal (A) e, por ramo, a linha até o ponto de virada, o ramo e o resto da linha a partir da volta (ou o ramo como outro final) — com as posições da virada e da volta. `SetRoutes.tempo` dá quando cada faixa entra e o tamanho do caminho tocando no master (o trecho da entrada à saída de cada faixa, mais curto se o master está acima do BPM dela).
- Celular: tocar na faixa do master no topo do SET ("MAPA ›") abre o MAPA. Gráfico com o tempo na horizontal e o master na vertical: a linha principal em azul, cada ramo na sua cor saindo da virada e, depois da volta, tracejado (mostra quanto o ramo alonga ou encurta o set); anel laranja onde a faixa estica mais de 10%. Lista dos caminhos: de onde sai e onde volta, duração, BPM de início → pico → fim e avisos (✓ ou ⚠ N). Tocar num caminho mostra as faixas dele com a hora em que entram, o BPM no master e o tom (a cor do ramo marca o trecho próprio); › abre a linha (principal ou do ramo). Capturas `docs/ui/mobile-28..29`.
- Evidência: core 118 (`caminhosDoMapaEAndamento`: caminhos A/B/C, posições da virada e da volta, horários de entrada, master mais alto encurta o caminho); mobile `SetBranchTest.theMapShowsEveryRouteAndItsTempo` (abre pelo toque na faixa do master, 3 caminhos, tempo andando e master sem saltos em todos, escolher B mostra o andamento dele, › abre o ramo).
- Não verificado: aparelho. O tempo é estimado pelos pontos de entrada e saída das transições (sem contar a sobreposição da mixagem, igual ao ≈ do topo do SET).

## 2026-10-26 — Set flexível: pontos de virada, pool, ramo à mão e ramo de ramo

- Pedido: pontos de quebra no set (ex.: abrir com 5 faixas certas e depois decidir entre seguir na linha ou ir para outra) e listas flexíveis, uma pool de músicas em vez de mapear faixa a faixa.
- Núcleo: `mix/SetPool.kt` — uma playlist vira pool de uma posição em diante (`sets/<id>.pool`, fora do banco; no pendrive é uma playlist comum); `links`/`linkCount` (com quantas outras da pool cada faixa combina, em qualquer sentido), `next` (o que tocar depois da que está tocando, pela melhor troca, sem as já tocadas), `fill` (enche a pool com faixas que ligam à entrada, à volta e entre si) e `branch` (ramo em pool que mira a melhor volta). `SetRoutes.routes` agora resolve ramos de ramos (um ramo sai do trecho próprio do pai e volta mais adiante no caminho do pai) e marca as posições de pool (`SetRoute.pool`, `insidePool`); `SetBranches.rootOf`/`tree`.
- Celular: o botão em cada faixa virou **ponto de virada**: "Criar ramo daqui" ou "Pool depois desta" (ou "Tirar a pool"). Num ramo também (ramo de ramo). O ramo tem TIPO: Lista (automática, como antes), Pool (4/6/8/12 faixas) ou Eu escolho (nasce vazio; PRÓXIMA sugere e VOLTA ▾ escolhe onde volta ou "outro final"). A pool aparece num bloco: "POOL · N faixas · K levam a X", cada faixa com "liga N" (ou "isolada") e **AGORA**: marcada a que está tocando, as outras vêm na ordem da melhor troca a partir dela (tom ok/✗ e %), as tocadas vão para o fim; ZERAR recomeça. Sem ORDENAR na pool, transições e avisos só na parte com ordem; "PARA A POOL" sugere faixas que ligam à pool. O mapa mostra os ramos de ramos e não conta avisos dentro da pool. Capturas `docs/ui/mobile-30..33`.
- Correções achadas: a prévia montada em segundo plano lia o master na hora de montar (saindo do SET no meio, montava no BPM errado) — agora lê no toque (commit separado); a sugestão de um ramo podia repetir uma faixa da linha principal — agora exclui o set inteiro; `SetPool.fill` parava cedo quando uma faixa sem ligação tinha nota alta.
- Evidência: core 120 (`ramoDentroDeRamoEPoolNoCaminho`, `poolCombinaEntreSiEMostraAProxima`); mobile `SetPoolTest` (3 fixas e pool escolhida na hora, AGORA/tocada/ZERAR, guardado e desfeito; ramo em pool que volta, ramo "eu escolho" com volta escolhida, ramo de ramo e mapa com 4 caminhos) e `SetBranchTest` pelo novo ponto de virada. Desktop verde.
- Não verificado: aparelho; o uso ao vivo (AGORA) é no celular, não no player — o CDJ/rekordbox vê só as playlists.

## 2026-10-26 — Fluxo amigável para quem é novo

- Pedido: fluxo e interface amigáveis para quem nem sabe como o app funciona, sem excesso de texto e avisos; o desenho deve conduzir o trabalho.
- Guia em 4 passos (`GuideUi.kt`, `GuideStep`): Importar · Marcar · Playlist · Pendrive, em marcadores numerados (feito ✓ em verde, o de agora em azul), uma palavra em cada; tocar leva para onde o passo se faz. Fica abaixo do topo nas áreas de trabalho e some quando tudo foi feito ou com ✕ (MENU → "Guia para começar" liga de novo). Substitui a linha "PRÓXIMO".
- Destaque do próximo controle (`Modifier.spotlight`): um contorno azul por fora, parado, sem texto e sem mudar o tamanho de nada; cada um some depois de usado uma vez (`ManagerApp.hint/seen`, guardado no aparelho). Onde: IMPORTAR MÚSICAS na biblioteca vazia, a aba do próximo passo na fileira de baixo, MONTAR SET sem playlists, MIXAR numa playlist, o ▶ da primeira transição do SET e depois o ENSAIAR, o ▶ OUVIR ao marcar, ESCOLHER e EXPORTAR no pendrive.
- Nomes simples e na ordem do trabalho: MÚSICAS · MARCAR · PLAYLISTS · PENDRIVE · MENU (antes FAIXAS · PLAYLISTS · TRIAGEM · …); MARCAR mostra quantas faltam. "Alvo" virou a ação: ＋ na playlist leva para as músicas com ＋ em cada linha apontando para ela; "SET" virou MIXAR. Pendrive em 2 passos numerados (escolher, exportar) e "NO CELULAR" no lugar de "APARELHO".
- Telas vazias com uma ação: biblioteca vazia com IMPORTAR MÚSICAS, IMPORTAR UMA PASTA e "TENHO UM PENDRIVE DO REKORDBOX"; playlists e pendrive sem músicas levam a importar; playlist vazia com ADICIONAR MÚSICAS; MARCAR sem músicas responde em vez de ficar mudo. Avisos de troca no SET numa linha só.
- Evidência: `NewcomerFlowTest` (app vazio mostra por onde começar e as três portas; guia passa por marcar, playlist com ＋, MIXAR, ▶ e ENSAIAR com os destaques sumindo depois de usados, pendrive em passos, fechar e religar o guia); testes antigos ajustados aos nomes novos. Capturas `docs/ui/mobile-34..38`.
- Não verificado: com pessoas novas de verdade (é a leitura das telas, não um teste de uso) e no aparelho.

## 2026-10-26 — Vídeo: montando um set com as músicas do usuário

- Pedido: gravar um vídeo montando um set com as músicas enviadas.
- `SetVideoTest` (com `VideoTestBase`, o equipamento de gravação compartilhado com a jornada): importa as 5 músicas (análise de verdade), marca, MONTAR SET (★4+, 60 min, Fluido), fixa o master em 130, ouve a primeira transição (som em `out/mix.wav`), cria um ramo no ponto de virada da 2ª faixa, faz do fim do set uma pool com AGORA e abre o MAPA. Só roda com `-PjourneyVideo=<pasta>`; as músicas ficam fora do repositório. Montagem com `tools/montar-video-jornada.py`.
- Resultado (123 s, 720×1644): o set saiu Imagination → See You Again → How Does It Feel → Shinjuku, todas tocando a 130 (esticadas de +1% a +3%); as trocas 6A→3A e 3A→1A aparecem como "tom não combina" (com 4 faixas de nota 4+ não há ordem melhor); o ramo B leva Fuck It (135 BPM, −4% no master) e é "outro final". Som só durante a transição (conferido: silêncio antes e depois).
- Achado ao rever: no mapa, o título do caminho B corta "→ outro final" quando o nome da música é longo. Não corrigido.
- Não verificado: aparelho; é a tela renderizada em JVM, não o app rodando no tablet.

## 2026-10-26 — Refino: visual mais limpo e mais simples de usar

- Pedido: executar as melhorias de refino propostas (enxugar e deixar o visual mais clean).
- SET: transição numa linha só (`8A→9A +1%`, estilo e compassos só quando mudados; tocar na linha abre o MIX; o aviso por extenso só ao tocar no ⚠, que oferece "Pôr uma ponte"); faixa sem o ícone de ramo (⋯ discreto e toque longo abrem o ponto de virada); o BPM da faixa e quanto estica só aparecem acima de 10%; uma ação principal (ENSAIAR) e o resumo ✓/⚠; o master fica na faixa de cima (tocar em "MASTER AUTO/130" escolhe), ORDENAR foi para o ⋯ do topo.
- MIX: simples por padrão (ondas, PLAY e os pulos de 1 e 4 compassos, COMP. e ESTILO); "AJUSTAR ▾" abre mixer, CUE SAÍDA/MEIO/FIM, saída e entrada, cues, tempo e sync. Título "MIX".
- Consistência: tom em Camelot em todo o app (lista, faixa, marcar, set, mapa), com "Am (musical)" como opção no MENU (Tela → Tom); BPM inteiro nas listas (decimal no detalhe); porcentagens sem "-0%". Vocabulário: o botão da playlist é SET (a tela é SET), cada troca é um MIX.
- Menos interrupções: gravar é sozinho — 20 s sem mudanças e ao sair do app, numa thread de fundo, sem tela de espera; a tela não pula (filtro, busca e playlist aberta ficam) e o que se faz enquanto grava continua na fila (`ManagerModel.beginQuiet/writeQuiet/finishQuiet`; desfazer espera a gravação). Saíram a barra "● N ↶ SALVAR", "Descartar mudanças", o ponto de "não salvo" e o "SALVAR E EXPORTAR"; ↶/↷ ficam no topo enquanto há o que desfazer; juntar/trazer do pendrive gravam antes em vez de pedir "Salve antes". Avisos somem sozinhos em 3,5 s (erro fica até o OK). TRAZER DO PENDRIVE só aparece quando o pendrive tem biblioteca.
- Ajustes: lista de músicas sem o cabeçalho de colunas (a ordem virou o chip "⇅ Artista" na fileira de filtros); pool com ponto verde/laranja e % no lugar de "tom ok … liga N" ("isolada" só quando é o caso); mapa com títulos curtos (sem "(Extended Mix)") e os pontos do BPM de cada faixa no caminho escolhido; diálogo de ramo abre com o padrão e CRIAR, as escolhas em "MAIS OPÇÕES".
- Evidência: core 122 (`aQuietSaveKeepsTheScreenAndWhatWasDoneMeanwhile`); mobile 58 (3 vídeos pulados), testes ajustados ao fluxo novo (⚠ → ponte, ⋯ → ordenar, master na faixa, AJUSTAR no mix, MAIS OPÇÕES no ramo); desktop 6. Capturas novas em `docs/ui/` (mobile-39 é o mix simples).
- Não verificado: aparelho (o gravar sozinho por tempo roda pela atividade, que os testes de tela não usam; o caminho de gravação silenciosa é coberto no core).

## 2026-10-26 — Importação em segundo plano, mais rápida e sem travar

- Relato do dono (no aparelho): importando várias músicas, travou no meio. Pedido: importação mais rápida e limpa, em segundo plano, podendo organizar as já importadas enquanto isso.
- Causas encontradas no código (não reproduzidas no aparelho): (1) o decodificador do Android guardava a faixa inteira em estéreo a 44,1 kHz antes de virar mono — uns 170 MB numa faixa de 8 min —, e falta de memória (OutOfMemoryError) não era tratada; (2) o laço do MediaCodec não tinha limite de tempo (um arquivo com defeito podia prendê-lo); (3) a cada 6 músicas uma gravação completa que reabria a biblioteca, chamada da thread de fundo; (4) a tela de espera bloqueava tudo.
- Correções: `MonoSink` junta o áudio já em mono ~22 kHz, aos pedaços (cerca de 1/4 da memória); 120 s no máximo por arquivo no decodificador; `ImportQueue`: uma thread copia, duas analisam ao mesmo tempo, cada música entra na lista assim que fica pronta (o modelo só é mexido na thread da tela), gravação silenciosa a cada 4 músicas ou 15 s (sem reabrir a tela) e as cópias do cache somem depois de gravadas; uma falha (inclusive falta de memória) conta só para aquela música; pedidos novos entram na mesma fila; a varredura de pasta pede os filhos de cada pasta numa consulta só (antes, uma por arquivo). Na tela, uma faixa fina "IMPORTANDO 7/40 · música ✕" no lugar da tela de espera; EXPORTAR e TRAZER DO PENDRIVE esperam a importação terminar.
- Evidência: `MonoSinkTest` (estéreo 44,1 kHz → mono 22 kHz pela média, limite de duração, 6 canais); `ImportQueueTest` (sem tela de espera; playlist criada no meio da importação fica; as 5 analisadas, gravadas e relidas do disco; o cache some; as mesmas de novo são puladas; parar no meio guarda o que já entrou). Mobile completo verde.
- Não verificado: no aparelho (o MediaCodec real não roda nos testes de JVM: `BLOCKED_HARDWARE`), nem a velocidade real com 2 análises em paralelo. Se o Android fechar o app em segundo plano (sair do app por muito tempo), a importação para; o que já entrou fica gravado.

## 2026-10-27 — Transições longas pela estrutura (intro sobre outro, drop no fim)

- Pedido do dono: o ensaio ficava focado numa virada de 16 compassos no fim; em eletrônica as transições são mais longas. Adaptar o padrão e criar um mecanismo que defina melhor entrada e transição.
- Núcleo (`SetPlanner.auto`): a que entra começa no começo dela (cue "Intro" ou o primeiro compasso) e a troca dura até o drop dela — o drop cai quando a que sai termina de sair (base DROP); sem drop, dura o outro da que sai (OUTRO); sem nada, 32 compassos (DEFAULT; antes eram 16). Sempre entre 16 e 64. A saída é o "Outro" da que sai, recuando de frase em frase (8 compassos) até a troca caber; sem outro, o último início de bloco de 16 compassos onde ela cabe. `fitOut` (recuar a saída quando a duração muda) e `barsToDrop` (quantos compassos até o drop). O drop só vale depois de 8 compassos de intro e antes de 60% da faixa.
- Celular: MENU → Set → Transição (Auto até o drop / 16 / 32 / 64); no MIX, durações 8/16/32/64 (trocar recua a saída se precisar) e a linha DROP: "ATÉ O DROP · 30" acerta a duração, e "o drop da próxima cai no fim da troca" quando já está; no SET cada troca mostra os compassos.
- Conferido nas 5 músicas do dono (`TransitionCheckTest`, com `TRANSITION_CHECK=<biblioteca>`): See You Again tem o drop no compasso 30 e as trocas para ela ficaram com 30 (o drop cai no fim); nas outras o drop detectado está cedo demais (0:05, 0:13: a análise tomou batidas da intro por refrão) ou tarde demais (Fuck It, depois de 60%) e foi descartado, então ficaram com 32; os outros detectados são curtos (8–16 s), então a saída foi o último bloco de 16 compassos onde 32 cabem (Shinjuku sai em 4:27 de 5:53).
- Evidência: core 123 (`transicao automatica pela estrutura`: drop no fim, saída no outro, recuo de frase em frase, padrão 32, fixa, mínimo 16).
- Não verificado: de ouvido no aparelho. A qualidade depende da análise de estrutura (drop e outro), que erra em parte das faixas — a duração cai no padrão de 32 nesses casos.

## 2026-10-27 — Ritmo da troca e ponto da troca de grave

- Pedido: as trocas de grave e as mixagens podem variar — mais rápidas ou mais devagar.
- Núcleo: `Transition.pace` (RÁPIDA / MÉDIA / LENTA) e `Transition.swapAt` (em que ponto o grave troca: 1/4, 1/2, 3/4, no fim), guardados no plano. `MixRenderer.levels`: troca de grave — rápida (agudos da que entra cheios num quarto do caminho até a troca, grave seco no compasso, a que sai some no último oitavo), média (a de antes), lenta (grave passa aos poucos nos 8 compassos antes da troca, a que sai desce na última metade); o ponto cai num início de compasso. Mistura — rápida cruza num trecho curto no meio, média cruza a troca inteira, lenta deixa as duas cheias no meio. Eco — a que entra sobe em 1/8, 1/4 ou 1/2. Média com o grave no meio é exatamente a troca de antes. O automático troca o grave no drop quando a troca vai até o drop.
- Celular: no MIX (tela simples), linhas RITMO e GRAVE (esta só na troca de grave; "NO DROP" quando a troca vai até ele); no SET, a linha da troca diz o ritmo quando não é o médio.
- Achado: ao incluir as linhas, a RITMO entrou por engano dentro da linha ESTILO (sem os botões de estilo); o `SetFlowTest` pegou (o CORTE não estava na tela) e foi corrigido. Também: faixas curtas — a transição automática passou a ter no máximo metade da faixa que sai, e sem outro a saída tenta o bloco de 16, depois a frase de 8, depois o compasso (antes, faixas de 40 s ficavam com saída no começo).
- Evidência: core 124 (`ritmo e ponto da troca de grave`: grave no fim, em 1/4 num compasso, lenta passando aos poucos, rápida e lenta no volume, mistura rápida e lenta, guardado no plano, automático no drop); mobile `SetFlowTest.paceAndTheBassSwapPointChangeTheTransition` (LENTA e 3/4 pela tela, prévia com o grave se cruzando, guardado e mostrado na linha). Captura `docs/ui/mobile-40`.
- Não verificado: de ouvido no aparelho.

## 2026-10-27 — Grave de uma vez ou aos poucos

- Pedido: virar o grave todo de uma vez num ponto, ou ir mudando aos poucos, conforme a situação.
- Núcleo: `Transition.swapBars` (0 = de uma vez no ponto; 4/8/16 = o grave de uma desce enquanto o da outra sobe, em N compassos que terminam no ponto do GRAVE; sem espaço antes do ponto, começa na saída e termina adiante), guardado no plano. O ritmo deixou de mexer no grave (antes, "lenta" passava o grave aos poucos em 8 compassos fixos): ritmo fica com agudos e volume, PASSA com o grave.
- Celular: no MIX, linha PASSA (DE UMA VEZ / 4 COMP. / 8 COMP. / 16 COMP.; mais compassos que a troca ficam apagados); no SET, "grave aos poucos" na linha da troca.
- Evidência: core 124 (`ritmo e ponto da troca de grave` ampliado: seco em qualquer ritmo, 8 compassos cruzando até o ponto, 16 sem espaço antes do ponto); mobile `SetFlowTest.paceAndTheBassSwapPointChangeTheTransition` (LENTA, 3/4 e 8 COMP. pela tela, prévia com os graves se cruzando, linha do SET).
- Não verificado: de ouvido no aparelho.

## 2026-10-27 — Set em quadro (canvas)

- Pedido: simplificar a experiência; montar o set como um canvas onde se vão colocando as músicas e dividindo as linhas do tempo.
- Núcleo: `SetCanvas.layout` — a linha principal e cada ramo (também ramo de ramo) numa pista própria, as faixas em colunas; o ramo começa na coluna depois da faixa de onde sai e liga na faixa onde volta (na linha de onde saiu ou numa acima). A faixa da volta é empurrada para depois do fim do ramo (e as seguintes com ela), repetindo até assentar: o quadro vira uma linha do tempo com o ramo ao lado do trecho que ele troca. As trocas que tocam uma pool ficam marcadas (sem número).
- Celular: o SET abre no quadro (`SetBoard`); a lista antiga fica em ⋯ → Ver em lista (preferência guardada). Em pé o tempo desce e os ramos são colunas; deitado, fileiras. Cartão = título curto, tom e BPM, com a cor da linha; número entre cartões = compassos da troca (⚠, ✓, ✗), tocar abre o MIX; ＋ no fim de cada linha abre "Pôr na linha X" (as que combinam com a última, BUSCAR para a biblioteca, ＋ várias vezes); cartão escolhido mostra ▶, ‹ ›, DIVIDIR e TIRAR. Escolher um cartão ou a letra de uma linha faz dela a linha ativa (`SetDesk.focus`): ENSAIAR, OUVIR e o MIX passam a ser dela. ⋯ ganhou um menu (ordenar, master, andamento, vista).
- Achados: o `OutlinedTextField` dentro do diálogo não deixava o Robolectric ficar ocioso (o teste travava); a busca passou a aparecer só ao tocar BUSCAR. `performScrollTo` em quadro com rolagem nos dois sentidos entrou em laço; o teste aciona o toque pela semântica. A primeira versão deixava a volta de um ramo comprido "para trás" (linha cruzando cartões); corrigido empurrando a faixa da volta.
- Evidência: core `quadroDoSetComRamosEmFileirasEVoltas` (colunas, volta em linha acima, ramo de ramo, pool, ramo vazio); mobile `SetBoardTest` (pôr pelo ＋, DIVIDIR → ramo numa linha nova logo depois de onde sai, escolher outra linha, mover, tirar, pergunta antes de tirar ponto de virada, troca abre o MIX, voltar à lista; deitado). Testes antigos do SET rodam na vista em lista. Capturas `docs/ui/mobile-41` a `mobile-43`.
- Não verificado: no aparelho (toque, rolagem e tamanho dos cartões numa tela real).

## 2026-10-27 — Vertentes que se juntam no fim

- Pedido: abertura e fechamento bem definidos e o meio em vertentes — montar a sequência inicial, dividir em vertentes e juntá-las no fim.
- Núcleo: `SetBuilder.branch` e `SetPool.branch` aceitam `joinAt` (a faixa da linha, depois do ponto de virada, onde o ramo junta; a busca mira a troca para ela). `SetDesk.createBranch(joinAt)`: sem escolha, junta onde já juntam os ramos que saem da mesma faixa (`siblingJoin`); `joinChoices` lista as faixas do caminho depois do ponto.
- Celular: no diálogo do ramo, "↩ junta em …" fica sempre à vista (Onde combinar / uma faixa / Não junta); no quadro, "↩ faixa" (ou FIM) no fim de cada ramo abre "Junta em" para mudar. Ramo "Eu escolho" também junta.
- Evidência: core 126 (`vertentesJuntamOndeODjEscolhe`: junta na faixa escolhida mesmo podendo antes, logo a seguinte vale, faixa antes do ponto é ignorada, pool também); mobile `SetBoardTest.openingThenStrandsThatJoinForTheEnding` (escolher a junção no diálogo, segunda vertente já abre juntando no mesmo lugar, fechamento depois da vertente mais longa, ↩ muda a junção). Mobile completo da rodada do quadro: 66 testes, 3 vídeos pulados, 0 falhas. Captura `docs/ui/mobile-44`.
- Não verificado: no aparelho.

## 2026-10-27 — Telas de set no computador (código compartilhado)

- Pedido: todas essas telas devem funcionar perfeitamente no aplicativo para computador também.
- Decisão: as telas não foram copiadas; viraram código compartilhado em `studio/src` (pacote `os.deckbridge.manager.studio`), compilado pelos dois apps (o celular inclui a pasta no `sourceSets` do Android; o computador no do Kotlin/JVM). Foram para lá: tema e componentes do design system, ChoiceDialog/ConfirmDialog, SetDesk, SET (quadro e lista), MIX e mapa. Alternativa descartada: copiar as telas para o desktop (duas versões divergiriam a cada rodada); outra: módulo Kotlin Multiplatform com alvo Android (exigiria o SDK do Android para compilar o desktop).
- `StudioHost` é o que cada app fornece: biblioteca, onde guardar os planos (`setsDir`), cache, pré-escuta (`StudioPreview`), avisos, guia, duração das transições, vista, trabalho em segundo plano, thread da tela, gravar agora (`saveNow`) e "voltar" (`LocalBack`). Celular: `ManagerApp`/`Preview`/`Job` implementam (o voltar é o do Android). Computador: `DesktopStudio` (preferências do Java, planos em `~/.deckbridge/sets`, nunca no pendrive), `DesktopPreview` (saída de som padrão; faixa decodificada pelo `AudioIo`), `StudioOverlay` (as telas por cima da janela, aviso e andamento) e Esc como voltar; botão **SET ▸** na playlist.
- Regra do pendrive (ADR-0009): no computador, o que o set muda na biblioteca entra na fila e só é gravado quando o usuário aplica; `saveNow` só grava sozinho na biblioteca local. "Transição" (Auto/16/32/64) passou para o ⋯ do SET (vale nos dois apps).
- `DesignSystemTest` do celular passou a varrer também `studio/`.
- Evidência: desktop `StudioTest` (offscreen, cliques): SET ▸, ＋, duas vertentes juntando na Glass Harbour, MIX com a prévia montada do áudio sintético, LENTA + 8 COMP., Esc fecha MIX, mapa e SET, lista, plano gravado na pasta de sets; desktop completo 7 testes (1 pulado: capturas antigas só com SHOTS_DIR). Celular completo depois da mudança: ver abaixo. Capturas `docs/ui/desktop-set-*.png`.
- Achado: a primeira execução do teste gravou "ver em lista" nas preferências reais do usuário do sistema; o teste passou a usar um nó de preferências e uma pasta de planos próprios, e a sujeira foi apagada.
- Não verificado: som de verdade no computador (sem placa de som no ambiente de teste) e uso numa janela real.
- Celular completo depois da mudança: 67 testes, 3 vídeos pulados, 0 falhas. Versão 0.10.0.

## 2026-10-27 — Rodada de UX do set: velocidade, marcar, ouvir caminho, arrastar, ao vivo, zoom, cola

- Pedido: fazer tudo o que der da lista de melhorias (2 a 9; a 1, testar no aparelho, depende do dono).
- Velocidade: `ManagerModel.trackGrid` lê grade e cues sem decodificar as ondas; o SET lê as grades do set numa thread de fundo ao abrir (`SetDesk.prefetch`), sem trava durante a leitura; os avisos de todos os caminhos do quadro são calculados fora da thread da tela (`produceState`). Medido no computador (`SetBenchTest`, só com SET_BENCH=1; 40 faixas de 3 min, set de 20 com 3 ramos): grade com ondas 1,6 ms → só grade 0,1 ms por faixa; quadro frio 14 ms (avisos 11 ms), com cache 3 ms (avisos 5 ms). No celular não medido (`BLOCKED_HARDWARE`).
- Marcar no MIX: INTRO / DROP / OUTRO AQUI (`SetDesk.markHere`) põem um cue de memória com o nome que a montagem automática procura, no compasso mais perto da cabeça, e acertam a troca aberta (entrada no intro; saída no outro; com o drop, a troca dura até ele e o grave troca nele). O cue vai para a fila da faixa e a grade em memória já o tem.
- Ouvir o caminho: no quadro, OUVIR toca o caminho inteiro da linha escolhida (`pathPlan`/`playPath`: trocas sem as de dentro de pool, cada uma no master daquele ponto); o botão diz a letra.
- Arrastar: cartão arrastado (toque longo no celular, direto no mouse) muda de posição ou de linha (`moveCard`; recusa faixa de onde sai um ramo e faixa que já está na linha de destino); barra azul mostra onde cai.
- Achado e correção no núcleo: `submitAll` não era desfeito de uma vez (cada mudança era um desfazer, ao contrário do que o comentário da ponte dizia). `PendingChange.group`: o que é pedido junto sai e volta junto (ponte, tirar faixa repetida, mover entre linhas).
- Ao vivo: ⋯ → Ao vivo; tocar na que toca (`liveTo`) — as que podem vir depois (`SetCanvas.nextFrom`: a seguinte, o começo de cada ramo que sai dela, a volta, a pool) ficam com contorno verde e em botões; as tocadas e o resto apagam.
- Zoom: − / TUDO / + (a densidade do quadro muda de escala; TUDO cabe o set na tela).
- Cola do set: ⋯ → Cola do set (`SetDesk.sheet`): linhas, faixas com tom e BPM, trocas (compassos, estilo, ritmo, grave, marca do ensaio), onde cada vertente sai e junta, pool. Celular: compartilhar do Android; computador: área de transferência (`StudioHost.share`).
- Evidência: core 127 (+`whatIsAskedTogetherIsUndoneTogether`, `aoVivoMostraOQuePodeVirDepois`); desktop `StudioTest` ampliado com cliques e arraste de mouse (marcar drop → 10 compassos e grave no drop; OUVIR C = abertura → C → fechamento; arrastar de B para C e desfazer; afastar; cola; ao vivo com as três saídas certas); celular `SetBoardTest` (ver tudo, ao vivo). Capturas `docs/ui/desktop-set-ao-vivo.png`, `desktop-set-afastado.png`, `mobile-45`, `mobile-46`.
- Item 8, em parte: a triagem (MARCAR: uma faixa por vez, estrelas e cor grandes, playlist alvo, onda que toca) passou para o código compartilhado (`studio/TriageUi.kt`, `Wave.kt`) e o computador ganhou **MARCAR ▸** ao lado dos filtros (Esc fecha). Desktop `StudioTest.markingOneByOneWorksOnTheComputer` (3 estrelas pela tela → nota 3 e passa para a próxima). Não passaram: o guia de primeiros passos e a importação em segundo plano (o computador tem a sua importação, com janela de revisão).
- Não verificado: aparelho (toque longo para arrastar, rolagem com zoom, compartilhar), som no computador.
- Fechamento da rodada: celular 67 testes (3 vídeos pulados, 0 falhas; `SetBuildingTest` passou a desfazer a ponte com um desfazer só, como o comentário dele já dizia), computador 8 (2 pulados: capturas antigas e a medição), núcleo 127. Versão 0.11.0.

## 2026-10-27 — Mixer e passo a passo no MIX (relato do dono no aparelho)

- Relato com print do MIX no celular (0.11.0): faltava ver o que acontece por baixo — grave, médio, agudo, faders — para aprender a fazer a virada.
- O mixer existia só em AJUSTAR (barras HI/LOW/FADER). Agora fica sempre à vista, como o da controladora: botões HI, MID e LOW por canal (meio-dia = normal, 7 horas = cortado, vermelho e KILL), fader de canal de pé, crossfader no meio (a prévia usa os faders de canal) e ECHO. Na prévia, agudos e médios andam juntos (um filtro só), então HI e MID mostram o mesmo valor.
- `MixGuide.steps` (núcleo): os passos da virada ditos em gestos, a partir dos mesmos pontos de `MixRenderer.levels` (entrada do CH 2 com o LOW fechado, abrir HI/MID até a troca do grave, trocar o grave de uma vez ou aos poucos, baixar o fader do CH 1; mistura, eco e corte também). `MixGuide.at`: o que está acontecendo e o próximo. Tela: PASSO com "comp. 4 / 8" (ou "saída em N comp."), o passo atual aceso e "em N comp. · próximo".
- Evidência: core `os passos da virada seguem o que a previa faz` (conferido contra os níveis nos compassos 16, 24, 25); mobile `SetFlowTest` (no meio da troca, PASSO diz "Troca o grave aos poucos"); captura `docs/ui/mobile-47`.
- Não verificado: no aparelho.
- Celular completo 67 (3 pulados, 0 falhas), computador 8 (2 pulados), núcleo verde. Versão 0.12.0.

## 2026-10-27 — Refino de design (look and feel)

- Pedido: rodada de refino de design, layout e usabilidade.
- Auditoria nas capturas atuais (celular em pé e deitado): (1) barra de baixo com cinco caixas de borda grossa e o anel duplo do guia parecendo defeito; deitado, rótulos cortados (MÚS, MARC); (2) guia com quatro caixas e linhas longas ocupando uma faixa da tela; (3) filtros com texto maior que o título das faixas; (4) quadrado colorido grande em cada música; (5) ✕ vermelho em cada linha da playlist e nome da playlist cortado pelos botões; (6) na faixa, OUVIR depois dos números, longe da onda.
- Correções: barra de baixo plana (o ativo em azul com traço em cima, contagem num selo laranja pequeno); barra lateral com os nomes inteiros; guia numa linha fina (quatro marcas pequenas + "PRÓXIMO Pendrive ›"); destaque do guia como halo suave (o mesmo componente vale no celular e no computador); filtros em 13sp e 32dp; cor da faixa como tirinha na borda da linha; playlist com o nome em cima da contagem ("4 músicas") e ▲ ▼ ✕ discretos; aba MARCAR da faixa na ordem de quem marca (números, onda, OUVIR, nota e cor, playlists); "‹ VOLTAR" virou "‹" (com descrição "Voltar" para leitores de tela).
- Computador: tela de entrada com o ícone e o nome do app, uma frase do que ele faz e os botões de abrir maiores (antes: dois botões pequenos no meio de uma tela preta).
- Evidência: celular completo 67 (3 pulados, 0 falhas) com o novo visual, computador 8 (2 pulados). Capturas atualizadas em `docs/ui/` (mobile-01, 03, 04, 05, 06, 10, 14; desktop-boas-vindas). Versão 0.13.0.
- Não verificado: no aparelho.

## 2026-10-27 — Rodada de UX com referências científicas

- Pedido: buscar referências científicas de conceitos de UX e aplicar. Fontes e o que mudou: `docs/ux-referencias.md`.
- Fitts / Parhi-Karlson-Bederson 2006 / Material 48 dp: `TouchTargetTest` percorre os controles tocáveis de músicas, playlist, quadro, MIX e marcar e exige área de toque ≥ 48 dp. Achado: o Compose já estende os alvos pequenos; nenhum ficou abaixo (os únicos apontados na primeira versão eram botões cortados pela borda da rolagem, não pequenos — o teste passou a ignorar esses).
- Hoober 2013: no quadro, OUVIR desceu para a barra de baixo (ao lado de ENSAIAR).
- Nielsen 1994 (visibilidade do estado): topo do celular mostra "✓ salvo", "• alterado" (grava sozinho em seguida) ou "gravando…".
- Kivetz et al. 2006 (gradiente de objetivo): "faltam N" nas três últimas da triagem.
- Kahneman et al. 1993 (pico-fim): triagem termina num resumo com PRONTO; ensaio termina num resumo com "REFAZER A PRIMEIRA".
- Testes ajustados ao fim novo (triagem e ensaio) e vídeos (laço até o resumo).
- Celular completo 68 (3 pulados, 0 falhas), computador 8 (2 pulados). Versão 0.14.0.

## 2026-10-27 — Menos memória de cabeça: biblioteca e sets (pesquisa em PIM, DJs e decisão)

- Pedido: pesquisa para diminuir o esforço mental de manter a biblioteca (novas entram, velhas ficam, as boas não se perdem) e de montar sets, com base em artigos e experimentos. Fontes, achados e o que mudou: `docs/ux-cognicao.md`.
- Núcleo: `TrackItem.dateAdded` (do banco); `smart/Keep.kt` — `isNew` (caixa de entrada: sem nota e fora das playlists), `isForgotten` (★4+, 30 dias ou mais, em nenhum set), `places`/`setCount` (onde a faixa está), `why` (por que uma sugestão: tom, BPM, nota, uso em sets); filtros `NEW` ("Novas") e `FORGOTTEN` ("Esquecidas"); a triagem começa pelas novas; `Transition.note` (lembrete da troca, guardado com o plano).
- Telas (celular e computador, código `studio/`): chips Novas/Esquecidas; "NOS SETS" na faixa (aba MARCAR no celular, inspetor no computador) com um toque para abrir o set; motivo em cada sugestão de "Pôr na linha"; LEMBRETE no MIX, na cola e no AO VIVO. O que é set: playlist aberta pelo SET (marcador `<id>.set` na pasta dos sets, fora do pendrive) e as linhas e vertentes dela.
- Relato do dono durante a rodada (print do MIX): os ajustes ficavam cortados embaixo. MIX no celular refeito: PLAY fixo embaixo; abas VIRADA (mixer, passo, lembrete) e AJUSTES (compassos, estilo, ritmo, grave, passa, marcar); ondas mais baixas; borda de baixo escurece com "▾" quando ainda rola. Deitado (largo) continua em duas colunas.
- Evidência: núcleo 134 (15 pulados, que dependem de arquivos de fora; +`KeepTest` 5); celular `KeepFlowTest` 3 (capturas mobile-49..52), `SetFlowTest` ajustado às abas (mobile-53); computador `StudioTest.newTracksAndTheSetsATrackIsInShowOnTheComputer` (captura desktop-nos-sets).
- Não verificado: no aparelho; nenhuma medida com DJs reais (ver "O que fica para depois" no documento).
- Versão 0.15.0.

## 2026-10-27 — UX com referências, rodada 2 (celular e computador)

- Pedido: nova pesquisa em artigos e comprovações de UX, aplicada aos dois apps. Fontes e mudanças: `docs/ux-referencias.md` (seção "Rodada 2").
- Contraste (WCAG 1.4.3): medidos todos os pares de texto e fundo. Corrigidos o cinza sobre a linha selecionada e o botão ativo (`Palette.dimOn`; computador `Pal.MutedBright` na linha da tabela) e o texto do aviso de erro. `ContrastTest`.
- Desfazer em vez de perguntar (Shneiderman; Bravo-Lillo et al.): tirar faixa de onde sai vertente sai direto, com DESFAZER. Computador: o aviso de ação rápida tem DESFAZER (Ctrl+Z) e dura 8 s. A confirmação ficou só para o que não volta (exportar, apagar).
- Progresso (Myers 1985): `jobFraction` lê "n/N" do texto do trabalho; barra que enche e porcentagem no celular e no computador.
- Atalhos (Lane et al. 2005; Grossman et al. 2007): `StudioKeys` (Espaço, ← →, 1–5, 0, Ctrl+Z/Y, Esc, ?/F1), a tecla escrita ao lado dos botões, lista em `?`; desligados quando um campo de texto tem o foco (`typingField`).
- Rótulos nos ícones (Wiedenbeck 1999), no computador: zoom e ações do cartão com palavra.
- Achado durante a rodada: a lista de atalhos usava o diálogo sem o tema e o título saiu quase invisível; agora usa `ManagerTheme` e só OK.
- Evidência: celular `ContrastTest`, `SetBoardTest` (DESFAZER traz a vertente de volta); computador `StudioTest.keyboardShortcutsAreShownAndWork` (atalhos, dicas, rótulos, aviso com DESFAZER, `jobFraction`; capturas desktop-atalhos e desktop-desfazer).
- Não verificado: teclado ABNT2 real (o `?` depende do leiaute; F1 funciona em qualquer um); aparelho. Versão 0.16.0.

## 2026-10-27 — UX com referências, rodada 3 (celular e computador)

- Pedido: mais pesquisa, mais artigos, mais conceitos aplicados. Fontes e mudanças: `docs/ux-referencias.md` ("Rodada 3").
- Heer & Robertson 2007: cartões do quadro deslizam até o lugar novo (por identidade da faixa); fios e compassos aparecem depois, em etapas.
- Hoggan et al. 2008: retorno tátil nos botões do MIX e ao pegar e soltar cartão (celular).
- Maister 1985: "MONTANDO A PRÉVIA · ≈N s" pelo tempo da última montagem (`SetDesk.lastBuildMs`).
- Palmer 1992: AJUSTES do MIX em três molduras com título.
- Nielsen, heurística 9: `friendlyError` (causa e próximo passo) no celular, no computador e na janela de pendrive.
- Achados: o Compose gerava método inválido com `return@key` dentro de `key {}` (ClassFormatError no computador); reorganizado. `mixBusy` passou a ter set privado.
- Evidência: computador `StudioTest` 4/4 (inclui `friendlyError`); celular `SetFlowTest` (estimativa), suíte completa no fechamento. Não verificado: tato e animação no aparelho. Versão 0.17.0.

## 2026-10-27 — Pesquisas recentes (UX) e rodada de design

- Pedido: conceitos de artigos recentes (modernidade) e depois artigos de design aplicados ao produto.
- UX recente (`docs/ux-referencias.md`, "Rodada 4"): Amershi et al. 2019 (trocas ajustadas × automáticas no quadro; "não sugerir esta" e controle geral no ⋯), Jin et al. 2020 (rumos COMBINA/SOBE/DESCE/DESCOBRIR; `Suggest.next`), Millecamp et al. 2019 (motivo curto), Android 14 texto até 200% (`LargeTextTest`; linhas com altura mínima; `CappedText` no quadro e no MIX), WCAG 2.3.3 (`reduceMotion`), voltar preditivo.
- Design (`docs/design-referencias.md`): cor nunca sozinha (WCAG 1.4.1, Birch 2012: `FitMark`, ✓/!, fio pontilhado), auditoria de colorido e complexidade (Reinecke et al. 2013; `tools/visual_audit.py`; cores não escolhidas apagadas na faixa e na triagem), curva emphasized do Material 3, PLAY maior (Material 3 Expressive, cobertura de imprensa).
- Achado: com texto a 200% a lista cortava artista e tom, e o cabeçalho do MIX quebrava "REMAIN" letra por letra. Corrigido (capturas `mobile-54` a `mobile-57`).
- Evidência: núcleo `KeepTest` 6 (rumos); celular `KeepFlowTest` (rumo, não sugerir, ajustada), `LargeTextTest`, `SetBoardTest`, `SetFlowTest`, `TouchTargetTest`, `DesignSystemTest`; suíte completa no fechamento. Não verificado: aparelho (texto grande real, remover animações, voltar preditivo). Versão 0.18.0.

## 2026-10-27 — Facilitadores de set (e a decisão sobre o Spotify)

- Pedido: mais facilitadores para montar o set, e usar músicas do Spotify (conta conectada) para classificar, montar e mixar, sem exportar.
- Pesquisa antes de prometer: a Developer Policy do Spotify (versão de 15/05/2025) proíbe mixar, sobrepor ou remixar conteúdo do Spotify; desde 27/11/2024 apps novos não têm audio features/análise (BPM, tom) nem prévias de 30 s; o acesso estendido exige empresa com 250 mil usuários e o modo de desenvolvimento ficou com poucos usuários Premium. Mixar faixa do Spotify aqui violaria os termos. Opções levadas ao dono (faixas planejadas por link, com ou sem conta, ou não integrar); decisão do dono: **não integrar o Spotify**.
- Facilitadores feitos (celular e computador, código `studio/`):
  - TROCAR no cartão do quadro: as que combinam com a de antes e a de depois primeiro (✓), depois as que combinam com uma (!); PÔR troca no mesmo lugar, um desfazer volta. Não troca faixa de onde sai vertente (avisa). Núcleo `SetDoctor.replacements`.
  - Duração alvo (⋯): "≈1:48 / 2:00 · faltam 12", com ✓ dentro de 5%; guardada por set fora do pendrive.
  - Duplicar set (⋯): cópia "(v2)" com as faixas, as trocas ajustadas, o master, a pool e o alvo, para testar sem mexer no original (as vertentes não vão junto; o aviso diz).
- Evidência: núcleo `KeepTest` 7; computador `StudioTest.setHelpersReplaceGoalAndDuplicate` (captura `desktop-trocar`); suíte completa no fechamento. Não verificado: aparelho. Versão 0.19.0.

## 2026-10-27 — Recursos de show: pronto pro show, arrumar em lote, pedido e guardar o que tocou

- Pedido: implementar os quatro facilitadores sugeridos do ponto de vista do DJ, com decisões técnicas e de layout embasadas em artigos. Fontes e decisões: `docs/ux-show.md`.
- Núcleo: `smart/Show.kt` — `ShowCheck.check` (áudio, grade, cues Intro/Drop/Outro, repetidas, trocas com aviso, não ensaiadas) e `Request.route` (menor caminho em camadas, até 3 faixas, só arestas que combinam).
- Studio (celular e computador): ⋯ → "Pronto pro show?" (`ShowDialog`), ARRUMAR (`prepareForShow`: análise e cues de estrutura onde faltam, sem mexer nos do DJ, em segundo plano com n/N, um desfazer); AO VIVO → PEDIDO (`RequestDialog`, PÔR NO SET logo depois da que toca); ao sair do AO VIVO, "Guardar o que tocou?" (`savePlayed`, playlist "Tocado dd/MM", marcada como set).
- Evidência: núcleo `ShowTest` 2; celular `ShowFlowTest` 3 (capturas mobile-58, mobile-59); computador `StudioTest.aRequestFromTheFloorGetsABridgeIntoTheSet` (8A → 7A → 6A; captura desktop-pedido); suíte completa no fechamento. Não verificado: aparelho, cabine. Versão 0.20.0.

## 2026-10-27 — Um conceito, um lugar (ADR-0011) e AO VIVO guardado

- Pedido: cuidado com duplicações (efeitos, sync, faixas), o ideal é tudo no core; tirar o AO VIVO da biblioteca por enquanto, guardado.
- `os.deckbridge.logic` saiu de dentro do app de performance para `core/src/main/kotlin/os/deckbridge/logic/` (git mv); o app de performance compila de lá por `srcDir`; o núcleo deixou de apontar para a pasta do app.
- Tom: um parser (`camelotOf` com sustenido, bemol, ♯/♭, "min"), uma distância (`camelotDistance`), um deslocamento (`camelotShift`); `Smart` e `SetPlanner` delegam. Achado: o parser da performance não entendia "G#m", "A#", "Gb".
- Partes da faixa: `Section` (Intro, Build, Drop, Break, Outro) no lugar do texto solto em 6 arquivos.
- Eco: a prévia do MIX usa as contas do ECHO do motor (`EchoSpec`, quantidade 0,8 na prévia); `EchoSpecTest` lê `dsp.h`. Achado: com a quantidade do meio (0,5) o eco do motor some antes do compasso seguinte (o teste da saída com eco pegou).
- AO VIVO: `Features.liveMode = false`; menu, pedido e "guardar o que tocou" fora da tela; testes ligam a chave só para eles; teste confirma que "Ao vivo" não aparece no app.
- Pendente (proposto no ADR-0011): prévia do MIX pelo motor C++ (render offline por JNI) para tirar a última duplicação de efeitos.
- Evidência: núcleo 139 (+`EchoSpecTest`), app de performance 149 (compilando a lógica do núcleo), computador e celular na suíte de fechamento.

## 2026-10-28 — Um app só: modos DJ e Biblioteca (ADR-0012)

- Pedido: "os dois apps deveriam ser um só… o modo de DJ deve só carregar as coisas de DJ… para economizar RAM e CPU; e aí vamos ter um modo de DJ para o PC também".
- Um APK, um ícone ("DeckBridge", id `os.deckbridge.manager`, o da antiga Biblioteca DJ, para não perder a biblioteca do aparelho). `mobile/app` virou o módulo de biblioteca Android `:biblioteca` do projeto `android/`; os arquivos de raiz do Gradle em `mobile/` saíram.
- Modo DJ (`MainActivity`) no processo principal; modo Biblioteca (`ManagerActivity`) no processo `:biblioteca`. Trocar (MENU → BIBLIOTECA ›; ⋯ → Modo DJ ›) grava o pendente, abre o outro modo e encerra o processo atual (`Modes.switchTo`). Durante importação a troca espera.
- Entrada `StartActivity` abre o último modo (arquivo `files/modo.txt`, legível pelos dois processos). Atalho "Modo DJ" no ícone.
- O modo DJ abre sozinho a biblioteca do aparelho (`FolderVolume`, só leitura) quando não há pendrive autorizado ligado, ou ao vir da Biblioteca. Não escreve nela nem no pendrive.
- O app usa o núcleo como dependência (`os.deckbridge:core`), sem `srcDir`. Achados: a linha da lista do núcleo não estreita o tipo entre módulos (BrowseScreen, corrigido com variável local); 9 funções `internal` do núcleo usadas pelos testes do app ficaram públicas.
- Achado: com `android:process` no manifesto do módulo, o Robolectric não devolvia à tela o resultado do trabalho em segundo plano ("Pronto pro show?" ficava em "Conferindo…"; isolado por bisseção contra o commit anterior). O processo `:biblioteca` passou a ser declarado no manifesto do app (o merge junta os atributos; conferido no APK com `aapt2`).
- `ModesTest` (3): último modo entre processos, mesma pasta para os dois modos, intenção do modo DJ.
- Evidência: núcleo 139/139; modo DJ (`:app:testMultiDebugUnitTest`) 149, 0 falhas (8 pulados por falta de arquivos locais); Biblioteca (`:biblioteca:testDebugUnitTest`) 76 da suíte completa + `ShowFlowTest` e `ModesTest` refeitos depois da correção do processo; APK `multiDebug` montado.
- Não verificado (`BLOCKED_HARDWARE`): memória devolvida na troca, tempo de troca, troca com a DDJ-400 ligada; nada rodou em aparelho. O histórico e os ajustes do antigo app de performance (`os.deckbridge.android`) não passam para o novo.

## 2026-10-28 — Modo DJ no computador (ADR-0012, item 7)

- As telas e a lógica dos decks saíram do app Android para `stage/src` (`DeckPage`, `PlayerScreen`, ondas, mixer, browse, RMX, menu, histórico, `NativeEngine`, `PerformanceHub`), compiladas pelo app e pelo programa de computador, como `studio/src` nas telas de set.
- O que é da plataforma ficou atrás de `DjHost`: decodificar, AAC, ajustes, sessão, histórico, capa, exportar texto, nome do aparelho. Android: `AndroidDjHost` (MediaCodec, DataStore, SQLite, seletor). Computador: `DesktopDjHost` (ffmpeg, arquivos em `~/.deckbridge/dj`, CSV). A capa (`CoverTile`) tem uma versão por plataforma. O botão do outro modo vem por `LocalModeSwitch`. `PerformanceHub.ledPort` virou uma função de envio (no Android, a porta MIDI; no computador, o `Receiver` do javax.sound.midi).
- Motor: o Gradle do computador compila `engine.cpp` + `controller_jni.cpp` com o Oboe sobre ALSA do Pi (`pi/alsa`) numa `libdeckbridge-engine.so` (só Linux).
- DDJ-400: `DesktopMidi` abre as portas com "DDJ" no nome e passa as mensagens pelo mesmo montador e regra C++; os LEDs voltam pela saída.
- Programa: `--dj` abre só o modo DJ; ⋯ → Modo DJ › e MENU → BIBLIOTECA › reabrem o programa no outro modo e encerram o atual.
- Achado: a carga da faixa guardava a onda de detalhe com `withContext(Dispatchers.Main)`, que não volta numa cena de teste do computador; agora a onda é devolvida e gravada de volta no contexto da tela, sem depender do "Main" de cada plataforma.
- `DjModeTest` (2): ajustes, sessão e histórico na pasta do app; a tela do DJ abre a biblioteca sintética, o encoder entra em Track, LOAD carrega no deck 1, PLAY anda no motor C++ real (PCM `null` do ALSA) e nenhum arquivo da biblioteca muda (captura `desktop-dj`).
- Não verificado (`BLOCKED_HARDWARE`): DDJ-400 no computador (MIDI, LEDs, 4 canais), som numa placa real, latência, Windows e macOS (o motor no computador é só ALSA).
- Evidência: computador `./gradlew test` 15 (2 pulados: capturas só com `SHOTS_DIR`), 0 falhas; modo DJ Android `:app:testMultiDebugUnitTest` 149 (8 pulados por arquivos locais), 0 falhas, já com as telas em `stage/`; APK `multiDebug` montado.

## 2026-10-28 — MIX: mixagem ao vivo com a mão do DJ, encaixe à mão e automático melhor (0.23.0)

- Pedido: ajustar o sync quando a música entra fora da grade; trocas automáticas menos apressadas e completas (baixar a que sai enquanto a outra entra); interferir no meio da troca (efeito, grave) e o app gravar isso como a mixagem certa daquela troca do set; com o grave cortado, subir pelo fader. Fontes e decisões: `docs/ux-mixagem.md`.
- Núcleo: a prévia guarda as duas faixas prontas (`Stems`, 16 bits, a que entra com um compasso de folga) e é mixada por `MixDsp` (EQ de 3 bandas que soma de volta ao original, fader, FILTER de estado variável, ECHO por canal, níveis interpolados em blocos de 16 quadros) — o mesmo código para o ao vivo (`MixPlayer`, `PcmSource`) e para o WAV (`MixPreview.mixdown`). Estilo, ritmo, grave, sync e mão mudam sem ler o áudio de novo (`sameStems`).
- Mão do DJ: `Ctl`, `Lane` (latch; gesto contínuo interpolado; NaN volta ao automático), `MixTake` (merge por trecho, release por canal, texto compacto no plano do set), `TakeRecorder`. `Transition.take`, `nudgeMs`, `beatShift` guardados no `.properties` do set.
- Aprender: `MixHabits` (mediana de ≥3 mixagens guardadas: ponto do grave e tempo de saída) aplicado às trocas automáticas novas em `SetPlanner.plan`; guardado em `habitos.properties`.
- Automático: troca de grave com o CH 2 entrando pelo fader (LOW fechado, HI/MID no meio-dia), o CH 1 tirando agudo e médio, grave num início de compasso, saída de no mínimo 4 compassos (`swapShape`); `MixGuide` segue.
- Tela: barra SUA MÃO / SUA MIXAGEM / AUTOMÁTICO com GUARDAR, DESFAZER e AUTOMÁTICO; mixer de arrastar com semântica de valor ajustável (leitor de tela); ENCAIXE DO CH 2; LOOP; atalhos `,` `.` `[` `]` `L` `Enter`. Android toca ao vivo por AudioTrack (fluxo, ~80 ms); computador por SourceDataLine.
- Achados: o corte seco aplicava a rampa de 10 ms só depois do ponto (o comentário dizia antes); com blocos de níveis, a cauda do clique vazava — a rampa agora acontece antes do ponto. Blocos de 64 quadros espalhavam o corte da saída com eco; ficaram em 16.
- Evidência: núcleo `MixTakeTest` 8 (latch, merge, gravador, plano, ao vivo = WAV quadro a quadro, FILTER, sync à mão sem remontar, aprender) e `MixTest` ajustado ao automático novo; celular `MixHandTest` 2 (knob pela acessibilidade, guardar/desfazer, encaixe sem remontar, loop, três mixagens ensinam as trocas novas; capturas mobile-60, mobile-61), `SetFlowTest`, `TouchTargetTest`, `DesignSystemTest`, `LargeTextTest`; suíte completa no fechamento.
- Não verificado (`BLOCKED_HARDWARE`): som real no aparelho (latência do toque ao som, CPU da mixagem ao vivo), DDJ-400.
- Fechamento: núcleo 147 (15 pulados por arquivos locais), 0 falhas; computador 15 (2 pulados), 0 falhas; Biblioteca 81 (3 pulados: vídeos com músicas locais), 0 falhas; APK `multiDebug` 0.23.0 (versionCode 24) montado.

## 2026-10-28 — MARCAR só com o que falta e telas fora da barra do sistema (0.23.1)

- Relato do dono: "minha biblioteca tá toda classificada e quando abro ele me obriga a passar por todas as músicas"; "os atalhos de cima ficam atrás da UI do celular com a hora".
- Causa 1: `Smart.triageQueue` punha as já marcadas no fim da fila; com tudo classificado a fila tinha a biblioteca inteira. Agora só as sem nota (novas primeiro); vazia, MARCAR não abre e diz "Tudo marcado ✓". Rever as marcadas virou escolha: MENU → Rever as marcadas (`Smart.reviewQueue`, nota mais alta primeiro). Também no computador.
- Causa 2: a Biblioteca desenha de ponta a ponta (`enableEdgeToEdge`) e a tela de MARCAR (e o resumo do fim) não descontava a barra de status nem a de gestos; o ✕ e o contador ficavam atrás da hora. Agora `statusBarsPadding`/`navigationBarsPadding`, como SET, mapa e MIX; o aviso de baixo também desconta a barra de gestos. Voltar do Android fecha a triagem.
- Evidência: núcleo 147/147 (`SmartTest`, `KeepTest` ajustados); celular `TriageQueueTest` (biblioteca toda marcada não abre, rever por escolha, ✕ fecha), `UxFlowTest`, `KeepFlowTest`, `ScreenshotTest`, `TouchTargetTest`, `PlatformTest`, `DesignSystemTest` passando. Não verificado em aparelho: a altura real da barra de status (nos testes de JVM ela é zero).

## 2026-10-29 — Achar a troca: o app ouve as faixas e procura a mixagem como um DJ (0.24.0)

- Pedido do dono: melhorar a construção das mixagens — analisar as faixas e achar uma troca boa olhando os breaks onde dá para virar o grave, encaixar as duas, usar loop quando precisa, inclusive com músicas difíceis. Decisões e fontes: `docs/ux-mixagem.md`, "Achar a troca".
- Núcleo: `TrackProfile` (por compasso, preso à grade: baixo pela mediana do grave em 20 ms, bateria pelo pulso de 5 ms no grave ou no agudo, melodia, frases por `phraseBars`; cache em texto versionado). `MixFinder` (saídas × entradas × durações × ponto do grave × loops; pontua sem dois baixos nem buraco, grave com batida, choque de melodias pelo tom, frases, drop no fim, não queimar o drop, não arrastar; eco/corte/filtro para pares difíceis; devolve as 4 melhores variadas com nota e porquê). `Transition` ganhou `aLoopBars`, `bLoopBars`, `bLoopTimes`, `why` e o estilo `FILTER`; a prévia toca os loops na grade (`MixRenderer.loop`, emenda de ~3 ms) e sabe onde cada faixa está (`aAt`, `bAt`, `timesA`, `timesB`).
- Studio: `SetDesk` ouve as faixas do set numa thread de fundo de prioridade baixa (perfis em `perfis/` na pasta dos sets, nunca no pendrive), guarda as opções por par e usa a melhor como troca automática (as ajustadas pelo DJ ficam). MIX → AJUSTES: ACHAR A TROCA (opções com nota, USAR/EM USO, POR QUE ESTA TROCA), FILTRO no estilo, LOOP D1 e LOOP D2; a onda marca as voltas do loop e as batidas de cada volta.
- Achados: o pulso do bumbo de `barFeatures` (95º percentil contra a mediana) some num drop com baixo contínuo e num clique de 20 ms (cai na borda do percentil e oscila ao longo da faixa); o perfil usa o 98º percentil nas duas faixas de frequência. O nível de grave do compasso conta o ataque do bumbo como baixo; o perfil usa a mediana em 20 ms.
- Evidência: núcleo `MixFinderTest` 6 (perfil acha baixo, bateria, melodia e frases de uma faixa sintética; sai no Outro, entra na intro e o drop cai no fim; outro curto pede loop; tons longe não juntam melodias; loops na grade da prévia; loops e porquê no plano). Celular `MixFinderUiTest` (ouve em segundo plano, perfis guardados, opções com porquê, USAR vira a troca do set; captura mobile-62).
- Não verificado: músicas reais (os pesos foram conferidos só com faixas sintéticas); tempo e memória de ouvir uma faixa no aparelho (`BLOCKED_HARDWARE`).

## 2026-10-29 — Biblioteca limpa: nomes, repetidas pelo áudio e menus no lugar (0.25.0)

- Relato do dono: faixas repetidas com nomes diferentes e nomes sujos não arrumados (print: "0003-Duke_Dumont,_Purple_…" sem artista; artista "0000-Adriatique"); e pedido de deixar no MENU só o que vale para o app todo. Decisões e fontes: `docs/ux-biblioteca-limpa.md`.
- Causa achada: `ImportQueue` copiava cada arquivo como `%04d-<nome>` e o título/artista saíam do nome da cópia. Agora a cópia fica numa pasta numerada com o nome original.
- Núcleo: `TrackNames` (limpeza de título e artista por regras; chave de comparação); `AudioIo.probe` devolve os nomes limpos; `Organizer` regra "Título e artista arrumados" (troca a de nome do arquivo, que só preenchia vazios) e repetidas por `Duplicates`; `Smart.findDuplicate` compara nomes limpos; `AudioPrint` (Haitsma e Kalker 2002 com bits confiáveis; trecho de 60 s a partir de 30% da faixa; distância com busca de ±12 s); `PrintStore` (cache em `digitais/`); `Duplicates.find`/`merge` (playlists no lugar, nota, cor, gênero, comentário; uma leva, um desfazer); `PreparedTrack.print`.
- Celular: importação pula a mesma gravação com outro nome (áudio, duração ±3 s, BPM ±0,5); MÚSICAS → ⋯ (ferramentas: importar pasta, analisar todas, arrumar nomes, procurar repetidas, refazer grades, rever as marcadas); tela de repetidas (FICA/SAI, JUNTAR, Juntar todas); MENU enxuto; MARCAR saiu da barra de baixo e virou o primeiro botão da fileira de MÚSICAS (passo do guia aponta para MÚSICAS). Achado: Arrumar nomes pelo MENU não abria (a janela só existia na aba MÚSICAS); as janelas agora ficam na raiz.
- Evidência: núcleo `TrackNamesTest` 6 (nomes do print do dono, número/tom/BPM, artista no título, lixo, nome certo não muda, chave), `DuplicatesTest` 3 (mesma gravação recodificada ~0,06 vs outra música, grupos pelo áudio e pelo nome, juntar), `OrganizerTest` ajustado; celular `LibraryCleanupTest` 2 (arrumar nomes sujos pelo ⋯; mesma música com outro nome achada, JUNTAR, playlist herdada, desfazer; capturas mobile-63, mobile-64).
- Não verificado: músicas reais do dono; tempo de "Procurar repetidas" no aparelho (`BLOCKED_HARDWARE`).

## 2026-10-29 — Ouvir o set inteiro em segundo plano, player na notificação, tela acesa no MIX (0.26.0)

- Pedidos do dono: tela apagando ao ouvir a mixagem; ouvir o set em segundo plano (navegar, sair do app, bloquear a tela); player na notificação com salto entre viradas; e "um ouvir pro set todo: as músicas com as transições". Detalhes: `docs/ux-ouvir-set.md`.
- Núcleo: `SetStream` (PcmSource: faixa até a saída, virada montada sem folga com `MixPlayer`, faixa seguinte de onde a virada deixou; produtor em thread de fundo 45 s à frente; pular por posição; rampas de 5 ms nas emendas); `PcmSource.close()`; `writeSequence` relata onde começa cada parte (capítulos do "só as viradas").
- Studio: `SetDesk.playWhole` (OUVIR da lista e do quadro), "Ouvir só as viradas" no ⋯; `StudioPreview.playSet`/`playSetStream`/`inBackground`; `Chapter` (início, título, virada ou faixa); sair do set não para o que toca em segundo plano.
- Celular: `Preview` com set em segundo plano (foco de áudio GAIN, pausa em perda curta, pausar/retomar, saltar entre viradas 10 s antes, Faixa/Virada n/N); `SetPlaybackService` (primeiro plano, mediaPlayback, MediaSession, MediaStyle com capa por trecho, fone tirado pausa); barra do player nas abas; licença de notificação no Android 13+; `keepAwake` (tela acesa no MIX e no ensaio); prévia ao vivo em 16 bits.
- Achado e corrigido: `Preview.StreamRun` chamava `Thread.stop()` (não o do Preview) quando a prévia ao vivo acabava → `UnsupportedOperationException` na thread da tela.
- Evidência: núcleo `SetStreamTest` 2 (ordem e tempo faixa/virada/faixa; pular perto de uma virada); celular `SetPlayerTest` 3 (OUVIR toca o set inteiro com trechos de faixa e de virada, serviço de mídia ligado, barra em outras abas, pausar, próxima virada 10 s antes, parar; "só as viradas" no ⋯; regra da tela acesa; capturas mobile-65/66/67).
- Não verificado: som real, notificação e tela bloqueada no aparelho, Bluetooth, consumo (`BLOCKED_HARDWARE`).
