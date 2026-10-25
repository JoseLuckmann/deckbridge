# DeckBridge OS — estado em 0.15.0 (efeitos, sem escuta)

App Android para a DDJ-400. O alvo 1 é o Galaxy Tab S6 Lite. O Raspberry é o alvo 2 e ainda não é um aparelho. Esta página é a leitura única. A spec está em `specs/001-ddj400-standalone/`. A escuta na controladora continua `BLOCKED_HARDWARE`. Simulação não fecha o roteiro.

## Versões

Cada linha é o que entrou no app. Nenhuma foi ouvida no tablet.

- **0.5.0-palco.** Tema de palco, dois decks, contraste, onda maior. APK arm64 com assinatura de debug.
- **0.6.0.** Player no espírito de duas telas de leitor de club, browser por cima, zoom, grade, indicador de Beat FX. O efeito ainda não processava áudio.
- **0.7.0.** Onda empilhada, browser denso, echo, delay e flanger no motor.
- **0.8.0.** Menu, salto, loop, lista de Beat FX no motor, RMX (cena, X-Pad, isolador, release).
- **0.9.0.** RMX em vista cheia, histórico, gravação WAV do master, variantes de export.
- **0.9.1.** O histórico lê a faixa carregada. Alvos de toque. Isolador em dB.
- **0.10.0.** Capa, Key Shift, Key Sync pela regra dos seis vizinhos, filtro, slip com regresso, emergency loop.
- **0.11.0.** Onda RGB, 3 BANDAS e AZUL a partir do export. `DEVSETTING.DAT` quando o byte está documentado.
- **0.12.0.** Corpus CC BY 3.0 fora do git, ordenar a lista, Active Loop, reverse, grade na memória do app, inspeção de pasta sem gravar.
- **0.12.1.** Grade presa ao kick, RGB aditivo (grave vermelho, médio verde, agudo azul), emenda de loop.
- **0.12.2.** Medição GiantSteps. Acc1 0,655. Pasta de MP3/WAV sem export do rekordbox.
- **0.13.0.** Slip na pausa, no scratch, no hot cue e no reverse. Frases, MATCHING, SUGGEST, medidor de fase, auto gain, beat jump 64.
- **0.13.1.** Tom por CQT e perfil EDMA publicado. MIREX 0,586 nas 58 faixas.
- **0.14.0.** Knobs que deslizam, de/para com os manuais, drum build sintetizado, mix de demonstração v2.
- **0.14.1.** One-shots CC0 no drum build, presos à fase da grade. Escolha de som no RMX. Onset medido a 0 ms. versionCode 18.
- **0.15.0 (host, sem APK novo).** Reverb FDN, deslocador de grão, RMX reescrito com cauda no drop, riser de bumbo (afinação em curva, bombeio, respiro, impacto), seis Sound Color FX. Ver `docs/testing/house-mix.md`.

## O que funciona, em simulação

Medido no host ou na JVM. Não é o tablet e não é a DDJ-400.

- Dois decks, play, cue, pitch de 14 bits, volume, crossfader, sync por BPM, hot cue, loop, beat jump até 64, reverse e slip.
- Biblioteca `export.pdb` e ANLZ em fixture sintética. OneLibrary só abre uma cópia local. O USB não é destino de escrita.
- Pasta de MP3/WAV sem export: BPM, tom e miniatura RGB em cache. O carimbo desse cache é `0131`.
- Beat FX e Color FX (só o knob FILTER) no motor, com deslize de parâmetro. RMX com cena, X-Pad, isolador e release.
- Drum build com bumbo, palma, caixa e chimbal em FLAC CC0, na grade do deck. O teste de onset ficou em 0 ms a 120, 128, 140 e 174 BPM, com pitch ±8%.
- Histórico local e gravação WAV do master. O anel de gravação não aloca na thread de áudio, no teste hospedeiro.
- Tela desenhada em 800×480, 1024×600 e 2000×1200, na JVM. O ícone adaptativo existe desde 23/10.

## O que falta

### Efeitos

O de/para está em `docs/research/fx-de-para.md`. O manual não publica as curvas. O que o app ainda não faz:

