// Lógica de controle da DDJ-400: a única implementação do projeto. O app Android usa por JNI (controller_jni.cpp,
// ControllerCore.kt) e o Pi direto (ADR-0008). Nasceu como porta, regra por regra, de MidiMap.kt
// (MidiMap, MidiAssembler) e MixMath.kt (TempoAssembler, performanceAction, surfaceAction, interpretPerformance).
// Os vetores de pi/control/testdata/golden.txt (gravados quando a regra ainda era Kotlin) seguem como teste de
// regressão: o ControllerGoldenTest (JVM, que agora chama este código por JNI) e o pi/control/controller_test.cpp.
// Mudou uma regra de propósito? Regrave o arquivo (DECKBRIDGE_WRITE_GOLDEN=1) e confira a diferença.
#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <optional>
#include <sstream>
#include <string>
#include <vector>

namespace deckbridge::control {

struct ControlHit {
    int status = 0;
    int midino = 0;
    std::string group;
    std::string key;
    std::string description;
    bool mapped = false;
};

struct MidiMessage {
    int status = 0;
    int data1 = 0;
    int data2 = 0;
    bool operator==(const MidiMessage &o) const { return status == o.status && data1 == o.data1 && data2 == o.data2; }
};

// Mesma ordem do enum Kotlin PerformanceAction.Kind.
enum class Kind {
    PLAY, CUE, HOT_CUE, VOLUME, CROSSFADER, PITCH, JOG,
    BROWSE_MOVE, BROWSE_ENTER, BROWSE_BACK, LOAD,
    BEAT_FX, FILTER, QUANTIZE, SYNC, MASTER,
    BEAT_JUMP, BEAT_JUMP_PAGE, LOOP_IN, LOOP_OUT, RELOOP, LOOP_SCALE, BEAT_LOOP, RMX,
    EQ, TRIM, HEAD_MIX, HEAD_GAIN, PFL, START, REVERSE_HOLD, LOOP_ADJUST, MASTER_LEVEL, JOG_TOUCH,
};

inline const char *kindName(Kind k) {
    static const char *names[] = {
        "PLAY", "CUE", "HOT_CUE", "VOLUME", "CROSSFADER", "PITCH", "JOG",
        "BROWSE_MOVE", "BROWSE_ENTER", "BROWSE_BACK", "LOAD",
        "BEAT_FX", "FILTER", "QUANTIZE", "SYNC", "MASTER",
        "BEAT_JUMP", "BEAT_JUMP_PAGE", "LOOP_IN", "LOOP_OUT", "RELOOP", "LOOP_SCALE", "BEAT_LOOP", "RMX",
        "EQ", "TRIM", "HEAD_MIX", "HEAD_GAIN", "PFL", "START", "REVERSE_HOLD", "LOOP_ADJUST", "MASTER_LEVEL", "JOG_TOUCH",
    };
    return names[static_cast<int>(k)];
}

struct Action {
    int deck = -1;
    Kind kind = Kind::PLAY;
    int hotIndex = -1;
    std::optional<double> slider;
};

inline bool startsWith(const std::string &s, const std::string &p) { return s.size() >= p.size() && s.compare(0, p.size(), p) == 0; }
inline bool endsWith(const std::string &s, const std::string &p) { return s.size() >= p.size() && s.compare(s.size() - p.size(), p.size(), p) == 0; }
inline bool contains(const std::string &s, const std::string &p) { return s.find(p) != std::string::npos; }

class MidiMap {
public:
    explicit MidiMap(std::vector<ControlHit> controls) : controls_(std::move(controls)) {
        // Índice direto (status, nota) → controle: O(1) por mensagem. O Kotlin procura numa lista (o primeiro que
        // casa); aqui vale o mesmo primeiro, porque só se grava o índice que ainda está vazio.
        index_.fill(-1);
        for (size_t i = 0; i < controls_.size(); ++i) {
            const int k = slot(controls_[i].status, controls_[i].midino);
            if (k >= 0 && index_[static_cast<size_t>(k)] < 0) index_[static_cast<size_t>(k)] = static_cast<int>(i);
        }
    }

    const std::vector<ControlHit> &all() const { return controls_; }

