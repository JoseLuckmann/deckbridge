# Mix de demonstração — drum build 0.14.1

Render no motor do host (`tools/demo_mix.cpp`, o mesmo `dsp.h` do APK, com os FLAC de `android/app/src/main/assets/drums/`). Não é o tablet e não é a DDJ-400. Ninguém ouviu este ficheiro aqui.

- Ficheiro: `/opt/cursor/artifacts/demo-mix-drums.mp3`
- Duração: 1:28,6 (88,583 s)
- 320 kbps, 3545325 bytes
- PCM pelo `PeakLimiter`, pico 0,980. O WAV de trabalho também fica nesse teto (PCM 16 bit)
- MP3 com −4,5 dB. True peak a 192 kHz: 0,883 (−1,08 dBTP), nenhuma amostra acima de 1,0
- NaN no render: 0
- Faixas: wrist watch (raymundo mendoza, CC BY 3.0) e toodaze (CC BY 3.0), as duas a 120,00 BPM na grelha do kick. A 128 o pitch do deck é 128/120. A grelha continua a 120; a fase acompanha a cabeça, que já anda mais depressa
- Sem locução. O roteiro é esta folha
- Licenças dos hits: `docs/licenses/drum-samples.md`. A palma é prévia MP3 do Freesound (CC0), não o WAV de 24 bit. Bumbo, caixa e chimbal são FLAC CC0 da Karoryfer

Os quatro primeiros segundos são a síntese da 0.14.0, de propósito: relógio solto, fora da grade, para comparar com os hits gravados que vêm a seguir. O roll sozinho de quatro compassos (0:07,3 e 0:47,3) é bumbo+palma, 1/4, 1/8, 1/16 e 1/32, um compasso de cada. A palma e a caixa sozinhas são dois compassos (1/4 e depois 1/8), para o timbre ficar óbvio. A caixa é mais curta que o bumbo; o RMS desse trecho fica mais baixo. O corpo dela levou um soft clip para sentar mais perto do bumbo.

Nos rolls com a faixa, o volume e o passa-altas sobem, a afinação sobe cerca de 10% no fim, a palma ou a caixa entra por cima nos compassos de 1/16 e 1/32, e o último meio tempo fica em silêncio antes do drop. O roll para no downbeat.

| Tempo | O que acontece |
| --- | --- |
| 0:00 | Síntese antiga, dois compassos, fora da grade. É o som da 0.14.0. |
| 0:04 | Bumbo gravado, sozinho. |
| 0:04,7 | Palma com cauda curta, sozinha. |
| 0:05,3 | Caixa, sozinha. |
| 0:06 | Chimbal fechado. |
| 0:06,7 | Chimbal aberto. |
| 0:07,3 | Roll sozinho, quatro compassos, 120 BPM, bumbo e palma. |
| 0:15,3 | Só a palma, dois compassos, 120. |
| 0:19,3 | Só a caixa, dois compassos, 120. |
| 0:23,3 | Na faixa, oito compassos, 120. Sobe, palma por cima, meio tempo de silêncio. |
| 0:39,3 | Drop a 120. O roll já parou no downbeat. |
| 0:47,3 | Roll sozinho, quatro compassos, 128. |
| 0:54,8 | Na faixa, oito compassos, 128, só o bumbo, sobe e cala meio tempo. |
| 1:09,8 | Drop a 128, até 1:28,6. |

O erro de onset medido no mesmo agendador está em `docs/research/plots/drum-onset.svg`. Pior caso nesta máquina: 0 ms, abaixo de 1 ms, a 120, 128, 140 e 174 BPM e com pitch ±8%.

Limites: host, não o tablet. A síntese antiga vive só neste render. A escuta continua `BLOCKED_HARDWARE`.
