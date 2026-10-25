# Mixagem de demonstração na tela do Pi

Grava a tela nativa (`deckbridge_ui`) e o áudio do motor com duas faixas do pendrive. A sequência tem:
- Beat FX Echo e um loop de 4 batidas com roll.
- BROWSE/LOAD, SYNC, entrada no fader e troca de grave.
- RMX: Echo UP e Noise UP com X-PAD DRUM.
- Filtro e Spiral no master.

Cada gesto entra como os bytes MIDI da DDJ-400, pelo mesmo caminho do rawmidi (`onMessage`). Os bytes vêm do mapa oficial, pelo nome do controle.

## Roteiro

Gerar com `python3 pi/demo/mix_demo.py android/app/src/main/resources/ddj400-map.json > roteiro.txt`.

Operações do `--script`:
- `at SEG BYTES`: tempo de relógio desde o início.
- `bar D COMPASSO BYTES`: compasso da grade do deck D.
- `ramp D DE ATÉ STATUS DATA1 V0 V1`: rampa de CC numa thread própria.
- `panel D COMPASSO 0|1`: abre ou fecha o painel RMX.
- `end D COMPASSO`.

Os compassos contam tempo tocado, não posição. Uma volta de loop ou um beat jump para trás soma tempo na velocidade de antes. Sem isso, um `bar 1 11` depois de um loop no compasso 9 esperaria para sempre.

## Gravar (PC, Xvfb)

```sh
sh pi/build.sh
mkfifo quadros.fifo
ffmpeg -f rawvideo -pix_fmt bgra -s 1024x600 -r 30 -i quadros.fifo -c:v libx264 -preset ultrafast video.mp4 &
DECKBRIDGE_ALSA_DEVICE="file:FILE=mix.raw,FORMAT=raw" xvfb-run -a -s "-screen 0 1024x600x24" \
  pi/build/deckbridge_ui --usb PENDRIVE --track 1 --map pi/build/ddj400-map.json --script roteiro.txt --record quadros.fifo
# O log diz quanto o 1º quadro sai depois de abrir o áudio (OFS); o áudio é f32le, 4 canais, 48 kHz (1–2 = master).
ffmpeg -i video.mp4 -ss OFS -f f32le -ar 48000 -ac 4 -i mix.raw \
  -filter_complex "[1:a]pan=stereo|c0=c0|c1=c1[a]" -map 0:v -map "[a]" -c:v libx264 -c:a aac -shortest mix.mp4
```

O dispositivo `file` do ALSA anda no relógio de parede, como uma placa real. Os quadros também saem a 30 qps de relógio; se a tela atrasa, o quadro se repete. Por isso vídeo e áudio têm a mesma duração.

## Pendrive

O pendrive da gravação é uma exportação no formato rekordbox feita pelo `PiUsbExportTest`, que só roda com `PI_USB=1`, a partir das faixas da sessão local. As faixas e o vídeo ficam fora do repositório. A pasta de teste `pi/library/testdata/usb` serve para os testes, mas não tem áudio.
