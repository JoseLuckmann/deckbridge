// Testes de DSP do RMX, dos Sound Color FX e do riser de bumbo, no host.
// g++ -std=c++17 -O2 -I android/app/src/main/cpp -o fx_rmx_test tools/fx_rmx_test.cpp && ./fx_rmx_test
// Rodar na raiz do repositório (os FLAC de bateria saem de android/app/src/main/assets/drums/).
#include "dsp.h"
#include "drum_load.h"

#include <cmath>
#include <complex>
#include <cstdio>
#include <string>
#include <vector>

namespace {

int failures = 0;
constexpr int kRate = 48000;

void check(bool ok, const std::string &name) {
    std::printf("%s %s\n", ok ? "PASS" : "FAIL", name.c_str());
    if (!ok) failures += 1;
}

void fft(std::vector<std::complex<double>> &a) {
    const size_t n = a.size();
    for (size_t i = 1, j = 0; i < n; ++i) {
        size_t bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(a[i], a[j]);
    }
    for (size_t len = 2; len <= n; len <<= 1) {
        const double ang = -2.0 * M_PI / static_cast<double>(len);
        const std::complex<double> wlen(std::cos(ang), std::sin(ang));
        for (size_t i = 0; i < n; i += len) {
            std::complex<double> w(1.0);
            for (size_t j = 0; j < len / 2; ++j) {
                const auto u = a[i + j];
                const auto v = a[i + j + len / 2] * w;
                a[i + j] = u + v;
                a[i + j + len / 2] = u - v;
                w *= wlen;
            }
        }
    }
}

// Frequência do maior pico entre lo e hi, em Hz, com janela de Hann de 16384 pontos.
double peakHz(const std::vector<float> &x, size_t from, double lo, double hi, double *level = nullptr) {
    const size_t n = 16384;
    std::vector<std::complex<double>> a(n);
    for (size_t i = 0; i < n; ++i) {
        const double w = 0.5 - 0.5 * std::cos(2.0 * M_PI * static_cast<double>(i) / static_cast<double>(n - 1));
        a[i] = (from + i < x.size() ? x[from + i] : 0.f) * w;
    }
    fft(a);
    double best = 0.0;
    size_t at = 0;
    for (size_t k = 1; k < n / 2; ++k) {
        const double hz = static_cast<double>(k) * kRate / static_cast<double>(n);
        if (hz < lo || hz > hi) continue;
        const double mag = std::abs(a[k]);
        if (mag > best) {
            best = mag;
            at = k;
        }
    }
    if (level != nullptr) *level = 20.0 * std::log10(best / static_cast<double>(n) + 1e-12);
    return static_cast<double>(at) * kRate / static_cast<double>(n);
}

double rms(const std::vector<float> &x, size_t a, size_t b) {
    double sum = 0.0;
    b = std::min(b, x.size());
    for (size_t i = a; i < b; ++i) sum += static_cast<double>(x[i]) * x[i];
    return b > a ? std::sqrt(sum / static_cast<double>(b - a)) : 0.0;
}

double db(double v) { return 20.0 * std::log10(v + 1e-12); }

bool loadDrums() {
    const char *names[] = {"kick.flac", "clap.flac", "snare.flac", "hat-closed.flac", "hat-open.flac"};
    for (int voice = 0; voice < 5; ++voice) {
        const std::string path = std::string("android/app/src/main/assets/drums/") + names[voice];
        FILE *file = std::fopen(path.c_str(), "rb");
        if (file == nullptr) return false;
        std::fseek(file, 0, SEEK_END);
        const long bytes = std::ftell(file);
        std::fseek(file, 0, SEEK_SET);
        std::vector<uint8_t> data(static_cast<size_t>(bytes));
        if (std::fread(data.data(), 1, data.size(), file) != data.size()) return false;
        std::fclose(file);
        if (!deckbridge::loadDrumFlac(data.data(), data.size(), voice)) return false;
    }
    return true;
}

// Toque de 440 Hz de 0,12 s no começo de cada batida de 120 BPM.
float tonePulse(long i, double bpm) {
    const double beat = deckbridge::samplesPerBeat(bpm, kRate);
    const double inBeat = std::fmod(static_cast<double>(i), beat);
    if (inBeat > 0.12 * kRate) return 0.f;
    const double env = std::sin(M_PI * inBeat / (0.12 * kRate));
    return static_cast<float>(0.4 * env * std::sin(2.0 * M_PI * 440.0 * static_cast<double>(i) / kRate));
}

// Rajada de ruído branco de 0,12 s no começo de cada batida.
float noisePulse(long i, double bpm) {
    static uint32_t state = 0x2545F491u;
    const double beat = deckbridge::samplesPerBeat(bpm, kRate);
    const double inBeat = std::fmod(static_cast<double>(i), beat);
    const float n = deckbridge::noiseSample(state);
    if (inBeat > 0.12 * kRate) return 0.f;
    return 0.35f * n * static_cast<float>(std::sin(M_PI * inBeat / (0.12 * kRate)));
}

void spiralRises() {
    deckbridge::SceneFx scene;
    scene.scene = 2;
    scene.bars = 1.f;
    scene.bpm = 120.f;
    scene.sampleRate = kRate;
    scene.setCeiling(1.f);
    scene.prepare();
    scene.setHold(true);
    const long total = static_cast<long>(kRate * 6.0);
    std::vector<float> out(static_cast<size_t>(total));
    for (long i = 0; i < total; ++i) {
        // Só o primeiro toque entra: o resto é o que o laço devolve.
        float l = i < 24000 ? tonePulse(i, 120.0) : 0.f;
        float r = l;
        scene.process(l, r);
        out[static_cast<size_t>(i)] = l;
    }
    // Janelas depois do fim da rampa (1 compasso = 2 s). Cada repetição sobe mais.
    const double early = peakHz(out, static_cast<size_t>(kRate * 2.4), 300.0, 4000.0);
    const double late = peakHz(out, static_cast<size_t>(kRate * 4.6), 300.0, 4000.0);
    std::printf("INFO spiral up pico cedo %.0f Hz, tarde %.0f Hz\n", early, late);
    check(early > 470.0, "spiral up: as repeticoes ja sobem de 440 Hz");
    check(late > early * 1.05, "spiral up: cada volta do laco e mais aguda que a anterior");
}

// Solta o RMX numa batida e mede a cauda: eco e reverb continuam depois do drop, depois sai limpo.
void releaseTail(int sceneId, const char *name) {
    deckbridge::MixerFx mix;
    mix.setSampleRate(kRate);
    const double spb = deckbridge::samplesPerBeat(120.0, kRate);
    auto cfg = [&](bool holding) {
        mix.setRmx(true, sceneId, holding, holding ? 1.f : 0.f, 1.f, 0, 0.25f, false, -1, 1.f, 1.f, 1.f, 1.f, 1.f, 1.f, false, 0, 120.f);
    };
    std::vector<float> out;
    const long hold = static_cast<long>(spb * 5.0);
    long i = 0;
    cfg(true);
    for (; i < hold; ++i) {
        mix.setDrumPhase(static_cast<double>(i) / spb, true, true);
        float l = noisePulse(i, 120.0);
        float r = l;
        mix.processDeck(0, l, r);
        mix.processMaster(l, r);
    }
    cfg(false);
    const long releaseAt = i;
    const long after = static_cast<long>(spb * 16.0);
    for (long k = 0; k < after; ++k, ++i) {
        mix.setDrumPhase(static_cast<double>(i) / spb, true, true);
        float l = 0.f;
        float r = 0.f;
        mix.processDeck(0, l, r);
        mix.processMaster(l, r);
        out.push_back(l);
    }
    (void)releaseAt;
    // Entrada silenciosa depois da soltura: tudo o que sai é cauda.
    const double beatSamples = spb;
    const double first = rms(out, static_cast<size_t>(beatSamples * 1.2), static_cast<size_t>(beatSamples * 2.2));
    const double late = rms(out, static_cast<size_t>(beatSamples * 12.0), static_cast<size_t>(beatSamples * 16.0));
    std::printf("INFO %s cauda 1a batida %.1f dB, ultimas duas %.1f dB\n", name, db(first), db(late));
    check(db(first) > -52.0, std::string(name) + ": a cauda continua depois da soltura");
    check(db(late) < db(first) - 10.0 && db(late) < -70.0, std::string(name) + ": a cauda morre e a cena sai limpa");
    check(!mix.scene.releasing && mix.scene.intensity == 0.f, std::string(name) + ": a cena volta ao repouso");
}

// A soltura cai na batida da grade, não no relógio interno.
void releaseOnGrid() {
    deckbridge::SceneFx scene;
    scene.scene = 3;
    scene.bars = 1.f;
    scene.bpm = 120.f;
    scene.sampleRate = kRate;
    scene.tailBeats = 1.5f;
    scene.setCeiling(1.f);
    scene.prepare();
    scene.setHold(true);
    const double spb = deckbridge::samplesPerBeat(120.0, kRate);
    const double offset = 0.37;  // a fase da grade não coincide com o relógio da cena
    long i = 0;
    for (; i < static_cast<long>(spb * 4); ++i) {
        scene.setGrid(static_cast<double>(i) / spb + offset, true);
        float l = 0.1f, r = 0.1f;
        scene.process(l, r);
    }
    scene.setHold(false);
    long startedAt = -1;
    for (long k = 0; k < static_cast<long>(spb * 3); ++k, ++i) {
        scene.setGrid(static_cast<double>(i) / spb + offset, true);
        float l = 0.1f, r = 0.1f;
        scene.process(l, r);
        if (startedAt < 0 && scene.tailing) startedAt = i;
    }
    const double beatAt = static_cast<double>(startedAt) / spb + offset;
    const double frac = beatAt - std::floor(beatAt);
    check(startedAt > 0 && (frac < 0.002 || frac > 0.998), "soltura do RMX cai na batida da grade");
}

void kickRiser() {
    const bool ok = loadDrums();
    check(ok, "bateria gravada carregou");
    deckbridge::XPad pad;
    pad.followGrid = true;
    pad.hats = false;
    pad.layerHits = false;
    pad.set(2, 0.25f, true, 126.f, kRate, 1.f, 0, 8, true, true);
    const double spb = deckbridge::samplesPerBeat(126.0, kRate);
    const long total = static_cast<long>(spb * 4.0 * 9.0);
    std::vector<float> out(static_cast<size_t>(total));
    std::vector<float> music(static_cast<size_t>(total));
    std::vector<float> dryRef(static_cast<size_t>(total));
    for (long i = 0; i < total; ++i) {
        pad.setPhase(static_cast<double>(i) / spb + 63.9, true);
        const float tone = 0.f;  // entrada muda: só o que o pad gera
        dryRef[static_cast<size_t>(i)] = tone;
        float l = tone;
        float r = tone;
        pad.process(l, r);
        out[static_cast<size_t>(i)] = l;
        music[static_cast<size_t>(i)] = tone;
    }
    const double barSamples = spb * 4.0;
    // O gesto começa em 64,0 (0,1 de batida depois do começo do render) e dura oito compassos.
    const size_t origin = static_cast<size_t>(spb * 0.1);
    // Afinação: a taxa de leitura do bumbo no primeiro e no último golpe (lida dos disparos ativos).
    (void)origin;
    float firstRate = 0.f;
    float lastRate = 0.f;
    bool monotonic = true;
    {
        deckbridge::XPad probe;
        probe.followGrid = true;
        probe.hats = false;
        probe.layerHits = false;
        probe.set(2, 0.25f, true, 126.f, kRate, 1.f, 0, 8, true, true);
        float previous = 0.f;
        for (long i = 0; i < total; ++i) {
            probe.setPhase(static_cast<double>(i) / spb + 63.9, true);
            float l = 0.f, r = 0.f;
            probe.process(l, r);
            for (const auto &shot : probe.shots) {
                if (!shot.on || shot.cursor > 2.0) continue;  // golpe recém-disparado
                if (firstRate == 0.f) firstRate = shot.rate;
                if (shot.rate + 1e-4f < previous) monotonic = false;
                previous = shot.rate;
                lastRate = shot.rate;
            }
        }
    }
    std::printf("INFO bumbo: taxa %.3f no primeiro golpe, %.3f no ultimo (%.1f semitons)\n", firstRate, lastRate,
                12.0 * std::log2(lastRate / std::max(0.01f, firstRate)));
    check(firstRate > 0.99f && firstRate < 1.02f && lastRate > 1.4f, "bumbo sobe de afinacao devagar, de 1,0 ate cerca de +8 semitons");
    check(monotonic, "a afinacao do bumbo nunca desce no gesto");
    // O grave de 90 Hz da música sai: a energia a 90 Hz no fim é bem menor que no começo, descontados os golpes.
    const double musicStart = rms(out, origin, origin + static_cast<size_t>(barSamples));
    const double musicEnd = rms(out, origin + static_cast<size_t>(barSamples * 7.0), origin + static_cast<size_t>(barSamples * 7.8));
    std::printf("INFO RMS inicio %.1f dB, fim %.1f dB\n", db(musicStart), db(musicEnd));
    check(db(musicEnd) > db(musicStart) + 2.0, "a subida cresce em volume");
    // Último meio tempo: a música cede e sobra o riser.
    const size_t endBeatSample = origin + static_cast<size_t>(barSamples * 8.0);
    const double gapRms = rms(out, endBeatSample - static_cast<size_t>(spb * 0.4), endBeatSample - static_cast<size_t>(spb * 0.05));
    const double preRms = rms(out, endBeatSample - static_cast<size_t>(spb * 1.4), endBeatSample - static_cast<size_t>(spb * 0.6));
    std::printf("INFO silencio: %.1f dB contra %.1f dB antes\n", db(gapRms), db(preRms));
    check(db(gapRms) < db(preRms) - 6.0, "ha um respiro antes do drop");
    // Impacto no drop: grave nos 400 ms seguintes.
    const double impactRms = rms(out, endBeatSample + 200, endBeatSample + static_cast<size_t>(kRate * 0.4));
    std::printf("INFO impacto: %.1f dB\n", db(impactRms));
    check(db(impactRms) > -40.0, "impacto no drop");
    // O ruído do riser sobe de centro: mais energia acima de 4 kHz no fim.
    double hiEnd = 0.0;
    peakHz(out, origin + static_cast<size_t>(barSamples * 7.0), 4000.0, 16000.0, &hiEnd);
    double hiStart = 0.0;
    peakHz(out, origin, 4000.0, 16000.0, &hiStart);
    std::printf("INFO ruido >4 kHz: %.1f dB no inicio, %.1f dB no fim\n", hiStart, hiEnd);
    check(hiEnd > hiStart + 6.0, "o ruido do riser abre para os agudos");
    check(deckbridge::finiteBuffer(out), "riser finito e sem estouro");
}

void colorKinds() {
    const char *names[] = {"SPACE", "DUB ECHO", "SWEEP", "NOISE", "CRUSH", "FILTER"};
    for (int kind = 0; kind < 6; ++kind) {
        deckbridge::MixerFx mix;
        mix.setSampleRate(kRate);
        mix.setColorKind(0, kind);
        mix.setFilter(0, 0.5f);
        const long n = static_cast<long>(kRate * 1.5);
        std::vector<float> dry(static_cast<size_t>(n));
        std::vector<float> centre(static_cast<size_t>(n));
        for (long i = 0; i < n; ++i) {
            dry[static_cast<size_t>(i)] = tonePulse(i, 120.0);
            float l = dry[static_cast<size_t>(i)];
            float r = l;
            mix.processDeck(0, l, r);
            centre[static_cast<size_t>(i)] = l;
        }
        check(deckbridge::diffRms(dry, centre) < 1e-4f, std::string(names[kind]) + ": centro deixa o som original");
        for (float knob : {0.04f, 0.96f}) {
            deckbridge::MixerFx m2;
            m2.setSampleRate(kRate);
            m2.setColorKind(0, kind);
            m2.setFilter(0, knob);
            std::vector<float> wet(static_cast<size_t>(n));
            for (long i = 0; i < n; ++i) {
                float l = dry[static_cast<size_t>(i)];
                float r = l;
                m2.processDeck(0, l, r);
                wet[static_cast<size_t>(i)] = l;
            }
            check(deckbridge::finiteBuffer(wet), std::string(names[kind]) + (knob < 0.5f ? " esquerda" : " direita") + ": finito");
            check(deckbridge::diffRms(dry, wet) > 0.01f, std::string(names[kind]) + (knob < 0.5f ? " esquerda" : " direita") + ": muda o som");
        }
    }
    // DUB ECHO e SPACE deixam cauda quando o knob volta ao centro.
    for (int kind : {0, 1}) {
        deckbridge::MixerFx mix;
        mix.setSampleRate(kRate);
        mix.setColorKind(0, kind);
        mix.setFilter(0, 0.95f);
        const long on = static_cast<long>(kRate * 2.0);
        for (long i = 0; i < on; ++i) {
            float l = tonePulse(i, 120.0);
            float r = l;
            mix.processDeck(0, l, r);
        }
        mix.setFilter(0, 0.5f);
        std::vector<float> tail;
        for (long i = 0; i < static_cast<long>(kRate * 4.0); ++i) {
            float l = 0.f;
            float r = 0.f;
            mix.processDeck(0, l, r);
            tail.push_back(l);
        }
        const double early = rms(tail, 0, static_cast<size_t>(kRate * 0.8));
        const double late = rms(tail, static_cast<size_t>(kRate * 3.2), static_cast<size_t>(kRate * 4.0));
        std::printf("INFO %s cauda %.1f dB -> %.1f dB\n", names[kind], db(early), db(late));
        check(db(early) > -60.0 && db(late) < db(early) - 6.0, std::string(names[kind]) + ": cauda ao voltar ao centro, depois some");
    }
    // Trocar de tipo no meio do som não estala nem gera NaN.
    deckbridge::MixerFx mix;
    mix.setSampleRate(kRate);
    mix.setFilter(0, 0.9f);
    float peak = 0.f;
    bool finite = true;
    for (long i = 0; i < kRate * 6; ++i) {
        if (i % kRate == 0) mix.setColorKind(0, static_cast<int>((i / kRate) % 6));
        float l = tonePulse(i, 120.0);
        float r = l;
        mix.processDeck(0, l, r);
        peak = std::max(peak, std::fabs(l));
        if (!std::isfinite(l)) finite = false;
    }
    check(finite && peak < 1.0f, "trocar o tipo de Color FX em andamento fica finito");
}

void beatFxNew() {
    // Pitch: com o knob todo à direita o toque de 440 Hz sai uma oitava acima.
    deckbridge::BeatInsert fx;
    fx.configure(8, true, 1.0f, 120.f, 1.f, kRate);
    const long n = static_cast<long>(kRate * 1.5);
    std::vector<float> out(static_cast<size_t>(n));
    for (long i = 0; i < n; ++i) {
        float l = static_cast<float>(0.4 * std::sin(2.0 * M_PI * 440.0 * static_cast<double>(i) / kRate));
        float r = l;
        fx.process(l, r);
        out[static_cast<size_t>(i)] = l;
    }
    double wet = 0.0;
    const double hz = peakHz(out, static_cast<size_t>(kRate * 0.6), 300.0, 1500.0, &wet);
    std::printf("INFO pitch +1 oitava: pico em %.0f Hz\n", hz);
    check(std::fabs(hz - 880.0) < 40.0, "Pitch: knob a direita sobe uma oitava");
    // Spiral: cada repetição sobe.
    deckbridge::BeatInsert sp;
    sp.configure(3, true, 0.8f, 120.f, 0.75f, kRate);
    const long m = static_cast<long>(kRate * 5.0);
    std::vector<float> o2(static_cast<size_t>(m));
    for (long i = 0; i < m; ++i) {
        float l = i < 24000 ? tonePulse(i, 120.0) : 0.f;
        float r = l;
        sp.process(l, r);
        o2[static_cast<size_t>(i)] = l;
    }
    const double first = peakHz(o2, static_cast<size_t>(kRate * 0.8), 300.0, 4000.0);
    const double third = peakHz(o2, static_cast<size_t>(kRate * 2.6), 300.0, 6000.0);
    std::printf("INFO Beat spiral: repeticao 1 %.0f Hz, repeticao 3 %.0f Hz\n", first, third);
    check(third > first * 1.04, "Beat FX Spiral: as repeticoes sobem de tom");
    // Helix travado: com o knob todo à direita o laço continua depois que a entrada para.
    deckbridge::BeatInsert hx;
    hx.configure(11, true, 0.7f, 120.f, 1.f, kRate);
    std::vector<float> o3;
    for (long i = 0; i < kRate * 8; ++i) {
        if (i == kRate * 2) hx.configure(11, true, 1.0f, 120.f, 1.f, kRate);  // sobe o knob: trava o que está no laço
        float l = i < kRate * 2 ? noisePulse(i, 120.0) : 0.f;
        float r = l;
        hx.process(l, r);
        o3.push_back(l);
    }
    const double held = rms(o3, static_cast<size_t>(kRate * 6.0), static_cast<size_t>(kRate * 8.0));
    std::printf("INFO Helix travado: %.1f dB depois de 6 s\n", db(held));
    check(db(held) > -50.0, "Helix: knob a direita trava o som no laco");
}

}  // namespace