- Spiral sobe o pitch por repetição (0.15.0), mas não quando a fração muda.
- Helix trava no máximo do knob (0.15.0); não grava ao subir o knob.
- Mobius não tem parâmetro publicado para fechar.
- Pitch é um deslocador de grão de −1 a +1 oitava (0.15.0), com artefatos em material tonal denso.
- Roll e slip roll não misturam o seco com o roll pelo LEVEL/DEPTH.
- O Filter de Beat FX oscila no ciclo da fração (0.15.0); a curva é nossa.
- O Vinyl Brake não devolve a velocidade com o knob à esquerda.
- Os seis Sound Color FX existem no motor e num chip do menu; não há seletor na tela do deck. O Q do FILTER é fixo.
- As cenas do RMX usam um knob só. O aparelho de referência tem o knob grande e dois sub-parâmetros.
- O drum build não usa as fontes internas desse aparelho. Os hits são gravações CC0. A palma é prévia MP3 do Freesound, não o WAV de 24 bit.

### Analisador

Números em `docs/testing/ground-truth-corpus.md`. Corpus fora do git.

- Tempo: no holdout, Acc1 0,894 e Acc2 0,947 desde 23/10 (`docs/testing/tempo-corpus.md`). Os números das 58 faixas abaixo são de antes. Na época eram Acc1 0,655 e Acc2 0,724, com alvo de 0,85 e 0,95. House, techno e trance fecham. Drum and bass fica em Acc1 0,400. Dubstep fica em 0,400.
- Tom nas 58: MIREX 0,586, exato 0,466, Camelot 0,759. O alvo de 0,65 não apareceu. No holdout (16 faixas, fora da escolha dos parâmetros) o MIREX é 0,456 e o exato é 0,312. No extra (190) o MIREX é 0,519 e o exato é 0,437. Nas 248 juntas o exato é 0,444.
- Frases: 17 de 26 fronteiras anotadas a 1 compasso (65%) e 22 de 26 a 4 compassos (24/10, rótulos no estilo do rekordbox). `docs/testing/phrase-annotations.md`.
- Três faixas com TKEY continuam diferentes do tag: Chromosome Boutique lê F (TKEY Bm), Ecosystem lê Cm (TKEY Ebm), Silver lê D (TKEY Em). Nenhuma foi forçada.

### Leitor de club

A tabela está em `docs/research/cdj3000-coverage.md`. Fora desta linha: Pro DJ Link, nuvem, Wi-Fi, ecrã do prato, pré-escuta por mesa, gravar loop ou banco no USB. O jog em segundos continua uma escala provisória. Master Tempo é duas linhas de atraso. Key Sync é a regra dos seis vizinhos, não o algoritmo comercial.

### App e aparelho

- Ícone adaptativo e assinatura de release existem desde 23/10. A chave de release de verdade ainda precisa ser criada pelo dono.
- O emulador desta máquina não abriu (A022). Os prints são da JVM.
- Passos 13 a 30 de `docs/hil/roteiro-validacao-final.md` estão por executar. O 25 (bring-up) deve ser o primeiro com a controladora. Incluem escuta, pendrive real, master e fone, e o RMX na controladora.
- Busca por voz (A053) e quatro decks (A054) ficam adiados até o dono reabrir.
- O alvo 2 (imagem de Raspberry, Mixxx no aparelho, gates G0 a G2) não começou no hardware.

## Roteiro de hardware

`docs/hil/roteiro-validacao-final.md`. Os passos 13 a 30 continuam abertos. Comece pelo 25. A 0.14.1 não fecha nenhum: drum build e grade não são um gesto novo na controladora. A instalação da prova A0 está em `docs/android/prova-a0.md`.

## Backlog, por ordem

1. Ouvir o app no Tab S6 Lite com a DDJ-400 e executar os passos 25 e 13 a 30. É o buraco que muda o produto.
2. Criar a chave de release (o dono) antes de entregar o APK fora deste ciclo.
3. Os buracos de efeito que a mix já deixa ouvir: spiral, helix, balanço do roll, pitch.
4. Tempo em drum and bass e dubstep, sem desfazer house, techno e trance.
5. Tom fora do corte de 58, a começar pelo holdout.
6. As 10 fronteiras de frase que ficam a mais de 1 compasso.
7. Medir o jog e fechar o que a cobertura ainda marca como aproximação de ecrã.
8. Prints de emulador (A022), se o KVM voltar a abrir.
9. A053 e A054, só com pedido novo do dono.
10. Alvo 2: inventário do Pi, áudio de 4 canais no Linux, imagem. T005 em diante.
11. Pro DJ Link, nuvem e ecrã do prato ficam de fora. A058.

## Como continuar o projeto

Quem recebe o repositório clona o bundle, que traz o histórico, os ramos e as tags.

```bash
git clone deckbridge-os.bundle deckbridge-os
cd deckbridge-os
```

O ramo é `main`. Nesta entrega não há outros ramos nem tags.

