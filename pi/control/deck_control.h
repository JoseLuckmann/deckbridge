// Ações da DDJ-400 aplicadas ao motor no Pi: a mesma regra do onPerformance do DeckPage (Android) para mesa,
// transporte, prato e pitch. É um template no motor para o teste usar um motor falso que só anota as chamadas.
// O que o Pi ainda não tem (lista, efeitos, loops, RMX) fica em `unsupported` e aparece no log, não some calado.
#pragma once

#include "controller.h"

#include <array>
#include <cmath>
#include <string>

namespace deckbridge::control {

/** jogSeconds do MixMath.kt: giro grande (busca com SHIFT) andando mais devagar por unidade. */
inline double jogSeconds(double bend) { return std::fabs(bend) >= 20.0 ? bend / 800.0 : bend / 160.0; }

template <class Engine>
class DeckControl {
public:
    explicit DeckControl(Engine &engine) : engine_(engine) {}

    /** BPM da grade de cada deck (vem da análise da faixa); sem ele o SYNC não age. */
    void setBpm(int deck, double bpm) {
        if (deck >= 0 && deck <= 1) bpm_[static_cast<size_t>(deck)] = bpm;
    }

    bool playing(int deck) const { return deck >= 0 && deck <= 1 && playing_[static_cast<size_t>(deck)]; }
    float pitch(int deck) const { return deck >= 0 && deck <= 1 ? pitch_[static_cast<size_t>(deck)] : 1.f; }
    long unsupported() const { return unsupported_; }
    const std::string &lastUnsupported() const { return lastUnsupported_; }

    void apply(const Action &a) {
        const int deck = a.deck;
        const bool valid = deck >= 0 && deck <= 1;
        const auto d = static_cast<size_t>(valid ? deck : 0);
        switch (a.kind) {
            case Kind::PLAY:
                if (!valid) return;
                playing_[d] = !playing_[d];
                engine_.transport(deck, playing_[d]);
                break;
            case Kind::CUE:
                if (!valid) return;
                playing_[d] = false;
                engine_.cue(deck);
                break;
            case Kind::HOT_CUE:
                if (valid) engine_.hotCue(deck, a.hotIndex);
                break;
            case Kind::VOLUME:
                if (valid) vol_[d] = static_cast<float>(a.slider.value_or(0.0));
                break;
            case Kind::CROSSFADER:
                cross_ = static_cast<float>(a.slider.value_or(0.5));
                break;
            case Kind::EQ:
                if (!valid || a.hotIndex < 0 || a.hotIndex > 2) return;
                eq_[d][static_cast<size_t>(a.hotIndex)] = static_cast<float>(a.slider.value_or(0.5));
                engine_.setEq(deck, eq_[d][0], eq_[d][1], eq_[d][2]);
                break;
            case Kind::TRIM:
                if (valid) engine_.setTrimKnob(deck, static_cast<float>(a.slider.value_or(0.5)));
                break;
            case Kind::START:
                if (!valid) return;
                engine_.seekSeconds(deck, 0.0);
                playing_[d] = true;
                engine_.transport(deck, true);
                break;
            case Kind::PFL:
                if (valid) pfl_[d] = !pfl_[d];
                break;
            case Kind::HEAD_MIX:
                headMix_ = static_cast<float>(a.slider.value_or(0.0));
                engine_.setHead(headMix_, headLevel_);
                break;
            case Kind::HEAD_GAIN:
                headLevel_ = static_cast<float>(a.slider.value_or(0.75));
                engine_.setHead(headMix_, headLevel_);
                break;
            case Kind::PITCH:
                if (valid) pitch_[d] = static_cast<float>(a.slider.value_or(1.0));
                break;
            case Kind::JOG_TOUCH:
                if (!valid) return;
                // Modo vinil (o padrão do app): tocar no topo segura a faixa e o giro faz scratch.
                scratching_[d] = a.hotIndex == 1;
                engine_.scratchTouch(deck, scratching_[d]);
                return;
            case Kind::JOG: {
                if (!valid) return;
                const double seconds = jogSeconds(a.slider.value_or(0.0) * jogSensitivity_);
                if (scratching_[d]) engine_.scratchMove(deck, seconds);
                else engine_.nudge(deck, seconds);
                return;
            }
            case Kind::FILTER: {
                if (!valid) return;
                const long value = std::lround(a.slider.value_or(0.5) * 127.0);
                engine_.setFilter(deck, static_cast<float>(std::clamp(value, 0L, 127L)) / 127.f);
                return;
            }
            case Kind::SYNC: {
                // Tempo do outro deck (sem o alinhamento de fase do app, que precisa da grade: próxima etapa).
                if (!valid) return;
                const size_t other = 1 - d;
                if (bpm_[d] <= 0.0 || bpm_[other] <= 0.0) return;
                pitch_[d] = static_cast<float>(std::clamp(bpm_[other] * pitch_[other] / bpm_[d], 0.5, 2.0));
                break;
            }
            default:
                ++unsupported_;
                lastUnsupported_ = kindName(a.kind);
                return;
        }
        pushMix();
    }

private:
    void pushMix() { engine_.setMix(vol_[0], vol_[1], pitch_[0], pitch_[1], cross_, pfl_[0], pfl_[1]); }

    Engine &engine_;
    std::array<bool, 2> playing_{};
    std::array<bool, 2> scratching_{};
    std::array<bool, 2> pfl_{};
    std::array<float, 2> vol_{0.8f, 0.8f};
    std::array<float, 2> pitch_{1.f, 1.f};
    std::array<double, 2> bpm_{};
    std::array<std::array<float, 3>, 2> eq_{{{0.5f, 0.5f, 0.5f}, {0.5f, 0.5f, 0.5f}}};
    float cross_ = 0.5f;
    float headMix_ = 0.f;
    float headLevel_ = 0.75f;
    double jogSensitivity_ = 1.0;
    long unsupported_ = 0;
    std::string lastUnsupported_;
};

}  // namespace deckbridge::control
