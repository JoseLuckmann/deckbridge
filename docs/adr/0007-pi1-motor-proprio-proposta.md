# ADR-0007 — Raspberry Pi 1 Model B com o motor próprio (proposta)

**Data:** 2026-10-24  
**Estado:** PROPOSTA. Depende de decisão do dono. Não muda a ordem do AGENTS.md enquanto não for aceita.

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

## Pergunta ao dono

1. Aceita abrir a opção B ou a C como trilha paralela ao alvo 2 do AGENTS.md? A opção A continua sendo a trilha normativa.
2. Se sim, o modo reduzido da B é aceitável (sem o set completo de FX e RMX)? Ou prefere a C (Pi Zero 2 W ou Pi 3) para manter os efeitos?

Até a resposta, nada de código específico do Pi entra no repositório.
