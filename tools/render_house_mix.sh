#!/bin/sh
# Mix de avaliação dos efeitos. Sem argumento: duas faixas house sintéticas. Com um WAV 48 kHz 16 bit a 130 BPM
# (grade em 0,005 s) como $1: a faixa do usuário no deck A. Nada disso entra no git.
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
OUT="${OUT:-$ROOT/local-mixes}"
mkdir -p "$OUT"
cd "$ROOT"
g++ -std=c++17 -O2 -Wall -Wextra -I android/app/src/main/cpp -o "$OUT/house_mix" tools/house_mix.cpp
"$OUT/house_mix" "$OUT/mix.wav" "$OUT/roteiro.tsv" ${1:+"$1"}
ffmpeg -y -v error -i "$OUT/mix.wav" -af volume=-2.0dB -c:a libmp3lame -b:a 256k "$OUT/mix.mp3"
echo "mp3 $OUT/mix.mp3"
