// Custo de CPU do caminho de efeitos no pior caso: EQ nos dois decks, Color FX SPACE (reverb) nos dois,
// Beat FX Reverb, cena RMX Reverb UP segurada e drum build rodando. Mede por bloco de 256 quadros contra o
// orçamento de tempo real. Host x86, não o tablet: o tablet é várias vezes mais lento por núcleo.
//
// g++ -std=c++17 -O2 -I android/app/src/main/cpp -o cpu_bench tools/cpu_bench.cpp && ./cpu_bench
#include "dsp.h"
#include "drum_load.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <vector>

int main() {
    constexpr int kRate = 48000;
    constexpr int kBlock = 256;
    const double budgetUs = 1e6 * kBlock / kRate;
    deckbridge::MixerFx mix;
    mix.setSampleRate(kRate);
    mix.setColorKind(0, deckbridge::kColorSpace);
    mix.setColorKind(1, deckbridge::kColorDubEcho);
    mix.setFilter(0, 0.95f);
    mix.setFilter(1, 0.95f);
    mix.setEq(0, 0.2f, 0.8f, 0.3f);
    mix.setEq(1, 0.7f, 0.1f, 0.9f);
    mix.setBeat(4, true, 0.9f, 2, 130.f, 1.f);
    // Cena RMX segurada + roll de bateria na grade.
    mix.setRmx(true, 3, true, 1.f, 8.f, 2, 0.25f, true, -1, 1.f, 1.f, 1.f, 1.f, 1.f, 1.f, false, 0, 130.f, 1.f, 3, 8, true, true, 0);
    mix.beat.on = false;
    uint32_t state = 12345;
    std::vector<double> blocks;
    double beat = 0.0;
    for (int b = 0; b < kRate * 20 / kBlock; ++b) {
        const auto t0 = std::chrono::steady_clock::now();
        for (int i = 0; i < kBlock; ++i) {
            float a = deckbridge::noiseSample(state) * 0.3f;
            float c = deckbridge::noiseSample(state) * 0.3f;
            float ar = a, cr = c;
            beat += 130.0 / 60.0 / kRate;
            mix.beginSample();
            mix.setDrumPhase(beat, true, true);
            mix.processDeck(0, a, ar);
            mix.processDeck(1, c, cr);
            float l = a + c;
            float r = ar + cr;
            mix.processMaster(l, r);
        }
        const auto t1 = std::chrono::steady_clock::now();
        blocks.push_back(std::chrono::duration<double, std::micro>(t1 - t0).count());
    }
    {
        size_t worst = 0;
        for (size_t i = 0; i < blocks.size(); ++i) if (blocks[i] > blocks[worst]) worst = i;
        std::printf("pior bloco foi o %zu (%.2f s)\n", worst, worst * 256.0 / 48000.0);
    }
    std::sort(blocks.begin(), blocks.end());
    double sum = 0;
    for (double v : blocks) sum += v;
    std::printf("blocos %zu  media %.1f us  p99 %.1f us  maximo %.1f us  orcamento %.0f us\n", blocks.size(), sum / blocks.size(),
                blocks[blocks.size() * 99 / 100], blocks.back(), budgetUs);
    std::printf("uso medio %.1f%% do orcamento; pior bloco %.1f%%\n", 100.0 * sum / blocks.size() / budgetUs, 100.0 * blocks.back() / budgetUs);
    return 0;
}
