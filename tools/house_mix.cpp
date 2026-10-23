// Mix de avaliação em house, pelo mesmo caminho de efeitos do APK (dsp.h, MixerFx).
// As duas faixas são sintetizadas aqui (sem material de terceiros): bumbo, chimbal, palma,
// baixo com sidechain, acordes e pad, com breakdown de 8 e 4 compassos onde a subida acontece.
// Não é o tablet e não é a DDJ-400. Ninguém ouviu o resultado aqui.
//
// g++ -std=c++17 -O2 -I android/app/src/main/cpp -o house_mix tools/house_mix.cpp
// ./house_mix saida.wav cues.tsv        (rodar na raiz do repositório, para achar os FLAC)
// -DHOUSE_LEGACY compila contra o dsp.h antigo (sem tipos de Color FX).
#include "dsp.h"
#include "drum_load.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace {

constexpr int kRate = 48000;
constexpr double kBpm = 126.0;
constexpr double kBeat = kRate * 60.0 / kBpm;
constexpr double kBar = kBeat * 4.0;
constexpr double kTwoPi = 6.283185307179586;
constexpr int kFilterKind = 5;

struct Rng {
    uint32_t state;
    float next() {
        state ^= state << 13;
        state ^= state >> 17;
        state ^= state << 5;
        return (static_cast<int>(state & 0xFFFF) / 32768.f) - 1.f;
    }
};

struct Track {
    std::vector<float> left;
    std::vector<float> right;
};

enum Section { kIntro, kGroove, kBreak };

struct Plan {
    int bars;
    int root;  // MIDI
    uint32_t seed;
    Section (*section)(int);
};

double midiHz(double note) { return 440.0 * std::pow(2.0, (note - 69.0) / 12.0); }

void addAt(std::vector<float> &buf, long start, const std::vector<float> &voice, float gain) {
    for (size_t i = 0; i < voice.size(); ++i) {
        const long at = start + static_cast<long>(i);
        if (at < 0 || at >= static_cast<long>(buf.size())) continue;
        buf[static_cast<size_t>(at)] += voice[i] * gain;
    }
}

std::vector<float> kickVoice() {
    std::vector<float> out(static_cast<size_t>(kRate * 0.5));
    double phase = 0.0;
    for (size_t i = 0; i < out.size(); ++i) {
        const double t = static_cast<double>(i) / kRate;
        const double f = 46.0 + 118.0 * std::exp(-t / 0.032);
        phase += kTwoPi * f / kRate;
        double y = std::sin(phase) * std::exp(-t / 0.17);
        if (t < 0.003) y += 0.35 * std::sin(kTwoPi * 2200.0 * t) * (1.0 - t / 0.003);
        out[i] = static_cast<float>(std::tanh(1.5 * y) * 0.9);
    }
    return out;
}

std::vector<float> hatVoice(double decay, uint32_t seed, float amp) {
    Rng rng{seed};
    std::vector<float> out(static_cast<size_t>(kRate * std::max(0.05, decay * 5.0)));
    float prev = 0.f;
    for (size_t i = 0; i < out.size(); ++i) {
        const double t = static_cast<double>(i) / kRate;
        const float n = rng.next();
        const float high = n - prev * 0.85f;  // passa-altas simples
        prev = n;
        out[i] = high * static_cast<float>(std::exp(-t / decay)) * amp;
    }
    return out;
}

std::vector<float> clapVoice(uint32_t seed) {
    Rng rng{seed};
    std::vector<float> out(static_cast<size_t>(kRate * 0.35));
    float lp = 0.f;
    float hp = 0.f;
    for (size_t i = 0; i < out.size(); ++i) {
        const double t = static_cast<double>(i) / kRate;
        const float n = rng.next();
        lp += (n - lp) * 0.32f;
        hp += (lp - hp) * 0.04f;
        const float band = lp - hp;
        double env = std::exp(-t / 0.11) * 0.55;
        for (int burst = 0; burst < 3; ++burst) {
            const double tb = t - burst * 0.011;
            if (tb >= 0.0 && tb < 0.009) env += std::exp(-tb / 0.003);
        }
        out[i] = band * static_cast<float>(env) * 1.6f;
    }
    return out;
}

