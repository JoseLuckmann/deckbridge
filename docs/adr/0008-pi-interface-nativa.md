# ADR-0008 — Interface do Pi: nativa (C++ e SDL2), não Compose Desktop

**Data:** 2026-10-25  
**Estado:** aceita para a trilha do Pi (ADR-0007, opção C). Não muda o app Android.

## Contexto

No Pi (Zero 2 W com 512 MB, Pi 3 com 1 GB, ambos Cortex-A53) a interface divide a máquina com o motor. O motor com duas faixas de 6 min em int16 ocupa 133 MB de RSS (medido, `docs/performance/README.md`). As candidatas eram duas:

1. Compose Multiplatform Desktop na JVM: reaproveita a lógica Kotlin do app (`logic/`).
2. Interface nativa em C++ com SDL2: no Pi, sobre KMSDRM, sem X11. Precisa portar para C++ a parte da lógica que a interface usa.

## Medida

Mesma cena nos dois protótipos: 1024×600, duas ondas com uma coluna por pixel, grade de batidas e texto, redesenhadas a 30 qps por 20 s. Rodaram no Xvfb deste PC x86 (Xeon), com renderização por software. As medidas são o RSS, a memória privada (`Private_Clean + Private_Dirty`) e a CPU média do processo.

| interface | RSS | privada | CPU |
|---|---|---|---|
| SDL2 software | 15 MB | 7 MB | 4,0 % de um núcleo |
| Compose Desktop, JVM padrão (o GL falhou e caiu para software) | 243 MB | 180 MB | 39,0 % |
| Compose Desktop, JVM ajustada (`-Xmx96m`, SerialGC, só C1, metaspace 96 MB) | 151 MB | 128 MB | 29,4 % |

Ferramentas: SDL2 2.30.0, SDL2_ttf 2.22.0, Compose Multiplatform 1.7.3, Kotlin 2.0.21, OpenJDK 21. Os protótipos ficaram fora do repositório, porque só servem para esta medida.

Estimativa (não é medida): um núcleo A53 do Pi deve ser de 5 a 10 vezes mais lento que este Xeon neste tipo de código. Com isso:
- Compose ajustado: 150–300 % de um núcleo só para a interface.
- SDL2: 20–40 % de um núcleo.

RAM, contas a partir do medido:
- Zero 2 W: 512 MB menos ~64 MB de GPU e ~100 MB de sistema deixam ~350 MB.
- Motor com 2 faixas: 133 MB.
- Compose ajustado: mais 128–151 MB, quase sem folga para o GC e o catálogo.
- SDL2: mais 7–15 MB.

## Decisão

No Pi, a interface é nativa em C++ com SDL2 (KMSDRM no aparelho, X11 ou Xvfb no PC para teste). A lógica que ela precisa é portada para C++:
- o mapa MIDI da DDJ-400;
- o montador de MIDI;
- as ações de performance;
- o estado dos decks.

A porta é verificada contra o Kotlin com vetores de referência. Os mesmos bytes MIDI entram nas duas versões e as ações que saem têm de ser iguais. A interface do Android continua em Compose.

## Alternativas descartadas

- **Compose Desktop no Pi:** custa 9 a 18 vezes mais memória privada e 7 vezes mais CPU no x86. Caberia no Pi 3 com 1 GB, mas a uma taxa de quadros menor, e não cabe com folga no Zero 2 W.
- **Kotlin/Native para a lógica, com a interface em C:** evita a porta, mas põe outro runtime (GC próprio) no processo do áudio e outra cadeia de build. Fica para reavaliar se a porta crescer demais.

## Consequências

- Duas implementações da lógica de controle, Kotlin e C++. Os vetores de referência gerados do Kotlin entram como teste das duas, para não divergirem.
- O motor e a interface ficam no mesmo processo. A interface nunca segura o lock do áudio por mais que um setter.
