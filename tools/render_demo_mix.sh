#!/bin/sh
# Mix de demonstração no motor do host. O MP3 não entra no git.
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
CORPUS="${CORPUS:-/opt/cursor/artifacts/real-corpus}"
OUT="${OUT:-/opt/cursor/artifacts/demo-mix.mp3}"
WORK="${WORK:-/tmp/demo-mix-work}"
mkdir -p "$WORK/voice" "$WORK/audio"
g++ -std=c++17 -O2 -Wall -Wextra -I "$ROOT/android/app/src/main/cpp" -o "$WORK/demo_mix" "$ROOT/tools/demo_mix.cpp"
ffmpeg -y -v error -i "$CORPUS/wrist-watch.flac" -ac 2 -ar 48000 -c:a pcm_s16le "$WORK/audio/wrist.wav"
ffmpeg -y -v error -i "$CORPUS/toodaze.mp3" -ac 2 -ar 48000 -c:a pcm_s16le "$WORK/audio/toodaze.wav"
# Frases curtas. espeak-ng pt-br; a música fica por cima.
phrases="
00|Sincronia
01|Troca de grave
02|Eco grave
03|Eco
04|Uma batida
05|Pingue-pongue
06|Espiral
07|Reverb
08|Trans
09|Flanger
10|Phaser
11|Pitch
12|Slip roll
13|Roll
14|Hélice
15|Móbius
16|Filtro
17|Freio
18|Color, filtro
19|Volta o grave
20|Cena, eco acima
21|Cena, ruído
22|Cena, espiral
23|Cena, reverb acima
24|Cena, reverb abaixo
25|Cena, eco abaixo
26|Cena, fita
27|Cena, dub eco
28|Pad, roll
29|Pad, trans
30|Pad, bateria
31|Pad, agudo
32|Corta o grave
33|Sobe o grave
34|Corta o médio
35|Sobe o médio
36|Corta o agudo
37|Sobe o agudo
38|Loop
39|Salto
40|Slip
"
af="silenceremove=start_periods=1:start_silence=0:start_threshold=-35dB,areverse,silenceremove=start_periods=1:start_silence=0:start_threshold=-35dB,areverse"
printf '%s\n' "$phrases" | while IFS='|' read -r id text; do
    [ -n "$id" ] || continue
    espeak-ng -v pt-br -s 165 -a 200 -w "$WORK/voice/raw.wav" "$text"
    ffmpeg -y -v error -i "$WORK/voice/raw.wav" -af "$af" -ac 1 -ar 48000 -c:a pcm_s16le "$WORK/voice/$id.wav"
done
"$WORK/demo_mix" "$WORK/audio/wrist.wav" "$WORK/audio/toodaze.wav" "$WORK/voice" "$WORK/mix.wav" "$WORK/cues.tsv"
# O PCM já passou pelo limiter (tecto 0,98). −3 dB deixa o true peak do MP3 abaixo de 0 dBFS.
ffmpeg -y -v error -i "$WORK/mix.wav" -af volume=-3.0dB -c:a libmp3lame -b:a 320k "$OUT"
cp "$WORK/cues.tsv" "$WORK/../demo-cues.tsv" 2>/dev/null || true
echo "mp3 $OUT"
echo "cues $WORK/cues.tsv"