// Serra com passa-baixas. Uma nota do baixo ou uma voz de acorde.
void addSaw(std::vector<float> &buf, long start, double hz, double length, double decay, float amp, float cutoff, float detune) {
    const long n = static_cast<long>(length * kRate);
    double p1 = 0.0;
    double p2 = 0.37;
    float lp = 0.f;
    const float a = 1.f - std::exp(-6.2831853f * cutoff / kRate);
    for (long i = 0; i < n; ++i) {
        const long at = start + i;
        if (at < 0 || at >= static_cast<long>(buf.size())) continue;
        const double t = static_cast<double>(i) / kRate;
        p1 += hz / kRate;
        p2 += hz * (1.0 + detune) / kRate;
        p1 -= std::floor(p1);
        p2 -= std::floor(p2);
        const float saw = static_cast<float>((p1 * 2.0 - 1.0) + (p2 * 2.0 - 1.0)) * 0.5f;
        lp += (saw - lp) * a;
        float env = static_cast<float>(std::exp(-t / decay));
        const float attack = std::min(1.f, static_cast<float>(t / 0.004));
        const float release = std::min(1.f, static_cast<float>((length - t) / 0.01));
        buf[static_cast<size_t>(at)] += lp * env * attack * release * amp;
    }
}

Section planA(int bar) {
    if (bar < 4) return kIntro;
    if (bar < 16) return kGroove;
    if (bar < 24) return kBreak;
    if (bar < 40) return kGroove;
    if (bar < 48) return kBreak;
    if (bar < 64) return kGroove;
    if (bar < 68) return kBreak;
    if (bar < 80) return kGroove;
    if (bar < 84) return kBreak;
    return kGroove;
}

Section planB(int bar) {
    if (bar < 2) return kIntro;
    return kGroove;
}

