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
}  // namespace

int main() {
    faderCurves();
    compressor();
    masterIsolator();
    std::printf("FAILS %d\n", fails);
    return fails == 0 ? 0 : 1;
}