Pré-requisitos do alvo 1: JDK 17 ou mais recente (bytecode 17; a 0.14.1 foi gerada com OpenJDK 21), Android SDK com `android-35`, build-tools `35.0.0`, NDK `27.2.12479018` e CMake `3.22.1`, `ANDROID_HOME` nesse SDK, e o wrapper `android/gradlew` (Gradle `8.11.1`, Android Gradle Plugin `8.7.3`, Kotlin `2.0.21`). Os testes do motor no host pedem `g++` com C++17. A trilha Python pede Python 3.

```bash
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
cd android
./gradlew :app:testMultiDebugUnitTest :app:assembleArm64Release :app:assembleMultiDebug
```

`--offline` exige cache já preenchido. O APK arm64 sai em `app/build/outputs/apk/arm64/release/`.

Leitura: `STATUS.md` (esta página), `specs/001-ddj400-standalone/spec.md`, `plan.md` e `tasks.md`. O roteiro de hardware é `docs/hil/roteiro-validacao-final.md`, com os passos 13 a 24 ainda abertos. As listas antigas estão em `docs/hil/checklist-fase0.md` e `docs/hil/checklist-fase1.md`.

## Construir, testar e instalar

No diretório `android/`, com o SDK em `ANDROID_HOME` (nesta máquina, `$HOME/android-sdk`):

```bash
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
cd android
./gradlew --offline :app:testMultiDebugUnitTest :app:assembleArm64Release :app:assembleMultiDebug
```

O APK do tablet é `app/build/outputs/apk/arm64/release/`. O de três ABIs, para emulador, é `app/build/outputs/apk/multi/debug/`. Os dois desta 0.14.1 estão em `releases/` neste pacote, quando o zip foi montado, e em `/opt/cursor/artifacts/` na máquina de build.

- `deckbridge-arm64.apk`: 4467924 bytes, SHA-256 `4c17fecc3dea8307c01be6cba30187b801ae36ec174ab6276d3ae82a18e99d70`, versionName 0.14.1, versionCode 18, só `arm64-v8a`.
- `deckbridge-debug.apk`: 20064590 bytes, SHA-256 `6cfec4bb1367d3a296a90be06514442d4c36652acfb8c4de731a816349ce0f7a`.

Assinatura de debug, certificado SHA-256 `4c81c3d9518732e87f376517ef12a5e1d99b27f0c6f1a3ddc05c3c2b7e8a24da`. Falta o ícone. Os dois contêm `libdeckbridge-engine.so`, `libsqlcipher.so` e os cinco FLAC de bateria.

No tablet: copiar o APK arm64, permitir instalar desta fonte, abrir o DeckBridge. A primeira tela é o diagnóstico. BROWSE pede a raiz do USB, só leitura. LOAD DECK 1 ou LOAD DECK 2 carrega a faixa.

Testes de host, sem Gradle:

```bash
g++ -std=c++17 -O2 -I android/app/src/main/cpp -o /tmp/dsp_test android/app/src/test/cpp/dsp_test.cpp && /tmp/dsp_test
g++ -std=c++17 -O2 -I android/app/src/main/cpp -o /tmp/drum_grid_test tools/drum_grid_test.cpp && /tmp/drum_grid_test
g++ -std=c++17 -O2 -I android/app/src/main/cpp -o /tmp/fx_measure tools/fx_measure.cpp && /tmp/fx_measure
```

Na última passagem deste host: DSP `ALL PASS`, grade `FAILS 0` com pior erro 0 ms, `fx_measure` `FAILS 0`.

As mixes de demonstração são do motor do host, não do tablet. Roteiros em `docs/testing/demo-mix-v2.md` e `docs/testing/demo-mix-drums.md`. Licenças dos hits em `docs/licenses/drum-samples.md`.

## Decisões