Track synth(const Plan &plan) {
    Track track;
    const long total = static_cast<long>(plan.bars * kBar) + kRate * 4;
    track.left.assign(static_cast<size_t>(total), 0.f);
    track.right.assign(static_cast<size_t>(total), 0.f);
    std::vector<float> kickBuf(static_cast<size_t>(total), 0.f);
    std::vector<float> drums(static_cast<size_t>(total), 0.f);
    std::vector<float> bass(static_cast<size_t>(total), 0.f);
    std::vector<float> chordsL(static_cast<size_t>(total), 0.f);
    std::vector<float> chordsR(static_cast<size_t>(total), 0.f);
    std::vector<float> pad(static_cast<size_t>(total), 0.f);
    const auto kick = kickVoice();
    const auto hatC = hatVoice(0.018, plan.seed ^ 0x11u, 0.11f);
    const auto hatO = hatVoice(0.085, plan.seed ^ 0x22u, 0.15f);
    const auto clap = clapVoice(plan.seed ^ 0x33u);
    const double root = plan.root;
    const double minor7[4] = {0.0, 3.0, 7.0, 10.0};
    std::vector<long> kickStarts;
    for (int bar = 0; bar < plan.bars; ++bar) {
        const Section sec = plan.section(bar);
        const double barStart = bar * kBar;
        if (sec != kBreak) {
            for (int beat = 0; beat < 4; ++beat) {
                const long at = static_cast<long>(barStart + beat * kBeat);
                addAt(kickBuf, at, kick, 1.f);
                kickStarts.push_back(at);
            }
        }
        for (int step = 0; step < 16; ++step) {
            const long at = static_cast<long>(barStart + step * kBeat / 4.0);
            const bool offbeat = (step % 4) == 2;
            if (sec == kBreak) {
                if (step % 2 == 0) addAt(drums, at, hatC, 0.5f);
                continue;
            }
            if (offbeat) addAt(drums, at, hatO, 1.f);
            else if (step % 2 == 1 && sec == kGroove) addAt(drums, at, hatC, 0.8f);
            if (sec == kGroove && (step == 4 || step == 12)) addAt(drums, at, clap, 0.75f);
        }
        if (sec == kGroove) {
            for (int beat = 0; beat < 4; ++beat) {
                const long at = static_cast<long>(barStart + (beat + 0.5) * kBeat);
                const double note = root - 24.0 + ((bar / 4) % 2 == 1 && beat >= 2 ? 3.0 : 0.0);
                addSaw(bass, at, midiHz(note), kBeat * 0.42 / kRate, 0.2, 0.55f, 380.f, 0.004f);
            }
        }
        if (sec != kIntro) {
            const int pattern[3] = {0, 6, 10};  // semicolcheias dentro do compasso
            for (int hit : pattern) {
                if (sec == kBreak && hit != 0) continue;
                const long at = static_cast<long>(barStart + hit * kBeat / 4.0);
                for (int voice = 0; voice < 4; ++voice) {
                    const double note = root + minor7[voice] + 12.0 + (voice == 3 ? -12.0 : 0.0);
                    addSaw(chordsL, at, midiHz(note), 0.26, 0.15, 0.07f, 2600.f, 0.006f);
                    addSaw(chordsR, at, midiHz(note), 0.26, 0.15, 0.07f, 2600.f, -0.006f);
                }
            }
        }
        if (sec == kBreak) {
            const long at = static_cast<long>(barStart);
            for (int voice = 0; voice < 4; ++voice) {
                const double note = root + minor7[voice];
                addSaw(pad, at, midiHz(note), kBar / kRate * 0.98, 6.0, 0.06f, 1400.f, 0.004f);
            }
        }
    }
    // Sidechain: tudo menos o bumbo e a bateria cede ao bumbo.
    std::vector<float> duck(static_cast<size_t>(total), 1.f);
    for (long start : kickStarts) {
        for (long i = 0; i < kRate / 4; ++i) {
            const long at = start + i;
            if (at >= total) break;
            const float t = static_cast<float>(i) / kRate;
            duck[static_cast<size_t>(at)] = std::min(duck[static_cast<size_t>(at)], 1.f - 0.82f * std::exp(-t / 0.075f));
        }
    }
    // Eco de 3/4 de tempo nos acordes.
    const long delaySamples = static_cast<long>(kBeat * 0.75);
    for (long i = delaySamples; i < total; ++i) {
        chordsL[static_cast<size_t>(i)] += chordsR[static_cast<size_t>(i - delaySamples)] * 0.34f;
        chordsR[static_cast<size_t>(i)] += chordsL[static_cast<size_t>(i - delaySamples)] * 0.34f;
    }
    for (long i = 0; i < total; ++i) {
        const float d = duck[static_cast<size_t>(i)];
        const float body = kickBuf[static_cast<size_t>(i)] * 0.95f + bass[static_cast<size_t>(i)] * d;
        track.left[static_cast<size_t>(i)] = body + drums[static_cast<size_t>(i)] + chordsL[static_cast<size_t>(i)] * d + pad[static_cast<size_t>(i)] * (0.35f + 0.65f * d);
        track.right[static_cast<size_t>(i)] = body + drums[static_cast<size_t>(i)] * 0.96f + chordsR[static_cast<size_t>(i)] * d + pad[static_cast<size_t>(i)] * (0.35f + 0.65f * d);
    }
    float peak = 0.f;
    for (long i = 0; i < total; ++i) peak = std::max(peak, std::max(std::fabs(track.left[static_cast<size_t>(i)]), std::fabs(track.right[static_cast<size_t>(i)])));
    const float scale = peak > 0.f ? 0.72f / peak : 1.f;
    for (long i = 0; i < total; ++i) {
        track.left[static_cast<size_t>(i)] *= scale;
        track.right[static_cast<size_t>(i)] *= scale;
    }
    return track;
}

bool loadDrums() {
    const char *names[] = {"kick.flac", "clap.flac", "snare.flac", "hat-closed.flac", "hat-open.flac"};
    bool all = true;
    for (int voice = 0; voice < 5; ++voice) {
        const std::string path = std::string("android/app/src/main/assets/drums/") + names[voice];
        FILE *file = std::fopen(path.c_str(), "rb");
        if (file == nullptr) {
            std::fprintf(stderr, "sample ausente %s\n", path.c_str());
            all = false;
            continue;
        }
        std::fseek(file, 0, SEEK_END);
        const long bytes = std::ftell(file);
        std::fseek(file, 0, SEEK_SET);
        std::vector<uint8_t> data(static_cast<size_t>(std::max(0L, bytes)));
        if (!data.empty() && std::fread(data.data(), 1, data.size(), file) != data.size()) all = false;
        std::fclose(file);
        if (!deckbridge::loadDrumFlac(data.data(), data.size(), voice)) all = false;
    }
    return all;
}

