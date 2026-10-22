#!/bin/sh
# Mix 0.14.1 do drum build, no motor do host. O MP3 não entra no git.
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
CORPUS="${CORPUS:-/opt/cursor/artifacts/real-corpus}"
OUT="${OUT:-/opt/cursor/artifacts/demo-mix-drums.mp3}"
WORK="${WORK:-/tmp/demo-mix-drums}"
mkdir -p "$WORK/voice" "$WORK/audio" "$(dirname "$OUT")"
cd "$ROOT"
g++ -std=c++17 -O2 -Wall -Wextra -I "$ROOT/android/app/src/main/cpp" -o "$WORK/demo_mix" "$ROOT/tools/demo_mix.cpp"
ffmpeg -y -v error -i "$CORPUS/wrist-watch.flac" -ac 2 -ar 48000 -c:a pcm_s16le "$WORK/audio/wrist.wav"
ffmpeg -y -v error -i "$CORPUS/toodaze.mp3" -ac 2 -ar 48000 -c:a pcm_s16le "$WORK/audio/toodaze.wav"
"$WORK/demo_mix" "$WORK/audio/wrist.wav" "$WORK/audio/toodaze.wav" "$WORK/voice" "$WORK/mix.wav" "$WORK/cues.tsv" drums
ffmpeg -y -v error -i "$WORK/mix.wav" -af volume=-4.5dB -c:a libmp3lame -b:a 320k "$OUT"
cp "$WORK/cues.tsv" /opt/cursor/artifacts/demo-mix-drums-cues.tsv
echo "mp3 $OUT"
echo "cues $WORK/cues.tsv"
