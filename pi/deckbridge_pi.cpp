// DeckBridge no Linux (Raspberry Pi OS 64 bit, ADR-0007 opção C), fase 1: o motor do app (engine.cpp, sem mudança)
// tocando pelo ALSA, sem tela. Carrega WAV nos dois decks (ou uma faixa sintética), toca e imprime a cada segundo
// a posição, os x-runs, as falhas de bloco, a CPU do processo e a RAM residente.
//
//   deckbridge_pi [--device hw:DDJ400] [--period 256] [--seconds 30] [--wav a.wav] [--wav b.wav] [--fx]
//                 [--midi DDJ] [--map ddj400-map.json]   (DDJ-400 pelo ALSA rawmidi; ADR-0008)
//
// O pendrive do rekordbox é só leitura: este programa só lê os arquivos que recebe e não grava nada.

#define DECKBRIDGE_NO_JNI 1
#include "../android/app/src/main/cpp/engine.cpp"
#include "control/deck_control.h"
#include "control/midi_in.h"

#include <sys/mman.h>
#include <sys/resource.h>
#include <unistd.h>

#include <cstdio>
#include <cstring>
#include <fstream>

namespace {

struct Pcm16 {
    std::vector<int16_t> left;
    std::vector<int16_t> right;
    int rate = 0;
};

uint32_t le32(const uint8_t *p) { return p[0] | (p[1] << 8) | (p[2] << 16) | (static_cast<uint32_t>(p[3]) << 24); }
uint16_t le16(const uint8_t *p) { return static_cast<uint16_t>(p[0] | (p[1] << 8)); }

/** WAV PCM 16/24/32 bit ou float 32, mono ou estéreo, direto para int16 (sem uma cópia float da faixa). */
bool readWav(const char *path, Pcm16 &out, std::string &error) {
    std::ifstream file(path, std::ios::binary);
    if (!file) {
        error = "não abri o arquivo";
        return false;
    }
    std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
    if (bytes.size() < 44 || std::memcmp(bytes.data(), "RIFF", 4) != 0 || std::memcmp(bytes.data() + 8, "WAVE", 4) != 0) {
        error = "não é WAV";
        return false;
    }
    int format = 0, channels = 0, bits = 0;
    size_t at = 12;
    const uint8_t *data = nullptr;
    size_t dataSize = 0;
    while (at + 8 <= bytes.size()) {
        const uint32_t size = le32(&bytes[at + 4]);
        const uint8_t *body = &bytes[at + 8];
        const size_t room = bytes.size() - (at + 8);
        if (std::memcmp(&bytes[at], "fmt ", 4) == 0 && size >= 16 && room >= 16) {
            format = le16(body);
            channels = le16(body + 2);
            out.rate = static_cast<int>(le32(body + 4));
            bits = le16(body + 14);
            if (format == 0xFFFE && size >= 26 && room >= 26) format = le16(body + 24);
        } else if (std::memcmp(&bytes[at], "data", 4) == 0) {
            data = body;
            dataSize = std::min<size_t>(size, room);
            break;
        }
        at += 8 + size + (size & 1);
    }
    if (data == nullptr || channels < 1 || channels > 8 || out.rate <= 0) {
        error = "WAV sem fmt/data";
        return false;
    }
    const size_t width = static_cast<size_t>(bits / 8);
    if (!((format == 1 && (bits == 16 || bits == 24 || bits == 32)) || (format == 3 && bits == 32))) {
        error = "formato não suportado (PCM 16/24/32 ou float 32)";
        return false;
    }
    const size_t frames = dataSize / (width * static_cast<size_t>(channels));
    out.left.resize(frames);
    out.right.resize(frames);
    auto sample = [&](const uint8_t *p) -> float {
        if (format == 3) {
            float f;
            std::memcpy(&f, p, 4);
            return f;
        }
        if (bits == 16) return static_cast<float>(static_cast<int16_t>(le16(p))) / 32768.f;
        if (bits == 24) {
            int32_t v = p[0] | (p[1] << 8) | (p[2] << 16);
            if (v & 0x800000) v |= ~0xFFFFFF;
            return static_cast<float>(v) / 8388608.f;
        }
        return static_cast<float>(static_cast<int32_t>(le32(p))) / 2147483648.f;
    };
    for (size_t i = 0; i < frames; ++i) {
        const uint8_t *frame = data + i * width * static_cast<size_t>(channels);
        const float l = sample(frame);
        const float r = channels > 1 ? sample(frame + width) : l;
        out.left[i] = deckbridge::toPcm16(l);
        out.right[i] = deckbridge::toPcm16(r);
    }
    return true;
}

/** Faixa sintética (bumbo, baixo e chimbal a 128 BPM) para testar sem música. */
Pcm16 synthTrack(int seconds, double bpm, double bassHz) {
    Pcm16 out;
    out.rate = 44100;
    const size_t frames = static_cast<size_t>(seconds) * 44100u;
    out.left.resize(frames);
    out.right.resize(frames);
    const double beat = 60.0 / bpm;
    uint32_t noise = 1u;
    for (size_t i = 0; i < frames; ++i) {
        const double t = static_cast<double>(i) / 44100.0;
        const double in = std::fmod(t, beat);
        double x = 0.6 * std::exp(-in * 28.0) * std::sin(2.0 * M_PI * (48.0 + 90.0 * std::exp(-in * 40.0)) * in);
        x += 0.22 * std::sin(2.0 * M_PI * bassHz * t);
        noise = noise * 1664525u + 1013904223u;
        const double off = std::fmod(t + beat / 2, beat);
        x += 0.08 * (static_cast<double>(noise >> 9) / 4194304.0 - 1.0) * std::exp(-off * 70.0);
        out.left[i] = deckbridge::toPcm16(static_cast<float>(x));
        out.right[i] = out.left[i];
    }
    return out;
}

long rssKb() {
    std::ifstream statm("/proc/self/statm");
    long pages = 0, resident = 0;
    statm >> pages >> resident;
    return resident * (sysconf(_SC_PAGESIZE) / 1024);
}

double cpuSeconds() {
    rusage usage{};
    getrusage(RUSAGE_SELF, &usage);
    return usage.ru_utime.tv_sec + usage.ru_stime.tv_sec + (usage.ru_utime.tv_usec + usage.ru_stime.tv_usec) / 1e6;
}

std::string field(const std::string &stats, const std::string &key) {
    const auto at = stats.find(key + "=");
    if (at == std::string::npos) return "?";
    const auto end = stats.find('\n', at);
    return stats.substr(at + key.size() + 1, end - at - key.size() - 1);
}

}  // namespace

