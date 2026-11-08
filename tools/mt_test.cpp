// Mede o Master Tempo: o antigo (leitura na velocidade + deslocador de tom de dois atrasos) contra o WSOLA de dsp.h.
// Não é o tablet. g++ -std=c++17 -O2 -I android/app/src/main/cpp -o mt_test tools/mt_test.cpp
// ./mt_test sintetico              métricas num sinal harmônico (afinação, pureza, energia)
// ./mt_test faixa.wav pitch saida  renderiza 20 s a partir de 30 s: saida-antigo.wav e saida-novo.wav (WAV 48 kHz 16 bit)
#include "dsp.h"

#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace {
constexpr int kRate = 48000;

struct Pcm {
    std::vector<float> l, r;
};

bool loadWav(const char *path, Pcm &out) {
    FILE *f = std::fopen(path, "rb");
    if (!f) return false;
    char riff[12];
    if (std::fread(riff, 1, 12, f) != 12) return false;
    int channels = 0, rate = 0, bits = 0;
    std::vector<unsigned char> data;
    while (true) {
        char id[4];
        uint32_t size = 0;
        if (std::fread(id, 1, 4, f) != 4 || std::fread(&size, 4, 1, f) != 1) break;
        if (!std::memcmp(id, "fmt ", 4)) {
            unsigned char fmt[64]{};
            const uint32_t take = std::min<uint32_t>(size, sizeof(fmt));
            if (std::fread(fmt, 1, take, f) != take) break;
            if (size > take) std::fseek(f, static_cast<long>(size - take), SEEK_CUR);
            channels = fmt[2] | (fmt[3] << 8);
            rate = fmt[4] | (fmt[5] << 8) | (fmt[6] << 16) | (fmt[7] << 24);
            bits = fmt[14] | (fmt[15] << 8);
        } else if (!std::memcmp(id, "data", 4)) {
            data.resize(size);
            if (std::fread(data.data(), 1, size, f) != size) return false;
            break;
        } else {
            std::fseek(f, static_cast<long>(size + (size & 1)), SEEK_CUR);
        }
    }
    std::fclose(f);
    if (rate != kRate || bits != 16 || (channels != 1 && channels != 2)) return false;
    const size_t frames = data.size() / (channels * 2);
    out.l.resize(frames);
    out.r.resize(frames);
    for (size_t i = 0; i < frames; ++i) {
        auto s = [&](int c) { return static_cast<int16_t>(data[(i * channels + c) * 2] | (data[(i * channels + c) * 2 + 1] << 8)) / 32768.f; };
        out.l[i] = s(0);
        out.r[i] = channels == 2 ? s(1) : out.l[i];
    }
    return true;
}

void writeWav(const std::string &path, const std::vector<float> &l, const std::vector<float> &r) {
    FILE *f = std::fopen(path.c_str(), "wb");
    if (!f) return;
    const uint32_t frames = static_cast<uint32_t>(l.size());
    const uint32_t bytes = frames * 4;
    auto u32 = [&](uint32_t v) { std::fwrite(&v, 4, 1, f); };
    auto u16 = [&](uint16_t v) { std::fwrite(&v, 2, 1, f); };
    std::fwrite("RIFF", 1, 4, f); u32(36 + bytes); std::fwrite("WAVEfmt ", 1, 8, f);
    u32(16); u16(1); u16(2); u32(kRate); u32(kRate * 4); u16(4); u16(16);
    std::fwrite("data", 1, 4, f); u32(bytes);
    for (uint32_t i = 0; i < frames; ++i) {
        u16(static_cast<uint16_t>(static_cast<int16_t>(std::max(-1.f, std::min(1.f, l[i])) * 32767.f)));
        u16(static_cast<uint16_t>(static_cast<int16_t>(std::max(-1.f, std::min(1.f, r[i])) * 32767.f)));
    }
    std::fclose(f);
}