// Balanço do Roll: esquerda = original, direita = roll; o padrão 0,5 deixa o roll dominar.
void rollBalanceCurve() {
    using deckbridge::rollMixFromLevel;
    check(rollMixFromLevel(0.f) == 0.f, "roll: knob todo a esquerda e so o original");
    check(rollMixFromLevel(1.f) == 1.f, "roll: knob todo a direita e so o roll");
    check(std::fabs(rollMixFromLevel(0.5f) - 0.7f) < 1e-6f, "roll: o padrao 0,5 da 70% de roll");
    float last = -1.f;
    bool monotonic = true;
    for (int i = 0; i <= 100; ++i) {
        const float value = rollMixFromLevel(i / 100.f);
        if (value + 1e-6f < last) monotonic = false;
        last = value;
    }
    check(monotonic, "roll: o balanco sobe com o knob");
    check(deckbridge::rollBalance(1.f, -1.f, 0.f) == -1.f && deckbridge::rollBalance(1.f, -1.f, 1.f) == 1.f &&
              std::fabs(deckbridge::rollBalance(1.f, -1.f, 0.5f)) < 1e-6f,
          "roll: a mistura vai do ao vivo ao enrolado");
}

// Scratch: o deslocamento total é a soma dos giros, para a frente e para trás, e a velocidade troca de sinal.
void scratchFollows() {
    deckbridge::ScratchFollower follower;
    follower.touch(true);
    double position = 0.0;
    double minRate = 0.0;
    double maxRate = 0.0;
    const int rate = 48000;
    for (int stroke = 0; stroke < 4; ++stroke) {
        const double dir = stroke % 2 == 0 ? 1.0 : -1.0;
        for (int message = 0; message < 12; ++message) {
            follower.move(dir * 0.015 * rate);
            for (int i = 0; i < rate / 100; ++i) {
                const double step = follower.step(rate);
                position += step;
                minRate = std::min(minRate, step);
                maxRate = std::max(maxRate, step);
            }
        }
    }
    for (int i = 0; i < rate / 5; ++i) position += follower.step(rate);
    check(std::fabs(position) < 1.0, "scratch: dois vai e vem voltam ao ponto de partida");
    check(maxRate > 1.0 && minRate < -1.0, "scratch: a velocidade passa de 1x para a frente e para trás");
    follower.touch(false);
    check(follower.step(rate) == 0.0, "scratch: solto, o prato não move a faixa");
}