    ControlHit lookup(int status, int data1) const {
        const int k = slot(status, data1);
        if (k >= 0 && index_[static_cast<size_t>(k)] >= 0) return controls_[static_cast<size_t>(index_[static_cast<size_t>(k)])];
        ControlHit miss;
        miss.status = status;
        miss.midino = data1;
        miss.description = "sem nome no mapa oficial";
        return miss;
    }

    /** Mesmo leitor do MidiMap.fromJson: objetos de profundidade 2 com "status". */
    static MidiMap fromJson(const std::string &text) {
        std::vector<ControlHit> controls;
        int depth = 0;
        long start = -1;
        for (size_t i = 0; i < text.size(); ++i) {
            const char c = text[i];
            if (c == '{') {
                if (depth == 1) start = static_cast<long>(i);
                ++depth;
            } else if (c == '}') {
                if (depth == 2 && start >= 0) {
                    const std::string obj = text.substr(static_cast<size_t>(start), i - static_cast<size_t>(start) + 1);
                    if (contains(obj, "\"status\"")) {
                        ControlHit hit;
                        hit.status = jsonInt(obj, "status");
                        hit.midino = jsonInt(obj, "midino");
                        hit.group = jsonString(obj, "group");
                        hit.key = jsonString(obj, "key");
                        hit.description = jsonString(obj, "description");
                        hit.mapped = true;
                        controls.push_back(hit);
                    }
                    start = -1;
                }
                --depth;
            }
        }
        return MidiMap(std::move(controls));
    }

    static MidiMap fromFile(const std::string &path) {
        std::ifstream in(path);
        std::stringstream ss;
        ss << in.rdbuf();
        return fromJson(ss.str());
    }

private:
    static int slot(int status, int data1) {
        if (status < 0 || status > 255 || data1 < 0 || data1 > 255) return -1;
        return (status << 8) | data1;
    }

    static std::string jsonString(const std::string &obj, const std::string &key) {
        const std::string marker = "\"" + key + "\": \"";
        const auto from = obj.find(marker);
        if (from == std::string::npos) return "";
        const auto start = from + marker.size();
        const auto end = obj.find('"', start);
        return obj.substr(start, end - start);
    }

    static int jsonInt(const std::string &obj, const std::string &key) {
        const std::string marker = "\"" + key + "\": ";
        const auto from = obj.find(marker);
        if (from == std::string::npos) return -1;
        size_t at = from + marker.size();
        int value = 0;
        while (at < obj.size() && obj[at] >= '0' && obj[at] <= '9') value = value * 10 + (obj[at++] - '0');
        return value;
    }

    std::vector<ControlHit> controls_;
    std::array<int, 65536> index_{};
};

/** Porta de MidiAssembler: running status, SysEx ignorado, tempo real ignorado, system common cancela, note-off vira
 *  note-on com velocidade 0. Sem alocação por mensagem: a saída vai num vetor reaproveitado. */
class MidiAssembler {
public:
    void push(const uint8_t *bytes, size_t count, std::vector<MidiMessage> &out) {
        for (size_t i = 0; i < count; ++i) {
            const int value = bytes[i];
            if (inSysEx_) {
                if (value == 0xF7) inSysEx_ = false;
                continue;
            }
            if (value == 0xF0) {
                inSysEx_ = true;
                pending_ = 0;
                continue;
            }
            if (value >= 0xF8) continue;
            if (value >= 0xF1) {
                running_ = -1;
                pending_ = 0;
                continue;
            }
            if (value >= 0x80) {
                running_ = value;
                pending_ = 0;
                buf_[pending_++] = value;
                continue;
            }
            if (running_ < 0) continue;
            if (pending_ == 0) buf_[pending_++] = running_;
            buf_[pending_++] = value;
            const int needed = (running_ >= 0xC0 && running_ <= 0xDF) ? 2 : 3;
            if (pending_ >= needed) {
                const int data2 = needed == 3 ? buf_[2] : 0;
                if (buf_[0] >= 0x80 && buf_[0] <= 0x8F) out.push_back({buf_[0] + 0x10, buf_[1], 0});
                else out.push_back({buf_[0], buf_[1], data2});
                pending_ = 0;
            }
        }
    }

private:
    int running_ = -1;
    int buf_[3]{};
    int pending_ = 0;
    bool inSysEx_ = false;
};

inline constexpr double kTempoRanges[] = {0.06, 0.10, 0.16, 0.25};
inline constexpr double kDefaultTempoRange = 0.10;
inline constexpr double kJogBendScale = 0.8;
inline constexpr double kJogSeekScale = 150.0;

inline double nextTempoRange(double current) {
    for (int i = 0; i < 4; ++i) {
        if (std::fabs(kTempoRanges[i] - current) < 1e-9) return kTempoRanges[(i + 1) % 4];
    }
    return kTempoRanges[0];
}

inline double pitchFromTempo14(int msb, int lsb, double range = kDefaultTempoRange) {
    const int full = ((msb & 0x7F) << 7) | (lsb & 0x7F);
    const double rate = 1.0 - (full / static_cast<double>(0x2000));
    return std::clamp(1.0 + rate * range, 0.5, 2.0);
}

class TempoAssembler {
public:
    double rangeOf(int deck) const { return deck >= 0 && deck <= 1 ? range_[deck] : kDefaultTempoRange; }
    void setRange(int deck, double value) {
        if (deck >= 0 && deck <= 1) range_[deck] = value;
    }