// pitch = velocidade da faixa; shiftSemis = Key Shift. keyLock mantém o tom do arquivo.
void renderOld(const Pcm &in, double start, int frames, double pitch, int shiftSemis, Pcm &out) {
    deckbridge::PitchShift sl, sr;
    double position = start * kRate;
    const int n = static_cast<int>(in.l.size());
    float ratio = static_cast<float>(1.0 / pitch) * std::pow(2.f, shiftSemis / 12.f);
    for (int i = 0; i < frames; ++i) {
        float a = deckbridge::readInterp(in.l.data(), n, position);
        float b = deckbridge::readInterp(in.r.data(), n, position);
        if (std::fabs(ratio - 1.f) > 0.0008f) {
            a = sl.process(a, ratio);
            b = sr.process(b, ratio);
        }
        out.l.push_back(a);
        out.r.push_back(b);
        position += pitch;
    }
}

double renderNew(const Pcm &in, double start, int frames, double pitch, int shiftSemis, Pcm &out) {
    deckbridge::Wsola w;
    double position = start * kRate;
    const int n = static_cast<int>(in.l.size());
    const double rate = std::pow(2.0, shiftSemis / 12.0);
    auto read = [&](double pos, float &a, float &b) {
        a = deckbridge::readInterp(in.l.data(), n, pos);
        b = deckbridge::readInterp(in.r.data(), n, pos);
    };
    const auto began = std::chrono::steady_clock::now();
    for (int i = 0; i < frames; ++i) {
        float a, b;
        w.next(read, position, 1.0, rate, pitch, false, a, b);
        out.l.push_back(a);
        out.r.push_back(b);
        position += pitch;
    }
    return std::chrono::duration<double>(std::chrono::steady_clock::now() - began).count();
}

// Fração da energia que cai nos harmônicos de f0 (±2 bins), com janela de Hann, sobre `frames` amostras.
double harmonicPurity(const std::vector<float> &x, int from, int frames, double f0, double &rms) {
    std::vector<double> w(frames);
    double total = 0.0;
    for (int i = 0; i < frames; ++i) {
        const double h = 0.5 - 0.5 * std::cos(2.0 * M_PI * i / frames);
        w[i] = x[from + i] * h;
        total += w[i] * w[i];
    }
    rms = std::sqrt(total / frames);
    double inside = 0.0;
    for (int k = 1; k <= 10; ++k) {
        const double f = f0 * k;
        for (int bin = -2; bin <= 2; ++bin) {
            const double freq = f + bin * static_cast<double>(kRate) / frames;
            double re = 0.0, im = 0.0;
            for (int i = 0; i < frames; ++i) {
                re += w[i] * std::cos(2.0 * M_PI * freq * i / kRate);
                im -= w[i] * std::sin(2.0 * M_PI * freq * i / kRate);
            }
            inside += (re * re + im * im) / frames * 2.0;
        }
    }
    return std::min(1.0, inside / (total * 1.0 + 1e-12) / 1.0);
}

double detectF0(const std::vector<float> &x, int from, int frames) {
    // Autocorrelação com refinamento parabólico, 100 a 500 Hz.
    int bestLag = 0;
    double best = -1e30;
    std::vector<double> corr(kRate / 100 + 2, 0.0);
    for (int lag = kRate / 500; lag <= kRate / 100; ++lag) {
        double acc = 0.0;
        for (int i = 0; i + lag < frames; ++i) acc += static_cast<double>(x[from + i]) * x[from + i + lag];
        corr[lag] = acc;
        if (acc > best) { best = acc; bestLag = lag; }
    }
    const double a = corr[bestLag - 1], b = corr[bestLag], c = corr[bestLag + 1];
    const double denom = a - 2 * b + c;
    const double shift = std::fabs(denom) < 1e-12 ? 0.0 : 0.5 * (a - c) / denom;
    return kRate / (bestLag + shift);
}
}  // namespace

