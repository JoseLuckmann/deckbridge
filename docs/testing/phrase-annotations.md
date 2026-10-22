# Frases no corpus real — 0.13.0

Medido em 22/10/2026 no JVM deste host. Não é o tablet. Não é a DDJ-400. Ninguém ouviu estas faixas aqui. A escuta continua `BLOCKED_HARDWARE`.

O tempo e o tom são os do analisador 0.12.2, sem alteração do pente. Acc1 0,655, Acc2 0,724 e MIREX 0,476 em 58 faixas do GiantSteps continuam abaixo de 0,85 / 0,95 / 0,70. A grelha desta página é essa: compasso = 240 / BPM. Chromosome Boutique fica em 154,2. Caught In The Beat fica em 160,0.

Os nove WAV mono estão em `/opt/cursor/artifacts/real-corpus/mono`. Não entram no git. O carimbo `analise 0.12.1 rgb` do volume simulado não foi reescrito.

## Como as fronteiras foram marcadas

A energia grave e a aguda são o envelope do app (pólo simples a 180 Hz, janelas de 2 s). Cada janela é repartida pelos compassos que cobre, para um compasso mais curto que 2 s não ficar com energia zero. Isso é a mesma curva que o detector vê.

A marcação foi feita a ler essa curva, primeiro em grupos de 8 compassos e depois no compasso do degrau. Não houve colunas. A regra, fechada antes de contar os acertos:

- Uma fronteira é o primeiro compasso de um degrau que se mantém pelo menos quatro compassos.
- O drop é a chegada ao patamar alto. Se o ficheiro já abre nesse patamar, o drop é 0.
- O build é um degrau anterior, que sai da abertura baixa e ainda não é o patamar. Se não há degrau separado, não se marca build.
- O break é o primeiro compasso de uma descida para cerca de metade, que dura vários compassos e depois volta. Um buraco de um compasso não é break. Um segundo break, quando existe, entra na lista. O detector só emite um.
- O outro é a descida que não regressa ao patamar. Quando a música segue alta e só os últimos compassos são silêncio de ficheiro, o outro marcado é essa cauda, e a tabela diz isso.

Depois desta lista fechada, o detector foi ajustado uma vez, de forma geral: sem compassos vazios, drop em 0 quando a abertura já é alta, o break procura a descida (não o regresso) e o outro é a última descida que se mantém ou um regresso mais baixo. As marcas não foram movidas para ir ao encontro desse ajuste.

Um acerto é distância de no máximo 1 compasso dessa faixa. A tolerância de 4 compassos é metade de uma célula de 8, que é o passo para onde a fronteira é atraída.

## Resultado

16 de 26 fronteiras caem a 1 compasso (0,615). 20 de 26 caem a 4 compassos (0,769). O teste `PhraseHitRateTest` falha se a contagem descer abaixo destes dois pisos.

| Faixa | Marca | Anotado (s) | Detector (s) | Compassos | |
|---|---|---:|---:|---:|---|
| Chromosome Boutique | outro | 175,87 | 174,32 | 1,00 | acerto |
| Ecosystem | build | 53,12 | 47,21 | 3,00 | a 4 |
| Ecosystem | drop | 110,16 | 62,95 | 24,00 | falha |
| Ecosystem | break | 175,08 | 204,59 | 15,00 | falha |
| Ecosystem | outro | 182,95 | 216,39 | 17,00 | falha |
| Silver | drop | 0,00 | 0,00 | 0,00 | acerto |
| Silver | outro | 205,96 | 217,87 | 7,00 | falha |
| At The Count | drop | 12,63 | 12,63 | 0,00 | acerto |
| At The Count | break | 151,58 | 164,21 | 8,00 | falha |
| At The Count | outro | 227,37 | 227,37 | 0,00 | acerto |
| Caught In The Beat | build | 16,50 | 12,00 | 3,00 | a 4 |
| Caught In The Beat | drop | 24,00 | 24,00 | 0,00 | acerto |
| Caught In The Beat | break | 66,00 | 72,00 | 4,00 | a 4 |
| Caught In The Beat | break | 132,00 | 72,00 | 40,00 | falha |
| Caught In The Beat | outro | 246,00 | 252,00 | 4,00 | a 4 |
| Morning | build | 16,27 | 16,27 | 0,00 | acerto |
| Morning | drop | 32,54 | 32,54 | 0,00 | acerto |
| Morning | break | 162,71 | 162,71 | 0,00 | acerto |
| Morning | outro | 357,97 | 357,97 | 0,00 | acerto, cauda do ficheiro |
| Ocean | drop | 0,00 | 0,00 | 0,00 | acerto |
| Ocean | outro | 357,96 | 357,96 | 0,00 | acerto, cauda do ficheiro |
| toodaze | drop | 0,00 | 0,00 | 0,00 | acerto |
| toodaze | break | 240,00 | 240,00 | 0,00 | acerto |
| toodaze | outro | 320,00 | 320,00 | 0,00 | acerto, cauda do ficheiro |
| wrist watch | drop | 32,00 | 32,00 | 0,00 | acerto |
| wrist watch | outro | 304,00 | 304,00 | 0,00 | acerto, cauda do ficheiro |

O que isto não fecha:

- Ecosystem: a marca põe o drop no patamar mais alto (110 s). O detector agarra a primeira subida moderada (63 s) e empurra o break e o outro para o fim.
- Silver: a descida anotada aos 206 s sai no detector aos 218 s.
- At The Count: o break anotado aos 152 s sai aos 164 s. A descida é irregular e o encaixe de 8 compassos cai do lado do regresso.
- Caught In The Beat: o encaixe de 8 compassos desloca build, break e outro 3 ou 4 compassos. O segundo break, aos 132 s, não tem lugar no detector, que só guarda um.
- Morning, Ocean, wrist watch e toodaze: o outro que acerta é o silêncio do fim do ficheiro, com a música ainda alta no compasso anterior. Não é um outro ouvido.
- Chromosome Boutique não tem drop nem break nítidos nesta curva. Só o outro foi marcado.
- Ocean não tem break: a descida do meio fica perto de 18% e não foi marcada.
- Um seno de 1 kHz a −20 dBFS (amplitude 0,1) mede cerca de −23,7 LUFS, com tolerância de 2 LU no teste. O trim é (alvo − LUFS), preso a ±12 dB. O alvo de fábrica é −10 LUFS. Não se leu auto gain do export: a linha de conteúdo que este parser conhece não tem um campo publicado para isso. O limiter do master segura o pico em 0,98. Um par já baixo passa intacto.
- Os cues automáticos (A no primeiro downbeat, B no drop, C no break, D no outro; o preset INTRO troca a ordem) ficam na análise do app. O USB não é escrito.
