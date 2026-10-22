#!/bin/sh
# Mix musical 0.14.0 no motor do host. O MP3 não entra no git.
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
CORPUS="${CORPUS:-/opt/cursor/artifacts/real-corpus}"
OUT="${OUT:-/opt/cursor/artifacts/demo-mix-v2.mp3}"
WORK="${WORK:-/tmp/demo-mix-v2}"
mkdir -p "$WORK/voice" "$WORK/audio" "$(dirname "$OUT")"
g++ -std=c++17 -O2 -Wall -Wextra -I "$ROOT/android/app/src/main/cpp" -o "$WORK/demo_mix" "$ROOT/tools/demo_mix.cpp"
ffmpeg -y -v error -i "$CORPUS/wrist-watch.flac" -ac 2 -ar 48000 -c:a pcm_s16le "$WORK/audio/wrist.wav"
ffmpeg -y -v error -i "$CORPUS/toodaze.mp3" -ac 2 -ar 48000 -c:a pcm_s16le "$WORK/audio/toodaze.wav"
# Sem locução nas frases musicais. A folha de cues cobre o roteiro.
"$WORK/demo_mix" "$WORK/audio/wrist.wav" "$WORK/audio/toodaze.wav" "$WORK/voice" "$WORK/mix.wav" "$WORK/cues.tsv" v2
ffmpeg -y -v error -i "$WORK/mix.wav" -af volume=-4.5dB -c:a libmp3lame -b:a 320k "$OUT"
cp "$WORK/cues.tsv" /opt/cursor/artifacts/demo-mix-v2-cues.tsv
echo "mp3 $OUT"
echo "cues $WORK/cues.tsv"
