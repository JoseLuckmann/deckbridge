// Os LEDs em C++ têm de mandar, para a mesma sequência de estados, os mesmos bytes que o app Kotlin
// (pi/control/testdata/leds.txt, gerado por LedGoldenTest).
#include "leds.h"

#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>

using namespace deckbridge::control;

int main(int argc, char **argv) {
    const std::string root = argc > 1 ? argv[1] : ".";
    std::ifstream in(root + "/pi/control/testdata/leds.txt");
    if (!in) {
        std::printf("FAIL leds.txt ausente\n");
        return 1;
    }
    LedDiff diff;
    std::vector<uint8_t> got;
    long steps = 0, failures = 0, bytes = 0;
    std::string line;
    while (std::getline(in, line)) {
        if (line.empty() || line[0] == '#') continue;
        if (line[0] == 'R') {
            diff.resend();
            continue;
        }
        if (line[0] == 'S') {
            std::istringstream s(line.substr(1));
            LedState state;
            for (auto &d : state.decks) {
                int loaded, playing, sync, pfl, mask, loop, peak;
                s >> loaded >> playing >> sync >> pfl >> mask >> loop >> peak;
                d.loaded = loaded;
                d.playing = playing;
                d.sync = sync;
                d.pfl = pfl;
                d.hotCues = static_cast<uint8_t>(mask);
                d.beatLoop = loop;
                d.peak = static_cast<float>(peak) / 1000.f;
            }
            int fx, pads;
            s >> fx >> pads;
            state.beatFxOn = fx;
            state.samplerPads = static_cast<uint8_t>(pads);
            got.clear();
            diff.messages(state, got);
            continue;
        }
        if (line[0] == 'O') {
            std::string want = line.substr(1), have;
            char hex[4];
            for (uint8_t b : got) {
                std::snprintf(hex, sizeof hex, " %02X", b);
                have += hex;
            }
            if (have != want && failures++ < 5) std::printf("FAIL passo %ld:\n  esperado%s\n  obtido  %s\n", steps, want.c_str(), have.c_str());
            bytes += static_cast<long>(got.size());
            ++steps;
        }
    }
    std::printf("%s %ld passos, %ld bytes de LED iguais ao Kotlin\nFAILS %ld\n", failures == 0 ? "PASS" : "FAIL", steps, bytes, failures);
    return failures == 0 ? 0 : 1;
}