// Backspin pelo prato: giro rápido para trás e soltar. A faixa segue para trás pela inércia, freia, passa por zero
// e volta a 1x sozinha; o prato seguro de novo para o giro solto na hora.
void backspinCoasts() {
    const int rate = 48000;
    deckbridge::ScratchFollower follower;
    follower.touch(true);
    double position = 0.0;
    for (int message = 0; message < 15; ++message) {
        follower.move(-0.06 * rate);  // 60 ms para trás a cada 10 ms: 6x em reverso
        for (int i = 0; i < rate / 100; ++i) position += follower.step(rate, 1.0, 1.0);
    }
    const double atRelease = follower.rate;
    follower.touch(false);
    check(follower.coasting && atRelease < -4.0, "backspin: solto girando a mais de 4x para trás, entra no giro solto");
    double minRate = 0.0;
    double back = 0.0;
    int samples = 0;
    while (follower.driving() && samples < rate * 3) {
        const double step = follower.step(rate, 1.0, 1.0);
        minRate = std::min(minRate, step);
        if (step < 0.0) back -= step;
        position += step;
        ++samples;
    }
    const double seconds = static_cast<double>(samples) / rate;
    check(!follower.driving(), "backspin: o giro solto termina sozinho");
    check(seconds > 0.8 && seconds < 2.0, "backspin: freia em 0,8 a 2 s (" + std::to_string(seconds) + " s)");
    check(back > 1.5 * rate, "backspin: depois de soltar ainda volta mais de 1,5 s da faixa");
    // Deck parado: o giro solto termina em zero.
    follower.touch(true);
    follower.move(-0.06 * rate);
    for (int i = 0; i < rate / 100; ++i) follower.step(rate, 0.0, 1.0);
    follower.touch(false);
    double last = -1.0;
    for (int i = 0; i < rate * 2; ++i) last = follower.step(rate, 0.0, 1.0);
    check(!follower.driving() && last == 0.0, "backspin: com o deck parado, para em zero");
    // Agarrar o prato no meio do giro solto para a faixa.
    follower.touch(true);
    follower.move(-0.06 * rate);
    for (int i = 0; i < rate / 100; ++i) follower.step(rate, 1.0, 1.0);
    follower.touch(false);
    follower.step(rate, 1.0, 1.0);
    follower.touch(true);
    check(follower.active && !follower.coasting && follower.step(rate, 1.0, 1.0) == 0.0, "backspin: segurar o prato para o giro solto");
}