int main(int argc, char **argv) {
    if (argc >= 2 && !std::strcmp(argv[1], "sintetico")) {
        const double f0 = 220.0;
        Pcm tone;
        for (int i = 0; i < kRate * 20; ++i) {
            double v = 0.0;
            for (int k = 1; k <= 10; ++k) v += std::sin(2.0 * M_PI * f0 * k * i / kRate + 0.4 * k) / k;
            // Envoltória suave de amplitude (notas) para não ser um tom estático.
            const double env = 0.7 + 0.3 * std::sin(2.0 * M_PI * 1.3 * i / kRate);
            tone.l.push_back(static_cast<float>(0.3 * v * env));
            tone.r.push_back(tone.l.back());
        }
        struct Case { double pitch; int shift; };
        const Case cases[] = {{1.0775, 0}, {0.94, 0}, {1.20, 0}, {1.0, 2}, {1.0, -5}, {1.06, 3}};
        std::printf("%-18s %10s %10s %10s %10s %10s %10s\n", "caso", "f0 alvo", "f0 antigo", "f0 novo", "pureza A", "pureza N", "rms N/A");
        int failures = 0;
        for (const Case &c : cases) {
            const double target = f0 * std::pow(2.0, c.shift / 12.0);
            Pcm oldOut, newOut;
            renderOld(tone, 2.0, kRate * 6, c.pitch, c.shift, oldOut);
            renderNew(tone, 2.0, kRate * 6, c.pitch, c.shift, newOut);
            const int from = kRate, len = kRate * 2;
            double rmsOld = 0, rmsNew = 0;
            const double f0Old = detectF0(oldOut.l, from, len);
            const double f0New = detectF0(newOut.l, from, len);
            const double pOld = harmonicPurity(oldOut.l, from, len / 4, target, rmsOld);
            const double pNew = harmonicPurity(newOut.l, from, len / 4, target, rmsNew);
            char name[64];
            std::snprintf(name, sizeof(name), "p %.4f key %+d", c.pitch, c.shift);
            std::printf("%-18s %10.2f %10.2f %10.2f %10.3f %10.3f %10.3f\n", name, target, f0Old, f0New, pOld, pNew, rmsNew / (rmsOld + 1e-12));
            if (std::fabs(f0New / target - 1.0) > 0.004) { std::printf("  FALHA afinação\n"); ++failures; }
            if (pNew < 0.9 || pNew + 1e-9 < pOld) { std::printf("  FALHA pureza\n"); ++failures; }
            if (rmsNew / (rmsOld + 1e-12) < 0.7 || rmsNew / (rmsOld + 1e-12) > 1.4) { std::printf("  FALHA nível\n"); ++failures; }
        }
        // Custo: 60 s de áudio com a pior razão (key shift e tempo).
        Pcm sink;
        const double secs = renderNew(tone, 2.0, kRate * 12, 1.2, 4, sink);
        std::printf("custo: 12 s de áudio em %.3f s (%.2f%% de um núcleo, um deck)\n", secs, secs / 12.0 * 100.0);
        return failures == 0 ? 0 : 1;
    }
    if (argc >= 4) {
        Pcm in;
        if (!loadWav(argv[1], in)) { std::fprintf(stderr, "WAV 48 kHz 16 bit esperado\n"); return 2; }
        const double pitch = std::atof(argv[2]);
        const int shift = argc >= 5 ? std::atoi(argv[4]) : 0;
        Pcm a, b;
        renderOld(in, 30.0, kRate * 20, pitch, shift, a);
        renderNew(in, 30.0, kRate * 20, pitch, shift, b);
        writeWav(std::string(argv[3]) + "-antigo.wav", a.l, a.r);
        writeWav(std::string(argv[3]) + "-novo.wav", b.l, b.r);
        return 0;
    }
    std::fprintf(stderr, "uso: mt_test sintetico | mt_test faixa.wav pitch saida [semitons]\n");
    return 2;
}
