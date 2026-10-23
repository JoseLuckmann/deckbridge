// Renderiza o áudio de uma sessão de mixagem a partir do registro de operações que o modelo do app
// (android/app/src/test/.../session) resolveu a partir de mensagens MIDI da DDJ-400.
// Mesmo caminho de efeitos do APK (dsp.h, MixerFx) e mesma ordem de onAudioReady em engine.cpp.
// Não é o tablet e não é a DDJ-400.
//
// g++ -std=c++17 -O2 -I android/app/src/main/cpp -o ddj_session tools/ddj_session.cpp
// ./ddj_session deckA.wav deckB.wav ops.tsv saida.wav        (rodar na raiz do repositório)
//
// Formato do ops.tsv (campos separados por TAB):
//   grid  <deck> <origemSeg> <bpm>
//   <t>   load <deck> <arquivo>       troca a faixa do deck (índice na linha de comando, modo set)
//   <t>   gridat <deck> <origem> <bpm>
//   <t>   trim <deck> <linear>
//   <t>   colorfx <deck> <tipo>
//   <t>   pos <deck> <seg>            posição inicial do deck
//   <t>   play <deck> <0|1>
//   <t>   seek <deck> <seg>
//   <t>   loop <deck> <entradaSeg> <saidaSeg>   (saída <= entrada desliga o loop)
//   <t>   pitch <deck> <mult>
//   <t>   vol <deck> <0..1>
//   <t>   cross <0..1>
//   <t>   eq <deck> <low> <mid> <high>
//   <t>   filter <deck> <0..1>
//   <t>   beatfx <efeito> <on> <nivel0-127> <canal> <bpm> <batidas>
//   <t>   rmx <24 floats do rmxPayload>
//   <t>   end
#include "dsp.h"
#include "drum_load.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <sstream>
#include <string>
#include <vector>

namespace {

constexpr int kRate = 48000;

struct Audio {
    std::vector<float> left;
    std::vector<float> right;
};

struct Deck {
    const std::vector<float> *leftData = nullptr;
    const std::vector<float> *rightData = nullptr;
    double position = 0.0;
    double pitch = 1.0;
    float volume = 0.f;
    bool playing = false;
    double lastEmitted = 0.0;
    double gridOrigin = 0.0;
    double gridBpm = 0.0;
    double loopStart = -1.0;
    double loopEnd = -1.0;
    float trim = 1.f;  // ganho automático por faixa (o trim da análise de loudness)

