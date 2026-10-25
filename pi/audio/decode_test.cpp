// Decodificação no Pi: o mesmo sinal em WAV, FLAC e MP3 (codificados por lame/flac a partir do WAV) tem de voltar
// igual (WAV e FLAC, bit a bit) ou próximo (MP3, com perda: SNR depois de alinhar o atraso do codificador).
//   decode_test gerar ref.wav        → escreve um WAV 16 bit estéreo de 20 s a 44,1 kHz
//   decode_test comparar ref.wav x   → decodifica x e compara com ref.wav
#include "decode.h"

#include <cmath>
#include <cstdio>
#include <fstream>

using namespace deckbridge;

int main(int argc, char **argv) {
    if (argc >= 3 && std::string(argv[1]) == "gerar") {
        const int rate = 44100, seconds = 20;
        const uint32_t frames = static_cast<uint32_t>(rate * seconds);
        std::ofstream f(argv[2], std::ios::binary);
        auto w32 = [&](uint32_t v) { f.write(reinterpret_cast<const char *>(&v), 4); };
        auto w16 = [&](uint16_t v) { f.write(reinterpret_cast<const char *>(&v), 2); };
        f.write("RIFF", 4); w32(36 + frames * 4); f.write("WAVEfmt ", 8); w32(16); w16(1); w16(2); w32(rate); w32(rate * 4); w16(4); w16(16);
        f.write("data", 4); w32(frames * 4);
        uint32_t noise = 7;
        for (uint32_t i = 0; i < frames; ++i) {
            const double t = static_cast<double>(i) / rate, beat = std::fmod(t, 0.46875);
            double x = 0.5 * std::exp(-beat * 25) * std::sin(2 * M_PI * (50 + 80 * std::exp(-beat * 35)) * beat) + 0.2 * std::sin(2 * M_PI * 220 * t);
            noise = noise * 1664525u + 1013904223u;
            const double y = x + 0.03 * ((noise >> 9) / 4194304.0 - 1.0);
            w16(static_cast<uint16_t>(static_cast<int16_t>(std::lround(x * 30000)))); w16(static_cast<uint16_t>(static_cast<int16_t>(std::lround(y * 30000))));
        }
        return 0;
    }
    if (argc >= 3 && std::string(argv[1]) == "fuzz") {
        // Arquivos estragados (bits, cortes, cabeçalhos com tamanhos absurdos): nada pode ler fora do vetor.
        uint32_t rng = 77;
        auto next = [&](uint32_t n) { rng = rng * 1664525u + 1013904223u; return n ? (rng >> 8) % n : 0u; };
        long runs = 0, decoded = 0;
        for (int a = 2; a < argc; ++a) {
            const auto seed = library::readFile(argv[a]);
            if (!seed) continue;
            for (int round = 0; round < 60; ++round) {
                library::Bytes b(*seed);
                if (round % 3 == 0) b.resize(next(static_cast<uint32_t>(b.size())));
                for (int k = 0; k < 24 && !b.empty(); ++k) {
                    const size_t at = k < 8 ? next(std::min<uint32_t>(64, static_cast<uint32_t>(b.size()))) : next(static_cast<uint32_t>(b.size()));
                    b[at] = static_cast<uint8_t>(next(256));
                }
                audio::Pcm16 out;
                std::string err;
                decoded += audio::decodeBytes(b, argv[a], out, err);
                ++runs;
            }
        }
        std::printf("PASS %ld arquivos estragados (%ld ainda decodificaram) sem leitura fora da faixa\n", runs, decoded);
        return 0;
    }
    if (argc < 4) return 2;
    audio::Pcm16 ref, got;
    std::string error;
    if (!audio::decodeFile(argv[2], ref, error)) { std::printf("FAIL referência: %s\n", error.c_str()); return 1; }
    if (!audio::decodeFile(argv[3], got, error)) { std::printf("FAIL %s: %s\n", argv[3], error.c_str()); return 1; }
    // Alinha pelo atraso do codificador (MP3 põe silêncio no começo): melhor deslocamento até 4000 quadros.
    long bestLag = 0;
    double bestErr = 1e300;
    const long n = static_cast<long>(std::min(ref.left.size(), got.left.size())) - 8000;
    for (long lag = 0; lag <= 4000; ++lag) {
        double err = 0;
        for (long i = 4000; i < 4000 + 44100; ++i) {
            const double d = static_cast<double>(got.left[static_cast<size_t>(i + lag)]) - ref.left[static_cast<size_t>(i)];
            err += d * d;
        }
        if (err < bestErr) { bestErr = err; bestLag = lag; }
        if (err == 0) break;
    }
    double sig = 0, noise = 0;
    long same = 0;
    for (long i = 0; i < n; ++i) {
        for (int c = 0; c < 2; ++c) {
            const double r = c ? ref.right[static_cast<size_t>(i)] : ref.left[static_cast<size_t>(i)];
            const double g = c ? got.right[static_cast<size_t>(i + bestLag)] : got.left[static_cast<size_t>(i + bestLag)];
            sig += r * r;
            noise += (g - r) * (g - r);
            same += g == r;
        }
    }
    const double snr = noise > 0 ? 10 * std::log10(sig / noise) : 999.0;
    const double needed = std::atof(argv[1]);
    const bool ok = got.rate == ref.rate && snr >= needed;
    std::printf("%s %s: %d Hz, %zu quadros, atraso %ld, SNR %.1f dB (mínimo %.0f), %.2f%% das amostras idênticas\n", ok ? "PASS" : "FAIL", argv[3],
                got.rate, got.left.size(), bestLag, snr, needed, 100.0 * same / (2.0 * n));
    return ok ? 0 : 1;
}
