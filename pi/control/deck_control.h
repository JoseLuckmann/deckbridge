// Ações da DDJ-400 aplicadas ao motor no Pi: a mesma regra do onPerformance do DeckPage (Android) para mesa,
// transporte, prato, pitch, SYNC (tempo e fase), Beat FX (reduceBeatFx), RMX (reduceRmxPad, rmxPayload e a rampa da
// cena segurada), beat loop, reloop, loop in/out e beat jump. É um template no motor para o teste usar um motor falso.
// A lista (BROWSE/LOAD) fica com a tela; o que o Pi ainda não tem fica em `unsupported` e aparece no log.
#pragma once

#include "controller.h"

#include <array>
#include <atomic>
#include <cmath>
#include <mutex>
#include <string>

namespace deckbridge::control {

/** jogSeconds do MixMath.kt: giro grande (busca com SHIFT) andando mais devagar por unidade. */
inline double jogSeconds(double bend) { return std::fabs(bend) >= 20.0 ? bend / 800.0 : bend / 160.0; }

/** Nomes e frações do Beat FX, na ordem do app (PlayerModel.kt: BEAT_FX_NAMES, BEAT_FX_FRACTIONS). */
inline constexpr const char *kBeatFxNames[] = {"Low Cut Echo", "Echo", "Ping Pong", "Spiral", "Reverb", "Trans", "Flanger", "Phaser", "Pitch",
                                               "Slip Roll", "Roll", "Helix", "Mobius", "Filter", "Vinyl Brake", "Shimmer", "Delay"};
inline constexpr int kBeatFxCount = 17;
inline constexpr const char *kFractionLabels[] = {"1/16", "1/8", "1/4", "1/2", "3/4", "1/1", "2/1", "4/1", "8/1"};
inline constexpr double kFractionBeats[] = {0.0625, 0.125, 0.25, 0.5, 0.75, 1.0, 2.0, 4.0, 8.0};
inline constexpr const char *kSceneNames[] = {"Echo UP", "Noise UP", "Spiral UP", "Reverb UP", "Reverb DOWN", "Echo DOWN", "Tape STOP", "Dub ECHO"};
inline constexpr double kXBeats[] = {0.0625, 0.125, 0.25, 0.5, 1.0};

struct BeatFxState {
    int effect = 0, fraction = 5, channel = 0, level = 64;
    bool on = false;
};

struct RmxState {
    bool panel = false, holding = false, xOn = false, gate = false, drumGap = true, drumPitch = true;
    int scene = 0, bars = 4, xMode = 0, xBeat = 2, release = -1, channel = 0, drumVoice = 3, drumBars = 8;
    float intensity = 0.f, drumBuild = 0.f;
    float low[2]{1.f, 1.f}, mid[2]{1.f, 1.f}, high[2]{1.f, 1.f};
};

/** Loop de cada deck como o DeckFace do app (segundos; -1 = sem). */
struct LoopState {
    float start = -1.f, end = -1.f, memoryStart = -1.f, memoryEnd = -1.f;
};

template <class Engine>
class DeckControl {
public:
    explicit DeckControl(Engine &engine) : engine_(engine) {}

    /** BPM da grade de cada deck (vem da análise da faixa); sem ele o SYNC não age. */
    void setBpm(int deck, double bpm) {
        if (deck >= 0 && deck <= 1) bpm_[deck].store(bpm);
    }

    // Lidos pela thread dos LEDs enquanto a do MIDI escreve: atômicos.
    bool playing(int deck) const { return deck >= 0 && deck <= 1 && playing_[deck].load(std::memory_order_relaxed); }
    bool pfl(int deck) const { return deck >= 0 && deck <= 1 && pfl_[deck].load(std::memory_order_relaxed); }
    float pitch(int deck) const { return deck >= 0 && deck <= 1 ? pitch_[deck].load(std::memory_order_relaxed) : 1.f; }
    /** Grade de cada deck (origem e BPM), para o SYNC de fase e o beat jump. */
    void setGrid(int deck, double originSec, double bpm) {
        std::lock_guard<std::mutex> lock(m_);
        if (deck >= 0 && deck <= 1) {
            gridOrigin_[deck] = originSec;
            setBpm(deck, bpm);
        }
    }

