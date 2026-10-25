# DeckBridge no Raspberry Pi (ADR-0007, opção C)

Alvo: Pi Zero 2 W ou Pi 3 com Raspberry Pi OS 64 bit (aarch64). O motor é o mesmo `engine.cpp` do app Android, com
o Oboe trocado por ALSA (`pi/alsa/oboe/Oboe.h`). Fase 1: sem tela nem MIDI. Toca dois decks e mostra o estado.

## Compilar

```sh
pi/build.sh          # no próprio Pi (precisa de g++ e libasound2-dev) ou no PC
pi/build.sh arm64    # no PC Ubuntu: compilação cruzada (g++-aarch64-linux-gnu e libasound2-dev:arm64)
```

## Rodar

```sh
aplay -l                                   # descobrir o nome do cartão da DDJ-400
./deckbridge_pi --device hw:CARD=NOME --wav a.wav --wav b.wav --seconds 60
./deckbridge_pi --device null --fx         # sem placa: o PCM null anda no relógio, só para medir CPU
```

A cada segundo o programa imprime a posição dos decks, os x-runs do ALSA, as falhas de bloco do motor, a CPU do
processo e a RAM residente. Ele só lê os WAV que recebe. O pendrive do rekordbox continua só leitura (OD-23).

## Ajustes do sistema para o áudio

- Tempo real: `LimitRTPRIO` e `LimitMEMLOCK` no serviço (`pi/deckbridge.service`). Fora do systemd, o mesmo vai em `/etc/security/limits.d/`. Sem isso o programa avisa e toca sem SCHED_FIFO.
- `--period 256 --periods 3` é o padrão: 5,3 ms por bloco e ~16 ms de buffer a 48 kHz. Se houver x-runs, suba para 512 ou 4 períodos.
- Para medir no aparelho: o governor de CPU em `performance` e um núcleo reservado ao áudio (`isolcpus`). Isso ainda não foi medido (`BLOCKED_HARDWARE`).

## O que foi verificado sem o Pi (2026-10-25)

- x86 com o PCM `null`: dois decks tocando em tempo real por 4 s, 0 x-runs e 0 falhas de bloco. A CPU do processo ficou em 1,4 %, ou 2,5 % com SHIMMER ligado. RSS de 33 MB com duas faixas sintéticas de 25 s.
- Saída capturada pelo plugin `file` do ALSA: master nos canais 1–2 (RMS 0,27, pico 0,98), fone nos canais 3–4 em silêncio porque nenhum deck tinha CUE, 3,2 s contínuos.
- Build aarch64 (Cortex-A53) rodando sob `qemu-aarch64` com o ALSA do arm64: abre, toca em tempo real e tem 0 x-runs. A CPU e a RAM sob qemu não dizem nada sobre o Pi.
- Não verificado (`BLOCKED_HARDWARE`): Pi de verdade, DDJ-400 no USB do Pi, latência real, temperatura e consumo.
