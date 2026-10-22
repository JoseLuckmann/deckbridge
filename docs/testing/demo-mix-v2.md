# Mix de demonstração v2

Render no motor do host (`tools/demo_mix.cpp`, o mesmo `dsp.h` do APK). Não é o tablet e não é a DDJ-400. Ninguém ouviu este ficheiro aqui.

- Ficheiro: `/opt/cursor/artifacts/demo-mix-v2.mp3`
- Duração: 3:20 (200,000 s)
- 320 kbps, 8002605 bytes
- PCM pelo `PeakLimiter`, pico 0,981
- MP3 com −4,5 dB. True peak a 192 kHz: 0,874 (−1,17 dBTP), nenhuma amostra acima de 1,0
- Faixas: wrist watch (raymundo mendoza, CC BY 3.0) e toodaze (CC BY 3.0), as duas a 120,00 BPM na grelha do kick
- Sem locução. O roteiro é esta folha.

Profundidade na casa dos 40–70%, knobs ao longo de 2–8 compassos. A comparação aos 1:52 é a única exceção: quatro compassos na curva de 0.13.1 (filtro fechado de repente e eco quase todo molhado, sem escurecer) e quatro na curva nova.

| Tempo | O que acontece |
| --- | --- |
| 0:00 | Deck A. O filtro abre de um passa-baixas leve até ao centro, em oito compassos. |
| 0:16 | Deck B entra em oito compassos. O grave troca sem kill seco. O filtro do B sobe. |
| 0:32 | Eco no B, cerca de 55%. O tempo desliza de 1/2 para 1 tempo ao longo de oito compassos. |
| 0:48 | O eco desliga. A cauda fica e o seco volta. |
| 0:56 | Flanger, a profundidade sobe até cerca de 60%. |
| 1:04 | Phaser em 1/2, à volta de 55%. |
| 1:12 | Reverb à volta de 45%, a abrir. |
| 1:20 | Slip roll de 1 tempo, dois compassos. |
| 1:24 | O slip solta e a faixa continua. |
| 1:28 | Roll de 1/2 tempo, dois compassos, com crossfade na emenda. |
| 1:32 | O roll solta. |
| 1:36 | Filtro do B nos dois sentidos, oito compassos: fecha o grave e depois abre o agudo, sem ir ao extremo. |
| 1:52 | Comparação. Até 2:00, a curva antiga, fundo e brilhante. De 2:00 a 2:08, filtro e eco novos, moderados. |
| 2:08 | Scene FX Echo UP durante oito compassos: o passa-altas sobe, o eco realimenta, entra um ruído baixo. |
| 2:24 | Solta no downbeat. A cena cai em cerca de 18 ms e o drop fica limpo. |
| 2:28 | Drum build. Roll sintetizado, 1/4 → 1/8 → 1/16 → 1/32 de batida, pitch e volume a subir, oito compassos. |
| 2:44 | O roll para neste downbeat. |
| 2:48 | Volta ao deck A com eco a cerca de 60%. |
| 2:56 | O eco desliga no meio da volta. A cauda atravessa a transição. |
| 3:04 | Isolador: o grave sobe cerca de 3 dB e volta, sem kill. |
| 3:12 | Seco, até 3:20. |

Créditos: wrist watch, raymundo mendoza, CC BY 3.0. toodaze, CC BY 3.0. Os ataques de bateria são gerados no código, não são samples de terceiros.

Limites: host, não o tablet. A curva antiga da comparação vive só neste render. O que ainda não bate com a Pioneer está em `docs/research/fx-de-para.md`.