    /** Painel RMX aberto (o botão RMX da tela): os pads do SAMPLER passam a ser cenas e X-PAD. */
    void setRmxPanel(bool open) {
        std::lock_guard<std::mutex> lock(m_);
        rmx_.panel = open;
        pushFx();
    }

    /** Grade do deck para quem agenda pelo compasso (roteiro da demonstração). */
    double gridOrigin(int deck) const {
        std::lock_guard<std::mutex> lock(m_);
        return gridOrigin_[deck & 1];
    }
    double bpm(int deck) const { return bpm_[deck & 1].load(); }

    BeatFxState beatFx() const {
        std::lock_guard<std::mutex> lock(m_);
        return fx_;
    }
    RmxState rmx() const {
        std::lock_guard<std::mutex> lock(m_);
        return rmx_;
    }
    LoopState loop(int deck) const {
        std::lock_guard<std::mutex> lock(m_);
        return loops_[deck & 1];
    }

    /** Chamado pela tela a cada quadro: a rampa da cena RMX (sobe em `bars` compassos segurada, cai 20%/40 ms solta). */
    void tick(double dtSec) {
        std::lock_guard<std::mutex> lock(m_);
        if (!rmx_.panel) return;
        const float before = rmx_.intensity;
        if (rmx_.holding) {
            const double bpm = bpm_[0].load() > 1.0 ? bpm_[0].load() : 120.0;  // como o app: 120 sem faixa
            const double ramp = std::max(0.3, rmx_.bars * 4.0 * 60.0 / bpm);
            rmx_.intensity = std::min(1.f, rmx_.intensity + static_cast<float>(dtSec / ramp));
        } else if (rmx_.intensity > 0.02f) {
            rmx_.intensity *= static_cast<float>(std::pow(0.8, dtSec / 0.04));
            if (rmx_.intensity <= 0.02f) rmx_.intensity = 0.f;
        }
        if (rmx_.intensity != before) pushFx();
    }

    long unsupported() const { return unsupported_; }
    const std::string &lastUnsupported() const { return lastUnsupported_; }

