// Testes do console (curvas de fader, COMP, isolador do master, Send/Return, FX FREQUENCY e efeitos do V10).
// g++ -std=c++17 -O2 -I android/app/src/main/cpp -o console_test tools/console_test.cpp && ./console_test
#include "dsp.h"

#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

namespace {
int fails = 0;

void check(bool ok, const std::string &name) {
    std::printf("%s %s\n", ok ? "PASS" : "FAIL", name.c_str());
    if (!ok) ++fails;
}

constexpr int kRate = 48000;
constexpr double kPi = 3.14159265358979323846;

float sine(int i, float hz, float amp) { return amp * static_cast<float>(std::sin(2.0 * kPi * hz * i / kRate)); }

float rms(const std::vector<float> &x, size_t from = 0) {
    double sum = 0.0;
    for (size_t i = from; i < x.size(); ++i) sum += static_cast<double>(x[i]) * x[i];
    return x.size() > from ? static_cast<float>(std::sqrt(sum / static_cast<double>(x.size() - from))) : 0.f;
}

float db(float linear) { return 20.f * std::log10(std::max(linear, 1e-9f)); }

void faderCurves() {
    for (int curve = 0; curve < 3; ++curve) {
        bool monotonic = true;
        float last = -1.f;
        for (int i = 0; i <= 100; ++i) {
            const float g = deckbridge::channelFaderGain(i / 100.f, curve);
            if (g < last - 1e-6f) monotonic = false;
            last = g;
        }
        check(monotonic && deckbridge::channelFaderGain(0.f, curve) == 0.f && deckbridge::channelFaderGain(1.f, curve) == 1.f,
              "curva de canal " + std::to_string(curve) + ": sobe de 0 a 1 sem voltar");
    }
    // No meio do curso: a de topo fica baixa, a gradual alta, a linear no meio.
    const float steep = deckbridge::channelFaderGain(0.5f, 0);
    const float soft = deckbridge::channelFaderGain(0.5f, 1);
    const float flat = deckbridge::channelFaderGain(0.5f, 2);
    check(steep < 0.2f && soft > 0.7f && std::fabs(flat - 0.5f) < 1e-6f, "curvas de canal no meio do curso: topo < linear < gradual");
    float a = 0.f, b = 0.f;
    deckbridge::crossfaderSides(0.5f, 0, a, b);
    check(a == 1.f && b == 1.f, "crossfader lento: os dois inteiros no centro");
    deckbridge::crossfaderSides(0.f, 2, a, b);
    check(a == 1.f && b == 0.f, "crossfader de corte: na ponta só o deck 1");
    deckbridge::crossfaderSides(0.05f, 2, a, b);
    check(a == 1.f && b == 1.f, "crossfader de corte: a 5% da ponta os dois já estão inteiros");
    deckbridge::crossfaderSides(0.05f, 0, a, b);
    check(b < 0.2f, "crossfader lento: a 5% da ponta o deck 2 ainda está baixo");
}

void compressor() {
    deckbridge::ChannelComp off;
    off.set(0.f, kRate);
    bool same = true;
    for (int i = 0; i < kRate / 10; ++i) {
        float l = sine(i, 220.f, 0.9f);
        float r = l;
        const float in = l;
        off.process(l, r);
        if (l != in) same = false;
    }
    check(same, "COMP todo à esquerda: sai o som original, amostra a amostra");

    // Sinal alto e trecho baixo: com o COMP no máximo a diferença entre os dois diminui.
    deckbridge::ChannelComp full;
    full.set(1.f, kRate);
    std::vector<float> loud, quiet;
    for (int i = 0; i < kRate; ++i) {
        float l = sine(i, 220.f, 0.9f);
        float r = l;
        full.process(l, r);
        if (i > kRate / 2) loud.push_back(l);
    }
    for (int i = 0; i < kRate; ++i) {
        float l = sine(i, 220.f, 0.1f);
        float r = l;
        full.process(l, r);
        if (i > kRate / 2) quiet.push_back(l);
    }
    const float spreadIn = db(0.9f) - db(0.1f);
    const float spreadOut = db(rms(loud)) - db(rms(quiet));
    check(spreadOut < spreadIn - 6.f, "COMP no máximo: a distância entre alto e baixo cai mais de 6 dB (" +
                                          std::to_string(spreadIn) + " → " + std::to_string(spreadOut) + ")");
    check(std::isfinite(rms(loud)) && rms(loud) < 1.f, "COMP: sem NaN e sem passar de 0 dBFS no trecho alto");
}

void masterIsolator() {
    check(deckbridge::isolatorGainFromKnob(0.f) == 0.f, "isolador: todo à esquerda corta a banda");
    check(std::fabs(deckbridge::isolatorGainFromKnob(0.5f) - 1.f) < 1e-6f, "isolador: centro é 0 dB");
    check(std::fabs(db(deckbridge::isolatorGainFromKnob(1.f)) - 9.f) < 0.01f, "isolador: todo à direita é +9 dB");
    deckbridge::MixerFx mix;
    mix.setSampleRate(kRate);
    const float console[] = {2.f, 0.f, 0.f, 0.f, 1.f, 0.f, 0.5f, 0.5f};
    mix.setConsole(console, 8);
    std::vector<float> lowOut, highOut;
    for (int i = 0; i < kRate; ++i) {
        float l = sine(i, 60.f, 0.3f);
        float r = l;
        mix.processMaster(l, r);
        if (i > kRate / 2) lowOut.push_back(l);
    }
    for (int i = 0; i < kRate; ++i) {
        float l = sine(i, 5000.f, 0.3f);
        float r = l;
        mix.processMaster(l, r);
        if (i > kRate / 2) highOut.push_back(l);
    }
    check(db(rms(lowOut)) < db(0.3f / std::sqrt(2.f)) - 30.f, "isolador do master: LOW fechado tira o grave (60 Hz)");
    check(std::fabs(db(rms(highOut)) - db(0.3f / std::sqrt(2.f))) < 1.5f, "isolador do master: o agudo passa a 0 dB");
    const float offConsole[] = {2.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f};
    mix.setConsole(offConsole, 8);
    std::vector<float> back;
    for (int i = 0; i < kRate; ++i) {
        float l = sine(i, 60.f, 0.3f);
        float r = l;
        mix.processMaster(l, r);
        if (i > kRate / 2) back.push_back(l);
    }
    check(std::fabs(db(rms(back)) - db(0.3f / std::sqrt(2.f))) < 0.5f, "isolador desligado: o grave volta inteiro, mesmo com os knobs fechados");
}

// Configura o Beat FX como o app: efeito, ligado, nível 0..1, canal 2 (master), BPM e fração.
deckbridge::MixerFx beatMixer(int effect, float level, float beats, bool on = true) {
    deckbridge::MixerFx mix;
    mix.setSampleRate(kRate);
    mix.setBeat(effect, on, level, 2, 120.f, beats);
    return mix;
}

void delayRepeatsOnce() {
    // 120 BPM, fração 1/2: 0,25 s. Um clique: sai a repetição em 0,25 s e nada em 0,5 s.
    auto mix = beatMixer(16, 0.8f, 0.5f);
    std::vector<float> out;
    for (int i = 0; i < kRate; ++i) {
        float l = i == 100 ? 1.f : 0.f;
        float r = l;
        mix.processMaster(l, r);
        out.push_back(l);
    }
    auto peakAround = [&](int center) {
        float peak = 0.f;
        for (int i = center - 200; i < center + 200; ++i) peak = std::max(peak, std::fabs(out[i]));
        return peak;
    };
    const int step = kRate / 4;
    check(peakAround(100 + step) > 0.2f, "DELAY: a repetição chega em 1/2 batida (" + std::to_string(peakAround(100 + step)) + ")");
    check(peakAround(100 + 2 * step) < 0.01f, "DELAY: não há segunda repetição (" + std::to_string(peakAround(100 + 2 * step)) + ")");
}

// Energia numa frequência (Goertzel).
float toneEnergy(const std::vector<float> &x, float hz) {
    const double w = 2.0 * kPi * hz / kRate;
    const double c = 2.0 * std::cos(w);
    double s1 = 0.0, s2 = 0.0;
    for (float v : x) {
        const double s0 = v + c * s1 - s2;
        s2 = s1;
        s1 = s0;
    }
    return static_cast<float>(s1 * s1 + s2 * s2 - c * s1 * s2);
}

void shimmerSparkles() {
    std::vector<float> tails[2];
    const int effects[2] = {4, 15};
    float maxAbs = 0.f;
    for (int k = 0; k < 2; ++k) {
        auto mix = beatMixer(effects[k], 0.8f, 1.f);
        for (int i = 0; i < kRate * 4; ++i) {
            // Meio segundo de lá (440 Hz) e depois silêncio: a cauda é o efeito.
            float l = i < kRate / 2 ? sine(i, 440.f, 0.4f) : 0.f;
            float r = l;
            mix.processMaster(l, r);
            if (i > kRate / 2 + kRate / 4 && i < kRate * 2) tails[k].push_back(l);
            if (k == 1) maxAbs = std::max(maxAbs, std::fabs(l));
        }
    }
    // Oitava acima contra a nota tocada (440 Hz) na cauda: o SHIMMER tem muito mais oitava que o REVERB. A troca de grãos
    // do deslocador espalha a oitava em bandas laterais (cerca de ±24 Hz), então soma-se a faixa em volta.
    auto band = [&](const std::vector<float> &x, float center) {
        float sum = 0.f;
        for (float hz = center - 60.f; hz <= center + 60.f; hz += 4.f) sum += toneEnergy(x, hz);
        return sum;
    };
    const float verbOct = band(tails[0], 880.f) / std::max(1e-12f, band(tails[0], 440.f));
    const float shimOct = band(tails[1], 880.f) / std::max(1e-12f, band(tails[1], 440.f));
    check(shimOct > verbOct * 10.f && shimOct > 0.05f, "SHIMMER: a cauda ganha a oitava de cima (880/440: " +
                                                           std::to_string(verbOct) + " → " + std::to_string(shimOct) + ")");
    check(rms(tails[1]) > 0.01f && maxAbs < 1.f, "SHIMMER: tem cauda e não dispara");
    // Depois de 4 s sem entrada a cauda está caindo (laço estável).
    auto mix = beatMixer(15, 1.f, 1.f);
    std::vector<float> early, late;
    for (int i = 0; i < kRate * 8; ++i) {
        float l = i < kRate / 2 ? sine(i, 440.f, 0.4f) : 0.f;
        float r = l;
        mix.processMaster(l, r);
        if (i > kRate && i < kRate * 2) early.push_back(l);
        if (i > kRate * 7) late.push_back(l);
    }
    check(rms(late) < rms(early) * 0.5f, "SHIMMER no máximo: o laço com oitava não se realimenta para sempre");
}

void spiralBendsWithTempo() {
    // Mudar a fração: no Spiral o atraso ainda está a caminho 0,1 s depois (o tom das repetições muda nesse trajeto);
    // no Echo já chegou.
    for (int effect : {3, 1}) {
        deckbridge::BeatInsert fx;
        fx.configure(effect, true, 0.7f, 120.f, 1.f, kRate);
        for (int i = 0; i < kRate / 10; ++i) {
            float l = sine(i, 220.f, 0.3f);
            float r = l;
            fx.process(l, r);
        }
        fx.configure(effect, true, 0.7f, 120.f, 0.5f, kRate);
        for (int i = 0; i < kRate / 10; ++i) {
            float l = sine(i, 220.f, 0.3f);
            float r = l;
            fx.process(l, r);
        }
        const float target = static_cast<float>(fx.delaySamples());
        const float start = kRate * 0.5f;
        const float travelled = (start - fx.delaySm) / (start - target);
        if (effect == 3) {
            check(travelled > 0.1f && travelled < 0.4f, "SPIRAL: 0,1 s depois de mudar a fração o atraso fez " +
                                                           std::to_string(travelled * 100.f) + "% do trajeto (desliza como fita)");
        } else {
            check(travelled > 0.8f, "ECHO: o atraso chega rápido ao novo tempo");
        }
    }
}

void fxFrequencyBands() {
    // Eco só no HI: o grave (60 Hz) passa sem cauda; com as três bandas o grave ecoa.
    auto tailOf = [&](bool low, bool mid, bool high) {
        deckbridge::MixerFx mix;
        mix.setSampleRate(kRate);
        const float console[] = {2.f, 0.f, 0.f, 0.f, 0.f, 0.5f, 0.5f, 0.5f, 0.f, 0.f, -1.f, 0.5f, 0.5f, 0.5f, 0.7f,
                                 low ? 1.f : 0.f, mid ? 1.f : 0.f, high ? 1.f : 0.f};
        mix.setConsole(console, 18);
        mix.setBeat(1, true, 0.8f, 2, 120.f, 0.5f);
        std::vector<float> tail;
        for (int i = 0; i < kRate * 2; ++i) {
            float l = i < kRate / 2 ? sine(i, 60.f, 0.4f) : 0.f;
            float r = l;
            mix.processMaster(l, r);
            if (i > kRate / 2 + kRate / 20) tail.push_back(l);
        }
        return rms(tail);
    };
    const float all = tailOf(true, true, true);
    const float highOnly = tailOf(false, false, true);
    check(all > 0.02f, "FX FREQUENCY: com as três bandas o grave ecoa (" + std::to_string(all) + ")");
    check(highOnly < all * 0.05f, "FX FREQUENCY: só HI, o grave sai sem eco (" + std::to_string(highOnly) + ")");
    // Bandas apagadas: o original passa inteiro (60 Hz no nível de entrada durante o trecho com sinal).
    deckbridge::MixerFx mix;
    mix.setSampleRate(kRate);
    const float console[] = {2.f, 0.f, 0.f, 0.f, 0.f, 0.5f, 0.5f, 0.5f, 0.f, 0.f, -1.f, 0.5f, 0.5f, 0.5f, 0.7f, 0.f, 0.f, 1.f};
    mix.setConsole(console, 18);
    mix.setBeat(5, true, 1.f, 2, 120.f, 0.25f);
    std::vector<float> body;
    for (int i = 0; i < kRate; ++i) {
        float l = sine(i, 60.f, 0.4f);
        float r = l;
        mix.processMaster(l, r);
        if (i > kRate / 4) body.push_back(l);
    }
    check(std::fabs(db(rms(body)) - db(0.4f / std::sqrt(2.f))) < 1.f, "FX FREQUENCY: TRANS só no HI não corta o grave");
}
}  // namespace

int main() {
    faderCurves();
    compressor();
    masterIsolator();
    delayRepeatsOnce();
    shimmerSparkles();
    spiralBendsWithTempo();
    fxFrequencyBands();
    std::printf("FAILS %d\n", fails);
    return fails == 0 ? 0 : 1;
}