    std::optional<Action> interpret(const ControlHit &hit, int value) {
        if (!hit.mapped) return std::nullopt;
        int deck;
        if (hit.group == "[Channel1]") deck = 0;
        else if (hit.group == "[Channel2]") deck = 1;
        else return std::nullopt;
        if (endsWith(hit.key, "tempoSliderMSB")) {
            msb_[deck] = value & 0x7F;
            return std::nullopt;
        }
        if (endsWith(hit.key, "tempoSliderLSB")) {
            lsb_[deck] = value & 0x7F;
            return pitchAction(deck);
        }
        if (endsWith(hit.key, "cycleTempoRange") && value > 0) {
            range_[deck] = nextTempoRange(range_[deck]);
            return pitchAction(deck);
        }
        return std::nullopt;
    }

private:
    std::optional<Action> pitchAction(int deck) const {
        if (msb_[deck] < 0 || lsb_[deck] < 0) return std::nullopt;
        Action a;
        a.deck = deck;
        a.kind = Kind::PITCH;
        a.slider = pitchFromTempo14(msb_[deck], lsb_[deck], range_[deck]);
        return a;
    }

    int msb_[2]{-1, -1};
    int lsb_[2]{-1, -1};
    double range_[2]{kDefaultTempoRange, kDefaultTempoRange};
};

inline Action act(int deck, Kind kind, int hotIndex = -1, std::optional<double> slider = std::nullopt) {
    Action a;
    a.deck = deck;
    a.kind = kind;
    a.hotIndex = hotIndex;
    a.slider = slider;
    return a;
}

inline std::optional<Action> performanceAction(const ControlHit &hit, int value) {
    if (!hit.mapped) return std::nullopt;
    const int deck = hit.group == "[Channel1]" ? 0 : hit.group == "[Channel2]" ? 1 : -1;
    const double slider = std::clamp(value, 0, 127) / 127.0;
    const std::string &key = hit.key;
    if (key == "reverseroll" && deck >= 0) return act(deck, Kind::REVERSE_HOLD, value > 0 ? 1 : 0);
    if (key == "play" && value > 0 && deck >= 0) return act(deck, Kind::PLAY);
    if (key == "cue_default" && value > 0 && deck >= 0) return act(deck, Kind::CUE);
    if (startsWith(key, "hotcue_") && endsWith(key, "_activate") && value > 0 && deck >= 0) {
        const std::string rest = key.substr(7);
        const std::string number = rest.substr(0, rest.find('_'));
        if (number.empty() || number.find_first_not_of("0123456789") != std::string::npos) return std::nullopt;
        return act(deck, Kind::HOT_CUE, std::stoi(number) - 1);
    }
    if (key == "pfl" && value > 0 && deck >= 0) return act(deck, Kind::PFL);
    if (key == "volume" && deck >= 0 && hit.midino < 32) return act(deck, Kind::VOLUME, -1, slider);
    if (key == "crossfader" && hit.midino < 32) return act(-1, Kind::CROSSFADER, -1, slider);
    if (startsWith(hit.group, "[EqualizerRack1_[Channel") && hit.midino < 32) {
        int band;
        if (key == "parameter1") band = 0;
        else if (key == "parameter2") band = 1;
        else if (key == "parameter3") band = 2;
        else return std::nullopt;
        const int eqDeck = contains(hit.group, "[Channel2]") ? 1 : 0;
        return act(eqDeck, Kind::EQ, band, slider);
    }
    if (key == "pregain" && deck >= 0 && hit.midino < 32) return act(deck, Kind::TRIM, -1, slider);
    if (hit.group == "[Master]" && key == "headMix" && hit.midino < 32) return act(-1, Kind::HEAD_MIX, -1, slider);
    if (hit.group == "[Master]" && key == "headGain" && hit.midino < 32) return act(-1, Kind::HEAD_GAIN, -1, slider);
    if (key == "PioneerDDJ400.jogTouch" && deck >= 0) return act(deck, Kind::JOG_TOUCH, value > 0 ? 1 : 0);
    if (key == "PioneerDDJ400.jogTurn" && deck >= 0) return act(deck, Kind::JOG, -1, (value - 64) * kJogBendScale);
    if (key == "PioneerDDJ400.jogSearch" && deck >= 0) return act(deck, Kind::JOG, -1, (value - 64) * kJogSeekScale);
    return std::nullopt;
}

inline std::optional<Action> beatLoopAction(const std::string &key, int deck) {
    if (deck < 0) return std::nullopt;
    static const std::pair<const char *, double> loops[] = {
        {"beatloop_0.25_toggle", 0.25}, {"beatloop_0.5_toggle", 0.5}, {"beatloop_1_toggle", 1.0}, {"beatloop_2_toggle", 2.0},
        {"beatloop_4_toggle", 4.0}, {"beatloop_8_toggle", 8.0}, {"beatloop_16_toggle", 16.0}, {"beatloop_32_toggle", 32.0},
    };
    for (const auto &item : loops) {
        if (key == item.first) return act(deck, Kind::BEAT_LOOP, -1, item.second);
    }
    return std::nullopt;
}

inline std::optional<Action> surfaceAction(const ControlHit &hit, int value) {
    if (!hit.mapped) return std::nullopt;
    const std::string &key = hit.key;
    if (key == "MoveVertical") {
        const int steps = value == 0 ? 0 : value < 64 ? value : value - 128;
        if (steps == 0) return std::nullopt;
        return act(-1, Kind::BROWSE_MOVE, -1, static_cast<double>(steps));
    }
    if (value <= 0 && key != "PioneerDDJ400.beatFxLevelDepthRotate" && key != "super1" && key != "PioneerDDJ400.samplerPadPressed") {
        return std::nullopt;
    }
    const int deck = contains(hit.group, "Channel2") ? 1 : contains(hit.group, "Channel1") ? 0 : -1;
    if (key == "MoveFocusForward") return act(-1, Kind::BROWSE_ENTER);
    if (key == "MoveFocusBackward") return act(-1, Kind::BROWSE_BACK);
    if (key == "LoadSelectedTrack") return deck >= 0 ? std::optional<Action>(act(deck, Kind::LOAD)) : std::nullopt;
    if (key == "PioneerDDJ400.beatFxSelectPressed") return act(-1, Kind::BEAT_FX, 1);
    if (key == "PioneerDDJ400.beatFxSelectShiftPressed") return act(-1, Kind::BEAT_FX, 2);
    if (key == "PioneerDDJ400.beatFxLeftPressed") return act(-1, Kind::BEAT_FX, 3);
    if (key == "PioneerDDJ400.beatFxRightPressed") return act(-1, Kind::BEAT_FX, 4);
    if (key == "PioneerDDJ400.beatFxOnOffPressed") return act(-1, Kind::BEAT_FX, 5);
    if (key == "PioneerDDJ400.beatFxOnOffShiftPressed") return act(-1, Kind::BEAT_FX, 6);
    if (key == "PioneerDDJ400.beatFxChannel") {
        int channel = 0;
        if (contains(hit.description, "MASTER")) channel = 2;
        else if (contains(hit.description, "CH2") || contains(hit.description, "DECK 2")) channel = 1;
        return act(channel, Kind::BEAT_FX, 7);
    }
    if (key == "PioneerDDJ400.beatFxLevelDepthRotate") return act(-1, Kind::BEAT_FX, 8, static_cast<double>(std::clamp(value, 0, 127)));
    if (key == "super1") {
        if (hit.midino != 23 && hit.midino != 24) return std::nullopt;
        return act(deck, Kind::FILTER, -1, std::clamp(value, 0, 127) / 127.0);
    }
    auto ifDeck = [&](Action a) -> std::optional<Action> { return deck >= 0 ? std::optional<Action>(a) : std::nullopt; };
    if (key == "start_play") return ifDeck(act(deck, Kind::START));
    if (key == "PioneerDDJ400.quickJumpBack") return ifDeck(act(deck, Kind::BEAT_JUMP, -1, -32.0));
    if (key == "PioneerDDJ400.quickJumpForward") return ifDeck(act(deck, Kind::BEAT_JUMP, -1, 32.0));
    if (key == "PioneerDDJ400.toggleQuantize") return act(deck, Kind::QUANTIZE);
    if (key == "PioneerDDJ400.syncPressed") return act(deck, Kind::SYNC);
    if (key == "PioneerDDJ400.syncLongPressed") return act(deck, Kind::MASTER);
    if (key == "PioneerDDJ400.toggleLoopAdjustIn") return ifDeck(act(deck, Kind::LOOP_ADJUST, 1));
    if (key == "PioneerDDJ400.toggleLoopAdjustOut") return ifDeck(act(deck, Kind::LOOP_ADJUST, 2));
    if (key == "loop_in") return ifDeck(act(deck, Kind::LOOP_IN));
    if (key == "loop_out") return ifDeck(act(deck, Kind::LOOP_OUT));
    if (key == "reloop_toggle") return ifDeck(act(deck, Kind::RELOOP, 0));
    if (key == "reloop_andstop") return ifDeck(act(deck, Kind::RELOOP, 1));
    if (key == "PioneerDDJ400.cueLoopCallLeft") return ifDeck(act(deck, Kind::LOOP_SCALE, -1, 0.5));
    if (key == "PioneerDDJ400.cueLoopCallRight") return ifDeck(act(deck, Kind::LOOP_SCALE, -1, 2.0));
    if (key == "PioneerDDJ400.beatjumpPadPressed") {
        const int pad = hit.midino - 32;
        if (deck < 0 || pad < 0 || pad > 7) return std::nullopt;
        return act(deck, Kind::BEAT_JUMP, pad);
    }
    if (key == "PioneerDDJ400.decreaseBeatjumpSizes") return ifDeck(act(deck, Kind::BEAT_JUMP_PAGE, 0));
    if (key == "PioneerDDJ400.increaseBeatjumpSizes") return ifDeck(act(deck, Kind::BEAT_JUMP_PAGE, 1));
    if (key == "PioneerDDJ400.samplerPadPressed") {
        const int pad = std::clamp(hit.midino - 48, 0, 7);
        const int side = (hit.status == 153 || hit.status == 154 || contains(hit.description, "RIGHT")) ? 1 : 0;
        return act(side, Kind::RMX, pad, value > 0 ? 1.0 : 0.0);
    }
    if (key == "PioneerDDJ400.samplerPadShiftPressed") {
        const int pad = std::clamp(hit.midino - 48, 0, 7);
        const int side = (hit.status == 154 || contains(hit.description, "RIGHT")) ? 1 : 0;
        return act(side, Kind::RMX, 8 + pad, 1.0);
    }
    return beatLoopAction(key, deck);
}

inline std::optional<Action> interpretPerformance(const ControlHit &hit, int value, TempoAssembler &tempo) {
    if (auto a = tempo.interpret(hit, value)) return a;
    if (auto a = performanceAction(hit, value)) return a;
    return surfaceAction(hit, value);
}

}  // namespace deckbridge::control
