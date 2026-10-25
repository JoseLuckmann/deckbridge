# ADR-0007 — Raspberry Pi com o motor próprio

**Data:** 2026-10-24  
**Estado:** ACEITA em 2026-10-25, opção C (decisão do dono: "vamos de C"). Trilha paralela; a opção A (Mixxx upstream) continua sendo a trilha normativa do alvo 2 no AGENTS.md.

## Contexto

O dono quer, como sonho, o DeckBridge rodando num Raspberry Pi 1 Model B com tela LCD de 7". O AGENTS.md manda o alvo 2 (Raspberry Pi) começar por um vertical slice com o Mixxx upstream estável e o mapping DDJ-400 existente, e não com um motor próprio. Esta ADR registra o conflito e as opções. Nada aqui foi medido num Pi: os números de CPU do Pi são estimativas de `docs/performance/relatorio-cpu-ram.md`, a partir da contagem de instruções ARMv6 sob qemu.

O que limita o Pi 1 Model B:
- CPU: ARM1176 a 700 MHz, sem NEON. Estimativa de 280 a 490 M instruções/s neste tipo de código.
- RAM: 512 MB divididos com a GPU.
- USB: um barramento só, dividido entre o áudio da DDJ-400, o MIDI, o pendrive e a Ethernet.
- Sistema: não há Android. A interface em Compose não vai junto.

Estimativa, com as otimizações da rodada 1 já aplicadas:

| cena | fração de um Pi 1 |
|---|---|
| ociosa | 10–18 % |
| típica (2 decks, EQ, filtro, CUE) | 27–48 % |
| pior Beat FX | 138–241 % |
| pior RMX | 188–328 % |

Um grão do Master Tempo custaria 3–5 ms por deck num bloco de 5,3 ms.

## Opções

### A. Seguir o AGENTS.md: Mixxx upstream num Pi 4 ou Pi 3 (recomendado para o alvo 2)
- A favor: é a ordem normativa. O Mixxx já roda em Pi 4, e o mapping DDJ-400 existe.
- Contra: não é o Pi 1. Também não traz os efeitos e o RMX do app, só o que o Mixxx tem.

### B. Motor próprio num Pi 1, em modo reduzido
- O que roda: dois decks com EQ, filtro e poucos efeitos leves. Sem Master Tempo, ou com ele num deck só. Onda simples a 15 Hz.
- O que precisa ser feito:
  - Análise vinda do rekordbox (ANLZ), sem análise no aparelho.
  - PCM em cache local com `mmap`, nunca no pendrive.
  - Uma interface Linux nova (SDL2 ou LVGL no framebuffer) e um front-end ALSA no lugar do Oboe.
- A favor: é o sonho do dono. O motor C++ já é portável e está testado no host, e o código ARMv6 sai bit a bit igual ao x86 sob qemu.
- Contra:
  - O set completo de FX e RMX não cabe (estimativa).
  - A interface tem de ser reescrita.
  - O USB compartilhado é um risco sem medida.
  - Contraria o AGENTS.md até esta ADR ser aceita.

### C. Motor próprio num Pi Zero 2 W ou Pi 3
- O que muda: quatro núcleos A53 com NEON. O áudio fica num núcleo isolado e a análise noutro.
- A favor: muito mais folga que o Pi 1. O Zero 2 W mantém o tamanho pequeno.
- Contra: as mesmas pendências de interface e de ALSA que a B. O Zero 2 W também tem 512 MB, então o int16 e o `mmap` continuam necessários.

## O que já vale independente da decisão

As otimizações feitas para o tablet são o mesmo trabalho que qualquer opção com o motor próprio precisaria:
- FTZ no callback e nenhuma alocação nele.
- Locks curtos no áudio.
- PCM em int16, que reduz à metade a RAM de cada deck.
- Catálogo sem as ondas de detalhe.
- Fdn e WSOLA mais baratos.

Ver `docs/performance/README.md`.

## Decisão

Opção C: o motor próprio num Pi Zero 2 W ou num Pi 3, com Raspberry Pi OS 64 bit (aarch64, Cortex-A53). Isso segue a D-06. O Pi 1 Model B fica fora.

Plano em fases, cada uma com prova antes da seguinte:
1. **Motor no Linux** (feito em 2026-10-25, `pi/`):
   - `engine.cpp` sem mudança de lógica, sobre um `Oboe.h` feito em ALSA.
   - Thread SCHED_FIFO, conversão de formato e contagem de x-runs.
   - Desconexão no mesmo caminho do Oboe.
   - Build aarch64 por compilação cruzada.
2. **MIDI da DDJ-400 no Linux** (ALSA rawmidi) e **decisão da interface** numa ADR própria, com medida. As candidatas são Compose Multiplatform Desktop, reaproveitando a lógica Kotlin, e uma interface nativa.
3. **Imagem e serviço**:
   - systemd com rtprio e memlock.
   - Núcleo reservado ao áudio.
   - Governor de CPU em `performance`.
   - Pendrive montado só leitura, como a OD-23.
4. **HIL** num Pi de verdade: o roteiro do tablet adaptado. Até lá, `BLOCKED_HARDWARE`.