struct RmxCfg {
    bool armed = false;
    int scene = 0;
    bool holding = false;
    float ceiling = 0.f;
    float bars = 1.f;
    int xMode = 0;
    bool xOn = false;
    float build = 0.f;
    int voice = 0;
    int drumBars = 8;
    bool gap = true;
    bool pitch = true;
    float isoLow[2] = {1.f, 1.f};
    bool operator==(const RmxCfg &o) const { return std::memcmp(this, &o, sizeof(RmxCfg)) == 0; }
};

struct BeatCfg {
    int effect = 0;
    bool on = false;
    float level = 0.f;
    float beats = 1.f;
    bool operator==(const BeatCfg &o) const { return std::memcmp(this, &o, sizeof(BeatCfg)) == 0; }
};

struct Cue {
    double bar;
    const char *text;
};

float rampUp(double bar, double a, double b) { return static_cast<float>(std::clamp((bar - a) / (b - a), 0.0, 1.0)); }
bool within(double bar, double a, double b) { return bar >= a && bar < b; }

void writeWav(const char *path, const std::vector<float> &l, const std::vector<float> &r) {
    FILE *file = std::fopen(path, "wb");
    if (file == nullptr) return;
    const uint32_t frames = static_cast<uint32_t>(l.size());
    const uint32_t bytes = frames * 4;
    auto u32 = [&](uint32_t v) { std::fwrite(&v, 4, 1, file); };
    auto u16 = [&](uint16_t v) { std::fwrite(&v, 2, 1, file); };
    std::fwrite("RIFF", 1, 4, file);
    u32(36 + bytes);
    std::fwrite("WAVEfmt ", 1, 8, file);
    u32(16);
    u16(1);
    u16(2);
    u32(kRate);
    u32(kRate * 4);
    u16(4);
    u16(16);
    std::fwrite("data", 1, 4, file);
    u32(bytes);
    for (uint32_t i = 0; i < frames; ++i) {
        const int16_t a = static_cast<int16_t>(std::lround(std::clamp(l[i], -1.f, 1.f) * 32767.f));
        const int16_t b = static_cast<int16_t>(std::lround(std::clamp(r[i], -1.f, 1.f) * 32767.f));
        std::fwrite(&a, 2, 1, file);
        std::fwrite(&b, 2, 1, file);
    }
    std::fclose(file);
}

}  // namespace