| ID | Estado | Decisão |
|---|---|---|
| D-01 | emendada pela OD-11 | O produto não é um driver do rekordbox. O alvo 1 é o app nosso. |
| D-02 | vale no alvo 2 | Mixxx 2.5.6 como base dessa trilha. |
| D-03 | vale | USB rekordbox só leitura no P1. |
| D-04 | vale | Paisagem e controlos físicos. |
| D-05 | vale | Não abrir a DDJ-400. |
| D-06 | vale no alvo 2 | Raspberry Pi OS 64-bit nessa trilha. |
| D-07 | vale | Offline primeiro. |
| D-08 | emendada pela OD-17 | No tablet a UI é Compose, desenho próprio. |
| D-09 | vale | Análise fora da thread de áudio. |
| D-10 | vale | Licença aberta, nome sem marca de terceiro. OD-06 ainda escolhe a licença final. |
| OD-01 | decidida | Vários Raspberry e Linux genérico no alvo 2. Capacidade lida em execução. |
| OD-02 | decidida | Layout adaptável. Referência 800×480 e 1024×600. O tablet também usa 2000×1200. |
| OD-03 | decidida | MP3 e WAV primeiro. FLAC e AIFF continuam se o decoder existir. |
| OD-04 | aberta | Versão do rekordbox do dono. Default: export tradicional. |
| OD-05 | aberta | Tomada ou bateria no aparelho Pi. Default: tomada. Convive com a OD-15, que é outra pergunta. |
| OD-06 | aberta | Licença final. Default provisório: GPL-2.0-or-later. |
| OD-07 | aberta | Nome definitivo. Default: DeckBridge OS. |
| OD-08 | aberta | PWA sem comandos de mixer. |
| OD-09 | aberta | Métricas do trainer. |
| OD-10 | aberta | Gravar o set não bloqueia o P1. O tablet já grava WAV local. |
| OD-11 | decidida | Alvo 1: tablet na DDJ-400. Alvo 2: Raspberry. |
| OD-12 | provisória | Motor próprio no Android. É o código da 0.14.1. Falta a confirmação formal do dono. |
| OD-13 | provisória | Só leitura no app, com o Android a montar o volume. A OD-23 fixa a regra. |
| OD-14 | provisória | Se faltarem 4 canais, trocar de tablet antes de um driver USB. |
| OD-15 | provisória | Set na bateria, sem hub. |
| OD-16 | provisória | Distribuir por APK direto. É o que esta versão faz, com assinatura de debug. |
| OD-17 | decidida | Ecrã no espírito do leitor de dois decks, sem logo desse leitor. |
| OD-18 | decidida | RGB aditivo: vermelho grave, verde médio, azul agudo. |
| OD-19 | decidida | RMX na 0.8.0. Mapa dos pads provisório. |
| OD-20 | decidida | De/para dos efeitos com os manuais públicos. Sem áudio da Pioneer. |
| OD-21 | decidida | Drum build com one-shots CC0 na grade do deck. |
| OD-22 | decidida | A053 e A054 adiadas. |
| OD-23 | decidida | USB do rekordbox somente leitura nos dois alvos. ADR-0002. |
| OD-24 | decidida | Publicar as métricas do analisador sem as tratar como alvo cumprido. |

A constituição 1.1.0 está em `.specify/memory/constitution.md`. A emenda é a ADR-0006.


## Atualização de 23/10/2026 (rodada de pendências)

- Master Tempo e Key Shift por WSOLA (o deslocador antigo não segurava a afinação): `docs/testing/master-tempo.md`.
- Tempo por envelope multibanda: holdout Acc1 0,894 e Acc2 0,947 (antes 0,629 e 0,742); tom por classificador linear: holdout MIREX 0,589 e exato 0,496 (antes 0,472 e 0,374): `docs/testing/tempo-corpus.md`. Frases: sem ganho, sem verdade de terreno.
- Ícone adaptativo e assinatura de release (chave fora do git, `keystore.properties` ou variáveis `DECKBRIDGE_*`; sem chave o release sai sem assinatura). A chave de release de verdade ainda precisa ser criada pelo dono.
- Ganho de saída do app (−12 a +6 dB) na tela da mesa; lista do set gravado (.cue e .txt); marcas de frase na onda; BEAT JUMP com valor 16 e setas; balanço do roll.
- Set completo de seis faixas em vídeo (`docs/testing/session-video.md`).
- Nada disto foi ouvido no tablet nem na DDJ-400 (`BLOCKED_HARDWARE`).

## Atualização de 24/10/2026 (preparação para o hardware, só neste ambiente)

- Recursos do leitor e da mesa:
  - Touch Preview no fone, curvas dos faders, COMP por canal, isolador do master e Send/Return interno.
  - SHIMMER, DELAY e FX FREQUENCY. O tom do Spiral e do Helix agora segue o tempo.
  - Frases no estilo do rekordbox.
  - Ver `docs/runbooks/implementation-log.md`.
- DDJ-400 virtual numa janela separada (1280×760, a partir da foto do produto), com o log MIDI. Vídeos de mix e de RMX gerados com ela.
- Motor real testado no host (`tools/engine_host_test.cpp`):
  - Blocos de 64 a 4096 dão saída idêntica bit a bit.
  - De 1 a 8 canais e de 44,1 a 96 kHz.
  - Desconexão com nova tentativa.
  - Fuzz com NaN e infinito.
  - Quatro threads.
  - ASan, UBSan e TSan limpos.
  - O motor ganhou saneamento das entradas e um limitador no fone.