// Tape STOP: soltar religa a fita mesmo com a grade parada (a grade vem do deck que a própria cena parou).
// Antes a soltura esperava a próxima batida dessa grade, que nunca chegava, e a faixa ficava parada.
void tapeStopReleases() {
    const int rate = 48000;
    deckbridge::MixerFx mix;
    mix.setSampleRate(rate);
    auto rmx = [&](bool holding) {
        mix.setRmx(true, 6, holding, 0.f, 1.f, 0, 0.25f, false, -1, 1.f, 1.f, 1.f, 1.f, 1.f, 1.f, false, 1, 120.f);
    };
    rmx(true);
    // Um compasso segurado: a grade anda junto com a velocidade do deck, que cai a zero.
    double beat = 10.0;
    for (int i = 0; i < rate * 2; ++i) {
        beat += mix.rateFor(1) / (rate * 0.5);
        mix.setDrumPhase(beat, true, true);
        float l = 0.1f, r = 0.1f;
        mix.processDeck(1, l, r);
    }
    check(mix.rateFor(1) < 0.1, "Tape STOP segurado: o deck quase parou");
    rmx(false);
    int samples = 0;
    while (mix.rateFor(1) < 0.999 && samples < rate) {
        beat += mix.rateFor(1) / (rate * 0.5);
        mix.setDrumPhase(beat, true, true);
        float l = 0.1f, r = 0.1f;
        mix.processDeck(1, l, r);
        ++samples;
    }
    check(samples < rate / 5, "Tape STOP solto: a fita volta a 1x em " + std::to_string(samples * 1000 / rate) + " ms");
    // Outra cena solta com a grade parada (deck freado): sai depois de no máximo 2 batidas do relógio.
    deckbridge::MixerFx echo;
    echo.setSampleRate(rate);
    echo.setRmx(true, 0, true, 0.f, 1.f, 0, 0.25f, false, -1, 1.f, 1.f, 1.f, 1.f, 1.f, 1.f, false, 0, 120.f);
    for (int i = 0; i < rate * 2; ++i) {
        echo.setDrumPhase(3.0, true, true);
        float l = 0.1f, r = 0.1f;
        echo.processDeck(0, l, r);
    }
    echo.setRmx(true, 0, false, 0.f, 1.f, 0, 0.25f, false, -1, 1.f, 1.f, 1.f, 1.f, 1.f, 1.f, false, 0, 120.f);
    int wait = 0;
    while (echo.scene.releasing && wait < rate * 20) {
        echo.setDrumPhase(3.0, true, true);
        float l = 0.1f, r = 0.1f;
        echo.processDeck(0, l, r);
        ++wait;
    }
    check(!echo.scene.releasing, "cena solta com a grade parada termina (" + std::to_string(wait / rate) + " s)");
}

int main() {
    tapeStopReleases();
    scratchFollows();
    backspinCoasts();
    rollBalanceCurve();
    spiralRises();
    for (int scene : {0, 2, 3, 4, 5, 7}) {
        const char *names[] = {"Echo UP", "", "Spiral UP", "Reverb UP", "Reverb DOWN", "Echo DOWN", "", "Dub ECHO"};
        releaseTail(scene, names[scene]);
    }
    releaseOnGrid();
    kickRiser();
    colorKinds();
    beatFxNew();
    std::printf("FAILS %d\n", failures);
    return failures == 0 ? 0 : 1;
}
