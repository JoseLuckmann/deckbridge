# Mix de demonstração dos efeitos

Ficheiro: `/opt/cursor/artifacts/demo-mix.mp3`

- Duração: **3:42** (222,0 s de programa, 222,024 s no contentor)
- MP3 CBR **320 kbps**, 8 881 965 bytes
- Forma de onda: `/opt/cursor/artifacts/demo-mix-wave.png`

Isto é o motor do **host** (`dsp.h` e a mesma ordem de `Deck::read` / `onAudioReady` de `engine.cpp`), compilado com `g++` neste computador. Não passou pelo tablet, nem pela DDJ-400, nem pelo Oboe. Ninguém ouviu este ficheiro aqui. A escuta continua `BLOCKED_HARDWARE`.

O PCM sai do `PeakLimiter` com tecto 0,98. O pico medido nesse PCM é **0,980**. O MP3 leva mais −3 dB, porque o codificador cria picos entre amostras. True peak do MP3, medido a 192 kHz (4×): **0,847 (−1,44 dBTP)**. Nenhuma amostra dessa leitura passa de 1,0. Não há NaN no PCM.

## O que toca

Duas faixas CC BY 3.0, as duas a uma grelha de kick de 120,00 (é a grelha que trava o grave; o analisador do app lia 120,28 e 120,24). O pitch de sync fica em 1,000. As duas correm desde o início; o crossfader é o linear do motor.

| Deck | Faixa | Artista | Entrada |
|---|---|---|---|
| A | wrist watch | raymundo mendoza | 32,03 s, o drop |
| B | toodaze | raymundo mendoza | 0,03 s, o primeiro kick |

A voz é `espeak-ng` pt-br, frases curtas, pico 0,20 por cima da música a 70%. Não é uma locução de estúdio. Dura no máximo 1,5 s e cabe no começo de cada bloco.

O knob FILTER é o único Sound Color FX deste mapa. Não há seletor de ruído, pitch ou dub eco nesse knob. O pitch ouve-se no Beat FX. O ruído e o dub eco ouvem-se no RMX, que é onde o código os tem.

Cada Beat FX (excepto o freio) fica 4 s ligado e 2 s seco. O eco mostra a troca de valor de batida: meio tempo (0,25 s) e depois uma batida (0,50 s). A autocorrelação desses dois blocos cai em 0,250 s e 0,500 s. No seco a seguir, o atraso de 0,25 s desaparece.

## Roteiro

| Tempo | Secção | O que a voz diz |
|---|---|---|
| 00:00 | Dois decks em sincronia, só o A | Sincronia |
| 00:06 | Troca para o B: sync, grave do A para o B, filtro do B a abrir | Troca de grave |
| 00:14 | Beat FX, eco com corte | Eco grave |
| 00:20 | Beat FX, eco em 1/2 | Eco |
| 00:26 | Beat FX, eco em 1/1 | Uma batida |
| 00:32 | Beat FX, pingue-pongue | Pingue-pongue |
| 00:38 | Beat FX, espiral | Espiral |
| 00:44 | Beat FX, reverb | Reverb |
| 00:50 | Beat FX, trans | Trans |
| 00:56 | Beat FX, flanger | Flanger |
| 01:02 | Beat FX, phaser | Phaser |
| 01:08 | Beat FX, pitch | Pitch |
| 01:14 | Beat FX, slip roll em 1/2 | Slip roll |
| 01:20 | Beat FX, roll em 1/4 | Roll |
| 01:26 | Beat FX, hélice | Hélice |
| 01:32 | Beat FX, móbius | Móbius |
| 01:38 | Beat FX, filtro (grave, depois agudo) | Filtro |
| 01:44 | Beat FX, freio de vinil (2 s). No seco, a cabeça volta à grelha | Freio |
| 01:48 | Color FX, o knob de filtro: fecha o grave, volta ao centro, abre o agudo | Color, filtro |
| 01:56 | Volta ao A: sync, grave e filtro no sentido contrário | Volta o grave |
| 02:04 | RMX Scene FX, Echo UP. Sobe num compasso e solta no seguinte | Cena, eco acima |
| 02:08 | RMX Scene FX, Noise UP | Cena, ruído |
| 02:12 | RMX Scene FX, Spiral UP | Cena, espiral |
| 02:16 | RMX Scene FX, Reverb UP | Cena, reverb acima |
| 02:20 | RMX Scene FX, Reverb DOWN | Cena, reverb abaixo |
| 02:24 | RMX Scene FX, Echo DOWN | Cena, eco abaixo |
| 02:28 | RMX Scene FX, Tape STOP. A cena seguinte reentra na grelha | Cena, fita |
| 02:32 | RMX Scene FX, Dub ECHO | Cena, dub eco |
| 02:36 | RMX X-Pad, roll em 1/4 | Pad, roll |
| 02:42 | RMX X-Pad, trans | Pad, trans |
| 02:48 | RMX X-Pad, bateria | Pad, bateria |
| 02:54 | RMX X-Pad, agudo | Pad, agudo |
| 03:00 | Isolador, grave em kill | Corta o grave |
| 03:04 | Isolador, grave em +6 dB | Sobe o grave |
| 03:08 | Isolador, médio em kill | Corta o médio |
| 03:12 | Isolador, médio em +6 dB | Sobe o médio |
| 03:16 | Isolador, agudo em kill | Corta o agudo |
| 03:20 | Isolador, agudo em +6 dB | Sobe o agudo |
| 03:24 | Loop de 4 batidas, duas voltas | Loop |
| 03:30 | Salto de +8 batidas | Salto |
| 03:32 | Salto de −8, de volta | — |
| 03:34 | Slip: a cabeça recua 4 batidas e o ponto escondido continua | Slip |
| 03:36 | Slip solta: a cabeça salta para o ponto escondido | — |
| 03:38 | Seco, até 03:42 | — |

## Limites

- Spiral, hélice, móbius, pitch, reverb e as cenas RMX são as aproximações que já estão no app, não o algoritmo de um aparelho comercial.
- O X-Pad roll não guarda o ponto escondido; o slip roll do Beat FX guarda. O slip de 03:34 é o slip do deck.
- O freio e a fita atrasam a cabeça. O bloco seguinte faz um salto seco para a grelha de 120. Ouve-se o salto.
- O eco com corte e o pad de agudo ficam finos: o RMS desses blocos é baixo porque o filtro tira o grave. Não são silêncio.
- O isolador de médio e de agudo mexe pouco no nível, porque estas duas faixas têm o grave como a maior parte da energia. O kill e o boost do grave ouvem-se melhor.
- São 3:42, não 3:00. Cada efeito precisava de uns compassos e de um seco antes do seguinte.
- Para repetir: `tools/render_demo_mix.sh`. O áudio de saída fica fora do git.
