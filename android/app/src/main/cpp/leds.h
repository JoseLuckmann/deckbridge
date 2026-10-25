// LEDs da DDJ-400: a única implementação do projeto (app Android por JNI, ControllerCore.kt; Pi direto). Nasceu como
// porta de DdjLeds.kt; os vetores de pi/control/testdata/leds.txt (LedGoldenTest) seguem como teste de regressão. Os bytes vêm do mapa pinado do Mixxx 2.5.6; não é captura da controladora.
#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <unordered_map>
#include <vector>

namespace deckbridge::control {

struct DeckLed {
    bool loaded = false;
    bool playing = false;
    bool sync = false;
    bool pfl = false;
    uint8_t hotCues = 0;  // bit i = hot cue i existe (0 a 7)
    int beatLoop = -1;    // índice em kBeatLoopSizes, ou -1
    float peak = 0.f;
};

struct LedState {
    std::array<DeckLed, 2> decks{};
    bool beatFxOn = false;
    uint8_t samplerPads = 0;  // bit i = pad i do SAMPLER aceso (RMX)
};

inline constexpr double kBeatLoopSizes[] = {0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 16.0, 32.0};

inline int beatLoopIndex(double loopBeats) {
    if (loopBeats <= 0.0) return -1;
    int best = 0;
    for (int i = 1; i < 8; ++i) {
        if (std::fabs(kBeatLoopSizes[i] - loopBeats) < std::fabs(kBeatLoopSizes[best] - loopBeats)) best = i;
    }
    return std::fabs(kBeatLoopSizes[best] - loopBeats) / kBeatLoopSizes[best] <= 0.06 ? best : -1;
}

/** Alvos na mesma ordem do LinkedHashMap do Kotlin: chave nova vai ao fim, chave repetida troca o valor no lugar. */
class LedTargets {
public:
    void clear() {
        order_.clear();
        where_.clear();
    }
    void set(int key, int value) {
        auto it = where_.find(key);
        if (it == where_.end()) {
            where_[key] = order_.size();
            order_.push_back({key, value});
        } else {
            order_[it->second].second = value;
        }
    }
    const std::vector<std::pair<int, int>> &items() const { return order_; }

private:
    std::vector<std::pair<int, int>> order_;
    std::unordered_map<int, size_t> where_;
};

inline void ledTargets(const LedState &state, LedTargets &out) {
    out.clear();
    auto key = [](int status, int note) { return (status << 8) | note; };
    auto lit = [&](int status, int note, bool on) { out.set(key(status, note), on ? 0x7F : 0x00); };
    for (int deck = 0; deck < 2; ++deck) {
        const DeckLed &d = state.decks[static_cast<size_t>(deck)];
        lit(0x9F, deck, d.loaded);
        lit(0x90 + deck, 0x0B, d.playing);
        lit(0x90 + deck, 0x0C, d.loaded && !d.playing);
        lit(0x90 + deck, 0x58, d.sync);
        lit(0x90 + deck, 0x54, d.pfl);
        for (int pad = 0; pad < 8; ++pad) {
            for (int status : {0x97 + 2 * deck, 0x98 + 2 * deck}) {
                lit(status, pad, (d.hotCues >> pad) & 1);
                lit(status, 0x60 + pad, pad == d.beatLoop);
            }
        }
        out.set(key(0xB0 + deck, 0x02), std::clamp(static_cast<int>(d.peak * 150.f), 0, 127));
    }
    lit(0x94, 0x47, state.beatFxOn);
    lit(0x94, 0x43, state.beatFxOn);
    for (int pad = 0; pad < 8; ++pad) lit(0x97, 0x30 + pad, (state.samplerPads >> pad) & 1);
}

/** Só as mudanças desde o último envio; `resend` esquece tudo (a controladora reconectou). Sem alocar depois do 1º. */
class LedDiff {
public:
    void resend() { last_.clear(); }

    /** Acrescenta em `out` as mensagens de 3 bytes que mudaram. */
    void messages(const LedState &state, std::vector<uint8_t> &out) {
        ledTargets(state, targets_);
        for (const auto &[k, value] : targets_.items()) {
            auto it = last_.find(k);
            if (it != last_.end() && it->second == value) continue;
            last_[k] = value;
            out.push_back(static_cast<uint8_t>(k >> 8));
            out.push_back(static_cast<uint8_t>(k & 0xFF));
            out.push_back(static_cast<uint8_t>(value));
        }
    }

private:
    LedTargets targets_;
    std::unordered_map<int, int> last_;
};

}  // namespace deckbridge::control