namespace {

// Comandos de um instante do roteiro, iguais nos dois modos (sintético e com faixa do usuário).
struct Cmd {
    int colorKind = kFilterKind;
    float colorKnob = 0.5f;
    BeatCfg beat;
    RmxCfg rmx;
    float volA = 1.f;
    float volB = 0.f;
};

struct Show {
    double bpm = 126.0;
    double totalBars = 100.0;
    std::vector<Cue> cues;
    void (*script)(double, Cmd &) = nullptr;
    // mapeia o compasso do mix para o compasso da faixa A (jump de grade)
    double (*songBar)(double) = nullptr;
    double gridOriginSec = 0.0;
};

void scriptSynthetic(double bar, Cmd &c) {
    if (within(bar, 4, 10)) {
        const int slot = static_cast<int>(bar) - 4;
        c.colorKind = slot;
        const double local = bar - std::floor(bar);
        const float swing = static_cast<float>(local < 0.5 ? local * 2.0 : (1.0 - local) * 2.0);
        c.colorKnob = slot == kFilterKind ? 0.5f - 0.47f * swing : 0.5f + 0.47f * swing;
    }
    if (within(bar, 10, 15)) {
        const int slot = static_cast<int>(bar) - 10;
        const double local = bar - std::floor(bar);
        const int effects[5] = {1, 3, 4, 8, 13};
        const float levels[5] = {0.6f, 0.7f, 0.7f, 0.88f, 0.75f};
        const float beats[5] = {0.75f, 0.75f, 1.f, 1.f, 2.f};
        c.beat.on = local < 0.5;
        c.beat.effect = effects[slot];
        c.beat.level = levels[slot];
        c.beat.beats = beats[slot];
    }
    RmxCfg &w = c.rmx;
    auto build = [&](double from, double to, int scene, int voice, int bars) {
        if (within(bar, from - 0.1, to)) {  // arma antes: o gesto quantiza para a próxima batida
            w.armed = true;
            w.xMode = 2;
            w.xOn = true;
            w.build = 1.f;
            w.voice = voice;
            w.drumBars = bars;
            w.scene = scene;
            w.bars = static_cast<float>(bars);
            w.holding = bar >= from;
        } else if (within(bar, to, to + 1)) {
            w.armed = true;
            w.scene = scene;
            w.bars = static_cast<float>(bars);
        }
    };
    build(16, 24, 1, 0, 8);
    build(40, 48, 0, 3, 8);
    if (within(bar, 64, 68)) { w.armed = true; w.scene = 2; w.bars = 4.f; w.holding = true; }
    else if (within(bar, 68, 69)) { w.armed = true; w.scene = 2; w.bars = 4.f; }
    if (within(bar, 80, 84)) { w.armed = true; w.scene = 3; w.bars = 4.f; w.holding = true; }
    else if (within(bar, 84, 85)) { w.armed = true; w.scene = 3; w.bars = 4.f; }
    const double bOrigin = 84.0;
    if (bar >= bOrigin + 4.0) {
        c.volB = 1.f;
        w.isoLow[1] = bar < bOrigin + 8.0 ? 0.f : 1.f;
        w.isoLow[0] = bar < bOrigin + 8.0 ? 1.f : 0.f;
    }
    if (bar >= bOrigin + 12.0) c.volA = 1.f - rampUp(bar, bOrigin + 12.0, bOrigin + 15.0);
}

// Roteiro na faixa do usuário (130 BPM, grade em 0,005 s). Compassos do mix -> compassos da faixa:
// 0-16 -> 16-32, 16-32 -> 32-48, 32-44 -> 48-60, 44-60 -> 100-116, 60-84 -> 116-140.
double songBarOf(double bar) {
    if (bar < 16) return 16 + bar;
    if (bar < 44) return 32 + (bar - 16);
    if (bar < 60) return 100 + (bar - 44);
    return 116 + (bar - 60);
}

void scriptSong(double bar, Cmd &c) {
    // Color FX, um tipo por compasso, nos compassos 2 a 8 do mix (a faixa já está no groove cheio).
    if (within(bar, 2, 8)) {
        const int slot = static_cast<int>(bar) - 2;
        c.colorKind = slot;
        const double local = bar - std::floor(bar);
        const float swing = static_cast<float>(local < 0.5 ? local * 2.0 : (1.0 - local) * 2.0);
        c.colorKnob = slot == kFilterKind ? 0.5f - 0.47f * swing : 0.5f + 0.47f * swing;
    }
    if (within(bar, 8, 13)) {
        const int slot = static_cast<int>(bar) - 8;
        const double local = bar - std::floor(bar);
        const int effects[5] = {1, 3, 4, 8, 13};
        const float levels[5] = {0.6f, 0.7f, 0.7f, 0.88f, 0.75f};
        const float beats[5] = {0.75f, 0.75f, 1.f, 1.f, 2.f};
        c.beat.on = local < 0.5;
        c.beat.effect = effects[slot];
        c.beat.level = levels[slot];
        c.beat.beats = beats[slot];
    }
    RmxCfg &w = c.rmx;
    auto build = [&](double from, double to, int scene, int voice, int bars) {
        if (within(bar, from - 0.1, to)) {
            w.armed = true;
            w.xMode = 2;
            w.xOn = true;
            w.build = 1.f;
            w.voice = voice;
            w.drumBars = bars;
            w.scene = scene;
            w.bars = static_cast<float>(bars);
            w.holding = bar >= from;
        } else if (within(bar, to, to + 1)) {
            w.armed = true;
            w.scene = scene;
            w.bars = static_cast<float>(bars);
        }
    };
    build(17, 21, 1, 0, 4);    // faixa 33-37: ruído subindo + bumbo, drop no 37
    build(32, 36, 0, 3, 4);    // faixa 48-52: eco up + bumbo e palma, drop no 52
    build(44, 52, 2, 0, 8);    // faixa 100-108: spiral up + bumbo, drop no 108
    build(60, 68, 3, 3, 8);    // faixa 116-124: reverb up + bumbo e palma, drop no 124
    if (bar >= 76 && bar < 82) {  // saída: filtro fecha no deck A
        c.colorKind = kFilterKind;
        c.colorKnob = 0.5f - 0.47f * rampUp(bar, 76, 82);
    }
}

}  // namespace