    void read(double rateMul, float &l, float &r) {
        l = r = 0.f;
        if (leftData == nullptr || rightData == nullptr) return;
        const int frames = static_cast<int>(leftData->size());
        if (!playing || frames < 4) return;
        if (position < 0.0) position = 0.0;
        if (position >= frames - 2) {
            playing = false;
            return;
        }
        lastEmitted = position;
        l = deckbridge::readInterp(leftData->data(), frames, position) * trim;
        r = deckbridge::readInterp(rightData->data(), frames, position) * trim;
        position += pitch * rateMul;
        // Loop de batida: ao passar da saída, volta para a entrada mantendo a fração do passo (sem emenda).
        if (loopEnd > loopStart && position >= loopEnd) {
            position = loopStart + std::fmod(position - loopEnd, loopEnd - loopStart);
        }
    }
};

bool loadWav(const char *path, Audio &deck) {
    FILE *file = std::fopen(path, "rb");
    if (file == nullptr) return false;
    char riff[12];
    if (std::fread(riff, 1, 12, file) != 12) return false;
    int rate = 0, channels = 0, bits = 0;
    std::vector<unsigned char> data;
    while (true) {
        char id[4];
        uint32_t size = 0;
        if (std::fread(id, 1, 4, file) != 4 || std::fread(&size, 4, 1, file) != 1) break;
        if (std::memcmp(id, "fmt ", 4) == 0) {
            unsigned char fmt[64]{};
            const uint32_t take = std::min<uint32_t>(size, sizeof(fmt));
            if (std::fread(fmt, 1, take, file) != take) break;
            if (size > take) std::fseek(file, static_cast<long>(size - take), SEEK_CUR);
            channels = fmt[2] | (fmt[3] << 8);
            rate = fmt[4] | (fmt[5] << 8) | (fmt[6] << 16) | (fmt[7] << 24);
            bits = fmt[14] | (fmt[15] << 8);
            if (size & 1) std::fgetc(file);
        } else if (std::memcmp(id, "data", 4) == 0) {
            data.resize(size);
            if (size > 0 && std::fread(data.data(), 1, size, file) != size) return false;
            break;
        } else {
            std::fseek(file, static_cast<long>(size + (size & 1)), SEEK_CUR);
        }
    }
    std::fclose(file);
    if (rate != kRate || bits != 16 || (channels != 1 && channels != 2)) return false;
    const int frames = static_cast<int>(data.size() / (channels * 2));
    deck.left.resize(frames);
    deck.right.resize(frames);
    for (int i = 0; i < frames; ++i) {
        auto s = [&](int c) {
            const int at = (i * channels + c) * 2;
            return static_cast<int16_t>(data[at] | (data[at + 1] << 8)) / 32768.f;
        };
        deck.left[i] = s(0);
        deck.right[i] = channels == 2 ? s(1) : deck.left[i];
    }
    return true;
}

struct Op {
    double t = 0.0;
    std::string name;
    std::vector<double> args;
};

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

int main(int argc, char **argv) {
    // Modo antigo: deckA.wav deckB.wav ops.tsv saida.wav. Modo set: ops.tsv saida.wav faixa0.wav faixa1.wav ...
    const bool setMode = argc >= 4 && std::string(argv[1]).size() > 4 && std::string(argv[1]).substr(std::string(argv[1]).size() - 4) == ".tsv";
    if (!setMode && argc < 5) {
        std::fprintf(stderr, "uso: ddj_session deckA.wav deckB.wav ops.tsv saida.wav | ddj_session ops.tsv saida.wav faixa0.wav ...\n");
        return 2;
    }
    const char *opsPath = setMode ? argv[1] : argv[3];
    const char *outPath = setMode ? argv[2] : argv[4];
    std::vector<Audio> audios;
    const int firstFile = setMode ? 3 : 1;
    const int fileCount = setMode ? argc - 3 : 2;
    audios.resize(static_cast<size_t>(fileCount));
    for (int index = 0; index < fileCount; ++index) {
        if (!loadWav(argv[firstFile + index], audios[static_cast<size_t>(index)])) {
            std::fprintf(stderr, "WAV 48 kHz 16 bit esperado: %s\n", argv[firstFile + index]);
            return 2;
        }
    }
    Deck decks[2];
    for (int d = 0; d < 2 && d < fileCount; ++d) {
        decks[d].leftData = &audios[static_cast<size_t>(d)].left;
        decks[d].rightData = &audios[static_cast<size_t>(d)].right;
    }
    const bool drumsOk = loadDrums();
    std::fprintf(stderr, "bateria gravada: %s\n", drumsOk ? "ok" : "incompleta");
    std::vector<Op> ops;
    {
        FILE *file = std::fopen(opsPath, "r");
        if (file == nullptr) return 2;
        char line[2048];
        while (std::fgets(line, sizeof(line), file) != nullptr) {
            std::istringstream in(line);
            std::string first;
            in >> first;
            if (first.empty()) continue;
            Op op;
            if (first == "grid") {
                double deck = 0, origin = 0, bpm = 0;
                in >> deck >> origin >> bpm;
                const int d = static_cast<int>(deck);
                if (d >= 0 && d < 2) {
                    decks[d].gridOrigin = origin;
                    decks[d].gridBpm = bpm;
                }
                continue;
            }
            op.t = std::atof(first.c_str());
            in >> op.name;
            std::string token;
            while (in >> token) op.args.push_back(std::atof(token.c_str()));
            ops.push_back(op);
        }
        std::fclose(file);
    }
    std::stable_sort(ops.begin(), ops.end(), [](const Op &a, const Op &b) { return a.t < b.t; });
    double endAt = 0.0;
    for (const Op &op : ops) {
        if (op.name == "end") endAt = op.t;
    }
    if (endAt <= 0.0) endAt = ops.empty() ? 1.0 : ops.back().t + 2.0;

    deckbridge::MixerFx mix;
    deckbridge::PeakLimiter limit;
    mix.setSampleRate(kRate);
    mix.setColorKind(0, deckbridge::kColorFilter);
    mix.setColorKind(1, deckbridge::kColorFilter);
    mix.setFilter(0, 0.5f);
    mix.setFilter(1, 0.5f);
    float cross = 0.5f;
    const long total = static_cast<long>(endAt * kRate);
    std::vector<float> outL;
    std::vector<float> outR;
    outL.reserve(static_cast<size_t>(total));
    outR.reserve(static_cast<size_t>(total));
    size_t next = 0;
    long nan = 0;
    double peak = 0.0;
    // Picos por janela de 50 ms para os medidores da tela: canal 1, canal 2, master E, master D.
    std::string levelsPath = std::string(outPath) + ".levels.tsv";
    FILE *levels = std::fopen(levelsPath.c_str(), "w");
    float win[4] = {0.f, 0.f, 0.f, 0.f};
    for (long i = 0; i < total; ++i) {
        const double now = static_cast<double>(i) / kRate;
        while (next < ops.size() && ops[next].t <= now) {
            const Op &op = ops[next++];
            const auto &a = op.args;
            auto deckOf = [&]() { return std::clamp(a.empty() ? 0 : static_cast<int>(a[0]), 0, 1); };
            if (op.name == "pos" && a.size() >= 2) {
                decks[deckOf()].position = a[1] * kRate;
            } else if (op.name == "play" && a.size() >= 2) {
                decks[deckOf()].playing = a[1] > 0.5;
            } else if (op.name == "seek" && a.size() >= 2) {
                decks[deckOf()].position = std::max(0.0, a[1] * kRate);
            } else if (op.name == "load" && a.size() >= 2) {
                const int file = static_cast<int>(a[1]);
                if (file >= 0 && file < fileCount) {
                    Deck &target = decks[deckOf()];
                    target.leftData = &audios[static_cast<size_t>(file)].left;
                    target.rightData = &audios[static_cast<size_t>(file)].right;
                    target.position = 0.0;
                    target.playing = false;
                    target.pitch = 1.0;
                    target.loopStart = target.loopEnd = -1.0;
                }
            } else if (op.name == "trim" && a.size() >= 2) {
                decks[deckOf()].trim = static_cast<float>(a[1]);
            } else if (op.name == "gridat" && a.size() >= 3) {
                decks[deckOf()].gridOrigin = a[1];
                decks[deckOf()].gridBpm = a[2];
            } else if (op.name == "colorfx" && a.size() >= 2) {
                mix.setColorKind(deckOf(), static_cast<int>(a[1]));
            } else if (op.name == "loop" && a.size() >= 3) {
                decks[deckOf()].loopStart = a[1] * kRate;
                decks[deckOf()].loopEnd = a[2] > a[1] ? a[2] * kRate : -1.0;
            } else if (op.name == "pitch" && a.size() >= 2) {
                decks[deckOf()].pitch = a[1];
            } else if (op.name == "vol" && a.size() >= 2) {
                decks[deckOf()].volume = static_cast<float>(a[1]);
            } else if (op.name == "cross" && !a.empty()) {
                cross = std::clamp(static_cast<float>(a[0]), 0.f, 1.f);
            } else if (op.name == "eq" && a.size() >= 4) {
                mix.setEq(deckOf(), static_cast<float>(a[1]), static_cast<float>(a[2]), static_cast<float>(a[3]));
            } else if (op.name == "filter" && a.size() >= 2) {
                mix.setFilter(deckOf(), static_cast<float>(a[1]));
            } else if (op.name == "beatfx" && a.size() >= 6) {
                mix.setBeat(static_cast<int>(a[0]), a[1] > 0.5, std::clamp(static_cast<float>(a[2]) / 127.f, 0.f, 1.f),
                            static_cast<int>(a[3]), static_cast<float>(a[4]), static_cast<float>(a[5]));
            } else if (op.name == "rmx" && a.size() >= 24) {
                const float *v = nullptr;
                float values[24];
                for (int k = 0; k < 24; ++k) values[k] = static_cast<float>(a[k]);
                v = values;
                mix.setRmx(v[0] > 0.5f, static_cast<int>(v[1]), v[2] > 0.5f, v[3], v[4], static_cast<int>(v[5]), v[6], v[7] > 0.5f,
                           static_cast<int>(v[8]), v[9], v[10], v[11], v[12], v[13], v[14], v[15] > 0.5f, static_cast<int>(v[16]),
                           v[17], v[18], static_cast<int>(v[19]), static_cast<int>(v[20]), v[21] > 0.5f, v[22] > 0.5f,
                           static_cast<int>(v[23]));
            }
        }
        mix.beginSample();
        if (mix.release.active() && mix.release.kind != 1) {
            float ignoredL = 0.f;
            float ignoredR = 0.f;
            mix.release.process(ignoredL, ignoredR);
        }
        float al = 0.f, ar = 0.f, bl = 0.f, br = 0.f;
        decks[0].read(mix.rateFor(0), al, ar);
        decks[1].read(mix.rateFor(1), bl, br);
        const int gridDeck = std::clamp(mix.drumDeck, 0, 1);
        const Deck &grid = decks[gridDeck];
        const bool gridLive = grid.playing && grid.gridBpm > 1.0;
        mix.setDrumPhase(deckbridge::musicalBeat(grid.lastEmitted, kRate, grid.gridBpm, grid.gridOrigin), gridLive, true);
        mix.processDeck(0, al, ar);
        mix.processDeck(1, bl, br);
        const float sideA = std::min(1.f, 2.f * (1.f - cross));
        const float sideB = std::min(1.f, 2.f * cross);
        float masterL = al * sideA * decks[0].volume + bl * sideB * decks[1].volume;
        float masterR = ar * sideA * decks[0].volume + br * sideB * decks[1].volume;
        mix.processMaster(masterL, masterR);
        limit.process(masterL, masterR);
        if (!std::isfinite(masterL) || !std::isfinite(masterR)) {
            nan += 1;
            masterL = masterR = 0.f;
        }
        peak = std::max(peak, static_cast<double>(std::max(std::fabs(masterL), std::fabs(masterR))));
        win[0] = std::max(win[0], std::max(std::fabs(al), std::fabs(ar)));
        win[1] = std::max(win[1], std::max(std::fabs(bl), std::fabs(br)));
        win[2] = std::max(win[2], std::fabs(masterL));
        win[3] = std::max(win[3], std::fabs(masterR));
        if (levels != nullptr && (i + 1) % (kRate / 20) == 0) {
            std::fprintf(levels, "%.3f\t%.4f\t%.4f\t%.4f\t%.4f\n", now, win[0], win[1], win[2], win[3]);
            win[0] = win[1] = win[2] = win[3] = 0.f;
        }
        outL.push_back(masterL);
        outR.push_back(masterR);
    }
    // Fade curto no fim para não cortar seco.
    const int fade = kRate / 4;
    for (int i = 0; i < fade && i < static_cast<int>(outL.size()); ++i) {
        const float g = 1.f - static_cast<float>(i) / fade;
        outL[outL.size() - 1 - static_cast<size_t>(i)] *= 1.f - g;
        outR[outR.size() - 1 - static_cast<size_t>(i)] *= 1.f - g;
    }
    if (levels != nullptr) std::fclose(levels);
    writeWav(outPath, outL, outR);
    std::printf("segundos %.1f pico %.3f nan %ld ops %zu\n", endAt, peak, nan, ops.size());
    return nan == 0 ? 0 : 1;
}
