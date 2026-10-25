#!/bin/sh
# Compila o DeckBridge para Linux. Sem argumento: compilação nativa (no próprio Pi ou no PC).
# "arm64": compilação cruzada para Raspberry Pi OS 64 bit (Pi Zero 2 W e Pi 3, Cortex-A53).
# Ferramentas usadas em 2026-10-25 (Ubuntu 24.04, pacotes assinados do apt; nada de curl|sh):
#   g++-aarch64-linux-gnu 13.3.0, libasound2-dev(:arm64) 1.2.11-1ubuntu0.3, qemu-user 8.2.2 (só para testar no PC).
set -eu
cd "$(dirname "$0")/.."
OUT=${OUT:-pi/build}
mkdir -p "$OUT"
FLAGS="-std=c++17 -O2 -fwrapv -pthread -Wall -I pi/alsa -I android/app/src/main/cpp"
if [ "${1:-}" = "arm64" ]; then
    # Cabeçalhos do ALSA são os mesmos nas duas arquiteturas; só eles entram, não o resto do /usr/include do PC.
    mkdir -p "$OUT/inc"
    ln -sfn /usr/include/alsa "$OUT/inc/alsa"
    aarch64-linux-gnu-g++ $FLAGS -mcpu=cortex-a53 -I "$OUT/inc" -static-libstdc++ -static-libgcc \
        -o "$OUT/deckbridge_pi.arm64" pi/deckbridge_pi.cpp \
        -L/usr/lib/aarch64-linux-gnu -Wl,-rpath-link,/usr/lib/aarch64-linux-gnu -lasound
    echo "$OUT/deckbridge_pi.arm64"
    for t in control/controller_test control/leds_test library/rekordbox_test; do g++ -std=c++17 -O2 -o "$OUT/$(basename $t)" pi/$t.cpp && "$OUT/$(basename $t)" .; done
else
    g++ $FLAGS -o "$OUT/deckbridge_pi" pi/deckbridge_pi.cpp -lasound
    echo "$OUT/deckbridge_pi"
    for t in control/controller_test control/leds_test library/rekordbox_test; do g++ -std=c++17 -O2 -o "$OUT/$(basename $t)" pi/$t.cpp && "$OUT/$(basename $t)" .; done
fi
# O mapa da DDJ-400 vai junto do binário (--map, padrão ./ddj400-map.json).
cp android/app/src/main/resources/ddj400-map.json "$OUT/"
