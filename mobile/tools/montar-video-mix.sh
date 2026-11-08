#!/bin/sh
# Junta os quadros (15 fps) com o áudio da prévia: o áudio começa quando a cabeça começa a andar (quadro "intro").
# Uso: montar-video-mix.sh <pasta passada em -PmixVideo>
S=${1:?pasta}
intro=$(awk -F'\t' '$1=="quadros"{print $7}' $S/out/info.txt)
start=$(awk -F'\t' '$1=="audio"{print $4}' $S/out/info.txt)
delay=$((intro * 66))
ffmpeg -v error -y -framerate 1000/66 -i $S/out/frames/%05d.png -ss $(echo "scale=3; $start/1000" | bc) -i $S/out/mix.wav \
  -filter_complex "[0:v]scale=720:-2,format=yuv420p[v];[1:a]adelay=${delay}|${delay}[a]" -map "[v]" -map "[a]" -shortest \
  -c:v libx264 -crf 20 -preset medium -r 30 -c:a aac -b:a 192k -movflags +faststart $S/teste-de-mix.mp4
