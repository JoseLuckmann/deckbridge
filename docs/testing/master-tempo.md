# Master Tempo e Key Shift (23/10/2026)

Medido no host (`tools/mt_test.cpp`), não no tablet. Escuta: `BLOCKED_HARDWARE`.

## O que mudou

O Master Tempo antigo lia a faixa na velocidade do fader e corrigia o tom com um deslocador de dois atrasos (`PitchShift`). Num seno de 237 Hz (faixa a 1,0775x), a saída dele tinha cerca de 19,5 Hz em vez de 220 Hz. Ou seja, o Master Tempo e o Key Shift dos decks não seguravam a afinação.

Agora é WSOLA (`Wsola`, `dsp.h`), no mesmo ponto do motor:

- Janela de Hann de 2048 amostras, salto de 1024, busca de correlação de ±220 amostras (passo 4 e refino de ±3).
- Lê o grão direto da faixa: sem latência extra. O tom vem da taxa de leitura do grão (`rate`), o tempo vem do avanço da posição de análise (o que o motor já fazia).
- Funciona com loop, roll, reverso e Key Shift junto com o tempo. Salto vindo de fora (seek, hot cue) reinicia o grão, e o primeiro grão entra sem a subida de Hann para não amaciar o ataque.
- Sem Master Tempo e sem Key Shift, ou com Master Tempo a menos de 1% do tempo original, a leitura continua direta.

## Medidas

`./mt_test sintetico` (tom harmônico de 220 Hz, 10 harmônicos, envoltória lenta):

| caso | afinação alvo | afinação medida | pureza harmônica |
|---|---:|---:|---:|
| tempo 1,0775, Master Tempo | 220,00 Hz | 220,07 Hz | 1,000 |
| tempo 0,94 | 220,00 Hz | 219,96 Hz | 1,000 |
| tempo 1,20 | 220,00 Hz | 219,92 Hz | 1,000 |
| Key Shift +2 | 246,94 Hz | 246,88 Hz | 1,000 |
| Key Shift −5 | 164,81 Hz | 164,75 Hz | 1,000 |
| tempo 1,06 e Key Shift +3 | 261,63 Hz | 261,58 Hz | 1,000 |

Custo: 12 s de áudio em 0,09 s no host (cerca de 0,8% de um núcleo por deck, no pior caso de tempo e Key Shift juntos). O tablet é outra medida.

Faixa real (faixa A, 20 s a partir de 30 s, tempo 1,0775), distância espectral log (log10, 0 a 400 bins) contra o Rubber Band do ffmpeg com o mesmo tempo e tom preservado:

| | distância | fator de crista (referência 3,06) |
|---|---:|---:|
| antigo | 1,155 | 2,73 |
| WSOLA | 0,340 | 2,86 |

Limites: o Rubber Band é a referência de qualidade, não a verdade perceptual; o fator de crista mostra que o WSOLA suaviza um pouco os transientes. Em faixas muito esticadas (±16% e mais) a emenda por correlação pode soar "aquosa" em material polifônico; só a escuta no hardware confirma. Reproduzir: `g++ -std=c++17 -O2 -I android/app/src/main/cpp -o mt_test tools/mt_test.cpp && ./mt_test sintetico`.