int main(int argc, char **argv) {
    int seconds = 20;
    bool fx = false;
    std::vector<std::string> wavs;
    std::string midiFilter;
    bool midi = false;
    std::string mapPath = "ddj400-map.json";
    for (int i = 1; i < argc; ++i) {
        const std::string arg = argv[i];
        auto next = [&]() -> std::string { return i + 1 < argc ? argv[++i] : ""; };
        if (arg == "--device") oboe::AlsaConfig::device = next();
        else if (arg == "--period") oboe::AlsaConfig::periodFrames = std::max(32, std::atoi(next().c_str()));
        else if (arg == "--periods") oboe::AlsaConfig::periods = std::max(2, std::atoi(next().c_str()));
        else if (arg == "--seconds") seconds = std::max(1, std::atoi(next().c_str()));
        else if (arg == "--wav") wavs.push_back(next());
        else if (arg == "--fx") fx = true;
        else if (arg == "--midi") {
            midi = true;
            midiFilter = next();
        } else if (arg == "--map") mapPath = next();
        else {
            std::fprintf(stderr, "uso: %s [--device NOME] [--period N] [--periods N] [--seconds N] [--wav a.wav] [--wav b.wav] [--fx] [--midi FILTRO] [--map ARQ]\n", argv[0]);
            return 2;
        }
    }
    // Sem paginação no meio do set: o que o motor usa fica na RAM (precisa de memlock; sem ele só avisa).
    if (mlockall(MCL_CURRENT | MCL_FUTURE) != 0) std::fprintf(stderr, "aviso: mlockall recusado (%s); siga sem travar a RAM\n", std::strerror(errno));

    ToneEngine &engine = ::engine();
    for (int deck = 0; deck < 2; ++deck) {
        Pcm16 track;
        if (deck < static_cast<int>(wavs.size())) {
            std::string error;
            if (!readWav(wavs[static_cast<size_t>(deck)].c_str(), track, error)) {
                std::fprintf(stderr, "deck %d: %s: %s\n", deck + 1, wavs[static_cast<size_t>(deck)].c_str(), error.c_str());
                return 1;
            }
        } else {
            track = synthTrack(std::max(seconds + 5, 30), 128.0, deck == 0 ? 55.0 : 49.0);
        }
        engine.installDeck(deck, std::move(track.left), std::move(track.right), track.rate, 128.f, 0.f, nullptr, 0, -1.f, -1.f);
    }
    const std::string opened = engine.start(0, 3);
    std::printf("aparelho %s: aberto=%s canais=%s taxa=%s latência=%s ms formato=%s erro=%s\n",
                oboe::AlsaConfig::deviceName().c_str(), field(opened, "opened").c_str(), field(opened, "opened_channels").c_str(),
                field(opened, "sample_rate").c_str(), field(opened, "latency_ms").c_str(),
                oboe::AlsaConfig::lastFormat.load(), field(opened, "error").c_str());
    if (field(opened, "opened") != "true") return 1;
    engine.setMix(1.f, 1.f, 1.f, 1.f, 0.5f, false, false);
    engine.transport(0, true);
    engine.transport(1, true);
    if (fx) engine.setBeatFx(15, true, 90, 2, 128.f, 1.f);  // SHIMMER no master: carga pesada de teste.
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    // DDJ-400: bytes do rawmidi → mapa oficial → ação → motor, na thread do MIDI (a mesma regra do app Android).
    deckbridge::control::MidiIn midiIn;
    deckbridge::control::DeckControl<ToneEngine> control(engine);
    deckbridge::control::TempoAssembler tempo;
    std::atomic<long> midiMessages{0};
    if (midi) {
        static const deckbridge::control::MidiMap map = deckbridge::control::MidiMap::fromFile(mapPath);
        if (map.all().empty()) {
            std::fprintf(stderr, "mapa MIDI vazio ou ausente: %s\n", mapPath.c_str());
            return 1;
        }
        control.setBpm(0, 128.0);
        control.setBpm(1, 128.0);
        const std::string port = midiIn.open(midiFilter, [&](const deckbridge::control::MidiMessage &m) {
            midiMessages.fetch_add(1);
            if (auto action = deckbridge::control::interpretPerformance(map.lookup(m.status, m.data1), m.data2, tempo)) control.apply(*action);
        });
        std::printf("MIDI: %s\n", port.empty() ? "nenhuma porta de entrada encontrada (DECKBRIDGE_MIDI_DEVICE ou --midi FILTRO)" : port.c_str());
    }
    std::printf("tempo real: %s\n", oboe::AlsaConfig::lastRealtime.load() ? "sim (SCHED_FIFO)" : "não (sem permissão de rtprio)");
    double lastCpu = cpuSeconds();
    auto lastWall = std::chrono::steady_clock::now();
    for (int s = 1; s <= seconds; ++s) {
        std::this_thread::sleep_for(std::chrono::seconds(1));
        const double cpu = cpuSeconds();
        const auto wall = std::chrono::steady_clock::now();
        const double share = (cpu - lastCpu) / std::chrono::duration<double>(wall - lastWall).count();
        lastCpu = cpu;
        lastWall = wall;
        const std::string st = engine.stats();
        std::printf("t=%3ds deck1=%6.2fs deck2=%6.2fs xruns=%s falhas=%d cpu=%5.1f%% rss=%ld KB midi=%ld\n", s, engine.positionSeconds(0),
                    engine.positionSeconds(1), field(st, "xruns").c_str(), engine.glitchCount(), share * 100.0, rssKb(), midiMessages.load());
        std::fflush(stdout);
    }
    engine.stop();
    return 0;
}