- Fuzz do caminho MIDI → DeckPage. Achou e corrigiu um crash no ajuste de loop e system common tratado como running status.
- Fuzz dos parsers da biblioteca: nenhuma exceção. A carga não muda nem cria arquivos num volume sem permissão de escrita (hash antes e depois). A exportação do histórico para o pendrive da biblioteca passou a ser recusada.
- Bring-up: checklist das 121 mensagens do mapa, lista do que chega fora do mapa e remapeamento aprendido na tela de diagnóstico. Passo 25 do roteiro.
- Suíte JVM: 141 testes, 0 falhas. Nada disto foi ouvido no tablet nem na DDJ-400 (`BLOCKED_HARDWARE`).

## Atualização de 25/10/2026 (Raspberry Pi, opção C)

- Decisões do dono e ADRs:
  - ADR-0007 aceita: o motor próprio num Pi Zero 2 W ou Pi 3, com Raspberry Pi OS 64 bit. O Mixxx (opção A) continua sendo a trilha normativa do alvo 2.
  - ADR-0008: tela nativa em SDL2, medida contra o Compose Desktop. A SDL2 usa de 9 a 18 vezes menos memória privada.
- `pi/`:
  - Motor do app sobre ALSA.
  - DDJ-400 por rawmidi: entrada e LEDs.
  - Leitor do pendrive em C++, só leitura.
  - WAV, MP3 e FLAC direto para int16.
  - Tela no layout do app em 1024×600 e 800×480.
  - Build aarch64 com testes sob qemu.
  - Ver `pi/README.md` e `docs/ui/pi-*.png`.
- Lógica única: o controle e os LEDs da DDJ-400 agora são uma implementação só, em C++, para o app e para o Pi. O motor já era um só.
- Duplicado de propósito: o leitor do pendrive (Kotlin no app, C++ no Pi). Os dois estão presos pelo `RekordboxGoldenTest`.
- Demonstração no PC (Xvfb): a tela do Pi mixou duas faixas por roteiro MIDI da DDJ-400, com Beat FX, loops, SYNC e RMX (cenas e X-PAD). Ver `pi/demo/README.md`.
- Library Manager de desktop (ADR-0009, OD-25): programa em `desktop/`, único que escreve no pendrive. Organiza a biblioteca à mão: metadados, playlists, cues e grade (ANLZ), importar áudio com análise, renomear arquivos por padrão, remover, compactar, Organizar por regras (sem IA), arrastar e soltar. Gravação com backup, execução com desfazer em ordem inversa, verificação e restauração (que nunca apaga áudio). Núcleo em `core/` (compartilhado com o app de celular): 83 testes, lido por quatro leitores independentes (Kotlin, C++ do Pi, Kaitai do banco e do ANLZ) e conferido contra exports reais do rekordbox (páginas das tabelas constantes idênticas byte a byte; biblioteca real de 3886 faixas editada e relida igual). Biblioteca local no computador (`~/.deckbridge/library`), exportar para pendrive e trazer de pendrive. **Nenhum player nem o rekordbox leu o que ele grava (`BLOCKED_HARDWARE`)**: teste numa cópia primeiro. Fora, de propósito: classificação/pontuação por modelo, etiquetas dentro do áudio. Ver `desktop/README.md`.
- App de celular da biblioteca (ADR-0010, `mobile/`, `os.deckbridge.manager`): biblioteca salva no aparelho, em pé e deitado (referência Galaxy S25 Ultra), importar e analisar, marcar (nota, cor, comentário), playlists, grade e cues, exportar para pendrive pelo seletor de documentos do Android e trazer de pendrive. Atalhos de uso repetido (filtros rápidos com contagem, busca com `bpm:`/`tom:`/`nota:`, playlist alvo de um toque, triagem, editar em lote, Arrumar tudo, analisar todas, refazer, pré-checagem de exportação): ver `mobile/README.md`; os principais também estão no desktop. 29 testes de JVM (capturas em 1440×3120 e 3120×1440; toques simulados em atalhos de uso repetido; exportação completa por um provedor de documentos de brinquedo). **Não rodou em aparelho**: decodificação de MP3/FLAC (MediaCodec), pendrive USB real no S25 Ultra e toque na tela são `BLOCKED_HARDWARE`. Ver `mobile/README.md`.
- Nada disto rodou num Pi, num tablet nem na DDJ-400 (`BLOCKED_HARDWARE`).
