# Mix de avaliação dos efeitos — 0.15.0 (host)

Render no motor do host (`tools/house_mix.cpp`, o mesmo `dsp.h` do APK). Não é o tablet e não é a DDJ-400. Ninguém ouviu estes ficheiros aqui. A escuta continua `BLOCKED_HARDWARE`.

Reproduzir: `tools/render_house_mix.sh [faixa.wav]`. Sem argumento, duas faixas house sintetizadas no próprio código (sem material de terceiros). Com uma faixa do usuário (WAV 48 kHz, 130 BPM, grade em 0,005 s), ela entra no deck A com −6 dB de trim. A faixa e o render ficam em `local-mixes/`, ignorado pelo git.

Roteiro com a faixa do usuário (tempos do mix; compassos da faixa entre parênteses):

| Tempo | O que acontece |
| --- | --- |
| 0:03–0:14 | Color FX, um tipo por compasso: SPACE, DUB ECHO, SWEEP, NOISE, CRUSH, FILTER. |
| 0:14–0:25 | Beat FX: eco, spiral, reverb, pitch, filtro rítmico. |
| 0:31 | Build 1: ruído que abre + bumbo subindo, 4 compassos. Drop em 0:38 (compasso 37). |
| 0:59 | Build 2: Echo UP + bumbo e palma, 4 compassos. Drop em 1:06 (52), cauda do eco. |
| 1:21 | Build 3: Spiral UP + bumbo, 8 compassos. Drop em 1:36 (108), cauda do spiral. |
| 1:50 | Build 4: Reverb UP + bumbo e palma, 8 compassos. Drop em 2:05 (124), cauda da reverb. |
| 2:20 | Saída: o filtro fecha. |

Medidas objetivas (`tools/fx_rmx_test.cpp`, 0 falhas; `tools/spectro.py` para o espectrograma):

- Bumbo: taxa de leitura de 1,000 no primeiro golpe a 1,569 no último (+7,8 semitons com PITCH), sem nunca descer.
- Spiral UP: o pico de um toque de 440 Hz vai de 542 Hz a 721 Hz entre repetições. Beat FX Spiral idem. Pitch a +1 oitava mediu 861 Hz (alvo 880).
- Respiro antes do drop: −23,6 dB contra −15,2 dB no meio da subida. Impacto no drop: −15,7 dB.
- Soltura do RMX cai na batida da grade (erro < 0,2% de batida com fase deslocada de 0,37). Antes caía no relógio interno da cena.
- Cauda no drop: eco e reverb continuam e somem por si em 8 batidas. No render com a faixa, o nível 1/4 de compasso depois do drop 4 ficou −14,8 dB (antes −13,8); o motor antigo caía de −12,6 para −19,5 dB.
- Helix travado: −0,1 dB por batida depois de travar (antes −2,3 dB por batida).
- Color FX: o centro deixa o som original (diferença RMS < 1e-4) nos seis tipos. DUB ECHO e SPACE deixam cauda ao voltar ao centro e depois somem.

Limites: sem escuta. As curvas dos seis Color FX são nossas; os manuais não as publicam. O deslocador de grão (`GrainShift`, janela de 2048) tem artefatos em material tonal denso; serve a repetições de eco, não a um Pitch limpo.

## Arquivo no repositório

`docs/testing/audio/mix-house-sintetica.mp3` (192 kbps, 3:10) é a mix das duas faixas sintetizadas no código, sem material de terceiros, por isso pode ficar no git. O roteiro é `mix-house-sintetica.tsv` (segundos, compasso, evento). Faixas públicas do corpus CC BY 3.0 (`tools/fetch_real_corpus.py`) não puderam ser baixadas nesta sessão: o proxy do ambiente bloqueou archive.org (403).