    void apply(const Action &a) {
        std::lock_guard<std::mutex> lock(m_);
        const int deck = a.deck;
        const bool valid = deck >= 0 && deck <= 1;
        const auto d = static_cast<size_t>(valid ? deck : 0);
        switch (a.kind) {
            case Kind::PLAY:
                if (!valid) return;
                playing_[d] = !playing_[d].load();
                engine_.transport(deck, playing_[d].load());
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
                if (valid) pfl_[d] = !pfl_[d].load();
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
                if (valid) pitch_[d].store(static_cast<float>(a.slider.value_or(1.0)));
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
                // Tempo do outro deck e, com os dois tocando, a fase pela grade (syncPhaseShiftSeconds do app).
                if (!valid) return;
                const size_t other = 1 - d;
                const double own = bpm_[d].load(), them = bpm_[other].load();
                if (own <= 0.0 || them <= 0.0) return;
                pitch_[d].store(static_cast<float>(std::clamp(them * pitch_[other].load() / own, 0.5, 2.0)));
                if (playing_[d].load() && playing_[other].load()) {
                    const double ownSec = engine_.positionSeconds(deck), otherSec = engine_.positionSeconds(static_cast<int>(other));
                    auto phase = [](double sec, double origin, double bpm) {
                        const double beat = (sec - origin) * bpm / 60.0;
                        return beat - std::floor(beat);
                    };
                    double delta = phase(otherSec, gridOrigin_[other], them) - phase(ownSec, gridOrigin_[d], own);
                    if (delta > 0.5) delta -= 1.0;
                    if (delta < -0.5) delta += 1.0;
                    const double shift = delta * 60.0 / own;
                    if (std::fabs(shift) > 0.002) engine_.seekSeconds(deck, ownSec + shift);
                }
                break;
            }
            case Kind::BEAT_FX:
                if (rmx_.panel && a.hotIndex == 8 && rmx_.xOn && rmx_.xMode == 2) {
                    rmx_.drumBuild = std::clamp(static_cast<float>(a.slider.value_or(0.0) / 127.0), 0.f, 1.f);
                } else if (rmx_.panel && a.hotIndex == 8) {
                    rmx_.intensity = std::clamp(static_cast<float>(a.slider.value_or(0.0) / 127.0), 0.f, 1.f);
                } else {
                    switch (a.hotIndex) {
                        case 1: fx_.effect = (fx_.effect + 1) % kBeatFxCount; break;
                        case 2: fx_.effect = (fx_.effect - 1 + kBeatFxCount) % kBeatFxCount; break;
                        case 3: fx_.fraction = std::max(0, fx_.fraction - 1); break;
                        case 4: fx_.fraction = std::min(8, fx_.fraction + 1); break;
                        case 5: fx_.on = !fx_.on; break;
                        case 6: fx_.on = false; break;
                        case 7:
                            fx_.channel = std::clamp(deck, 0, 2);
                            rmx_.channel = fx_.channel;
                            break;
                        case 8: fx_.level = std::clamp(static_cast<int>(std::lround(a.slider.value_or(fx_.level))), 0, 127); break;
                        default: break;
                    }
                }
                pushFx();
                return;
            case Kind::RMX: {
                if (!rmx_.panel) return;
                const int pad = a.hotIndex;
                const bool down = a.slider.value_or(0.0) > 0.0;
                if (pad >= 0 && pad <= 3 && down) {
                    rmx_.scene = pad;
                    rmx_.holding = true;
                } else if (pad >= 0 && pad <= 3) {
                    rmx_.holding = false;
                } else if (pad >= 4 && pad <= 7 && down) {
                    const int mode = pad - 4;
                    const bool turningOn = !(rmx_.xOn && rmx_.xMode == mode);
                    rmx_.xMode = mode;
                    rmx_.xOn = turningOn;
                    if (mode == 2 && turningOn && rmx_.drumBuild < 0.2f) rmx_.drumBuild = 1.f;
                }
                pushFx();
                return;
            }
            case Kind::BEAT_LOOP: {
                if (!valid || bpm_[d].load() <= 1.0) return;
                LoopState &l = loops_[d];
                const float pos = static_cast<float>(engine_.positionSeconds(deck));
                const float length = static_cast<float>(a.slider.value_or(4.0) * 60.0 / bpm_[d].load());
                if (l.end > l.start && std::fabs((l.end - l.start) - length) < 0.02f) {
                    l.memoryStart = l.start;
                    l.memoryEnd = l.end;
                    l.start = l.end = -1.f;
                    engine_.setLoop(deck, -1.f, -1.f);
                } else {
                    l.start = l.memoryStart = std::max(0.f, pos);
                    l.end = l.memoryEnd = pos + length;
                    engine_.setLoop(deck, l.start, l.end);
                }
                return;
            }
            case Kind::RELOOP: {
                if (!valid) return;
                LoopState &l = loops_[d];
                if (l.end > l.start) {
                    l.memoryStart = l.start;
                    l.memoryEnd = l.end;
                    l.start = l.end = -1.f;
                    engine_.setLoop(deck, -1.f, -1.f);
                } else if (l.memoryEnd > l.memoryStart) {
                    l.start = l.memoryStart;
                    l.end = l.memoryEnd;
                    engine_.setLoop(deck, l.start, l.end);
                    engine_.seekSeconds(deck, l.start);
                }
                if (a.hotIndex == 1) {
                    playing_[d] = false;
                    engine_.transport(deck, false);
                }
                return;
            }
            case Kind::LOOP_IN:
                if (!valid) return;
                loops_[d].start = static_cast<float>(engine_.positionSeconds(deck));
                loops_[d].end = -1.f;
                engine_.setLoop(deck, loops_[d].start, -1.f);
                return;
            case Kind::LOOP_OUT: {
                if (!valid) return;
                LoopState &l = loops_[d];
                const float end = static_cast<float>(engine_.positionSeconds(deck));
                if (end > l.start && l.start >= 0.f) {
                    l.end = end;
                    l.memoryStart = l.start;
                    l.memoryEnd = end;
                    engine_.setLoop(deck, l.start, end);
                }
                return;
            }
            case Kind::LOOP_SCALE: {
                if (!valid) return;
                LoopState &l = loops_[d];
                const double factor = a.slider.value_or(1.0);
                if (l.end <= l.start || factor <= 0.0) return;
                l.end = l.start + std::max(0.001f, static_cast<float>((l.end - l.start) * factor));
                l.memoryStart = l.start;
                l.memoryEnd = l.end;
                engine_.setLoop(deck, l.start, l.end);
                return;
            }
            case Kind::BEAT_JUMP: {
                if (!valid || bpm_[d].load() <= 1.0) return;
                static const double pads[] = {-1, 1, -2, 2, -4, 4, -8, 8};
                const double beats = a.hotIndex >= 0 && a.hotIndex < 8 ? pads[a.hotIndex] : a.slider.value_or(0.0);
                engine_.seekSeconds(deck, std::max(0.0, engine_.positionSeconds(deck) + beats * 60.0 / bpm_[d].load()));
                return;
            }
            default:
                ++unsupported_;
                lastUnsupported_ = kindName(a.kind);
                return;
        }
        pushMix();
    }

private:
    void pushMix() {
        engine_.setMix(vol_[0], vol_[1], pitch_[0].load(), pitch_[1].load(), cross_, pfl_[0].load(), pfl_[1].load());
        pushFx();
    }