int main(int argc, char **argv) {
    const char *outPath = argc > 1 ? argv[1] : "house-mix.wav";
    const char *cuePath = argc > 2 ? argv[2] : "house-cues.tsv";
    const char *songPath = argc > 3 ? argv[3] : nullptr;
    const bool drumsOk = loadDrums();
    std::fprintf(stderr, "bateria gravada: %s\n", drumsOk ? "ok" : "incompleta, o motor usa a modelagem");

    Show show;
    Track a;
    Track b;
    double bOriginBar = 1.0e9;
    if (songPath != nullptr) {
        // Faixa do usuário, WAV 48 kHz 16 bit estéreo. Fica fora do git.
        FILE *file = std::fopen(songPath, "rb");
        if (file == nullptr) {
            std::fprintf(stderr, "faixa ausente %s\n", songPath);
            return 2;
        }
        std::fseek(file, 0, SEEK_END);
        const long bytes = std::ftell(file);
        std::fseek(file, 44, SEEK_SET);
        const long frames = (bytes - 44) / 4;
        std::vector<int16_t> pcm(static_cast<size_t>(frames) * 2);
        if (std::fread(pcm.data(), 2, pcm.size(), file) != pcm.size()) return 2;
        std::fclose(file);
        a.left.resize(static_cast<size_t>(frames));
        a.right.resize(static_cast<size_t>(frames));
        // −6 dB de trim: a faixa chega quente (pico 1,0) e o master precisa de espaço para os efeitos.
        for (long i = 0; i < frames; ++i) {
            a.left[static_cast<size_t>(i)] = pcm[static_cast<size_t>(i) * 2] / 32768.f * 0.5f;
            a.right[static_cast<size_t>(i)] = pcm[static_cast<size_t>(i) * 2 + 1] / 32768.f * 0.5f;
        }
        show.bpm = 130.0;
        show.totalBars = 84.0;
        show.script = scriptSong;
        show.songBar = songBarOf;
        show.gridOriginSec = 0.005;
        show.cues = {
            {0, "faixa sem efeito"},        {2, "Color SPACE"},   {3, "Color DUB ECHO"},
            {4, "Color SWEEP"},             {5, "Color NOISE"},   {6, "Color CRUSH"},
            {7, "Color FILTER"},            {8, "Beat eco"},      {9, "Beat spiral"},
            {10, "Beat reverb"},            {11, "Beat pitch"},   {12, "Beat filter ritmico"},
            {17, "BUILD 1: ruido + bumbo subindo (4 compassos)"}, {21, "DROP 1 (faixa 37)"},
            {32, "BUILD 2: eco up + bumbo e palma (4 compassos)"}, {36, "DROP 2 (faixa 52), cauda do eco"},
            {44, "BUILD 3: spiral up + bumbo (8 compassos)"},      {52, "DROP 3 (faixa 108), cauda do spiral"},
            {60, "BUILD 4: reverb up + bumbo e palma (8 compassos)"}, {68, "DROP 4 (faixa 124), cauda da reverb"},
            {76, "saida: filtro fecha"},
        };
    } else {
        a = synth({100, 57, 0xA110u, planA});
        b = synth({24, 53, 0xB220u, planB});
        bOriginBar = 84.0;
        show.bpm = kBpm;
        show.totalBars = 100.0;
        show.script = scriptSynthetic;
        show.songBar = [](double bar) { return bar; };
        show.cues = {
            {0, "A sozinha, referencia"}, {4, "Color SPACE"}, {5, "Color DUB ECHO"}, {6, "Color SWEEP"},
            {7, "Color NOISE"}, {8, "Color CRUSH"}, {9, "Color FILTER"}, {10, "Beat FX eco"},
            {11, "Beat FX spiral"}, {12, "Beat FX reverb"}, {13, "Beat FX pitch"}, {14, "Beat FX filter ritmico"},
            {16, "BUILD 1: bumbo subindo + ruido, 8 compassos"}, {24, "DROP 1"},
            {40, "BUILD 2: eco up + bumbo e palma, 8 compassos"}, {48, "DROP 2, cauda do eco"},
            {64, "BUILD 3: spiral up, 4 compassos"}, {68, "DROP 3, cauda do spiral"},
            {80, "BUILD 4: reverb up, 4 compassos"}, {84, "DROP 4, cauda da reverb. B entra sem grave"},
            {88, "B abre"}, {92, "troca de grave A -> B"}, {96, "A sai"},
        };
    }
    const double spb = kRate * 60.0 / show.bpm;
    const double barSamples = spb * 4.0;

    deckbridge::MixerFx mix;
    deckbridge::PeakLimiter limit;
    mix.setSampleRate(kRate);
    mix.setFilter(0, 0.5f);
    mix.setFilter(1, 0.5f);
#ifndef HOUSE_LEGACY
    mix.setColorKind(0, kFilterKind);
#endif

    const long totalFrames = static_cast<long>(show.totalBars * barSamples);
    std::vector<float> outL;
    std::vector<float> outR;
    outL.reserve(static_cast<size_t>(totalFrames));
    outR.reserve(static_cast<size_t>(totalFrames));
    RmxCfg rmxCfg;
    BeatCfg beatCfg;
    bool first = true;
    int colorKind = -1;
    double peakAll = 0.0;
    long nan = 0;

    for (long i = 0; i < totalFrames; ++i) {
        const double bar = static_cast<double>(i) / barSamples;
        Cmd cmd;
        show.script(bar, cmd);
#ifndef HOUSE_LEGACY
        if (cmd.colorKind != colorKind) {
            mix.setColorKind(0, cmd.colorKind);
            colorKind = cmd.colorKind;
        }
#else
        (void)colorKind;
#endif
        mix.setFilter(0, cmd.colorKnob);
        if (first || !(cmd.beat == beatCfg)) {
            mix.setBeat(cmd.beat.effect, cmd.beat.on, cmd.beat.level, 0, static_cast<float>(show.bpm), cmd.beat.beats);
            beatCfg = cmd.beat;
        }
        const RmxCfg &want = cmd.rmx;
        if (first || !(want == rmxCfg)) {
            mix.setRmx(want.armed, want.scene, want.holding, want.holding ? 1.f : 0.f, want.bars, want.xMode, 0.25f, want.xOn,
                       -1, want.isoLow[0], 1.f, 1.f, want.isoLow[1], 1.f, 1.f, false, 0, static_cast<float>(show.bpm), want.build,
                       want.voice, want.drumBars, want.gap, want.pitch, 0);
            rmxCfg = want;
        }
        first = false;

        // Posição na faixa A: o mix pode saltar de seção, sempre em compasso inteiro.
        const double songBar = show.songBar(std::floor(bar)) + (bar - std::floor(bar));
        const long aIndex = static_cast<long>(songBar * barSamples) + (songPath != nullptr ? 0L : 0L);
        float al = 0.f, ar = 0.f, bl = 0.f, br = 0.f;
        if (aIndex >= 0 && aIndex < static_cast<long>(a.left.size())) {
            al = a.left[static_cast<size_t>(aIndex)];
            ar = a.right[static_cast<size_t>(aIndex)];
        }
        const long bIndex = i - static_cast<long>(bOriginBar * barSamples);
        if (bIndex >= 0 && bIndex < static_cast<long>(b.left.size())) {
            bl = b.left[static_cast<size_t>(bIndex)];
            br = b.right[static_cast<size_t>(bIndex)];
        }
        mix.beginSample();
        const double gridBeat = (static_cast<double>(aIndex) / kRate - show.gridOriginSec) * show.bpm / 60.0;
        mix.setDrumPhase(gridBeat, true, true);
        mix.processDeck(0, al, ar);
        mix.processDeck(1, bl, br);
        float masterL = al * cmd.volA + bl * cmd.volB;
        float masterR = ar * cmd.volA + br * cmd.volB;
        mix.processMaster(masterL, masterR);
        limit.process(masterL, masterR);
        if (!std::isfinite(masterL) || !std::isfinite(masterR)) {
            nan += 1;
            masterL = masterR = 0.f;
        }
        peakAll = std::max(peakAll, static_cast<double>(std::max(std::fabs(masterL), std::fabs(masterR))));
        outL.push_back(masterL);
        outR.push_back(masterR);
    }
    writeWav(outPath, outL, outR);
    FILE *tsv = std::fopen(cuePath, "w");
    if (tsv != nullptr) {
        for (const Cue &cue : show.cues) std::fprintf(tsv, "%.3f\t%.1f\t%s\n", cue.bar * barSamples / kRate, cue.bar, cue.text);
        std::fclose(tsv);
    }
    std::printf("frames %ld segundos %.1f pico %.3f nan %ld\n", totalFrames, static_cast<double>(totalFrames) / kRate, peakAll, nan);
    return nan == 0 ? 0 : 1;
}
