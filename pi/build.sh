#!/bin/sh
# Compila o DeckBridge para Linux. Sem argumento: compilação nativa (no próprio Pi ou no PC).
# "arm64": compilação cruzada para Raspberry Pi OS 64 bit (Pi Zero 2 W e Pi 3, Cortex-A53).
# Ferramentas usadas em 2026-10-25 (Ubuntu 24.04, pacotes assinados do apt; nada de curl|sh):
#   g++-aarch64-linux-gnu 13.3.0, libasound2-dev(:arm64) 1.2.11-1ubuntu0.3, libsdl2-dev(:arm64) 2.30.0,
#   libsdl2-ttf-dev(:arm64) 2.22.0, qemu-user 8.2.2 (só para testar no PC). No Pi: g++, libasound2-dev, libsdl2-dev,
#   libsdl2-ttf-dev e fonts-dejavu-core.
set -eu
cd "$(dirname "$0")/.."
OUT=${OUT:-pi/build}
mkdir -p "$OUT"
FLAGS="-std=c++17 -O2 -fwrapv -pthread -Wall -I pi/alsa -I android/app/src/main/cpp"
if [ "${1:-}" = "arm64" ]; then
    # Cabeçalhos do ALSA são os mesmos nas duas arquiteturas; só eles entram, não o resto do /usr/include do PC.
    mkdir -p "$OUT/inc"
    ln -sfn /usr/include/alsa "$OUT/inc/alsa"
    for prog in deckbridge_pi ui/deckbridge_ui; do
        libs="-lasound"
        [ "$prog" = ui/deckbridge_ui ] && libs="-lasound -lSDL2 -lSDL2_ttf"
        aarch64-linux-gnu-g++ $FLAGS -mcpu=cortex-a53 -I "$OUT/inc" -static-libstdc++ -static-libgcc \
            -o "$OUT/$(basename $prog).arm64" pi/$prog.cpp \
            -L/usr/lib/aarch64-linux-gnu -Wl,-rpath-link,/usr/lib/aarch64-linux-gnu $libs
        echo "$OUT/$(basename $prog).arm64"
    done
    for t in control/controller_test control/leds_test library/rekordbox_test; do g++ -std=c++17 -O2 -o "$OUT/$(basename $t)" pi/$t.cpp && "$OUT/$(basename $t)" .; done
else
    g++ $FLAGS -o "$OUT/deckbridge_pi" pi/deckbridge_pi.cpp -lasound
    g++ $FLAGS -o "$OUT/deckbridge_ui" pi/ui/deckbridge_ui.cpp -lasound $(sdl2-config --cflags --libs) -lSDL2_ttf
    echo "$OUT/deckbridge_pi $OUT/deckbridge_ui"
    for t in control/controller_test control/leds_test library/rekordbox_test; do g++ -std=c++17 -O2 -o "$OUT/$(basename $t)" pi/$t.cpp && "$OUT/$(basename $t)" .; done
fi
# O mapa da DDJ-400 vai junto do binário (--map, padrão ./ddj400-map.json).
cp android/app/src/main/resources/ddj400-map.json "$OUT/"