    /** Beat FX e RMX no motor, como o LaunchedEffect(rmx, fx, ...) do DeckPage: com o painel RMX aberto, Beat FX desligado. */
    void pushFx() {
        const int gridDeck = rmx_.channel == 1 ? 1 : 0;
        const float bpm = static_cast<float>(std::max(1.0, bpm_[gridDeck].load()) * pitch_[gridDeck].load());
        const float beats = static_cast<float>(kFractionBeats[std::clamp(fx_.fraction, 0, 8)]);
        const float payload[24] = {
            rmx_.panel ? 1.f : 0.f, static_cast<float>(rmx_.scene), rmx_.holding ? 1.f : 0.f, rmx_.intensity, static_cast<float>(rmx_.bars),
            static_cast<float>(rmx_.xMode), static_cast<float>(kXBeats[std::clamp(rmx_.xBeat, 0, 4)]), rmx_.xOn ? 1.f : 0.f,
            static_cast<float>(rmx_.release), rmx_.low[0], rmx_.mid[0], rmx_.high[0], rmx_.low[1], rmx_.mid[1], rmx_.high[1],
            rmx_.gate ? 1.f : 0.f, static_cast<float>(rmx_.channel), bpm,
            rmx_.xOn && rmx_.xMode == 2 ? std::clamp(rmx_.drumBuild, 0.05f, 1.f) : std::clamp(rmx_.drumBuild, 0.f, 1.f),
            static_cast<float>(std::clamp(rmx_.drumVoice, 0, 3)), static_cast<float>(std::clamp(rmx_.drumBars, 4, 16)), rmx_.drumGap ? 1.f : 0.f,
            rmx_.drumPitch ? 1.f : 0.f, static_cast<float>(gridDeck)};
        if (rmx_.panel) {
            engine_.setBeatFx(fx_.effect, false, fx_.level, fx_.channel, bpm, beats);
            engine_.setRmx(payload, 24);
        } else {
            engine_.setRmx(payload, 24);
            engine_.setBeatFx(fx_.effect, fx_.on, fx_.level, fx_.channel, bpm, beats);
        }
    }

    Engine &engine_;
    std::atomic<bool> playing_[2]{{false}, {false}};
    std::array<bool, 2> scratching_{};
    std::atomic<bool> pfl_[2]{{false}, {false}};
    std::array<float, 2> vol_{0.8f, 0.8f};
    // pitch lido pela tela e bpm escrito pela thread de carga: atômicos.
    std::atomic<float> pitch_[2]{{1.f}, {1.f}};
    std::atomic<double> bpm_[2]{{0.0}, {0.0}};
    std::array<std::array<float, 3>, 2> eq_{{{0.5f, 0.5f, 0.5f}, {0.5f, 0.5f, 0.5f}}};
    float cross_ = 0.5f;
    float headMix_ = 0.f;
    float headLevel_ = 0.75f;
    double jogSensitivity_ = 1.0;
    long unsupported_ = 0;
    std::string lastUnsupported_;
    mutable std::mutex m_;  // apply (thread do MIDI) e tick/leituras (tela)
    BeatFxState fx_;
    RmxState rmx_;
    LoopState loops_[2];
    double gridOrigin_[2]{0.0, 0.0};
};

}  // namespace deckbridge::control
